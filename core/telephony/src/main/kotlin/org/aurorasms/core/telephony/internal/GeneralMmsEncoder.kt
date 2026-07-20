// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.codec.aosp.pdu.CharacterSets
import org.aurorasms.core.telephony.codec.aosp.pdu.EncodedStringValue
import org.aurorasms.core.telephony.codec.aosp.pdu.PduBody
import org.aurorasms.core.telephony.codec.aosp.pdu.PduComposer
import org.aurorasms.core.telephony.codec.aosp.pdu.PduHeaders
import org.aurorasms.core.telephony.codec.aosp.pdu.PduPart
import org.aurorasms.core.telephony.codec.aosp.pdu.SendReq

/** Bounded one-operation composer for ordinary direct and group MMS. */
internal class GeneralMmsEncoder(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val transactionId: () -> String = ::newGeneralMmsTransactionId,
) {
    private val appContext = context.applicationContext

    fun encode(
        recipients: RecipientSet,
        payload: OutgoingMmsPayload.Message,
    ): GeneralMmsEncodingResult {
        val now = nowMillis()
        if (now < 0L) return GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.INVALID_METADATA)
        val transaction = transactionId()
        if (!GENERAL_MMS_TRANSACTION_ID.matches(transaction)) {
            return GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.INVALID_METADATA)
        }
        return try {
            val request = SendReq(
                MMS_MULTIPART_RELATED.bytes(),
                EncodedStringValue(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.bytes()),
                PduHeaders.CURRENT_MMS_VERSION,
                transaction.bytes(),
            ).apply {
                setTo(
                    recipients.addresses
                        .map { address -> EncodedStringValue(CharacterSets.UTF_8, address.value.utf8()) }
                        .toTypedArray(),
                )
                setDate(now / 1_000L)
                setExpiry(GENERAL_MMS_EXPIRY_SECONDS)
                setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.bytes())
                setPriority(PduHeaders.PRIORITY_NORMAL)
                setDeliveryReport(PduHeaders.VALUE_NO)
                setReadReport(PduHeaders.VALUE_NO)
                payload.subject?.let { subject ->
                    setSubject(EncodedStringValue(CharacterSets.UTF_8, subject.utf8()))
                }
                setBody(generalMmsBody(payload))
            }
            val bytes = PduComposer(appContext, request).make()
                ?: return GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.COMPOSITION_FAILED)
            when (val bounded = EncodedMmsPdu.create(bytes)) {
                is EncodedMmsPdu.CreationResult.Valid -> GeneralMmsEncodingResult.Encoded(
                    pdu = bounded.pdu,
                    transactionId = transaction,
                    timestampMillis = now,
                )
                is EncodedMmsPdu.CreationResult.Rejected -> GeneralMmsEncodingResult.Rejected(
                    if (bounded.reason == EncodedMmsPdu.CreationResult.Reason.TOO_LARGE) {
                        GeneralMmsEncodingFailure.PAYLOAD_TOO_LARGE
                    } else {
                        GeneralMmsEncodingFailure.COMPOSITION_FAILED
                    },
                )
            }
        } catch (_: IllegalArgumentException) {
            GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.INVALID_METADATA)
        } catch (_: IllegalStateException) {
            GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.COMPOSITION_FAILED)
        } catch (_: RuntimeException) {
            GeneralMmsEncodingResult.Rejected(GeneralMmsEncodingFailure.COMPOSITION_FAILED)
        }
    }
}

internal fun generalMmsBody(payload: OutgoingMmsPayload.Message): PduBody = PduBody().apply {
    addPart(
        generalMmsPart(
            contentType = GENERAL_MMS_SMIL_CONTENT_TYPE,
            contentId = GENERAL_MMS_SMIL_CONTENT_ID,
            location = GENERAL_MMS_SMIL_LOCATION,
            data = generalMmsSmil(payload).utf8(),
            disposition = GENERAL_MMS_INLINE_DISPOSITION,
        ),
    )
    payload.text?.let { text ->
        addPart(
            generalMmsPart(
                contentType = GENERAL_MMS_TEXT_CONTENT_TYPE,
                contentId = GENERAL_MMS_TEXT_CONTENT_ID,
                location = GENERAL_MMS_TEXT_LOCATION,
                data = text.utf8(),
                disposition = GENERAL_MMS_INLINE_DISPOSITION,
                charset = CharacterSets.UTF_8,
            ),
        )
    }
    payload.attachments.forEachIndexed { index, attachment ->
        val location = generalMmsAttachmentLocation(index, attachment.contentType)
        addPart(
            generalMmsPart(
                contentType = attachment.contentType,
                contentId = "media$index",
                location = location,
                data = attachment.copyBytes(),
                disposition = GENERAL_MMS_ATTACHMENT_DISPOSITION,
            ),
        )
    }
}

internal fun generalMmsSmil(payload: OutgoingMmsPayload.Message): String = buildString {
    append("<smil><head><layout><root-layout/><region id=\"Image\"/><region id=\"Text\"/></layout></head><body>")
    payload.text?.let {
        append("<par dur=\"5000ms\"><text src=\"")
        append(GENERAL_MMS_TEXT_LOCATION)
        append("\" region=\"Text\"/></par>")
    }
    payload.attachments.forEachIndexed { index, attachment ->
        val tag = when {
            attachment.contentType.startsWith("image/") -> "img"
            attachment.contentType.startsWith("audio/") -> "audio"
            else -> "video"
        }
        append("<par dur=\"5000ms\"><")
        append(tag)
        append(" src=\"")
        append(generalMmsAttachmentLocation(index, attachment.contentType))
        append("\"")
        if (tag == "img") append(" region=\"Image\"")
        append("/></par>")
    }
    append("</body></smil>")
}

internal fun generalMmsAttachmentLocation(index: Int, contentType: String): String {
    require(index in 0 until OutgoingMmsPayload.Message.MAX_ATTACHMENTS)
    val extension = when (contentType) {
        OutgoingMmsAttachment.IMAGE_JPEG -> "jpg"
        OutgoingMmsAttachment.IMAGE_PNG -> "png"
        OutgoingMmsAttachment.IMAGE_GIF -> "gif"
        OutgoingMmsAttachment.IMAGE_WEBP -> "webp"
        OutgoingMmsAttachment.AUDIO_MP4 -> "m4a"
        OutgoingMmsAttachment.VIDEO_MP4 -> "mp4"
        else -> throw IllegalArgumentException("Unsupported MMS attachment content type")
    }
    return "media_${index}.$extension"
}

private fun generalMmsPart(
    contentType: String,
    contentId: String,
    location: String,
    data: ByteArray,
    disposition: String,
    charset: Int = 0,
): PduPart = PduPart().apply {
    setContentType(contentType.bytes())
    setContentId(contentId.bytes())
    setContentLocation(location.bytes())
    setName(location.bytes())
    setFilename(location.bytes())
    setContentDisposition(disposition.bytes())
    if (charset != 0) setCharset(charset)
    setData(data)
}

internal sealed interface GeneralMmsEncodingResult {
    data class Encoded(
        val pdu: EncodedMmsPdu,
        val transactionId: String,
        val timestampMillis: Long,
    ) : GeneralMmsEncodingResult {
        override fun toString(): String = "GeneralMmsEncodingResult.Encoded(pdu=$pdu, REDACTED)"
    }

    data class Rejected(val reason: GeneralMmsEncodingFailure) : GeneralMmsEncodingResult
}

internal enum class GeneralMmsEncodingFailure {
    INVALID_METADATA,
    PAYLOAD_TOO_LARGE,
    COMPOSITION_FAILED,
}

internal const val GENERAL_MMS_MULTIPART_RELATED = "application/vnd.wap.multipart.related"
internal const val GENERAL_MMS_SMIL_CONTENT_TYPE = "application/smil"
internal const val GENERAL_MMS_TEXT_CONTENT_TYPE = "text/plain"
internal const val GENERAL_MMS_SMIL_CONTENT_ID = "smil"
internal const val GENERAL_MMS_TEXT_CONTENT_ID = "text0"
internal const val GENERAL_MMS_SMIL_LOCATION = "smil.xml"
internal const val GENERAL_MMS_TEXT_LOCATION = "text_0.txt"
internal const val GENERAL_MMS_INLINE_DISPOSITION = "inline"
internal const val GENERAL_MMS_ATTACHMENT_DISPOSITION = "attachment"
internal const val GENERAL_MMS_EXPIRY_SECONDS = 7L * 24L * 60L * 60L
internal val GENERAL_MMS_TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,64}")

private const val MMS_MULTIPART_RELATED = GENERAL_MMS_MULTIPART_RELATED

private fun newGeneralMmsTransactionId(): String =
    "T" + UUID.randomUUID().toString().replace("-", "").take(16)

private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
