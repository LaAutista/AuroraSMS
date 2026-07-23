// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.aurorasms.core.state.DraftAttachment

@Entity(
    tableName = "draft_attachments",
    primaryKeys = ["draft_id", "attachment_index"],
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class DraftAttachmentEntity(
    @ColumnInfo(name = "draft_id")
    val draftId: Long,
    @ColumnInfo(name = "attachment_index")
    val attachmentIndex: Int,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "content_bytes", typeAffinity = ColumnInfo.BLOB)
    val contentBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DraftAttachmentEntity &&
            draftId == other.draftId &&
            attachmentIndex == other.attachmentIndex &&
            contentType == other.contentType &&
            contentBytes.contentEquals(other.contentBytes)

    override fun hashCode(): Int =
        31 * (31 * (31 * draftId.hashCode() + attachmentIndex) + contentType.hashCode()) +
            contentBytes.contentHashCode()

    override fun toString(): String =
        "DraftAttachmentEntity(index=$attachmentIndex, contentType=$contentType, " +
            "size=${contentBytes.size}, content=REDACTED)"
}

internal fun DraftAttachment.toEntity(draftId: Long, index: Int): DraftAttachmentEntity =
    DraftAttachmentEntity(
        draftId = draftId,
        attachmentIndex = index,
        contentType = contentType,
        contentBytes = copyBytes(),
    )

internal fun DraftAttachmentEntity.toDomain(expectedIndex: Int): DraftAttachment {
    require(attachmentIndex == expectedIndex) { "Stored draft attachment indices are not contiguous" }
    return when (val result = DraftAttachment.create(contentType, contentBytes)) {
        is DraftAttachment.CreationResult.Valid -> result.attachment
        is DraftAttachment.CreationResult.Rejected ->
            error("A stored draft attachment violates its admitted bounds")
    }
}
