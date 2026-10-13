package com.vironite.retrievex.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** FrameLayout whose height follows its width: height = width / ratio. Used for the media block of a post. */
public class RatioFrameLayout extends FrameLayout {
    private float ratio = 16f / 9f;

    public RatioFrameLayout(Context c) {
        super(c);
    }

    public RatioFrameLayout(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    /** Width divided by height. */
    public void setRatio(float ratio) {
        if (ratio > 0f && Math.abs(ratio - this.ratio) > 0.001f) {
            this.ratio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED || w <= 0) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        int h = Math.round(w / ratio);
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
