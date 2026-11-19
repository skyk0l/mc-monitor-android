package com.mcmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Мягкий тёмно-синий фон с парой нежных подсветок, как в десктопном виджете. */
public class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient background;
    private RadialGradient mintGlow;
    private RadialGradient violetGlow;
    private RadialGradient topGlow;

    public BackdropView(Context context) {
        this(context, null);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }
        background = new LinearGradient(
                0f, 0f, 0f, height,
                0xFF0F1219, 0xFF1A1E2A,
                Shader.TileMode.CLAMP);

        mintGlow = new RadialGradient(
                width * 0.08f, height * 0.06f, width * 0.75f,
                new int[]{0x227EE0A3, 0x0A7EE0A3, 0x007EE0A3},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);

        violetGlow = new RadialGradient(
                width * 1.02f, height * 0.98f, width * 0.8f,
                new int[]{0x2A7C6CFF, 0x0F7C6CFF, 0x007C6CFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);

        topGlow = new RadialGradient(
                width * 0.55f, -height * 0.25f, width * 0.85f,
                new int[]{0x14FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        if (background == null) {
            onSizeChanged(width, height, 0, 0);
        }

        paint.setShader(background);
        canvas.drawRect(0f, 0f, width, height, paint);

        paint.setShader(topGlow);
        canvas.drawRect(0f, 0f, width, height, paint);

        paint.setShader(mintGlow);
        canvas.drawRect(0f, 0f, width, height, paint);

        paint.setShader(violetGlow);
        canvas.drawRect(0f, 0f, width, height, paint);

        paint.setShader(null);
    }
}
