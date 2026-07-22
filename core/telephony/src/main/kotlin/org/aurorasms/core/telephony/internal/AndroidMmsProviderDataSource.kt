// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.aurorasms.core.model.asConversationId
import org.aurorasms.core.telephony.DecodedIncomingMmsPart
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.OutgoingVoiceMemoProviderRecord
import org.aurorasms.core.telephony.OutgoingMmsProviderRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.buildProviderPageFromRaw

class AndroidMmsProviderDataSource internal constructor(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val ioDispatcher: CoroutineDispatcher,
    private val resolver: ContentResolver,
    private val threadIdResolver: (Set<String>) -> Long? = { recipients ->
        Telephony.Threads.getOrCreateThreadId(context.applicationContext, recipients)
    },
) : MmsProviderDataSource {
    constructor(
        context: Context,
        roleState: DefaultSmsRoleState,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context = context,
        roleState = roleState,
        ioDispatcher = ioDispatcher,
        resolver = context.applicationContext.contentResolver,
    )

    private val appContext = context.applicationContext
    private val insertMutex = Mutex()

    override suspend fun count(): ProviderAccessResult<Long> = withReadAccess("count MMS") {
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            MMS_INDEX_ELIGIBILITY_SELECTION,
            null,
            null,
        )?.use { ProviderAccessResult.Success(it.count.toLong()) }
            ?: ProviderAccessResult.Unavailable("count MMS")
    }

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> = withReadAccess("read MMS page") {
        val beforeSeconds = request.before?.timestampMillis?.div(MILLIS_PER_SECOND_VALUE)
        val selection = request.before?.let {
            "$MMS_INDEX_ELIGIBILITY_SELECTION AND " +
                "((${Telephony.Mms.DATE} < ?) OR " +
                "(${Telephony.Mms.DATE} = ? AND ${BaseColumns._ID} < ?))"
        } ?: MMS_INDEX_ELIGIBILITY_SELECTION
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

    override suspend fun readExact(
        id: ProviderMessageId,
    ): ProviderAccessResult<MmsProviderMessage?> = withReadAccess("read exact MMS") {
        if (id.kind != ProviderKind.MMS) {
            return@withReadAccess ProviderAccessResult.InvalidInput("provider message kind")
        }
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id.value)
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
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ProviderAccessResult.Success(null)
            val raw = RawMmsProviderRow(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
                threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)),
                subject = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)),
                messageBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)),
                timestampSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)),
                sentTimestampSeconds = cursor.nullableLong(
                    cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT),
                ),
                subscriptionId = cursor.nullableInt(
                    cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID),
                ),
                read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.READ)) != 0,
                seen = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.SEEN)) != 0,
                locked = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.LOCKED)) != 0,
                rawStatus = cursor.nullableInt(cursor.getColumnIndexOrThrow(Telephony.Mms.STATUS)),
                rawResponseStatus = cursor.nullableInt(
                    cursor.getColumnIndexOrThrow(Telephony.Mms.RESPONSE_STATUS),
                ),
                rawRetrieveStatus = cursor.nullableInt(
                    cursor.getColumnIndexOrThrow(Telephony.Mms.RETRIEVE_STATUS),
                ),
                messageSizeBytes = cursor.nullableLong(
                    cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_SIZE),
                ),
            )
            ProviderAccessResult.Success(projectMmsRow(raw))
        } ?: ProviderAccessResult.Unavailable("read exact MMS")
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

            projectAddressMetadata(rawRows)
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
    ): ProviderAccessResult<ProviderStoredMessage> = insertMutex.withLock {
        withWriteAccess("insert incoming MMS") {
            insertIncomingSerialized(message)
        }
    }

    private fun insertIncomingSerialized(
        message: DecodedIncomingMmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> {
        val duplicate = findExistingIncoming(message)
        when (duplicate) {
            is ExistingIncomingMms.Complete -> return ProviderAccessResult.Success(duplicate.stored)
            is ExistingIncomingMms.Incomplete -> {
                if (!deleteIncompleteIncoming(duplicate.uri, message)) {
                    return ProviderAccessResult.Unavailable("remove incomplete incoming MMS")
                }
            }
            ExistingIncomingMms.None -> Unit
            ExistingIncomingMms.Ambiguous,
            ExistingIncomingMms.Unavailable,
            -> return ProviderAccessResult.Unavailable("read existing incoming MMS")
        }

        val dummyId = dummyMmsPartOwnerId(message.operationId.value)
        val dummyPartUri = "content://mms/$dummyId/part".toUri()
        var messageUri: Uri? = null
        var rowComplete = false
        return try {
            resolver.delete(dummyPartUri, null, null)
            val partCount = persistIncomingParts(dummyPartUri, message.parts)
            val threadId = threadIdResolver(message.participants.map(ParticipantAddress::value).toSet())
                ?.takeIf { it > 0L }
                ?: return ProviderAccessResult.Unavailable("resolve incoming MMS thread")
            val subscriptionId = message.subscriptionId.value
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, message.receivedTimestampMillis / MILLIS_PER_SECOND_VALUE)
                put(Telephony.Mms.DATE_SENT, message.sentTimestampMillis / MILLIS_PER_SECOND_VALUE)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.MESSAGE_TYPE, MMS_RETRIEVE_CONF_TYPE)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                put(Telephony.Mms.CONTENT_TYPE, MMS_MULTIPART_RELATED)
                put(Telephony.Mms.TRANSACTION_ID, message.notificationTransactionId)
                message.messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
                put(Telephony.Mms.MESSAGE_CLASS, MMS_MESSAGE_CLASS_PERSONAL)
                put(Telephony.Mms.RETRIEVE_STATUS, MMS_RETRIEVE_STATUS_OK)
                put(Telephony.Mms.MESSAGE_SIZE, message.parts.sumOf { it.size.toLong() })
                put(Telephony.Mms.TEXT_ONLY, if (message.parts.all(DecodedIncomingMmsPart::isTextOnly)) 1 else 0)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
                put(Telephony.Mms.CREATOR, appContext.packageName)
                message.subject?.let {
                    put(Telephony.Mms.SUBJECT, it)
                    put(Telephony.Mms.SUBJECT_CHARSET, MMS_UTF_8_CHARSET)
                }
            }
            messageUri = resolver.insert(Telephony.Mms.CONTENT_URI, values)
                ?: return ProviderAccessResult.Unavailable("insert incoming MMS row")
            val messageId = ContentUris.parseId(messageUri).takeIf { it > 0L }
                ?: return ProviderAccessResult.Unavailable("read inserted incoming MMS ID")
            val moved = resolver.update(
                dummyPartUri,
                ContentValues().apply { put(MMS_PART_MESSAGE_ID, messageId) },
                null,
                null,
            )
            if (moved != partCount) {
                return ProviderAccessResult.Unavailable("attach incoming MMS parts")
            }
            val addressCount = persistIncomingAddresses(messageId, message)
            if (!incomingMmsRowComplete(messageUri, message, threadId, partCount, addressCount)) {
                return ProviderAccessResult.Unavailable("verify incoming MMS row")
            }
            rowComplete = true
            ProviderAccessResult.Success(
                ProviderStoredMessage(
                    providerId = ProviderMessageId(ProviderKind.MMS, messageId),
                    conversationId = org.aurorasms.core.model.ConversationId(threadId),
                    incomingDisposition = IncomingDeliveryDisposition.NEWLY_INSERTED,
                ),
            )
        } catch (_: IOException) {
            ProviderAccessResult.Unavailable("write incoming MMS part")
        } finally {
            runCatching { resolver.delete(dummyPartUri, null, null) }
            messageUri?.takeUnless { rowComplete }?.let { incomplete ->
                runCatching { resolver.delete(incomplete, null, null) }
            }
        }
    }

    private fun findExistingIncoming(message: DecodedIncomingMmsRecord): ExistingIncomingMms {
        val subscriptionId = message.subscriptionId.value
        val selection =
            "${Telephony.Mms.CREATOR} = ? AND ${Telephony.Mms.TRANSACTION_ID} = ? AND " +
                "${Telephony.Mms.SUBSCRIPTION_ID} = ? AND ${Telephony.Mms.DATE_SENT} = ?"
        val arguments = arrayOf(
            appContext.packageName,
            message.notificationTransactionId,
            subscriptionId.toString(),
            (message.sentTimestampMillis / MILLIS_PER_SECOND_VALUE).toString(),
        )
        val candidates = resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(BaseColumns._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.MESSAGE_BOX),
            selection,
            arguments,
            null,
        )?.use { cursor ->
            buildList<Triple<Long, Long, Int>> {
                while (size < 2 && cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2)))
                }
                if (cursor.moveToNext()) add(Triple(-1L, -1L, -1))
            }
        } ?: return ExistingIncomingMms.Unavailable
        if (candidates.isEmpty()) return ExistingIncomingMms.None
        val candidate = candidates.singleOrNull() ?: return ExistingIncomingMms.Ambiguous
        if (candidate.first <= 0L || candidate.second <= 0L) return ExistingIncomingMms.Ambiguous
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, candidate.first)
        if (candidate.third != Telephony.Mms.MESSAGE_BOX_INBOX) return ExistingIncomingMms.Ambiguous
        val expectedAddressCount = message.expectedAddressCount()
        return if (
            incomingMmsRowComplete(
                uri = uri,
                message = message,
                threadId = candidate.second,
                partCount = message.parts.size,
                addressCount = expectedAddressCount,
            )
        ) {
            ExistingIncomingMms.Complete(
                ProviderStoredMessage(
                    providerId = ProviderMessageId(ProviderKind.MMS, candidate.first),
                    conversationId = org.aurorasms.core.model.ConversationId(candidate.second),
                    incomingDisposition = IncomingDeliveryDisposition.COMPLETED_REPLAY,
                ),
            )
        } else {
            ExistingIncomingMms.Incomplete(uri)
        }
    }

    private fun deleteIncompleteIncoming(uri: Uri, message: DecodedIncomingMmsRecord): Boolean {
        val subscriptionId = message.subscriptionId.value
        val selection =
            "${Telephony.Mms.CREATOR} = ? AND ${Telephony.Mms.TRANSACTION_ID} = ? AND " +
                "${Telephony.Mms.SUBSCRIPTION_ID} = ? AND ${Telephony.Mms.DATE_SENT} = ? AND " +
                "${Telephony.Mms.MESSAGE_BOX} = ?"
        val arguments = arrayOf(
            appContext.packageName,
            message.notificationTransactionId,
            subscriptionId.toString(),
            (message.sentTimestampMillis / MILLIS_PER_SECOND_VALUE).toString(),
            Telephony.Mms.MESSAGE_BOX_INBOX.toString(),
        )
        return resolver.delete(uri, selection, arguments) == 1
    }

    private fun persistIncomingParts(
        partUri: Uri,
        parts: List<DecodedIncomingMmsPart>,
    ): Int {
        parts.forEachIndexed { index, part ->
            val fallback = "part-$index"
            val location = sequenceOf(part.contentLocation, part.name, part.filename)
                .filterNotNull()
                .firstOrNull()
                ?: fallback
            val values = incomingPartValues(part, location, fallback)
            val inserted = resolver.insert(partUri, values)
                ?: throw IOException("MMS incoming part insert failed")
            if (ContentUris.parseId(inserted) <= 0L) {
                throw IOException("MMS incoming part ID unavailable")
            }
            if (part.decodedText == null && part.size > 0) {
                resolver.openOutputStream(inserted, "w")?.use { output ->
                    output.write(part.copyBytes())
                    output.flush()
                } ?: throw IOException("MMS incoming part stream unavailable")
            }
        }
        return parts.size
    }

    private fun incomingPartValues(
        part: DecodedIncomingMmsPart,
        location: String,
        fallbackContentId: String,
    ): ContentValues = ContentValues().apply {
        put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
        put(MMS_PART_CONTENT_ID, part.contentId ?: "<$fallbackContentId>")
        put(Telephony.Mms.Part.CONTENT_LOCATION, location)
        put(Telephony.Mms.Part.NAME, part.name ?: location)
        put(Telephony.Mms.Part.FILENAME, part.filename ?: location)
        put(
            MMS_PART_CONTENT_DISPOSITION,
            part.contentDisposition ?: if (part.isTextOnly()) MMS_INLINE_DISPOSITION else MMS_ATTACHMENT_DISPOSITION,
        )
        part.charsetMibEnum?.let { put(Telephony.Mms.Part.CHARSET, it) }
        part.decodedText?.let { put(Telephony.Mms.Part.TEXT, it) }
    }

    private fun persistIncomingAddresses(
        messageId: Long,
        message: DecodedIncomingMmsRecord,
    ): Int {
        val rows = buildList {
            add(message.sender to MMS_FROM_ADDRESS_TYPE)
            message.to.distinctBy(ParticipantAddress::value).forEach { add(it to MMS_TO_ADDRESS_TYPE) }
            message.cc.distinctBy(ParticipantAddress::value).forEach { add(it to MMS_CC_ADDRESS_TYPE) }
        }.distinctBy { (address, type) -> address.value to type }
        val addressUri = "content://mms/$messageId/addr".toUri()
        rows.forEach { (address, type) ->
            val inserted = resolver.insert(
                addressUri,
                ContentValues().apply {
                    put(MMS_ADDRESS, address.value)
                    put(MMS_ADDRESS_CHARSET, MMS_UTF_8_CHARSET)
                    put(MMS_ADDRESS_TYPE, type)
                },
            ) ?: throw IOException("MMS incoming address insert failed")
            if (ContentUris.parseId(inserted) <= 0L) {
                throw IOException("MMS incoming address ID unavailable")
            }
        }
        return rows.size
    }

    private fun incomingMmsRowComplete(
        uri: Uri,
        message: DecodedIncomingMmsRecord,
        threadId: Long,
        partCount: Int,
        addressCount: Int,
    ): Boolean {
        val messageId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return false
        val rowMatches = resolver.query(
            uri,
            arrayOf(
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.CREATOR,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.TRANSACTION_ID,
                Telephony.Mms.SUBSCRIPTION_ID,
                Telephony.Mms.DATE_SENT,
                Telephony.Mms.MESSAGE_TYPE,
                Telephony.Mms.MESSAGE_ID,
                Telephony.Mms.MESSAGE_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getLong(0) == threadId &&
                cursor.getString(1) == appContext.packageName &&
                cursor.getInt(2) == Telephony.Mms.MESSAGE_BOX_INBOX &&
                cursor.getString(3) == message.notificationTransactionId &&
                cursor.getInt(4) == message.subscriptionId.value &&
                cursor.getLong(5) == message.sentTimestampMillis / MILLIS_PER_SECOND_VALUE &&
                cursor.getInt(6) == MMS_RETRIEVE_CONF_TYPE &&
                cursor.getString(7) == message.messageId &&
                cursor.getLong(8) == message.parts.sumOf { it.size.toLong() }
        } == true
        if (!rowMatches) return false
        val actualPartCount = resolver.query(
            "content://mms/$messageId/part".toUri(),
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { it.count } ?: return false
        val actualAddressCount = resolver.query(
            "content://mms/$messageId/addr".toUri(),
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { it.count } ?: return false
        return actualPartCount == partCount && actualAddressCount == addressCount
    }

    override suspend fun insertOutgoingVoiceMemo(
        message: OutgoingVoiceMemoProviderRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = insertMutex.withLock {
        withWriteAccess("insert outgoing voice memo") {
            insertOutgoingVoiceMemoSerialized(message)
        }
    }

    override suspend fun insertOutgoing(
        message: OutgoingMmsProviderRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = insertMutex.withLock {
        withWriteAccess("insert outgoing MMS") {
            insertOutgoingSerialized(message)
        }
    }

    override suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: org.aurorasms.core.model.ConversationId,
        status: OutgoingMmsProviderStatus,
    ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> =
        withWriteAccess("update outgoing MMS status") {
            if (id.kind != ProviderKind.MMS || conversationId.value <= 0L) {
                return@withWriteAccess ProviderAccessResult.InvalidInput("provider message identity")
            }
            val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id.value)
            val target = status.providerMessageBox()
            val allowedCurrent = status.allowedCurrentBoxes()
            val placeholders = allowedCurrent.joinToString(",") { "?" }
            val arguments = buildList {
                add(appContext.packageName)
                add(conversationId.value.toString())
                allowedCurrent.forEach { add(it.toString()) }
            }.toTypedArray()
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, target) },
                "${Telephony.Mms.CREATOR} = ? AND ${Telephony.Mms.THREAD_ID} = ? AND " +
                    "${Telephony.Mms.MESSAGE_BOX} IN ($placeholders)",
                arguments,
            )
            when {
                updated == 1 -> ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.APPLIED)
                updated != 0 -> ProviderAccessResult.Unavailable("update outgoing MMS status")
                else -> when (readOutgoingOwnership(uri)) {
                    OutgoingMmsOwnershipRow.Absent ->
                        ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.ROW_ABSENT)
                    is OutgoingMmsOwnershipRow.Found ->
                        ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT)
                    OutgoingMmsOwnershipRow.Unavailable ->
                        ProviderAccessResult.Unavailable("read outgoing MMS ownership")
                }
            }
        }

    override suspend fun rollbackOutgoingPreparation(
        operationId: org.aurorasms.core.model.MessageId,
        conversationId: org.aurorasms.core.model.ConversationId,
        transactionId: String,
    ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = insertMutex.withLock {
        withWriteAccess("rollback outgoing MMS preparation") {
            if (
                operationId.kind != ProviderKind.PENDING_OPERATION ||
                operationId.value <= 0L ||
                conversationId.value <= 0L ||
                !transactionId.matches(OUTGOING_TRANSACTION_ID)
            ) {
                return@withWriteAccess ProviderAccessResult.InvalidInput(
                    "outgoing MMS preparation identity",
                )
            }
            resolver.delete(
                "content://mms/${dummyMmsPartOwnerId(operationId.value)}/part".toUri(),
                null,
                null,
            )
            val selection =
                "${Telephony.Mms.CREATOR} = ? AND ${Telephony.Mms.THREAD_ID} = ? AND " +
                    "${Telephony.Mms.TRANSACTION_ID} = ?"
            val arguments = arrayOf(
                appContext.packageName,
                conversationId.value.toString(),
                transactionId,
            )
            val candidates = resolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(BaseColumns._ID, Telephony.Mms.MESSAGE_BOX),
                selection,
                arguments,
                null,
            )?.use { cursor ->
                buildList<Pair<Long, Int>> {
                    while (size < 2 && cursor.moveToNext()) {
                        add(cursor.getLong(0) to cursor.getInt(1))
                    }
                    if (cursor.moveToNext()) add(-1L to -1)
                }
            } ?: return@withWriteAccess ProviderAccessResult.Unavailable(
                "read outgoing MMS preparation",
            )
            if (candidates.isEmpty()) {
                return@withWriteAccess ProviderAccessResult.Success(
                    OutgoingMmsStatusUpdateOutcome.ROW_ABSENT,
                )
            }
            val candidate = candidates.singleOrNull()
                ?: return@withWriteAccess ProviderAccessResult.Success(
                    OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
                )
            if (candidate.first <= 0L || candidate.second != Telephony.Mms.MESSAGE_BOX_FAILED) {
                return@withWriteAccess ProviderAccessResult.Success(
                    OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
                )
            }
            val deleted = resolver.delete(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, candidate.first),
                "$selection AND ${Telephony.Mms.MESSAGE_BOX} = ?",
                arguments + Telephony.Mms.MESSAGE_BOX_FAILED.toString(),
            )
            when (deleted) {
                1 -> ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.APPLIED)
                0 -> ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT)
                else -> ProviderAccessResult.Unavailable("delete outgoing MMS preparation")
            }
        }
    }

    private fun insertOutgoingVoiceMemoSerialized(
        message: OutgoingVoiceMemoProviderRecord,
    ): ProviderAccessResult<ProviderStoredMessage> {
        val dummyId = dummyMmsPartOwnerId(message.operationId.value)
        val dummyPartUri = "content://mms/$dummyId/part".toUri()
        var messageUri: Uri? = null
        var rowComplete = false
        return try {
            val partCount = persistVoiceMemoParts(dummyPartUri, message)
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, message.providerThreadId.value)
                put(Telephony.Mms.DATE, message.timestampMillis / MILLIS_PER_SECOND_VALUE)
                put(Telephony.Mms.DATE_SENT, 0L)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
                put(Telephony.Mms.MESSAGE_TYPE, MMS_SEND_REQUEST_TYPE)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                put(Telephony.Mms.CONTENT_TYPE, MMS_MULTIPART_RELATED)
                put(Telephony.Mms.TRANSACTION_ID, message.transactionId)
                put(Telephony.Mms.MESSAGE_CLASS, MMS_MESSAGE_CLASS_PERSONAL)
                put(Telephony.Mms.PRIORITY, MMS_PRIORITY_NORMAL)
                put(Telephony.Mms.DELIVERY_REPORT, MMS_VALUE_NO)
                put(Telephony.Mms.READ_REPORT, MMS_VALUE_NO)
                put(Telephony.Mms.MESSAGE_SIZE, message.encodedSize)
                put(Telephony.Mms.TEXT_ONLY, 0)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.SUBSCRIPTION_ID, message.subscriptionId.value)
                put(Telephony.Mms.CREATOR, appContext.packageName)
                message.subject?.let {
                    put(Telephony.Mms.SUBJECT, it)
                    put(Telephony.Mms.SUBJECT_CHARSET, MMS_UTF_8_CHARSET)
                }
            }
            messageUri = resolver.insert(Telephony.Mms.CONTENT_URI, values)
                ?: return ProviderAccessResult.Unavailable("insert outgoing MMS row")
            val messageId = ContentUris.parseId(messageUri).takeIf { it > 0L }
                ?: return ProviderAccessResult.Unavailable("read inserted MMS ID")
            val moved = resolver.update(
                dummyPartUri,
                ContentValues().apply { put(MMS_PART_MESSAGE_ID, messageId) },
                null,
                null,
            )
            if (moved != partCount) {
                return ProviderAccessResult.Unavailable("attach outgoing MMS parts")
            }
            val addressUri = "content://mms/$messageId/addr".toUri()
            for (recipient in message.recipients.addresses) {
                val inserted = resolver.insert(
                    addressUri,
                    ContentValues().apply {
                        put(MMS_ADDRESS, recipient.value)
                        put(MMS_ADDRESS_CHARSET, MMS_UTF_8_CHARSET)
                        put(MMS_ADDRESS_TYPE, MMS_TO_ADDRESS_TYPE)
                    },
                ) ?: return ProviderAccessResult.Unavailable("insert outgoing MMS address")
                if (ContentUris.parseId(inserted) <= 0L) {
                    return ProviderAccessResult.Unavailable("read outgoing MMS address ID")
                }
            }
            if (!outgoingMmsRowComplete(messageUri, message.providerThreadId, message.recipients.size, partCount)) {
                return ProviderAccessResult.Unavailable("verify outgoing MMS row")
            }
            rowComplete = true
            ProviderAccessResult.Success(
                ProviderStoredMessage(
                    providerId = ProviderMessageId(ProviderKind.MMS, messageId),
                    conversationId = message.providerThreadId.asConversationId(),
                ),
            )
        } catch (_: IOException) {
            ProviderAccessResult.Unavailable("write outgoing MMS part")
        } finally {
            runCatching { resolver.delete(dummyPartUri, null, null) }
            messageUri?.takeUnless { rowComplete }?.let { incomplete ->
                runCatching { resolver.delete(incomplete, null, null) }
            }
        }
    }

    private fun insertOutgoingSerialized(
        message: OutgoingMmsProviderRecord,
    ): ProviderAccessResult<ProviderStoredMessage> {
        val dummyId = dummyMmsPartOwnerId(message.operationId.value)
        val dummyPartUri = "content://mms/$dummyId/part".toUri()
        var messageUri: Uri? = null
        var rowComplete = false
        return try {
            val partCount = persistGeneralMmsParts(dummyPartUri, message)
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, message.providerThreadId.value)
                put(Telephony.Mms.DATE, message.timestampMillis / MILLIS_PER_SECOND_VALUE)
                put(Telephony.Mms.DATE_SENT, 0L)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
                put(Telephony.Mms.MESSAGE_TYPE, MMS_SEND_REQUEST_TYPE)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                put(Telephony.Mms.CONTENT_TYPE, MMS_MULTIPART_RELATED)
                put(Telephony.Mms.TRANSACTION_ID, message.transactionId)
                put(Telephony.Mms.MESSAGE_CLASS, MMS_MESSAGE_CLASS_PERSONAL)
                put(Telephony.Mms.PRIORITY, MMS_PRIORITY_NORMAL)
                put(Telephony.Mms.DELIVERY_REPORT, MMS_VALUE_NO)
                put(Telephony.Mms.READ_REPORT, MMS_VALUE_NO)
                put(Telephony.Mms.MESSAGE_SIZE, message.encodedSize)
                put(Telephony.Mms.TEXT_ONLY, if (message.payload.attachments.isEmpty()) 1 else 0)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.SUBSCRIPTION_ID, message.subscriptionId.value)
                put(Telephony.Mms.CREATOR, appContext.packageName)
                message.payload.subject?.let {
                    put(Telephony.Mms.SUBJECT, it)
                    put(Telephony.Mms.SUBJECT_CHARSET, MMS_UTF_8_CHARSET)
                }
            }
            messageUri = resolver.insert(Telephony.Mms.CONTENT_URI, values)
                ?: return ProviderAccessResult.Unavailable("insert outgoing MMS row")
            val messageId = ContentUris.parseId(messageUri).takeIf { it > 0L }
                ?: return ProviderAccessResult.Unavailable("read inserted MMS ID")
            val moved = resolver.update(
                dummyPartUri,
                ContentValues().apply { put(MMS_PART_MESSAGE_ID, messageId) },
                null,
                null,
            )
            if (moved != partCount) {
                return ProviderAccessResult.Unavailable("attach outgoing MMS parts")
            }
            val addressUri = "content://mms/$messageId/addr".toUri()
            for (recipient in message.recipients.addresses) {
                val inserted = resolver.insert(
                    addressUri,
                    ContentValues().apply {
                        put(MMS_ADDRESS, recipient.value)
                        put(MMS_ADDRESS_CHARSET, MMS_UTF_8_CHARSET)
                        put(MMS_ADDRESS_TYPE, MMS_TO_ADDRESS_TYPE)
                    },
                ) ?: return ProviderAccessResult.Unavailable("insert outgoing MMS address")
                if (ContentUris.parseId(inserted) <= 0L) {
                    return ProviderAccessResult.Unavailable("read outgoing MMS address ID")
                }
            }
            if (
                !outgoingMmsRowComplete(
                    messageUri,
                    message.providerThreadId,
                    message.recipients.size,
                    partCount,
                )
            ) {
                return ProviderAccessResult.Unavailable("verify outgoing MMS row")
            }
            rowComplete = true
            ProviderAccessResult.Success(
                ProviderStoredMessage(
                    providerId = ProviderMessageId(ProviderKind.MMS, messageId),
                    conversationId = message.providerThreadId.asConversationId(),
                ),
            )
        } catch (_: IOException) {
            ProviderAccessResult.Unavailable("write outgoing MMS part")
        } finally {
            runCatching { resolver.delete(dummyPartUri, null, null) }
            messageUri?.takeUnless { rowComplete }?.let { incomplete ->
                runCatching { resolver.delete(incomplete, null, null) }
            }
        }
    }

    private fun persistVoiceMemoParts(
        partUri: Uri,
        message: OutgoingVoiceMemoProviderRecord,
    ): Int {
        var count = 0
        insertTextPart(
            partUri = partUri,
            contentType = MMS_SMIL_CONTENT_TYPE,
            contentId = "<smil>",
            location = MMS_SMIL_LOCATION,
            text = voiceMemoSmil(message.memo.durationMillis, message.text?.isNotBlank() == true),
        )
        count += 1
        message.text?.takeIf(String::isNotBlank)?.let { text ->
            insertTextPart(
                partUri = partUri,
                contentType = MMS_TEXT_CONTENT_TYPE,
                contentId = "<text0>",
                location = MMS_TEXT_LOCATION,
                text = text,
                charset = MMS_UTF_8_CHARSET,
            )
            count += 1
        }
        val audioUri = resolver.insert(
            partUri,
            partValues(
                contentType = MMS_AUDIO_CONTENT_TYPE,
                contentId = "<voice0>",
                location = MMS_AUDIO_LOCATION,
                disposition = MMS_ATTACHMENT_DISPOSITION,
            ),
        ) ?: throw IOException("MMS audio part insert failed")
        resolver.openOutputStream(audioUri, "w")?.use { output ->
            output.write(message.memo.copyBytes())
            output.flush()
        } ?: throw IOException("MMS audio part stream unavailable")
        return count + 1
    }

    private fun persistGeneralMmsParts(
        partUri: Uri,
        message: OutgoingMmsProviderRecord,
    ): Int {
        var count = 0
        insertTextPart(
            partUri = partUri,
            contentType = GENERAL_MMS_SMIL_CONTENT_TYPE,
            contentId = "<$GENERAL_MMS_SMIL_CONTENT_ID>",
            location = GENERAL_MMS_SMIL_LOCATION,
            text = generalMmsSmil(message.payload),
        )
        count += 1
        message.payload.text?.let { text ->
            insertTextPart(
                partUri = partUri,
                contentType = GENERAL_MMS_TEXT_CONTENT_TYPE,
                contentId = "<$GENERAL_MMS_TEXT_CONTENT_ID>",
                location = GENERAL_MMS_TEXT_LOCATION,
                text = text,
                charset = MMS_UTF_8_CHARSET,
            )
            count += 1
        }
        message.payload.attachments.forEachIndexed { index, attachment ->
            val location = generalMmsAttachmentLocation(index, attachment.contentType)
            val mediaUri = resolver.insert(
                partUri,
                partValues(
                    contentType = attachment.contentType,
                    contentId = "<media$index>",
                    location = location,
                    disposition = GENERAL_MMS_ATTACHMENT_DISPOSITION,
                ),
            ) ?: throw IOException("MMS media part insert failed")
            resolver.openOutputStream(mediaUri, "w")?.use { output ->
                output.write(attachment.copyBytes())
                output.flush()
            } ?: throw IOException("MMS media part stream unavailable")
            count += 1
        }
        return count
    }

    private fun insertTextPart(
        partUri: Uri,
        contentType: String,
        contentId: String,
        location: String,
        text: String,
        charset: Int? = null,
    ) {
        val values = partValues(
            contentType = contentType,
            contentId = contentId,
            location = location,
            disposition = MMS_INLINE_DISPOSITION,
        ).apply {
            put(Telephony.Mms.Part.TEXT, text)
            charset?.let { put(Telephony.Mms.Part.CHARSET, it) }
        }
        if (resolver.insert(partUri, values) == null) throw IOException("MMS text part insert failed")
    }

    private fun partValues(
        contentType: String,
        contentId: String,
        location: String,
        disposition: String,
    ): ContentValues = ContentValues().apply {
        put(Telephony.Mms.Part.CONTENT_TYPE, contentType)
        put(MMS_PART_CONTENT_ID, contentId)
        put(Telephony.Mms.Part.CONTENT_LOCATION, location)
        put(Telephony.Mms.Part.NAME, location)
        put(Telephony.Mms.Part.FILENAME, location)
        put(MMS_PART_CONTENT_DISPOSITION, disposition)
    }

    private fun outgoingMmsRowComplete(
        uri: Uri,
        providerThreadId: ProviderThreadId,
        recipientCount: Int,
        expectedPartCount: Int,
    ): Boolean {
        val messageId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return false
        val rowMatches = resolver.query(
            uri,
            arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.CREATOR, Telephony.Mms.MESSAGE_BOX),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getLong(0) == providerThreadId.value &&
                cursor.getString(1) == appContext.packageName &&
                cursor.getInt(2) == Telephony.Mms.MESSAGE_BOX_FAILED
        } == true
        if (!rowMatches) return false
        val partCount = resolver.query(
            "content://mms/$messageId/part".toUri(),
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { it.count } ?: return false
        val addressCount = resolver.query(
            "content://mms/$messageId/addr".toUri(),
            arrayOf(BaseColumns._ID),
            "$MMS_ADDRESS_TYPE = ?",
            arrayOf(MMS_TO_ADDRESS_TYPE.toString()),
            null,
        )?.use { it.count } ?: return false
        return partCount == expectedPartCount && addressCount == recipientCount
    }

    private fun readOutgoingOwnership(uri: Uri): OutgoingMmsOwnershipRow = try {
        resolver.query(
            uri,
            arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.CREATOR, Telephony.Mms.MESSAGE_BOX),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                OutgoingMmsOwnershipRow.Absent
            } else {
                OutgoingMmsOwnershipRow.Found(cursor.getLong(0), cursor.getString(1), cursor.getInt(2))
            }
        } ?: OutgoingMmsOwnershipRow.Unavailable
    } catch (_: RuntimeException) {
        OutgoingMmsOwnershipRow.Unavailable
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

    private suspend fun <T> withWriteAccess(
        operation: String,
        block: () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) return@withContext ProviderAccessResult.RoleRequired
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

}

private sealed interface ExistingIncomingMms {
    data object None : ExistingIncomingMms
    data object Ambiguous : ExistingIncomingMms
    data object Unavailable : ExistingIncomingMms
    data class Incomplete(val uri: Uri) : ExistingIncomingMms
    data class Complete(val stored: ProviderStoredMessage) : ExistingIncomingMms
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

private sealed interface OutgoingMmsOwnershipRow {
    data object Absent : OutgoingMmsOwnershipRow
    data object Unavailable : OutgoingMmsOwnershipRow
    data class Found(val threadId: Long, val creator: String?, val messageBox: Int) : OutgoingMmsOwnershipRow
}

internal data class RawMmsAddress(
    val address: String?,
    val type: Int,
) {
    override fun toString(): String = "RawMmsAddress(hasAddress=${address != null}, type=$type, REDACTED)"
}

internal data class MmsAddressMetadata(
    val sender: ParticipantAddress?,
    val participants: List<ParticipantAddress>,
    val truncated: Boolean,
) {
    override fun toString(): String =
        "MmsAddressMetadata(hasSender=${sender != null}, participantCount=${participants.size}, truncated=$truncated)"

    companion object {
        val INCOMPLETE = MmsAddressMetadata(
            sender = null,
            participants = emptyList(),
            truncated = true,
        )
    }
}

internal fun projectAddressMetadata(rawRows: List<RawMmsAddress>): MmsAddressMetadata {
    val valid = ArrayList<Pair<RawMmsAddress, ParticipantAddress>>(rawRows.size)
    var identityIncomplete = rawRows.isEmpty()
    for (row in rawRows) {
        val rawAddress = row.address
        if (rawAddress == null) {
            identityIncomplete = true
            continue
        }
        val normalized = rawAddress.trim()
        if (normalized.equals(INSERT_ADDRESS_TOKEN, ignoreCase = true)) continue
        if (normalized.isEmpty()) {
            identityIncomplete = true
            continue
        }
        val address = try {
            ParticipantAddress(normalized)
        } catch (_: IllegalArgumentException) {
            identityIncomplete = true
            continue
        }
        valid += row to address
    }

    val participants = LinkedHashMap<String, ParticipantAddress>()
    var participantOverflow = false
    valid.forEach { (_, address) ->
        if (participants.size < MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
            participants.putIfAbsent(address.value, address)
        } else if (address.value !in participants) {
            participantOverflow = true
        }
    }
    return MmsAddressMetadata(
        sender = valid.firstOrNull { (row) -> row.type == MMS_FROM_ADDRESS_TYPE }?.second,
        participants = participants.values.toList(),
        truncated = identityIncomplete ||
            participantOverflow ||
            participants.isEmpty() ||
            rawRows.size > MAX_INSPECTED_MMS_ADDRESS_ROWS,
    )
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

private fun OutgoingMmsProviderStatus.providerMessageBox(): Int = when (this) {
    OutgoingMmsProviderStatus.FAILED -> Telephony.Mms.MESSAGE_BOX_FAILED
    OutgoingMmsProviderStatus.OUTBOX -> Telephony.Mms.MESSAGE_BOX_OUTBOX
    OutgoingMmsProviderStatus.SENT -> Telephony.Mms.MESSAGE_BOX_SENT
}

private fun OutgoingMmsProviderStatus.allowedCurrentBoxes(): IntArray = when (this) {
    OutgoingMmsProviderStatus.FAILED ->
        intArrayOf(Telephony.Mms.MESSAGE_BOX_FAILED, Telephony.Mms.MESSAGE_BOX_OUTBOX)
    OutgoingMmsProviderStatus.OUTBOX ->
        intArrayOf(Telephony.Mms.MESSAGE_BOX_FAILED, Telephony.Mms.MESSAGE_BOX_OUTBOX)
    OutgoingMmsProviderStatus.SENT ->
        intArrayOf(Telephony.Mms.MESSAGE_BOX_OUTBOX, Telephony.Mms.MESSAGE_BOX_SENT)
}

internal fun dummyMmsPartOwnerId(operationId: Long): Long {
    require(operationId > 0L)
    return Long.MAX_VALUE - operationId
}

private fun DecodedIncomingMmsPart.isTextOnly(): Boolean =
    contentType == MMS_TEXT_CONTENT_TYPE || contentType == MMS_SMIL_CONTENT_TYPE

private fun DecodedIncomingMmsRecord.expectedAddressCount(): Int =
    buildSet {
        add(sender.value to MMS_FROM_ADDRESS_TYPE)
        to.forEach { add(it.value to MMS_TO_ADDRESS_TYPE) }
        cc.forEach { add(it.value to MMS_CC_ADDRESS_TYPE) }
    }.size

internal fun voiceMemoSmil(durationMillis: Long, hasText: Boolean): String = buildString {
    append("<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/>")
    if (hasText) append("<region id=\"Text\" left=\"0\" top=\"0\" width=\"320px\" height=\"480px\"/>")
    append("</layout></head><body><par dur=\"")
    append(durationMillis)
    append("ms\">")
    if (hasText) append("<text src=\"").append(MMS_TEXT_LOCATION).append("\" region=\"Text\"/>")
    append("<audio src=\"").append(MMS_AUDIO_LOCATION).append("\"/>")
    append("</par></body></smil>")
}

private const val MILLIS_PER_SECOND_VALUE = 1_000L
private const val MAX_INSPECTED_MMS_ADDRESS_ROWS = MmsProviderMessage.MAX_MMS_PARTICIPANTS
private const val MAX_INSPECTED_MMS_PART_ROWS = 100
private const val MMS_FROM_ADDRESS_TYPE = 137
private const val INSERT_ADDRESS_TOKEN = "insert-address-token"
private const val MMS_ADDRESS_PATH = "addr"
private const val MMS_PART_PATH = "part"
private const val TEXT_PLAIN_MIME_TYPE = "text/plain"
private const val SMIL_MIME_TYPE = "application/smil"
private const val DEFAULT_BINARY_MIME_TYPE = "application/octet-stream"
private const val MMS_SEND_REQUEST_TYPE = 128
private const val MMS_RETRIEVE_CONF_TYPE = 132
private const val MMS_VERSION_1_2 = 0x12
private const val MMS_PRIORITY_NORMAL = 129
private const val MMS_VALUE_NO = 129
private const val MMS_TO_ADDRESS_TYPE = 151
private const val MMS_CC_ADDRESS_TYPE = 130
private const val MMS_RETRIEVE_STATUS_OK = 128
private const val MMS_UTF_8_CHARSET = 106
private const val MMS_MULTIPART_RELATED = "application/vnd.wap.multipart.related"
private const val MMS_MESSAGE_CLASS_PERSONAL = "personal"
private const val MMS_SMIL_CONTENT_TYPE = "application/smil"
private const val MMS_TEXT_CONTENT_TYPE = "text/plain"
private const val MMS_AUDIO_CONTENT_TYPE = "audio/mp4"
private const val MMS_SMIL_LOCATION = "smil.xml"
private const val MMS_TEXT_LOCATION = "text_0.txt"
private const val MMS_AUDIO_LOCATION = "voice_0.m4a"
private const val MMS_INLINE_DISPOSITION = "inline"
private const val MMS_ATTACHMENT_DISPOSITION = "attachment"
private const val MMS_PART_MESSAGE_ID = "mid"
private const val MMS_PART_CONTENT_ID = "cid"
private const val MMS_PART_CONTENT_DISPOSITION = "cd"
private const val MMS_ADDRESS = "address"
private const val MMS_ADDRESS_CHARSET = "charset"
private const val MMS_ADDRESS_TYPE = "type"
private val OUTGOING_TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,64}")
