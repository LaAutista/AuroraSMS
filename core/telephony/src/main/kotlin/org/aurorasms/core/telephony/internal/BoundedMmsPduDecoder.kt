// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.codec.aosp.pdu.CharacterSets
import org.aurorasms.core.telephony.codec.aosp.pdu.EncodedStringValue
import org.aurorasms.core.telephony.codec.aosp.pdu.NotificationInd
import org.aurorasms.core.telephony.codec.aosp.pdu.PduParser
import org.aurorasms.core.telephony.codec.aosp.pdu.PduPart
import org.aurorasms.core.telephony.codec.aosp.pdu.RetrieveConf

/**
 * Bounded, content-redacting boundary around the pinned AOSP MMS parser.
 *
 * The parser only consumes an already bounded [EncodedMmsPdu]. This wrapper then
 * validates every value before it can reach transport or provider code.
 */
internal class BoundedMmsPduDecoder {
    fun decode(pdu: EncodedMmsPdu): BoundedMmsDecodeResult = try {
        when (val parsed = PduParser(pdu.copyBytes()).parse()) {
            is NotificationInd -> decodeNotification(parsed)
            is RetrieveConf -> decodeRetrieved(parsed)
            else -> BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.UNSUPPORTED_MESSAGE_TYPE)
        }
    } catch (_: IllegalArgumentException) {
        BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.MALFORMED)
    } catch (_: IllegalStateException) {
        BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.MALFORMED)
    } catch (_: IndexOutOfBoundsException) {
        BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.MALFORMED)
    } catch (_: NegativeArraySizeException) {
        BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.MALFORMED)
    } catch (_: RuntimeException) {
        BoundedMmsDecodeResult.Rejected(BoundedMmsDecodeFailure.MALFORMED)
    }

    private fun decodeNotification(value: NotificationInd): BoundedMmsDecodeResult {
        val transactionId = value.transactionId.safeAscii(MAX_TRANSACTION_ID_BYTES)
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val contentLocation = value.contentLocation.safeContentLocation()
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val messageSize = value.messageSize
        if (messageSize !in 1L..EncodedMmsPdu.MAX_ENCODED_BYTES.toLong()) {
            return rejected(BoundedMmsDecodeFailure.LIMIT_EXCEEDED)
        }
        val expiryTimestampMillis = value.expiry.secondsToMillisOrNull()
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val messageClass = value.messageClass.safeAscii(MAX_MESSAGE_CLASS_BYTES)
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val sender = value.from?.safeAddressOrNull()
        if (value.from != null && sender == null) {
            return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val subject = value.subject.safeSubjectOrNull()
        if (value.subject != null && subject == null) {
            return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        return BoundedMmsDecodeResult.Decoded(
            BoundedMmsPdu.Notification(
                transactionId = transactionId,
                contentLocation = contentLocation,
                sender = sender,
                subject = subject,
                messageSizeBytes = messageSize,
                expiryTimestampMillis = expiryTimestampMillis,
                messageClass = messageClass,
            ),
        )
    }

    private fun decodeRetrieved(value: RetrieveConf): BoundedMmsDecodeResult {
        val body = value.body ?: return rejected(BoundedMmsDecodeFailure.MALFORMED)
        if (body.partsNum !in 1..MAX_PART_COUNT) {
            return rejected(BoundedMmsDecodeFailure.LIMIT_EXCEEDED)
        }
        val sentTimestampMillis = value.date.secondsToMillisOrNull()
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val sender = value.from?.safeAddressOrNull()
        if (value.from != null && sender == null) {
            return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val to = value.to.safeAddressesOrNull()
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val cc = value.cc.safeAddressesOrNull()
            ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        val participants = linkedMapOf<String, ParticipantAddress>().apply {
            sender?.let { put(it.value, it) }
            to.forEach { put(it.value, it) }
            cc.forEach { put(it.value, it) }
        }.values.toList()
        if (participants.isEmpty() || participants.size > MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
            return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val subject = value.subject.safeSubjectOrNull()
        if (value.subject != null && subject == null) {
            return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val messageId = value.messageId?.let { raw ->
            raw.safeAscii(MAX_MESSAGE_ID_BYTES)
                ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val transactionId = value.transactionId?.let { raw ->
            raw.safeAscii(MAX_TRANSACTION_ID_BYTES)
                ?: return rejected(BoundedMmsDecodeFailure.UNSAFE_METADATA)
        }
        val retrieveStatus = value.retrieveStatus
        if (retrieveStatus != 0 && retrieveStatus != RETRIEVE_STATUS_OK) {
            return rejected(BoundedMmsDecodeFailure.REMOTE_RETRIEVAL_FAILED)
        }

        var aggregateBytes = 0L
        val parts = ArrayList<BoundedMmsPart>(body.partsNum)
        repeat(body.partsNum) { index ->
            val source = body.getPart(index)
            val contentType = source.contentType.safeMimeTypeOrNull()
                ?: return rejected(BoundedMmsDecodeFailure.UNSUPPORTED_CONTENT)
            if (contentType.startsWith(DRM_MIME_PREFIX)) {
                return rejected(BoundedMmsDecodeFailure.UNSUPPORTED_CONTENT)
            }
            val bytes = source.data ?: ByteArray(0)
            aggregateBytes += bytes.size
            if (aggregateBytes > EncodedMmsPdu.MAX_ENCODED_BYTES) {
                return rejected(BoundedMmsDecodeFailure.LIMIT_EXCEEDED)
            }
            val charset = source.charset.takeIf { it != 0 }
            val decodedText = if (contentType == TEXT_PLAIN) {
                bytes.decodeTextOrNull(charset)
                    ?: return rejected(BoundedMmsDecodeFailure.UNSUPPORTED_CONTENT)
            } else {
                null
            }
            parts += BoundedMmsPart(
                contentType = contentType,
                charsetMibEnum = charset,
                name = source.name.safeOptionalMetadata(MAX_PART_NAME_BYTES),
                filename = source.filename.safeOptionalMetadata(MAX_PART_NAME_BYTES),
                contentLocation = source.contentLocation.safeOptionalMetadata(MAX_PART_NAME_BYTES),
                contentId = source.contentId.safeOptionalMetadata(MAX_PART_NAME_BYTES),
                contentDisposition = source.contentDisposition.safeOptionalMetadata(MAX_DISPOSITION_BYTES),
                decodedText = decodedText,
                bytes = bytes,
            )
        }
        return BoundedMmsDecodeResult.Decoded(
            BoundedMmsPdu.Retrieved(
                sender = sender,
                to = to,
                cc = cc,
                participants = participants,
                subject = subject,
                sentTimestampMillis = sentTimestampMillis,
                messageId = messageId,
                transactionId = transactionId,
                parts = parts,
            ),
        )
    }

    private fun rejected(reason: BoundedMmsDecodeFailure): BoundedMmsDecodeResult.Rejected =
        BoundedMmsDecodeResult.Rejected(reason)

    companion object {
        const val MAX_PART_COUNT: Int = 25
        private const val MAX_TRANSACTION_ID_BYTES = 128
        private const val MAX_MESSAGE_ID_BYTES = 256
        private const val MAX_MESSAGE_CLASS_BYTES = 64
        private const val MAX_PART_NAME_BYTES = 255
        private const val MAX_DISPOSITION_BYTES = 64
        private const val MAX_CONTENT_LOCATION_BYTES = 2_048
        private const val TEXT_PLAIN = "text/plain"
        private const val DRM_MIME_PREFIX = "application/vnd.oma.drm"
        private const val RETRIEVE_STATUS_OK = 0x80
        private val MIME_TYPE = Regex(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}",
        )

        private fun ByteArray?.safeContentLocation(): String? {
            val value = safeAscii(MAX_CONTENT_LOCATION_BYTES) ?: return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.isAbsolute || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null) {
                return null
            }
            if (uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                return null
            }
            return value
        }

        private fun ByteArray?.safeMimeTypeOrNull(): String? {
            val value = safeAscii(129)?.lowercase(Locale.ROOT) ?: return null
            return value.takeIf(MIME_TYPE::matches)
        }

        private fun ByteArray?.safeAscii(maxBytes: Int): String? {
            if (this == null || isEmpty() || size > maxBytes) return null
            if (any { byte -> byte.toInt() !in 0x20..0x7e }) return null
            return String(this, StandardCharsets.US_ASCII)
        }

        private fun ByteArray?.safeOptionalMetadata(maxBytes: Int): String? {
            if (this == null || isEmpty()) return null
            if (size > maxBytes) return null
            val value = String(this, StandardCharsets.UTF_8)
            return value.takeIf { text ->
                text.isNotBlank() &&
                    '\uFFFD' !in text &&
                    text.none(Char::isISOControl)
            }
        }

        private fun EncodedStringValue?.safeSubjectOrNull(): String? {
            if (this == null) return null
            val value = string
            return value.takeIf {
                it.length <= MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS &&
                    it.none(Char::isISOControl)
            }
        }

        private fun EncodedStringValue.safeAddressOrNull(): ParticipantAddress? {
            val value = string.trim()
            return runCatching { ParticipantAddress(value) }.getOrNull()
        }

        private fun Array<EncodedStringValue>?.safeAddressesOrNull(): List<ParticipantAddress>? {
            if (this == null) return emptyList()
            if (size > MmsProviderMessage.MAX_MMS_PARTICIPANTS) return null
            val values = ArrayList<ParticipantAddress>(size)
            for (encoded in this) {
                values += encoded.safeAddressOrNull() ?: return null
            }
            return values.distinctBy(ParticipantAddress::value)
        }

        private fun ByteArray.decodeTextOrNull(charsetMibEnum: Int?): String? {
            val charset = runCatching {
                if (charsetMibEnum == null || charsetMibEnum == CharacterSets.ANY_CHARSET) {
                    StandardCharsets.UTF_8
                } else {
                    Charset.forName(CharacterSets.getMimeName(charsetMibEnum))
                }
            }.getOrNull() ?: return null
            val value = String(this, charset)
            return value.takeIf {
                it.length <= MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS &&
                    it.all { character ->
                        !character.isISOControl() || character == '\n' || character == '\r' || character == '\t'
                    }
            }
        }

        private fun Long.secondsToMillisOrNull(): Long? =
            takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
    }
}

internal sealed interface BoundedMmsDecodeResult {
    data class Decoded(val pdu: BoundedMmsPdu) : BoundedMmsDecodeResult {
        override fun toString(): String = "BoundedMmsDecodeResult.Decoded(pdu=$pdu)"
    }

    data class Rejected(val reason: BoundedMmsDecodeFailure) : BoundedMmsDecodeResult
}

internal enum class BoundedMmsDecodeFailure {
    MALFORMED,
    UNSUPPORTED_MESSAGE_TYPE,
    UNSAFE_METADATA,
    LIMIT_EXCEEDED,
    UNSUPPORTED_CONTENT,
    REMOTE_RETRIEVAL_FAILED,
}

internal sealed interface BoundedMmsPdu {
    data class Notification(
        val transactionId: String,
        val contentLocation: String,
        val sender: ParticipantAddress?,
        val subject: String?,
        val messageSizeBytes: Long,
        val expiryTimestampMillis: Long,
        val messageClass: String,
    ) : BoundedMmsPdu {
        override fun toString(): String =
            "BoundedMmsPdu.Notification(hasSender=${sender != null}, hasSubject=${subject != null}, " +
                "messageSizeBytes=$messageSizeBytes, REDACTED)"
    }

    data class Retrieved(
        val sender: ParticipantAddress?,
        val to: List<ParticipantAddress>,
        val cc: List<ParticipantAddress>,
        val participants: List<ParticipantAddress>,
        val subject: String?,
        val sentTimestampMillis: Long,
        val messageId: String?,
        val transactionId: String?,
        val parts: List<BoundedMmsPart>,
    ) : BoundedMmsPdu {
        override fun toString(): String =
            "BoundedMmsPdu.Retrieved(hasSender=${sender != null}, participantCount=${participants.size}, " +
                "hasSubject=${subject != null}, partCount=${parts.size}, REDACTED)"
    }
}

internal class BoundedMmsPart(
    val contentType: String,
    val charsetMibEnum: Int?,
    val name: String?,
    val filename: String?,
    val contentLocation: String?,
    val contentId: String?,
    val contentDisposition: String?,
    val decodedText: String?,
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    val size: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BoundedMmsPart &&
            contentType == other.contentType &&
            charsetMibEnum == other.charsetMibEnum &&
            name == other.name &&
            filename == other.filename &&
            contentLocation == other.contentLocation &&
            contentId == other.contentId &&
            contentDisposition == other.contentDisposition &&
            decodedText == other.decodedText &&
            Arrays.equals(bytes, other.bytes)

    override fun hashCode(): Int = 31 * contentType.hashCode() + Arrays.hashCode(bytes)

    override fun toString(): String =
        "BoundedMmsPart(contentType=$contentType, size=$size, hasText=${decodedText != null}, REDACTED)"
}
