package com.andy.detectiontest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.andy.detectiontest.detection.DetectionResult;
import com.andy.detectiontest.detection.NCNNDetector;
import com.andy.detectiontest.detection.NCNNPostProcessor;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DetectionTest";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int PICK_IMAGE_REQUEST = 200;

    // Views
    private PreviewView previewView;
    private ImageView resultImage;
    private TextView resultText;
    private TextView statsText;
    private View statsPanel;
    private Button captureBtn;
    private Button albumBtn;
    private Button backBtn;

    // Camera
    private ImageCapture imageCapture;
    private boolean processing = false;

    // NCNN 本地推理
    private NCNNDetector detector;

    // Threading
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView  = findViewById(R.id.previewView);
        resultImage  = findViewById(R.id.resultImage);
        resultText   = findViewById(R.id.resultText);
        statsText    = findViewById(R.id.statsText);
        statsPanel   = findViewById(R.id.statsPanel);
        captureBtn   = findViewById(R.id.captureBtn);
        albumBtn     = findViewById(R.id.albumBtn);
        backBtn      = findViewById(R.id.backBtn);

        executor = Executors.newSingleThreadExecutor();

        // NCNN 本地推理
        detector = new NCNNDetector();
        boolean ok = detector.load(getAssets());
        Toast.makeText(this, ok ? "模型已加载" : "模型加载失败", Toast.LENGTH_SHORT).show();

        captureBtn.setOnClickListener(v -> takePhoto());
        albumBtn.setOnClickListener(v -> pickFromAlbum());
        backBtn.setOnClickListener(v -> showCamera());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    // ============================================================
    // 相机
    // ============================================================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);

            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "相机启动失败", Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ============================================================
    // 拍照 / 相册
    // ============================================================

    private void takePhoto() {
        if (processing) return;
        if (imageCapture == null) return;

        processing = true;
        showLoading();

        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        Bitmap bitmap = imageProxyToBitmap(image);
                        image.close();
                        if (bitmap == null) {
                            processing = false;
                            runOnUiThread(() -> resultText.setText("图片解码失败"));
                            return;
                        }
                        showResultArea();
                        runDetection(bitmap);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        processing = false;
                        Log.e(TAG, "Capture failed", e);
                        runOnUiThread(() -> {
                            resultText.setText("拍照失败");
                            showCamera();
                        });
                    }
                });
    }

    private void pickFromAlbum() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                processing = true;
                showLoading();
                showResultArea();
                executor.execute(() -> {
                    try {
                        Bitmap bitmap = loadBitmapFromUri(imageUri);
                        if (bitmap == null) {
                            throw new RuntimeException("Bitmap decode returned null");
                        }
                        runDetection(bitmap);
                    } catch (Exception e) {
                        processing = false;
                        Log.e(TAG, "Load image failed", e);
                        runOnUiThread(() -> resultText.setText("图片加载失败"));
                    }
                });
            }
        }
    }

    // ============================================================
    // NCNN 推理
    // ============================================================

    private void runDetection(Bitmap bitmap) {
        runOnUiThread(() -> resultText.setText("检测中..."));

        executor.execute(() -> {
            try {
                long t0 = System.nanoTime();
                List<DetectionResult> results = detector.detect(bitmap);
                long t1 = System.nanoTime();
                double totalMs = (t1 - t0) / 1_000_000.0;

                runOnUiThread(() -> showDetectionResults(bitmap, results, totalMs));

            } catch (Exception e) {
                Log.e(TAG, "Detection error", e);
                runOnUiThread(() -> resultText.setText("检测出错: " + e.getLocalizedMessage()));
            } finally {
                processing = false;
            }
        });
    }

    // ============================================================
    // 结果展示
    // ============================================================

    private void showDetectionResults(Bitmap original, List<DetectionResult> results,
                                      double totalMs) {
        Bitmap annotated = drawResults(original, results);
        resultImage.setImageBitmap(annotated);

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Float> maxConf = new HashMap<>();
        for (DetectionResult r : results) {
            counts.put(r.className, counts.getOrDefault(r.className, 0) + 1);
            float cur = maxConf.getOrDefault(r.className, 0f);
            if (r.confidence > cur) maxConf.put(r.className, r.confidence);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 检测统计\n");
        sb.append("─────────────────\n");
        if (results.isEmpty()) {
            sb.append("⚠️ 未检测到目标\n");
        } else {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String cls = e.getKey();
                int cnt = e.getValue();
                float conf = maxConf.getOrDefault(cls, 0f);
                sb.append(String.format("• %s: %d  (最高 %.0f%%)\n",
                        classNameDisplay(cls), cnt, conf * 100));
            }
        }
        sb.append("─────────────────\n");
        sb.append(String.format("⏱ 总耗时: %.0f ms\n", totalMs));
        sb.append(String.format("📏 图片: %d×%d", original.getWidth(), original.getHeight()));

        statsText.setText(sb.toString());
        statsPanel.setVisibility(View.VISIBLE);
        resultText.setText(String.format("检测 %d 个目标", results.size()));
    }

    private void showLoading() {
        statsPanel.setVisibility(View.GONE);
        resultText.setVisibility(View.VISIBLE);
        resultText.setText("检测中...");
    }

    private void showResultArea() {
        previewView.setVisibility(View.GONE);
        resultImage.setVisibility(View.VISIBLE);
        resultText.setVisibility(View.VISIBLE);
        captureBtn.setVisibility(View.GONE);
        albumBtn.setVisibility(View.GONE);
        backBtn.setVisibility(View.VISIBLE);
    }

    private void showCamera() {
        processing = false;
        previewView.setVisibility(View.VISIBLE);
        resultImage.setVisibility(View.GONE);
        resultText.setVisibility(View.GONE);
        statsPanel.setVisibility(View.GONE);
        captureBtn.setVisibility(View.VISIBLE);
        albumBtn.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.GONE);
    }

    // ============================================================
    // 绘制检测框
    // ============================================================

    private Bitmap drawResults(Bitmap src, List<DetectionResult> results) {
        int sw = src.getWidth(), sh = src.getHeight();
        float scale = Math.min(1600f / sw, 1600f / sh);
        Bitmap working = src;
        if (scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            Bitmap scaled = Bitmap.createBitmap(src, 0, 0, sw, sh, m, true);
            if (scaled != null) {
                if (scaled != src) src.recycle();
                working = scaled;
                sw = working.getWidth();
                sh = working.getHeight();
            }
        }

        Bitmap copy = working.copy(Bitmap.Config.ARGB_8888, true);
        if (copy == null) return working;

        Canvas canvas = new Canvas(copy);

        float textSize = Math.min(sw, sh) * 0.04f;
        float strokeWidth = Math.max(3f, Math.min(sw, sh) * 0.005f);

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(strokeWidth);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(textSize);
        labelPaint.setFakeBoldText(true);

        for (DetectionResult r : results) {
            int color = NCNNPostProcessor.getClassColor(r.classId);
            boxPaint.setColor(color);
            fillPaint.setColor(color);

            float left   = r.x * sw;
            float top    = r.y * sh;
            float right  = (r.x + r.w) * sw;
            float bottom = (r.y + r.h) * sh;

            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = String.format("%s %.0f%%",
                    classNameDisplay(r.className), r.confidence * 100);
            float tw = labelPaint.measureText(label);
            float pad = textSize * 0.2f;
            float lx = Math.min(left, sw - tw - pad * 2 - 4);
            float ly = Math.max(0, top - textSize - pad * 2);

            canvas.drawRect(lx, ly, lx + tw + pad * 2, ly + textSize + pad * 2, fillPaint);
            canvas.drawText(label, lx + pad, ly + textSize + pad * 0.8f, labelPaint);
        }

        return copy;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private String classNameDisplay(String name) {
        if (name == null) return "未知";
        switch (name.toLowerCase()) {
            case "face_sheet": return "📄 面单";
            case "tape":       return "📎 胶带";
            default:           return name;
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpegData = new byte[buffer.remaining()];
            buffer.get(jpegData);

            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) return null;

            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "JPEG→Bitmap failed", e);
            return null;
        }
    }

    private Bitmap loadBitmapFromUri(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new RuntimeException("Cannot open URI: " + uri);
        Bitmap bitmap = BitmapFactory.decodeStream(is);
        is.close();
        return bitmap;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}
