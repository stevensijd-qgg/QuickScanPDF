package com.quickscan.pdf;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/** Loads scanned pages and runs the cleaning / straightening pipeline on them. */
final class PageProcessor {

    /** Long side of the working copy shown while reviewing and adjusting. */
    static final int PREVIEW_SIDE = 1600;
    /** Long side cap for the pages that go into the PDF. */
    private static final int FINAL_SIDE = 4096;
    /** Long side of the review thumbnails. */
    static final int THUMB_SIDE = 520;
    /** Height cap of the straightened preview the thumbnail is cut from. */
    private static final int PREVIEW_OUTPUT = 1400;

    private static final int[] DARKEN_LUT = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            DARKEN_LUT[i] = (int) Math.round(255 * Math.pow(i / 255.0, 1.8));
        }
    }

    private PageProcessor() {
    }

    /** Decodes {@code uri} with its long side at most {@code maxSide}, then rotates it. */
    static Bitmap decode(ContentResolver resolver, Uri uri, int maxSide, int rotation) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        if (longSide <= 0) {
            throw new IOException("cannot read page image");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int sample = 1;
        while (longSide / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            throw new IOException("cannot decode page image");
        }
        float scale = Math.min(1f, maxSide / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f || rotation % 360 != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (transformed != bitmap) {
                bitmap.recycle();
            }
            bitmap = transformed;
        }
        return bitmap;
    }

    static int[] pixels(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    static Bitmap fit(Bitmap source, int maxSide) {
        float scale = Math.min(1f, maxSide / (float) Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    /** Pushes greys toward black (white stays white) so faint ruled lines print clearly. */
    static void darken(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (c == 0xFFFFFFFF) {
                continue;
            }
            px[i] = 0xFF000000 | (DARKEN_LUT[(c >> 16) & 0xFF] << 16) | (DARKEN_LUT[(c >> 8) & 0xFF] << 8) | DARKEN_LUT[c & 0xFF];
        }
    }

    /** Top-edge angle of the quad in degrees. */
    static float tilt(double[] c) {
        if (c == null) {
            return 0;
        }
        double deg = Math.toDegrees(Math.atan2(c[3] - c[1], c[2] - c[0]));
        return (float) Math.max(-15, Math.min(15, deg));
    }

    /**
     * Builds the review thumbnails for a page and detects its table (unless the user already
     * set corners or chose to skip it).
     */
    static void analyze(ContentResolver resolver, ScanSession.Page page, boolean clean, boolean table, boolean darken,
                        TableRectifier.Layout layout) throws IOException {
        Bitmap preview = decode(resolver, page.source, PREVIEW_SIDE, page.rotation);
        int w = preview.getWidth(), h = preview.getHeight();
        page.previewW = w;
        page.previewH = h;
        Bitmap before = fit(preview, THUMB_SIDE);
        int[] px = pixels(preview);
        preview.recycle();
        if (clean) {
            PaperCleaner.clean(px, w, h);
        }
        if (table && !page.skip && page.manualCorners == null) {
            page.autoCorners = TableRectifier.detectTable(px, w, h);
        }
        double[] corners = table ? page.corners() : null;
        page.tilt = tilt(corners);

        int[] out = px;
        int ow = w, oh = h;
        if (corners != null) {
            TableRectifier.Result r = TableRectifier.rectifyWith(px, w, h, corners, layout.withMaxHeight(PREVIEW_OUTPUT));
            if (r != null) {
                out = r.pixels;
                ow = r.width;
                oh = r.height;
            }
        }
        if (darken) {
            darken(out);
        }
        Bitmap full = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888);
        Bitmap after = fit(full, THUMB_SIDE);
        if (after != full) {
            full.recycle();
        }
        // the previous thumbnails may still be on screen for a frame: leave them to the GC
        page.before = before;
        page.after = after;
        page.analyzed = true;
    }

    /** Full-resolution page for the PDF; {@code straightened[0]} says whether the table was straightened. */
    static Bitmap finalPage(ContentResolver resolver, ScanSession.Page page, boolean clean, boolean table, boolean darken,
                            TableRectifier.Layout layout, boolean[] straightened) throws IOException {
        Bitmap source = decode(resolver, page.source, FINAL_SIDE, page.rotation);
        int w = source.getWidth(), h = source.getHeight();
        int[] px = pixels(source);
        source.recycle();
        if (clean) {
            PaperCleaner.clean(px, w, h);
        }
        double[] corners = table ? page.corners() : null;
        int[] out = px;
        int ow = w, oh = h;
        straightened[0] = false;
        if (corners != null && page.previewW > 0) {
            double s = w / (double) page.previewW;
            double[] scaled = new double[8];
            for (int i = 0; i < 8; i++) {
                scaled[i] = corners[i] * s;
            }
            TableRectifier.Result r = TableRectifier.rectifyWith(out, w, h, scaled, layout);
            if (r != null) {
                out = r.pixels;
                ow = r.width;
                oh = r.height;
                straightened[0] = true;
            }
        }
        if (darken) {
            darken(out);
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888);
    }
}
