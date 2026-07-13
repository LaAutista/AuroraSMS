// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.ui.graphics.ImageBitmap
import org.aurorasms.core.telephony.MmsAttachmentDescriptor
import org.aurorasms.core.telephony.MmsAttachmentId

data class StaticAttachmentPreview(
    val attachmentId: MmsAttachmentId,
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
    val allocationBytes: Int,
) {
    init {
        require(width in 1..MAXIMUM_PREVIEW_EDGE_PIXELS && height in 1..MAXIMUM_PREVIEW_EDGE_PIXELS) {
            "Attachment previews must remain dimension-bounded"
        }
        require(width.toLong() * height.toLong() <= MAXIMUM_PREVIEW_PIXELS) {
            "Attachment previews must remain pixel-bounded"
        }
        require(allocationBytes in 1..MAXIMUM_PREVIEW_CACHE_BYTES) {
            "Attachment preview allocations must fit the cache bound"
        }
    }

    override fun toString(): String =
        "StaticAttachmentPreview(width=$width, height=$height, allocationBytes=$allocationBytes, REDACTED)"
}

sealed interface AttachmentPreviewResult {
    data class Ready(val preview: StaticAttachmentPreview) : AttachmentPreviewResult
    data object NotFound : AttachmentPreviewResult
    data object RoleRequired : AttachmentPreviewResult
    data object PermissionDenied : AttachmentPreviewResult
    data object UnsupportedType : AttachmentPreviewResult
    data object EncodedInputTooLarge : AttachmentPreviewResult
    data object SourceDimensionsTooLarge : AttachmentPreviewResult
    data object Malformed : AttachmentPreviewResult
    data object Unavailable : AttachmentPreviewResult
}

interface BoundedPreviewLoader {
    suspend fun load(descriptor: MmsAttachmentDescriptor): AttachmentPreviewResult

    suspend fun clear()
}

const val MAXIMUM_PREVIEW_ENCODED_BYTES: Int = 16 * 1_024 * 1_024
const val MAXIMUM_PREVIEW_SOURCE_EDGE_PIXELS: Int = 8_192
const val MAXIMUM_PREVIEW_SOURCE_PIXELS: Long = 40_000_000L
const val MAXIMUM_PREVIEW_EDGE_PIXELS: Int = 2_048
const val MAXIMUM_PREVIEW_PIXELS: Long = 4_194_304L
const val MAXIMUM_CONCURRENT_PREVIEW_DECODES: Int = 2
const val MAXIMUM_PENDING_PREVIEW_LOADS: Int = 8
const val MAXIMUM_PREVIEW_CACHE_ENTRIES: Int = 4
const val MAXIMUM_PREVIEW_CACHE_BYTES: Int = 16 * 1_024 * 1_024
