// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
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
        val storedClaims = preferences.all.mapNotNull { (key, value) ->
            val serialized = value as? String ?: return@mapNotNull null
            key.takeIf { it.startsWith(KEY_PREFIX) }
                ?.let { storedKey -> decode(storedKey, serialized) }
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
        return editor.putString(key, serialize(claim, claimedAtMillis)).commit()
    }

    private fun decode(key: String, serialized: String): StoredClaim? {
        val fields = serialized.split('|')
        if (fields.size != SERIALIZED_FIELD_COUNT) return null
        val claimedAtMillis = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val conversationId = fields[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val subscriptionId = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val expiresAtMillis = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val recipientDigest = fields[4].takeIf { digest ->
            digest.length == SHA_256_HEX_CHARACTERS && digest.all { it in HEX }
        } ?: return null
        return StoredClaim(
            key = key,
            claimedAtMillis = claimedAtMillis,
            conversationId = conversationId,
            subscriptionId = subscriptionId,
            expiresAtMillis = expiresAtMillis,
            recipientDigest = recipientDigest,
        )
    }

    private fun serialize(claim: ReplyReplayClaim, claimedAtMillis: Long): String = listOf(
        claimedAtMillis,
        claim.conversationId.value,
        claim.subscriptionId.value,
        claim.expiresAtMillis,
        recipientDigest(claim.recipient.value),
    ).joinToString(separator = "|")

    private fun recipientDigest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun isValidRequestId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAXIMUM_REQUEST_ID_CHARACTERS &&
            value.none(Char::isISOControl)

    private data class StoredClaim(
        val key: String,
        val claimedAtMillis: Long,
        @Suppress("unused") val conversationId: Long,
        @Suppress("unused") val subscriptionId: Int,
        val expiresAtMillis: Long,
        @Suppress("unused") val recipientDigest: String,
    ) {
        fun isExpired(nowMillis: Long, retentionCutoff: Long): Boolean =
            claimedAtMillis < retentionCutoff && expiresAtMillis <= nowMillis
    }

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_replay"
        const val KEY_PREFIX = "claim."
        const val MAXIMUM_REQUEST_ID_CHARACTERS = 256
        const val DEFAULT_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAXIMUM_ACTIVE_CLAIMS = 16_384
        const val ABSOLUTE_MAXIMUM_ACTIVE_CLAIMS = 65_536
        const val HEX = "0123456789abcdef"
        const val SHA_256_HEX_CHARACTERS = 64
        const val SERIALIZED_FIELD_COUNT = 5
    }
}
