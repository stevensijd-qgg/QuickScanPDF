package com.quickscan.pdf;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A row of options with a white pill that glides to the selected one. */
public class SegmentedControl extends FrameLayout {

    interface OnSelectListener {
        void onSelect(int index);
    }

    private final View pill;
    private final LinearLayout row;
    private int selected;
    private boolean gliding;
    private float textSize = 14;
    private OnSelectListener listener;

    public SegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        int pad = Ui.dp(context, 4);
        setPadding(pad, pad, pad, pad);
        setBackgroundResource(R.drawable.segment_track);
        pill = new View(context);
        pill.setBackgroundResource(R.drawable.segment_pill);
        pill.setElevation(Ui.dp(context, 1));
        addView(pill, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // keep the labels above the pill's elevation
        row.setElevation(Ui.dp(context, 2));
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setTextSize(float sp) {
        textSize = sp;
    }

    void setOnSelectListener(OnSelectListener l) {
        listener = l;
    }

    void setOptions(String... labels) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView option = new TextView(getContext());
            option.setText(labels[i]);
            option.setGravity(Gravity.CENTER);
            option.setTextSize(textSize);
            option.setTextColor(getContext().getColor(R.color.text));
            option.setClickable(true);
            option.setFocusable(true);
            option.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            int index = i;
            option.setOnClickListener(v -> {
                if (index != selected) {
                    Ui.hapticTick(v);
                    select(index, true);
                    if (listener != null) {
                        listener.onSelect(index);
                    }
                }
            });
            Ui.pressable(option);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                lp.setMarginStart(Ui.dp(getContext(), 4));
            }
            row.addView(option, lp);
        }
        select(Math.min(selected, labels.length - 1), false);
    }

    int getSelected() {
        return selected;
    }

    void select(int index, boolean animate) {
        selected = index;
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView option = (TextView) row.getChildAt(i);
            boolean on = i == index;
            option.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            option.setSelected(on);
        }
        View target = row.getChildAt(index);
        if (target == null || target.getWidth() == 0) {
            requestLayout();
            return;
        }
        float x = target.getLeft() - row.getChildAt(0).getLeft();
        if (animate) {
            gliding = true;
            pill.animate().translationX(x).setDuration(380).setInterpolator(new OvershootInterpolator(1.3f))
                    .withEndAction(() -> gliding = false).start();
        } else {
            pill.animate().cancel();
            gliding = false;
            pill.setTranslationX(x);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View first = row.getChildAt(0);
        if (first == null) {
            return;
        }
        int l = row.getLeft() + first.getLeft();
        pill.layout(l, row.getTop(), l + first.getWidth(), row.getTop() + first.getHeight());
        View target = row.getChildAt(selected);
        if (target != null && !gliding) {
            pill.setTranslationX(target.getLeft() - first.getLeft());
        }
    }
}
