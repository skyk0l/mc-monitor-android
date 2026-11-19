package com.mcmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Заголовок «MC MONITOR» кубическими буквами: плоские квадраты с объёмным
 * срезом, как у надписей в Minecraft.
 */
public class BlockTitleView extends View {

    private static final int ROWS = 7;
    private static final int COLS = 5;
    /** Пустая колонка между буквами, в тех же «пикселях». */
    private static final int GAP = 1;
    /** Глубина объёма в пикселях буквы. */
    private static final float DEPTH = 1.7f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private String text = "MC MONITOR";
    private float unit = 6f;
    private int[][] glyphs = new int[0][];
    private float textWidth;

    public BlockTitleView(Context context) {
        this(context, null);
    }

    public BlockTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        depthPaint.setColor(0xFF315D3E);
        paint.setShader(null);
    }

    public void setText(String value) {
        text = value == null ? "" : value.toUpperCase();
        glyphs = buildGlyphs(text);
        requestLayout();
        invalidate();
    }

    public static int[][] buildGlyphs(String value) {
        int[][] result = new int[value.length()][];
        for (int i = 0; i < value.length(); i++) {
            result[i] = glyph(value.charAt(i));
        }
        return result;
    }

    /** 7 строк по 5 символов, '1' — закрашенный квадрат. */
    private static int[] glyph(char c) {
        switch (c) {
            case 'A': return new int[]{0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
            case 'B': return new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110};
            case 'C': return new int[]{0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111};
            case 'D': return new int[]{0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110};
            case 'E': return new int[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111};
            case 'F': return new int[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000};
            case 'G': return new int[]{0b01111, 0b10000, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110};
            case 'H': return new int[]{0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
            case 'I': return new int[]{0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111};
            case 'J': return new int[]{0b00111, 0b00010, 0b00010, 0b00010, 0b10010, 0b10010, 0b01100};
            case 'K': return new int[]{0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001};
            case 'L': return new int[]{0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111};
            case 'M': return new int[]{0b10001, 0b11011, 0b10101, 0b10001, 0b10001, 0b10001, 0b10001};
            case 'N': return new int[]{0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001};
            case 'O': return new int[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
            case 'P': return new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000};
            case 'Q': return new int[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101};
            case 'R': return new int[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001};
            case 'S': return new int[]{0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110};
            case 'T': return new int[]{0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100};
            case 'U': return new int[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
            case 'V': return new int[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100};
            case 'W': return new int[]{0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001};
            case 'X': return new int[]{0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001};
            case 'Y': return new int[]{0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100};
            case 'Z': return new int[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111};
            case '0': return new int[]{0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110};
            case '1': return new int[]{0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
            case '2': return new int[]{0b01110, 0b10001, 0b00001, 0b00110, 0b01000, 0b10000, 0b11111};
            case '3': return new int[]{0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110};
            case '4': return new int[]{0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010};
            case '5': return new int[]{0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110};
            case '6': return new int[]{0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110};
            case '7': return new int[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000};
            case '8': return new int[]{0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110};
            case '9': return new int[]{0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100};
            default: return new int[]{0, 0, 0, 0, 0, 0, 0};
        }
    }

    private int totalUnits() {
        int count = text.length();
        if (count == 0) {
            return 1;
        }
        return count * COLS + (count - 1) * GAP;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (available <= 0) {
            available = 240;
        }
        unit = available / (float) totalUnits();
        textWidth = totalUnits() * unit;

        int height = Math.round(ROWS * unit + DEPTH * unit
                + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(
                resolveSize(Math.round(textWidth + getPaddingLeft() + getPaddingRight()), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (glyphs.length == 0) {
            glyphs = buildGlyphs(text);
        }
        paint.setShader(new LinearGradient(
                0f, getPaddingTop(), 0f, getPaddingTop() + ROWS * unit,
                0xFFF0FFE9, 0xFF9BD891, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (glyphs.length == 0) {
            return;
        }
        float x0 = getPaddingLeft();
        float y0 = getPaddingTop();
        float step = DEPTH * unit;

        // Дальний срез, потом ближний — получается мягкий объём.
        for (int layer = 2; layer >= 1; layer--) {
            depthPaint.setColor(layer == 2 ? 0xFF274A32 : 0xFF4F8B58);
            float offset = (step * layer) / 2f;
            for (int g = 0; g < glyphs.length; g++) {
                int[] rows = glyphs[g];
                float gx = x0 + g * (COLS + GAP) * unit + offset;
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLS; col++) {
                        if ((rows[row] & (1 << (COLS - 1 - col))) == 0) {
                            continue;
                        }
                        rect.set(
                                gx + col * unit,
                                y0 + row * unit + offset,
                                gx + (col + 1) * unit + 0.25f,
                                y0 + (row + 1) * unit + 0.25f);
                        canvas.drawRect(rect, depthPaint);
                    }
                }
            }
        }

        for (int g = 0; g < glyphs.length; g++) {
            int[] rows = glyphs[g];
            float gx = x0 + g * (COLS + GAP) * unit;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    if ((rows[row] & (1 << (COLS - 1 - col))) == 0) {
                        continue;
                    }
                    rect.set(
                            gx + col * unit,
                            y0 + row * unit,
                            gx + (col + 1) * unit + 0.25f,
                            y0 + (row + 1) * unit + 0.25f);
                    canvas.drawRect(rect, paint);
                }
            }
        }
    }
}
