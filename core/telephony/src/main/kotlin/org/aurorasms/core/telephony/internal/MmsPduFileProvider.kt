// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** A direction-enforcing, cache-only provider for one MMS platform operation. */
class MmsPduFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val direction = directionFrom(uri)
            ?: throw FileNotFoundException("Unknown MMS staging URI")
        val accepted = when (direction) {
            MmsPduDirection.SEND_SOURCE -> mode == READ_MODE
            MmsPduDirection.DOWNLOAD_TARGET -> mode == WRITE_MODE || mode == WRITE_TRUNCATE_MODE
        }
        if (!accepted) {
            throw FileNotFoundException("MMS staging URI does not permit this access mode")
        }
        return super.openFile(uri, mode)
            ?: throw FileNotFoundException("MMS staging file is unavailable")
    }

    override fun getType(uri: Uri): String {
        if (directionFrom(uri) == null) {
            throw IllegalArgumentException("Unknown MMS staging URI")
        }
        return MMS_PDU_MIME_TYPE
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        check(Binder.getCallingUid() == Process.myUid()) { "Only AuroraSMS may delete staged MMS files" }
        return super.delete(uri, selection, selectionArgs)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("MMS staging files are created by AuroraSMS")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("MMS staging metadata cannot be updated")

    private fun directionFrom(uri: Uri): MmsPduDirection? {
        val segments = uri.pathSegments
        if (segments.size != EXPECTED_PATH_SEGMENTS || !FILE_NAME.matches(segments[1])) return null
        return when (segments[0]) {
            MmsPduStagingStore.SEND_PATH_NAME -> MmsPduDirection.SEND_SOURCE
            MmsPduStagingStore.DOWNLOAD_PATH_NAME -> MmsPduDirection.DOWNLOAD_TARGET
            else -> null
        }
    }

    companion object {
        private const val READ_MODE = "r"
        private const val WRITE_MODE = "w"
        private const val WRITE_TRUNCATE_MODE = "wt"
        private const val EXPECTED_PATH_SEGMENTS = 2
        private const val MMS_PDU_MIME_TYPE = "application/vnd.wap.mms-message"
        private val FILE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdu")
    }
}
