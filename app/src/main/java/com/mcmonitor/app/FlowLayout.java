package com.mcmonitor.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Простой контейнер, который переносит чипсы ников на новую строку. */
public class FlowLayout extends ViewGroup {

    private int horizontalSpacing;
    private int verticalSpacing;

    public FlowLayout(Context context) {
        this(context, null);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        horizontalSpacing = Math.round(6f * density);
        verticalSpacing = Math.round(6f * density);
    }

    public void setSpacing(int horizontalPx, int verticalPx) {
        horizontalSpacing = horizontalPx;
        verticalSpacing = verticalPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int available = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : widthSize - getPaddingLeft() - getPaddingRight();

        int lineWidth = 0;
        int lineHeight = 0;
        int contentWidth = 0;
        int contentHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int childHeight = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;

            boolean wrap = lineWidth > 0 && lineWidth + horizontalSpacing + childWidth > available;
            if (wrap) {
                contentWidth = Math.max(contentWidth, lineWidth);
                contentHeight += lineHeight + verticalSpacing;
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth += (lineWidth > 0 ? horizontalSpacing : 0) + childWidth;
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }

        contentWidth = Math.max(contentWidth, lineWidth);
        contentHeight += lineHeight;

        int measuredWidth = resolveSize(
                Math.max(contentWidth + getPaddingLeft() + getPaddingRight(), getSuggestedMinimumWidth()),
                widthMeasureSpec);
        int measuredHeight = resolveSize(
                Math.max(contentHeight + getPaddingTop() + getPaddingBottom(), getSuggestedMinimumHeight()),
                heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = getWidth() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int childHeight = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;

            if (x > getPaddingLeft() && x + childWidth > available) {
                x = getPaddingLeft();
                y += lineHeight + verticalSpacing;
                lineHeight = 0;
            }

            int childLeft = x + params.leftMargin;
            int childTop = y + params.topMargin;
            child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());

            x += childWidth + horizontalSpacing;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    public static class LayoutParams extends MarginLayoutParams {

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            super(source);
        }
    }
}
