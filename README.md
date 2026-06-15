# DetectionTestApp

Android 端侧快递包装检测 APP — 基于 NCNN 推理引擎的 YOLO 目标检测。

## 技术栈

- **语言:** Java
- **推理引擎:** NCNN (v20260526, 非 Vulkan 版)
- **模型格式:** NCNN .param + .bin (pnnx 转换)
- **相机:** CameraX + ImageCapture
- **UI:** ConstraintLayout + Custom View (Canvas 绘制)
- **最低 API:** 24 (Android 7.0)

## 功能

| 功能 | 说明 |
|------|------|
| 拍照检测 | CameraX 实时预览 → 拍照 → NCNN 推理 → Canvas 标注 |
| 相册检测 | 从系统相册选择图片检测 |
| 多模型切换 | 内置/导入模型随时切换 |
| 模型导入 | 从文件管理器选择 .param/.bin 文件，命名后导入 |
| 模型持久化 | 导入的模型保存在内部存储，JSON 注册表管理 |
| 统计面板 | 彩色柱状图 + 元信息表格（Canvas 绘制） |

## 快速开始

### 构建 APK

```bash
git clone https://github.com/bLuesCreater/DetectionTestApp.git
cd DetectionTestApp
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 部署

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 导入模型

1. 导出模型为 NCNN 格式（.param + .bin）
2. 点击底部 **导入** 按钮
3. 依次选择 .param 文件和 .bin 文件
4. 输入自定义模型名称
5. 自动加载

### 内置模型

assets/ 目录下已预置一个内置模型 `yolov8n`（实际为 yolo11n 架构），UI 显示为 **快递检测 v1**，检测两类目标：
- 面单 (face_sheet)
- 胶带 (tape)

## 项目结构

```
app/src/main/java/com/andy/detectiontest/
├── MainActivity.java          # 主界面
├── StatsChartView.java        # 统计图表 View
└── detection/
    ├── DetectionResult.java   # 检测结果数据模型
    ├── ModelConfig.java       # 模型配置
    ├── ModelManager.java      # 模型管理器
    ├── NCNNDetector.java      # NCNN 推理封装
    ├── NCNNPostProcessor.java # 后处理 (NMS)
    └── NCNNPreProcessor.java  # 预处理 (letterbox)

app/src/main/cpp/
└── ncnn_jni.cpp               # JNI 桥接

app/src/main/assets/
├── yolov8n.param              # 内置模型结构
└── yolov8n.bin                # 内置模型权重
```

## 模型转换

将 ultralytics YOLO 模型转为 NCNN 格式：

```bash
# .pt → TorchScript
yolo export model=best.pt format=torchscript imgsz=640

# TorchScript → NCNN (pnnx)
pnnx best.torchscript inputshape=[1,3,640,640]

# 或直接使用 ultralytics 导出
yolo export model=best.pt format=ncnn imgsz=640
```

## License

MIT
