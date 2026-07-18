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

/** Inline-reply pending-operation IDs occupy this value and the range above it. */
const val INLINE_REPLY_OPERATION_ID_BOUNDARY: Long = 1L shl 62
