// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId

/** Content-free crash-ordering owner for the outgoing MMS provider/platform boundary. */
@SuppressLint("ApplySharedPref", "UseKtx")
internal class OutgoingMmsSubmissionJournal(
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
    fun reserve(
        operationId: MessageId,
        conversationId: ConversationId,
        transactionId: String,
    ): Boolean {
        if (!operationId.isValid() || conversationId.value <= 0L || !TRANSACTION_ID.matches(transactionId)) {
            return false
        }
        val createdAt = nowMillis().takeIf { it >= 0L } ?: return false
        val records = decodedRecords() ?: return false
        val key = key(operationId.value)
        if (key in preferences.all || records.size >= maximumEntries) return false
        return preferences.edit().putString(
            key,
            Record(
                operationId = operationId,
                conversationId = conversationId,
                transactionId = transactionId,
                providerId = null,
                state = State.PREPARING,
                createdAtMillis = createdAt,
            ).encode(key),
        ).commit()
    }

    @Synchronized
    fun markPrepared(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = update(operationId) { record ->
        if (
            record.state != State.PREPARING ||
            record.providerId != null ||
            providerId.kind != ProviderKind.MMS ||
            providerId.value <= 0L ||
            record.conversationId != conversationId
        ) {
            null
        } else {
            record.copy(providerId = providerId, state = State.PREPARED)
        }
    }

    @Synchronized
    fun markSubmitting(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = updateExact(operationId, providerId, conversationId) { record ->
        if (record.state == State.PREPARED) record.copy(state = State.SUBMITTING) else null
    }

    @Synchronized
    fun markSubmissionUnknown(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = updateExact(operationId, providerId, conversationId) { record ->
        when (record.state) {
            State.SUBMITTING -> record.copy(state = State.SUBMISSION_UNKNOWN)
            State.SUBMISSION_UNKNOWN -> record
            State.PREPARING,
            State.PREPARED,
            State.CALLBACK_SENT,
            State.CALLBACK_FAILED,
            -> null
        }
    }

    @Synchronized
    fun recordCallback(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        sent: Boolean,
    ): Boolean = updateExact(operationId, providerId, conversationId) { record ->
        val target = if (sent) State.CALLBACK_SENT else State.CALLBACK_FAILED
        when (record.state) {
            State.SUBMITTING,
            State.SUBMISSION_UNKNOWN,
            -> record.copy(state = target)
            State.PREPARING,
            State.PREPARED,
            -> null
            State.CALLBACK_SENT,
            State.CALLBACK_FAILED,
            -> record.takeIf { it.state == target }
        }
    }

    @Synchronized
    fun acknowledgeKnownUnsent(operationId: MessageId): Boolean = remove(
        operationId = operationId,
        acceptedStates = setOf(State.PREPARING, State.PREPARED),
    )

    @Synchronized
    fun acknowledgeCallback(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = removeExact(
        operationId = operationId,
        providerId = providerId,
        conversationId = conversationId,
        acceptedStates = setOf(State.CALLBACK_SENT, State.CALLBACK_FAILED),
    )

    @Synchronized
    fun recoverySnapshot(): RecoveryResult =
        decodedRecords()?.let(RecoveryResult::Available) ?: RecoveryResult.PersistenceFailure

    private fun updateExact(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        transform: (Record) -> Record?,
    ): Boolean {
        val record = read(operationId) ?: return false
        if (record.providerId != providerId || record.conversationId != conversationId) return false
        return writeUpdated(record, transform)
    }

    private fun update(operationId: MessageId, transform: (Record) -> Record?): Boolean {
        val record = read(operationId) ?: return false
        return writeUpdated(record, transform)
    }

    private fun writeUpdated(record: Record, transform: (Record) -> Record?): Boolean {
        val updated = transform(record) ?: return false
        val key = key(record.operationId.value)
        return preferences.edit().putString(key, updated.encode(key)).commit()
    }

    private fun remove(operationId: MessageId, acceptedStates: Set<State>): Boolean {
        val record = read(operationId) ?: return false
        if (record.state !in acceptedStates) return false
        return preferences.edit().remove(key(operationId.value)).commit()
    }

    private fun removeExact(
        operationId: MessageId,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
        acceptedStates: Set<State>,
    ): Boolean {
        val record = read(operationId) ?: return false
        if (
            record.providerId != providerId ||
            record.conversationId != conversationId ||
            record.state !in acceptedStates
        ) {
            return false
        }
        return preferences.edit().remove(key(operationId.value)).commit()
    }

    private fun read(operationId: MessageId): Record? {
        if (!operationId.isValid()) return null
        val key = key(operationId.value)
        return (preferences.all[key] as? String)?.let { decode(key, it) }
    }

    private fun decodedRecords(): List<Record>? {
        val records = ArrayList<Record>(preferences.all.size)
        preferences.all.toSortedMap().forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return null
            records += decode(key, value) ?: return null
        }
        return records
    }

    private fun decode(key: String, encoded: String): Record? {
        val operationFromKey = key.removePrefix(KEY_PREFIX).toLongOrNull()
            ?.let { MessageId(ProviderKind.PENDING_OPERATION, it) }
            ?.takeIf { it.isValid() }
            ?: return null
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != VERSION) return null
        val operation = fields[1].toLongOrNull()
            ?.takeIf { it == operationFromKey.value }
            ?.let { MessageId(ProviderKind.PENDING_OPERATION, it) }
            ?: return null
        val conversation = fields[2].toLongOrNull()?.takeIf { it > 0L }?.let(::ConversationId)
            ?: return null
        val transaction = fields[3].takeIf(TRANSACTION_ID::matches) ?: return null
        val providerValue = fields[4].toLongOrNull() ?: return null
        val provider = when {
            providerValue == 0L -> null
            providerValue > 0L -> ProviderMessageId(ProviderKind.MMS, providerValue)
            else -> return null
        }
        val state = State.decode(fields[5]) ?: return null
        if ((state == State.PREPARING) != (provider == null)) return null
        val createdAt = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val record = Record(operation, conversation, transaction, provider, state, createdAt)
        val payload = record.payload()
        if (fields.take(PAYLOAD_FIELD_COUNT).joinToString(SEPARATOR) != payload) return null
        val expected = checksum(key, payload)
        if (!MessageDigest.isEqual(expected.ascii(), fields[7].ascii())) return null
        return record
    }

    private fun Record.payload(): String = listOf(
        VERSION,
        operationId.value,
        conversationId.value,
        transactionId,
        providerId?.value ?: 0L,
        state.code,
        createdAtMillis,
    ).joinToString(SEPARATOR)

    private fun Record.encode(key: String): String {
        val payload = payload()
        return "$payload$SEPARATOR${checksum(key, payload)}"
    }

    internal data class Record(
        val operationId: MessageId,
        val conversationId: ConversationId,
        val transactionId: String,
        val providerId: ProviderMessageId?,
        val state: State,
        val createdAtMillis: Long,
    ) {
        override fun toString(): String =
            "OutgoingMmsSubmissionJournal.Record(state=$state, hasProvider=${providerId != null}, REDACTED)"
    }

    internal enum class State(val code: String) {
        PREPARING("G"),
        PREPARED("P"),
        SUBMITTING("S"),
        SUBMISSION_UNKNOWN("U"),
        CALLBACK_SENT("T"),
        CALLBACK_FAILED("F"),
        ;

        companion object {
            fun decode(code: String): State? = entries.singleOrNull { it.code == code }
        }
    }

    internal sealed interface RecoveryResult {
        data class Available(val records: List<Record>) : RecoveryResult
        data object PersistenceFailure : RecoveryResult
    }

    companion object {
        const val MAXIMUM_ENTRIES = 128
        private const val PREFERENCE_NAME = "aurora_outgoing_mms_submission_journal_v1"
        private const val KEY_PREFIX = "mms:"
        private const val VERSION = "M1"
        private const val SEPARATOR = "|"
        private const val PAYLOAD_FIELD_COUNT = 7
        private const val FIELD_COUNT = 8
        private val TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,64}")

        private fun key(operationId: Long): String = "$KEY_PREFIX$operationId"
        private fun checksum(key: String, payload: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$key\n$payload".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun MessageId.isValid(): Boolean =
    kind == ProviderKind.PENDING_OPERATION && value in 1L until COMPOSER_OPERATION_ID_BOUNDARY

private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
