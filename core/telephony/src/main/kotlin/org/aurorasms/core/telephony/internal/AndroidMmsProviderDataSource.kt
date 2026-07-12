// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage

class AndroidMmsProviderDataSource(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MmsProviderDataSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun count(): ProviderAccessResult<Long> = withReadAccess("count MMS") {
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { ProviderAccessResult.Success(it.count.toLong()) }
            ?: ProviderAccessResult.Unavailable("count MMS")
    }

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> = withReadAccess("read MMS page") {
        val beforeSeconds = request.before?.timestampMillis?.div(MILLIS_PER_SECOND)
        val selection = request.before?.let {
            "(${Telephony.Mms.DATE} < ?) OR (${Telephony.Mms.DATE} = ? AND ${BaseColumns._ID} < ?)"
        }
        val selectionArgs = request.before?.let {
            arrayOf(
                beforeSeconds.toString(),
                beforeSeconds.toString(),
                it.providerRowId.toString(),
            )
        }
        val queryArgs = Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${Telephony.Mms.DATE} DESC, ${BaseColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.limit + 1)
        }
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.DATE,
            Telephony.Mms.DATE_SENT,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.READ,
            Telephony.Mms.SEEN,
        )

        resolver.query(Telephony.Mms.CONTENT_URI, projection, queryArgs, null)?.use { cursor ->
            val rows = ArrayList<MmsProviderMessage>(request.limit + 1)
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
            val boxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val sentIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT)
            val subscriptionIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
            val seenIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SEEN)

            while (cursor.moveToNext() && rows.size <= request.limit) {
                val id = cursor.getLong(idIndex)
                if (id <= 0L) continue
                val timestamp = cursor.getLong(dateIndex).secondsToMillis()
                val sent = if (cursor.isNull(sentIndex)) null else cursor.getLong(sentIndex).secondsToMillis()
                val subscription = if (cursor.isNull(subscriptionIndex)) null else cursor.getInt(subscriptionIndex)
                rows += MmsProviderMessage(
                    id = ProviderMessageId(ProviderKind.MMS, id),
                    threadId = cursor.getLong(threadIndex).coerceAtLeast(0L),
                    participants = emptyList(),
                    subject = cursor.getString(subjectIndex)?.take(MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS),
                    direction = if (cursor.getInt(boxIndex) == Telephony.Mms.MESSAGE_BOX_INBOX) {
                        MessageDirection.INCOMING
                    } else {
                        MessageDirection.OUTGOING
                    },
                    timestampMillis = timestamp,
                    sentTimestampMillis = sent,
                    subscriptionId = subscription?.takeIf { it >= 0 }?.let(::AuroraSubscriptionId),
                    read = cursor.getInt(readIndex) != 0,
                    seen = cursor.getInt(seenIndex) != 0,
                )
            }

            val hasMore = rows.size > request.limit
            val pageRows = if (hasMore) rows.take(request.limit) else rows
            val last = pageRows.lastOrNull()
            ProviderAccessResult.Success(
                ProviderPage(
                    items = pageRows,
                    next = if (hasMore && last != null) {
                        ProviderPageCursor(last.timestampMillis, last.id.value)
                    } else {
                        null
                    },
                    exhausted = !hasMore,
                ),
            )
        } ?: ProviderAccessResult.Unavailable("read MMS page")
    }

    override suspend fun insertIncoming(
        message: DecodedIncomingMmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) {
            ProviderAccessResult.RoleRequired
        } else {
            // A decoded header alone is insufficient to atomically create the
            // provider row, addresses, parts, and SMIL. ADR 0001 forbids a
            // partial row that would look like successful MMS persistence.
            ProviderAccessResult.Unsupported("audited MMS provider codec")
        }
    }

    private suspend fun <T> withReadAccess(
        operation: String,
        block: () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) return@withContext ProviderAccessResult.RoleRequired
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ProviderAccessResult.PermissionDenied
        }
        try {
            block()
        } catch (_: SecurityException) {
            ProviderAccessResult.PermissionDenied
        } catch (_: IllegalArgumentException) {
            ProviderAccessResult.InvalidInput(operation)
        } catch (_: RuntimeException) {
            ProviderAccessResult.Unavailable(operation)
        }
    }

    private fun Long.secondsToMillis(): Long =
        coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
