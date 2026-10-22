package com.quickscan.pdf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets the user drag the table frame of one page, re-detect it, rotate the page or skip it. */
public class AdjustActivity extends Activity {

    static final String EXTRA_INDEX = "index";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private int index;
    private ScanSession.Page page;
    private int rotation;
    private Bitmap preview;
    private int[] cleanedPixels;
    /** Detected corners for the current rotation, or null. */
    private double[] detected;
    private boolean busy;

    private QuadEditorView editor;
    private TextView hint;
    private View rotateIcon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        if (index < 0 || index >= ScanSession.pages.size()) {
            finish();
            return;
        }
        page = ScanSession.pages.get(index);
        rotation = page.rotation;
        setContentView(R.layout.activity_adjust);
        Ui.applySystemBarInsets(findViewById(R.id.root));

        ((TextView) findViewById(R.id.page_info)).setText(getString(R.string.page_of, index + 1, ScanSession.pages.size()));
        editor = findViewById(R.id.editor);
        hint = findViewById(R.id.hint);
        rotateIcon = findViewById(R.id.rotate_icon);
        editor.setListener(() -> hint.setText(R.string.hint_manual));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        View reset = findViewById(R.id.btn_reset);
        Ui.asButton(reset);
        reset.setOnClickListener(v -> resetCorners());
        View detect = findViewById(R.id.tool_detect);
        View rotate = findViewById(R.id.tool_rotate);
        View skip = findViewById(R.id.tool_skip);
        for (View tool : new View[]{detect, rotate, skip}) {
            Ui.asButton(tool);
        }
        Ui.pressable(detect, rotate, skip, findViewById(R.id.btn_cancel), findViewById(R.id.btn_ok));
        detect.setOnClickListener(v -> redetect());
        rotate.setOnClickListener(v -> rotate());
        skip.setOnClickListener(v -> skipPage());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_ok).setOnClickListener(v -> confirm());

        load(true);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** Decodes the preview for the current rotation and works out where the frame starts. */
    private void load(boolean first) {
        busy = true;
        boolean clean = Prefs.clean(this);
        int rot = rotation;
        io.execute(() -> {
            try {
                Bitmap bitmap = PageProcessor.decode(getContentResolver(), page.source, PageProcessor.PREVIEW_SIDE, rot);
                int w = bitmap.getWidth(), h = bitmap.getHeight();
                int[] px = PageProcessor.pixels(bitmap);
                if (clean) PaperCleaner.clean(px, w, h);
                boolean sameView = rot == page.rotation && page.previewW == w;
                // reuse the review screen's detection when the page is shown the same way
                double[] found = sameView && page.autoCorners != null ? page.autoCorners : TableRectifier.detectTable(px, w, h);
                double[] start = first && sameView && page.manualCorners != null ? page.manualCorners : found;
                float[] corners = start != null ? toFloat(start) : defaultFrame(w, h);
                double[] detectedCorners = found;
                main.post(() -> {
                    if (isDestroyed()) return;
                    preview = bitmap;
                    cleanedPixels = px;
                    detected = detectedCorners;
                    busy = false;
                    if (first) {
                        editor.setImage(bitmap, corners);
                        if (detectedCorners == null) hint.setText(R.string.hint_not_found);
                    } else {
                        editor.setImage(bitmap, defaultFrame(w, h));
                        editor.animateTo(corners, null);
                        hint.setText(detectedCorners != null ? R.string.hint_rotated : R.string.hint_not_found);
                    }
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                main.post(() -> {
                    Toast.makeText(this, R.string.no_pdf, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void resetCorners() {
        if (preview == null || busy) return;
        Ui.hapticTick(editor);
        editor.animateTo(detected != null ? toFloat(detected) : defaultFrame(preview.getWidth(), preview.getHeight()),
                () -> hint.setText(R.string.hint_reset));
    }

    /** Sweeps the photo while detecting again, then springs the frame onto the table. */
    private void redetect() {
        if (preview == null || busy) return;
        busy = true;
        hint.setText(R.string.hint_detecting);
        int w = preview.getWidth(), h = preview.getHeight();
        int[] px = cleanedPixels;
        double[][] result = new double[1][];
        boolean[] finished = new boolean[2]; // [detection done, sweep done]
        Runnable settle = () -> {
            if (!finished[0] || !finished[1] || isDestroyed()) return;
            busy = false;
            detected = result[0];
            if (result[0] != null) {
                editor.animateTo(toFloat(result[0]), () -> {
                    hint.setText(R.string.hint_detected);
                    Ui.hapticConfirm(editor);
                });
            } else {
                hint.setText(R.string.hint_not_found);
                shake(editor);
            }
        };
        io.execute(() -> {
            result[0] = TableRectifier.detectTable(px, w, h);
            main.post(() -> {
                finished[0] = true;
                settle.run();
            });
        });
        editor.playSweep(() -> {
            finished[1] = true;
            settle.run();
        });
    }

    private void rotate() {
        if (busy) return;
        rotation = (rotation + 90) % 360;
        rotateIcon.setRotation(0f);
        rotateIcon.animate().rotation(-90f).setDuration(450).setInterpolator(new OvershootInterpolator(2f))
                .withEndAction(() -> rotateIcon.setRotation(0f)).start();
        Ui.hapticTick(editor);
        load(false);
    }

    private void skipPage() {
        page.skip = true;
        page.manualCorners = null;
        commitRotation();
        Ui.hapticConfirm(editor);
        done();
    }

    private void confirm() {
        if (preview == null || busy) return;
        float[] c = editor.getCorners();
        double[] corners = new double[8];
        for (int i = 0; i < 8; i++) corners[i] = c[i];
        commitRotation();
        page.manualCorners = corners;
        page.skip = false;
        Ui.hapticConfirm(editor);
        done();
    }

    private void commitRotation() {
        if (preview != null) {
            page.rotation = rotation;
            page.previewW = preview.getWidth();
            page.previewH = preview.getHeight();
            page.autoCorners = detected;
        }
    }

    private void done() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_INDEX, index));
        finish();
    }

    private static void shake(View v) {
        v.animate().translationX(Ui.dp(v.getContext(), 8)).setDuration(60).withEndAction(() ->
                v.animate().translationX(-Ui.dp(v.getContext(), 6)).setDuration(70).withEndAction(() ->
                        v.animate().translationX(0).setDuration(180).setInterpolator(new OvershootInterpolator(3f)).start()
                ).start()
        ).start();
    }

    private static float[] toFloat(double[] c) {
        float[] f = new float[8];
        for (int i = 0; i < 8; i++) f[i] = (float) c[i];
        return f;
    }

    /** Frame inset 8 % from the edges, for pages where nothing was detected. */
    private static float[] defaultFrame(int w, int h) {
        float l = w * 0.08f, t = h * 0.08f, r = w * 0.92f, b = h * 0.92f;
        return new float[]{l, t, r, t, r, b, l, b};
    }
}
