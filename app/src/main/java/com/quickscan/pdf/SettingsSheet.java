package com.quickscan.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/** Bottom sheet with the table layout settings; slides up, and its preview morphs live. */
final class SettingsSheet {

    private SettingsSheet() {
    }

    static void show(Activity activity, Runnable onClosed) {
        Dialog dialog = new Dialog(activity, R.style.SheetDialog);
        View sheet = activity.getLayoutInflater().inflate(R.layout.sheet_settings, null);
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        int basePad = sheet.getPaddingBottom();
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), basePad + Ui.systemBars(insets)[3]);
            return insets;
        });

        SharedPreferences prefs = Prefs.of(activity);
        LayoutPreviewView preview = sheet.findViewById(R.id.preview);
        TextView sumPaper = sheet.findViewById(R.id.sum_paper);
        TextView sumMargin = sheet.findViewById(R.id.sum_margin);
        TextView sumPos = sheet.findViewById(R.id.sum_pos);
        Runnable refresh = () -> {
            sumPaper.setText(activity.getString(R.string.sum_paper, Prefs.paperName(activity, Prefs.paper(activity))));
            sumMargin.setText(activity.getString(R.string.sum_margin, Prefs.marginName(activity, Prefs.margin(activity))));
            sumPos.setText(Prefs.positionName(activity, Prefs.position(activity)));
        };
        Runnable morph = () -> preview.setLayout(Prefs.paper(activity), Prefs.margin(activity), Prefs.position(activity),
                Prefs.darken(activity), true);
        preview.setLayout(Prefs.paper(activity), Prefs.margin(activity), Prefs.position(activity), Prefs.darken(activity), false);
        refresh.run();

        SegmentedControl paper = sheet.findViewById(R.id.seg_paper);
        paper.setTextSize(13);
        paper.setOptions("A4", "A5", activity.getString(R.string.paper_original));
        paper.select(Prefs.indexOf(Prefs.PAPERS, Prefs.paper(activity)), false);
        paper.setOnSelectListener(i -> {
            prefs.edit().putString(Prefs.PAPER, Prefs.PAPERS[i]).apply();
            refresh.run();
            morph.run();
        });

        SegmentedControl margin = sheet.findViewById(R.id.seg_margin);
        margin.setTextSize(13);
        margin.setOptions(activity.getString(R.string.margin_narrow), activity.getString(R.string.margin_standard),
                activity.getString(R.string.margin_wide));
        margin.select(Prefs.indexOf(Prefs.MARGINS, Prefs.margin(activity)), false);
        margin.setOnSelectListener(i -> {
            prefs.edit().putString(Prefs.MARGIN, Prefs.MARGINS[i]).apply();
            refresh.run();
            morph.run();
        });

        SegmentedControl position = sheet.findViewById(R.id.seg_pos);
        position.setTextSize(13);
        position.setOptions(activity.getString(R.string.pos_top_short), activity.getString(R.string.pos_center_short));
        position.select(Prefs.indexOf(Prefs.POSITIONS, Prefs.position(activity)), false);
        position.setOnSelectListener(i -> {
            prefs.edit().putString(Prefs.POSITION, Prefs.POSITIONS[i]).apply();
            refresh.run();
            morph.run();
        });

        SpringSwitch uniform = sheet.findViewById(R.id.switch_uniform);
        uniform.setChecked(Prefs.uniform(activity));
        uniform.setOnChangeListener((v, on) -> prefs.edit().putBoolean(Prefs.UNIFORM, on).apply());
        SpringSwitch darken = sheet.findViewById(R.id.switch_darken);
        darken.setChecked(Prefs.darken(activity));
        darken.setOnChangeListener((v, on) -> {
            prefs.edit().putBoolean(Prefs.DARKEN, on).apply();
            morph.run();
        });

        View done = sheet.findViewById(R.id.btn_done);
        Ui.pressable(done);
        Runnable close = () -> sheet.animate().translationY(sheet.getHeight()).setDuration(240)
                .setInterpolator(new AccelerateInterpolator(1.5f)).withEndAction(dialog::dismiss).start();
        done.setOnClickListener(v -> {
            Ui.hapticConfirm(v);
            close.run();
        });
        sheet.findViewById(R.id.btn_close).setOnClickListener(v -> close.run());
        dialog.setOnDismissListener(d -> onClosed.run());

        dialog.setOnShowListener(d -> {
            sheet.setTranslationY(sheet.getHeight() > 0 ? sheet.getHeight() : Ui.dp(activity, 700));
            sheet.animate().translationY(0).setDuration(550).setInterpolator(new OvershootInterpolator(0.8f)).start();
        });
        dialog.show();
    }
}
