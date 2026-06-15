package com.andy.detectiontest.detection;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * NCNN 本地推理封装 — 支持多模型切换（assets + 文件系统）
 *
 * 输入: 640×640 RGB float[3*640*640] (CHW, /255.0 normalized)
 * 输出: 配置而定 (默认: c=1 h=6 w=8400, feature-major)
 */
public class NCNNDetector {

    private static final String TAG = "NCNNDetector";

    private boolean loaded = false;
    private ModelConfig currentConfig = null;

    // JNI methods
    private static native boolean nativeLoad(AssetManager assetManager);
    private static native boolean nativeLoadModel(AssetManager assetManager,
                                                   String paramFile, String binFile);
    private static native boolean nativeLoadFromFile(String paramPath, String binPath);
    private static native float[] nativeDetect(float[] inputData);
    private static native void nativeClose();

    static {
        try {
            System.loadLibrary("ncnn_jni");
            Log.i(TAG, "ncnn_jni loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load ncnn_jni", e);
        }
    }

    public NCNNDetector() {}

    // ============================================================
    // 加载 / 切换模型
    // ============================================================

    /**
     * 向后兼容：加载默认模型 (快递检测 v1)
     */
    public boolean load(AssetManager assetManager) {
        closeNet();
        boolean ok = nativeLoad(assetManager);
        if (ok) {
            currentConfig = ModelConfig.builtin("yolov8n", "快递检测 v1");
            loaded = true;
            Log.i(TAG, "Loaded default model");
        }
        return ok;
    }

    /**
     * 从 assets 加载内置模型
     */
    public boolean loadModel(AssetManager assetManager, ModelConfig config) {
        closeNet();
        boolean ok = nativeLoadModel(assetManager,
                config.builtinKey + ".param",
                config.builtinKey + ".bin");
        if (ok) {
            currentConfig = config;
            loaded = true;
            Log.i(TAG, "Loaded builtin model: " + config.customName);
        }
        return ok;
    }

    /**
     * 从文件系统加载导入的模型
     */
    public boolean loadModelFromFile(ModelConfig config) {
        if (config.paramFilePath == null || config.binFilePath == null) {
            Log.e(TAG, "loadModelFromFile: null path");
            return false;
        }
        closeNet();
        boolean ok = nativeLoadFromFile(config.paramFilePath, config.binFilePath);
        if (ok) {
            currentConfig = config;
            loaded = true;
            Log.i(TAG, "Loaded imported model: " + config.customName
                    + " (" + config.paramFilePath + ")");
        }
        return ok;
    }

    private void closeNet() {
        if (loaded) {
            nativeClose();
            loaded = false;
            currentConfig = null;
        }
    }

    // ============================================================
    // 推理
    // ============================================================

    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!loaded) return new ArrayList<>();

        int origW = bitmap.getWidth();
        int origH = bitmap.getHeight();

        NCNNPreProcessor.LetterboxInfo letterbox = new NCNNPreProcessor.LetterboxInfo(1f, 0, 0);
        float[] inputData = NCNNPreProcessor.process(bitmap, letterbox);

        long t0 = System.nanoTime();
        float[] rawOutput = nativeDetect(inputData);
        long t1 = System.nanoTime();
        Log.d(TAG, "Inference: " + String.format("%.1f", (t1 - t0) / 1_000_000.0) + "ms");

        if (rawOutput == null || rawOutput.length == 0) {
            Log.w(TAG, "Inference returned empty");
            return new ArrayList<>();
        }

        ModelConfig cfg = (currentConfig != null) ? currentConfig : ModelConfig.builtin("yolov8n", "快递检测 v1");
        return NCNNPostProcessor.process(rawOutput, origW, origH, letterbox, cfg);
    }

    // ============================================================
    // Getter / Setter
    // ============================================================

    public boolean isLoaded() { return loaded; }

    public ModelConfig getModelConfig() { return currentConfig; }

    public void close() {
        closeNet();
    }
}
