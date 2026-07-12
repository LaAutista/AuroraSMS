// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId

/**
 * Small private journal for the provider-insert/notification boundary.
 *
 * Values contain only a redacted fingerprint, an independent opaque recovery
 * token, scalar timestamps/IDs, and state. Addresses, bodies, PDU bytes, and
 * SIM labels are never persisted.
 * The application backup rules exclude the complete shared-preference domain.
 */
// This boundary deliberately uses synchronous commit() for crash ordering.
// The dependency policy admits androidx.core, not the core-ktx edit helper.
@SuppressLint("UseKtx")
internal class IncomingSmsReplayJournal(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
    private val maximumEntries: Int = MAXIMUM_ENTRIES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newRecoveryToken: () -> String = { UUID.randomUUID().toString() },
) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    init {
        require(maximumEntries in 2..MAXIMUM_ENTRIES)
    }

    @Synchronized
    fun lookup(fingerprint: MessageDeliveryFingerprint): LookupResult {
        val value = preferences.getString(key(fingerprint), null) ?: return LookupResult.Missing
        return decode(fingerprint, value)?.let(LookupResult::Found) ?: LookupResult.Corrupt
    }

    @Synchronized
    fun begin(
        fingerprint: MessageDeliveryFingerprint,
        receivedTimestampMillis: Long,
        sentTimestampMillis: Long,
        subscriptionId: AuroraSubscriptionId?,
    ): Boolean {
        require(receivedTimestampMillis >= 0L && sentTimestampMillis >= 0L)
        val key = key(fingerprint)
        if (preferences.contains(key)) return false

        val all = decodedEntries()
        val removalsNeeded = (preferences.all.keys.count(::isEntryKey) - maximumEntries + 1)
            .coerceAtLeast(0)
        val removable = all
            .filter { it.state == State.COMPLETE }
            .sortedBy(Entry::updatedTimestampMillis)
            .take(removalsNeeded)
        if (removable.size < removalsNeeded) return false

        val recoveryToken = runCatching { newRecoveryToken() }
            .getOrNull()
            ?.takeIf(RECOVERY_TOKEN_PATTERN::matches)
            ?: return false
        val entry = Entry(
            fingerprint = fingerprint,
            providerRecoveryToken = recoveryToken,
            state = State.PENDING,
            providerId = null,
            conversationId = null,
            receivedTimestampMillis = receivedTimestampMillis,
            sentTimestampMillis = sentTimestampMillis,
            subscriptionId = subscriptionId,
            updatedTimestampMillis = nowMillis().coerceAtLeast(0L),
        )
        val editor = preferences.edit()
        removable.forEach { editor.remove(key(it.fingerprint)) }
        return editor.putString(key, encode(entry)).commit()
    }

    @Synchronized
    fun markStored(
        fingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = transition(
        fingerprint = fingerprint,
        providerId = providerId,
        conversationId = conversationId,
        target = State.STORED,
    )

    @Synchronized
    fun markComplete(
        fingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = transition(
        fingerprint = fingerprint,
        providerId = providerId,
        conversationId = conversationId,
        target = State.COMPLETE,
    )

    @Synchronized
    fun resetPending(fingerprint: MessageDeliveryFingerprint): Boolean {
        val current = when (val result = lookup(fingerprint)) {
            is LookupResult.Found -> result.entry
            LookupResult.Missing,
            LookupResult.Corrupt
            -> return false
        }
        if (current.state != State.STORED) return false
        val updated = current.copy(
            state = State.PENDING,
            providerId = null,
            conversationId = null,
            updatedTimestampMillis = nowMillis().coerceAtLeast(0L),
        )
        return preferences.edit().putString(key(fingerprint), encode(updated)).commit()
    }

    @Synchronized
    fun claimedProviderIds(excluding: MessageDeliveryFingerprint): Set<Long>? {
        val decoded = decodedEntriesOrNull() ?: return null
        return decoded
            .asSequence()
            .filter { it.fingerprint != excluding && it.state != State.PENDING }
            .mapNotNull { it.providerId?.value }
            .toSet()
    }

    @Synchronized
    internal fun clear(): Boolean = preferences.edit().clear().commit()

    private fun transition(
        fingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        target: State,
    ): Boolean {
        if (providerId.kind != ProviderKind.SMS) return false
        val current = when (val result = lookup(fingerprint)) {
            is LookupResult.Found -> result.entry
            LookupResult.Missing,
            LookupResult.Corrupt
            -> return false
        }
        if (current.providerId != null && current.providerId != providerId) return false
        if (current.conversationId != null && current.conversationId != conversationId) return false
        if (target == State.STORED && current.state == State.COMPLETE) return false
        val updated = current.copy(
            state = target,
            providerId = providerId,
            conversationId = conversationId,
            updatedTimestampMillis = nowMillis().coerceAtLeast(0L),
        )
        return preferences.edit().putString(key(fingerprint), encode(updated)).commit()
    }

    private fun decodedEntries(): List<Entry> = preferences.all.mapNotNull { (key, value) ->
        if (!isEntryKey(key) || value !is String) return@mapNotNull null
        val token = key.removePrefix(ENTRY_PREFIX)
        val fingerprint = runCatching {
            MessageDeliveryFingerprint.fromStorageToken(token)
        }.getOrNull() ?: return@mapNotNull null
        decode(fingerprint, value)
    }

    private fun decodedEntriesOrNull(): List<Entry>? {
        val entries = preferences.all.filterKeys(::isEntryKey)
        val decoded = entries.mapNotNull { (key, value) ->
            val encoded = value as? String ?: return@mapNotNull null
            val fingerprint = runCatching {
                MessageDeliveryFingerprint.fromStorageToken(key.removePrefix(ENTRY_PREFIX))
            }.getOrNull() ?: return@mapNotNull null
            decode(fingerprint, encoded)
        }
        return decoded.takeIf { it.size == entries.size }
    }

    private fun encode(entry: Entry): String = listOf(
        FORMAT_VERSION,
        entry.providerRecoveryToken,
        entry.state.code,
        entry.providerId?.value ?: 0L,
        entry.conversationId?.value ?: 0L,
        entry.receivedTimestampMillis,
        entry.sentTimestampMillis,
        entry.subscriptionId?.value ?: NO_SUBSCRIPTION,
        entry.updatedTimestampMillis,
    ).joinToString(SEPARATOR)

    private fun decode(
        fingerprint: MessageDeliveryFingerprint,
        encoded: String,
    ): Entry? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return null
        val recoveryToken = fields[1].takeIf(RECOVERY_TOKEN_PATTERN::matches) ?: return null
        val state = State.fromCode(fields[2]) ?: return null
        val providerValue = fields[3].toLongOrNull() ?: return null
        val conversationValue = fields[4].toLongOrNull() ?: return null
        val received = fields[5].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val sent = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val subscriptionValue = fields[7].toIntOrNull()?.takeIf { it >= NO_SUBSCRIPTION } ?: return null
        val updated = fields[8].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val hasStoredIds = providerValue > 0L && conversationValue > 0L
        if ((state == State.PENDING && (providerValue != 0L || conversationValue != 0L)) ||
            (state != State.PENDING && !hasStoredIds)
        ) {
            return null
        }
        return Entry(
            fingerprint = fingerprint,
            providerRecoveryToken = recoveryToken,
            state = state,
            providerId = providerValue.takeIf { it > 0L }?.let {
                ProviderMessageId(ProviderKind.SMS, it)
            },
            conversationId = conversationValue.takeIf { it > 0L }?.let(::ConversationId),
            receivedTimestampMillis = received,
            sentTimestampMillis = sent,
            subscriptionId = subscriptionValue.takeIf { it >= 0 }?.let(::AuroraSubscriptionId),
            updatedTimestampMillis = updated,
        )
    }

    data class Entry(
        val fingerprint: MessageDeliveryFingerprint,
        val providerRecoveryToken: String,
        val state: State,
        val providerId: ProviderMessageId?,
        val conversationId: ConversationId?,
        val receivedTimestampMillis: Long,
        val sentTimestampMillis: Long,
        val subscriptionId: AuroraSubscriptionId?,
        val updatedTimestampMillis: Long,
    )

    enum class State(val code: String) {
        PENDING("P"),
        STORED("S"),
        COMPLETE("C"),
        ;

        companion object {
            fun fromCode(code: String): State? = entries.firstOrNull { it.code == code }
        }
    }

    sealed interface LookupResult {
        data object Missing : LookupResult
        data object Corrupt : LookupResult
        data class Found(val entry: Entry) : LookupResult
    }

    companion object {
        private const val PREFERENCE_NAME = "aurora_sms_delivery_journal_v1"
        private const val ENTRY_PREFIX = "delivery."
        private const val FORMAT_VERSION = "2"
        private const val SEPARATOR = ","
        private const val FIELD_COUNT = 9
        private const val NO_SUBSCRIPTION = -1
        private val RECOVERY_TOKEN_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val MAXIMUM_ENTRIES = 512

        private fun key(fingerprint: MessageDeliveryFingerprint): String =
            ENTRY_PREFIX + fingerprint.toStorageToken()

        private fun isEntryKey(key: String): Boolean = key.startsWith(ENTRY_PREFIX)
    }
}
