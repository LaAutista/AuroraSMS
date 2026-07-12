// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/**
 * A SHA-256 fingerprint used only to make one platform delivery idempotent.
 *
 * The storage token is sensitive derived data. Callers may persist it in
 * private, backup-excluded state but must never log, display, or transmit it.
 */
class MessageDeliveryFingerprint private constructor(
    private val storageToken: String,
) {
    fun toStorageToken(): String = storageToken

    override fun equals(other: Any?): Boolean =
        other is MessageDeliveryFingerprint && storageToken == other.storageToken

    override fun hashCode(): Int = storageToken.hashCode()

    override fun toString(): String = "MessageDeliveryFingerprint(REDACTED)"

    companion object {
        const val SHA_256_BYTES: Int = 32
        private const val STORAGE_CHARACTERS: Int = SHA_256_BYTES * 2
        private val STORAGE_PATTERN = Regex("[0-9a-f]{$STORAGE_CHARACTERS}")

        fun fromSha256(digest: ByteArray): MessageDeliveryFingerprint {
            require(digest.size == SHA_256_BYTES) { "A delivery fingerprint must be SHA-256" }
            val token = buildString(STORAGE_CHARACTERS) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_CHARACTERS[value ushr 4])
                    append(HEX_CHARACTERS[value and 0x0f])
                }
            }
            return MessageDeliveryFingerprint(token)
        }

        fun fromStorageToken(token: String): MessageDeliveryFingerprint {
            require(STORAGE_PATTERN.matches(token)) { "Invalid delivery fingerprint storage token" }
            return MessageDeliveryFingerprint(token)
        }

        private const val HEX_CHARACTERS: String = "0123456789abcdef"
    }
}
