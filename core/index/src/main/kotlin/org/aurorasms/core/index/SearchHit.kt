// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

data class SearchHit(
    val localRowId: Long,
    val providerId: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val direction: MessageDirection,
    val box: MessageBox,
    val status: MessageStatus,
    val subscriptionId: AuroraSubscriptionId?,
    val senderAddress: String?,
    val body: String?,
    val subject: String?,
    val attachmentCount: Int,
    val attachmentTypeSummary: String,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
) {
    init {
        require(localRowId > 0L) { "Index row IDs must be positive" }
        require(timestampMillis >= 0L) { "Indexed timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Indexed sent timestamps cannot be negative"
        }
        require(attachmentCount >= 0) { "Attachment counts cannot be negative" }
    }

    override fun toString(): String = "SearchHit(REDACTED)"
}

data class SearchAnchor(
    val localRowId: Long,
    val providerId: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
) {
    init {
        require(localRowId > 0L) { "Anchor row IDs must be positive" }
    }

    override fun toString(): String = "SearchAnchor(REDACTED)"
}
