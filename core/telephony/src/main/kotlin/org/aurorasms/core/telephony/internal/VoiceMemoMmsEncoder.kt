// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.codec.aosp.pdu.CharacterSets
import org.aurorasms.core.telephony.codec.aosp.pdu.EncodedStringValue
import org.aurorasms.core.telephony.codec.aosp.pdu.PduBody
import org.aurorasms.core.telephony.codec.aosp.pdu.PduComposer
import org.aurorasms.core.telephony.codec.aosp.pdu.PduHeaders
import org.aurorasms.core.telephony.codec.aosp.pdu.PduPart
import org.aurorasms.core.telephony.codec.aosp.pdu.SendReq

/** Narrow outgoing-only wrapper around the pinned AOSP framework SendReq composer. */
internal class VoiceMemoMmsEncoder(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val transactionId: () -> String = ::newTransactionId,
) {
    private val appContext = context.applicationContext

    fun encode(
        recipients: RecipientSet,
        payload: OutgoingMmsPayload.VoiceMemo,
    ): VoiceMemoMmsEncodingResult {
        if (recipients.size != 1) {
            return VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.UNSUPPORTED_RECIPIENT_SET)
        }
        val now = nowMillis()
        if (now < 0L) {
            return VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.INVALID_METADATA)
        }
        val transaction = transactionId()
        if (!TRANSACTION_ID.matches(transaction)) {
            return VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.INVALID_METADATA)
        }
        return try {
            val request = SendReq(
                MULTIPART_RELATED.bytes(),
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
                setExpiry(EXPIRY_SECONDS)
                setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.bytes())
                setPriority(PduHeaders.PRIORITY_NORMAL)
                setDeliveryReport(PduHeaders.VALUE_NO)
                setReadReport(PduHeaders.VALUE_NO)
                payload.subject?.takeIf(String::isNotBlank)?.let { subject ->
                    setSubject(EncodedStringValue(CharacterSets.UTF_8, subject.utf8()))
                }
                setBody(body(payload))
            }
            val bytes = PduComposer(appContext, request).make()
                ?: return VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.COMPOSITION_FAILED)
            when (val bounded = EncodedMmsPdu.create(bytes)) {
                is EncodedMmsPdu.CreationResult.Valid -> VoiceMemoMmsEncodingResult.Encoded(
                    pdu = bounded.pdu,
                    transactionId = transaction,
                    timestampMillis = now,
                )
                is EncodedMmsPdu.CreationResult.Rejected -> VoiceMemoMmsEncodingResult.Rejected(
                    if (bounded.reason == EncodedMmsPdu.CreationResult.Reason.TOO_LARGE) {
                        VoiceMemoMmsEncodingFailure.PAYLOAD_TOO_LARGE
                    } else {
                        VoiceMemoMmsEncodingFailure.COMPOSITION_FAILED
                    },
                )
            }
        } catch (_: IllegalArgumentException) {
            VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.INVALID_METADATA)
        } catch (_: IllegalStateException) {
            VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.COMPOSITION_FAILED)
        } catch (_: RuntimeException) {
            VoiceMemoMmsEncodingResult.Rejected(VoiceMemoMmsEncodingFailure.COMPOSITION_FAILED)
        }
    }

    private fun body(payload: OutgoingMmsPayload.VoiceMemo): PduBody {
        val text = payload.text?.takeIf(String::isNotBlank)
        val smil = voiceMemoSmil(payload.memo.durationMillis, text != null)
        return PduBody().apply {
            addPart(
                part(
                    contentType = APPLICATION_SMIL,
                    contentId = SMIL_CONTENT_ID,
                    location = SMIL_LOCATION,
                    data = smil.utf8(),
                    disposition = INLINE_DISPOSITION,
                ),
            )
            if (text != null) {
                addPart(
                    part(
                        contentType = TEXT_PLAIN,
                        contentId = TEXT_CONTENT_ID,
                        location = TEXT_LOCATION,
                        data = text.utf8(),
                        disposition = INLINE_DISPOSITION,
                        charset = CharacterSets.UTF_8,
                    ),
                )
            }
            addPart(
                part(
                    contentType = AUDIO_MP4,
                    contentId = AUDIO_CONTENT_ID,
                    location = AUDIO_LOCATION,
                    data = payload.memo.copyBytes(),
                    disposition = ATTACHMENT_DISPOSITION,
                ),
            )
        }
    }

    private fun part(
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

    companion object {
        private const val MULTIPART_RELATED = "application/vnd.wap.multipart.related"
        private const val APPLICATION_SMIL = "application/smil"
        private const val TEXT_PLAIN = "text/plain"
        private const val AUDIO_MP4 = "audio/mp4"
        private const val SMIL_CONTENT_ID = "smil"
        private const val TEXT_CONTENT_ID = "text0"
        private const val AUDIO_CONTENT_ID = "voice0"
        private const val SMIL_LOCATION = "smil.xml"
        private const val TEXT_LOCATION = "text_0.txt"
        private const val AUDIO_LOCATION = "voice_0.m4a"
        private const val INLINE_DISPOSITION = "inline"
        private const val ATTACHMENT_DISPOSITION = "attachment"
        private const val EXPIRY_SECONDS = 7L * 24L * 60L * 60L
        private val TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,64}")

        private fun newTransactionId(): String =
            "T" + UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

internal sealed interface VoiceMemoMmsEncodingResult {
    data class Encoded(
        val pdu: EncodedMmsPdu,
        val transactionId: String,
        val timestampMillis: Long,
    ) : VoiceMemoMmsEncodingResult {
        override fun toString(): String =
            "VoiceMemoMmsEncodingResult.Encoded(pdu=$pdu, REDACTED)"
    }
    data class Rejected(val reason: VoiceMemoMmsEncodingFailure) : VoiceMemoMmsEncodingResult
}

internal enum class VoiceMemoMmsEncodingFailure {
    UNSUPPORTED_RECIPIENT_SET,
    INVALID_METADATA,
    PAYLOAD_TOO_LARGE,
    COMPOSITION_FAILED,
}

private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
