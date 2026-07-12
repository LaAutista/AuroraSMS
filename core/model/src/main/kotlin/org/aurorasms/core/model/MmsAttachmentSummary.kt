// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import java.util.Collections

/**
 * A bounded MMS attachment projection containing metadata only.
 *
 * [attachmentCount] and [contentTypes] describe only the inspected attachment
 * rows. [metadataTruncated] makes an overflow explicit instead of pretending
 * the bounded summary is complete. [totalBytes] is the provider message-size
 * value when it is present and valid; attachment bytes are never retained.
 */
class MmsAttachmentSummary(
    val attachmentCount: Int,
    val totalBytes: Long?,
    contentTypes: List<MmsAttachmentType>,
    val metadataTruncated: Boolean,
) {
    val contentTypes: List<MmsAttachmentType> =
        Collections.unmodifiableList(contentTypes.toList())

    init {
        require(attachmentCount in 0..MAX_ATTACHMENT_COUNT) {
            "MMS attachment count is outside the bounded summary"
        }
        require(totalBytes == null || totalBytes >= 0L) { "MMS total bytes cannot be negative" }
        require(contentTypes.size <= attachmentCount) {
            "MMS attachment type metadata exceeds the attachment count"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MmsAttachmentSummary &&
            attachmentCount == other.attachmentCount &&
            totalBytes == other.totalBytes &&
            contentTypes == other.contentTypes &&
            metadataTruncated == other.metadataTruncated

    override fun hashCode(): Int {
        var result = attachmentCount
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        result = 31 * result + contentTypes.hashCode()
        result = 31 * result + metadataTruncated.hashCode()
        return result
    }

    override fun toString(): String =
        "MmsAttachmentSummary(" +
            "attachmentCount=$attachmentCount, " +
            "totalBytes=$totalBytes, " +
            "contentTypeCount=${contentTypes.size}, " +
            "metadataTruncated=$metadataTruncated)"

    companion object {
        const val MAX_ATTACHMENT_COUNT: Int = 25

        val EMPTY: MmsAttachmentSummary = MmsAttachmentSummary(
            attachmentCount = 0,
            totalBytes = null,
            contentTypes = emptyList(),
            metadataTruncated = false,
        )
    }
}
