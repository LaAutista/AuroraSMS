// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical, content-redacted ownership fingerprints shared by restore and provider verification. */
internal object AuroraRestoreCanonicalDigest {
    fun sms(
        record: AuroraBackupSmsRecord,
        includeHistoricalBox: Boolean,
    ): AuroraRestorePreparedDigest = digest(DOMAIN_SMS, includeHistoricalBox) { output ->
        if (includeHistoricalBox) output.writeInt(record.box.code)
        output.writeNullableString(record.address)
        output.writeNullableString(record.body)
        output.writeLong(record.timestampMillis)
        output.writeNullableLong(record.sentTimestampMillis)
        output.writeBoolean(record.read)
        output.writeBoolean(record.seen)
        output.writeBoolean(record.locked)
        output.writeNullableInt(record.status)
        output.writeNullableInt(record.errorCode)
        output.writeNullableInt(record.protocol)
        output.writeNullableInt(record.replyPathPresent)
        output.writeNullableString(record.subject)
        output.writeNullableString(record.serviceCenter)
        output.writeNullableInt(record.subscriptionId)
    }

    fun beginMms(
        record: AuroraBackupMmsRecord,
        includeHistoricalBox: Boolean,
    ): AuroraRestoreMmsDigestAccumulator = AuroraRestoreMmsDigestAccumulator(
        record,
        includeHistoricalBox,
    )

    @Throws(IOException::class)
    fun mmsPart(
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
        binaryDestination: OutputStream? = null,
    ): AuroraRestoreMmsPartDigest {
        val payloadHash = MessageDigest.getInstance(SHA_256)
        val payloadSize = when (payload) {
            AuroraBackupDecodedPartPayload.Empty -> 0L
            is AuroraBackupDecodedPartPayload.Text -> {
                val bytes = payload.value.toByteArray(StandardCharsets.UTF_8)
                payloadHash.update(bytes)
                bytes.size.toLong()
            }
            is AuroraBackupDecodedPartPayload.Binary -> payload.copyTo(
                HashingCopyOutputStream(binaryDestination, payloadHash),
            )
        }
        val payloadKind = when (payload) {
            AuroraBackupDecodedPartPayload.Empty -> PAYLOAD_EMPTY
            is AuroraBackupDecodedPartPayload.Text -> PAYLOAD_TEXT
            is AuroraBackupDecodedPartPayload.Binary -> PAYLOAD_BINARY
        }
        return AuroraRestoreMmsPartDigest(
            digestHex(DOMAIN_MMS_PART) { output ->
                output.writeInt(record.sequence)
                output.writeRequiredString(record.contentType)
                output.writeNullableInt(record.charset)
                output.writeNullableString(record.name)
                output.writeNullableString(record.contentDisposition)
                output.writeNullableString(record.filename)
                output.writeNullableString(record.contentId)
                output.writeNullableString(record.contentLocation)
                output.writeByte(payloadKind)
                output.writeLong(payloadSize)
                output.write(payloadHash.digest())
            },
        )
    }

    private fun digest(
        domain: String,
        includeHistoricalBox: Boolean,
        write: (DataOutputStream) -> Unit,
    ): AuroraRestorePreparedDigest = AuroraRestorePreparedDigest(
        digestHex(domain) { output ->
            output.writeBoolean(includeHistoricalBox)
            write(output)
        },
    )

    internal fun digestHex(
        domain: String,
        write: (DataOutputStream) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        DataOutputStream(MessageDigestOutputStream(digest)).use { output ->
            output.writeRequiredString(domain)
            write(output)
        }
        return digest.digest().toLowerHex()
    }

    private const val SHA_256 = "SHA-256"
    private const val DOMAIN_SMS = "AuroraSMS restore SMS digest v1"
    private const val DOMAIN_MMS = "AuroraSMS restore MMS digest v1"
    private const val DOMAIN_MMS_PART = "AuroraSMS restore MMS part digest v1"
    private const val PAYLOAD_EMPTY = 0
    private const val PAYLOAD_TEXT = 1
    private const val PAYLOAD_BINARY = 2

    internal fun mmsDomain(): String = DOMAIN_MMS
}

data class AuroraRestoreMmsPartDigest(val value: String) {
    init {
        require(value.matches(Regex("[a-f0-9]{64}")))
    }

    override fun toString(): String = "AuroraRestoreMmsPartDigest(REDACTED)"
}

internal class AuroraRestoreMmsDigestAccumulator(
    record: AuroraBackupMmsRecord,
    includeHistoricalBox: Boolean,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val output = DataOutputStream(MessageDigestOutputStream(digest))
    private var partCount = 0
    private var complete = false

    init {
        output.writeRequiredString(AuroraRestoreCanonicalDigest.mmsDomain())
        output.writeBoolean(includeHistoricalBox)
        if (includeHistoricalBox) output.writeInt(record.box.code)
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
        output.writeNullableString(record.subject)
        output.writeNullableInt(record.subjectCharset)
        output.writeNullableString(record.contentType)
        output.writeNullableString(record.contentLocation)
        output.writeNullableString(record.messageClass)
        output.writeNullableString(record.transactionId)
        output.writeInt(record.addresses.size)
        record.addresses.forEach { address ->
            output.writeInt(address.type)
            output.writeRequiredString(address.address)
            output.writeNullableInt(address.charset)
        }
    }

    fun accept(part: AuroraRestoreMmsPartDigest) {
        check(!complete) { "MMS ownership digest is already complete" }
        output.writeInt(partCount)
        output.write(hexBytes(part.value))
        partCount += 1
    }

    fun finish(): AuroraRestorePreparedDigest {
        check(!complete) { "MMS ownership digest is already complete" }
        complete = true
        output.writeInt(partCount)
        output.close()
        return AuroraRestorePreparedDigest(
            digest.digest().toLowerHex(),
        )
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private class HashingCopyOutputStream(
    private val destination: OutputStream?,
    private val digest: MessageDigest,
) : OutputStream() {
    override fun write(value: Int) {
        destination?.write(value)
        digest.update(value.toByte())
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        destination?.write(bytes, offset, length)
        digest.update(bytes, offset, length)
    }

    override fun flush() {
        destination?.flush()
    }
}

private class MessageDigestOutputStream(
    private val digest: MessageDigest,
) : OutputStream() {
    override fun write(value: Int) {
        digest.update(value.toByte())
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }
}

private fun DataOutputStream.writeRequiredString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeRequiredString(value)
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    if (value != null) writeLong(value)
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
