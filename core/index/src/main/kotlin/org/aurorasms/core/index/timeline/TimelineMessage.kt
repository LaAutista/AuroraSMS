// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.timeline

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

data class TimelineMessage(
    val localRowId: Long,
    val providerMessageId: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val direction: MessageDirection,
    val box: MessageBox,
    val status: MessageStatus,
    val subscriptionId: AuroraSubscriptionId?,
    val senderAddress: ParticipantAddress?,
    val bodyPreview: String?,
    val bodyTruncated: Boolean,
    val subject: String?,
    val attachmentCount: Int,
    val attachmentTypeSummary: String,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
) {
    init {
        require(localRowId > 0L) { "Timeline local rows must be positive" }
        require(timestampMillis >= 0L) { "Timeline timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Timeline sent timestamps cannot be negative"
        }
        require(bodyPreview == null || bodyPreview.length <= MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS) {
            "Timeline body previews must remain bounded"
        }
        require(attachmentCount >= 0) { "Attachment counts cannot be negative" }
    }

    override fun toString(): String = "TimelineMessage(REDACTED)"
}

const val MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS: Int = 16_384
