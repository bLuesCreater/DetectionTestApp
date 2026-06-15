cmake_minimum_required(VERSION 3.10)
project("ncnn_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn 头文件路径
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/include)

# 动态链接 libncnn.so（运行时加载，构建时链接）
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libncnn.so
)

# 编译 JNI 桥接库
add_library(ncnn_jni SHARED
    src/main/cpp/ncnn_jni.cpp
)

target_link_libraries(ncnn_jni
    ncnn
    log
    android
)
