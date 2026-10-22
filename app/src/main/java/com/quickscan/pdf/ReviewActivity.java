package com.quickscan.pdf;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows every scanned page before/after straightening, then writes the PDF. */
public class ReviewActivity extends Activity {

    private static final int REQ_ADJUST = 10;
    private static final int REQ_SAVE_AS = 11;
    private static final String SUB_DIR = "QuickScan";
    private static final int JPEG_QUALITY = 88;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Card> cards = new ArrayList<>();

    private boolean clean;
    private boolean table;
    private boolean darken;
    private boolean showAfter = true;
    private boolean working;
    private int analyzed;

    private TextView summary;
    private Button generate;
    private View progressBox;
    private TextView progressText;
    private ProgressLineView progressLine;
    private View doneBox;
    private Object backBlocker;
    private ScanSession.SavedFile saved;

    private static final class Card {
        View root;
        ImageView before;
        ImageView after;
        View busy;
        TextView badge;
        TextView action;
        boolean shown;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ScanSession.pages.isEmpty()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_review);
        Ui.applySystemBarInsets(findViewById(R.id.root));
        clean = Prefs.clean(this);
        table = Prefs.table(this);
        darken = Prefs.darken(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        View cancel = findViewById(R.id.btn_cancel);
        Ui.asButton(cancel);
        cancel.setOnClickListener(v -> finish());

        summary = findViewById(R.id.summary);
        SegmentedControl compare = findViewById(R.id.compare);
        compare.setOptions(getString(R.string.compare_before), getString(R.string.compare_after));
        compare.select(1, false);
        compare.setOnSelectListener(i -> setShowAfter(i == 1));
        compare.setVisibility(table || clean ? View.VISIBLE : View.GONE);

        generate = findViewById(R.id.btn_generate);
        progressBox = findViewById(R.id.progress_box);
        progressText = findViewById(R.id.progress_text);
        progressLine = findViewById(R.id.progress_line);
        doneBox = findViewById(R.id.done_box);
        Ui.pressable(generate, findViewById(R.id.btn_home), findViewById(R.id.btn_open_pdf));
        generate.setOnClickListener(v -> startGenerate());
        findViewById(R.id.btn_home).setOnClickListener(v -> finish());
        findViewById(R.id.btn_open_pdf).setOnClickListener(v -> {
            if (saved != null) FileActions.open(this, saved.uri);
        });

        buildGrid();
        analyzeAll();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!working) {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- page grid

    private void buildGrid() {
        LinearLayout grid = findViewById(R.id.grid);
        LayoutInflater inflater = getLayoutInflater();
        int gap = Ui.dp(this, 12);
        LinearLayout row = null;
        for (int i = 0; i < ScanSession.pages.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.topMargin = gap;
                grid.addView(row, lp);
            }
            View v = inflater.inflate(R.layout.item_page, row, false);
            if (i % 2 == 1) {
                ((LinearLayout.LayoutParams) v.getLayoutParams()).setMarginStart(gap);
            }
            row.addView(v);
            Card card = new Card();
            card.root = v;
            card.before = v.findViewById(R.id.img_before);
            card.after = v.findViewById(R.id.img_after);
            card.busy = v.findViewById(R.id.busy);
            card.badge = v.findViewById(R.id.page_badge);
            card.action = v.findViewById(R.id.page_action);
            ((TextView) v.findViewById(R.id.page_label)).setText(getString(R.string.page_label, i + 1));
            card.badge.setText(R.string.badge_analyzing);
            card.action.setVisibility(View.INVISIBLE);
            Ui.asButton(card.action);
            int index = i;
            card.action.setOnClickListener(x -> openAdjust(index));
            cards.add(card);
        }
        if (ScanSession.pages.size() % 2 == 1 && row != null) {
            Space filler = new Space(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginStart(gap);
            row.addView(filler, lp);
        }
    }

    private void analyzeAll() {
        int total = ScanSession.pages.size();
        generate.setEnabled(false);
        generate.setText(getString(R.string.review_analyzing, 1, total));
        TableRectifier.Layout layout = Prefs.layout(this, 3508);
        ContentResolver resolver = getContentResolver();
        for (int i = 0; i < total; i++) {
            int index = i;
            io.execute(() -> {
                ScanSession.Page page = ScanSession.pages.get(index);
                try {
                    PageProcessor.analyze(resolver, page, clean, table, darken, layout);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    page.analyzed = true; // keep going; the page goes in as it is
                }
                main.post(() -> {
                    analyzed++;
                    bindCard(index, true);
                    if (analyzed < total) {
                        generate.setText(getString(R.string.review_analyzing, analyzed + 1, total));
                    } else {
                        generate.setEnabled(true);
                        generate.setText(getString(R.string.generate, total));
                    }
                    updateSummary();
                });
            });
        }
    }

    private void bindCard(int index, boolean entrance) {
        if (isFinishing() || isDestroyed()) return;
        ScanSession.Page page = ScanSession.pages.get(index);
        Card card = cards.get(index);
        card.busy.setVisibility(View.GONE);
        card.before.setImageBitmap(page.before);
        card.after.setImageBitmap(page.after);
        card.action.setVisibility(table ? View.VISIBLE : View.INVISIBLE);
        card.action.setText(page.corners() != null || page.skip ? R.string.action_adjust : R.string.action_select);
        updateBadge(index);
        card.before.setRotation(0f);
        if (entrance && !card.shown) {
            card.shown = true;
            View shown = showAfter ? card.after : card.before;
            View hidden = showAfter ? card.before : card.after;
            hidden.setAlpha(0f);
            shown.setAlpha(0f);
            shown.setScaleX(0.92f);
            shown.setScaleY(0.92f);
            shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(1.6f)).start();
        } else {
            card.after.setAlpha(showAfter ? 1f : 0f);
            card.before.setAlpha(showAfter ? 0f : 1f);
            if (!entrance) {
                Ui.pop(card.root);
            }
        }
    }

    private void updateBadge(int index) {
        ScanSession.Page page = ScanSession.pages.get(index);
        Card card = cards.get(index);
        int text;
        boolean accent;
        if (!page.analyzed) {
            text = R.string.badge_analyzing;
            accent = false;
        } else if (!table || page.skip) {
            text = R.string.badge_skipped;
            accent = false;
        } else if (page.corners() == null) {
            text = R.string.badge_none;
            accent = false;
        } else if (page.manualCorners != null) {
            text = R.string.badge_manual;
            accent = true;
        } else {
            text = showAfter ? R.string.badge_straight : R.string.badge_detected;
            accent = true;
        }
        if (!table && page.analyzed) {
            card.badge.setVisibility(View.GONE);
            return;
        }
        card.badge.setVisibility(View.VISIBLE);
        card.badge.setText(text);
        card.badge.setBackgroundResource(accent ? R.drawable.badge_bg : R.drawable.badge_muted_bg);
        card.badge.setTextColor(getColor(accent ? R.color.badge_ink : R.color.badge_muted_ink));
    }

    private void updateSummary() {
        if (!table) {
            summary.setText(R.string.review_summary_off);
            return;
        }
        int straight = 0, none = 0;
        for (ScanSession.Page p : ScanSession.pages) {
            if (!p.analyzed) continue;
            if (p.corners() != null) straight++;
            else none++;
        }
        if (none == 0) {
            summary.setText(getString(R.string.review_summary_all, straight));
        } else if (straight == 0) {
            summary.setText(R.string.review_summary_none);
        } else {
            summary.setText(getString(R.string.review_summary, straight, none));
        }
    }

    /** Before → after: the photo turns upright while the cleaned page fades in, one card after another. */
    private void setShowAfter(boolean after) {
        showAfter = after;
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            ScanSession.Page page = ScanSession.pages.get(i);
            long delay = i * 80L;
            float straighten = table && page.corners() != null ? -page.tilt : 0f;
            if (after) {
                card.before.animate().rotation(straighten).alpha(0f).scaleX(0.96f).scaleY(0.96f)
                        .setStartDelay(delay).setDuration(600).setInterpolator(new DecelerateInterpolator(2f)).start();
                card.after.setScaleX(0.94f);
                card.after.setScaleY(0.94f);
                card.after.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(delay + 120).setDuration(650).setInterpolator(new OvershootInterpolator(1.2f)).start();
            } else {
                card.before.setRotation(straighten);
                card.before.animate().rotation(0f).alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(delay).setDuration(600).setInterpolator(new OvershootInterpolator(1.1f)).start();
                card.after.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                        .setStartDelay(delay).setDuration(350).setInterpolator(new DecelerateInterpolator()).start();
            }
            updateBadge(i);
        }
    }

    private void openAdjust(int index) {
        if (working || !ScanSession.pages.get(index).analyzed) return;
        startActivityForResult(new Intent(this, AdjustActivity.class).putExtra(AdjustActivity.EXTRA_INDEX, index), REQ_ADJUST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADJUST && resultCode == RESULT_OK && data != null) {
            int index = data.getIntExtra(AdjustActivity.EXTRA_INDEX, -1);
            if (index < 0 || index >= cards.size()) return;
            cards.get(index).busy.setVisibility(View.VISIBLE);
            TableRectifier.Layout layout = Prefs.layout(this, 3508);
            ContentResolver resolver = getContentResolver();
            io.execute(() -> {
                try {
                    PageProcessor.analyze(resolver, ScanSession.pages.get(index), clean, table, darken, layout);
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                    // the previous thumbnails stay
                }
                main.post(() -> {
                    bindCard(index, false);
                    updateSummary();
                });
            });
        } else if (requestCode == REQ_SAVE_AS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                runGenerate(data.getData());
            }
        }
    }

    // ---------------------------------------------------------------- generating

    private void startGenerate() {
        if (working) return;
        if (MainActivity.USE_MEDIA_STORE) {
            runGenerate(null);
        } else {
            Intent saveAs = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(FileActions.MIME_PDF)
                    .putExtra(Intent.EXTRA_TITLE, MainActivity.newFileName(this));
            startActivityForResult(saveAs, REQ_SAVE_AS);
        }
    }

    private void runGenerate(Uri dest) {
        working = true;
        blockBack(true);
        int total = ScanSession.pages.size();
        // button → progress pill
        progressText.setText(getString(R.string.generating, 1, total));
        progressLine.reset();
        progressBox.setVisibility(View.VISIBLE);
        progressBox.setAlpha(0f);
        progressBox.animate().alpha(1f).setDuration(200).start();
        generate.animate().alpha(0f).setDuration(160).withEndAction(() -> generate.setVisibility(View.INVISIBLE)).start();

        String name = dest == null ? MainActivity.newFileName(this) : FileActions.displayName(getContentResolver(), dest,
                MainActivity.newFileName(this));
        TableRectifier.Layout layout = Prefs.layout(this, 3508);
        float pageWidth = Prefs.pageWidthPt(this);
        ContentResolver resolver = getContentResolver();
        io.execute(() -> {
            Uri target = dest;
            try {
                if (target == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, FileActions.MIME_PDF);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (target == null) throw new IOException("MediaStore insert returned null");
                }
                long size;
                int straightened = 0;
                try (OutputStream raw = resolver.openOutputStream(target, "w")) {
                    if (raw == null) throw new IOException("cannot open output");
                    OutputStream out = new BufferedOutputStream(raw, 256 * 1024);
                    JpegPdfWriter writer = new JpegPdfWriter(out);
                    boolean[] flag = new boolean[1];
                    for (int i = 0; i < total; i++) {
                        int n = i + 1;
                        main.post(() -> {
                            progressText.setText(getString(R.string.generating, n, total));
                            progressLine.setProgress((n - 0.5f) / total);
                        });
                        Bitmap page = PageProcessor.finalPage(resolver, ScanSession.pages.get(i), clean, table, darken, layout, flag);
                        if (flag[0]) straightened++;
                        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1024 * 1024);
                        page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg);
                        int w = page.getWidth(), h = page.getHeight();
                        page.recycle();
                        writer.addPage(jpeg.toByteArray(), w, h, pageWidth);
                    }
                    writer.finish();
                    out.flush();
                    size = writer.size();
                }
                if (dest == null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(target, done, null, null);
                }
                ScanSession.SavedFile file = new ScanSession.SavedFile();
                file.uri = target;
                file.name = name;
                file.pages = total;
                file.size = size;
                file.inMediaStore = dest == null;
                file.location = getString(dest == null ? R.string.location_downloads : R.string.location_picked);
                file.straightenedPages = straightened;
                file.savedAt = System.currentTimeMillis();
                Bitmap first = ScanSession.pages.get(0).after;
                file.thumb = first == null ? null : PageProcessor.fit(first, 160);
                main.post(() -> showDone(file));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (dest == null && target != null) {
                    try {
                        resolver.delete(target, null, null);
                    } catch (RuntimeException ignored) {
                        // nothing more to clean up
                    }
                }
                String message = String.valueOf(e.getMessage());
                main.post(() -> showFailed(message));
            }
        });
    }

    /** Progress fills, then the pill pops, the check draws itself and the next steps rise in. */
    private void showDone(ScanSession.SavedFile file) {
        if (isDestroyed()) return;
        saved = file;
        ScanSession.history.add(0, file);
        ScanSession.justSaved = file;
        working = false;
        blockBack(false);
        progressLine.setProgress(1f);
        progressBox.postDelayed(() -> {
            if (isDestroyed()) return;
            progressBox.animate().alpha(0f).setDuration(150).withEndAction(() -> progressBox.setVisibility(View.GONE)).start();
            ((TextView) findViewById(R.id.done_text)).setText(getString(R.string.saved_to, file.location));
            doneBox.setVisibility(View.VISIBLE);
            View pill = findViewById(R.id.done_pill);
            Ui.pop(pill);
            ((CheckMarkView) findViewById(R.id.check)).play();
            Ui.rise(findViewById(R.id.done_actions), 250);
            Ui.hapticConfirm(pill);
            pill.announceForAccessibility(((TextView) findViewById(R.id.done_text)).getText());
        }, 380);
        for (Card card : cards) {
            card.action.setEnabled(false);
            card.action.setAlpha(0.5f);
        }
    }

    private void showFailed(String message) {
        if (isDestroyed()) return;
        working = false;
        blockBack(false);
        progressBox.setVisibility(View.GONE);
        generate.setVisibility(View.VISIBLE);
        generate.setAlpha(1f);
        Toast.makeText(this, getString(R.string.save_failed, message), Toast.LENGTH_LONG).show();
    }

    /** While the PDF is being written, back does nothing (it would lose the half-written file). */
    private void blockBack(boolean block) {
        if (Build.VERSION.SDK_INT < 33) {
            return; // onBackPressed checks `working`
        }
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (block && backBlocker == null) {
            OnBackInvokedCallback callback = () -> { };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backBlocker = callback;
        } else if (!block && backBlocker != null) {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backBlocker);
            backBlocker = null;
        }
    }
}
