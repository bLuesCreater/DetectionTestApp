package com.andy.detectiontest.detection;

/**
 * 检测结果数据模型
 * x, y, w, h 均为归一化 [0,1]，相对于原图
 * 内部存储为 (left, top, width, height) 格式
 * 从 API 的 (center_x, center_y, w, h) 自动转换
 */
public class DetectionResult {
    public int classId;
    public String className;
    public float confidence;
    public float x;      // 归一化左边界 (left, 由 center_x - w/2 得来)
    public float y;      // 归一化上边界 (top, 由 center_y - h/2 得来)
    public float w;      // 归一化宽度
    public float h;      // 归一化高度

    @Override
    public String toString() {
        return String.format("%s(%.2f) [%.2f,%.2f,%.2f,%.2f]",
                className, confidence, x, y, w, h);
    }
}
