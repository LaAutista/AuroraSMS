// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A historical provider box. Restore never treats one of these values as send authority. */
enum class AuroraBackupMessageBox(internal val code: Int) {
    INBOX(1),
    SENT(2),
    DRAFT(3),
    OUTBOX(4),
    FAILED(5),
    QUEUED(6),
    ;

    internal companion object {
        fun decode(code: Int): AuroraBackupMessageBox? = entries.singleOrNull { it.code == code }
    }
}

data class AuroraBackupSmsRecord(
    val archiveMessageId: Long,
    val box: AuroraBackupMessageBox,
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
    init {
        require(archiveMessageId > 0L)
        require(timestampMillis >= 0L)
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L)
        require(address.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_ADDRESS_BYTES))
        require(body.isNullOrBounded(AuroraBackupMessageCodec.MAX_TEXT_BYTES))
        require(subject.isNullOrBounded(AuroraBackupMessageCodec.MAX_TEXT_BYTES))
        require(serviceCenter.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_ADDRESS_BYTES))
    }

    override fun toString(): String =
        "AuroraBackupSmsRecord(archiveMessageId=$archiveMessageId, box=$box, " +
            "hasAddress=${address != null}, bodyBytes=${body?.utf8Size() ?: 0}, REDACTED)"
}

data class AuroraBackupMmsAddress(
    val type: Int,
    val address: String,
    val charset: Int?,
) {
    init {
        require(address.isSafeMetadata(AuroraBackupMessageCodec.MAX_ADDRESS_BYTES))
    }

    override fun toString(): String = "AuroraBackupMmsAddress(type=$type, REDACTED)"
}

data class AuroraBackupMmsRecord(
    val archiveMessageId: Long,
    val box: AuroraBackupMessageBox,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
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
    val expiryMillis: Long?,
    val deliveryTimeMillis: Long?,
    val subject: String?,
    val subjectCharset: Int?,
    val contentType: String?,
    val contentLocation: String?,
    val messageClass: String?,
    val transactionId: String?,
    val addresses: List<AuroraBackupMmsAddress>,
) {
    init {
        require(archiveMessageId > 0L)
        require(timestampMillis >= 0L)
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L)
        require(messageSizeBytes == null || messageSizeBytes >= 0L)
        require(expiryMillis == null || expiryMillis >= 0L)
        require(deliveryTimeMillis == null || deliveryTimeMillis >= 0L)
        require(subject.isNullOrBounded(AuroraBackupMessageCodec.MAX_TEXT_BYTES))
        require(contentType.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_MIME_BYTES))
        require(contentLocation.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(messageClass.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(transactionId.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(addresses.size <= AuroraBackupMessageCodec.MAX_MMS_ADDRESSES)
    }

    override fun toString(): String =
        "AuroraBackupMmsRecord(archiveMessageId=$archiveMessageId, box=$box, " +
            "addressCount=${addresses.size}, hasSubject=${subject != null}, REDACTED)"
}

sealed interface AuroraBackupMmsPartPayload {
    data object Empty : AuroraBackupMmsPartPayload

    class Text(val value: String) : AuroraBackupMmsPartPayload {
        init {
            require(value.isBounded(AuroraBackupMessageCodec.MAX_TEXT_BYTES))
        }

        override fun toString(): String = "AuroraBackupMmsPartPayload.Text(bytes=${value.utf8Size()})"
    }

    class Binary(
        private val writeBytes: (OutputStream) -> Unit,
    ) : AuroraBackupMmsPartPayload {
        internal fun writeTo(output: OutputStream) = writeBytes(output)

        override fun toString(): String = "AuroraBackupMmsPartPayload.Binary(REDACTED)"
    }
}

data class AuroraBackupMmsPartRecord(
    val parentArchiveMessageId: Long,
    val sequence: Int,
    val contentType: String,
    val charset: Int?,
    val name: String?,
    val contentDisposition: String?,
    val filename: String?,
    val contentId: String?,
    val contentLocation: String?,
    val payload: AuroraBackupMmsPartPayload,
) {
    init {
        require(parentArchiveMessageId > 0L)
        require(contentType.isMimeType())
        require(name.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentDisposition.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(filename.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentId.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentLocation.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
    }

    override fun toString(): String =
        "AuroraBackupMmsPartRecord(parentArchiveMessageId=$parentArchiveMessageId, " +
            "sequence=$sequence, contentType=$contentType, payload=$payload, REDACTED)"
}

data class AuroraBackupDecodedMmsPart(
    val parentArchiveMessageId: Long,
    val sequence: Int,
    val contentType: String,
    val charset: Int?,
    val name: String?,
    val contentDisposition: String?,
    val filename: String?,
    val contentId: String?,
    val contentLocation: String?,
) {
    init {
        require(parentArchiveMessageId > 0L)
        require(contentType.isMimeType())
        require(name.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentDisposition.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(filename.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentId.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
        require(contentLocation.isNullOrSafeMetadata(AuroraBackupMessageCodec.MAX_METADATA_BYTES))
    }

    override fun toString(): String =
        "AuroraBackupDecodedMmsPart(parentArchiveMessageId=$parentArchiveMessageId, " +
            "sequence=$sequence, contentType=$contentType, REDACTED)"
}

sealed interface AuroraBackupDecodedPartPayload {
    data object Empty : AuroraBackupDecodedPartPayload
    data class Text(val value: String) : AuroraBackupDecodedPartPayload {
        override fun toString(): String = "AuroraBackupDecodedPartPayload.Text(bytes=${value.utf8Size()})"
    }

    /** One-shot authenticated record stream, valid only during the visitor callback. */
    class Binary internal constructor(
        private val source: InputStream,
    ) : AuroraBackupDecodedPartPayload {
        private var completed = false

        @Throws(IOException::class)
        fun copyTo(destination: OutputStream): Long {
            check(!completed) { "Binary backup payload is one-shot" }
            val copied = source.copyTo(destination, 64 * 1_024)
            completed = true
            return copied
        }

        @Throws(IOException::class)
        fun discard(): Long = copyTo(DiscardingOutputStream)

        internal fun requireCompleted() {
            if (!completed) {
                throw VisitorFailureException(
                    IllegalStateException("Binary backup payload was neither copied nor discarded"),
                )
            }
        }

        override fun toString(): String = "AuroraBackupDecodedPartPayload.Binary(REDACTED)"
    }
}

interface AuroraBackupMessageVisitor {
    @Throws(IOException::class)
    fun onSms(record: AuroraBackupSmsRecord)

    @Throws(IOException::class)
    fun onMms(record: AuroraBackupMmsRecord)

    @Throws(IOException::class)
    fun onMmsPart(
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    )
}

/** Exact version-one message schemas carried inside [AuroraBackupArchive]. */
object AuroraBackupMessageCodec {
    const val SCHEMA_VERSION: Int = 1
    const val MAX_ADDRESS_BYTES: Int = 4 * 1_024
    const val MAX_TEXT_BYTES: Int = 512 * 1_024
    const val MAX_METADATA_BYTES: Int = 4 * 1_024
    const val MAX_MIME_BYTES: Int = 256
    const val MAX_MMS_ADDRESSES: Int = 100

    fun smsEntry(record: AuroraBackupSmsRecord): AuroraBackupEntry =
        AuroraBackupEntry(AuroraBackupEntryType.SMS) { output ->
            DataOutputStream(output).use { data -> writeSms(data, record) }
        }

    fun mmsEntry(record: AuroraBackupMmsRecord): AuroraBackupEntry =
        AuroraBackupEntry(AuroraBackupEntryType.MMS) { output ->
            DataOutputStream(output).use { data -> writeMms(data, record) }
        }

    fun mmsPartEntry(record: AuroraBackupMmsPartRecord): AuroraBackupEntry =
        AuroraBackupEntry(AuroraBackupEntryType.MMS_PART) { output ->
            DataOutputStream(output).use { data ->
                data.writeByte(SCHEMA_VERSION)
                data.writeLong(record.parentArchiveMessageId)
                data.writeInt(record.sequence)
                data.writeRequiredString(record.contentType, MAX_MIME_BYTES)
                data.writeNullableInt(record.charset)
                data.writeNullableString(record.name, MAX_METADATA_BYTES)
                data.writeNullableString(record.contentDisposition, MAX_METADATA_BYTES)
                data.writeNullableString(record.filename, MAX_METADATA_BYTES)
                data.writeNullableString(record.contentId, MAX_METADATA_BYTES)
                data.writeNullableString(record.contentLocation, MAX_METADATA_BYTES)
                when (val payload = record.payload) {
                    AuroraBackupMmsPartPayload.Empty -> data.writeByte(PAYLOAD_EMPTY)
                    is AuroraBackupMmsPartPayload.Text -> {
                        data.writeByte(PAYLOAD_TEXT)
                        data.writeRequiredString(payload.value, MAX_TEXT_BYTES)
                    }
                    is AuroraBackupMmsPartPayload.Binary -> {
                        data.writeByte(PAYLOAD_BINARY)
                        data.flush()
                        payload.writeTo(output)
                    }
                }
            }
        }

    fun decodeSms(bytes: ByteArray): AuroraBackupSmsRecord? = decode(bytes, ::readSms)

    fun decodeMms(bytes: ByteArray): AuroraBackupMmsRecord? = decode(bytes, ::readMms)

    internal fun inspect(
        type: AuroraBackupEntryType,
        source: InputStream,
    ): AuroraBackupSchemaIdentity? = consume(type, source, visitor = null)

    internal fun consume(
        type: AuroraBackupEntryType,
        source: InputStream,
        visitor: AuroraBackupMessageVisitor?,
    ): AuroraBackupSchemaIdentity? = try {
        when (type) {
            AuroraBackupEntryType.SMS -> decode(source, ::readSms)?.let { record ->
                invokeVisitor { visitor?.onSms(record) }
                AuroraBackupSchemaIdentity.Message(record.archiveMessageId, isMms = false)
            }
            AuroraBackupEntryType.MMS -> decode(source, ::readMms)?.let { record ->
                invokeVisitor { visitor?.onMms(record) }
                AuroraBackupSchemaIdentity.Message(record.archiveMessageId, isMms = true)
            }
            AuroraBackupEntryType.MMS_PART -> consumeMmsPart(source, visitor)?.let {
                AuroraBackupSchemaIdentity.MmsPart(it)
            }
        }
    } catch (error: VisitorFailureException) {
        throw error
    } catch (error: IOException) {
        throw VisitorFailureException(error)
    }

    /**
     * Validates one complete part record without retaining a binary attachment.
     * The source must expose only this record's authenticated content bytes.
     */
    fun validateMmsPart(source: InputStream): Boolean = runCatching {
        consumeMmsPart(source, visitor = null)
    }.getOrNull() != null

    private fun consumeMmsPart(
        source: InputStream,
        visitor: AuroraBackupMessageVisitor?,
    ): Long? = try {
        val data = DataInputStream(source)
        if (data.readUnsignedByte() != SCHEMA_VERSION) return null
        val parentArchiveMessageId = data.readLong()
        if (parentArchiveMessageId <= 0L) return null
        val sequence = data.readInt()
        val contentType = data.readRequiredString(MAX_MIME_BYTES)
        if (!contentType.isMimeType()) return null
        val charset = data.readNullableInt()
        val metadata = ArrayList<String?>(5)
        repeat(5) { metadata += data.readNullableString(MAX_METADATA_BYTES) }
        if (metadata.any { it != null && !it.isMetadataValue(MAX_METADATA_BYTES) }) return null
        val record = AuroraBackupDecodedMmsPart(
            parentArchiveMessageId = parentArchiveMessageId,
            sequence = sequence,
            contentType = contentType,
            charset = charset,
            name = metadata[0],
            contentDisposition = metadata[1],
            filename = metadata[2],
            contentId = metadata[3],
            contentLocation = metadata[4],
        )
        val complete = when (data.readUnsignedByte()) {
            PAYLOAD_EMPTY -> {
                if (data.read() != -1) return null
                invokePartVisitor(visitor, record, AuroraBackupDecodedPartPayload.Empty)
                true
            }
            PAYLOAD_TEXT -> {
                val value = data.readRequiredString(MAX_TEXT_BYTES)
                if (data.read() != -1) return null
                invokePartVisitor(visitor, record, AuroraBackupDecodedPartPayload.Text(value))
                true
            }
            PAYLOAD_BINARY -> {
                val payload = AuroraBackupDecodedPartPayload.Binary(data)
                if (visitor == null) {
                    payload.discard()
                } else {
                    invokePartVisitor(visitor, record, payload)
                }
                payload.requireCompleted()
                true
            }
            else -> false
        }
        parentArchiveMessageId.takeIf { complete }
    } catch (_: IOException) {
        null
    } catch (error: VisitorFailureException) {
        throw error
    } catch (_: RuntimeException) {
        null
    }

    private fun invokePartVisitor(
        visitor: AuroraBackupMessageVisitor?,
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    ) {
        invokeVisitor { visitor?.onMmsPart(record, payload) }
    }

    private inline fun invokeVisitor(action: () -> Unit) {
        try {
            action()
        } catch (error: VisitorFailureException) {
            throw error
        } catch (error: IOException) {
            throw VisitorFailureException(error)
        } catch (error: RuntimeException) {
            throw VisitorFailureException(error)
        }
    }

    private fun writeSms(output: DataOutputStream, record: AuroraBackupSmsRecord) {
        output.writeByte(SCHEMA_VERSION)
        output.writeLong(record.archiveMessageId)
        output.writeByte(record.box.code)
        output.writeNullableString(record.address, MAX_ADDRESS_BYTES)
        output.writeNullableString(record.body, MAX_TEXT_BYTES)
        output.writeLong(record.timestampMillis)
        output.writeNullableLong(record.sentTimestampMillis)
        output.writeBoolean(record.read)
        output.writeBoolean(record.seen)
        output.writeBoolean(record.locked)
        output.writeNullableInt(record.status)
        output.writeNullableInt(record.errorCode)
        output.writeNullableInt(record.protocol)
        output.writeNullableInt(record.replyPathPresent)
        output.writeNullableString(record.subject, MAX_TEXT_BYTES)
        output.writeNullableString(record.serviceCenter, MAX_ADDRESS_BYTES)
        output.writeNullableInt(record.subscriptionId)
    }

    private fun writeMms(output: DataOutputStream, record: AuroraBackupMmsRecord) {
        output.writeByte(SCHEMA_VERSION)
        output.writeLong(record.archiveMessageId)
        output.writeByte(record.box.code)
        output.writeLong(record.timestampMillis)
        output.writeNullableLong(record.sentTimestampMillis)
        output.writeBoolean(record.read)
        output.writeBoolean(record.seen)
        output.writeBoolean(record.locked)
        output.writeNullableInt(record.subscriptionId)
        output.writeNullableInt(record.messageType)
        output.writeNullableInt(record.version)
        output.writeNullableInt(record.priority)
        output.writeNullableInt(record.status)
        output.writeNullableInt(record.responseStatus)
        output.writeNullableInt(record.retrieveStatus)
        output.writeNullableInt(record.readReport)
        output.writeNullableInt(record.deliveryReport)
        output.writeNullableInt(record.reportAllowed)
        output.writeNullableLong(record.messageSizeBytes)
        output.writeNullableLong(record.expiryMillis)
        output.writeNullableLong(record.deliveryTimeMillis)
        output.writeNullableString(record.subject, MAX_TEXT_BYTES)
        output.writeNullableInt(record.subjectCharset)
        output.writeNullableString(record.contentType, MAX_MIME_BYTES)
        output.writeNullableString(record.contentLocation, MAX_METADATA_BYTES)
        output.writeNullableString(record.messageClass, MAX_METADATA_BYTES)
        output.writeNullableString(record.transactionId, MAX_METADATA_BYTES)
        output.writeInt(record.addresses.size)
        record.addresses.forEach { address ->
            output.writeInt(address.type)
            output.writeRequiredString(address.address, MAX_ADDRESS_BYTES)
            output.writeNullableInt(address.charset)
        }
    }

    private fun readSms(input: DataInputStream): AuroraBackupSmsRecord {
        requireSchema(input)
        return AuroraBackupSmsRecord(
            archiveMessageId = input.readLong(),
            box = readBox(input),
            address = input.readNullableString(MAX_ADDRESS_BYTES),
            body = input.readNullableString(MAX_TEXT_BYTES),
            timestampMillis = input.readLong(),
            sentTimestampMillis = input.readNullableLong(),
            read = input.readBoolean(),
            seen = input.readBoolean(),
            locked = input.readBoolean(),
            status = input.readNullableInt(),
            errorCode = input.readNullableInt(),
            protocol = input.readNullableInt(),
            replyPathPresent = input.readNullableInt(),
            subject = input.readNullableString(MAX_TEXT_BYTES),
            serviceCenter = input.readNullableString(MAX_ADDRESS_BYTES),
            subscriptionId = input.readNullableInt(),
        )
    }

    private fun readMms(input: DataInputStream): AuroraBackupMmsRecord {
        requireSchema(input)
        val archiveMessageId = input.readLong()
        val box = readBox(input)
        val timestampMillis = input.readLong()
        val sentTimestampMillis = input.readNullableLong()
        val read = input.readBoolean()
        val seen = input.readBoolean()
        val locked = input.readBoolean()
        val subscriptionId = input.readNullableInt()
        val messageType = input.readNullableInt()
        val version = input.readNullableInt()
        val priority = input.readNullableInt()
        val status = input.readNullableInt()
        val responseStatus = input.readNullableInt()
        val retrieveStatus = input.readNullableInt()
        val readReport = input.readNullableInt()
        val deliveryReport = input.readNullableInt()
        val reportAllowed = input.readNullableInt()
        val messageSizeBytes = input.readNullableLong()
        val expiryMillis = input.readNullableLong()
        val deliveryTimeMillis = input.readNullableLong()
        val subject = input.readNullableString(MAX_TEXT_BYTES)
        val subjectCharset = input.readNullableInt()
        val contentType = input.readNullableString(MAX_MIME_BYTES)
        val contentLocation = input.readNullableString(MAX_METADATA_BYTES)
        val messageClass = input.readNullableString(MAX_METADATA_BYTES)
        val transactionId = input.readNullableString(MAX_METADATA_BYTES)
        val addressCount = input.readInt()
        if (addressCount !in 0..MAX_MMS_ADDRESSES) throw InvalidMessageSchemaException()
        val addresses = List(addressCount) {
            AuroraBackupMmsAddress(
                type = input.readInt(),
                address = input.readRequiredString(MAX_ADDRESS_BYTES),
                charset = input.readNullableInt(),
            )
        }
        return AuroraBackupMmsRecord(
            archiveMessageId = archiveMessageId,
            box = box,
            timestampMillis = timestampMillis,
            sentTimestampMillis = sentTimestampMillis,
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
            messageSizeBytes = messageSizeBytes,
            expiryMillis = expiryMillis,
            deliveryTimeMillis = deliveryTimeMillis,
            subject = subject,
            subjectCharset = subjectCharset,
            contentType = contentType,
            contentLocation = contentLocation,
            messageClass = messageClass,
            transactionId = transactionId,
            addresses = addresses,
        )
    }

    private fun requireSchema(input: DataInputStream) {
        if (input.readUnsignedByte() != SCHEMA_VERSION) throw InvalidMessageSchemaException()
    }

    private fun readBox(input: DataInputStream): AuroraBackupMessageBox =
        AuroraBackupMessageBox.decode(input.readUnsignedByte()) ?: throw InvalidMessageSchemaException()

    private inline fun <T> decode(bytes: ByteArray, read: (DataInputStream) -> T): T? = try {
        decode(ByteArrayInputStream(bytes), read)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private inline fun <T> decode(source: InputStream, read: (DataInputStream) -> T): T? = try {
        DataInputStream(source).let { input -> read(input).takeIf { input.read() == -1 } }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private const val PAYLOAD_EMPTY = 0
    private const val PAYLOAD_TEXT = 1
    private const val PAYLOAD_BINARY = 2
    private const val STREAM_BUFFER_BYTES = 64 * 1_024
}

internal sealed interface AuroraBackupSchemaIdentity {
    data class Message(val archiveMessageId: Long, val isMms: Boolean) : AuroraBackupSchemaIdentity
    data class MmsPart(val parentArchiveMessageId: Long) : AuroraBackupSchemaIdentity
}

internal class VisitorFailureException(cause: Throwable) : RuntimeException(cause)

private data object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(source: ByteArray, offset: Int, length: Int) = Unit
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    value?.let(::writeInt)
}

private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableString(value: String?, maximumBytes: Int) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeRequiredString(value, maximumBytes)
    }
}

private fun DataOutputStream.writeRequiredString(value: String, maximumBytes: Int) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    if (encoded.size > maximumBytes) throw InvalidMessageSchemaException()
    writeInt(encoded.size)
    write(encoded)
}

private fun DataInputStream.readNullableString(maximumBytes: Int): String? {
    val length = readInt()
    if (length == -1) return null
    return readStringBytes(length, maximumBytes)
}

private fun DataInputStream.readRequiredString(maximumBytes: Int): String =
    readStringBytes(readInt(), maximumBytes)

private fun DataInputStream.readStringBytes(length: Int, maximumBytes: Int): String {
    if (length !in 0..maximumBytes) throw InvalidMessageSchemaException()
    val bytes = ByteArray(length)
    readFully(bytes)
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw InvalidMessageSchemaException()
    }
}

private fun String?.isNullOrBounded(maximumBytes: Int): Boolean = this == null || isBounded(maximumBytes)

private fun String.isBounded(maximumBytes: Int): Boolean = utf8Size() <= maximumBytes

private fun String?.isNullOrSafeMetadata(maximumBytes: Int): Boolean =
    this == null || isMetadataValue(maximumBytes)

private fun String.isSafeMetadata(maximumBytes: Int): Boolean =
    isNotEmpty() && isBounded(maximumBytes) && none(Char::isISOControl)

private fun String.isMetadataValue(maximumBytes: Int): Boolean =
    isBounded(maximumBytes) && none(Char::isISOControl)

private fun String.isMimeType(): Boolean =
    isSafeMetadata(AuroraBackupMessageCodec.MAX_MIME_BYTES) &&
        MIME_TYPE.matches(this)

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private val MIME_TYPE = Regex("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+")

private class InvalidMessageSchemaException : IOException()
