// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
@SuppressLint("ApplySharedPref", "UseKtx")
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
        val entryKey = key(fingerprint)
        val value = preferences.all[entryKey] ?: return LookupResult.Missing
        if (value !is String) return LookupResult.Corrupt
        decodeStoredEntry(entryKey, fingerprint, value)?.let {
            return LookupResult.Found(it)
        }
        decodeQuarantine(fingerprint.toStorageToken(), value)?.let {
            return LookupResult.Quarantined(
                QuarantineEntry(
                    fingerprint = fingerprint,
                    reason = it.reason,
                    quarantinedTimestampMillis = it.quarantinedTimestampMillis,
                ),
            )
        }
        return LookupResult.Corrupt
    }

    @Synchronized
    fun begin(
        fingerprint: MessageDeliveryFingerprint,
        receivedTimestampMillis: Long,
        sentTimestampMillis: Long,
        subscriptionId: AuroraSubscriptionId?,
        providerContentDigest: IncomingSmsProviderContentDigest,
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
            providerContentDigest = providerContentDigest,
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
            LookupResult.Corrupt,
            is LookupResult.Quarantined,
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

    /**
     * Permanently retires an owned fingerprint whose exact provider evidence
     * can no longer be trusted. The tombstone remains under the original key,
     * so neither [lookup] nor [begin] can mistake it for a new delivery.
     */
    @Synchronized
    fun quarantine(
        fingerprint: MessageDeliveryFingerprint,
        reason: QuarantineReason,
    ): Boolean {
        val entryKey = key(fingerprint)
        val current = preferences.all[entryKey] ?: return false
        if (
            current is String &&
            decodeQuarantine(fingerprint.toStorageToken(), current) != null
        ) {
            return true
        }
        return preferences.edit().putString(
            entryKey,
            encodeQuarantine(
                storageToken = fingerprint.toStorageToken(),
                reason = reason,
                quarantinedTimestampMillis = nowMillis().coerceAtLeast(0L),
            ),
        ).commit()
    }

    /**
     * Returns a bounded, deterministic snapshot of deliveries that crossed the
     * provider-insert boundary but not the notification-complete boundary.
     * Malformed entries are atomically replaced by durable, key-bound
     * tombstones. They keep ownership of their original storage keys while
     * unrelated valid entries remain recoverable.
     */
    @Synchronized
    fun recoveryEntries(limit: Int): RecoveryEntriesResult {
        require(limit in 1..MAXIMUM_ENTRIES)
        val decoded = ArrayList<Entry>()
        val quarantines = LinkedHashMap<String, String>()
        preferences.all.filterKeys(::isEntryKey).forEach { (entryKey, value) ->
            val storageToken = entryKey.removePrefix(ENTRY_PREFIX)
            val fingerprint = runCatching {
                MessageDeliveryFingerprint.fromStorageToken(storageToken)
            }.getOrNull()
            val entry = if (fingerprint != null && value is String) {
                decodeStoredEntry(entryKey, fingerprint, value)
            } else {
                null
            }
            when {
                entry != null -> decoded += entry
                value is String && decodeQuarantine(storageToken, value) != null -> Unit
                else -> quarantines[entryKey] = encodeQuarantine(
                    storageToken = storageToken,
                    reason = QuarantineReason.MALFORMED_JOURNAL_RECORD,
                    quarantinedTimestampMillis = nowMillis().coerceAtLeast(0L),
                )
            }
        }
        if (quarantines.isNotEmpty()) {
            val editor = preferences.edit()
            quarantines.forEach(editor::putString)
            if (!editor.commit()) return RecoveryEntriesResult.Corrupt
        }
        return RecoveryEntriesResult.Success(
            decoded
                .asSequence()
                // Legacy v2 PENDING entries have no provider-content digest,
                // but still own an unresolved delivery and must never look
                // indistinguishable from a fully drained journal.
                .filter { entry -> entry.state != State.COMPLETE }
                .sortedWith(
                    compareBy<Entry>(Entry::updatedTimestampMillis)
                        .thenBy { it.fingerprint.toStorageToken() },
                )
                .take(limit)
                .toList(),
        )
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
            LookupResult.Corrupt,
            is LookupResult.Quarantined,
            -> return false
        }
        if (current.providerId != null && current.providerId != providerId) return false
        if (current.conversationId != null && current.conversationId != conversationId) return false
        val transitionAllowed = when (target) {
            State.STORED -> current.state == State.PENDING || current.state == State.STORED
            State.COMPLETE -> current.state == State.STORED || current.state == State.COMPLETE
            State.PENDING -> false
        }
        if (!transitionAllowed) return false
        val updated = current.copy(
            state = target,
            providerId = providerId,
            conversationId = conversationId,
            updatedTimestampMillis = nowMillis().coerceAtLeast(0L),
        )
        return preferences.edit().putString(key(fingerprint), encode(updated)).commit()
    }

    private fun decodedEntries(): List<Entry> = preferences.all.mapNotNull { (entryKey, value) ->
        if (!isEntryKey(entryKey) || value !is String) return@mapNotNull null
        val token = entryKey.removePrefix(ENTRY_PREFIX)
        val fingerprint = runCatching {
            MessageDeliveryFingerprint.fromStorageToken(token)
        }.getOrNull() ?: return@mapNotNull null
        decodeStoredEntry(entryKey, fingerprint, value)
    }

    private fun decodedEntriesOrNull(): List<Entry>? {
        val decoded = ArrayList<Entry>()
        for ((entryKey, value) in preferences.all.filterKeys(::isEntryKey)) {
            val encoded = value as? String ?: return null
            val storageToken = entryKey.removePrefix(ENTRY_PREFIX)
            val fingerprint = runCatching {
                MessageDeliveryFingerprint.fromStorageToken(storageToken)
            }.getOrNull()
            if (fingerprint != null) {
                decodeStoredEntry(entryKey, fingerprint, encoded)?.let {
                    decoded += it
                    continue
                }
            }
            if (decodeQuarantine(storageToken, encoded) == null) return null
        }
        return decoded
    }

    private fun decodeStoredEntry(
        entryKey: String,
        fingerprint: MessageDeliveryFingerprint,
        encoded: String,
    ): Entry? {
        val decoded = decode(fingerprint, encoded) ?: return null
        if (decoded.requiresMigration) {
            // A failed best-effort rewrite leaves the valid legacy value intact
            // so a later read or state transition can retry migration.
            preferences.edit().putString(entryKey, encode(decoded.entry)).commit()
        }
        return decoded.entry
    }

    private fun encode(entry: Entry): String {
        val payload = canonicalPayload(entry)
        val checksum = checksum(entry.fingerprint.toStorageToken(), payload)
        return listOf(payload, checksum).joinToString(SEPARATOR)
    }

    private fun encodeQuarantine(
        storageToken: String,
        reason: QuarantineReason,
        quarantinedTimestampMillis: Long,
    ): String {
        val payload = listOf(
            QUARANTINE_FORMAT_VERSION,
            reason.code,
            quarantinedTimestampMillis,
        ).joinToString(SEPARATOR)
        return listOf(
            payload,
            checksum(
                storageToken = storageToken,
                payload = payload,
                domain = QUARANTINE_CHECKSUM_DOMAIN,
            ),
        ).joinToString(SEPARATOR)
    }

    private fun decodeQuarantine(
        storageToken: String,
        encoded: String,
    ): DecodedQuarantine? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != QUARANTINE_FIELD_COUNT || fields[0] != QUARANTINE_FORMAT_VERSION) {
            return null
        }
        val reason = QuarantineReason.fromCode(fields[1]) ?: return null
        val quarantinedAt = fields[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val actualChecksum = fields[3].takeIf(SHA_256_PATTERN::matches) ?: return null
        val payload = fields.dropLast(1).joinToString(SEPARATOR)
        val expectedChecksum = checksum(
            storageToken = storageToken,
            payload = payload,
            domain = QUARANTINE_CHECKSUM_DOMAIN,
        )
        if (!MessageDigest.isEqual(
                actualChecksum.toByteArray(StandardCharsets.US_ASCII),
                expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            return null
        }
        val decoded = DecodedQuarantine(reason, quarantinedAt)
        return decoded.takeIf {
            encodeQuarantine(storageToken, it.reason, it.quarantinedTimestampMillis) == encoded
        }
    }

    private fun canonicalPayload(entry: Entry): String = listOf(
        FORMAT_VERSION,
        entry.providerRecoveryToken,
        entry.state.code,
        entry.providerId?.value ?: 0L,
        entry.conversationId?.value ?: 0L,
        entry.receivedTimestampMillis,
        entry.sentTimestampMillis,
        entry.subscriptionId?.value ?: NO_SUBSCRIPTION,
        entry.updatedTimestampMillis,
        entry.providerContentDigest?.toStorageToken() ?: NO_PROVIDER_CONTENT_DIGEST,
    ).joinToString(SEPARATOR)

    private fun legacyPayload(entry: Entry, version: String): String {
        val fields = mutableListOf(
            version,
            entry.providerRecoveryToken,
            entry.state.code,
            (entry.providerId?.value ?: 0L).toString(),
            (entry.conversationId?.value ?: 0L).toString(),
            entry.receivedTimestampMillis.toString(),
            entry.sentTimestampMillis.toString(),
            (entry.subscriptionId?.value ?: NO_SUBSCRIPTION).toString(),
            entry.updatedTimestampMillis.toString(),
        )
        if (version == LEGACY_FORMAT_VERSION_WITH_CONTENT_DIGEST) {
            fields += entry.providerContentDigest?.toStorageToken() ?: return ""
        }
        return fields.joinToString(SEPARATOR)
    }

    private fun decode(
        fingerprint: MessageDeliveryFingerprint,
        encoded: String,
    ): DecodedEntry? {
        val fields = encoded.split(SEPARATOR)
        val version = fields.firstOrNull() ?: return null
        when (version) {
            FORMAT_VERSION -> {
                if (fields.size != FIELD_COUNT) return null
                val actualChecksum = fields.last()
                    .takeIf(SHA_256_PATTERN::matches)
                    ?: return null
                val payload = fields.dropLast(1).joinToString(SEPARATOR)
                val expectedChecksum = checksum(fingerprint.toStorageToken(), payload)
                if (!MessageDigest.isEqual(
                        actualChecksum.toByteArray(StandardCharsets.US_ASCII),
                        expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                    )
                ) {
                    return null
                }
            }
            LEGACY_FORMAT_VERSION_WITH_CONTENT_DIGEST ->
                if (fields.size != LEGACY_FIELD_COUNT_WITH_CONTENT_DIGEST) return null
            LEGACY_FORMAT_VERSION -> if (fields.size != LEGACY_FIELD_COUNT) return null
            else -> return null
        }
        val recoveryToken = fields[1].takeIf(RECOVERY_TOKEN_PATTERN::matches) ?: return null
        val state = State.fromCode(fields[2]) ?: return null
        val providerValue = fields[3].toLongOrNull() ?: return null
        val conversationValue = fields[4].toLongOrNull() ?: return null
        val received = fields[5].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val sent = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val subscriptionValue = fields[7].toIntOrNull()?.takeIf { it >= NO_SUBSCRIPTION } ?: return null
        val updated = fields[8].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val providerContentDigest = when (version) {
            LEGACY_FORMAT_VERSION -> null
            FORMAT_VERSION -> if (fields[9] == NO_PROVIDER_CONTENT_DIGEST) {
                null
            } else {
                runCatching {
                    IncomingSmsProviderContentDigest.fromStorageToken(fields[9])
                }.getOrNull() ?: return null
            }
            else -> runCatching {
                IncomingSmsProviderContentDigest.fromStorageToken(fields[9])
            }.getOrNull() ?: return null
        }
        val hasStoredIds = providerValue > 0L && conversationValue > 0L
        if ((state == State.PENDING && (providerValue != 0L || conversationValue != 0L)) ||
            (state != State.PENDING && !hasStoredIds)
        ) {
            return null
        }
        val entry = Entry(
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
            providerContentDigest = providerContentDigest,
        )
        val canonical = when (version) {
            FORMAT_VERSION -> encode(entry)
            LEGACY_FORMAT_VERSION_WITH_CONTENT_DIGEST -> legacyPayload(entry, version)
            else -> legacyPayload(entry, version)
        }
        if (canonical != encoded) return null
        return DecodedEntry(
            entry = entry,
            requiresMigration = version != FORMAT_VERSION,
        )
    }

    private fun checksum(
        storageToken: String,
        payload: String,
        domain: ByteArray = CHECKSUM_DOMAIN,
    ): String =
        MessageDigest.getInstance("SHA-256").apply {
            update(domain)
            update(CHECKSUM_BOUNDARY)
            update(storageToken.toByteArray(StandardCharsets.US_ASCII))
            update(CHECKSUM_BOUNDARY)
            update(payload.toByteArray(StandardCharsets.UTF_8))
        }.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private data class DecodedEntry(
        val entry: Entry,
        val requiresMigration: Boolean,
    )

    private data class DecodedQuarantine(
        val reason: QuarantineReason,
        val quarantinedTimestampMillis: Long,
    )

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
        val providerContentDigest: IncomingSmsProviderContentDigest?,
    )

    data class QuarantineEntry(
        val fingerprint: MessageDeliveryFingerprint,
        val reason: QuarantineReason,
        val quarantinedTimestampMillis: Long,
    )

    enum class QuarantineReason(val code: String) {
        MALFORMED_JOURNAL_RECORD("M"),
        PROVIDER_ROW_MISSING("R"),
        PROVIDER_ROW_INVALID("I"),
        PROVIDER_ROW_MISMATCH("V"),
        ;

        companion object {
            fun fromCode(code: String): QuarantineReason? = entries.firstOrNull { it.code == code }
        }
    }

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
        data class Quarantined(val entry: QuarantineEntry) : LookupResult
    }

    sealed interface RecoveryEntriesResult {
        data object Corrupt : RecoveryEntriesResult
        data class Success(val entries: List<Entry>) : RecoveryEntriesResult
    }

    companion object {
        private const val PREFERENCE_NAME = "aurora_sms_delivery_journal_v1"
        private const val ENTRY_PREFIX = "delivery."
        private const val FORMAT_VERSION = "4"
        private const val QUARANTINE_FORMAT_VERSION = "Q1"
        private const val LEGACY_FORMAT_VERSION_WITH_CONTENT_DIGEST = "3"
        private const val LEGACY_FORMAT_VERSION = "2"
        private const val SEPARATOR = ","
        private const val FIELD_COUNT = 11
        private const val QUARANTINE_FIELD_COUNT = 4
        private const val LEGACY_FIELD_COUNT_WITH_CONTENT_DIGEST = 10
        private const val LEGACY_FIELD_COUNT = 9
        private const val NO_SUBSCRIPTION = -1
        private const val NO_PROVIDER_CONTENT_DIGEST = "-"
        private val CHECKSUM_DOMAIN =
            "AuroraSMS.INCOMING_SMS_REPLAY_JOURNAL.v4".toByteArray(StandardCharsets.US_ASCII)
        private val QUARANTINE_CHECKSUM_DOMAIN =
            "AuroraSMS.INCOMING_SMS_REPLAY_QUARANTINE.v1".toByteArray(StandardCharsets.US_ASCII)
        private val CHECKSUM_BOUNDARY = byteArrayOf(0)
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
        private val RECOVERY_TOKEN_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val MAXIMUM_ENTRIES = 512

        private fun key(fingerprint: MessageDeliveryFingerprint): String =
            ENTRY_PREFIX + fingerprint.toStorageToken()

        private fun isEntryKey(key: String): Boolean = key.startsWith(ENTRY_PREFIX)
    }
}
