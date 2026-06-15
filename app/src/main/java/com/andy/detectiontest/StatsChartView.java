package com.andy.detectiontest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 检测统计图表 View — Canvas 绘制彩色柱状图 + Canvas 表格
 */
public class StatsChartView extends View {

    // ── 数据 ──
    private List<BarData> bars;
    private String[] metaLabels;
    private String[] metaValues;

    // ── Paint ──
    private final Paint titlePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barLabelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barConfPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barCountPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tableLinePaint  = new Paint();
    private final Paint tableLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tableValPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 布局缓存 ──
    private float dp;

    public static class BarData {
        public String label;
        public int count;
        public int confPct;     // 0-100
        public int color;       // ARGB
        public int maxCount;    // 全局最大
    }

    public StatsChartView(Context context) { super(context); init(); }
    public StatsChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        dp = getResources().getDisplayMetrics().density;

        titlePaint.setColor(Color.parseColor("#333333"));
        titlePaint.setTextSize(16 * dp);
        titlePaint.setFakeBoldText(true);

        barBgPaint.setColor(Color.parseColor("#E0E0E0"));

        barLabelPaint.setColor(Color.parseColor("#333333"));
        barLabelPaint.setTextSize(13 * dp);

        barCountPaint.setColor(Color.parseColor("#666666"));
        barCountPaint.setTextSize(13 * dp);

        barConfPaint.setTextSize(13 * dp);
        barConfPaint.setFakeBoldText(true);

        linePaint.setColor(Color.parseColor("#CCCCCC"));
        linePaint.setStrokeWidth(1);

        tableLinePaint.setColor(Color.parseColor("#AAAAAA"));
        tableLinePaint.setStyle(Paint.Style.STROKE);
        tableLinePaint.setStrokeWidth(2);

        tableLabelPaint.setColor(Color.parseColor("#555555"));
        tableLabelPaint.setTextSize(13 * dp);

        tableValPaint.setColor(Color.parseColor("#333333"));
        tableValPaint.setTextSize(13 * dp);
    }

    // ============================================================
    // 公开方法
    // ============================================================

    public void setData(List<BarData> bars, String modelName,
                        String inputSize, String threshStr,
                        String timeStr, String imageStr, String boxesStr) {
        this.bars = bars;
        this.metaLabels = new String[]{"模型", "输入", "阈值", "耗时", "图片", "检测框"};
        this.metaValues = new String[]{modelName, inputSize, threshStr, timeStr, imageStr, boxesStr};
        requestLayout();
        invalidate();
    }

    // ============================================================
    // 测量
    // ============================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, computeHeight(w));
    }

    private int computeHeight(int viewWidth) {
        float pad = 12 * dp;
        float h = pad;

        // 标题
        h += 22 * dp + 6 * dp;

        // bar 行
        int barRows = (bars == null) ? 0 : bars.size();
        h += barRows * 24 * dp;
        if (barRows == 0) h += 24 * dp; // "无检测框"

        // 分隔线 + 间距
        h += 12 * dp + 1 + 8 * dp;

        // 表格：边框 + 6 行 + 5 内部横线
        float cellH = 22 * dp;
        h += 1 + cellH * 6 + 5 * 1 + 1;

        // 下边距
        h += pad;
        return (int) h;
    }

    // ============================================================
    // 绘制
    // ============================================================

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        float pad = 12 * dp;
        float w = getWidth();
        float y = pad;

        // ═══════ 标题 ═══════
        c.drawText("检测统计", pad, y + 16 * dp, titlePaint);
        y += 22 * dp;

        // ═══════ 柱状图 ═══════
        if (bars != null && !bars.isEmpty()) {
            float barH  = 16 * dp;
            float maxBW = w - pad * 2 - 140 * dp;

            int maxCount = 0;
            for (BarData b : bars) maxCount = Math.max(maxCount, b.maxCount);
            if (maxCount < 1) maxCount = 1;

            for (BarData b : bars) {
                float bx = pad;

                // 类名
                c.drawText(b.label, bx, y + 16 * dp, barLabelPaint);
                bx += barLabelPaint.measureText(b.label) + 8 * dp;

                // 背景条
                float bgW = Math.min(maxBW, (float) maxCount / maxCount * maxBW);
                float by  = y + (24 * dp - barH) / 2f;
                RectF bgR = new RectF(bx, by, bx + bgW, by + barH);
                c.drawRoundRect(bgR, 4 * dp, 4 * dp, barBgPaint);

                // 前景条
                float fgW = bgW * b.count / maxCount;
                if (fgW < 2 * dp) fgW = 2 * dp;
                barFgPaint.setColor(b.color);
                c.drawRoundRect(new RectF(bx, by, bx + fgW, by + barH), 4 * dp, 4 * dp, barFgPaint);

                // 数量
                bx += bgW + 6 * dp;
                String cntStr = b.count + " 个";
                c.drawText(cntStr, bx, y + 16 * dp, barCountPaint);

                // 置信度
                bx += barCountPaint.measureText(cntStr) + 6 * dp;
                barConfPaint.setColor(b.color);
                c.drawText(b.confPct + "%", bx, y + 16 * dp, barConfPaint);

                y += 24 * dp;
            }
        } else {
            c.drawText("(无检测框)", pad, y + 16 * dp, barLabelPaint);
            y += 24 * dp;
        }

        // ═══════ 分隔线 ═══════
        y += 8 * dp;
        c.drawLine(pad, y, w - pad, y, linePaint);
        y += 8 * dp;

        // ═══════ 元信息表格 ═══════
        if (metaLabels == null || metaValues == null) return;

        float tableL = pad;
        float tableR = w - pad;
        float tableW = tableR - tableL;
        float leftW  = Math.max(64 * dp, tableLabelPaint.measureText("检测框") + 16 * dp);
        float rightW = tableW - leftW;
        float cellH  = 22 * dp;
        int metaRows = metaLabels.length;

        float cornerR = 4 * dp;

        // 外边框
        RectF outerRect = new RectF(tableL, y, tableR, y + cellH * metaRows);
        c.drawRoundRect(outerRect, cornerR, cornerR, tableLinePaint);

        // 竖线：标签与值分隔
        float dividerX = tableL + leftW;
        c.drawLine(dividerX, y, dividerX, y + cellH * metaRows, tableLinePaint);

        // 内部横线（rows-1 条）
        for (int i = 1; i < metaRows; i++) {
            float ly = y + cellH * i;
            c.drawLine(tableL, ly, tableR, ly, tableLinePaint);
        }

        // 填充文字
        float textBaselineOff = 16 * dp; // 从 cell 顶部到 baseline 的偏移
        for (int i = 0; i < metaRows; i++) {
            float rowY = y + i * cellH;

            // 标签（左对齐，left padding 8dp）
            c.drawText(metaLabels[i], tableL + 8 * dp, rowY + textBaselineOff, tableLabelPaint);

            // 值（左对齐，竖线右侧 8dp padding）
            c.drawText(metaValues[i], dividerX + 8 * dp, rowY + textBaselineOff, tableValPaint);
        }
    }
}
