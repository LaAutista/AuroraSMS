// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import java.security.MessageDigest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId

internal data class NotificationReminderOwner(
    val id: NotificationReminderId,
    val messageId: ProviderMessageId,
    val conversationId: ConversationId,
    val dueTimestampMillis: Long,
    val createdTimestampMillis: Long,
) {
    override fun toString(): String = "NotificationReminderOwner(content=REDACTED)"
}

internal data class NotificationReminderCreation(
    val owner: NotificationReminderOwner,
    val displaced: List<NotificationReminderOwner>,
)

internal interface NotificationReminderStore {
    fun create(
        messageId: ProviderMessageId,
        conversationId: ConversationId,
        dueTimestampMillis: Long,
        createdTimestampMillis: Long,
    ): NotificationReminderCreation?

    fun read(id: NotificationReminderId): NotificationReminderOwner?
    fun all(): List<NotificationReminderOwner>?
    fun remove(id: NotificationReminderId): Boolean
    fun removeConversation(conversationId: ConversationId): List<NotificationReminderOwner>?
    fun clear(): List<NotificationReminderOwner>?
}

@SuppressLint("ApplySharedPref", "UseKtx")
internal class SharedPreferencesNotificationReminderStore(
    context: Context,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) : NotificationReminderStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        require(maximumEntries in 1..ABSOLUTE_MAXIMUM_ENTRIES)
    }

    @Synchronized
    override fun create(
        messageId: ProviderMessageId,
        conversationId: ConversationId,
        dueTimestampMillis: Long,
        createdTimestampMillis: Long,
    ): NotificationReminderCreation? {
        if (
            messageId.kind != ProviderKind.SMS ||
            dueTimestampMillis <= createdTimestampMillis ||
            createdTimestampMillis < 0L
        ) return null
        val existing = decodedEntries() ?: return null
        val nextId = nextId(existing) ?: return null
        val owner = NotificationReminderOwner(
            id = NotificationReminderId(nextId),
            messageId = messageId,
            conversationId = conversationId,
            dueTimestampMillis = dueTimestampMillis,
            createdTimestampMillis = createdTimestampMillis,
        )
        val sameConversation = existing.filter { it.conversationId == conversationId }
        val retained = existing.filterNot { it.conversationId == conversationId }
        val evicted = retained.sortedBy { it.createdTimestampMillis }
            .take((retained.size - maximumEntries + 1).coerceAtLeast(0))
        val displaced = sameConversation + evicted
        val editor = preferences.edit().putLong(NEXT_ID_KEY, nextId)
        displaced.forEach { editor.remove(key(it.id)) }
        if (!editor.putString(key(owner.id), encode(owner)).commit()) return null
        return NotificationReminderCreation(owner, displaced)
    }

    @Synchronized
    override fun read(id: NotificationReminderId): NotificationReminderOwner? {
        val encoded = preferences.all[key(id)] as? String ?: return null
        return decode(id, encoded) ?: run {
            preferences.edit().remove(key(id)).commit()
            null
        }
    }

    @Synchronized
    override fun all(): List<NotificationReminderOwner>? = decodedEntries()

    @Synchronized
    override fun remove(id: NotificationReminderId): Boolean =
        preferences.edit().remove(key(id)).commit()

    @Synchronized
    override fun removeConversation(
        conversationId: ConversationId,
    ): List<NotificationReminderOwner>? {
        val matching = decodedEntries()?.filter { it.conversationId == conversationId } ?: return null
        if (matching.isEmpty()) return emptyList()
        val editor = preferences.edit()
        matching.forEach { editor.remove(key(it.id)) }
        return matching.takeIf { editor.commit() }
    }

    @Synchronized
    override fun clear(): List<NotificationReminderOwner>? {
        val entries = decodedEntries() ?: return null
        val editor = preferences.edit()
        entries.forEach { editor.remove(key(it.id)) }
        // Preserve the monotonic ID so a delayed stale PendingIntent can never
        // alias a later reminder created after a clear or role transition.
        return entries.takeIf { editor.commit() }
    }

    private fun decodedEntries(): List<NotificationReminderOwner>? {
        val entries = mutableListOf<NotificationReminderOwner>()
        for ((key, value) in preferences.all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val rawId = key.removePrefix(KEY_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L && it.toString() == key.removePrefix(KEY_PREFIX) }
                ?: return null
            val encoded = value as? String ?: return null
            entries += decode(NotificationReminderId(rawId), encoded) ?: return null
        }
        return entries
    }

    private fun nextId(existing: List<NotificationReminderOwner>): Long? {
        val stored = preferences.getLong(NEXT_ID_KEY, 0L).coerceAtLeast(0L)
        val maximum = maxOf(stored, existing.maxOfOrNull { it.id.value } ?: 0L)
        return (maximum + 1L).takeIf { maximum < Long.MAX_VALUE }
    }

    private fun encode(owner: NotificationReminderOwner): String {
        val fields = listOf(
            FORMAT_VERSION,
            owner.id.value.toString(),
            owner.messageId.kind.name,
            owner.messageId.value.toString(),
            owner.conversationId.value.toString(),
            owner.dueTimestampMillis.toString(),
            owner.createdTimestampMillis.toString(),
        )
        return (fields + checksum(fields)).joinToString(SEPARATOR)
    }

    private fun decode(id: NotificationReminderId, encoded: String): NotificationReminderOwner? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields.first() != FORMAT_VERSION) return null
        val expected = checksum(fields.dropLast(1))
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                fields.last().toByteArray(Charsets.US_ASCII),
            )
        ) return null
        val storedId = canonicalPositiveLong(fields[1]) ?: return null
        if (storedId != id.value || fields[2] != ProviderKind.SMS.name) return null
        val messageValue = canonicalPositiveLong(fields[3]) ?: return null
        val conversationValue = canonicalPositiveLong(fields[4]) ?: return null
        val due = canonicalPositiveLong(fields[5]) ?: return null
        val created = fields[6].toLongOrNull()?.takeIf { it >= 0L && it.toString() == fields[6] }
            ?: return null
        if (due <= created) return null
        return NotificationReminderOwner(
            id = id,
            messageId = ProviderMessageId(ProviderKind.SMS, messageValue),
            conversationId = ConversationId(conversationValue),
            dueTimestampMillis = due,
            createdTimestampMillis = created,
        )
    }

    private fun checksum(fields: List<String>): String {
        val bytes = fields.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun canonicalPositiveLong(value: String): Long? =
        value.toLongOrNull()?.takeIf { it > 0L && it.toString() == value }

    private companion object {
        const val PREFERENCES_NAME = "aurora_notification_reminder_owners_v1"
        const val KEY_PREFIX = "reminder."
        const val NEXT_ID_KEY = "next_id"
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 8
        const val DEFAULT_MAXIMUM_ENTRIES = 64
        const val ABSOLUTE_MAXIMUM_ENTRIES = 256

        fun key(id: NotificationReminderId): String = KEY_PREFIX + id.value
    }
}
