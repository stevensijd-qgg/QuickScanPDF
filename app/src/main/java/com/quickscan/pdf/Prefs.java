package com.quickscan.pdf;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, stored in one SharedPreferences file. */
final class Prefs {

    static final String CLEAN = "clean_paper";
    static final String TABLE = "straighten_table";
    static final String PAPER = "table_paper";
    static final String MARGIN = "table_margin";
    static final String POSITION = "table_pos";
    static final String UNIFORM = "table_uniform";
    static final String DARKEN = "table_darken";

    static final String[] PAPERS = {"a4", "a5", "orig"};
    static final String[] MARGINS = {"narrow", "std", "wide"};
    static final String[] POSITIONS = {"top", "center"};

    /** A5 page width in points; A4 is {@code JpegPdfWriter}'s default. */
    private static final float A5_WIDTH_PT = 419.53f;
    private static final float A4_WIDTH_PT = 595.28f;

    private Prefs() {
    }

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean clean(Context c) {
        return of(c).getBoolean(CLEAN, true);
    }

    static boolean table(Context c) {
        return of(c).getBoolean(TABLE, true);
    }

    static boolean darken(Context c) {
        return of(c).getBoolean(DARKEN, false);
    }

    static String paper(Context c) {
        return of(c).getString(PAPER, "a4");
    }

    static String margin(Context c) {
        return of(c).getString(MARGIN, "std");
    }

    static String position(Context c) {
        return of(c).getString(POSITION, "top");
    }

    static boolean uniform(Context c) {
        return of(c).getBoolean(UNIFORM, true);
    }

    static double marginFraction(String margin) {
        switch (margin) {
            case "narrow":
                return 0.05;
            case "wide":
                return 0.13;
            default:
                return 0.08;
        }
    }

    /** Page layout for straightened tables, capped at {@code maxHeight} pixels. */
    static TableRectifier.Layout layout(Context c, int maxHeight) {
        String paper = paper(c);
        double side = marginFraction(margin(c));
        double ratio = "orig".equals(paper) ? 0 : TableRectifier.A4_RATIO; // A5 has A4's shape
        int cap = "a5".equals(paper) ? Math.min(maxHeight, 2480) : maxHeight;
        return new TableRectifier.Layout(ratio, side, side * 0.8, "center".equals(position(c)), uniform(c), cap);
    }

    static float pageWidthPt(Context c) {
        return "a5".equals(paper(c)) ? A5_WIDTH_PT : A4_WIDTH_PT;
    }

    static String paperName(Context c, String paper) {
        switch (paper) {
            case "a5":
                return "A5";
            case "orig":
                return c.getString(R.string.paper_original);
            default:
                return "A4";
        }
    }

    static String marginName(Context c, String margin) {
        switch (margin) {
            case "narrow":
                return c.getString(R.string.margin_narrow);
            case "wide":
                return c.getString(R.string.margin_wide);
            default:
                return c.getString(R.string.margin_standard);
        }
    }

    static String positionName(Context c, String position) {
        return c.getString("center".equals(position) ? R.string.position_center : R.string.position_top);
    }

    /** One line for the home screen, e.g. "A4 · 标准边距 · 表格靠上". */
    static String summary(Context c) {
        return c.getString(R.string.settings_summary_format, paperName(c, paper(c)),
                marginName(c, margin(c)), positionName(c, position(c)));
    }

    static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }
}
