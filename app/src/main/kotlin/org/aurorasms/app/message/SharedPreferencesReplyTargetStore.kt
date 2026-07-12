// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress

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
        if (nowMillis < 0L || target.expiresAtMillis <= nowMillis) return false
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
        val encoded = preferences.getString(preferenceKey, null) ?: return null
        val target = decode(requestId, encoded)
        if (target == null || target.expiresAtMillis <= nowMillis) {
            preferences.edit().remove(preferenceKey).apply()
            return null
        }
        return target
    }

    @Synchronized
    override fun clear(): Boolean = preferences.edit().clear().commit()

    private fun decodedEntries(): List<StoredTarget> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
        val requestId = key.removePrefix(KEY_PREFIX)
        decode(requestId, value)?.let { target -> StoredTarget(key, target) }
    }

    private fun encode(target: ReplyTarget): String = listOf(
        FORMAT_VERSION,
        target.conversationId.value,
        target.subscriptionId.value,
        target.expiresAtMillis,
        Base64.encodeToString(
            target.recipient.value.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        ),
    ).joinToString(separator = SEPARATOR)

    private fun decode(requestId: String, encoded: String): ReplyTarget? {
        if (!isValidRequestId(requestId)) return null
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return null
        val conversationId = fields[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val subscriptionId = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val expiresAtMillis = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val recipient = runCatching {
            String(
                Base64.decode(fields[4], Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
        }.getOrNull()?.let { value ->
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

    private fun isValidRequestId(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)

    private data class StoredTarget(
        val key: String,
        val target: ReplyTarget,
    )

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_targets"
        const val KEY_PREFIX = "target."
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 5
        const val DEFAULT_MAXIMUM_ENTRIES = 4_096
        const val ABSOLUTE_MAXIMUM_ENTRIES = 16_384

        fun key(requestId: String): String = KEY_PREFIX + requestId
    }
}
