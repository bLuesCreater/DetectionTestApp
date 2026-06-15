package com.andy.detectiontest.detection;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * NCNN 本地推理封装 — yolo11n-v1 (2-class: face_sheet, tape)
 *
 * 模型: NCNN via pnnx + yolo export
 * 输入: 640×640 RGB float[3*640*640] (CHW, /255.0 normalized)
 * 输出: c=1 h=6 w=8400 → flat float[50400]
 *   [cx, cy, w, h, conf_face_sheet, conf_tape] (pixel coords, sigmoid'd)
 */
public class NCNNDetector {

    private static final String TAG = "NCNNDetector";

    private boolean loaded = false;

    // JNI methods
    private static native boolean nativeLoad(AssetManager assetManager);
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

    public boolean load(AssetManager assetManager) {
        loaded = nativeLoad(assetManager);
        if (loaded) {
            Log.i(TAG, "NCNN model loaded (yolo11n-v1, face_sheet/tape)");
        } else {
            Log.e(TAG, "Failed to load NCNN model");
        }
        return loaded;
    }

    /**
     * Detect objects in bitmap
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        if (!loaded) return new ArrayList<>();

        int origW = bitmap.getWidth();
        int origH = bitmap.getHeight();

        // 1. Preprocess (letterbox 640×640 + /255.0 + CHW)
        NCNNPreProcessor.LetterboxInfo letterbox = new NCNNPreProcessor.LetterboxInfo(1f, 0, 0);
        float[] inputData = NCNNPreProcessor.process(bitmap, letterbox);

        // 2. JNI inference
        long t0 = System.nanoTime();
        float[] rawOutput = nativeDetect(inputData);
        long t1 = System.nanoTime();
        Log.d(TAG, "Inference: " + (t1 - t0) / 1_000_000 + "ms");

        if (rawOutput == null || rawOutput.length == 0) {
            Log.w(TAG, "Inference returned empty");
            return new ArrayList<>();
        }

        // 3. Postprocess (parse + NMS)
        return NCNNPostProcessor.process(rawOutput, origW, origH, letterbox);
    }

    public boolean isLoaded() { return loaded; }

    public void close() {
        nativeClose();
        loaded = false;
    }
}
