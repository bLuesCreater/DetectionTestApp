package com.andy.detectiontest;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.andy.detectiontest.detection.ModelConfig;
import com.andy.detectiontest.detection.ModelManager;
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

    // request codes
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int PICK_IMAGE_REQUEST        = 200;
    private static final int PICK_PARAM_FILE_REQUEST   = 300;
    private static final int PICK_BIN_FILE_REQUEST     = 400;

    // Views
    private PreviewView previewView;
    private ImageView resultImage;
    private TextView resultText;
    private StatsChartView statsChart;
    private View statsPanel;
    private Button modelSwitchBtn;
    private Button importBtn;
    private Button captureBtn;
    private Button albumBtn;
    private Button backBtn;

    // Camera
    private ImageCapture imageCapture;
    private boolean processing = false;

    // NCNN
    private NCNNDetector detector;
    private ModelManager modelManager;

    // 模型列表与当前索引
    private List<ModelConfig> modelList;
    private int currentModelIndex = -1;

    // 导入流程暂存
    private Uri pendingParamUri = null;

    // Threading
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView    = findViewById(R.id.previewView);
        resultImage    = findViewById(R.id.resultImage);
        resultText     = findViewById(R.id.resultText);
        statsChart     = findViewById(R.id.statsChart);
        statsPanel     = findViewById(R.id.statsPanel);
        modelSwitchBtn = findViewById(R.id.modelSwitchBtn);
        importBtn      = findViewById(R.id.importBtn);
        captureBtn     = findViewById(R.id.captureBtn);
        albumBtn       = findViewById(R.id.albumBtn);
        backBtn        = findViewById(R.id.backBtn);

        executor = Executors.newSingleThreadExecutor();
        detector = new NCNNDetector();

        // 初始化模型管理器
        modelManager = new ModelManager(this);
        modelManager.init();
        modelList = modelManager.getAllModels();

        if (modelList.isEmpty()) {
            Toast.makeText(this, "没有可用模型！", Toast.LENGTH_LONG).show();
            return;
        }

        // 加载第一个模型
        currentModelIndex = 0;
        loadModelByIndex(0);

        modelSwitchBtn.setOnClickListener(v -> showModelSwitcher());
        importBtn.setOnClickListener(v -> startImport());
        captureBtn.setOnClickListener(v -> takePhoto());
        albumBtn.setOnClickListener(v -> pickFromAlbum());
        backBtn.setOnClickListener(v -> showCamera());
    }

    // ============================================================
    // 🧠 模型切换
    // ============================================================

    private void refreshModelList() {
        modelList = modelManager.getAllModels();
    }

    private void loadModelByIndex(int index) {
        if (index < 0 || index >= modelList.size()) return;
        ModelConfig target = modelList.get(index);

        Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            boolean ok = modelManager.loadModel(detector, target);
            runOnUiThread(() -> {
                if (ok) {
                    currentModelIndex = index;
                    modelSwitchBtn.setText("切换");
                } else {
                    Toast.makeText(MainActivity.this,
                            "模型加载失败: " + target.customName, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showModelSwitcher() {
        if (modelList == null || modelList.isEmpty()) {
            Toast.makeText(this, "没有可用模型", Toast.LENGTH_SHORT).show();
            return;
        }

        final int curIdx = currentModelIndex;
        String[] items = new String[modelList.size()];
        for (int i = 0; i < modelList.size(); i++) {
            String marker = (i == curIdx) ? " ✓" : "";
            items[i] = modelList.get(i).customName + marker;
        }

        new AlertDialog.Builder(this)
                .setTitle("切换模型")
                .setSingleChoiceItems(items, curIdx,
                        (dialog, which) -> {
                            dialog.dismiss();
                            if (which == curIdx) return;
                            loadModelByIndex(which);
                        })
                .setNeutralButton("删除导入模型", (dialog, which) -> {
                    // 仅在当前模型是导入模型时可删除
                    if (curIdx >= 0 && curIdx < modelList.size()) {
                        ModelConfig cfg = modelList.get(curIdx);
                        if (!"builtin".equals(cfg.sourceType)) {
                            confirmDeleteModel(cfg);
                        } else {
                            Toast.makeText(this, "内置模型不能删除", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteModel(ModelConfig cfg) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("删除模型「" + cfg.customName + "」？")
                .setPositiveButton("删除", (dialog, which) -> {
                    modelManager.deleteModel(cfg);
                    refreshModelList();

                    if (modelList.isEmpty()) {
                        Toast.makeText(this, "已无可用模型", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // 切换到第一个可用模型
                    currentModelIndex = 0;
                    loadModelByIndex(0);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================
    // 📥 导入模型
    // ============================================================

    private void startImport() {
        Toast.makeText(this, "请选择 .param 模型结构文件", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_PARAM_FILE_REQUEST);
    }

    private void onParamFileSelected(Uri uri) {
        pendingParamUri = uri;
        Toast.makeText(this, "请选择 .bin 模型权重文件", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_BIN_FILE_REQUEST);
    }

    private void onBinFileSelected(Uri binUri) {
        if (pendingParamUri == null) {
            Toast.makeText(this, "请先选择 .param 文件", Toast.LENGTH_LONG).show();
            return;
        }

        // 弹命名对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("命名模型");

        final EditText input = new EditText(this);
        input.setHint("输入模型名称");
        input.setText("自定义模型 " + System.currentTimeMillis() % 10000);
        builder.setView(input);

        builder.setPositiveButton("导入", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            doImport(pendingParamUri, binUri, name);
        });
        builder.setNegativeButton("取消", (dialog, which) -> {
            pendingParamUri = null;
        });
        builder.show();
    }

    private void doImport(Uri paramUri, Uri binUri, String customName) {
        Toast.makeText(this, "正在导入...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            // 授予持久 URI 读取权限（SAF）
            try {
                getContentResolver().takePersistableUriPermission(
                        paramUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            try {
                getContentResolver().takePersistableUriPermission(
                        binUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}

            ModelConfig cfg = modelManager.importModel(paramUri, binUri, customName);

            runOnUiThread(() -> {
                pendingParamUri = null;

                if (cfg == null) {
                    Toast.makeText(MainActivity.this, "导入失败", Toast.LENGTH_LONG).show();
                    return;
                }

                // 刷新列表并切换到新模型
                refreshModelList();
                currentModelIndex = modelList.indexOf(cfg);
                loadModelByIndex(currentModelIndex);

                Toast.makeText(MainActivity.this,
                        "导入成功: " + cfg.customName, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ============================================================
    // Camera / Album / ActivityResult
    // ============================================================

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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == PICK_PARAM_FILE_REQUEST || requestCode == PICK_BIN_FILE_REQUEST) {
                pendingParamUri = null;
            }
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        switch (requestCode) {
            case PICK_IMAGE_REQUEST:
                handleAlbumImage(uri);
                break;
            case PICK_PARAM_FILE_REQUEST:
                onParamFileSelected(uri);
                break;
            case PICK_BIN_FILE_REQUEST:
                onBinFileSelected(uri);
                break;
        }
    }

    private void handleAlbumImage(Uri imageUri) {
        processing = true;
        showLoading();
        showResultArea();
        executor.execute(() -> {
            try {
                Bitmap bitmap = loadBitmapFromUri(imageUri);
                if (bitmap == null) throw new RuntimeException("Bitmap decode returned null");
                runDetection(bitmap);
            } catch (Exception e) {
                processing = false;
                Log.e(TAG, "Load image failed", e);
                runOnUiThread(() -> resultText.setText("图片加载失败"));
            }
        });
    }

    // ============================================================
    // NCNN 推理
    // ============================================================

    private void runDetection(Bitmap bitmap) {
        if (detector == null || !detector.isLoaded()) {
            processing = false;
            runOnUiThread(() -> resultText.setText("模型未加载"));
            return;
        }
        runOnUiThread(() -> resultText.setText("检测中 ..."));

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

        // 统计各类别
        java.util.List<StatsChartView.BarData> barList = new java.util.ArrayList<>();
        Map<String, int[]> classStats = new HashMap<>();
        Map<String, Integer> classIdMap = new HashMap<>();
        int maxCount = 0;
        for (DetectionResult r : results) {
            int[] stats = classStats.get(r.className);
            if (stats == null) {
                stats = new int[]{0, 0};
                classStats.put(r.className, stats);
                classIdMap.put(r.className, r.classId);
            }
            stats[0]++;
            int confInt = (int)(r.confidence * 100);
            if (confInt > stats[1]) stats[1] = confInt;
        }
        for (int[] s : classStats.values()) {
            if (s[0] > maxCount) maxCount = s[0];
        }

        for (Map.Entry<String, int[]> e : classStats.entrySet()) {
            StatsChartView.BarData b = new StatsChartView.BarData();
            b.label    = classNameDisplay(e.getKey());
            b.count    = e.getValue()[0];
            b.confPct  = e.getValue()[1];
            Integer cid = classIdMap.get(e.getKey());
            b.color    = NCNNPostProcessor.getClassColor(cid != null ? cid : 0);
            b.maxCount = maxCount;
            barList.add(b);
        }

        ModelConfig cfg = detector.getModelConfig();
        String modelName = (cfg != null) ? cfg.customName : "?";
        String inputSizeStr = (cfg != null) ? cfg.inputSize + "x" + cfg.inputSize : "?";
        String confStr = (cfg != null) ? String.format("%.2f", cfg.confThresh) : "?";
        String iouStr  = (cfg != null) ? String.format("%.2f", cfg.iouThresh) : "?";

        statsChart.setData(
                barList,
                modelName,
                inputSizeStr,
                "conf " + confStr + " / iou " + iouStr,
                String.format("%.0f ms", totalMs),
                original.getWidth() + " x " + original.getHeight(),
                String.valueOf(results.size()));

        statsPanel.setVisibility(View.VISIBLE);
        resultText.setText(String.format("共 %d 个检测框", results.size()));
    }

    private void showLoading() {
        statsPanel.setVisibility(View.GONE);
        resultText.setVisibility(View.VISIBLE);
        resultText.setText("检测中 ...");
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
            case "face_sheet": return "面单";
            case "tape":       return "胶带";
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
