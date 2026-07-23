// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId

/**
 * Private crash-ordering journal for transport-owned outgoing SMS submissions.
 *
 * Records contain only opaque operation/provider IDs, a part count, state, and
 * lifecycle times. Message content, recipients, and subscription data cannot
 * enter this boundary. Every security-relevant write uses synchronous commit.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
internal class OutgoingSmsSubmissionJournal(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
    private val maximumEntries: Int = MAXIMUM_ENTRIES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    init {
        require(maximumEntries in 1..MAXIMUM_ENTRIES)
    }

    @Synchronized
    fun recordPrepared(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean {
        if (
            !operationId.isValidOperation() ||
            !providerId.isValidSms() ||
            conversationId.value <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return false
        }
        val now = nowMillis().takeIf { it >= 0L } ?: return false
        val expiresAt = saturatingAdd(now, RETENTION_MILLIS).takeIf { it > now } ?: return false
        val snapshot = decodedSnapshot(now) ?: return false
        val key = key(operationId.value)
        if (
            (preferences.contains(key) && key !in snapshot.expiredTombstoneKeys) ||
            snapshot.records.size >= maximumEntries
        ) {
            return false
        }
        val editor = preferences.edit()
        snapshot.expiredTombstoneKeys.forEach(editor::remove)
        val record = Record(
            operationId = operationId.value,
            providerId = providerId,
            conversationId = conversationId,
            unitCount = unitCount,
            state = State.PREPARED,
            createdAtMillis = now,
            expiresAtMillis = expiresAt,
        )
        return editor.putString(key, record.encode(key)).commit()
    }

    @Synchronized
    fun recordSubmitting(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean = updateExact(operationId, providerId, conversationId, unitCount) { record ->
        if (record.state != State.PREPARED) null else record.copy(state = State.SUBMITTING)
    }

    @Synchronized
    fun recordSubmissionUnknown(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean {
        val now = nowMillis().takeIf { it >= 0L } ?: return false
        return updateExact(operationId, providerId, conversationId, unitCount) { record ->
            when (record.state) {
                State.SUBMITTING -> record.withExpiringState(State.SUBMISSION_UNKNOWN, now)
                State.SUBMISSION_UNKNOWN -> record
                State.PREPARED,
                State.KNOWN_UNSENT_QUARANTINED,
                -> null
            }
        }
    }

    @Synchronized
    fun recordKnownUnsentQuarantined(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean {
        val now = nowMillis().takeIf { it >= 0L } ?: return false
        return updateExact(operationId, providerId, conversationId, unitCount) { record ->
            when (record.state) {
                State.PREPARED -> record.withExpiringState(State.KNOWN_UNSENT_QUARANTINED, now)
                State.KNOWN_UNSENT_QUARANTINED -> record
                State.SUBMITTING,
                State.SUBMISSION_UNKNOWN,
                -> null
            }
        }
    }

    @Synchronized
    fun acknowledgeKnownUnsent(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean = removeExact(
        operationId = operationId,
        providerId = providerId,
        conversationId = conversationId,
        unitCount = unitCount,
        acceptedStates = setOf(State.PREPARED),
        missingIsSuccess = true,
    )

    @Synchronized
    fun acknowledgeSubmitted(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Boolean = removeExact(
        operationId = operationId,
        providerId = providerId,
        conversationId = conversationId,
        unitCount = unitCount,
        acceptedStates = setOf(State.SUBMITTING),
        missingIsSuccess = false,
    )

    @Synchronized
    fun recoverySnapshot(): RecoverySnapshotResult {
        val now = nowMillis().takeIf { it >= 0L } ?: return RecoverySnapshotResult.PersistenceFailure
        val snapshot = decodedSnapshot(now) ?: return RecoverySnapshotResult.PersistenceFailure
        if (
            snapshot.expiredTombstoneKeys.isNotEmpty() &&
            !removeKeys(snapshot.expiredTombstoneKeys)
        ) {
            return RecoverySnapshotResult.PersistenceFailure
        }
        return RecoverySnapshotResult.Available(
            snapshot.records.filterNot { record ->
                record.state.isExpiringTombstone && record.expiresAtMillis <= now
            },
        )
    }

    private fun updateExact(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
        transform: (Record) -> Record?,
    ): Boolean {
        val record = readExact(operationId, providerId, conversationId, unitCount) ?: return false
        val updated = transform(record) ?: return false
        if (updated == record) return true
        val key = key(operationId.value)
        return preferences.edit().putString(key, updated.encode(key)).commit()
    }

    private fun removeExact(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
        acceptedStates: Set<State>,
        missingIsSuccess: Boolean,
    ): Boolean {
        if (
            !operationId.isValidOperation() ||
            !providerId.isValidSms() ||
            conversationId.value <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return false
        }
        val key = key(operationId.value)
        val encoded = preferences.all[key] ?: return missingIsSuccess
        if (encoded !is String) return false
        val record = decode(key, encoded) ?: return false
        if (
            record.providerId != providerId ||
            record.conversationId != conversationId ||
            record.unitCount != unitCount ||
            record.state !in acceptedStates
        ) {
            return false
        }
        return preferences.edit().remove(key).commit()
    }

    private fun readExact(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        unitCount: Int,
    ): Record? {
        if (
            !operationId.isValidOperation() ||
            !providerId.isValidSms() ||
            conversationId.value <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return null
        }
        val key = key(operationId.value)
        val encoded = preferences.all[key] as? String ?: return null
        return decode(key, encoded)?.takeIf { record ->
            record.providerId == providerId &&
                record.conversationId == conversationId &&
                record.unitCount == unitCount
        }
    }

    private fun decodedSnapshot(nowMillis: Long): Snapshot? {
        val records = mutableListOf<Record>()
        val expiredTombstoneKeys = mutableListOf<String>()
        for ((key, value) in preferences.all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) return null
            val record = decode(key, value) ?: return null
            if (record.state.isExpiringTombstone && record.expiresAtMillis <= nowMillis) {
                expiredTombstoneKeys += key
            } else {
                records += record
            }
        }
        return Snapshot(records, expiredTombstoneKeys)
    }

    private fun decode(key: String, encoded: String): Record? {
        val operationIdFromKey = key.removePrefix(KEY_PREFIX).toLongOrNull()
            ?.takeIf { it > 0L }
            ?.takeIf { value ->
                MessageId(ProviderKind.PENDING_OPERATION, value).isValidOperation()
            }
            ?: return null
        val fields = encoded.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != VERSION) return null
        val operationId = fields[1].toLongOrNull()?.takeIf { it == operationIdFromKey } ?: return null
        val providerId = fields[2].toLongOrNull()?.takeIf { it > 0L }
            ?.let { ProviderMessageId(ProviderKind.SMS, it) }
            ?: return null
        val conversationId = fields[3].toLongOrNull()?.takeIf { it > 0L }
            ?.let(::ConversationId)
            ?: return null
        val unitCount = fields[4].toIntOrNull()?.takeIf { it in 1..MAXIMUM_UNIT_COUNT } ?: return null
        val state = State.decode(fields[5]) ?: return null
        val createdAt = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val expiresAt = fields[7].toLongOrNull()?.takeIf { it > createdAt } ?: return null
        val record = Record(operationId, providerId, conversationId, unitCount, state, createdAt, expiresAt)
        val canonicalPayload = record.payload()
        val encodedPayload = fields.take(PAYLOAD_FIELD_COUNT).joinToString(FIELD_SEPARATOR)
        if (encodedPayload != canonicalPayload) return null
        val expectedChecksum = checksum(key, canonicalPayload)
        if (
            !MessageDigest.isEqual(
                expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                fields[CHECKSUM_FIELD_INDEX].toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            return null
        }
        return record
    }

    private fun Record.payload(): String = listOf(
        VERSION,
        operationId.toString(),
        providerId.value.toString(),
        conversationId.value.toString(),
        unitCount.toString(),
        state.encoded,
        createdAtMillis.toString(),
        expiresAtMillis.toString(),
    ).joinToString(FIELD_SEPARATOR)

    private fun Record.encode(key: String): String {
        val payload = payload()
        return "$payload$FIELD_SEPARATOR${checksum(key, payload)}"
    }

    private fun Record.withExpiringState(state: State, nowMillis: Long): Record? {
        val expiryBase = maxOf(createdAtMillis, nowMillis)
        val expiry = saturatingAdd(expiryBase, RETENTION_MILLIS)
            .takeIf { it > expiryBase }
            ?: return null
        return copy(state = state, expiresAtMillis = expiry)
    }

    private fun checksum(key: String, payload: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$key\n$payload".toByteArray(StandardCharsets.UTF_8))
            .toLowerHex()

    private fun removeKeys(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }

    /** Existing journals may contain IDs from the pre-partition ordinary range. */
    private fun MessageId.isValidOperation(): Boolean =
        kind == ProviderKind.PENDING_OPERATION && value in 1 until INLINE_REPLY_OPERATION_ID_BOUNDARY

    private fun ProviderMessageId.isValidSms(): Boolean = kind == ProviderKind.SMS && value > 0L

    private fun key(operationId: Long): String = "$KEY_PREFIX$operationId"

    internal data class Record(
        val operationId: Long,
        val providerId: ProviderMessageId,
        val conversationId: ConversationId,
        val unitCount: Int,
        val state: State,
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
    )

    internal enum class State(val encoded: String) {
        PREPARED("p"),
        SUBMITTING("s"),
        SUBMISSION_UNKNOWN("u"),
        KNOWN_UNSENT_QUARANTINED("q");

        val isExpiringTombstone: Boolean
            get() = this == SUBMISSION_UNKNOWN || this == KNOWN_UNSENT_QUARANTINED

        companion object {
            fun decode(encoded: String): State? = entries.singleOrNull { it.encoded == encoded }
        }
    }

    internal sealed interface RecoverySnapshotResult {
        data class Available(val records: List<Record>) : RecoverySnapshotResult

        data object PersistenceFailure : RecoverySnapshotResult
    }

    private data class Snapshot(
        val records: List<Record>,
        val expiredTombstoneKeys: List<String>,
    )

    private companion object {
        const val PREFERENCE_NAME = "aurora_outgoing_sms_submissions"
        const val KEY_PREFIX = "operation:"
        const val VERSION = "1"
        const val FIELD_SEPARATOR = "|"
        const val PAYLOAD_FIELD_COUNT = 8
        const val CHECKSUM_FIELD_INDEX = 8
        const val FIELD_COUNT = 9
        const val MAXIMUM_ENTRIES = 128
        const val MAXIMUM_UNIT_COUNT = 255
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun saturatingAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
