package com.andy.detectiontest.detection;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理器 — 统一管理内置模型和导入模型
 *
 * 内置模型: assets/ 下的 .param/.bin 文件
 * 导入模型: 用户从外部选择文件后复制到内部存储
 *
 * 注册表: {filesDir}/models/registry.json
 * 导入文件: {filesDir}/models/m_{id}/model.param / model.bin
 */
public class ModelManager {

    private static final String TAG = "ModelManager";
    private static final String REGISTRY_FILE = "registry.json";
    private static final String MODELS_DIR   = "models";

    private final Context context;
    private final File modelsDir;
    private final File registryFile;

    /** 所有模型（顺序保留） */
    private final List<ModelConfig> models = new ArrayList<>();
    private int nextImportId = 0;

    // ============================================================
    // 内置模型自定义名映射表
    //   assets 文件名 → UI 显示名
    //   先生指出当前模型是 yolo11n 不是 yolov8n
    // ============================================================
    private static final Map<String, String> BUILTIN_NAMES = new HashMap<>();
    static {
        BUILTIN_NAMES.put("yolov8n", "快递检测 v1");
        // 后续有更多内置模型在此添加
    }

    public ModelManager(Context context) {
        this.context    = context;
        this.modelsDir  = new File(context.getFilesDir(), MODELS_DIR);
        this.registryFile = new File(modelsDir, REGISTRY_FILE);
    }

    // ============================================================
    // 初始化：扫描 assets + 加载注册表，合并为完整模型列表
    // ============================================================

    public void init() {
        models.clear();

        // 1. 加载注册表（持久化的模型信息）
        List<ModelConfig> imported = new ArrayList<>();
        loadRegistry(imported);

        // 2. 扫描 assets 内置模型
        List<ModelConfig> builtins = scanBuiltinAssets();

        // 3. 合并：内置模型在前，导入模型在后
        //    注册表中已记录的 builtin 使用注册表中的 customName（可编辑）
        //    未记录的内置模型使用默认 customName
        Map<String, ModelConfig> registryBuiltinMap = new HashMap<>();
        for (ModelConfig rc : imported) {
            if ("builtin".equals(rc.sourceType)) {
                registryBuiltinMap.put(rc.builtinKey, rc);
            }
        }

        for (ModelConfig b : builtins) {
            ModelConfig existing = registryBuiltinMap.get(b.builtinKey);
            if (existing != null) {
                // 注册表中有记录 → 用注册表名（可能是用户改过的）
                models.add(existing);
            } else {
                // 首次发现的内置模型 → 写回注册表
                models.add(b);
                imported.add(b);
            }
        }

        // 追加导入模型（排除已合并的内置）
        for (ModelConfig i : imported) {
            if (!"builtin".equals(i.sourceType)) {
                models.add(i);
            }
        }

        // 持久化
        nextImportId = countImported();
        saveRegistry();

        Log.i(TAG, "Init: " + models.size() + " model(s) loaded");
        for (int i = 0; i < models.size(); i++) {
            ModelConfig m = models.get(i);
            Log.i(TAG, "  [" + i + "] " + m.customName + " (" + m.sourceType + ")");
        }
    }

    // ============================================================
    // 扫描 assets 内置模型
    // ============================================================

    private List<ModelConfig> scanBuiltinAssets() {
        AssetManager am = context.getAssets();
        String[] files;
        try {
            files = am.list("");
        } catch (IOException e) {
            return Collections.emptyList();
        }
        if (files == null) return Collections.emptyList();

        List<ModelConfig> list = new ArrayList<>();
        for (String f : files) {
            if (!f.endsWith(".param")) continue;
            String name = f.substring(0, f.length() - ".param".length());
            String binFile = name + ".bin";

            boolean hasBin = false;
            for (String ff : files) {
                if (ff.equals(binFile)) { hasBin = true; break; }
            }
            if (!hasBin) continue;

            String displayName = BUILTIN_NAMES.getOrDefault(name, name);
            ModelConfig cfg = ModelConfig.builtin(name, displayName);
            list.add(cfg);
        }
        return list;
    }

    // ============================================================
    // 注册表 I/O
    // ============================================================

    private void loadRegistry(List<ModelConfig> out) {
        if (!registryFile.exists()) return;
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(registryFile), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load registry, starting fresh", e);
        }
    }

    private void saveRegistry() {
        try {
            if (!modelsDir.exists()) modelsDir.mkdirs();

            JSONArray arr = new JSONArray();
            for (ModelConfig m : models) {
                arr.put(toJson(m));
            }

            FileOutputStream fos = new FileOutputStream(registryFile);
            fos.write(arr.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save registry", e);
        }
    }

    // ============================================================
    // JSON 序列化
    // ============================================================

    private static JSONObject toJson(ModelConfig m) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("customName", m.customName);
        obj.put("sourceType", m.sourceType);
        obj.put("builtinKey", m.builtinKey != null ? m.builtinKey : "");
        obj.put("paramFilePath", m.paramFilePath != null ? m.paramFilePath : "");
        obj.put("binFilePath", m.binFilePath != null ? m.binFilePath : "");
        obj.put("inputSize", m.inputSize);
        obj.put("numDetections", m.numDetections);
        obj.put("stride", m.stride);
        obj.put("numClasses", m.numClasses);
        JSONArray cnArr = new JSONArray();
        if (m.classNames != null) {
            for (String cn : m.classNames) cnArr.put(cn);
        }
        obj.put("classNames", cnArr);
        obj.put("confThresh", m.confThresh);
        obj.put("iouThresh", m.iouThresh);
        return obj;
    }

    private static ModelConfig fromJson(JSONObject obj) throws JSONException {
        ModelConfig m = new ModelConfig();
        m.customName   = obj.optString("customName", "未命名模型");
        m.sourceType   = obj.optString("sourceType", "imported");
        m.builtinKey   = obj.optString("builtinKey", null);
        if ("".equals(m.builtinKey)) m.builtinKey = null;
        m.paramFilePath = obj.optString("paramFilePath", null);
        if ("".equals(m.paramFilePath)) m.paramFilePath = null;
        m.binFilePath   = obj.optString("binFilePath", null);
        if ("".equals(m.binFilePath)) m.binFilePath = null;
        m.inputSize     = obj.optInt("inputSize", 640);
        m.numDetections = obj.optInt("numDetections", 8400);
        m.stride        = obj.optInt("stride", 6);
        m.numClasses    = obj.optInt("numClasses", 2);

        JSONArray cnArr = obj.optJSONArray("classNames");
        if (cnArr != null && cnArr.length() > 0) {
            m.classNames = new String[cnArr.length()];
            for (int i = 0; i < cnArr.length(); i++) {
                m.classNames[i] = cnArr.getString(i);
            }
        } else {
            m.classNames = new String[]{"face_sheet", "tape"};
        }
        m.confThresh = (float) obj.optDouble("confThresh", 0.50);
        m.iouThresh  = (float) obj.optDouble("iouThresh", 0.45);
        return m;
    }

    // ============================================================
    // 公开方法
    // ============================================================

    /** 获取完整模型列表 */
    public List<ModelConfig> getAllModels() {
        return new ArrayList<>(models);
    }

    /**
     * 加载指定模型到 detector
     * @return true 加载成功
     */
    public boolean loadModel(NCNNDetector detector, ModelConfig config) {
        if ("builtin".equals(config.sourceType)) {
            return detector.loadModel(context.getAssets(), config);
        } else {
            return detector.loadModelFromFile(config);
        }
    }

    /**
     * 导入模型 — 从外部 URI 复制文件到内部存储
     *
     * @param paramUri  .param 文件的 content URI
     * @param binUri    .bin 文件的 content URI
     * @param customName 用户自定义名
     * @return 导入后的 ModelConfig，或 null 失败
     */
    public ModelConfig importModel(Uri paramUri, Uri binUri, String customName) {
        String subdir = "m_" + nextImportId;
        File dir = new File(modelsDir, subdir);
        if (!dir.mkdirs()) {
            Log.e(TAG, "Failed to create dir: " + dir);
            return null;
        }

        File paramDest = new File(dir, "model.param");
        File binDest   = new File(dir, "model.bin");

        ContentResolver cr = context.getContentResolver();

        try {
            // 复制 .param
            try (InputStream in = cr.openInputStream(paramUri);
                 OutputStream out = new FileOutputStream(paramDest)) {
                if (in == null) throw new IOException("Cannot open param URI");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            // 复制 .bin
            try (InputStream in = cr.openInputStream(binUri);
                 OutputStream out = new FileOutputStream(binDest)) {
                if (in == null) throw new IOException("Cannot open bin URI");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            Log.i(TAG, "Imported: " + paramDest.getAbsolutePath()
                    + " + " + binDest.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Import copy failed", e);
            // 清理
            paramDest.delete();
            binDest.delete();
            dir.delete();
            return null;
        }

        ModelConfig cfg = ModelConfig.imported(
                customName,
                paramDest.getAbsolutePath(),
                binDest.getAbsolutePath());

        models.add(cfg);
        nextImportId++;
        saveRegistry();

        return cfg;
    }

    /**
     * 删除导入的模型
     */
    public boolean deleteModel(ModelConfig config) {
        if (config == null || "builtin".equals(config.sourceType)) return false;

        models.remove(config);
        saveRegistry();

        // 清理文件（在 models/m_N/ 目录下）
        if (config.paramFilePath != null) {
            File paramFile = new File(config.paramFilePath);
            File dir = paramFile.getParentFile();
            if (dir != null && dir.exists()) {
                for (File f : dir.listFiles()) f.delete();
                dir.delete();
            }
        }
        return true;
    }

    private int countImported() {
        int cnt = 0;
        for (ModelConfig m : models) {
            if (!"builtin".equals(m.sourceType)) cnt++;
        }
        return cnt;
    }
}
