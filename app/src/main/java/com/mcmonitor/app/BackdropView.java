package com.mcmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;

/** Мягкий тёмно-синий фон с парой нежных подсветок, как в десктопном виджете. */
public class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient background;
    private RadialGradient mintGlow;
    private RadialGradient violetGlow;
    private RadialGradient topGlow;
    private int accentColor = 0xFF7EE0A3;

    public BackdropView(Context context) {
        this(context, null);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAccentColor(int color) {
        if (accentColor == color) {
            return;
        }
        accentColor = color;
        if (getWidth() > 0 && getHeight() > 0) {
            rebuildGradients(getWidth(), getHeight());
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }
        rebuildGradients(width, height);
    }

    private void rebuildGradients(int width, int height) {
        int darker = adjustColor(accentColor, 0.18f);
        int glow = withAlpha(accentColor, 34);
        int softGlow = withAlpha(accentColor, 10);
        int neighbor = rotateHue(accentColor, 38f);
        int neighborGlow = withAlpha(neighbor, 30);
        int neighborSoftGlow = withAlpha(neighbor, 12);
        background = new LinearGradient(
                0f, 0f, 0f, height,
                0xFF0F1219, darker,
                Shader.TileMode.CLAMP);

        mintGlow = new RadialGradient(
                width * 0.08f, height * 0.06f, width * 0.75f,
                new int[]{glow, softGlow, withAlpha(accentColor, 0)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);

        violetGlow = new RadialGradient(
                width * 1.02f, height * 0.98f, width * 0.8f,
                new int[]{neighborGlow, neighborSoftGlow, withAlpha(neighbor, 0)},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);

        topGlow = new RadialGradient(
                width * 0.55f, -height * 0.25f, width * 0.85f,
                new int[]{0x14FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
    }

    private int adjustColor(int color, float factor) {
        int red = Math.round(Color.red(color) * factor);
        int green = Math.round(Color.green(color) * factor);
        int blue = Math.round(Color.blue(color) * factor);
        return Color.rgb(red, green, blue);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int rotateHue(int color, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees) % 360f;
        return Color.HSVToColor(hsv);
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
