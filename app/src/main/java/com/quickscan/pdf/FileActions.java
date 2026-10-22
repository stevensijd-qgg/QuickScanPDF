package com.quickscan.pdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Open, share and rename a saved PDF. */
final class FileActions {

    static final String MIME_PDF = "application/pdf";
    private static final String PDF_EXT = ".pdf";
    private static final String FORBIDDEN_CHARS = "\\/:*?\"<>|";

    private FileActions() {
    }

    static boolean open(Activity activity, Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_PDF)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(view);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_viewer, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static void share(Activity activity, Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(MIME_PDF)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_title)));
    }

    interface RenameCallback {
        void onRenamed(String actualName);

        void onFailed(String message);
    }

    /** Asks for a new name, then renames the file where it is (same folder). */
    static void promptRename(Activity activity, ScanSession.SavedFile file, Executor io, RenameCallback callback) {
        String base = file.name.toLowerCase(Locale.ROOT).endsWith(PDF_EXT)
                ? file.name.substring(0, file.name.length() - PDF_EXT.length()) : file.name;
        EditText input = new EditText(activity);
        input.setSingleLine();
        input.setText(base);
        input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(activity);
        int pad = Ui.dp(activity, 20);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.rename_title)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.toLowerCase(Locale.ROOT).endsWith(PDF_EXT)) {
                    name = name.substring(0, name.length() - PDF_EXT.length()).trim();
                }
                if (name.isEmpty()) {
                    input.setError(activity.getString(R.string.rename_empty));
                    return;
                }
                for (int i = 0; i < name.length(); i++) {
                    if (FORBIDDEN_CHARS.indexOf(name.charAt(i)) >= 0) {
                        input.setError(activity.getString(R.string.rename_bad_chars));
                        return;
                    }
                }
                dialog.dismiss();
                rename(activity, file, name + PDF_EXT, io, callback);
            });
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
    }

    private static void rename(Activity activity, ScanSession.SavedFile file, String newName, Executor io, RenameCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        ContentResolver resolver = activity.getContentResolver();
        io.execute(() -> {
            try {
                Uri renamed;
                if (file.inMediaStore) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                    if (resolver.update(file.uri, values, null, null) == 0) {
                        throw new IOException(activity.getString(R.string.rename_not_found));
                    }
                    renamed = file.uri;
                } else {
                    renamed = DocumentsContract.renameDocument(resolver, file.uri, newName);
                    if (renamed == null) {
                        throw new IOException(activity.getString(R.string.rename_not_found));
                    }
                }
                String actual = displayName(resolver, renamed, newName);
                main.post(() -> {
                    file.uri = renamed;
                    file.name = actual;
                    callback.onRenamed(actual);
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> callback.onFailed(String.valueOf(e.getMessage())));
            }
        });
    }

    static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall back to the requested name
        }
        return fallback;
    }
}
