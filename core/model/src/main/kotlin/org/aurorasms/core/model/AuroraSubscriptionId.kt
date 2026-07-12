// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A validated Android subscription ID; the platform invalid sentinel is excluded. */
@JvmInline
value class AuroraSubscriptionId(val value: Int) {
    init {
        require(value >= 0) { "Subscription IDs cannot use the platform invalid sentinel" }
    }

    override fun toString(): String = "AuroraSubscriptionId(REDACTED)"
}
