package com.quickscan.pdf;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Miniature page showing where the straightened table will sit; morphs when settings change. */
public class LayoutPreviewView extends View {

    // current and target geometry in dp: paper w, paper h, side padding, block top
    private final float[] now = new float[4];
    private final float[] from = new float[4];
    private final float[] to = new float[4];
    private int inkNow, inkFrom, inkTo;
    private float lineNow, lineFrom, lineTo;
    private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paperEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private ValueAnimator animator;
    private boolean ready;

    public LayoutPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paper.setColor(0xFFFFFFFF);
        paperEdge.setColor(context.getColor(R.color.stroke));
        paperEdge.setStyle(Paint.Style.STROKE);
        paperEdge.setStrokeWidth(Ui.dp(context, 1));
        ink.setStyle(Paint.Style.STROKE);
        header.setColor(0xFFB9B4AB);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setLayout(String paperKind, String margin, String position, boolean darken, boolean animate) {
        float pw = "orig".equals(paperKind) ? 118 : "a5".equals(paperKind) ? 96 : 106;
        float ph = "a5".equals(paperKind) ? 136 : 150;
        float frac = (float) Prefs.marginFraction(margin);
        float blockH = ph * 0.64f;
        float top = "center".equals(position) ? (ph - blockH) / 2f : ph * frac * 0.8f;
        float[] target = {pw, ph, pw * frac, top};
        int inkColor = darken ? 0xFF141413 : 0xFF6F6B64;
        float line = darken ? 1.4f : 1f;
        if (!animate || !ready) {
            System.arraycopy(target, 0, now, 0, 4);
            inkNow = inkColor;
            lineNow = line;
            ready = true;
            invalidate();
            return;
        }
        System.arraycopy(now, 0, from, 0, 4);
        System.arraycopy(target, 0, to, 0, 4);
        inkFrom = inkNow;
        inkTo = inkColor;
        lineFrom = lineNow;
        lineTo = line;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(450);
        animator.setInterpolator(new OvershootInterpolator(1.3f));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 4; i++) {
                now[i] = from[i] + (to[i] - from[i]) * t;
            }
            float c = Math.max(0f, Math.min(1f, t));
            inkNow = (Integer) argb.evaluate(c, inkFrom, inkTo);
            lineNow = lineFrom + (lineTo - lineFrom) * c;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float pw = now[0] * d, ph = now[1] * d, pad = now[2] * d, top = now[3] * d;
        float left = (getWidth() - pw) / 2f, upper = (getHeight() - ph) / 2f;
        rect.set(left, upper, left + pw, upper + ph);
        canvas.drawRect(rect, paper);
        canvas.drawRect(rect, paperEdge);

        float bx = left + pad, bw = pw - 2 * pad, by = upper + top, bh = now[1] * 0.64f * d;
        inkFill.setColor(inkNow);
        // title bar, header line
        canvas.drawRoundRect(bx + bw * 0.2f, by, bx + bw * 0.8f, by + 3 * d, 1.5f * d, 1.5f * d, inkFill);
        canvas.drawRect(bx, by + 6 * d, bx + bw, by + 7.5f * d, header);
        // table
        float tt = by + 10.5f * d;
        ink.setColor(inkNow);
        ink.setStrokeWidth(lineNow * d);
        canvas.drawRect(bx, tt, bx + bw, by + bh, ink);
        float y = tt;
        float[] rows = {8, 8, 8, 12};
        for (float r : rows) {
            y += r * d;
            canvas.drawLine(bx, y, bx + bw, y, ink);
        }
        float last = by + bh - 16 * d;
        if (last > y + 4 * d) {
            canvas.drawLine(bx, last, bx + bw, last, ink);
        }
    }
}
