package com.quickscan.pdf;

import android.graphics.Bitmap;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory state shared by the screens: the batch being reviewed and the files saved since the
 * app was opened. It lives as long as the process, which is exactly "this session".
 */
final class ScanSession {

    /** One scanned page while it is being reviewed. Corners are in preview pixels. */
    static final class Page {
        final Uri source;
        int rotation;
        /** Size of the preview the corners refer to (after rotation). */
        int previewW, previewH;
        double[] autoCorners;
        double[] manualCorners;
        /** The user chose to keep this page as it is. */
        boolean skip;
        boolean analyzed;
        Bitmap before, after;
        /** Angle of the table's top edge in the photo, for the straighten animation. */
        float tilt;

        Page(Uri source) {
            this.source = source;
        }

        double[] corners() {
            if (skip) {
                return null;
            }
            return manualCorners != null ? manualCorners : autoCorners;
        }
    }

    /** A PDF saved during this session. */
    static final class SavedFile {
        Uri uri;
        String name;
        int pages;
        long size;
        String location;
        boolean inMediaStore;
        int straightenedPages;
        long savedAt;
        Bitmap thumb;
    }

    static final List<Page> pages = new ArrayList<>();
    /** Newest first. */
    static final List<SavedFile> history = new ArrayList<>();
    /** Set by the review screen right after saving, so the home screen can confirm it. */
    static SavedFile justSaved;

    private ScanSession() {
    }

    static void start(List<Uri> sources) {
        for (Page page : pages) {
            recycle(page.before);
            recycle(page.after);
        }
        pages.clear();
        for (Uri uri : sources) {
            pages.add(new Page(uri));
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
