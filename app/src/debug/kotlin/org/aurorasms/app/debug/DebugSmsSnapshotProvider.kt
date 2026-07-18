// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.net.toUri
import java.io.FileNotFoundException

/**
 * Debug-only, read-only shell view of the non-sensitive SMS row identity fields.
 *
 * The manifest permission is the outer platform boundary. The explicit UID check is a second,
 * provider-owned boundary so a privileged non-shell process cannot reuse this test seam.
 */
class DebugSmsSnapshotProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceShellCaller()
        requireSnapshotUri(uri)
        require(selection.isNullOrEmpty()) { "Selection is not supported" }
        require(selectionArgs.isNullOrEmpty()) { "Selection arguments are not supported" }
        require(sortOrder.isNullOrEmpty()) { "Sort order is not supported" }

        val requestedColumns = snapshotColumns(projection)

        val identity = Binder.clearCallingIdentity()
        return try {
            snapshot(requestedColumns)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun snapshot(requestedColumns: List<String>): Cursor {
        val result = MatrixCursor(requestedColumns.toTypedArray())
        val resolver = requireNotNull(context).contentResolver
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            SNAPSHOT_COLUMNS.toTypedArray(),
            null,
            null,
            null,
        )?.use { source ->
            val idIndex = source.getColumnIndexOrThrow(BaseColumns._ID)
            val threadIdIndex = source.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val typeIndex = source.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (source.moveToNext()) {
                val row = result.newRow()
                requestedColumns.forEach { column ->
                    row.add(
                        when (column) {
                            BaseColumns._ID -> source.nullableLong(idIndex)
                            Telephony.Sms.THREAD_ID -> source.nullableLong(threadIdIndex)
                            Telephony.Sms.TYPE -> source.nullableInt(typeIndex)
                            else -> error("Projection was validated before the provider query")
                        },
                    )
                }
            }
        }
        return result
    }

    override fun getType(uri: Uri): String {
        enforceShellCaller()
        requireSnapshotUri(uri)
        return MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = readOnly()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        readOnly()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = readOnly()

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = readOnly()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        throw FileNotFoundException(READ_ONLY_MESSAGE)

    private fun enforceShellCaller() {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Debug SMS snapshot is restricted to the Android shell UID")
        }
    }

    private fun requireSnapshotUri(uri: Uri) {
        require(uri == CONTENT_URI) { "Unsupported URI" }
    }

    private fun <T> readOnly(): T = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        const val AUTHORITY = "org.aurorasms.app.debug.sms_snapshot"
        val CONTENT_URI: Uri = "content://$AUTHORITY/sms".toUri()
        val SNAPSHOT_COLUMNS: List<String> = listOf(
            BaseColumns._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.TYPE,
        )

        private const val MIME_TYPE = "vnd.android.cursor.dir/vnd.aurorasms.debug.sms_snapshot"
        private const val READ_ONLY_MESSAGE = "Debug SMS snapshot provider is read-only"
        private val SNAPSHOT_COLUMN_SET = SNAPSHOT_COLUMNS.toSet()

        internal fun snapshotColumns(projection: Array<out String>?): List<String> {
            val requestedColumns = projection?.toList() ?: SNAPSHOT_COLUMNS
            require(requestedColumns.isNotEmpty()) { "Projection must not be empty" }
            require(requestedColumns.distinct().size == requestedColumns.size) {
                "Projection must not contain duplicate columns"
            }
            require(requestedColumns.all(SNAPSHOT_COLUMN_SET::contains)) {
                "Unsupported projection"
            }
            return requestedColumns
        }
    }
}

private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
