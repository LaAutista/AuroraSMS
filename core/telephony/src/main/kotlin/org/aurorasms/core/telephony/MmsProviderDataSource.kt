// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId

data class MmsProviderMessage(
    val id: ProviderMessageId,
    val threadId: Long,
    val participants: List<ParticipantAddress>,
    val subject: String?,
    val direction: MessageDirection,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: AuroraSubscriptionId?,
    val read: Boolean,
    val seen: Boolean,
) {
    init {
        require(threadId >= 0L) { "Provider thread IDs cannot be negative" }
        require(participants.size <= MAX_MMS_PARTICIPANTS) { "MMS participant list is too large" }
        require(subject == null || subject.length <= MAX_MMS_SUBJECT_CHARACTERS) { "MMS subject is too long" }
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Provider sent timestamps cannot be negative"
        }
    }

    companion object {
        const val MAX_MMS_PARTICIPANTS: Int = 100
        const val MAX_MMS_SUBJECT_CHARACTERS: Int = 1_000
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

    suspend fun insertIncoming(message: DecodedIncomingMmsRecord): ProviderAccessResult<ProviderStoredMessage>
}
