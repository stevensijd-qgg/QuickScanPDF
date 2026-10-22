package com.quickscan.pdf;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_SCAN = 1;
    private static final long TOAST_MS = 1800;

    /** Android 10+ writes straight into Downloads; older versions ask the user where to save. */
    static final boolean USE_MEDIA_STORE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private GmsDocumentScanner scanner;

    private View scanButton;
    private TextView settingsSummary;
    private View recentSection;
    private View historyButton;
    private TextView historyLabel;
    private ImageView historyChevron;
    private View historyWrap;
    private LinearLayout historyList;
    private View toast;
    private TextView toastText;
    private boolean historyOpen = true;
    private ValueAnimator historyAnim;
    private final Runnable hideToast = this::hideToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View content = findViewById(R.id.content);
        toast = findViewById(R.id.toast);
        int toastMargin = ((ViewGroup.MarginLayoutParams) toast.getLayoutParams()).bottomMargin;
        int top = content.getPaddingTop(), bottom = content.getPaddingBottom();
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int[] bars = Ui.systemBars(insets);
            content.setPadding(content.getPaddingLeft(), top + bars[1], content.getPaddingRight(), bottom + bars[3]);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) toast.getLayoutParams();
            lp.bottomMargin = toastMargin + bars[3];
            toast.setLayoutParams(lp);
            return insets;
        });

        scanner = GmsDocumentScanning.getClient(new GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build());

        scanButton = findViewById(R.id.btn_scan);
        Ui.asButton(scanButton);
        Ui.pressable(scanButton);
        scanButton.setOnClickListener(v -> startScan());
        ((TextView) findViewById(R.id.scan_hint)).setText(USE_MEDIA_STORE ? R.string.scan_hint_q : R.string.scan_hint_legacy);

        SpringSwitch clean = findViewById(R.id.switch_clean);
        clean.setChecked(Prefs.clean(this));
        clean.setOnChangeListener((v, on) -> {
            Prefs.of(this).edit().putBoolean(Prefs.CLEAN, on).apply();
            showToast(getString(on ? R.string.toast_clean_on : R.string.toast_clean_off));
        });
        SpringSwitch table = findViewById(R.id.switch_table);
        table.setChecked(Prefs.table(this));
        table.setOnChangeListener((v, on) -> {
            Prefs.of(this).edit().putBoolean(Prefs.TABLE, on).apply();
            showToast(getString(on ? R.string.toast_table_on : R.string.toast_table_off));
        });

        settingsSummary = findViewById(R.id.settings_summary);
        View settingsRow = findViewById(R.id.row_settings);
        Ui.asButton(settingsRow);
        settingsRow.setOnClickListener(v -> SettingsSheet.show(this, this::refreshSummary));
        refreshSummary();

        recentSection = findViewById(R.id.recent_section);
        historyButton = findViewById(R.id.btn_history);
        historyLabel = findViewById(R.id.history_label);
        historyChevron = findViewById(R.id.history_chevron);
        historyWrap = findViewById(R.id.history_wrap);
        historyList = findViewById(R.id.history_list);
        toastText = findViewById(R.id.toast_text);
        Ui.asButton(historyButton);
        historyButton.setOnClickListener(v -> toggleHistory());

        Ui.pressable(findViewById(R.id.btn_open), findViewById(R.id.btn_share), findViewById(R.id.btn_rename));
        findViewById(R.id.btn_open).setOnClickListener(v -> openFile(latest()));
        findViewById(R.id.btn_share).setOnClickListener(v -> shareFile(latest()));
        findViewById(R.id.btn_rename).setOnClickListener(v -> renameFile(latest()));

        if (savedInstanceState != null) {
            historyOpen = savedInstanceState.getBoolean("history_open", true);
        }
        toast.setTranslationY(Ui.dp(this, 90));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("history_open", historyOpen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecent();
        ScanSession.SavedFile saved = ScanSession.justSaved;
        if (saved != null) {
            ScanSession.justSaved = null;
            toast.postDelayed(() -> showToast(getString(R.string.toast_saved, saved.name)), 250);
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void startScan() {
        scanButton.setEnabled(false);
        scanner.getStartScanIntent(this)
                .addOnSuccessListener(sender -> {
                    try {
                        startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        showError(getString(R.string.scanner_failed, e.getMessage()));
                    }
                })
                .addOnFailureListener(e -> showError(getString(R.string.scanner_failed, e.getMessage())))
                .addOnCompleteListener(task -> scanButton.setEnabled(true));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) {
            return;
        }
        GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
        List<GmsDocumentScanningResult.Page> scanned = result == null ? null : result.getPages();
        if (scanned == null || scanned.isEmpty()) {
            showError(getString(R.string.no_pdf));
            return;
        }
        List<Uri> pages = new ArrayList<>();
        for (GmsDocumentScanningResult.Page page : scanned) {
            pages.add(page.getImageUri());
        }
        ScanSession.start(pages);
        startActivity(new Intent(this, ReviewActivity.class));
    }

    private void refreshSummary() {
        settingsSummary.setText(Prefs.summary(this));
    }

    // ---------------------------------------------------------------- recent files

    private ScanSession.SavedFile latest() {
        return ScanSession.history.isEmpty() ? null : ScanSession.history.get(0);
    }

    private void refreshRecent() {
        List<ScanSession.SavedFile> files = ScanSession.history;
        if (files.isEmpty()) {
            recentSection.setVisibility(View.GONE);
            return;
        }
        recentSection.setVisibility(View.VISIBLE);
        ScanSession.SavedFile first = files.get(0);
        ((ImageView) findViewById(R.id.latest_thumb)).setImageBitmap(first.thumb);
        ((TextView) findViewById(R.id.latest_name)).setText(first.name);
        ((TextView) findViewById(R.id.latest_meta)).setText(getString(R.string.meta_format, first.pages,
                Formatter.formatShortFileSize(this, first.size), first.location));
        TextView badge = findViewById(R.id.latest_badge);
        badge.setVisibility(first.straightenedPages > 0 ? View.VISIBLE : View.GONE);
        badge.setText(getString(R.string.badge_straightened, first.straightenedPages));

        boolean more = files.size() > 1;
        historyButton.setVisibility(more ? View.VISIBLE : View.GONE);
        historyList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 1; i < files.size(); i++) {
            ScanSession.SavedFile f = files.get(i);
            if (i > 1) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.divider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
                lp.setMarginStart(Ui.dp(this, 56));
                historyList.addView(divider, lp);
            }
            View row = inflater.inflate(R.layout.item_history, historyList, false);
            ((ImageView) row.findViewById(R.id.row_thumb)).setImageBitmap(f.thumb);
            ((TextView) row.findViewById(R.id.row_name)).setText(f.name);
            String meta = getString(R.string.meta_short, f.pages, Formatter.formatShortFileSize(this, f.size));
            if (f.straightenedPages > 0) {
                meta += getString(R.string.meta_straightened_suffix);
            }
            ((TextView) row.findViewById(R.id.row_meta)).setText(meta);
            ((TextView) row.findViewById(R.id.row_time)).setText(time.format(new Date(f.savedAt)));
            View main = row.findViewById(R.id.row_main);
            Ui.asButton(main);
            main.setOnClickListener(v -> openFile(f));
            View moreButton = row.findViewById(R.id.row_more);
            moreButton.setContentDescription(getString(R.string.more_actions, f.name));
            moreButton.setOnClickListener(v -> showMore(v, f));
            historyList.addView(row);
        }
        historyWrap.setVisibility(more && historyOpen ? View.VISIBLE : View.GONE);
        historyWrap.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        historyWrap.setAlpha(1f);
        historyChevron.setRotation(historyOpen ? 180f : 0f);
        updateHistoryLabel();
    }

    private void updateHistoryLabel() {
        int count = ScanSession.history.size();
        historyLabel.setText(getString(historyOpen ? R.string.history_toggle_open : R.string.history_toggle_closed, count));
        historyButton.setContentDescription(historyLabel.getText());
    }

    /** Expands or collapses the session list: height eases, contents fade, chevron turns. */
    private void toggleHistory() {
        historyOpen = !historyOpen;
        updateHistoryLabel();
        Ui.hapticTick(historyButton);
        historyChevron.animate().rotation(historyOpen ? 180f : 0f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.5f)).start();
        if (historyAnim != null) historyAnim.cancel();
        int width = View.MeasureSpec.makeMeasureSpec(((View) historyWrap.getParent()).getWidth(), View.MeasureSpec.EXACTLY);
        historyWrap.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int full = historyWrap.getMeasuredHeight();
        int from = historyWrap.getVisibility() == View.VISIBLE ? historyWrap.getHeight() : 0;
        int to = historyOpen ? full : 0;
        historyWrap.setVisibility(View.VISIBLE);
        historyAnim = ValueAnimator.ofInt(from, to);
        historyAnim.setDuration(400);
        historyAnim.setInterpolator(new DecelerateInterpolator(2f));
        historyAnim.addUpdateListener(a -> {
            historyWrap.getLayoutParams().height = (int) a.getAnimatedValue();
            historyWrap.setAlpha(full == 0 ? 1f : Math.min(1f, (int) a.getAnimatedValue() / (float) full));
            historyWrap.requestLayout();
        });
        historyAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!historyOpen) {
                    historyWrap.setVisibility(View.GONE);
                }
                historyWrap.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                historyWrap.requestLayout();
            }
        });
        historyAnim.start();
    }

    private void showMore(View anchor, ScanSession.SavedFile f) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.open);
        menu.getMenu().add(0, 2, 1, R.string.share);
        menu.getMenu().add(0, 3, 2, R.string.rename);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    openFile(f);
                    return true;
                case 2:
                    shareFile(f);
                    return true;
                default:
                    renameFile(f);
                    return true;
            }
        });
        menu.show();
    }

    private void openFile(ScanSession.SavedFile f) {
        if (f != null && FileActions.open(this, f.uri)) {
            showToast(getString(R.string.toast_opening));
        }
    }

    private void shareFile(ScanSession.SavedFile f) {
        if (f != null) {
            showToast(getString(R.string.toast_share));
            FileActions.share(this, f.uri);
        }
    }

    private void renameFile(ScanSession.SavedFile f) {
        if (f == null) {
            return;
        }
        FileActions.promptRename(this, f, io, new FileActions.RenameCallback() {
            @Override
            public void onRenamed(String actualName) {
                refreshRecent();
                Ui.hapticConfirm(toast);
                showToast(getString(R.string.renamed, actualName));
            }

            @Override
            public void onFailed(String message) {
                showError(getString(R.string.rename_failed, message));
            }
        });
    }

    // ---------------------------------------------------------------- toast

    /** Dark pill that springs up from the bottom and slips away after a moment. */
    private void showToast(String text) {
        toastText.setText(text);
        toast.removeCallbacks(hideToast);
        toast.setVisibility(View.VISIBLE);
        toast.animate().translationY(0f).alpha(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        toast.announceForAccessibility(text);
        toast.postDelayed(hideToast, TOAST_MS);
    }

    private void hideToast() {
        toast.animate().translationY(Ui.dp(this, 90)).alpha(0f).setDuration(260)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> toast.setVisibility(View.INVISIBLE)).start();
    }

    private void showError(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    static String newFileName(Activity activity) {
        return activity.getString(R.string.file_prefix) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
    }
}
