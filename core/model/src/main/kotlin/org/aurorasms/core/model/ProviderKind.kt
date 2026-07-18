// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** The namespace that owns a message-like identifier. */
enum class ProviderKind {
    SMS,
    MMS,
    DRAFT,
    SCHEDULED,
    PENDING_OPERATION,
    ;

    val isTelephonyProvider: Boolean
        get() = this == SMS || this == MMS
}

/**
 * Existing-thread composer operations occupy this value up to (but excluding)
 * [INLINE_REPLY_OPERATION_ID_BOUNDARY]. Transport-owned operations created by
 * current builds remain below this boundary.
 */
const val COMPOSER_OPERATION_ID_BOUNDARY: Long = 1L shl 61

/** Inline-reply pending-operation IDs occupy this value and the range above it. */
const val INLINE_REPLY_OPERATION_ID_BOUNDARY: Long = 1L shl 62

/**
 * Numeric region assigned to future pending-operation allocations.
 *
 * This classification is not proof that a durable owner actually owns an ID.
 * In particular, transport journals created before the composer partition can
 * contain grandfathered IDs in [COMPOSER]. Callers must consult durable owner
 * membership before routing or mutating an operation.
 */
enum class PendingOperationNamespace {
    RESPOND_VIA,
    COMPOSER,
    INLINE_REPLY,
}

/** Classifies a positive pending-operation ID without authorizing ownership. */
fun MessageId.pendingOperationNamespaceOrNull(): PendingOperationNamespace? {
    if (kind != ProviderKind.PENDING_OPERATION || value <= 0L) return null
    return when {
        value < COMPOSER_OPERATION_ID_BOUNDARY -> PendingOperationNamespace.RESPOND_VIA
        value < INLINE_REPLY_OPERATION_ID_BOUNDARY -> PendingOperationNamespace.COMPOSER
        else -> PendingOperationNamespace.INLINE_REPLY
    }
}
