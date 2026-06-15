#!/usr/bin/env python3
"""
DetectionTestApp 专用检测推理服务器

独立于 Green 的现网服务，使用 2-class 模型（face_sheet + tape）。
部署在 Orange Pi 5 Pro，端口 8002。

API:
  POST /detect  mulitpart/form-data {image: JPEG}
  → {success, detections[{class_id, class_name, confidence, x, y, w, h}],
     inference_ms, width, height}

依赖: pip install fastapi uvicorn numpy opencv-python
       rknnlite (在 Orange Pi venv 中已安装)
"""
import sys
import json
import time
from pathlib import Path

import numpy as np
import cv2
from fastapi import FastAPI, UploadFile, File
import uvicorn


# 模型输出已含 sigmoid，无需额外激活

# ============================================================
# 3-class 配置
# ============================================================
CLASS_NAMES = ['face_sheet', 'tape']
NUM_CLASSES = len(CLASS_NAMES)  # 2
CONF_THRESH = 0.3
CONF_STRICT = 0.50   # 至少有一个检测超过此阈值才保留结果（抑制手机实拍背景噪声）
IOU_THRESH = 0.45
INPUT_SIZE = (1280, 1280)

# ============================================================
# RKNN 推理封装（基于 rknn_inference_v3.py，适配 3-class）
# ============================================================
class RKNNYOLO:
    def __init__(self, model_path):
        from rknnlite.api import RKNNLite
        self.rknn = RKNNLite()
        ret = self.rknn.load_rknn(model_path)
        if ret != 0:
            raise RuntimeError(f"加载 RKNN 模型失败: {model_path}")
        ret = self.rknn.init_runtime()
        if ret != 0:
            raise RuntimeError("RKNN runtime 初始化失败")
        self.nc = NUM_CLASSES
        self.names = CLASS_NAMES
        print(f"[RKNN] Model loaded: {model_path}")
        print(f"[RKNN] Classes ({self.nc}): {self.names}")

    def predict(self, image_bytes):
        """输入 JPEG bytes → Results"""
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            return []

        orig_h, orig_w = img.shape[:2]

        # Letterbox 预处理
        padded, ratio, (dw, dh) = self._letterbox(img, INPUT_SIZE)
        padded = padded.astype(np.float32) / 255.0
        padded = np.transpose(padded, (2, 0, 1)).reshape(1, 3, *INPUT_SIZE)

        # 推理
        outputs = self.rknn.inference(inputs=[padded])
        output = np.squeeze(outputs[0], axis=0)  # (7, 33600)
        output = np.transpose(output)  # (33600, 7)

        # 解码 (cx, cy, w, h, conf_fs, conf_tape, conf_clean)
        cx = output[:, 0]
        cy = output[:, 1]
        bw = output[:, 2]
        bh = output[:, 3]
        scores = output[:, 4:]  # (33600, 3) 模型输出已含 sigmoid

        max_scores = scores.max(axis=1)
        max_cls = scores.argmax(axis=1)

        mask = max_scores > CONF_THRESH
        if not mask.any() or max_scores.max() < CONF_STRICT:
            # 最高置信度也不够 -> 干净纸板或背景，返回空
            return [Results(Boxes([]))]

        cx, cy, bw, bh = cx[mask], cy[mask], bw[mask], bh[mask]
        max_scores = max_scores[mask]
        max_cls = max_cls[mask]

        # 坐标还原（padded 像素空间 → 原图像素空间）
        # 模型输出 cx,cy,w,bh 已在无符号 0~1280 像素空间，不需要再乘 INPUT_SIZE
        cx = (cx - dw) / ratio[0]
        cy = (cy - dh) / ratio[1]
        bw = bw / ratio[0]
        bh = bh / ratio[1]

        # cx,cy,w,h → x1,y1,x2,y2
        x1 = np.clip(cx - bw / 2, 0, orig_w)
        y1 = np.clip(cy - bh / 2, 0, orig_h)
        x2 = np.clip(cx + bw / 2, 0, orig_w)
        y2 = np.clip(cy + bh / 2, 0, orig_h)

        # NMS
        boxes_list = []
        for i in range(len(x1)):
            if x2[i] > x1[i] and y2[i] > y1[i]:
                boxes_list.append({
                    'xyxy': [x1[i], y1[i], x2[i], y2[i]],
                    'conf': float(max_scores[i]),
                    'cls': int(max_cls[i]),
                })

        kept = self._nms(boxes_list, IOU_THRESH)
        return [Results(Boxes([Box(b) for b in kept]))]

    @staticmethod
    def _letterbox(im, new_shape=(640, 640), color=(114, 114, 114)):
        shape = im.shape[:2]
        r = min(new_shape[0] / shape[0], new_shape[1] / shape[1])
        new_unpad = (int(round(shape[1] * r)), int(round(shape[0] * r)))
        dw = (new_shape[1] - new_unpad[0]) / 2
        dh = (new_shape[0] - new_unpad[1]) / 2
        if shape[::-1] != new_unpad:
            im = cv2.resize(im, new_unpad, interpolation=cv2.INTER_LINEAR)
        top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
        left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
        im = cv2.copyMakeBorder(im, top, bottom, left, right,
                                cv2.BORDER_CONSTANT, value=color)
        return im, (r, r), (dw, dh)

    @staticmethod
    def _nms(boxes, iou_thresh):
        if not boxes:
            return []
        boxes = sorted(boxes, key=lambda b: b['conf'], reverse=True)
        keep = []
        while boxes:
            best = boxes.pop(0)
            keep.append(best)
            boxes = [b for b in boxes
                     if RKNNYOLO._iou(b['xyxy'], best['xyxy']) <= iou_thresh]
        return keep

    @staticmethod
    def _iou(a, b):
        ax1, ay1, ax2, ay2 = a
        bx1, by1, bx2, by2 = b
        ix1, iy1 = max(ax1, bx1), max(ay1, by1)
        ix2, iy2 = min(ax2, bx2), min(ay2, by2)
        inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
        area_a = (ax2 - ax1) * (ay2 - ay1)
        area_b = (bx2 - bx1) * (by2 - by1)
        union = area_a + area_b - inter
        return inter / union if union > 0 else 0


class Box:
    def __init__(self, data):
        self._xyxy = np.array([data['xyxy']], dtype=np.float32)
        self._conf = np.array([data['conf']], dtype=np.float32)
        self._cls = np.array([data['cls']], dtype=np.int32)

    @property
    def xyxy(self): return self._xyxy
    @property
    def conf(self): return self._conf
    @property
    def cls(self): return self._cls


class Boxes:
    def __init__(self, boxes):
        self._boxes = boxes
    def __len__(self): return len(self._boxes)
    def __iter__(self): return iter(self._boxes)
    def __getitem__(self, idx): return self._boxes[idx]
    @property
    def xyxy(self):
        return np.vstack([b.xyxy for b in self._boxes]) if self._boxes else np.empty((0, 4))
    @property
    def conf(self):
        return np.array([b.conf[0] for b in self._boxes]) if self._boxes else np.empty(0)
    @property
    def cls(self):
        return np.array([b.cls[0] for b in self._boxes]) if self._boxes else np.empty(0)


class Results:
    def __init__(self, boxes): self._boxes = boxes
    @property
    def boxes(self): return self._boxes


# ============================================================
# FastAPI 服务
# ============================================================
MODEL_PATH = str(Path.home() / "project" / "cardboard-recycle" / "best_fp16.rknn")

app = FastAPI(title="DetectionTestApp 推理服务")

@app.on_event("startup")
def load_model():
    global rknn, YOLO_AVAILABLE
    try:
        rknn = RKNNYOLO(MODEL_PATH)
        YOLO_AVAILABLE = True
        print("[Server] 模型加载成功")
    except Exception as e:
        print(f"[Server] 模型加载失败: {e}")
        YOLO_AVAILABLE = False
        rknn = None


@app.post("/detect")
async def detect(image: UploadFile = File(...)):
    if not YOLO_AVAILABLE or rknn is None:
        return {"success": False, "error": "Model not loaded"}
    
    img_bytes = await image.read()
    if not img_bytes:
        return {"success": False, "error": "Empty image"}
    
    # 获取原图尺寸
    nparr = np.frombuffer(img_bytes, np.uint8)
    orig = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if orig is None:
        return {"success": False, "error": "Invalid image"}
    orig_h, orig_w = orig.shape[:2]
    
    t0 = time.time()
    results = rknn.predict(img_bytes)
    elapsed_ms = round((time.time() - t0) * 1000, 1)
    
    detections = []
    if results and len(results) > 0:
        boxes = results[0].boxes
        if boxes is not None and len(boxes) > 0:
            all_xyxy = boxes.xyxy
            all_conf = boxes.conf
            all_cls = boxes.cls
            for i in range(len(all_xyxy)):
                x1, y1, x2, y2 = all_xyxy[i]
                box_w = x2 - x1
                box_h = y2 - y1
                cx = (x1 + x2) / 2.0
                cy = (y1 + y2) / 2.0
                cls_id = int(all_cls[i])
                cls_name = CLASS_NAMES[cls_id] if cls_id < NUM_CLASSES else "unknown"
                detections.append({
                    "class_id": cls_id,
                    "class_name": cls_name,
                    "confidence": round(float(all_conf[i]), 4),
                    "x": round(float(cx / orig_w), 6),
                    "y": round(float(cy / orig_h), 6),
                    "w": round(float(box_w / orig_w), 6),
                    "h": round(float(box_h / orig_h), 6),
                })
    
    return {
        "success": True,
        "detections": detections,
        "inference_ms": elapsed_ms,
        "width": orig_w,
        "height": orig_h,
    }


@app.get("/health")
def health():
    return {
        "status": "ok",
        "yolo": YOLO_AVAILABLE,
        "model": "best_fp16.rknn",
        "classes": CLASS_NAMES,
    }


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8002
    print(f"🧪 DetectionTestApp 推理服务 http://0.0.0.0:{port}")
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
