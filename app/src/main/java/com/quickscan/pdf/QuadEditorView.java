package com.quickscan.pdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Shows a page photo with the table frame on top: four draggable corners, a magnifier that
 * follows the corner being moved, a scan sweep while detecting, and springy corner moves.
 * Corners are kept in image pixels: TL, TR, BR, BL as x,y pairs.
 */
public class QuadEditorView extends View {

    interface Listener {
        void onDragEnd();
    }

    private static final float ZOOM = 2.6f;

    private Bitmap image;
    private final float[] corners = new float[8];
    private final float[] viewPts = new float[8];
    private final Matrix toView = new Matrix();
    private final Matrix toImage = new Matrix();
    private final RectF imageRect = new RectF();

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loupeRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint sweepPaint = new Paint();
    private final Paint sweepLine = new Paint();
    private final Path quad = new Path();
    private final Path loupeClip = new Path();
    private final int workspace;
    private final int accent;

    private int drag = -1;
    private int active;
    private final float[] handleScale = {1, 1, 1, 1};
    private float loupeSide;      // 0 = left, 1 = right
    private float loupeFocus;     // 0 = resting, 1 = dragging
    private float sweep = -1f;    // < 0: no sweep running
    private Listener listener;
    private ValueAnimator cornerAnim;

    public QuadEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        workspace = context.getColor(R.color.workspace);
        accent = context.getColor(R.color.primary);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadowPaint.setMaskFilter(new BlurMaskFilter(dp(14), BlurMaskFilter.Blur.NORMAL));
        }
        shadowPaint.setColor(0x73000000);
        fillPaint.setColor(accent);
        linePaint.setColor(accent);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        handleFill.setColor(0xFFFFFFFF);
        handleRing.setColor(accent);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(dp(3));
        haloPaint.setColor(accent);
        haloPaint.setAlpha(56);
        loupeRing.setColor(0xFFFFFFFF);
        loupeRing.setStyle(Paint.Style.STROKE);
        loupeRing.setStrokeWidth(dp(3));
        crossPaint.setColor(accent);
        crossPaint.setStrokeWidth(dp(1));
        shadePaint.setColor(0xFF141413);
        sweepLine.setColor(accent);
        sweepLine.setStrokeWidth(dp(2));
        setContentDescription(context.getString(R.string.editor_description));
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setImage(Bitmap bitmap, float[] start) {
        image = bitmap;
        System.arraycopy(start, 0, corners, 0, 8);
        updateMatrix();
        loupeSide = sideFor(active);
        invalidate();
    }

    float[] getCorners() {
        return corners.clone();
    }

    /** Springs the frame to {@code target} with a slight overshoot. */
    void animateTo(float[] target, Runnable end) {
        if (cornerAnim != null) cornerAnim.cancel();
        float[] start = corners.clone();
        cornerAnim = ValueAnimator.ofFloat(0f, 1f);
        cornerAnim.setDuration(520);
        cornerAnim.setInterpolator(new OvershootInterpolator(1.4f));
        cornerAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 8; i++) {
                corners[i] = start[i] + (target[i] - start[i]) * t;
            }
            invalidate();
        });
        if (end != null) {
            cornerAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    end.run();
                }
            });
        }
        cornerAnim.start();
    }

    /** Sweeps a scan line over the photo once, then runs {@code end}. */
    void playSweep(Runnable end) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(750);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            sweep = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                sweep = -1f;
                invalidate();
                if (end != null) end.run();
            }
        });
        a.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateMatrix();
    }

    private void updateMatrix() {
        if (image == null || getWidth() == 0) {
            return;
        }
        float pad = dp(24);
        float scale = Math.min((getWidth() - 2 * pad) / image.getWidth(), (getHeight() - 2 * pad) / image.getHeight());
        float dx = (getWidth() - image.getWidth() * scale) / 2f, dy = (getHeight() - image.getHeight() * scale) / 2f;
        toView.setScale(scale, scale);
        toView.postTranslate(dx, dy);
        toView.invert(toImage);
        imageRect.set(dx, dy, dx + image.getWidth() * scale, dy + image.getHeight() * scale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(workspace);
        if (image == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // blur masks only render on hardware canvases from Android 9
            canvas.drawRect(imageRect.left, imageRect.top + dp(8), imageRect.right, imageRect.bottom + dp(8), shadowPaint);
        }
        canvas.drawBitmap(image, toView, imagePaint);

        if (sweep >= 0f) {
            float shade = sweep < 0.3f ? sweep / 0.3f : 1f - (sweep - 0.3f) / 0.7f;
            shadePaint.setAlpha((int) (72 * shade));
            canvas.drawRect(imageRect, shadePaint);
            float band = dp(40);
            float y = imageRect.top - band + sweep * (imageRect.height() + band);
            sweepPaint.setShader(new LinearGradient(0, y, 0, y + band, 0x00FFFFFF, (accent & 0x00FFFFFF) | 0x59000000, Shader.TileMode.CLAMP));
            canvas.drawRect(imageRect.left, y, imageRect.right, y + band, sweepPaint);
            canvas.drawLine(imageRect.left, y + band, imageRect.right, y + band, sweepLine);
        }

        toView.mapPoints(viewPts, corners);
        quad.reset();
        quad.moveTo(viewPts[0], viewPts[1]);
        for (int i = 1; i < 4; i++) {
            quad.lineTo(viewPts[2 * i], viewPts[2 * i + 1]);
        }
        quad.close();
        fillPaint.setAlpha(drag < 0 ? 36 : 20);
        canvas.drawPath(quad, fillPaint);
        linePaint.setStrokeWidth(dp(2.5f));
        canvas.drawPath(quad, linePaint);

        float radius = dp(12);
        for (int i = 0; i < 4; i++) {
            float cx = viewPts[2 * i], cy = viewPts[2 * i + 1], s = handleScale[i];
            if (s > 1.02f) {
                canvas.drawCircle(cx, cy, (radius + dp(8)) * s, haloPaint);
            }
            canvas.drawCircle(cx, cy, radius * s, handleFill);
            canvas.drawCircle(cx, cy, radius * s - dp(1.5f), handleRing);
        }
        drawLoupe(canvas);
    }

    private void drawLoupe(Canvas canvas) {
        float size = dp(104), margin = dp(14);
        float x = margin + (getWidth() - 2 * margin - size) * loupeSide;
        float cx = x + size / 2f, cy = margin + size / 2f;
        float px = viewPts[2 * active], py = viewPts[2 * active + 1];
        float s = 0.9f + 0.1f * loupeFocus;
        canvas.save();
        canvas.scale(s, s, cx, cy);
        loupeClip.reset();
        loupeClip.addCircle(cx, cy, size / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(loupeClip);
        canvas.drawColor(workspace);
        Matrix zoom = new Matrix();
        zoom.postTranslate(-px, -py);
        zoom.postScale(ZOOM, ZOOM);
        zoom.postTranslate(cx, cy);
        Matrix m = new Matrix(toView);
        m.postConcat(zoom);
        canvas.drawBitmap(image, m, imagePaint);
        Path zoomed = new Path(quad);
        zoomed.transform(zoom);
        linePaint.setStrokeWidth(dp(1.5f));
        canvas.drawPath(zoomed, linePaint);
        canvas.drawLine(cx, cy - dp(14), cx, cy + dp(14), crossPaint);
        canvas.drawLine(cx - dp(14), cy, cx + dp(14), cy, crossPaint);
        canvas.restore();
        canvas.drawCircle(cx, cy, size / 2f, loupeRing);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (image == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                toView.mapPoints(viewPts, corners);
                int hit = -1;
                float best = dp(36);
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(e.getX() - viewPts[2 * i], e.getY() - viewPts[2 * i + 1]);
                    if (d < best) {
                        best = d;
                        hit = i;
                    }
                }
                if (hit < 0) {
                    return false;
                }
                if (cornerAnim != null) cornerAnim.cancel();
                drag = hit;
                active = hit;
                getParent().requestDisallowInterceptTouchEvent(true);
                animateHandle(hit, 1.35f);
                animateLoupe(sideFor(hit), 1f);
                Ui.hapticGrab(this);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag < 0) return false;
                float[] p = {e.getX(), e.getY()};
                toImage.mapPoints(p);
                corners[2 * drag] = Math.max(0, Math.min(image.getWidth(), p[0]));
                corners[2 * drag + 1] = Math.max(0, Math.min(image.getHeight(), p[1]));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (drag < 0) return false;
                animateHandle(drag, 1f);
                animateLoupe(sideFor(drag), 0f);
                drag = -1;
                Ui.hapticTick(this);
                if (listener != null) listener.onDragEnd();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            default:
                return drag >= 0;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** The magnifier sits on the side away from the corner, so the finger never covers it. */
    private float sideFor(int corner) {
        toView.mapPoints(viewPts, corners);
        return viewPts[2 * corner] < getWidth() / 2f ? 1f : 0f;
    }

    private void animateHandle(int index, float target) {
        ValueAnimator a = ValueAnimator.ofFloat(handleScale[index], target);
        a.setDuration(220);
        a.setInterpolator(new OvershootInterpolator(2.5f));
        a.addUpdateListener(v -> {
            handleScale[index] = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    private void animateLoupe(float side, float focus) {
        float side0 = loupeSide, focus0 = loupeFocus;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(340);
        a.setInterpolator(new DecelerateInterpolator(2f));
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            loupeSide = side0 + (side - side0) * t;
            loupeFocus = focus0 + (focus - focus0) * t;
            invalidate();
        });
        a.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
