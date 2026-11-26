package com.mcmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class CatalogHistoryChartView extends View {
    public interface OnSampleSelectedListener {
        void onSampleSelected(CloudSyncClient.CatalogHistorySample sample);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accentColor;
    private final List<CloudSyncClient.CatalogHistorySample> samples = new ArrayList<>();
    private OnSampleSelectedListener listener;
    private int selectedIndex = -1;

    public CatalogHistoryChartView(Context context, int accentColor) {
        super(context);
        this.accentColor = accentColor;
        setMinimumHeight(dp(180));
        setContentDescription(context.getString(R.string.catalog_history_chart));
    }

    public void setOnSampleSelectedListener(OnSampleSelectedListener value) {
        listener = value;
    }

    public void setSamples(List<CloudSyncClient.CatalogHistorySample> values) {
        samples.clear();
        samples.addAll(values);
        selectedIndex = samples.isEmpty() ? -1 : samples.size() - 1;
        invalidate();
        notifySelection();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(28);
        float right = getWidth() - dp(12);
        float top = dp(12);
        float bottom = getHeight() - dp(20);
        if (right <= left || bottom <= top) {
            return;
        }

        paint.setColor(0x557A8C85);
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.STROKE);
        for (int step = 0; step < 4; step++) {
            float y = top + (bottom - top) * step / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        if (samples.isEmpty()) {
            return;
        }

        int maxOnline = 1;
        for (CloudSyncClient.CatalogHistorySample sample : samples) {
            maxOnline = Math.max(maxOnline, sample.onlineCount);
        }
        Path line = new Path();
        float spacing = samples.size() <= 1
                ? 0f : (right - left) / (samples.size() - 1);
        for (int index = 0; index < samples.size(); index++) {
            float x = samples.size() == 1 ? (left + right) / 2f : left + spacing * index;
            float y = bottom - (bottom - top) * samples.get(index).onlineCount / maxOnline;
            if (index == 0) {
                line.moveTo(x, y);
            } else {
                line.lineTo(x, y);
            }
        }
        paint.setColor(accentColor);
        paint.setStrokeWidth(dp(2));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(line, paint);
        for (int index = 0; index < samples.size(); index++) {
            float x = samples.size() == 1 ? (left + right) / 2f : left + spacing * index;
            float y = bottom - (bottom - top) * samples.get(index).onlineCount / maxOnline;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(index == selectedIndex ? accentColor : 0xFF202C27);
            canvas.drawCircle(x, y, dp(index == selectedIndex ? 5 : 3), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(accentColor);
            canvas.drawCircle(x, y, dp(index == selectedIndex ? 5 : 3), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (samples.isEmpty() || (event.getAction() != MotionEvent.ACTION_DOWN
                && event.getAction() != MotionEvent.ACTION_MOVE)) {
            return super.onTouchEvent(event);
        }
        float left = dp(28);
        float right = Math.max(left, getWidth() - dp(12));
        float fraction = (event.getX() - left) / Math.max(1f, right - left);
        selectedIndex = Math.round(Math.max(0f, Math.min(1f, fraction)) * (samples.size() - 1));
        invalidate();
        notifySelection();
        return true;
    }

    private void notifySelection() {
        if (listener != null && selectedIndex >= 0 && selectedIndex < samples.size()) {
            listener.onSampleSelected(samples.get(selectedIndex));
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
