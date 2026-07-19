// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

data class MmsProviderMessage(
    val id: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val sender: ParticipantAddress?,
    val participants: List<ParticipantAddress>,
    val participantsTruncated: Boolean,
    val body: String?,
    val subject: String?,
    val direction: MessageDirection,
    val box: MessageBox,
    val status: MessageStatus,
    val rawStatus: Int?,
    val rawResponseStatus: Int?,
    val rawRetrieveStatus: Int?,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: AuroraSubscriptionId?,
    val attachments: MmsAttachmentSummary,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val syncFingerprint: MessageSyncFingerprint,
) {
    init {
        require(id.kind == org.aurorasms.core.model.ProviderKind.MMS) {
            "MMS provider messages need an MMS provider ID"
        }
        require(participants.size <= MAX_MMS_PARTICIPANTS) { "MMS participant list is too large" }
        require(body == null || body.length <= MAX_MMS_TEXT_CHARACTERS) { "MMS text is too long" }
        require(subject == null || subject.length <= MAX_MMS_SUBJECT_CHARACTERS) { "MMS subject is too long" }
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Provider sent timestamps cannot be negative"
        }
    }

    override fun toString(): String =
        "MmsProviderMessage(" +
            "participantCount=${participants.size}, " +
            "bodyLength=${body?.length ?: 0}, " +
            "hasSubject=${subject != null}, " +
            "attachmentCount=${attachments.attachmentCount})"

    companion object {
        const val MAX_MMS_PARTICIPANTS: Int = 100
        const val MAX_MMS_SUBJECT_CHARACTERS: Int = 1_000
        const val MAX_MMS_TEXT_CHARACTERS: Int = 100_000
    }
}

/**
 * A future audited codec produces this normalized record. Phase 1 deliberately
 * does not infer it from untrusted WAP bytes.
 */
data class DecodedIncomingMmsRecord(
    val participants: List<ParticipantAddress>,
    val subject: String?,
    val text: String?,
    val sentTimestampMillis: Long,
    val receivedTimestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId?,
) {
    init {
        require(participants.isNotEmpty()) { "An incoming MMS needs a participant" }
        require(participants.size <= MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
            "MMS participant list is too large"
        }
        require(subject == null || subject.length <= MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS) {
            "MMS subject is too long"
        }
        require(text == null || text.length <= MAX_MMS_TEXT_CHARACTERS) { "MMS text is too long" }
        require(sentTimestampMillis >= 0L && receivedTimestampMillis >= 0L) {
            "MMS timestamps cannot be negative"
        }
    }

    companion object {
        const val MAX_MMS_TEXT_CHARACTERS: Int = 100_000
    }
}

interface MmsProviderDataSource {
    suspend fun count(): ProviderAccessResult<Long>

    suspend fun readPage(request: ProviderPageRequest): ProviderAccessResult<ProviderPage<MmsProviderMessage>>

    /** Reads one exact provider identity for guarded local mutations. */
    suspend fun readExact(
        id: ProviderMessageId,
    ): ProviderAccessResult<MmsProviderMessage?> =
        ProviderAccessResult.Unsupported("read exact MMS")

    suspend fun insertIncoming(message: DecodedIncomingMmsRecord): ProviderAccessResult<ProviderStoredMessage>
}
