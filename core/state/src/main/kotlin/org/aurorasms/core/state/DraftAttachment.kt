// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.util.Arrays

/** One metadata-stripped image retained only while its exact draft remains. */
class DraftAttachment private constructor(
    val contentType: String,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    val size: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DraftAttachment &&
            contentType == other.contentType &&
            Arrays.equals(bytes, other.bytes)

    override fun hashCode(): Int = 31 * contentType.hashCode() + Arrays.hashCode(bytes)

    override fun toString(): String =
        "DraftAttachment(contentType=$contentType, size=$size, content=REDACTED)"

    companion object {
        const val IMAGE_JPEG: String = "image/jpeg"
        const val IMAGE_PNG: String = "image/png"
        const val MAX_BYTES: Int = 786_432
        const val MAX_ATTACHMENTS: Int = 10
        const val MAX_BYTES_TOTAL: Long = 917_504L

        val SUPPORTED_CONTENT_TYPES: Set<String> = setOf(IMAGE_JPEG, IMAGE_PNG)

        fun create(contentType: String, bytes: ByteArray): CreationResult = when {
            contentType !in SUPPORTED_CONTENT_TYPES ->
                CreationResult.Rejected(CreationResult.Reason.UNSUPPORTED_CONTENT_TYPE)
            bytes.isEmpty() -> CreationResult.Rejected(CreationResult.Reason.EMPTY)
            bytes.size > MAX_BYTES -> CreationResult.Rejected(CreationResult.Reason.TOO_LARGE)
            else -> CreationResult.Valid(DraftAttachment(contentType, bytes))
        }

        fun isValidSet(attachments: List<DraftAttachment>): Boolean =
            attachments.size <= MAX_ATTACHMENTS &&
                attachments.sumOf { it.size.toLong() } <= MAX_BYTES_TOTAL
    }

    sealed interface CreationResult {
        data class Valid(val attachment: DraftAttachment) : CreationResult
        data class Rejected(val reason: Reason) : CreationResult

        enum class Reason {
            EMPTY,
            TOO_LARGE,
            UNSUPPORTED_CONTENT_TYPE,
        }
    }
}

/** Draft attachment mutations are exact-revision compare-and-swap operations. */
interface DraftAttachmentRepository {
    suspend fun read(draftId: DraftId): DraftRepositoryResult<List<DraftAttachment>>

    suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>>
}
