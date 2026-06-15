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
 * 检测统计图表 View
 *
 *  ┌──────────────────────────────────┐
 *  │ 检测统计                          │
 *  │  ┌──饼图──┐  ┌─置信度竖状图─┐     │
 *  │  │ 🥧扇形  │  │ ██ ██ ██    │     │
 *  │  │ 图例    │  │ ██ ██ ██ ██ │     │
 *  │  └────────┘  └─────────────┘     │
 *  │ ┌─ 元信息表格 ─────────────────┐ │
 *  │ │ 模型 ...                      │ │
 *  │ └──────────────────────────────┘ │
 *  └──────────────────────────────────┘
 */
public class StatsChartView extends View {

    // ── 数据 ──
    private List<PieSlice> pieSlices;        // 饼图扇形
    private List<VertBar>  vertBars;          // 竖状图条
    private String[] metaLabels;
    private String[] metaValues;

    // ── Paint ──
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pieBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pieArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint = new Paint();
    private final Paint vertBarPaint = new Paint();
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint();
    private final Paint tableLinePaint = new Paint();
    private final Paint tableLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tableValPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float dp;

    // ── 数据模型 ──
    public static class PieSlice {
        public String label;
        public int count;
        public int color;
    }

    public static class VertBar {
        public float confidence; // 0~1
        public int color;
    }

    public StatsChartView(Context context) { super(context); init(); }
    public StatsChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        dp = getResources().getDisplayMetrics().density;

        titlePaint.setColor(Color.parseColor("#333333"));
        titlePaint.setTextSize(16 * dp);
        titlePaint.setFakeBoldText(true);

        pieBgPaint.setColor(Color.parseColor("#EEEEEE"));
        pieBgPaint.setStyle(Paint.Style.STROKE);
        pieBgPaint.setStrokeWidth(1);

        pieArcPaint.setStyle(Paint.Style.FILL);
        pieArcPaint.setAntiAlias(true);

        labelPaint.setColor(Color.parseColor("#333333"));
        labelPaint.setTextSize(12 * dp);

        legendPaint.setColor(Color.parseColor("#555555"));
        legendPaint.setTextSize(12 * dp);

        barBgPaint.setColor(Color.parseColor("#E0E0E0"));
        barBgPaint.setStyle(Paint.Style.FILL);

        vertBarPaint.setStyle(Paint.Style.FILL);

        axisPaint.setColor(Color.parseColor("#999999"));
        axisPaint.setTextSize(9 * dp);

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

    public void setData(List<PieSlice> pieSlices, List<VertBar> vertBars,
                        String modelName, String inputSize,
                        String threshStr, String timeStr,
                        String imageStr, String boxesStr) {
        this.pieSlices = pieSlices;
        this.vertBars  = vertBars;
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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), computeHeight());
    }

    private int computeHeight() {
        float h = 12 * dp;                    // 上边距
        h += 22 * dp;                         // 标题
        h += 6 * dp;                          // 标题间距
        h += 170 * dp;                        // 饼图+竖状图行
        h += 12 * dp + 1 + 8 * dp;            // 分隔线
        h += 1 + 22 * dp * 6 + 5 * 1 + 1;     // 表格（边框+6行+5横线）
        h += 12 * dp;                         // 下边距
        return (int) h;
    }

    // ============================================================
    // 绘制
    // ============================================================

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        float pad = 12 * dp;
        float w   = getWidth();
        float y   = pad;

        // ── 标题 ──
        c.drawText("检测统计", pad, y + 16 * dp, titlePaint);
        y += 22 * dp + 6 * dp;

        // ════════════════════════════════════════════
        // 饼图（左） + 竖状图（右）
        // ════════════════════════════════════════════
        float rowH = 160 * dp;
        float midX = w / 2f;

        // ─── 饼图（左半区） ───
        float pieL = pad;
        float pieT = y;
        float pieR = midX - 6 * dp;
        float pieB = y + rowH;

        float pieDiam = Math.min(pieR - pieL, pieB - pieT) - 8 * dp;
        float pieCX   = pieL + (pieR - pieL) / 2f;
        float pieCY   = pieT + 30 * dp + pieDiam / 2f;
        float pieRadi = pieDiam / 2f - 4 * dp;

        // 饼图标题
        c.drawText("类别分布", pieL + 4 * dp, pieT + 14 * dp, labelPaint);

        if (pieSlices != null && !pieSlices.isEmpty()) {
            int total = 0;
            for (PieSlice s : pieSlices) total += s.count;
            if (total > 0 && pieRadi > 10 * dp) {
                // 背景圆
                c.drawCircle(pieCX, pieCY, pieRadi, pieBgPaint);

                // 画扇形
                float sweepSum = 0;
                for (PieSlice s : pieSlices) {
                    float sweep = 360f * s.count / total;
                    pieArcPaint.setColor(s.color);
                    RectF oval = new RectF(pieCX - pieRadi, pieCY - pieRadi,
                                           pieCX + pieRadi, pieCY + pieRadi);
                    c.drawArc(oval, sweepSum - 90, sweep, true, pieArcPaint);
                    sweepSum += sweep;
                }
            }

            // 图例（饼图下方集中排列）
            float lx = pieL + 4 * dp;
            float ly = pieCY + pieRadi + 10 * dp;
            float legendItemH = 16 * dp;
            Paint legendSwatch = new Paint();
            legendSwatch.setStyle(Paint.Style.FILL);

            for (PieSlice s : pieSlices) {
                float swatchX = lx;
                legendSwatch.setColor(s.color);
                c.drawRect(swatchX, ly + 2 * dp, swatchX + 10 * dp, ly + 12 * dp, legendSwatch);
                String txt = s.label + " " + s.count + " 个";
                c.drawText(txt, swatchX + 14 * dp, ly + 12 * dp, legendPaint);
                ly += legendItemH;
            }
        } else {
            c.drawText("无数据", pieL + 4 * dp, pieT + 60 * dp, labelPaint);
        }

        // ─── 竖状图（右半区） ───
        float vL = midX + 6 * dp;
        float vT = y;
        float vR = w - pad;
        float vB = y + rowH;

        c.drawText("置信度分布", vL + 4 * dp, vT + 14 * dp, labelPaint);

        if (vertBars != null && !vertBars.isEmpty()) {
            int maxBars = Math.min(vertBars.size(), 12);
            float barAreaL = vL + 8 * dp;
            float barAreaR = vR - 4 * dp;
            float barAreaW = barAreaR - barAreaL;
            float barAreaT = vT + 24 * dp;
            float barAreaB = vB - 20 * dp;
            float barAreaH = barAreaB - barAreaT;

            float barW = Math.min(barAreaW / maxBars - 2 * dp, 24 * dp);
            float gap  = (barAreaW - barW * maxBars) / (maxBars + 1);

            float maxConf = 0;
            for (VertBar b : vertBars) {
                if (b.confidence > maxConf) maxConf = b.confidence;
            }
            if (maxConf <= 0) maxConf = 1;

            // 基线
            c.drawLine(barAreaL, barAreaB, barAreaR, barAreaB, axisPaint);

            for (int i = 0; i < maxBars; i++) {
                VertBar vb = vertBars.get(i);
                float barH = (vb.confidence / maxConf) * barAreaH;
                float bx   = barAreaL + gap * (i + 1) + barW * i;
                float by   = barAreaB - barH;

                // 背景条（浅灰）
                c.drawRect(bx, barAreaT, bx + barW, barAreaB, barBgPaint);
                // 前景条
                vertBarPaint.setColor(vb.color);
                c.drawRect(bx, by, bx + barW, barAreaB, vertBarPaint);

                // 底部数字（序号）
                String idxStr = String.valueOf(i + 1);
                float tx = bx + (barW - axisPaint.measureText(idxStr)) / 2f;
                c.drawText(idxStr, tx, barAreaB + 14 * dp, axisPaint);
            }

            // 纵轴指示
            c.drawText("100%", barAreaR - 28 * dp, barAreaT + 10 * dp, axisPaint);
            c.drawText("0",    barAreaR - 12 * dp, barAreaB - 4 * dp, axisPaint);
        } else {
            c.drawText("无数据", vL + 4 * dp, vT + 60 * dp, labelPaint);
        }

        y += rowH;

        // ═══════ 分隔线 ═══════
        y += 8 * dp;
        c.drawLine(pad, y, w - pad, y, linePaint);
        y += 8 * dp;

        // ═══════ 元信息表格 ═══════
        if (metaLabels == null || metaValues == null) return;

        float tableL = pad;
        float tableR = w - pad;
        float leftW  = Math.max(64 * dp, tableLabelPaint.measureText("检测框") + 16 * dp);
        float cellH  = 22 * dp;
        int metaRows = metaLabels.length;

        // 外边框
        c.drawRoundRect(new RectF(tableL, y, tableR, y + cellH * metaRows),
                4 * dp, 4 * dp, tableLinePaint);

        // 竖线
        float dividerX = tableL + leftW;
        c.drawLine(dividerX, y, dividerX, y + cellH * metaRows, tableLinePaint);

        // 内部横线
        for (int i = 1; i < metaRows; i++) {
            c.drawLine(tableL, y + cellH * i, tableR, y + cellH * i, tableLinePaint);
        }

        // 填充文字
        float baselineOff = 16 * dp;
        for (int i = 0; i < metaRows; i++) {
            float rowY = y + i * cellH;
            c.drawText(metaLabels[i], tableL + 8 * dp, rowY + baselineOff, tableLabelPaint);
            c.drawText(metaValues[i], dividerX + 8 * dp, rowY + baselineOff, tableValPaint);
        }
    }
}
