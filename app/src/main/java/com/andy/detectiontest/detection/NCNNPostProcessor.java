package com.andy.detectiontest.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Post-processor for NCNN YOLO11n (640×640 input, 2-class output).
 *
 * NCNN output: flat float[50400] = 6 × 8400 (feature-major)
 * Per detection: [cx, cy, w, h, conf_face_sheet, conf_tape]
 *   cx,cy,w,h are pixel-level coords in 640×640 space (already stride-scaled)
 *   conf values are sigmoid'd (0~1)
 */
public class NCNNPostProcessor {

    public static final int INPUT_SIZE = 640;
    public static final int NUM_DETECTIONS = 8400;
    private static final int STRIDE = 6;  // cx,cy,w,h,cls0,cls1
    private static final int NUM_CLASSES = 2;

    private static final float CONF_THRESH = 0.50f;
    private static final float IOU_THRESH = 0.45f;

    public static final String[] CLASS_NAMES = { "face_sheet", "tape" };
    private static final int[] CLASS_COLORS = { 0xFFFF3232, 0xFF32C832 };

    public static List<DetectionResult> process(
            float[] rawOutput, int origW, int origH,
            NCNNPreProcessor.LetterboxInfo letterbox) {
        return process(rawOutput, origW, origH, letterbox, CONF_THRESH, IOU_THRESH);
    }

    public static List<DetectionResult> process(
            float[] rawOutput, int origW, int origH,
            NCNNPreProcessor.LetterboxInfo letterbox,
            float confThresh, float iouThresh) {

        if (rawOutput == null || rawOutput.length < NUM_DETECTIONS * STRIDE) {
            return new ArrayList<>();
        }

        float scale = letterbox.scale;
        float dw = letterbox.dw;
        float dh = letterbox.dh;

        List<DetectionResult> candidates = new ArrayList<>();

        for (int det = 0; det < NUM_DETECTIONS; det++) {
            // Feature-major read: output[ch * NUM_DETECTIONS + det]
            float cx = rawOutput[0 * NUM_DETECTIONS + det];
            float cy = rawOutput[1 * NUM_DETECTIONS + det];
            float w  = rawOutput[2 * NUM_DETECTIONS + det];
            float h  = rawOutput[3 * NUM_DETECTIONS + det];

            // Class scores (already sigmoid'd by NCNN param)
            float conf0 = rawOutput[4 * NUM_DETECTIONS + det];
            float conf1 = rawOutput[5 * NUM_DETECTIONS + det];

            float maxConf = conf0;
            int maxCls = 0;
            if (conf1 > maxConf) { maxConf = conf1; maxCls = 1; }

            if (maxConf < confThresh) continue;

            // cx,cy,w,h are pixel coords in 640×640 padded space
            float x1 = cx - w / 2f;
            float y1 = cy - h / 2f;
            float x2 = cx + w / 2f;
            float y2 = cy + h / 2f;

            // Letterbox inverse: remove padding, scale back to original image
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
            result.className = CLASS_NAMES[maxCls];
            result.confidence = maxConf;
            result.x = x1 / origW;
            result.y = y1 / origH;
            result.w = (x2 - x1) / origW;
            result.h = (y2 - y1) / origH;

            candidates.add(result);
        }

        if (candidates.isEmpty()) return candidates;

        return perClassNMS(candidates, iouThresh);
    }

    private static List<DetectionResult> perClassNMS(
            List<DetectionResult> detections, float iouThresh) {

        List<List<DetectionResult>> groups = new ArrayList<>(NUM_CLASSES);
        for (int i = 0; i < NUM_CLASSES; i++) groups.add(new ArrayList<>());

        for (DetectionResult d : detections) {
            if (d.classId >= 0 && d.classId < NUM_CLASSES) {
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

    public static int getClassColor(int classId) {
        if (classId >= 0 && classId < CLASS_COLORS.length) return CLASS_COLORS[classId];
        return 0xFFC8C800;
    }
}
