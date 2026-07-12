// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/**
 * A stable identifier qualified by its owning namespace.
 *
 * A numeric SMS provider ID is therefore never equal to the same numeric MMS,
 * draft, scheduled-message, or pending-operation ID.
 */
data class MessageId(
    val kind: ProviderKind,
    val value: Long,
) {
    init {
        require(value > 0L) { "Message IDs must be positive" }
    }
}
