package com.andy.detectiontest.detection;

import java.util.Arrays;

/**
 * 模型配置 — 描述一个 NCNN 模型的元信息
 *
 * sourceType:
 *   "builtin"   → param/bin 从 assets 读取
 *   "imported"  → param/bin 从内部存储读取
 */
public class ModelConfig {

    /** 用户自定义名（显示用），如 "快递检测 v1" */
    public String customName;

    /** 来源类型: "builtin" | "imported" */
    public String sourceType;

    // ——— 内置模型专用 ———
    /** assets 文件名前缀（仅 builtin），如 "yolov8n" */
    public String builtinKey;

    // ——— 导入模型专用 ———
    /** 内部存储中 param 文件的绝对路径（仅 imported） */
    public String paramFilePath;
    /** 内部存储中 bin 文件的绝对路径（仅 imported） */
    public String binFilePath;

    // ——— 模型结构参数 ———
    public int inputSize       = 640;
    public int numDetections   = 8400;
    public int stride          = 6;
    public int numClasses      = 2;
    public String[] classNames = {"face_sheet", "tape"};
    public float confThresh    = 0.50f;
    public float iouThresh     = 0.45f;

    public ModelConfig() {}

    // ============================================================
    // 工厂方法
    // ============================================================

    /** 创建内置模型配置 */
    public static ModelConfig builtin(String builtinKey, String customName) {
        ModelConfig cfg = new ModelConfig();
        cfg.sourceType = "builtin";
        cfg.builtinKey = builtinKey;
        cfg.customName = customName;
        return cfg;
    }

    /** 创建导入模型配置 */
    public static ModelConfig imported(String customName,
                                       String paramFilePath, String binFilePath) {
        ModelConfig cfg = new ModelConfig();
        cfg.sourceType = "imported";
        cfg.customName = customName;
        cfg.paramFilePath = paramFilePath;
        cfg.binFilePath   = binFilePath;
        return cfg;
    }

    @Override
    public String toString() {
        return "ModelConfig{" +
                "customName='" + customName + '\'' +
                ", sourceType='" + sourceType + '\'' +
                '}';
    }
}
