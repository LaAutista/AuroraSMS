// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.pendingOperationNamespaceOrNull
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.MmsDownloadRequest

/** Metadata-only crash owner for the incoming MMS platform/provider/notification boundary. */
@SuppressLint("ApplySharedPref", "UseKtx")
internal class IncomingMmsDownloadJournal(
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
    fun reserve(request: MmsDownloadRequest): ReserveResult {
        if (!request.operationId.isIncomingMmsOperation()) return ReserveResult.Rejected
        val createdAt = nowMillis().takeIf { it >= 0L } ?: return ReserveResult.Rejected
        val records = decodedRecords() ?: return ReserveResult.PersistenceFailure
        val notificationDigest = notificationDigest(request)
        records.singleOrNull { it.notificationDigest == notificationDigest }?.let { duplicate ->
            return ReserveResult.Duplicate(duplicate)
        }
        if (records.size >= maximumEntries || key(request.operationId.value) in preferences.all) {
            return ReserveResult.Rejected
        }
        val record = Record(
            operationId = request.operationId,
            subscriptionId = request.subscriptionId,
            transactionId = request.notificationTransactionId,
            notificationDigest = notificationDigest,
            expectedSizeBytes = request.expectedSizeBytes,
            receivedTimestampMillis = request.receivedTimestampMillis,
            fileName = null,
            providerId = null,
            conversationId = null,
            state = State.RESERVED,
            createdAtMillis = createdAt,
        )
        return if (write(record)) ReserveResult.Reserved(record) else ReserveResult.PersistenceFailure
    }

    @Synchronized
    fun markStaged(operationId: MessageId, fileName: String): TransitionResult =
        update(operationId) { record ->
            if (!FILE_NAME.matches(fileName)) return@update null
            when (record.state) {
                State.RESERVED -> record.copy(fileName = fileName, state = State.STAGED)
                State.STAGED -> record.takeIf { it.fileName == fileName }
                else -> null
            }
        }

    @Synchronized
    fun markSubmitting(operationId: MessageId, fileName: String): TransitionResult =
        updateExactFile(operationId, fileName) { record ->
            when (record.state) {
                State.STAGED -> record.copy(state = State.SUBMITTING)
                State.SUBMITTING -> record
                else -> null
            }
        }

    @Synchronized
    fun markSubmissionUnknown(operationId: MessageId, fileName: String): TransitionResult =
        updateExactFile(operationId, fileName) { record ->
            when (record.state) {
                State.SUBMITTING -> record.copy(state = State.SUBMISSION_UNKNOWN)
                State.SUBMISSION_UNKNOWN -> record
                else -> null
            }
        }

    @Synchronized
    fun recordSuccessCallback(operationId: MessageId, fileName: String): TransitionResult =
        updateExactFile(operationId, fileName) { record ->
            when (record.state) {
                State.SUBMITTING,
                State.SUBMISSION_UNKNOWN,
                -> record.copy(state = State.CALLBACK_SUCCEEDED)
                State.CALLBACK_SUCCEEDED,
                State.PERSISTED,
                -> record
                else -> null
            }
        }

    @Synchronized
    fun recordFailureCallback(operationId: MessageId, fileName: String): TransitionResult =
        updateExactFile(operationId, fileName) { record ->
            when (record.state) {
                State.SUBMITTING,
                State.SUBMISSION_UNKNOWN,
                -> record.copy(state = State.CALLBACK_FAILED)
                State.CALLBACK_FAILED -> record
                else -> null
            }
        }

    @Synchronized
    fun markPersisted(
        operationId: MessageId,
        fileName: String,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): TransitionResult = updateExactFile(operationId, fileName) { record ->
        if (providerId.kind != ProviderKind.MMS || providerId.value <= 0L || conversationId.value <= 0L) {
            return@updateExactFile null
        }
        when (record.state) {
            State.CALLBACK_SUCCEEDED -> record.copy(
                providerId = providerId,
                conversationId = conversationId,
                state = State.PERSISTED,
            )
            State.PERSISTED -> record.takeIf {
                it.providerId == providerId && it.conversationId == conversationId
            }
            else -> null
        }
    }

    @Synchronized
    fun abandonBeforeSubmission(operationId: MessageId, fileName: String?): Boolean =
        remove(operationId) { record ->
            record.state in setOf(State.RESERVED, State.STAGED) && record.fileName == fileName
        }

    @Synchronized
    fun acknowledgeFailure(operationId: MessageId, fileName: String): Boolean =
        remove(operationId) { record ->
            record.state == State.CALLBACK_FAILED && record.fileName == fileName
        }

    @Synchronized
    fun acknowledgeMalformed(operationId: MessageId, fileName: String): Boolean =
        remove(operationId) { record ->
            record.state == State.CALLBACK_SUCCEEDED && record.fileName == fileName
        }

    @Synchronized
    fun acknowledgePersisted(
        operationId: MessageId,
        fileName: String,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): Boolean = remove(operationId) { record ->
        record.state == State.PERSISTED &&
            record.fileName == fileName &&
            record.providerId == providerId &&
            record.conversationId == conversationId
    }

    @Synchronized
    fun recoverySnapshot(): RecoveryResult =
        decodedRecords()?.let(RecoveryResult::Available) ?: RecoveryResult.PersistenceFailure

    private fun updateExactFile(
        operationId: MessageId,
        fileName: String,
        transform: (Record) -> Record?,
    ): TransitionResult = update(operationId) { record ->
        record.takeIf { it.fileName == fileName }?.let(transform)
    }

    private fun update(operationId: MessageId, transform: (Record) -> Record?): TransitionResult {
        val record = read(operationId) ?: return TransitionResult.Rejected
        val updated = transform(record) ?: return TransitionResult.Rejected
        return if (write(updated)) {
            TransitionResult.Applied(updated)
        } else {
            TransitionResult.PersistenceFailure
        }
    }

    private fun remove(operationId: MessageId, predicate: (Record) -> Boolean): Boolean {
        val record = read(operationId) ?: return false
        if (!predicate(record)) return false
        return preferences.edit().remove(key(operationId.value)).commit()
    }

    private fun read(operationId: MessageId): Record? {
        if (!operationId.isIncomingMmsOperation()) return null
        val key = key(operationId.value)
        return (preferences.all[key] as? String)?.let { decode(key, it) }
    }

    private fun write(record: Record): Boolean {
        val key = key(record.operationId.value)
        return preferences.edit().putString(key, record.encode(key)).commit()
    }

    private fun decodedRecords(): List<Record>? {
        val records = ArrayList<Record>(preferences.all.size)
        val notificationDigests = HashSet<String>(preferences.all.size)
        preferences.all.toSortedMap().forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return null
            val record = decode(key, value) ?: return null
            if (!notificationDigests.add(record.notificationDigest)) return null
            records += record
        }
        return records
    }

    private fun decode(key: String, encoded: String): Record? {
        val operationFromKey = key.removePrefix(KEY_PREFIX).toLongOrNull()
            ?.let { MessageId(ProviderKind.PENDING_OPERATION, it) }
            ?.takeIf(MessageId::isIncomingMmsOperation)
            ?: return null
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != VERSION) return null
        val operationId = fields[1].toLongOrNull()
            ?.takeIf { it == operationFromKey.value }
            ?.let { MessageId(ProviderKind.PENDING_OPERATION, it) }
            ?: return null
        val subscriptionId = fields[2].toIntOrNull()
            ?.takeIf { it >= 0 }
            ?.let(::AuroraSubscriptionId)
            ?: return null
        val transactionId = decodeTransaction(fields[3]) ?: return null
        val notificationDigest = fields[4].takeIf(SHA_256_HEX::matches) ?: return null
        val expectedSize = fields[5].toLongOrNull()
            ?.takeIf { it in 1L..EncodedMmsPdu.MAX_ENCODED_BYTES.toLong() }
            ?: return null
        val receivedAt = fields[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val fileName = when (fields[7]) {
            NO_VALUE -> null
            else -> fields[7].takeIf(FILE_NAME::matches) ?: return null
        }
        val providerValue = fields[8].toLongOrNull() ?: return null
        val conversationValue = fields[9].toLongOrNull() ?: return null
        val providerId = providerValue.takeIf { it > 0L }?.let { ProviderMessageId(ProviderKind.MMS, it) }
        val conversationId = conversationValue.takeIf { it > 0L }?.let(::ConversationId)
        if ((providerValue == 0L) != (providerId == null) || (conversationValue == 0L) != (conversationId == null)) {
            return null
        }
        val state = State.decode(fields[10]) ?: return null
        val createdAt = fields[11].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val record = Record(
            operationId = operationId,
            subscriptionId = subscriptionId,
            transactionId = transactionId,
            notificationDigest = notificationDigest,
            expectedSizeBytes = expectedSize,
            receivedTimestampMillis = receivedAt,
            fileName = fileName,
            providerId = providerId,
            conversationId = conversationId,
            state = state,
            createdAtMillis = createdAt,
        )
        if (!record.hasValidShape()) return null
        val payload = record.payload()
        if (fields.take(PAYLOAD_FIELD_COUNT).joinToString(SEPARATOR) != payload) return null
        if (!MessageDigest.isEqual(checksum(key, payload).ascii(), fields[12].ascii())) return null
        return record
    }

    private fun Record.hasValidShape(): Boolean = when (state) {
        State.RESERVED -> fileName == null && providerId == null && conversationId == null
        State.STAGED,
        State.SUBMITTING,
        State.SUBMISSION_UNKNOWN,
        State.CALLBACK_SUCCEEDED,
        State.CALLBACK_FAILED,
        -> fileName != null && providerId == null && conversationId == null
        State.PERSISTED -> fileName != null && providerId != null && conversationId != null
    }

    private fun Record.payload(): String = listOf(
        VERSION,
        operationId.value,
        subscriptionId.value,
        encodeTransaction(transactionId),
        notificationDigest,
        expectedSizeBytes,
        receivedTimestampMillis,
        fileName ?: NO_VALUE,
        providerId?.value ?: 0L,
        conversationId?.value ?: 0L,
        state.code,
        createdAtMillis,
    ).joinToString(SEPARATOR)

    private fun Record.encode(key: String): String {
        val payload = payload()
        return "$payload$SEPARATOR${checksum(key, payload)}"
    }

    internal data class Record(
        val operationId: MessageId,
        val subscriptionId: AuroraSubscriptionId,
        val transactionId: String,
        val notificationDigest: String,
        val expectedSizeBytes: Long,
        val receivedTimestampMillis: Long,
        val fileName: String?,
        val providerId: ProviderMessageId?,
        val conversationId: ConversationId?,
        val state: State,
        val createdAtMillis: Long,
    ) {
        override fun toString(): String =
            "IncomingMmsDownloadJournal.Record(state=$state, hasFile=${fileName != null}, " +
                "hasProvider=${providerId != null}, REDACTED)"
    }

    internal enum class State(val code: String) {
        RESERVED("R"),
        STAGED("G"),
        SUBMITTING("S"),
        SUBMISSION_UNKNOWN("U"),
        CALLBACK_SUCCEEDED("C"),
        CALLBACK_FAILED("F"),
        PERSISTED("P"),
        ;

        companion object {
            fun decode(code: String): State? = entries.singleOrNull { it.code == code }
        }
    }

    internal sealed interface ReserveResult {
        data class Reserved(val record: Record) : ReserveResult
        data class Duplicate(val record: Record) : ReserveResult
        data object Rejected : ReserveResult
        data object PersistenceFailure : ReserveResult
    }

    internal sealed interface TransitionResult {
        data class Applied(val record: Record) : TransitionResult
        data object Rejected : TransitionResult
        data object PersistenceFailure : TransitionResult
    }

    internal sealed interface RecoveryResult {
        data class Available(val records: List<Record>) : RecoveryResult
        data object PersistenceFailure : RecoveryResult
    }

    companion object {
        const val MAXIMUM_ENTRIES: Int = 128
        private const val PREFERENCE_NAME = "aurora_incoming_mms_download_journal_v1"
        private const val KEY_PREFIX = "download:"
        private const val VERSION = "D1"
        private const val SEPARATOR = "|"
        private const val NO_VALUE = "-"
        private const val PAYLOAD_FIELD_COUNT = 12
        private const val FIELD_COUNT = 13
        private val TRANSACTION_ID = Regex("[ -~]{1,128}")
        private val SHA_256_HEX = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdu",
        )

        private fun notificationDigest(request: MmsDownloadRequest): String = sha256(
            "${request.subscriptionId.value}\n${request.notificationTransactionId}\n${request.contentLocation}",
        )

        private fun encodeTransaction(value: String): String = Base64.encodeToString(
            value.toByteArray(StandardCharsets.US_ASCII),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        private fun decodeTransaction(encoded: String): String? = runCatching {
            String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                StandardCharsets.US_ASCII,
            )
        }.getOrNull()?.takeIf { value ->
            TRANSACTION_ID.matches(value) && encodeTransaction(value) == encoded
        }

        private fun key(operationId: Long): String = "$KEY_PREFIX$operationId"
        private fun checksum(key: String, payload: String): String = sha256("$key\n$payload")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun MessageId.isIncomingMmsOperation(): Boolean =
    kind == ProviderKind.PENDING_OPERATION &&
        pendingOperationNamespaceOrNull() == PendingOperationNamespace.INCOMING_MMS

private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
