// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * A private, bounded at-most-once journal for notification replies.
 *
 * The platform-generated provider token is bound to its conversation,
 * subscription, target digest, and expiry. Recipient plaintext and message
 * bodies never enter this journal. Synchronous commits are intentional and the
 * receiver invokes this guard on its bounded IO path.
 */
@SuppressLint("UseKtx") // The KTX helper discards commit() failure; replay claims must fail closed.
class SharedPreferencesReplyReplayGuard(
    context: Context,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val maximumActiveClaims: Int = DEFAULT_MAXIMUM_ACTIVE_CLAIMS,
) : ReplyReplayGuard {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        require(retentionMillis > 0L) { "retentionMillis must be positive" }
        require(maximumActiveClaims in 1..ABSOLUTE_MAXIMUM_ACTIVE_CLAIMS) {
            "maximumActiveClaims is out of bounds"
        }
    }

    @Synchronized
    override fun claim(claim: ReplyReplayClaim, claimedAtMillis: Long): Boolean {
        if (
            !isValidRequestId(claim.requestId) ||
            claimedAtMillis < 0L ||
            claim.expiresAtMillis <= claimedAtMillis
        ) {
            return false
        }
        val cutoff = (claimedAtMillis - retentionMillis).coerceAtLeast(0L)
        val entries = preferences.all
        val storedClaims = ArrayList<StoredClaim>(entries.size)
        entries.forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return false
            val serialized = value as? String ?: return false
            val decoded = decode(key, serialized) ?: return false
            storedClaims += decoded
        }
        val key = KEY_PREFIX + claim.requestId
        if (storedClaims.any { stored -> stored.key == key && !stored.isExpired(claimedAtMillis, cutoff) }) {
            return false
        }

        val expired = storedClaims.filter { stored -> stored.isExpired(claimedAtMillis, cutoff) }
        val retainedCount = storedClaims.size - expired.size
        if (retainedCount >= maximumActiveClaims) return false

        val editor = preferences.edit()
        expired.forEach { stored -> editor.remove(stored.key) }
        val expiredKeys = expired.mapTo(HashSet(expired.size)) { stored -> stored.key }
        storedClaims.filter { stored -> stored.requiresMigration && stored.key !in expiredKeys }
            .forEach { stored -> editor.putString(stored.key, serialize(stored)) }
        return editor.putString(key, serialize(claim, claimedAtMillis)).commit()
    }

    private fun decode(key: String, serialized: String): StoredClaim? {
        val requestId = key.removePrefix(KEY_PREFIX)
            .takeIf(::isValidRequestId)
            ?: return null
        if (key != KEY_PREFIX + requestId) return null
        val fields = serialized.split(SEPARATOR)
        return when {
            fields.size == SERIALIZED_FIELD_COUNT && fields[0] == FORMAT_VERSION ->
                decodeCurrent(key, requestId, fields)
            fields.size == LEGACY_FIELD_COUNT -> decodeLegacy(key, requestId, fields)
            else -> null
        }
    }

    private fun decodeCurrent(
        key: String,
        requestId: String,
        fields: List<String>,
    ): StoredClaim? {
        if (!hasValidChecksum(fields)) return null
        val storedRequestId = decodeCanonicalText(fields[1])
            ?.takeIf(::isValidRequestId)
            ?: return null
        if (storedRequestId != requestId) return null
        val claimedAtMillis = fields[2].toLongOrNull()
            ?.takeIf { it >= 0L && it.toString() == fields[2] }
            ?: return null
        val conversationId = fields[3].toLongOrNull()
            ?.takeIf { it > 0L && it.toString() == fields[3] }
            ?: return null
        val subscriptionId = fields[4].toIntOrNull()
            ?.takeIf { it >= 0 && it.toString() == fields[4] }
            ?: return null
        val expiresAtMillis = fields[5].toLongOrNull()
            ?.takeIf {
                it > claimedAtMillis && it.toString() == fields[5]
            }
            ?: return null
        val recipientDigest = fields[6].takeIf(::isCanonicalSha256) ?: return null
        return StoredClaim(
            key = key,
            requestId = requestId,
            claimedAtMillis = claimedAtMillis,
            conversationId = conversationId,
            subscriptionId = subscriptionId,
            expiresAtMillis = expiresAtMillis,
            recipientDigest = recipientDigest,
            requiresMigration = false,
        )
    }

    private fun decodeLegacy(
        key: String,
        requestId: String,
        fields: List<String>,
    ): StoredClaim? {
        val claimedAtMillis = fields[0].toLongOrNull()
            ?.takeIf { it >= 0L && it.toString() == fields[0] }
            ?: return null
        val conversationId = fields[1].toLongOrNull()
            ?.takeIf { it > 0L && it.toString() == fields[1] }
            ?: return null
        val subscriptionId = fields[2].toIntOrNull()
            ?.takeIf { it >= 0 && it.toString() == fields[2] }
            ?: return null
        val expiresAtMillis = fields[3].toLongOrNull()
            ?.takeIf {
                it > claimedAtMillis && it.toString() == fields[3]
            }
            ?: return null
        val recipientDigest = fields[4].takeIf(::isCanonicalSha256) ?: return null
        return StoredClaim(
            key = key,
            requestId = requestId,
            claimedAtMillis = claimedAtMillis,
            conversationId = conversationId,
            subscriptionId = subscriptionId,
            expiresAtMillis = expiresAtMillis,
            recipientDigest = recipientDigest,
            requiresMigration = true,
        )
    }

    private fun serialize(claim: ReplyReplayClaim, claimedAtMillis: Long): String = serialize(
        requestId = claim.requestId,
        claimedAtMillis = claimedAtMillis,
        conversationId = claim.conversationId.value,
        subscriptionId = claim.subscriptionId.value,
        expiresAtMillis = claim.expiresAtMillis,
        recipientDigest = recipientDigest(claim.recipient.value),
    )

    private fun serialize(claim: StoredClaim): String = serialize(
        requestId = claim.requestId,
        claimedAtMillis = claim.claimedAtMillis,
        conversationId = claim.conversationId,
        subscriptionId = claim.subscriptionId,
        expiresAtMillis = claim.expiresAtMillis,
        recipientDigest = claim.recipientDigest,
    )

    private fun serialize(
        requestId: String,
        claimedAtMillis: Long,
        conversationId: Long,
        subscriptionId: Int,
        expiresAtMillis: Long,
        recipientDigest: String,
    ): String {
        val canonicalFields = listOf(
            FORMAT_VERSION,
            encodeText(requestId),
            claimedAtMillis.toString(),
            conversationId.toString(),
            subscriptionId.toString(),
            expiresAtMillis.toString(),
            recipientDigest,
        )
        return (canonicalFields + checksum(canonicalFields)).joinToString(separator = SEPARATOR)
    }

    private fun hasValidChecksum(fields: List<String>): Boolean {
        val storedChecksum = fields.last().takeIf(::isCanonicalSha256) ?: return false
        val expectedChecksum = checksum(fields.dropLast(1))
        return MessageDigest.isEqual(
            storedChecksum.toByteArray(Charsets.US_ASCII),
            expectedChecksum.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun checksum(canonicalFields: List<String>): String = sha256Hex(
        canonicalFields.joinToString(separator = SEPARATOR).toByteArray(Charsets.UTF_8),
    )

    private fun encodeText(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        BASE64_FLAGS,
    )

    private fun decodeCanonicalText(encoded: String): String? {
        val bytes = runCatching { Base64.decode(encoded, BASE64_FLAGS) }.getOrNull() ?: return null
        val decoded = String(bytes, Charsets.UTF_8)
        if (encodeText(decoded) != encoded) return null
        return decoded
    }

    private fun recipientDigest(value: String): String {
        return sha256Hex(value.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun isCanonicalSha256(value: String): Boolean =
        value.length == SHA_256_HEX_CHARACTERS && value.all { it in HEX }

    private fun isValidRequestId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAXIMUM_REQUEST_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private data class StoredClaim(
        val key: String,
        val requestId: String,
        val claimedAtMillis: Long,
        val conversationId: Long,
        val subscriptionId: Int,
        val expiresAtMillis: Long,
        val recipientDigest: String,
        val requiresMigration: Boolean,
    ) {
        fun isExpired(nowMillis: Long, retentionCutoff: Long): Boolean =
            claimedAtMillis < retentionCutoff && expiresAtMillis <= nowMillis
    }

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_replay"
        const val KEY_PREFIX = "claim."
        const val FORMAT_VERSION = "2"
        const val SEPARATOR = "|"
        const val MAXIMUM_REQUEST_ID_CHARACTERS = 256
        const val DEFAULT_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAXIMUM_ACTIVE_CLAIMS = 16_384
        const val ABSOLUTE_MAXIMUM_ACTIVE_CLAIMS = 65_536
        const val HEX = "0123456789abcdef"
        const val SHA_256_HEX_CHARACTERS = 64
        const val SERIALIZED_FIELD_COUNT = 8
        const val LEGACY_FIELD_COUNT = 5
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
    }
}
