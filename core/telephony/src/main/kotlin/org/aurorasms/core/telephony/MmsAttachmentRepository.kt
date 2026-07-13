// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.io.InputStream
import org.aurorasms.core.model.MmsAttachmentType
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId

data class MmsAttachmentId(
    val providerMessageId: ProviderMessageId,
    val providerPartId: Long,
) {
    init {
        require(providerMessageId.kind == ProviderKind.MMS) { "Attachment IDs require an MMS provider message" }
        require(providerPartId > 0L) { "MMS provider part IDs must be positive" }
    }

    override fun toString(): String = "MmsAttachmentId(REDACTED)"
}

data class MmsAttachmentDescriptor(
    val id: MmsAttachmentId,
    val type: MmsAttachmentType,
) {
    override fun toString(): String = "MmsAttachmentDescriptor(type=${type.mimeType}, REDACTED)"
}

data class MmsStaticImageList(
    val items: List<MmsAttachmentDescriptor>,
    val metadataTruncated: Boolean,
) {
    init {
        require(items.size <= MAXIMUM_VISIBLE_MMS_IMAGE_PARTS) { "Visible MMS image metadata must remain bounded" }
        require(items.map(MmsAttachmentDescriptor::id).distinct().size == items.size) {
            "Visible MMS image parts must be unique"
        }
    }

    override fun toString(): String =
        "MmsStaticImageList(itemCount=${items.size}, metadataTruncated=$metadataTruncated)"
}

sealed interface MmsAttachmentListResult {
    data class Success(val value: MmsStaticImageList) : MmsAttachmentListResult
    data object InvalidMessageKind : MmsAttachmentListResult
    data object RoleRequired : MmsAttachmentListResult
    data object PermissionDenied : MmsAttachmentListResult
    data object Unavailable : MmsAttachmentListResult
}

class MmsAttachmentContent(
    val descriptor: MmsAttachmentDescriptor,
    val encodedLengthBytes: Long?,
    val stream: InputStream,
) {
    init {
        require(encodedLengthBytes == null || encodedLengthBytes >= 0L) {
            "Encoded attachment lengths cannot be negative"
        }
    }

    override fun toString(): String = "MmsAttachmentContent(REDACTED)"
}

fun interface MmsAttachmentContentReader<T> {
    fun read(content: MmsAttachmentContent): T
}

sealed interface MmsAttachmentReadResult<out T> {
    data class Success<T>(val value: T) : MmsAttachmentReadResult<T> {
        override fun toString(): String = "MmsAttachmentReadResult.Success(REDACTED)"
    }

    data object NotFound : MmsAttachmentReadResult<Nothing>
    data object RoleRequired : MmsAttachmentReadResult<Nothing>
    data object PermissionDenied : MmsAttachmentReadResult<Nothing>
    data object UnsupportedType : MmsAttachmentReadResult<Nothing>
    data object Unavailable : MmsAttachmentReadResult<Nothing>
}

interface MmsAttachmentRepository {
    suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult

    /** [reader] is invoked on the repository I/O context and the stream closes when it returns. */
    suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T>
}

val SUPPORTED_STATIC_MMS_IMAGE_MIME_TYPES: Set<String> = setOf(
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/heif",
    "image/heic",
    "image/avif",
)

const val MAXIMUM_VISIBLE_MMS_IMAGE_PARTS: Int = 25
