package com.quickscan.pdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Thin progress line that eases toward each new value. */
public class ProgressLineView extends View {

    private final Paint track = new Paint();
    private final Paint fill = new Paint();
    private float value;
    private ValueAnimator animator;

    public ProgressLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setColor(context.getColor(R.color.divider));
        fill.setColor(context.getColor(R.color.primary));
    }

    void reset() {
        if (animator != null) animator.cancel();
        value = 0f;
        invalidate();
    }

    void setProgress(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(value, target);
        animator.setDuration(420);
        animator.setInterpolator(new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(a -> {
            value = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), track);
        canvas.drawRect(0, 0, getWidth() * value, getHeight(), fill);
    }
}
