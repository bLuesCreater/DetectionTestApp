// ncnn_jni.cpp — JNI wrapper for ncnn YOLO11n inference
// 支持 assets 加载 + 文件系统直接加载

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <ncnn/net.h>

static ncnn::Net* g_net = nullptr;

// ============================================================
// 辅助：assets 读取
// ============================================================

static std::vector<uint8_t> read_asset(AAssetManager* mgr, const char* path) {
    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset) return {};
    off_t size = AAsset_getLength(asset);
    std::vector<uint8_t> buf(size);
    memcpy(buf.data(), AAsset_getBuffer(asset), size);
    AAsset_close(asset);
    return buf;
}

static void ensure_exists(const char* path) {
    std::string dir(path);
    size_t slash = dir.rfind('/');
    if (slash != std::string::npos) {
        dir.resize(slash);
        mkdir(dir.c_str(), 0777);
    }
}

static void write_file(const char* path, const std::vector<uint8_t>& data) {
    ensure_exists(path);
    FILE* f = fopen(path, "wb");
    if (!f) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "fopen fail: %s", path);
        return;
    }
    fwrite(data.data(), 1, data.size(), f);
    fclose(f);
}

// ============================================================
// 内部：从 assets 读取 → 写入缓存 → 加载
// ============================================================

static jboolean do_load_from_assets(AAssetManager* mgr,
                                    const char* paramName, const char* binName,
                                    const char* cacheParam, const char* cacheBin) {
    auto param = read_asset(mgr, paramName);
    auto model = read_asset(mgr, binName);
    if (param.empty() || model.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni",
                            "Asset read fail: %s / %s", paramName, binName);
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni",
                        "Assets: %s(%zu) %s(%zu)", paramName, param.size(), binName, model.size());

    if (g_net) { delete g_net; g_net = nullptr; }
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = false;

    write_file(cacheParam, param);
    write_file(cacheBin, model);

    int ret = g_net->load_param(cacheParam);
    if (ret != 0) { __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_param: %d", ret); delete g_net; g_net = nullptr; return JNI_FALSE; }
    ret = g_net->load_model(cacheBin);
    if (ret != 0) { __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_model: %d", ret); delete g_net; g_net = nullptr; return JNI_FALSE; }
    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "Assets model loaded OK");
    return JNI_TRUE;
}

// ============================================================
// 内部：直接从文件系统路径加载
// ============================================================

static jboolean do_load_from_file(const char* paramPath, const char* binPath) {
    if (g_net) { delete g_net; g_net = nullptr; }
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = false;

    int ret = g_net->load_param(paramPath);
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_param(%s): %d", paramPath, ret);
        delete g_net; g_net = nullptr;
        return JNI_FALSE;
    }

    ret = g_net->load_model(binPath);
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_model(%s): %d", binPath, ret);
        delete g_net; g_net = nullptr;
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "File model loaded OK: %s", paramPath);
    return JNI_TRUE;
}

extern "C" {

// ——— 加载默认 yolov8n（向后兼容） ———
JNIEXPORT jboolean JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;
    return do_load_from_assets(mgr,
        "yolov8n.param", "yolov8n.bin",
        "/data/data/com.andy.detectiontest/cache/model.param",
        "/data/data/com.andy.detectiontest/cache/model.bin");
}

// ——— 从 assets 加载指定文件 ———
JNIEXPORT jboolean JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/,
    jobject assetManager, jstring paramFile, jstring binFile) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    const char* pStr = env->GetStringUTFChars(paramFile, nullptr);
    const char* bStr = env->GetStringUTFChars(binFile, nullptr);

    std::string hash = std::to_string((size_t)pStr);
    std::string cacheDir = "/data/data/com.andy.detectiontest/cache/";
    std::string cp = cacheDir + hash + ".param";
    std::string cb = cacheDir + hash + ".bin";

    jboolean result = do_load_from_assets(mgr, pStr, bStr, cp.c_str(), cb.c_str());

    env->ReleaseStringUTFChars(paramFile, pStr);
    env->ReleaseStringUTFChars(binFile, bStr);
    return result;
}

// ——— 从文件系统加载（导入模型用） ———
JNIEXPORT jboolean JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeLoadFromFile(
    JNIEnv* env, jobject /*thiz*/,
    jstring paramPath, jstring binPath) {
    const char* pp = env->GetStringUTFChars(paramPath, nullptr);
    const char* bp = env->GetStringUTFChars(binPath, nullptr);
    jboolean result = do_load_from_file(pp, bp);
    env->ReleaseStringUTFChars(paramPath, pp);
    env->ReleaseStringUTFChars(binPath, bp);
    return result;
}

// ——— 推理 ———
JNIEXPORT jfloatArray JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeDetect(
    JNIEnv* env, jobject /*thiz*/, jfloatArray inputData) {
    if (!g_net) return nullptr;

    const int INPUT_W = 640;
    const int INPUT_H = 640;

    jfloat* inputBuf = env->GetFloatArrayElements(inputData, nullptr);

    ncnn::Mat in(INPUT_W, INPUT_H, 3);
    if (in.empty()) {
        env->ReleaseFloatArrayElements(inputData, inputBuf, JNI_ABORT);
        return nullptr;
    }
    memcpy((float*)in.data, inputBuf, 3 * INPUT_W * INPUT_H * sizeof(float));
    env->ReleaseFloatArrayElements(inputData, inputBuf, JNI_ABORT);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.input("in0", in);

    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0) return nullptr;

    size_t total = (size_t)out.c * out.h * out.w;
    std::vector<float> flat(total);
    size_t idx = 0;
    for (int c = 0; c < out.c; c++) {
        const float* ptr = out.channel(c);
        for (int h = 0; h < out.h; h++) {
            for (int w = 0; w < out.w; w++) {
                flat[idx++] = ptr[h * out.w + w];
            }
        }
    }

    jfloatArray result = env->NewFloatArray(total);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, total, flat.data());
    return result;
}

// ——— 释放 ———
JNIEXPORT void JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeClose(
    JNIEnv* env, jobject /*thiz*/) {
    if (g_net) { delete g_net; g_net = nullptr; }
}

} // extern "C"
