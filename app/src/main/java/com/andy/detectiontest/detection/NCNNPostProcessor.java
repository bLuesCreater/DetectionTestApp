package com.andy.detectiontest.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Post-processor for NCNN YOLO (640×640 input, multi-class).
 *
 * NCNN output: flat float[] = stride × numDetections (feature-major)
 * Per detection: [cx, cy, w, h, conf_cls0, conf_cls1, ...]
 *   cx,cy,w,h are pixel-level coords in padded space
 *   conf values are sigmoid'd (0~1)
 */
public class NCNNPostProcessor {

    public static final int INPUT_SIZE = 640;
    private static final int DEFAULT_NUM_DETECTIONS = 8400;
    private static final int DEFAULT_STRIDE = 6;
    private static final int DEFAULT_NUM_CLASSES = 2;

    private static final float DEFAULT_CONF_THRESH = 0.50f;
    private static final float DEFAULT_IOU_THRESH  = 0.45f;

    public static final String[] DEFAULT_CLASS_NAMES = { "face_sheet", "tape" };
    private static final int[] DEFAULT_CLASS_COLORS = { 0xFFFF3232, 0xFF32C832 };

    // ============================================================
    // 旧接口（向后兼容）
    // ============================================================

    public static List<DetectionResult> process(
            float[] rawOutput, int origW, int origH,
            NCNNPreProcessor.LetterboxInfo letterbox) {
        ModelConfig cfg = ModelConfig.builtin("yolov8n", "快递检测 v1");
        cfg.confThresh = DEFAULT_CONF_THRESH;
        cfg.iouThresh  = DEFAULT_IOU_THRESH;
        return process(rawOutput, origW, origH, letterbox, cfg);
    }

    public static List<DetectionResult> process(
            float[] rawOutput, int origW, int origH,
            NCNNPreProcessor.LetterboxInfo letterbox,
            float confThresh, float iouThresh) {
        ModelConfig cfg = ModelConfig.builtin("yolov8n", "快递检测 v1");
        cfg.confThresh = confThresh;
        cfg.iouThresh  = iouThresh;
        return process(rawOutput, origW, origH, letterbox, cfg);
    }

    // ============================================================
    // 新接口：从 ModelConfig 读取参数
    // ============================================================

    public static List<DetectionResult> process(
            float[] rawOutput, int origW, int origH,
            NCNNPreProcessor.LetterboxInfo letterbox,
            ModelConfig config) {

        int numDetections = config.numDetections;
        int stride        = config.stride;
        int numClasses    = config.numClasses;
        String[] classNames = (config.classNames != null && config.classNames.length >= numClasses)
                ? config.classNames : DEFAULT_CLASS_NAMES;

        float confThresh = config.confThresh;
        float iouThresh  = config.iouThresh;

        if (rawOutput == null || rawOutput.length < numDetections * stride) {
            return new ArrayList<>();
        }

        float scale = letterbox.scale;
        float dw = letterbox.dw;
        float dh = letterbox.dh;

        List<DetectionResult> candidates = new ArrayList<>();

        for (int det = 0; det < numDetections; det++) {
            // Feature-major read: output[ch * numDetections + det]
            float cx = rawOutput[0 * numDetections + det];
            float cy = rawOutput[1 * numDetections + det];
            float w  = rawOutput[2 * numDetections + det];
            float h  = rawOutput[3 * numDetections + det];

            // 找最佳类别
            float maxConf = 0f;
            int maxCls = -1;
            for (int c = 0; c < numClasses; c++) {
                float conf = rawOutput[(4 + c) * numDetections + det];
                if (conf > maxConf) {
                    maxConf = conf;
                    maxCls = c;
                }
            }

            if (maxCls < 0 || maxConf < confThresh) continue;

            // cx,cy,w,h are pixel coords in INPUT_SIZE padded space
            float x1 = cx - w / 2f;
            float y1 = cy - h / 2f;
            float x2 = cx + w / 2f;
            float y2 = cy + h / 2f;

            // Letterbox inverse
            x1 = (x1 - dw) / scale;
            y1 = (y1 - dh) / scale;
            x2 = (x2 - dw) / scale;
            y2 = (y2 - dh) / scale;

            // Clip to original image bounds
            x1 = Math.max(0, Math.min(origW, x1));
            y1 = Math.max(0, Math.min(origH, y1));
            x2 = Math.max(0, Math.min(origW, x2));
            y2 = Math.max(0, Math.min(origH, y2));

            if (x2 <= x1 || y2 <= y1) continue;

            // Normalize to [0,1] for overlay drawing
            DetectionResult result = new DetectionResult();
            result.classId = maxCls;
            result.className = (maxCls < classNames.length) ? classNames[maxCls] : "cls_" + maxCls;
            result.confidence = maxConf;
            result.x = x1 / origW;
            result.y = y1 / origH;
            result.w = (x2 - x1) / origW;
            result.h = (y2 - y1) / origH;

            candidates.add(result);
        }

        if (candidates.isEmpty()) return candidates;

        return perClassNMS(candidates, numClasses, iouThresh);
    }

    // ============================================================
    // NMS
    // ============================================================

    private static List<DetectionResult> perClassNMS(
            List<DetectionResult> detections, int numClasses, float iouThresh) {

        List<List<DetectionResult>> groups = new ArrayList<>(numClasses);
        for (int i = 0; i < numClasses; i++) groups.add(new ArrayList<>());

        for (DetectionResult d : detections) {
            if (d.classId >= 0 && d.classId < numClasses) {
                groups.get(d.classId).add(d);
            }
        }

        List<DetectionResult> results = new ArrayList<>();
        for (List<DetectionResult> group : groups) {
            results.addAll(nms(group, iouThresh));
        }
        return results;
    }

    private static List<DetectionResult> nms(
            List<DetectionResult> detections, float iouThresh) {
        if (detections.isEmpty()) return detections;

        List<DetectionResult> sorted = new ArrayList<>(detections);
        Collections.sort(sorted, (a, b) -> Float.compare(b.confidence, a.confidence));

        List<DetectionResult> keep = new ArrayList<>();
        int n = sorted.size();
        boolean[] suppressed = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (suppressed[i]) continue;
            keep.add(sorted.get(i));

            for (int j = i + 1; j < n; j++) {
                if (suppressed[j]) continue;
                if (computeIoU(sorted.get(i), sorted.get(j)) > iouThresh) {
                    suppressed[j] = true;
                }
            }
        }
        return keep;
    }

    private static float computeIoU(DetectionResult a, DetectionResult b) {
        float ax1 = a.x, ay1 = a.y, ax2 = a.x + a.w, ay2 = a.y + a.h;
        float bx1 = b.x, by1 = b.y, bx2 = b.x + b.w, by2 = b.y + b.h;

        float ix1 = Math.max(ax1, bx1), iy1 = Math.max(ay1, by1);
        float ix2 = Math.min(ax2, bx2), iy2 = Math.min(ay2, by2);

        float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        float union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter;
        return union > 0 ? inter / union : 0;
    }

    // ============================================================
    // 颜色
    // ============================================================

    public static int getClassColor(int classId) {
        if (classId >= 0 && classId < DEFAULT_CLASS_COLORS.length) return DEFAULT_CLASS_COLORS[classId];
        return 0xFFC8C800;
    }
}
