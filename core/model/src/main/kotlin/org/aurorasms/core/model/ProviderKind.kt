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
