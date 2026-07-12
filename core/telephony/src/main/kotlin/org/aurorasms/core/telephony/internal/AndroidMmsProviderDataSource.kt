// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.MmsAttachmentType
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.buildProviderPageFromRaw

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
        val beforeSeconds = request.before?.timestampMillis?.div(MILLIS_PER_SECOND_VALUE)
        val eligibleRows =
            "${BaseColumns._ID} > 0 AND " +
                "${Telephony.Mms.DATE} BETWEEN 0 AND $MAX_MMS_DATE_SECONDS"
        val selection = request.before?.let {
            "$eligibleRows AND " +
                "((${Telephony.Mms.DATE} < ?) OR " +
                "(${Telephony.Mms.DATE} = ? AND ${BaseColumns._ID} < ?))"
        } ?: eligibleRows
        val selectionArgs = request.before?.let {
            arrayOf(
                beforeSeconds.toString(),
                beforeSeconds.toString(),
                it.providerRowId.toString(),
            )
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
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
            Telephony.Mms.LOCKED,
            Telephony.Mms.STATUS,
            Telephony.Mms.RESPONSE_STATUS,
            Telephony.Mms.RETRIEVE_STATUS,
            Telephony.Mms.MESSAGE_SIZE,
        )

        resolver.query(Telephony.Mms.CONTENT_URI, projection, queryArgs, null)?.use { cursor ->
            val rawRows = ArrayList<RawMmsProviderRow>(request.limit + 1)
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
            val boxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val sentIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT)
            val subscriptionIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
            val seenIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SEEN)
            val lockedIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.LOCKED)
            val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.STATUS)
            val responseStatusIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.RESPONSE_STATUS)
            val retrieveStatusIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.RETRIEVE_STATUS)
            val messageSizeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_SIZE)

            while (rawRows.size <= request.limit && cursor.moveToNext()) {
                rawRows += RawMmsProviderRow(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIndex),
                    subject = cursor.getString(subjectIndex),
                    messageBox = cursor.getInt(boxIndex),
                    timestampSeconds = cursor.getLong(dateIndex),
                    sentTimestampSeconds = cursor.nullableLong(sentIndex),
                    subscriptionId = cursor.nullableInt(subscriptionIndex),
                    read = cursor.getInt(readIndex) != 0,
                    seen = cursor.getInt(seenIndex) != 0,
                    locked = cursor.getInt(lockedIndex) != 0,
                    rawStatus = cursor.nullableInt(statusIndex),
                    rawResponseStatus = cursor.nullableInt(responseStatusIndex),
                    rawRetrieveStatus = cursor.nullableInt(retrieveStatusIndex),
                    messageSizeBytes = cursor.nullableLong(messageSizeIndex),
                )
            }

            ProviderAccessResult.Success(
                buildProviderPageFromRaw(
                    request = request,
                    rawRows = rawRows,
                    cursorFor = RawMmsProviderRow::pageCursor,
                    project = ::projectMmsRow,
                ),
            )
        } ?: ProviderAccessResult.Unavailable("read MMS page")
    }

    private fun projectMmsRow(raw: RawMmsProviderRow): MmsProviderMessage? {
        if (raw.id <= 0L || raw.threadId <= 0L || raw.timestampSeconds < 0L) return null
        val providerId = ProviderMessageId(ProviderKind.MMS, raw.id)
        val providerThreadId = ProviderThreadId(raw.threadId)
        val addresses = readAddressMetadata(providerId.value)
        val parts = readPartMetadata(
            providerId = providerId.value,
            totalBytes = raw.messageSizeBytes?.takeIf { it >= 0L },
        )
        val messageBox = raw.messageBox.toMmsMessageBox()
        val messageStatus = messageBox.toMmsMessageStatus()
        val timestampMillis = raw.timestampSeconds.secondsToMillis()
        val sentTimestampMillis = raw.sentTimestampSeconds
            ?.takeIf { it >= 0L }
            ?.secondsToMillis()
        val subscription = raw.subscriptionId
            ?.takeIf { it >= 0 }
            ?.let(::AuroraSubscriptionId)
        val subject = raw.subject?.take(MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS)
        val fingerprint = ProviderProjectionFingerprint.mms(
            providerId = providerId,
            providerThreadId = providerThreadId,
            sender = addresses.sender,
            participants = addresses.participants,
            participantsTruncated = addresses.truncated,
            body = parts.body,
            subject = subject,
            box = messageBox,
            status = messageStatus,
            rawStatus = raw.rawStatus,
            rawResponseStatus = raw.rawResponseStatus,
            rawRetrieveStatus = raw.rawRetrieveStatus,
            timestampMillis = timestampMillis,
            sentTimestampMillis = sentTimestampMillis,
            subscriptionId = subscription,
            attachments = parts.attachments,
            read = raw.read,
            seen = raw.seen,
            locked = raw.locked,
        )
        return MmsProviderMessage(
            id = providerId,
            providerThreadId = providerThreadId,
            sender = addresses.sender,
            participants = addresses.participants,
            participantsTruncated = addresses.truncated,
            body = parts.body,
            subject = subject,
            direction = messageBox.toDirection(),
            box = messageBox,
            status = messageStatus,
            rawStatus = raw.rawStatus,
            rawResponseStatus = raw.rawResponseStatus,
            rawRetrieveStatus = raw.rawRetrieveStatus,
            timestampMillis = timestampMillis,
            sentTimestampMillis = sentTimestampMillis,
            subscriptionId = subscription,
            attachments = parts.attachments,
            read = raw.read,
            seen = raw.seen,
            locked = raw.locked,
            syncFingerprint = fingerprint,
        )
    }

    private fun readAddressMetadata(providerId: Long): MmsAddressMetadata {
        return try {
            val uri = mmsMetadataUri(providerId, MMS_ADDRESS_PATH)
            val rawRows = metadataCursor(
                uri = uri,
                projection = arrayOf(BaseColumns._ID, Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                limit = MAX_INSPECTED_MMS_ADDRESS_ROWS + 1,
            )?.use { cursor ->
                val rows = ArrayList<RawMmsAddress>(MAX_INSPECTED_MMS_ADDRESS_ROWS + 1)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                while (rows.size <= MAX_INSPECTED_MMS_ADDRESS_ROWS && cursor.moveToNext()) {
                    rows += RawMmsAddress(
                        address = cursor.getString(addressIndex),
                        type = cursor.getInt(typeIndex),
                    )
                }
                rows
            } ?: return MmsAddressMetadata.INCOMPLETE

            val valid = rawRows.mapNotNull { row ->
                val normalized = row.address
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(INSERT_ADDRESS_TOKEN, ignoreCase = true) }
                    ?: return@mapNotNull null
                val address = try {
                    ParticipantAddress(normalized)
                } catch (_: IllegalArgumentException) {
                    return@mapNotNull null
                }
                row to address
            }
            val participants = LinkedHashMap<String, ParticipantAddress>()
            valid.forEach { (_, address) ->
                if (participants.size < MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
                    participants.putIfAbsent(address.value, address)
                }
            }
            MmsAddressMetadata(
                sender = valid.firstOrNull { (row) -> row.type == MMS_FROM_ADDRESS_TYPE }?.second,
                participants = participants.values.toList(),
                truncated = rawRows.size > MAX_INSPECTED_MMS_ADDRESS_ROWS,
            )
        } catch (denied: SecurityException) {
            throw denied
        } catch (_: IllegalArgumentException) {
            MmsAddressMetadata.INCOMPLETE
        }
    }

    private fun readPartMetadata(
        providerId: Long,
        totalBytes: Long?,
    ): MmsPartMetadata {
        return try {
            val uri = mmsMetadataUri(providerId, MMS_PART_PATH)
            val rawRows = metadataCursor(
                uri = uri,
                projection = arrayOf(
                    BaseColumns._ID,
                    Telephony.Mms.Part.CONTENT_TYPE,
                    Telephony.Mms.Part.TEXT,
                    Telephony.Mms.Part.FILENAME,
                    Telephony.Mms.Part.NAME,
                    Telephony.Mms.Part.CONTENT_LOCATION,
                ),
                limit = MAX_INSPECTED_MMS_PART_ROWS + 1,
            )?.use { cursor ->
                val rows = ArrayList<RawMmsPart>(MAX_INSPECTED_MMS_PART_ROWS + 1)
                val contentTypeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
                val textIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
                val filenameIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.FILENAME)
                val nameIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.NAME)
                val contentLocationIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_LOCATION)
                while (rows.size <= MAX_INSPECTED_MMS_PART_ROWS && cursor.moveToNext()) {
                    rows += RawMmsPart(
                        contentType = cursor.getString(contentTypeIndex),
                        text = cursor.getString(textIndex),
                        filename = cursor.getString(filenameIndex),
                        name = cursor.getString(nameIndex),
                        contentLocation = cursor.getString(contentLocationIndex),
                    )
                }
                rows
            } ?: return MmsPartMetadata.incomplete(totalBytes)
            projectPartMetadata(rawRows, totalBytes)
        } catch (denied: SecurityException) {
            throw denied
        } catch (_: IllegalArgumentException) {
            MmsPartMetadata.incomplete(totalBytes)
        }
    }

    private fun metadataQueryArgs(limit: Int): Bundle = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} ASC")
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    private fun mmsMetadataUri(providerId: Long, childPath: String): Uri =
        Telephony.Mms.CONTENT_URI.buildUpon()
            .appendPath(providerId.toString())
            .appendPath(childPath)
            .build()

    private fun metadataCursor(
        uri: Uri,
        projection: Array<String>,
        limit: Int,
    ): android.database.Cursor? = try {
        resolver.query(uri, projection, metadataQueryArgs(limit), null)
    } catch (_: IllegalArgumentException) {
        // Some older/OEM providers reject structured query arguments on the
        // per-message Addr/Part URIs. Raw cursor consumption remains capped by
        // the callers even when the legacy overload cannot express LIMIT.
        resolver.query(uri, projection, null, null, "${BaseColumns._ID} ASC")
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

    companion object {
        private const val MMS_FROM_ADDRESS_TYPE = 137
        private const val INSERT_ADDRESS_TOKEN = "insert-address-token"
        private const val MMS_ADDRESS_PATH = "addr"
        private const val MMS_PART_PATH = "part"
    }
}

private data class RawMmsProviderRow(
    val id: Long,
    val threadId: Long,
    val subject: String?,
    val messageBox: Int,
    val timestampSeconds: Long,
    val sentTimestampSeconds: Long?,
    val subscriptionId: Int?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val rawStatus: Int?,
    val rawResponseStatus: Int?,
    val rawRetrieveStatus: Int?,
    val messageSizeBytes: Long?,
) {
    fun pageCursor(): ProviderPageCursor = ProviderPageCursor(
        timestampMillis = timestampSeconds.secondsToMillis(),
        providerRowId = id,
    )
}

private data class RawMmsAddress(
    val address: String?,
    val type: Int,
)

private data class MmsAddressMetadata(
    val sender: ParticipantAddress?,
    val participants: List<ParticipantAddress>,
    val truncated: Boolean,
) {
    companion object {
        val INCOMPLETE = MmsAddressMetadata(
            sender = null,
            participants = emptyList(),
            truncated = true,
        )
    }
}

internal data class RawMmsPart(
    val contentType: String?,
    val text: String?,
    val filename: String?,
    val name: String?,
    val contentLocation: String?,
)

internal data class MmsPartMetadata(
    val body: String?,
    val attachments: MmsAttachmentSummary,
) {
    override fun toString(): String =
        "MmsPartMetadata(bodyLength=${body?.length ?: 0}, attachments=$attachments)"

    companion object {
        fun incomplete(totalBytes: Long?): MmsPartMetadata = MmsPartMetadata(
            body = null,
            attachments = MmsAttachmentSummary(
                attachmentCount = 0,
                totalBytes = totalBytes,
                contentTypes = emptyList(),
                metadataTruncated = true,
            ),
        )
    }
}

internal fun projectPartMetadata(
    rawRows: List<RawMmsPart>,
    totalBytes: Long?,
): MmsPartMetadata {
    val body = StringBuilder()
    val attachmentTypes = ArrayList<MmsAttachmentType>(MmsAttachmentSummary.MAX_ATTACHMENT_COUNT)
    var attachmentRows = 0
    var metadataTruncated = rawRows.size > MAX_INSPECTED_MMS_PART_ROWS

    rawRows.take(MAX_INSPECTED_MMS_PART_ROWS).forEach { part ->
        when (part.normalizedContentType()) {
            TEXT_PLAIN_MIME_TYPE -> {
                val text = part.text.orEmpty()
                if (text.isNotEmpty() && body.length < MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS) {
                    if (body.isNotEmpty()) body.append('\n')
                    val remaining = MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS - body.length
                    body.append(text.take(remaining))
                    if (text.length > remaining) metadataTruncated = true
                } else if (text.isNotEmpty()) {
                    metadataTruncated = true
                }
            }

            SMIL_MIME_TYPE -> Unit
            else -> {
                attachmentRows += 1
                if (attachmentTypes.size < MmsAttachmentSummary.MAX_ATTACHMENT_COUNT) {
                    attachmentTypes += part.toAttachmentType()
                } else {
                    metadataTruncated = true
                }
            }
        }
    }
    val boundedAttachmentCount = attachmentRows.coerceAtMost(MmsAttachmentSummary.MAX_ATTACHMENT_COUNT)
    if (attachmentRows > boundedAttachmentCount) metadataTruncated = true
    return MmsPartMetadata(
        body = body.takeIf { it.isNotEmpty() }?.toString(),
        attachments = MmsAttachmentSummary(
            attachmentCount = boundedAttachmentCount,
            totalBytes = totalBytes,
            contentTypes = attachmentTypes,
            metadataTruncated = metadataTruncated,
        ),
    )
}

private fun RawMmsPart.normalizedContentType(): String =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: DEFAULT_BINARY_MIME_TYPE

private fun RawMmsPart.toAttachmentType(): MmsAttachmentType {
    val displayName = sequenceOf(filename, name, contentLocation)
        .mapNotNull { candidate ->
            candidate
                ?.trim()
                ?.take(MmsAttachmentType.MAX_DISPLAY_NAME_CHARACTERS)
                ?.takeIf {
                    it.isNotEmpty() &&
                        it.none(Char::isISOControl) &&
                        '/' !in it &&
                        '\\' !in it
                }
        }
        .firstOrNull()
    return try {
        MmsAttachmentType(normalizedContentType(), displayName)
    } catch (_: IllegalArgumentException) {
        MmsAttachmentType(DEFAULT_BINARY_MIME_TYPE, displayName)
    }
}

private fun Int.toMmsMessageBox(): MessageBox = when (this) {
    Telephony.Mms.MESSAGE_BOX_INBOX -> MessageBox.INBOX
    Telephony.Mms.MESSAGE_BOX_SENT -> MessageBox.SENT
    Telephony.Mms.MESSAGE_BOX_DRAFTS -> MessageBox.DRAFT
    Telephony.Mms.MESSAGE_BOX_OUTBOX -> MessageBox.OUTBOX
    Telephony.Mms.MESSAGE_BOX_FAILED -> MessageBox.FAILED
    else -> MessageBox.UNKNOWN
}

private fun MessageBox.toMmsMessageStatus(): MessageStatus = when (this) {
    MessageBox.INBOX,
    MessageBox.SENT,
    -> MessageStatus.COMPLETE
    MessageBox.OUTBOX,
    MessageBox.QUEUED,
    -> MessageStatus.PENDING
    MessageBox.FAILED -> MessageStatus.FAILED
    MessageBox.DRAFT -> MessageStatus.NONE
    MessageBox.UNKNOWN -> MessageStatus.UNKNOWN
}

private fun MessageBox.toDirection(): MessageDirection =
    if (this == MessageBox.INBOX) MessageDirection.INCOMING else MessageDirection.OUTGOING

private fun android.database.Cursor.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun android.database.Cursor.nullableInt(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun Long.secondsToMillis(): Long =
    coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND_VALUE) * MILLIS_PER_SECOND_VALUE

private const val MILLIS_PER_SECOND_VALUE = 1_000L
private const val MAX_MMS_DATE_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND_VALUE
private const val MAX_INSPECTED_MMS_ADDRESS_ROWS = MmsProviderMessage.MAX_MMS_PARTICIPANTS
private const val MAX_INSPECTED_MMS_PART_ROWS = 100
private const val TEXT_PLAIN_MIME_TYPE = "text/plain"
private const val SMIL_MIME_TYPE = "application/smil"
private const val DEFAULT_BINARY_MIME_TYPE = "application/octet-stream"
