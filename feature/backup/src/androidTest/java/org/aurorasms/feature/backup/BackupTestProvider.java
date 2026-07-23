// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Synthetic provider fixture; it contains no device Telephony content. */
public final class BackupTestProvider extends ContentProvider {
    public static final String AUTHORITY = "org.aurorasms.feature.backup.test.provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        if (projection == null) throw new IllegalArgumentException("Projection required");
        MatrixCursor cursor = new MatrixCursor(projection);
        String path = uri.getPath();
        if ("/sms".equals(path)) {
            addProjectedRow(cursor, projection, new SmsValues());
        } else if ("/mms".equals(path)) {
            addProjectedRow(cursor, projection, new MmsValues());
        } else if ("/mms/2/addr".equals(path)) {
            addProjectedRow(cursor, projection, new AddressValues(30L, 137, "+15550000001"));
            addProjectedRow(cursor, projection, new AddressValues(31L, 151, "insert-address-token"));
        } else if ("/mms/2/part".equals(path)) {
            addProjectedRow(cursor, projection, new TextPartValues());
            addProjectedRow(cursor, projection, new BinaryPartValues());
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !"/part/21".equals(uri.getPath())) {
            throw new FileNotFoundException("Unknown synthetic part");
        }
        File file = new File(fixtureContext().getCacheDir(), "backup-test-part.bin");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            for (int index = 0; index < 196_608; index += 1) output.write(index % 251);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    private android.content.Context fixtureContext() throws FileNotFoundException {
        android.content.Context context = getContext();
        if (context == null) throw new FileNotFoundException("No context");
        return context;
    }

    private static void addProjectedRow(MatrixCursor cursor, String[] projection, Values values) {
        Object[] row = new Object[projection.length];
        for (int index = 0; index < projection.length; index += 1) {
            row[index] = values.value(projection[index]);
        }
        cursor.addRow(row);
    }

    private interface Values {
        Object value(String column);
    }

    private static final class SmsValues implements Values {
        @Override
        public Object value(String column) {
            switch (column) {
                case "_id": return 1L;
                case "type": return 1;
                case "address": return "+15550000000";
                case "body": return "synthetic SMS";
                case "date": return 1_700_000_000_000L;
                case "date_sent": return 1_699_999_999_000L;
                case "read": return 1;
                case "seen": return 1;
                case "locked": return 0;
                case "status": return -1;
                case "error_code": return 0;
                case "protocol": return 0;
                case "reply_path_present": return 0;
                case "subject": return null;
                case "service_center": return "+15559999999";
                case "sub_id": return 1;
                default: throw new IllegalArgumentException("Unknown SMS column " + column);
            }
        }
    }

    private static final class MmsValues implements Values {
        @Override
        public Object value(String column) {
            switch (column) {
                case "_id": return 2L;
                case "msg_box": return 1;
                case "date": return 1_700_000_000L;
                case "date_sent": return 1_699_999_999L;
                case "read": return 0;
                case "seen": return 1;
                case "locked": return 0;
                case "sub_id": return 1;
                case "m_type": return 132;
                case "v": return 18;
                case "pri": return 129;
                case "st": return 128;
                case "resp_st": return 128;
                case "retr_st": return 128;
                case "rr": return 129;
                case "d_rpt": return 128;
                case "rpt_a": return 129;
                case "m_size": return 196_700L;
                case "exp": return 86_400L;
                case "d_tm": return 0L;
                case "sub": return "synthetic MMS";
                case "sub_cs": return 106;
                case "ct_t": return "application/vnd.wap.multipart.related";
                case "ct_l": return "synthetic-location";
                case "m_cls": return "personal";
                case "tr_id": return "synthetic-transaction";
                default: throw new IllegalArgumentException("Unknown MMS column " + column);
            }
        }
    }

    private static final class AddressValues implements Values {
        private final long id;
        private final int type;
        private final String address;

        AddressValues(long id, int type, String address) {
            this.id = id;
            this.type = type;
            this.address = address;
        }

        @Override
        public Object value(String column) {
            switch (column) {
                case "_id": return id;
                case "type": return type;
                case "address": return address;
                case "charset": return 106;
                default: throw new IllegalArgumentException("Unknown address column " + column);
            }
        }
    }

    private static final class TextPartValues implements Values {
        @Override
        public Object value(String column) {
            switch (column) {
                case "_id": return 20L;
                case "seq": return 0;
                case "ct": return "text/plain";
                case "chset": return 106;
                case "name": return null;
                case "cd": return null;
                case "fn": return null;
                case "cid": return "<text>";
                case "cl": return null;
                case "text": return "synthetic MMS body";
                case "_data": return null;
                default: throw new IllegalArgumentException("Unknown text part column " + column);
            }
        }
    }

    private static final class BinaryPartValues implements Values {
        @Override
        public Object value(String column) {
            switch (column) {
                case "_id": return 21L;
                case "seq": return 1;
                case "ct": return "image/jpeg";
                case "chset": return null;
                case "name": return "aurora.jpg";
                case "cd": return "attachment";
                case "fn": return "aurora.jpg";
                case "cid": return "<image>";
                case "cl": return "aurora.jpg";
                case "text": return null;
                case "_data": return "/synthetic/provider/path";
                default: throw new IllegalArgumentException("Unknown binary part column " + column);
            }
        }
    }
}
