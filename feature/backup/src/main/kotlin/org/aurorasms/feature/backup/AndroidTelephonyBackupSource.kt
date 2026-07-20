// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AuroraBackupSourceOpenResult {
    /** The sequence is deliberately single-use because attachment streams are provider-backed. */
    data class Ready(val entries: Sequence<AuroraBackupEntry>) : AuroraBackupSourceOpenResult
    data object RoleRequired : AuroraBackupSourceOpenResult
    data object PermissionDenied : AuroraBackupSourceOpenResult
    data class Unavailable(val operation: String) : AuroraBackupSourceOpenResult
}

/**
 * A bounded, lossless Telephony reader for explicit backup export.
 *
 * Provider IDs are used only as transient cursors and attachment handles. They are never encoded.
 * Each provider is capped at the maximum ID observed before streaming begins, so rows arriving
 * during export are left for the next archive rather than extending this one without bound.
 */
class AndroidTelephonyBackupSource internal constructor(
    private val resolver: ContentResolver,
    private val uris: ProviderUris,
    private val roleHeld: () -> Boolean,
    private val readPermissionGranted: () -> Boolean,
) {
    constructor(context: Context) : this(
        resolver = context.applicationContext.contentResolver,
        uris = ProviderUris.platform(),
        roleHeld = {
            Telephony.Sms.getDefaultSmsPackage(context.applicationContext) ==
                context.applicationContext.packageName
        },
        readPermissionGranted = {
            context.applicationContext.checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        },
    )

    fun open(): AuroraBackupSourceOpenResult {
        if (!roleHeld()) return AuroraBackupSourceOpenResult.RoleRequired
        if (!readPermissionGranted()) return AuroraBackupSourceOpenResult.PermissionDenied
        val smsMaximum = maximumId(uris.sms) ?: return AuroraBackupSourceOpenResult.Unavailable("snapshot SMS")
        val mmsMaximum = maximumId(uris.mms) ?: return AuroraBackupSourceOpenResult.Unavailable("snapshot MMS")
        val consumed = AtomicBoolean(false)
        return AuroraBackupSourceOpenResult.Ready(
            sequence {
                if (!consumed.compareAndSet(false, true)) throw BackupProviderSourceException("single-use source")
                var archiveMessageId = 1L
                readSmsRows(smsMaximum).forEach { raw ->
                    yield(AuroraBackupMessageCodec.smsEntry(raw.toRecord(archiveMessageId)))
                    archiveMessageId += 1L
                }
                readMmsRows(mmsMaximum).forEach { raw ->
                    val addresses = readMmsAddresses(raw.providerId)
                    yield(
                        AuroraBackupMessageCodec.mmsEntry(
                            raw.toRecord(archiveMessageId, addresses),
                        ),
                    )
                    readMmsParts(raw.providerId).forEach { part ->
                        yield(
                            AuroraBackupMessageCodec.mmsPartEntry(
                                part.toRecord(archiveMessageId, resolver, uris),
                            ),
                        )
                    }
                    archiveMessageId += 1L
                }
            },
        )
    }

    private fun maximumId(uri: Uri): Long? = try {
        val args = queryArgs(
            selection = "$ID_COLUMN > 0",
            selectionArgs = null,
            sortOrder = "$ID_COLUMN DESC",
            limit = 1,
        )
        resolver.query(uri, arrayOf(ID_COLUMN), args, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } ?: 0L else 0L
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun readSmsRows(maximumId: Long): Sequence<RawSmsBackupRow> = sequence {
        var afterId = 0L
        while (afterId < maximumId) {
            val rows = queryRows(
                uri = uris.sms,
                projection = SMS_PROJECTION,
                selection = "$ID_COLUMN > ? AND $ID_COLUMN <= ?",
                selectionArgs = arrayOf(afterId.toString(), maximumId.toString()),
                sortOrder = "$ID_COLUMN ASC",
                limit = PAGE_SIZE,
                operation = "read SMS backup page",
                map = Cursor::toRawSmsBackupRow,
            )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                if (row.providerId <= afterId || row.providerId > maximumId) {
                    throw BackupProviderSourceException("SMS cursor progress")
                }
                yield(row)
                afterId = row.providerId
            }
            if (rows.size < PAGE_SIZE) break
        }
    }

    private fun readMmsRows(maximumId: Long): Sequence<RawMmsBackupRow> = sequence {
        var afterId = 0L
        while (afterId < maximumId) {
            val rows = queryRows(
                uri = uris.mms,
                projection = MMS_PROJECTION,
                selection = "$ID_COLUMN > ? AND $ID_COLUMN <= ?",
                selectionArgs = arrayOf(afterId.toString(), maximumId.toString()),
                sortOrder = "$ID_COLUMN ASC",
                limit = PAGE_SIZE,
                operation = "read MMS backup page",
                map = Cursor::toRawMmsBackupRow,
            )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                if (row.providerId <= afterId || row.providerId > maximumId) {
                    throw BackupProviderSourceException("MMS cursor progress")
                }
                yield(row)
                afterId = row.providerId
            }
            if (rows.size < PAGE_SIZE) break
        }
    }

    private fun readMmsAddresses(providerId: Long): List<AuroraBackupMmsAddress> =
        queryRows(
            uri = uris.mmsAddress(providerId),
            projection = MMS_ADDRESS_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = "$ID_COLUMN ASC",
            limit = MAXIMUM_MMS_ADDRESSES + 1,
            operation = "read MMS backup addresses",
            map = Cursor::toMmsAddress,
        ).also {
            if (it.size > MAXIMUM_MMS_ADDRESSES) throw BackupProviderSourceException("MMS address limit")
        }

    private fun readMmsParts(providerId: Long): List<RawMmsPartBackupRow> =
        queryRows(
            uri = uris.mmsPart(providerId),
            projection = MMS_PART_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = "$ID_COLUMN ASC",
            limit = MAXIMUM_MMS_PARTS + 1,
            operation = "read MMS backup parts",
            map = Cursor::toRawMmsPartBackupRow,
        ).also {
            if (it.size > MAXIMUM_MMS_PARTS) throw BackupProviderSourceException("MMS part limit")
        }

    private fun <T> queryRows(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int,
        operation: String,
        map: (Cursor) -> T,
    ): List<T> = try {
        val args = queryArgs(selection, selectionArgs, sortOrder, limit)
        resolver.query(uri, projection, args, null)?.use { cursor ->
            val rows = ArrayList<T>(minOf(limit, PAGE_SIZE))
            while (rows.size < limit && cursor.moveToNext()) rows += map(cursor)
            rows
        } ?: throw BackupProviderSourceException(operation)
    } catch (error: BackupProviderSourceException) {
        throw error
    } catch (error: RuntimeException) {
        throw BackupProviderSourceException(operation, error)
    }

    internal data class ProviderUris(
        val sms: Uri,
        val mms: Uri,
        val mmsPartBase: Uri,
    ) {
        fun mmsAddress(providerId: Long): Uri = mms.buildUpon()
            .appendPath(providerId.toString())
            .appendPath("addr")
            .build()

        fun mmsPart(providerId: Long): Uri = mms.buildUpon()
            .appendPath(providerId.toString())
            .appendPath("part")
            .build()

        fun exactMmsPart(providerPartId: Long): Uri = mmsPartBase.buildUpon()
            .appendPath(providerPartId.toString())
            .build()

        companion object {
            fun platform(): ProviderUris = ProviderUris(
                sms = Telephony.Sms.CONTENT_URI,
                mms = Telephony.Mms.CONTENT_URI,
                mmsPartBase = Uri.parse("content://mms/part"),
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAXIMUM_MMS_ADDRESSES = AuroraBackupMessageCodec.MAX_MMS_ADDRESSES
        const val MAXIMUM_MMS_PARTS = 1_000
        const val ID_COLUMN = BaseColumns._ID

        val SMS_PROJECTION = arrayOf(
            ID_COLUMN, "type", "address", "body", "date", "date_sent", "read", "seen", "locked",
            "status", "error_code", "protocol", "reply_path_present", "subject", "service_center", "sub_id",
        )
        val MMS_PROJECTION = arrayOf(
            ID_COLUMN, "msg_box", "date", "date_sent", "read", "seen", "locked", "sub_id", "m_type", "v",
            "pri", "st", "resp_st", "retr_st", "rr", "d_rpt", "rpt_a", "m_size", "exp", "d_tm", "sub",
            "sub_cs", "ct_t", "ct_l", "m_cls", "tr_id",
        )
        val MMS_ADDRESS_PROJECTION = arrayOf(ID_COLUMN, "type", "address", "charset")
        val MMS_PART_PROJECTION = arrayOf(
            ID_COLUMN, "seq", "ct", "chset", "name", "cd", "fn", "cid", "cl", "text", "_data",
        )
    }
}

private data class RawSmsBackupRow(
    val providerId: Long,
    val boxCode: Int,
    val address: String?,
    val body: String?,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val status: Int?,
    val errorCode: Int?,
    val protocol: Int?,
    val replyPathPresent: Int?,
    val subject: String?,
    val serviceCenter: String?,
    val subscriptionId: Int?,
) {
    fun toRecord(archiveMessageId: Long): AuroraBackupSmsRecord = AuroraBackupSmsRecord(
        archiveMessageId = archiveMessageId,
        box = boxCode.toBackupBox(),
        address = address,
        body = body,
        timestampMillis = timestampMillis.requireNonNegative("SMS date"),
        sentTimestampMillis = sentTimestampMillis?.requireNonNegative("SMS sent date"),
        read = read,
        seen = seen,
        locked = locked,
        status = status,
        errorCode = errorCode,
        protocol = protocol,
        replyPathPresent = replyPathPresent,
        subject = subject,
        serviceCenter = serviceCenter,
        subscriptionId = subscriptionId,
    )
}

private data class RawMmsBackupRow(
    val providerId: Long,
    val boxCode: Int,
    val timestampSeconds: Long,
    val sentTimestampSeconds: Long?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val subscriptionId: Int?,
    val messageType: Int?,
    val version: Int?,
    val priority: Int?,
    val status: Int?,
    val responseStatus: Int?,
    val retrieveStatus: Int?,
    val readReport: Int?,
    val deliveryReport: Int?,
    val reportAllowed: Int?,
    val messageSizeBytes: Long?,
    val expirySeconds: Long?,
    val deliveryTimeSeconds: Long?,
    val subject: String?,
    val subjectCharset: Int?,
    val contentType: String?,
    val contentLocation: String?,
    val messageClass: String?,
    val transactionId: String?,
) {
    fun toRecord(
        archiveMessageId: Long,
        addresses: List<AuroraBackupMmsAddress>,
    ): AuroraBackupMmsRecord = AuroraBackupMmsRecord(
        archiveMessageId = archiveMessageId,
        box = boxCode.toBackupBox(),
        timestampMillis = timestampSeconds.secondsToMillis("MMS date"),
        sentTimestampMillis = sentTimestampSeconds?.secondsToMillis("MMS sent date"),
        read = read,
        seen = seen,
        locked = locked,
        subscriptionId = subscriptionId,
        messageType = messageType,
        version = version,
        priority = priority,
        status = status,
        responseStatus = responseStatus,
        retrieveStatus = retrieveStatus,
        readReport = readReport,
        deliveryReport = deliveryReport,
        reportAllowed = reportAllowed,
        messageSizeBytes = messageSizeBytes?.requireNonNegative("MMS size"),
        expiryMillis = expirySeconds?.secondsToMillis("MMS expiry"),
        deliveryTimeMillis = deliveryTimeSeconds?.secondsToMillis("MMS delivery time"),
        subject = subject,
        subjectCharset = subjectCharset,
        contentType = contentType,
        contentLocation = contentLocation,
        messageClass = messageClass,
        transactionId = transactionId,
        addresses = addresses,
    )
}

private data class RawMmsPartBackupRow(
    val providerPartId: Long,
    val sequence: Int,
    val contentType: String?,
    val charset: Int?,
    val name: String?,
    val contentDisposition: String?,
    val filename: String?,
    val contentId: String?,
    val contentLocation: String?,
    val text: String?,
    val dataReference: String?,
) {
    fun toRecord(
        parentArchiveMessageId: Long,
        resolver: ContentResolver,
        uris: AndroidTelephonyBackupSource.ProviderUris,
    ): AuroraBackupMmsPartRecord = AuroraBackupMmsPartRecord(
        parentArchiveMessageId = parentArchiveMessageId,
        sequence = sequence,
        contentType = contentType ?: throw BackupProviderSourceException("MMS part content type"),
        charset = charset,
        name = name,
        contentDisposition = contentDisposition,
        filename = filename,
        contentId = contentId,
        contentLocation = contentLocation,
        payload = when {
            dataReference != null -> AuroraBackupMmsPartPayload.Binary { destination ->
                resolver.openInputStream(uris.exactMmsPart(providerPartId))?.use { source ->
                    source.copyTo(destination, ATTACHMENT_COPY_BUFFER_BYTES)
                } ?: throw IOException("MMS part stream unavailable")
            }
            text != null -> AuroraBackupMmsPartPayload.Text(text)
            else -> AuroraBackupMmsPartPayload.Empty
        },
    )
}

private fun Cursor.toRawSmsBackupRow(): RawSmsBackupRow = RawSmsBackupRow(
    providerId = requiredLong("_id"),
    boxCode = requiredInt("type"),
    address = nullableString("address"),
    body = nullableString("body"),
    timestampMillis = requiredLong("date"),
    sentTimestampMillis = nullableLong("date_sent"),
    read = requiredInt("read") != 0,
    seen = requiredInt("seen") != 0,
    locked = requiredInt("locked") != 0,
    status = nullableInt("status"),
    errorCode = nullableInt("error_code"),
    protocol = nullableInt("protocol"),
    replyPathPresent = nullableInt("reply_path_present"),
    subject = nullableString("subject"),
    serviceCenter = nullableString("service_center"),
    subscriptionId = nullableInt("sub_id"),
)

private fun Cursor.toRawMmsBackupRow(): RawMmsBackupRow = RawMmsBackupRow(
    providerId = requiredLong("_id"),
    boxCode = requiredInt("msg_box"),
    timestampSeconds = requiredLong("date"),
    sentTimestampSeconds = nullableLong("date_sent"),
    read = requiredInt("read") != 0,
    seen = requiredInt("seen") != 0,
    locked = requiredInt("locked") != 0,
    subscriptionId = nullableInt("sub_id"),
    messageType = nullableInt("m_type"),
    version = nullableInt("v"),
    priority = nullableInt("pri"),
    status = nullableInt("st"),
    responseStatus = nullableInt("resp_st"),
    retrieveStatus = nullableInt("retr_st"),
    readReport = nullableInt("rr"),
    deliveryReport = nullableInt("d_rpt"),
    reportAllowed = nullableInt("rpt_a"),
    messageSizeBytes = nullableLong("m_size"),
    expirySeconds = nullableLong("exp"),
    deliveryTimeSeconds = nullableLong("d_tm"),
    subject = nullableString("sub"),
    subjectCharset = nullableInt("sub_cs"),
    contentType = nullableString("ct_t"),
    contentLocation = nullableString("ct_l"),
    messageClass = nullableString("m_cls"),
    transactionId = nullableString("tr_id"),
)

private fun Cursor.toMmsAddress(): AuroraBackupMmsAddress = AuroraBackupMmsAddress(
    type = requiredInt("type"),
    address = nullableString("address") ?: throw BackupProviderSourceException("MMS address absent"),
    charset = nullableInt("charset"),
)

private fun Cursor.toRawMmsPartBackupRow(): RawMmsPartBackupRow = RawMmsPartBackupRow(
    providerPartId = requiredLong("_id"),
    sequence = requiredInt("seq"),
    contentType = nullableString("ct"),
    charset = nullableInt("chset"),
    name = nullableString("name"),
    contentDisposition = nullableString("cd"),
    filename = nullableString("fn"),
    contentId = nullableString("cid"),
    contentLocation = nullableString("cl"),
    text = nullableString("text"),
    dataReference = nullableString("_data"),
)

private fun Cursor.requiredLong(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.requiredInt(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun Cursor.nullableLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.nullableInt(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.nullableString(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Int.toBackupBox(): AuroraBackupMessageBox =
    AuroraBackupMessageBox.decode(this) ?: throw BackupProviderSourceException("historical message box")

private fun Long.requireNonNegative(field: String): Long =
    takeIf { it >= 0L } ?: throw BackupProviderSourceException("$field is negative")

private fun Long.secondsToMillis(field: String): Long {
    requireNonNegative(field)
    if (this > Long.MAX_VALUE / MILLIS_PER_SECOND) throw BackupProviderSourceException("$field is too large")
    return this * MILLIS_PER_SECOND
}

private fun queryArgs(
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String,
    limit: Int,
): Bundle = Bundle().apply {
    selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
    selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val ATTACHMENT_COPY_BUFFER_BYTES = 64 * 1_024

private class BackupProviderSourceException(
    operation: String,
    cause: Throwable? = null,
) : RuntimeException(operation, cause)
