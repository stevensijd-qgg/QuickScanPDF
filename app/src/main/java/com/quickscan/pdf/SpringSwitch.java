package com.quickscan.pdf;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.widget.Checkable;
import android.widget.Switch;

/** On/off switch whose knob springs across with a slight overshoot. 56×44 dp touch area. */
public class SpringSwitch extends View implements Checkable {

    interface OnChangeListener {
        void onChange(SpringSwitch view, boolean checked);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final int onColor;
    private final int offColor;
    private boolean checked;
    /** 0 = off, 1 = on; overshoots a little while animating. */
    private float position;
    private ValueAnimator animator;
    private OnChangeListener listener;

    public SpringSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        onColor = context.getColor(R.color.primary);
        offColor = context.getColor(R.color.track_off);
        knobPaint.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            knobPaint.setShadowLayer(Ui.dp(context, 1.5f), 0, Ui.dp(context, 0.5f), 0x47000000);
        }
        setClickable(true);
        setFocusable(true);
    }

    void setOnChangeListener(OnChangeListener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(Ui.dp(getContext(), 56), widthMeasureSpec),
                resolveSize(Ui.dp(getContext(), 44), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float tw = Ui.dp(getContext(), 50), th = Ui.dp(getContext(), 30), pad = Ui.dp(getContext(), 3);
        float left = (getWidth() - tw) / 2f, top = (getHeight() - th) / 2f;
        track.set(left, top, left + tw, top + th);
        float colorT = Math.max(0f, Math.min(1f, position));
        trackPaint.setColor((Integer) argb.evaluate(colorT, offColor, onColor));
        canvas.drawRoundRect(track, th / 2f, th / 2f, trackPaint);
        float knob = th - 2 * pad;
        float travel = tw - 2 * pad - knob;
        float cx = left + pad + knob / 2f + travel * position;
        canvas.drawCircle(cx, top + th / 2f, knob / 2f, knobPaint);
    }

    @Override
    public boolean performClick() {
        setChecked(!checked, true);
        Ui.hapticToggle(this, checked);
        if (listener != null) {
            listener.onChange(this, checked);
        }
        return super.performClick();
    }

    @Override
    public void setChecked(boolean value) {
        setChecked(value, false);
    }

    void setChecked(boolean value, boolean animate) {
        checked = value;
        float target = value ? 1f : 0f;
        if (animator != null) {
            animator.cancel();
        }
        if (!animate) {
            position = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(380);
        animator.setInterpolator(new OvershootInterpolator(2.2f));
        animator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    @Override
    public void toggle() {
        setChecked(!checked, true);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Switch.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
