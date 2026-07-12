// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A compound key for a row in an Android Telephony message provider. */
data class ProviderMessageId(
    val kind: ProviderKind,
    val value: Long,
) {
    init {
        require(kind.isTelephonyProvider) {
            "A provider message ID must be qualified as SMS or MMS"
        }
        require(value > 0L) { "Provider message IDs must be positive" }
    }

    fun asMessageId(): MessageId = MessageId(kind = kind, value = value)

    override fun toString(): String = "ProviderMessageId(kind=$kind, value=REDACTED)"
}
