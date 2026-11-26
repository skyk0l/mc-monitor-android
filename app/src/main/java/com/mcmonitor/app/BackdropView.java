package com.mcmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.os.SystemClock;

/** Мягкий тёмно-синий фон с парой нежных подсветок, как в десктопном виджете. */
public class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient background;
    private RadialGradient mintGlow;
    private RadialGradient violetGlow;
    private RadialGradient topGlow;
    private int accentColor = 0xFF7EE0A3;
    private int sceneStyle;
    private int pattern;

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

    public void setSceneStyle(int style) {
        int selected = Math.max(0, Math.min(4, style));
        if (sceneStyle == selected) {
            return;
        }
        sceneStyle = selected;
        if (getWidth() > 0 && getHeight() > 0) {
            rebuildGradients(getWidth(), getHeight());
        }
        invalidate();
    }

    public void setPattern(int selectedPattern) {
        int selected = Math.max(0, Math.min(2, selectedPattern));
        if (pattern == selected) {
            return;
        }
        pattern = selected;
        invalidate();
    }

    public static int topBarColor(int accentColor) {
        int base = 0xFF0F1219;
        int amount = 8;
        return Color.rgb(
                (Color.red(base) * (100 - amount) + Color.red(accentColor) * amount) / 100,
                (Color.green(base) * (100 - amount) + Color.green(accentColor) * amount) / 100,
                (Color.blue(base) * (100 - amount) + Color.blue(accentColor) * amount) / 100);
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
        int[][] scenes = {
                {0xFF19292B, 0xFF203936},
                {0xFF1B2C3B, 0xFF213C50},
                {0xFF29263E, 0xFF35334F},
                {0xFF382A31, 0xFF423632},
                {0xFF252A30, 0xFF2C3036}
        };
        int[] scene = scenes[sceneStyle];
        int glow = withAlpha(accentColor, 48);
        int softGlow = withAlpha(accentColor, 18);
        int neighbor = rotateHue(accentColor, 38f);
        int neighborGlow = withAlpha(neighbor, 42);
        int neighborSoftGlow = withAlpha(neighbor, 18);
        background = new LinearGradient(
                0f, 0f, 0f, height,
                scene[0], scene[1],
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
        drawPattern(canvas, width, height);
    }

    private void drawPattern(Canvas canvas, int width, int height) {
        if (pattern == 0) {
            return;
        }
        float progress = (SystemClock.uptimeMillis() % 18000L) / 18000f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        paint.setColor(Color.argb(22, 255, 255, 255));
        if (pattern == 1) {
            float drift = (progress - 0.5f) * width * 0.12f;
            for (int i = 0; i < 5; i++) {
                float y = height * (0.32f + i * 0.13f);
                Path wave = new Path();
                wave.moveTo(0f, y);
                wave.cubicTo(
                        width * 0.28f + drift, y - height * 0.08f,
                        width * 0.68f - drift, y + height * 0.08f,
                        width, y);
                canvas.drawPath(wave, paint);
            }
        } else {
            float centerX = width * (0.76f + 0.05f * (float) Math.sin(progress * Math.PI * 2));
            float centerY = height * (0.28f + 0.04f * (float) Math.cos(progress * Math.PI * 2));
            for (int radius : new int[]{36, 68, 100, 132}) {
                float scaledRadius = radius * getResources().getDisplayMetrics().density;
                canvas.save();
                canvas.scale(1f, 0.62f, centerX, centerY);
                canvas.drawCircle(centerX, centerY, scaledRadius, paint);
                canvas.restore();
            }
        }
        paint.setStyle(Paint.Style.FILL);
        if (isShown()) {
            postInvalidateDelayed(80L);
        }
    }
}
