// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.FileNotFoundException;

/** One test-only SAF root and one deterministic image; never present in a production APK. */
public final class WallpaperTestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "org.aurorasms.app.wallpaper.testdocuments";
    public static final String ROOT_ID = "aurora_saf_root_7e3b2c91";
    public static final String ROOT_DOCUMENT_ID = "aurora_saf_root_document_7e3b2c91";
    public static final String IMAGE_DOCUMENT_ID = "aurora_saf_image_7e3b2c91";
    public static final String ROOT_TITLE = "AuroraSMS SAF Fixture 7E3B2C91";
    public static final String IMAGE_DISPLAY_NAME = "aurora-saf-fixture-7e3b2c91.png";
    public static final String IMAGE_MIME_TYPE = "image/png";

    public static final String METHOD_RESET = "aurora_saf_reset";
    public static final String METHOD_SET_AVAILABLE = "aurora_saf_set_available";
    public static final String METHOD_SNAPSHOT = "aurora_saf_snapshot";
    public static final String KEY_AVAILABLE = "available";
    public static final String KEY_OPEN_ATTEMPTS = "open_attempts";
    public static final String KEY_SUCCESSFUL_OPENS = "successful_opens";
    public static final String KEY_LAST_DOCUMENT_ID = "last_document_id";

    private static final String STATE_PREFERENCES = "aurora_saf_document_state";
    private static final Uri SOURCE_URI = Uri.parse(
        "content://org.aurorasms.app.wallpaper.testprovider/cold-restart.png"
    );
    private static final Object STATE_LOCK = new Object();

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        synchronized (STATE_LOCK) {
            SharedPreferences state = state(context);
            if (!state.contains(KEY_AVAILABLE)) {
                state.edit().putBoolean(KEY_AVAILABLE, true).commit();
            }
        }
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder row = cursor.newRow();
        add(row, cursor, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        add(row, cursor, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        add(row, cursor, DocumentsContract.Root.COLUMN_TITLE, ROOT_TITLE);
        add(row, cursor, DocumentsContract.Root.COLUMN_SUMMARY, "One synthetic test-only PNG");
        add(row, cursor, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
        add(row, cursor, DocumentsContract.Root.COLUMN_MIME_TYPES, IMAGE_MIME_TYPE);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
        includeDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId,
            String[] projection,
            String sortOrder
    ) throws FileNotFoundException {
        if (!ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            throw new FileNotFoundException("Unknown synthetic SAF parent");
        }
        MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
        includeDocument(cursor, IMAGE_DOCUMENT_ID);
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_DOCUMENT_ID.equals(parentDocumentId) && IMAGE_DOCUMENT_ID.equals(documentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        if (ROOT_DOCUMENT_ID.equals(documentId)) return DocumentsContract.Document.MIME_TYPE_DIR;
        if (IMAGE_DOCUMENT_ID.equals(documentId)) return IMAGE_MIME_TYPE;
        throw new FileNotFoundException("Unknown synthetic SAF document");
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        if (!IMAGE_DOCUMENT_ID.equals(documentId) || !"r".equals(mode)) {
            throw new FileNotFoundException("Synthetic SAF document is read only");
        }
        Context context = requireProviderContext();
        synchronized (STATE_LOCK) {
            SharedPreferences current = state(context);
            int attempts = current.getInt(KEY_OPEN_ATTEMPTS, 0) + 1;
            current.edit()
                .putInt(KEY_OPEN_ATTEMPTS, attempts)
                .putString(KEY_LAST_DOCUMENT_ID, documentId)
                .commit();
            if (!current.getBoolean(KEY_AVAILABLE, true)) {
                throw new FileNotFoundException("Synthetic SAF source unavailable");
            }
        }
        ParcelFileDescriptor descriptor = context.getContentResolver()
            .openFileDescriptor(SOURCE_URI, "r", signal);
        if (descriptor == null) throw new FileNotFoundException("Synthetic SAF source unavailable");
        synchronized (STATE_LOCK) {
            SharedPreferences current = state(context);
            current.edit()
                .putInt(
                    KEY_SUCCESSFUL_OPENS,
                    current.getInt(KEY_SUCCESSFUL_OPENS, 0) + 1
                )
                .commit();
        }
        return descriptor;
    }

    static Bundle resetState(Context context) {
        if (context == null) return new Bundle();
        synchronized (STATE_LOCK) {
            state(context).edit()
                .clear()
                .putBoolean(KEY_AVAILABLE, true)
                .putInt(KEY_OPEN_ATTEMPTS, 0)
                .putInt(KEY_SUCCESSFUL_OPENS, 0)
                .commit();
            return snapshotStateLocked(context);
        }
    }

    static Bundle setAvailable(Context context, boolean available) {
        if (context == null) return new Bundle();
        synchronized (STATE_LOCK) {
            state(context).edit().putBoolean(KEY_AVAILABLE, available).commit();
            return snapshotStateLocked(context);
        }
    }

    static Bundle snapshotState(Context context) {
        if (context == null) return new Bundle();
        synchronized (STATE_LOCK) {
            return snapshotStateLocked(context);
        }
    }

    private static Bundle snapshotStateLocked(Context context) {
        SharedPreferences current = state(context);
        Bundle result = new Bundle();
        result.putBoolean(KEY_AVAILABLE, current.getBoolean(KEY_AVAILABLE, true));
        result.putInt(KEY_OPEN_ATTEMPTS, current.getInt(KEY_OPEN_ATTEMPTS, 0));
        result.putInt(KEY_SUCCESSFUL_OPENS, current.getInt(KEY_SUCCESSFUL_OPENS, 0));
        result.putString(KEY_LAST_DOCUMENT_ID, current.getString(KEY_LAST_DOCUMENT_ID, null));
        return result;
    }

    private void includeDocument(MatrixCursor cursor, String documentId)
            throws FileNotFoundException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            add(row, cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
            add(row, cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_TITLE);
            add(
                row,
                cursor,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.MIME_TYPE_DIR
            );
            add(row, cursor, DocumentsContract.Document.COLUMN_FLAGS, 0);
            return;
        }
        if (IMAGE_DOCUMENT_ID.equals(documentId)) {
            add(row, cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, IMAGE_DOCUMENT_ID);
            add(row, cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, IMAGE_DISPLAY_NAME);
            add(row, cursor, DocumentsContract.Document.COLUMN_MIME_TYPE, IMAGE_MIME_TYPE);
            add(row, cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L);
            add(row, cursor, DocumentsContract.Document.COLUMN_FLAGS, 0);
            return;
        }
        throw new FileNotFoundException("Unknown synthetic SAF document");
    }

    private Context requireProviderContext() throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Synthetic SAF context unavailable");
        return context;
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void add(
            MatrixCursor.RowBuilder row,
            MatrixCursor cursor,
            String column,
            Object value
    ) {
        for (String present : cursor.getColumnNames()) {
            if (column.equals(present)) {
                row.add(column, value);
                return;
            }
        }
    }

    private static String[] resolveRootProjection(String[] projection) {
        if (projection != null) return projection;
        return new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        };
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        if (projection != null) return projection;
        return new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        };
    }
}
