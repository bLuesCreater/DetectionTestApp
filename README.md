# DetectionTestApp — 快递包装检测 APP

Android 端侧 NCNN 推理 APP，用于验证 yolo11n 2-class 检测质量。
基于 DetectApp 改造：移除 HTTP 远程推理和 RKNN，改为纯 NCNN 端侧推理。
模型为 yolo11n-v1，由 Green 和先生训练。

## 架构

```
手机 (Android, NCNN)
┌─────────────────────────────────┐
│  CameraX 拍照 / 相册选取        │
│  ↓ Bitmap                       │
│  NCNNPreProcessor (640×640)     │
│  ↓ float[3×640×640]            │
│  NCNN Inference (yolov8n.param) │
│  ↓ float[6×8400]               │
│  NCNNPostProcessor (parse+NMS)  │
│  ↓ List<DetectionResult>        │
│  Canvas 绘制检测框 + 统计面板    │
└─────────────────────────────────┘
```

## 技术栈

| 层 | 技术 |
|----|------|
| 模型 | yolo11n-v1 (NCNN 格式) |
| 推理引擎 | ncnn v20260526 (arm64-v8a) |
| 相机 | CameraX (ImageCapture) |
| UI 绘制 | Canvas 直接绘制检测框 |
| 统计面板 | CardView 显示 per-class 统计 |

## 预处理

1. Letterbox resize 到 640×640（灰色填充 114,114,114）
2. 像素值 /255.0 归一化到 [0,1]
3. HWC → CHW 格式

## 模型输出 (NCNN)

- Shape: (1, 6, 8400) → feature-major 排列 → flat float[50400]
- 每个检测: [cx, cy, w, h, conf_face_sheet, conf_tape]
- cx,cy,w,h 为像素级坐标（640×640 空间内，已 stride-scaled）
- conf 已 sigmoid，范围 [0,1]

## 后处理

1. Feature-major → detection-major 转换
2. Letterbox 逆变换（移除 padding + 缩放回原图）
3. 找最大置信度类别，过滤 conf < 0.5
4. Per-class NMS（IoU 阈值 0.45）
5. 坐标限制在原图范围内

## 类别

| id | name | 颜色 |
|----|------|------|
| 0 | face_sheet | 🔴 红 |
| 1 | tape | 🟢 绿 |

## 目录结构

```
DetectionTestApp/
├── app/
│   ├── build.gradle.kts         # Android 构建配置
│   ├── CMakeLists.txt           # JNI 编译（ncnn）
│   └── src/main/
│       ├── assets/
│       │   ├── yolov8n.param    # yolo11n NCNN 模型参数
│       │   └── yolov8n.bin      # yolo11n NCNN 模型权重
│       ├── cpp/
│       │   ├── ncnn_jni.cpp     # NCNN JNI 桥接
│       │   └── include/ncnn/    # NCNN C++ 头文件
│       ├── java/com/andy/detectiontest/
│       │   ├── MainActivity.java
│       │   └── detection/
│       │       ├── DetectionResult.java
│       │       ├── NCNNDetector.java      # NCNN 推理封装
│       │       ├── NCNNPreProcessor.java  # 640×640 letterbox
│       │       └── NCNNPostProcessor.java # 2类8400检测解析+NMS
│       ├── jniLibs/arm64-v8a/
│       │   └── libncnn.so      # NCNN 运行时库
│       └── res/
└── backend/
    └── test_detect_server.py    # （旧）测试后端，不再使用
```

## 构建

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
APK: app/build/outputs/apk/debug/app-debug.apk
```
