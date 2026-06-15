// ncnn_jni.cpp — JNI wrapper for ncnn YOLO11n inference

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <ncnn/net.h>

static ncnn::Net* g_net = nullptr;

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
    // Ensure parent dir of path exists (mkdir -p equivalent)
    // path like /data/data/com.xxx/cache/model.param
    std::string dir(path);
    size_t slash = dir.rfind('/');
    if (slash != std::string::npos) {
        dir.resize(slash);
        mkdir(dir.c_str(), 0777); // ignore error if exists
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
    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "Wrote %zu bytes", data.size());
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jobject assetManager) {

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    auto param = read_asset(mgr, "yolov8n.param");
    auto model = read_asset(mgr, "yolov8n.bin");
    if (param.empty() || model.empty()) return JNI_FALSE;

    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "Assets: param=%zu, model=%zu", param.size(), model.size());

    if (g_net) { delete g_net; g_net = nullptr; }
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = false;

    write_file("/data/data/com.andy.detectiontest/cache/model.param", param);
    write_file("/data/data/com.andy.detectiontest/cache/model.bin", model);

    int ret = g_net->load_param("/data/data/com.andy.detectiontest/cache/model.param");
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_param failed: %d", ret);
        delete g_net; g_net = nullptr;
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "load_param OK");

    ret = g_net->load_model("/data/data/com.andy.detectiontest/cache/model.bin");
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn_jni", "load_model failed: %d", ret);
        delete g_net; g_net = nullptr;
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, "ncnn_jni", "load_model OK");
    return JNI_TRUE;
}

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

JNIEXPORT void JNICALL
Java_com_andy_detectiontest_detection_NCNNDetector_nativeClose(
    JNIEnv* env, jobject /*thiz*/) {
    if (g_net) { delete g_net; g_net = nullptr; }
}

} // extern "C"
