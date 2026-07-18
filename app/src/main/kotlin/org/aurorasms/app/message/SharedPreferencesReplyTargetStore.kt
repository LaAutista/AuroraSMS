// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress
import java.security.MessageDigest

/**
 * Private process-death-safe reply routing state.
 *
 * Targets must contain the recipient because a notification reply can start a
 * fresh app process. The file is local-only, excluded from backup/transfer,
 * bounded, cleared on role loss, and never logged.
 */
@SuppressLint("UseKtx") // The KTX helper discards commit() failure; reply routing must fail closed.
class SharedPreferencesReplyTargetStore(
    context: Context,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) : ReplyTargetStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        require(maximumEntries in 1..ABSOLUTE_MAXIMUM_ENTRIES) {
            "maximumEntries is out of bounds"
        }
    }

    @Synchronized
    override fun put(target: ReplyTarget, nowMillis: Long): Boolean {
        if (
            !isValidRequestId(target.requestId) ||
            nowMillis < 0L ||
            target.expiresAtMillis <= nowMillis
        ) {
            return false
        }
        val stored = decodedEntries()
        val validKeys = stored.mapTo(mutableSetOf()) { it.key }
        val malformedKeys = preferences.all.keys.filter { key ->
            key.startsWith(KEY_PREFIX) && key !in validKeys
        }
        val expired = stored.filter { it.target.expiresAtMillis <= nowMillis }
        val active = stored.filter { entry ->
            entry.target.expiresAtMillis > nowMillis && entry.target.requestId != target.requestId
        }
        val evictions = active.sortedBy { it.target.expiresAtMillis }
            .take((active.size - maximumEntries + 1).coerceAtLeast(0))

        val editor = preferences.edit()
        malformedKeys.forEach(editor::remove)
        expired.forEach { editor.remove(it.key) }
        evictions.forEach { editor.remove(it.key) }
        return editor.putString(key(target.requestId), encode(target)).commit()
    }

    @Synchronized
    override fun get(requestId: String, nowMillis: Long): ReplyTarget? {
        if (nowMillis < 0L) return null
        val preferenceKey = key(requestId)
        val stored = preferences.all[preferenceKey] ?: return null
        val encoded = stored as? String
        if (encoded == null) {
            preferences.edit().remove(preferenceKey).apply()
            return null
        }
        val target = decode(requestId, encoded)
        if (target == null || target.expiresAtMillis <= nowMillis) {
            preferences.edit().remove(preferenceKey).apply()
            return null
        }
        return target
    }

    @Synchronized
    override fun remove(requestId: String, conversationId: ConversationId): Boolean {
        if (!isValidRequestId(requestId)) return false
        val preferenceKey = key(requestId)
        val stored = preferences.all[preferenceKey] ?: return true
        val encoded = stored as? String ?: return false
        val target = decode(requestId, encoded) ?: return false
        if (target.conversationId != conversationId) return false
        return preferences.edit().remove(preferenceKey).commit()
    }

    @Synchronized
    override fun clear(): Boolean = preferences.edit().clear().commit()

    private fun decodedEntries(): List<StoredTarget> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
        val requestId = key.removePrefix(KEY_PREFIX)
        decode(requestId, value)?.let { target -> StoredTarget(key, target) }
    }

    private fun encode(target: ReplyTarget): String {
        val canonicalFields = listOf(
            FORMAT_VERSION,
            encodeText(target.requestId),
            target.conversationId.value.toString(),
            target.subscriptionId.value.toString(),
            target.expiresAtMillis.toString(),
            encodeText(target.recipient.value),
        )
        return (canonicalFields + checksum(canonicalFields)).joinToString(separator = SEPARATOR)
    }

    private fun decode(requestId: String, encoded: String): ReplyTarget? {
        if (!isValidRequestId(requestId)) return null
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return null
        if (!hasValidChecksum(fields)) return null
        val storedRequestId = decodeCanonicalText(fields[1])
            ?.takeIf(::isValidRequestId)
            ?: return null
        if (storedRequestId != requestId) return null
        val conversationId = fields[2].toLongOrNull()
            ?.takeIf { it > 0L && it.toString() == fields[2] }
            ?: return null
        val subscriptionId = fields[3].toIntOrNull()
            ?.takeIf { it >= 0 && it.toString() == fields[3] }
            ?: return null
        val expiresAtMillis = fields[4].toLongOrNull()
            ?.takeIf { it > 0L && it.toString() == fields[4] }
            ?: return null
        val recipient = decodeCanonicalText(fields[5])?.let { value ->
            runCatching { ParticipantAddress(value) }.getOrNull()
        } ?: return null
        return ReplyTarget(
            requestId = requestId,
            conversationId = ConversationId(conversationId),
            recipient = recipient,
            subscriptionId = AuroraSubscriptionId(subscriptionId),
            expiresAtMillis = expiresAtMillis,
        )
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
        value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)

    private data class StoredTarget(
        val key: String,
        val target: ReplyTarget,
    )

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_targets"
        const val KEY_PREFIX = "target."
        const val FORMAT_VERSION = "2"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 7
        const val DEFAULT_MAXIMUM_ENTRIES = 4_096
        const val ABSOLUTE_MAXIMUM_ENTRIES = 16_384
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        const val HEX = "0123456789abcdef"
        const val SHA_256_HEX_CHARACTERS = 64

        fun key(requestId: String): String = KEY_PREFIX + requestId
    }
}
