// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/**
 * A SHA-256 fingerprint of the stable fields in one projected provider row.
 *
 * This private derived value may be persisted in Aurora's private index. It
 * must never be logged, displayed, or transmitted.
 */
class MessageSyncFingerprint private constructor(
    private val storageToken: String,
) {
    fun toStorageToken(): String = storageToken

    override fun equals(other: Any?): Boolean =
        other is MessageSyncFingerprint && storageToken == other.storageToken

    override fun hashCode(): Int = storageToken.hashCode()

    override fun toString(): String = "MessageSyncFingerprint(REDACTED)"

    companion object {
        const val SHA_256_BYTES: Int = 32
        private const val STORAGE_CHARACTERS: Int = SHA_256_BYTES * 2
        private const val HEX_CHARACTERS: String = "0123456789abcdef"
        private val STORAGE_PATTERN = Regex("[0-9a-f]{$STORAGE_CHARACTERS}")

        fun fromSha256(digest: ByteArray): MessageSyncFingerprint {
            require(digest.size == SHA_256_BYTES) { "A sync fingerprint must be SHA-256" }
            val token = buildString(STORAGE_CHARACTERS) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_CHARACTERS[value ushr 4])
                    append(HEX_CHARACTERS[value and 0x0f])
                }
            }
            return MessageSyncFingerprint(token)
        }

        fun fromStorageToken(token: String): MessageSyncFingerprint {
            require(STORAGE_PATTERN.matches(token)) { "Invalid sync fingerprint storage token" }
            return MessageSyncFingerprint(token)
        }
    }
}
