package com.winlator.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.winlator.core.CPUStatus;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FrameRating extends View implements Runnable {
    private static final int MAX_FRAME_SAMPLES = 50;
    private static final int FRAME_UPDATE_INTERVAL_MS = 500;
    private static final int STAT_UPDATE_INTERVAL_MS = 1000;
    private static final int CONTENT_PADDING_PX = 16;
    private static final int HORIZONTAL_PADDING_PX = 16;
    private static final int ITEM_SPACING_PX = 20;
    private static final int GRAPH_WIDTH_PX = 200;
    private static final int GRAPH_HEIGHT_PX = 20;
    private static final int EXTRA_HEIGHT_PX = 6;

    private final ActivityManager activityManager;
    private final BatteryManager batteryManager;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enginePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelGpuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelCpuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelRamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelFpsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRect = new RectF();
    private final ArrayDeque<Float> frameTimes = new ArrayDeque<>(MAX_FRAME_SAMPLES);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> statsFuture;
    private long lastStatsTotal = -1L;
    private long lastStatsIdle = -1L;
    private long lastFrameCounterTime = 0L;
    private long lastFrameSampleTime = 0L;
    private int frameCount = 0;
    private float fps = 0.0f;
    private int gpuUsage = -1;
    private int cpuUsage = -1;
    private int ramUsage = 0;
    private float powerWatts = Float.NaN;
    private String powerText = "--";
    private String engineLabel = "N/A";
    private boolean dragHighlighted = false;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        float textSize = dp(9.0f);

        backgroundPaint.setColor(Color.argb(127, 0, 0, 0));
        separatorPaint.setColor(Color.rgb(220, 220, 220));
        separatorPaint.setStrokeWidth(2.5f);

        graphPaint.setColor(Color.rgb(0, 255, 0));
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStrokeWidth(2.5f);

        Typeface monospace = Typeface.MONOSPACE;

        valuePaint.setColor(Color.WHITE);
        valuePaint.setTextSize(textSize);
        valuePaint.setTypeface(Typeface.create(monospace, Typeface.NORMAL));
        valuePaint.setLetterSpacing(0.0f);

        enginePaint.setColor(Color.rgb(255, 80, 160));
        enginePaint.setTextSize(textSize);
        enginePaint.setFakeBoldText(true);
        enginePaint.setTypeface(monospace);
        enginePaint.setLetterSpacing(0.02f);

        configureLabelPaint(labelGpuPaint, Color.rgb(100, 255, 100), textSize);
        configureLabelPaint(labelCpuPaint, Color.rgb(255, 180, 0), textSize);
        configureLabelPaint(labelRamPaint, Color.rgb(80, 200, 255), textSize);
        configureLabelPaint(labelPowerPaint, Color.rgb(200, 120, 255), textSize);
        configureLabelPaint(labelFpsPaint, Color.rgb(255, 255, 80), textSize);

        setWillNotDraw(false);
        setVisibility(GONE);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int horizontalPadding = CONTENT_PADDING_PX + HORIZONTAL_PADDING_PX;
        setPadding(horizontalPadding, CONTENT_PADDING_PX, horizontalPadding, CONTENT_PADDING_PX);
    }

    private void configureLabelPaint(Paint paint, int color, float textSize) {
        paint.setColor(color);
        paint.setTextSize(textSize);
        paint.setFakeBoldText(true);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setLetterSpacing(0.02f);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public void setEngineLabel(@Nullable String engineLabel) {
        String value = engineLabel != null && !engineLabel.trim().isEmpty()
                ? engineLabel.trim()
                : "N/A";
        if (!value.equals(this.engineLabel)) {
            this.engineLabel = value;
            requestLayout();
            postInvalidateOnAnimation();
        }
    }

    public void setDragHighlighted(boolean dragHighlighted) {
        if (this.dragHighlighted != dragHighlighted) {
            this.dragHighlighted = dragHighlighted;
            postInvalidateOnAnimation();
        }
    }

    public void startMonitoring() {
        if (statsFuture != null && !statsFuture.isCancelled()) return;
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        statsFuture = scheduler.scheduleAtFixedRate(this::updateSystemStats, 0L, STAT_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stopMonitoring() {
        if (statsFuture != null) {
            statsFuture.cancel(true);
            statsFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public void update() {
        long now = SystemClock.elapsedRealtime();
        if (lastFrameCounterTime == 0L) {
            lastFrameCounterTime = now;
        }
        if (lastFrameSampleTime != 0L) {
            float frameTimeMs = now - lastFrameSampleTime;
            if (frameTimeMs > 0.0f) {
                synchronized (frameTimes) {
                    if (frameTimes.size() >= MAX_FRAME_SAMPLES) {
                        frameTimes.removeFirst();
                    }
                    frameTimes.addLast(frameTimeMs);
                }
            }
        }
        lastFrameSampleTime = now;

        if (now >= lastFrameCounterTime + FRAME_UPDATE_INTERVAL_MS) {
            fps = (frameCount * 1000.0f) / Math.max(1L, now - lastFrameCounterTime);
            frameCount = 0;
            lastFrameCounterTime = now;
            post(this);
        }

        frameCount++;
    }

    private void updateSystemStats() {
        gpuUsage = readGpuUsage();
        cpuUsage = readCpuUsage();
        ramUsage = readRamUsage();
        powerWatts = readPowerWatts();
        powerText = Float.isNaN(powerWatts) ? "--" : String.format(Locale.ENGLISH, "%.1fW", powerWatts);
        post(this);
    }

    private int readRamUsage() {
        if (activityManager == null) return 0;
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        if (memoryInfo.totalMem <= 0L) return 0;
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        return Math.max(0, Math.min(100, (int) Math.round((usedMem * 100.0) / memoryInfo.totalMem)));
    }

    private int readCpuUsage() {
        Integer procStatUsage = readProcStatCpuUsage();
        return procStatUsage != null
                ? procStatUsage
                : CPUStatus.readFrequencyUsagePercent();
    }

    @Nullable
    private Integer readProcStatCpuUsage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 8) return null;

            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = Long.parseLong(parts[5]);
            long irq = Long.parseLong(parts[6]);
            long softirq = Long.parseLong(parts[7]);
            long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0L;

            long idleAll = idle + iowait;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;

            if (lastStatsTotal < 0L || lastStatsIdle < 0L) {
                lastStatsTotal = total;
                lastStatsIdle = idleAll;
                return 0;
            }

            long totalDelta = total - lastStatsTotal;
            long idleDelta = idleAll - lastStatsIdle;
            lastStatsTotal = total;
            lastStatsIdle = idleAll;
            if (totalDelta <= 0L || idleDelta < 0L) return 0;

            int usage = (int) Math.round(((totalDelta - idleDelta) * 100.0) / totalDelta);
            return Math.max(0, Math.min(100, usage));
        }
        catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private int readGpuUsage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) return -1;

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return -1;

            long busy = Long.parseLong(parts[0]);
            long total = Long.parseLong(parts[1]);
            if (total <= 0L) return -1;

            int usage = (int) Math.round((busy * 100.0) / total);
            return Math.max(0, Math.min(100, usage));
        }
        catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    private float readPowerWatts() {
        if (batteryManager == null) return Float.NaN;

        Intent batteryIntent = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int voltageMv = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) : -1;
        if (voltageMv <= 0) return Float.NaN;

        int currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (currentUa == Integer.MIN_VALUE || currentUa == 0) return Float.NaN;

        return Math.abs((currentUa / 1_000_000.0f) * (voltageMv / 1000.0f));
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(VISIBLE);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMonitoring();
        removeCallbacks(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float contentWidth = enginePaint.measureText(engineLabel + " ")
                + measureMetricWidth("GPU", "100%", labelGpuPaint)
                + measureMetricWidth("CPU", "100%", labelCpuPaint)
                + measureMetricWidth("RAM", "100%", labelRamPaint)
                + measureMetricWidth("PWR", "00.0W", labelPowerPaint)
                + measureMetricWidth("FPS", "000.0", labelFpsPaint)
                + GRAPH_WIDTH_PX
                + (ITEM_SPACING_PX * 6.0f);
        float textHeight = valuePaint.getFontMetrics().descent - valuePaint.getFontMetrics().ascent;
        int totalWidth = getPaddingLeft() + getPaddingRight() + Math.round(contentWidth);
        int totalHeight = getPaddingTop() + getPaddingBottom()
                + Math.round(textHeight) + EXTRA_HEIGHT_PX;

        setMeasuredDimension(resolveSize(totalWidth, widthMeasureSpec), resolveSize(totalHeight, heightMeasureSpec));
    }

    private float measureMetricWidth(String label, String maximumValue, Paint labelPaint) {
        return labelPaint.measureText(label + " ") + valuePaint.measureText(maximumValue);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float corner = dp(4.0f);
        tempRect.set(0.0f, 0.0f, width, height);
        backgroundPaint.setColor(dragHighlighted
                ? Color.argb(160, 33, 150, 243)
                : Color.argb(127, 0, 0, 0));
        canvas.drawRoundRect(tempRect, corner, corner, backgroundPaint);

        Paint.FontMetrics valueMetrics = valuePaint.getFontMetrics();
        float textBaseline = (height / 2.0f) - ((valueMetrics.ascent + valueMetrics.descent) / 2.0f);
        float x = getPaddingLeft();

        canvas.drawText(engineLabel, x, textBaseline, enginePaint);
        x += enginePaint.measureText(engineLabel + " ");
        x = beginNextItem(canvas, x, height);
        x = drawMetric(canvas, x, textBaseline, "GPU", formatPercentValue(gpuUsage), labelGpuPaint);
        x = beginNextItem(canvas, x, height);
        x = drawMetric(canvas, x, textBaseline, "CPU", formatPercentValue(cpuUsage), labelCpuPaint);
        x = beginNextItem(canvas, x, height);
        x = drawMetric(canvas, x, textBaseline, "RAM", formatPercentValue(ramUsage), labelRamPaint);
        x = beginNextItem(canvas, x, height);
        x = drawMetric(canvas, x, textBaseline, "PWR", powerText, labelPowerPaint);
        x = beginNextItem(canvas, x, height);
        x = drawMetric(canvas, x, textBaseline, "FPS", String.format(Locale.ENGLISH, "%.1f", fps), labelFpsPaint);
        x = beginNextItem(canvas, x, height);
        drawGraph(canvas, x, textBaseline);
    }

    private float drawMetric(Canvas canvas, float x, float baseline, String label, String value, Paint labelPaint) {
        canvas.drawText(label, x, baseline, labelPaint);
        x += labelPaint.measureText(label + " ");
        canvas.drawText(value, x, baseline, valuePaint);
        return x + valuePaint.measureText(value);
    }

    private float beginNextItem(Canvas canvas, float x, float height) {
        float nextX = x + ITEM_SPACING_PX;
        drawSeparator(canvas, nextX - (ITEM_SPACING_PX / 2.0f), height);
        return nextX;
    }

    private void drawSeparator(Canvas canvas, float x, float height) {
        float textHeight = valuePaint.getFontMetrics().descent - valuePaint.getFontMetrics().ascent;
        float lineHeight = textHeight * 0.8f;
        float centerY = height / 2.0f;
        canvas.drawLine(x, centerY - (lineHeight / 2.0f),
                x, centerY + (lineHeight / 2.0f), separatorPaint);
    }

    private String formatPercentValue(int value) {
        if (value < 0) {
            return " --%";
        }
        return String.format(Locale.ENGLISH, "%3d%%", value);
    }

    private void drawGraph(Canvas canvas, float left, float baseline) {
        Float[] samples;
        synchronized (frameTimes) {
            samples = frameTimes.toArray(new Float[0]);
        }
        if (samples.length < 2) return;

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        for (Float sample : samples) {
            if (sample == null) continue;
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        if (min == Float.MAX_VALUE || max == Float.MIN_VALUE) return;
        float span = Math.max(4.0f, max - min);
        float low = min - 1.0f;
        Path path = new Path();
        float step = (float)GRAPH_WIDTH_PX / (MAX_FRAME_SAMPLES - 1);
        float graphBottom = baseline + (GRAPH_HEIGHT_PX / 2.0f);
        for (int i = 0; i < samples.length; i++) {
            float normalized = (samples[i] - low) / span;
            normalized = Math.max(0.0f, Math.min(1.0f, normalized));
            float x = left + (i * step);
            float y = graphBottom - (normalized * GRAPH_HEIGHT_PX);
            if (i == 0) {
                path.moveTo(x, y);
            }
            else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, graphPaint);
    }
}
