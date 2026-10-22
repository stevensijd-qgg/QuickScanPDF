package com.quickscan.pdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Accent disc whose white check mark draws itself in. */
public class CheckMarkView extends View {

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check = new Path();
    private final Path partial = new Path();
    private float discScale = 1f;
    private float drawn = 1f;

    public CheckMarkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        disc.setColor(context.getColor(R.color.primary));
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void play() {
        discScale = 0.6f;
        drawn = 0f;
        ValueAnimator grow = ValueAnimator.ofFloat(0.6f, 1f);
        grow.setDuration(320);
        grow.setInterpolator(new OvershootInterpolator(2.5f));
        grow.addUpdateListener(a -> {
            discScale = (float) a.getAnimatedValue();
            invalidate();
        });
        grow.start();
        ValueAnimator draw = ValueAnimator.ofFloat(0f, 1f);
        draw.setStartDelay(150);
        draw.setDuration(450);
        draw.setInterpolator(new DecelerateInterpolator(1.6f));
        draw.addUpdateListener(a -> {
            drawn = (float) a.getAnimatedValue();
            invalidate();
        });
        draw.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        canvas.drawCircle(cx, cy, size / 2f * discScale, disc);
        // check mark from a 24-unit box: M5 12.5 l4.5 4.5 L19 7.5
        float u = size / 24f * 0.62f, ox = cx - 12 * u, oy = cy - 12 * u;
        check.reset();
        check.moveTo(ox + 5 * u, oy + 12.5f * u);
        check.lineTo(ox + 9.5f * u, oy + 17 * u);
        check.lineTo(ox + 19 * u, oy + 7.5f * u);
        stroke.setStrokeWidth(Math.max(2f, 3 * u));
        PathMeasure measure = new PathMeasure(check, false);
        partial.reset();
        measure.getSegment(0, measure.getLength() * drawn, partial, true);
        canvas.drawPath(partial, stroke);
    }
}
