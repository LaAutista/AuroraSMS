// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

@JvmInline
value class ScheduledSmsId(val value: Long) {
    init {
        require(value > 0L) { "Scheduled SMS IDs must be positive" }
    }

    override fun toString(): String = "ScheduledSmsId(REDACTED)"
}

@JvmInline
value class ScheduledSmsRevision(val updatedTimestampMillis: Long) {
    init {
        require(updatedTimestampMillis >= 0L) { "Scheduled SMS revisions cannot be negative" }
    }

    override fun toString(): String = "ScheduledSmsRevision(REDACTED)"
}

/** Purpose-separated participant identity; never log, display, analyze, or export it. */
class ScheduledSmsParticipantSetKey private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is ScheduledSmsParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "ScheduledSmsParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64
        private val DOMAIN_SEPARATOR = "AuroraSMS.ScheduledSmsParticipantSet.v1".encodeToByteArray()
        private val HEX = "0123456789abcdef".toCharArray()

        fun fromParticipants(participants: Iterable<ParticipantAddress>): ScheduledSmsParticipantSetKey {
            val exactValues = ArrayList<String>()
            participants.forEach { participant ->
                require(exactValues.size < MAX_PARTICIPANTS) {
                    "A scheduled SMS participant set cannot exceed $MAX_PARTICIPANTS entries"
                }
                exactValues += participant.value
            }
            val canonicalBytes = exactValues
                .map { ParticipantAddress(Normalizer.normalize(it, Normalizer.Form.NFC)).value }
                .distinct()
                .map { value ->
                    try {
                        value.encodeToByteArray(throwOnInvalidSequence = true)
                    } catch (failure: CharacterCodingException) {
                        throw IllegalArgumentException(
                            "A scheduled SMS participant address is not valid UTF-16",
                            failure,
                        )
                    }
                }
                .sortedWith(::compareUnsignedBytes)
            require(canonicalBytes.isNotEmpty()) { "A scheduled SMS participant set cannot be empty" }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(canonicalBytes.size)
            canonicalBytes.forEach { bytes ->
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return ScheduledSmsParticipantSetKey(
                STORAGE_PREFIX + digest.digest().toLowercaseHex(),
            )
        }

        internal fun fromStorageValue(value: String): ScheduledSmsParticipantSetKey {
            require(value.length == STORAGE_CHARACTERS && value.startsWith(STORAGE_PREFIX)) {
                "A stored scheduled SMS participant key has an invalid format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A stored scheduled SMS participant key is not lowercase SHA-256"
            }
            return ScheduledSmsParticipantSetKey(value)
        }

        private fun MessageDigest.updateInt(value: Int) {
            require(value >= 0)
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }

        private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
            this@toLowercaseHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }

        private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
            val shared = minOf(first.size, second.size)
            for (index in 0 until shared) {
                val comparison = (first[index].toInt() and 0xff) -
                    (second[index].toInt() and 0xff)
                if (comparison != 0) return comparison
            }
            return first.size - second.size
        }
    }
}

enum class ScheduledSmsPhase { PENDING, DISPATCHING, REVIEW_REQUIRED }

enum class ScheduledSmsPrecision { EXACT, INEXACT }

enum class ScheduledSmsReviewReason {
    CLOCK_CHANGED,
    MISSED_AFTER_RESTART,
    PRECONDITION_FAILED,
    ARMING_FAILED,
    INTERRUPTED_BEFORE_RESERVATION,
}

/** Content-free durable ownership for one user-scheduled, existing-thread SMS. */
data class ScheduledSms(
    val id: ScheduledSmsId,
    val participantSetKey: ScheduledSmsParticipantSetKey,
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val dueTimestampMillis: Long,
    val phase: ScheduledSmsPhase,
    val precision: ScheduledSmsPrecision,
    val reviewReason: ScheduledSmsReviewReason?,
    val armedWallTimestampMillis: Long,
    val armedElapsedRealtimeMillis: Long,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        require(dueTimestampMillis > createdTimestampMillis)
        require(armedWallTimestampMillis >= 0L && armedElapsedRealtimeMillis >= 0L)
        require(
            createdTimestampMillis >= 0L &&
                updatedTimestampMillis in createdTimestampMillis until Long.MAX_VALUE,
        )
        require((phase == ScheduledSmsPhase.REVIEW_REQUIRED) == (reviewReason != null)) {
            "Scheduled SMS review phase and reason disagree"
        }
    }

    val revision: ScheduledSmsRevision
        get() = ScheduledSmsRevision(updatedTimestampMillis)

    override fun toString(): String =
        "ScheduledSms(phase=$phase, precision=$precision, hasReview=${reviewReason != null}, REDACTED)"
}

data class ScheduledSmsRequest(
    val participantSetKey: ScheduledSmsParticipantSetKey,
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val expectedDraftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val dueTimestampMillis: Long,
    val createdTimestampMillis: Long,
    val armedElapsedRealtimeMillis: Long,
) {
    init {
        require(dueTimestampMillis > createdTimestampMillis)
        require(createdTimestampMillis >= 0L && armedElapsedRealtimeMillis >= 0L)
    }
}

/** Atomic schedule ownership plus the authoritative draft body, which is never copied into it. */
data class ScheduledSmsReservation(
    val schedule: ScheduledSms,
    val authoritativeBody: String,
) {
    init {
        require(authoritativeBody.isNotBlank())
    }

    override fun toString(): String = "ScheduledSmsReservation(REDACTED)"
}

enum class ScheduledSmsStorageOperation { CREATE, READ, TRANSITION, REMOVE, RECOVER }

sealed interface ScheduledSmsResult<out T> {
    data class Success<T>(val value: T) : ScheduledSmsResult<T> {
        override fun toString(): String = "ScheduledSmsResult.Success(REDACTED)"
    }

    data object NotFound : ScheduledSmsResult<Nothing>
    data object StaleWrite : ScheduledSmsResult<Nothing>
    data object PhaseMismatch : ScheduledSmsResult<Nothing>
    data object LimitExceeded : ScheduledSmsResult<Nothing>
    data object IneligibleDraft : ScheduledSmsResult<Nothing>
    data object InvalidTimestamp : ScheduledSmsResult<Nothing>
    data object CorruptData : ScheduledSmsResult<Nothing>
    data class StorageFailure(val operation: ScheduledSmsStorageOperation) : ScheduledSmsResult<Nothing>
}

enum class ScheduledSmsDispatchReconciliation { IN_PROGRESS, COMPLETED_AND_REMOVED, REVIEW_REQUIRED }

interface ScheduledSmsRepository {
    suspend fun create(request: ScheduledSmsRequest): ScheduledSmsResult<ScheduledSmsReservation>
    suspend fun read(id: ScheduledSmsId): ScheduledSmsResult<ScheduledSms>
    suspend fun readByThread(providerThreadId: ProviderThreadId): ScheduledSmsResult<ScheduledSms>
    fun observeByThread(providerThreadId: ProviderThreadId): Flow<ScheduledSmsResult<ScheduledSms?>>
    suspend fun recoverySnapshot(): ScheduledSmsResult<List<ScheduledSms>>
    suspend fun markArmed(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        precision: ScheduledSmsPrecision,
        armedWallTimestampMillis: Long,
        armedElapsedRealtimeMillis: Long,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms>
    suspend fun markDispatching(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms>
    suspend fun markReviewRequired(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        reason: ScheduledSmsReviewReason,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms>
    suspend fun remove(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
    ): ScheduledSmsResult<Unit>
    suspend fun reconcileDispatch(
        id: ScheduledSmsId,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSmsDispatchReconciliation>
}

const val MAXIMUM_SCHEDULED_SMS_OPERATIONS: Int = 128
