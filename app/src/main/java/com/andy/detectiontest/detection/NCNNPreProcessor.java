package com.andy.detectiontest.detection;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * Pre-processor for NCNN YOLO11n — 640×640 letterbox.
 *
 * Input: Bitmap (any size)
 * Output: float[3×640×640] CHW RGB, /255.0 normalized
 */
public class NCNNPreProcessor {

    public static final int INPUT_SIZE = 640;
    private static final int FILL_COLOR = 0xFF727272; // RGB(114,114,114)

    public static class LetterboxInfo {
        public float scale;
        public int dw;    // horizontal padding in pixels (640 space)
        public int dh;    // vertical padding in pixels (640 space)

        public LetterboxInfo(float scale, int dw, int dh) {
            this.scale = scale;
            this.dw = dw;
            this.dh = dh;
        }
    }

    public static float[] process(Bitmap bitmap, LetterboxInfo outInfo) {
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();

        float scale = Math.min((float) INPUT_SIZE / srcW, (float) INPUT_SIZE / srcH);
        int newW = Math.round(srcW * scale);
        int newH = Math.round(srcH * scale);
        int dw = (INPUT_SIZE - newW) / 2;
        int dh = (INPUT_SIZE - newH) / 2;

        outInfo.scale = scale;
        outInfo.dw = dw;
        outInfo.dh = dh;

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        Bitmap padded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        canvas.drawColor(FILL_COLOR);
        canvas.drawBitmap(scaled, dw, dh, null);
        scaled.recycle();

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        padded.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        padded.recycle();

        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int area = INPUT_SIZE * INPUT_SIZE;
        for (int i = 0; i < area; i++) {
            int px = pixels[i];
            float r = ((px >> 16) & 0xFF) / 255.0f;
            float g = ((px >> 8)  & 0xFF) / 255.0f;
            float b = ( px        & 0xFF) / 255.0f;
            data[i]           = r;   // R ch
            data[i + area]    = g;   // G ch
            data[i + 2*area]  = b;   // B ch
        }

        return data;
    }
}
