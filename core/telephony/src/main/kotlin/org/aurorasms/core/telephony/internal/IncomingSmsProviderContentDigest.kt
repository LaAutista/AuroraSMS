// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Redacted, domain-separated digest used only to identify an exact provider
 * row after a crash between the provider insert and journal checkpoint.
 */
internal class IncomingSmsProviderContentDigest private constructor(
    private val storageToken: String,
) {
    fun toStorageToken(): String = storageToken

    override fun equals(other: Any?): Boolean =
        other is IncomingSmsProviderContentDigest && storageToken == other.storageToken

    override fun hashCode(): Int = storageToken.hashCode()

    override fun toString(): String = "IncomingSmsProviderContentDigest(REDACTED)"

    companion object {
        private val DOMAIN = "AuroraSMS.INCOMING_PROVIDER_CONTENT.v1"
            .toByteArray(StandardCharsets.US_ASCII)
        private const val SHA_256_BYTES = 32
        private const val STORAGE_CHARACTERS = SHA_256_BYTES * 2
        private const val HEX_CHARACTERS = "0123456789abcdef"
        private val STORAGE_PATTERN = Regex("[0-9a-f]{$STORAGE_CHARACTERS}")

        fun fromContent(sender: String, body: String): IncomingSmsProviderContentDigest {
            val senderBytes = sender.toByteArray(StandardCharsets.UTF_8)
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(DOMAIN)
                updateInt(senderBytes.size)
                update(senderBytes)
                updateInt(bodyBytes.size)
                update(bodyBytes)
            }.digest()
            val token = buildString(STORAGE_CHARACTERS) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_CHARACTERS[value ushr 4])
                    append(HEX_CHARACTERS[value and 0x0f])
                }
            }
            return IncomingSmsProviderContentDigest(token)
        }

        fun fromStorageToken(token: String): IncomingSmsProviderContentDigest {
            require(STORAGE_PATTERN.matches(token)) { "Invalid incoming provider content digest" }
            return IncomingSmsProviderContentDigest(token)
        }

        private fun MessageDigest.updateInt(value: Int) {
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }
    }
}
