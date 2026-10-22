package com.quickscan.pdf;

import android.animation.AnimatorInflater;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;

/** Small helpers shared by the screens: sizes, insets, press feedback, haptics, entrances. */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    /** targetSdk 35+ draws edge-to-edge on Android 15+, so keep content clear of the system bars. */
    static void applySystemBarInsets(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] bars = systemBars(insets);
            v.setPadding(left + bars[0], top + bars[1], right + bars[2], bottom + bars[3]);
            return insets;
        });
    }

    /** left, top, right, bottom system bar (and cutout) insets. */
    static int[] systemBars(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new int[]{bars.left, bars.top, bars.right, bars.bottom};
        }
        return new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }

    /** Shrinks the view while it is held and springs it back on release. */
    static void pressable(View... views) {
        for (View v : views) {
            v.setStateListAnimator(AnimatorInflater.loadStateListAnimator(v.getContext(), R.animator.press));
        }
    }

    /** Makes a clickable layout read as a button to screen readers. */
    static void asButton(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    static void hapticConfirm(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static void hapticTick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    static void hapticGrab(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.GESTURE_START : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static void hapticToggle(View v, boolean on) {
        if (Build.VERSION.SDK_INT >= 34) {
            v.performHapticFeedback(on ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Quick overshooting "pop" used when something is confirmed. */
    static void pop(View v) {
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Fades and lifts a view into place. */
    static void rise(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(450)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }
}
