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

/** Incoming MMS downloads occupy this value up to [COMPOSER_OPERATION_ID_BOUNDARY]. */
const val INCOMING_MMS_OPERATION_ID_BOUNDARY: Long = 1L shl 60

/** Existing-thread composer operations occupy this value up to the inline-reply boundary. */
const val COMPOSER_OPERATION_ID_BOUNDARY: Long = 1L shl 61

/** Inline-reply pending-operation IDs occupy this value and the range above it. */
const val INLINE_REPLY_OPERATION_ID_BOUNDARY: Long = 1L shl 62

/**
 * Numeric region assigned to each pending-operation owner.
 *
 * This classification is not proof that a durable owner actually owns an ID.
 * In particular, transport journals created before these partitions can contain
 * grandfathered IDs in a newer numeric region. Callers must consult durable
 * owner membership before routing or mutating an inherited operation.
 */
enum class PendingOperationNamespace {
    RESPOND_VIA,
    INCOMING_MMS,
    COMPOSER,
    INLINE_REPLY,
}

/** Classifies a positive pending-operation ID without authorizing ownership. */
fun MessageId.pendingOperationNamespaceOrNull(): PendingOperationNamespace? {
    if (kind != ProviderKind.PENDING_OPERATION || value <= 0L) return null
    return when {
        value < INCOMING_MMS_OPERATION_ID_BOUNDARY -> PendingOperationNamespace.RESPOND_VIA
        value < COMPOSER_OPERATION_ID_BOUNDARY -> PendingOperationNamespace.INCOMING_MMS
        value < INLINE_REPLY_OPERATION_ID_BOUNDARY -> PendingOperationNamespace.COMPOSER
        else -> PendingOperationNamespace.INLINE_REPLY
    }
}
