// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A valid thread identifier owned by Android's Telephony provider. */
@JvmInline
value class ProviderThreadId(val value: Long) {
    init {
        require(value > 0L) { "Provider thread IDs must be positive" }
    }

    override fun toString(): String = "ProviderThreadId(REDACTED)"
}
