package com.quickscan.pdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_SCAN = 1;
    private static final int REQ_SAVE_AS = 2;
    private static final String MIME_PDF = "application/pdf";
    private static final String PDF_EXT = ".pdf";
    private static final String SUB_DIR = "QuickScan";
    private static final int JPEG_QUALITY = 88;
    private static final String FORBIDDEN_CHARS = "\\/:*?\"<>|";

    private static final String PREFS = "settings";
    private static final String PREF_CLEAN = "clean_paper";

    private static final String KEY_PENDING_PAGES = "pending_pages";
    private static final String KEY_PENDING_NAME = "pending_name";
    private static final String KEY_SAVED_URI = "saved_uri";
    private static final String KEY_SAVED_NAME = "saved_name";
    private static final String KEY_SAVED_META = "saved_meta";
    private static final String KEY_SAVED_MEDIA_STORE = "saved_media_store";

    /** Android 10+ writes straight into Downloads; older versions ask the user where to save. */
    private static final boolean USE_MEDIA_STORE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GmsDocumentScanner scanner;
    private SharedPreferences prefs;
    private Button scanButton;
    private View resultCard;
    private TextView resultName;
    private TextView resultMeta;

    // Scanned pages waiting for the "save as" picker (Android 7–9 only)
    private ArrayList<Uri> pendingPages;
    private String pendingName;

    private Uri savedUri;
    private String savedName;
    private String savedMeta;
    private boolean savedInMediaStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root));
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        scanner = GmsDocumentScanning.getClient(new GmsDocumentScannerOptions.Builder()
                // FULL mode = auto edge detection + crop + filters + ML shadow/stain removal
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                // page images rather than the scanner's PDF, so each page can be cleaned first
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build());

        scanButton = findViewById(R.id.btn_scan);
        resultCard = findViewById(R.id.result_card);
        resultName = findViewById(R.id.result_name);
        resultMeta = findViewById(R.id.result_meta);
        TextView saveHint = findViewById(R.id.save_hint);
        saveHint.setText(USE_MEDIA_STORE ? R.string.save_hint_q : R.string.save_hint_legacy);

        Switch cleanSwitch = findViewById(R.id.switch_clean);
        cleanSwitch.setChecked(prefs.getBoolean(PREF_CLEAN, true));
        cleanSwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean(PREF_CLEAN, checked).apply());

        scanButton.setOnClickListener(v -> startScan());
        findViewById(R.id.btn_open).setOnClickListener(v -> openSaved());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareSaved());
        findViewById(R.id.btn_rename).setOnClickListener(v -> promptRename());
        resultName.setOnClickListener(v -> promptRename());

        if (savedInstanceState != null) {
            pendingPages = savedInstanceState.getParcelableArrayList(KEY_PENDING_PAGES);
            pendingName = savedInstanceState.getString(KEY_PENDING_NAME);
            Uri uri = savedInstanceState.getParcelable(KEY_SAVED_URI);
            if (uri != null) {
                showResult(uri, savedInstanceState.getString(KEY_SAVED_NAME), savedInstanceState.getString(KEY_SAVED_META),
                        savedInstanceState.getBoolean(KEY_SAVED_MEDIA_STORE));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(KEY_PENDING_PAGES, pendingPages);
        outState.putString(KEY_PENDING_NAME, pendingName);
        outState.putParcelable(KEY_SAVED_URI, savedUri);
        outState.putString(KEY_SAVED_NAME, savedName);
        outState.putString(KEY_SAVED_META, savedMeta);
        outState.putBoolean(KEY_SAVED_MEDIA_STORE, savedInMediaStore);
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
                        toast(getString(R.string.scanner_failed, e.getMessage()));
                    }
                })
                .addOnFailureListener(e -> toast(getString(R.string.scanner_failed, e.getMessage())))
                .addOnCompleteListener(task -> scanButton.setEnabled(true));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQ_SCAN) {
            GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
            List<GmsDocumentScanningResult.Page> scanned = result == null ? null : result.getPages();
            if (scanned == null || scanned.isEmpty()) {
                toast(getString(R.string.no_pdf));
                return;
            }
            ArrayList<Uri> pages = new ArrayList<>();
            for (GmsDocumentScanningResult.Page page : scanned) {
                pages.add(page.getImageUri());
            }
            String name = getString(R.string.file_prefix) + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + PDF_EXT;
            if (USE_MEDIA_STORE) {
                save(pages, null, name);
            } else {
                pendingPages = pages;
                pendingName = name;
                Intent saveAs = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(MIME_PDF)
                        .putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(saveAs, REQ_SAVE_AS);
            }
        } else if (requestCode == REQ_SAVE_AS && data != null && data.getData() != null && pendingPages != null) {
            save(pendingPages, data.getData(), pendingName);
            pendingPages = null;
        }
    }

    /**
     * Cleans each scanned page (if enabled) and writes them into one PDF. With {@code dest == null}
     * a new entry is created in Downloads/QuickScan through MediaStore (Android 10+).
     */
    private void save(List<Uri> pages, Uri dest, String name) {
        boolean clean = prefs.getBoolean(PREF_CLEAN, true);
        scanButton.setEnabled(false);
        scanButton.setText(R.string.saving);
        io.execute(() -> {
            ContentResolver resolver = getContentResolver();
            Uri target = dest;
            try {
                if (target == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_PDF);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (target == null) {
                        throw new IOException("MediaStore insert returned null");
                    }
                }
                long size;
                try (OutputStream raw = resolver.openOutputStream(target, "w")) {
                    if (raw == null) {
                        throw new IOException("cannot open output");
                    }
                    OutputStream out = new BufferedOutputStream(raw, 256 * 1024);
                    JpegPdfWriter writer = new JpegPdfWriter(out);
                    for (int i = 0; i < pages.size(); i++) {
                        int pageNumber = i + 1;
                        mainHandler.post(() -> scanButton.setText(getString(R.string.processing, pageNumber, pages.size())));
                        addPage(writer, resolver, pages.get(i), clean);
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
                String location = getString(dest == null ? R.string.location_downloads : R.string.location_picked);
                String meta = getString(R.string.meta_format, pages.size(), Formatter.formatShortFileSize(this, size), location);
                Uri saved = target;
                mainHandler.post(() -> {
                    resetScanButton();
                    showResult(saved, name, meta, dest == null);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (dest == null && target != null) {
                    resolver.delete(target, null, null);
                }
                mainHandler.post(() -> {
                    resetScanButton();
                    toast(getString(R.string.save_failed, String.valueOf(e.getMessage())));
                });
            }
        });
    }

    private static void addPage(JpegPdfWriter writer, ContentResolver resolver, Uri page, boolean clean) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(page)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            throw new IOException("cannot decode page image");
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (clean) {
            int[] px = new int[w * h];
            bitmap.getPixels(px, 0, w, 0, 0, w, h);
            PaperCleaner.clean(px, w, h);
            bitmap.setPixels(px, 0, w, 0, 0, w, h);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1024 * 1024);
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg);
        bitmap.recycle();
        writer.addPage(jpeg.toByteArray(), w, h);
    }

    private void resetScanButton() {
        scanButton.setEnabled(true);
        scanButton.setText(R.string.scan);
    }

    private void showResult(Uri uri, String name, String meta, boolean inMediaStore) {
        savedUri = uri;
        savedName = name;
        savedMeta = meta;
        savedInMediaStore = inMediaStore;
        resultName.setText(name);
        resultMeta.setText(meta);
        resultCard.setVisibility(View.VISIBLE);
    }

    private void promptRename() {
        if (savedUri == null) {
            return;
        }
        String base = savedName.toLowerCase(Locale.ROOT).endsWith(PDF_EXT)
                ? savedName.substring(0, savedName.length() - PDF_EXT.length()) : savedName;
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(base);
        input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newBase = input.getText().toString().trim();
                if (newBase.toLowerCase(Locale.ROOT).endsWith(PDF_EXT)) {
                    newBase = newBase.substring(0, newBase.length() - PDF_EXT.length()).trim();
                }
                if (newBase.isEmpty()) {
                    input.setError(getString(R.string.rename_empty));
                    return;
                }
                for (int i = 0; i < newBase.length(); i++) {
                    if (FORBIDDEN_CHARS.indexOf(newBase.charAt(i)) >= 0) {
                        input.setError(getString(R.string.rename_bad_chars));
                        return;
                    }
                }
                dialog.dismiss();
                rename(newBase + PDF_EXT);
            });
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
    }

    /** Renames the saved file where it is (same folder), then shows the name the system actually used. */
    private void rename(String newName) {
        Uri uri = savedUri;
        boolean inMediaStore = savedInMediaStore;
        io.execute(() -> {
            ContentResolver resolver = getContentResolver();
            try {
                Uri renamed;
                if (inMediaStore) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                    if (resolver.update(uri, values, null, null) == 0) {
                        throw new IOException(getString(R.string.rename_not_found));
                    }
                    renamed = uri;
                } else {
                    renamed = DocumentsContract.renameDocument(resolver, uri, newName);
                    if (renamed == null) {
                        throw new IOException(getString(R.string.rename_not_found));
                    }
                }
                String actual = queryDisplayName(resolver, renamed, newName);
                mainHandler.post(() -> {
                    showResult(renamed, actual, savedMeta, inMediaStore);
                    toast(getString(R.string.renamed, actual));
                });
            } catch (IOException | RuntimeException e) {
                mainHandler.post(() -> toast(getString(R.string.rename_failed, String.valueOf(e.getMessage()))));
            }
        });
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall back to the requested name
        }
        return fallback;
    }

    private void openSaved() {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(savedUri, MIME_PDF)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_viewer));
        }
    }

    private void shareSaved() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(MIME_PDF)
                .putExtra(Intent.EXTRA_STREAM, savedUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.share_title)));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** targetSdk 35+ draws edge-to-edge on Android 15+, so keep content clear of the system bars. */
    private static void applySystemBarInsets(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            } else {
                v.setPadding(left + insets.getSystemWindowInsetLeft(), top + insets.getSystemWindowInsetTop(),
                        right + insets.getSystemWindowInsetRight(), bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }
}
