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
value class SendDelayId(val value: Long) {
    init {
        require(value > 0L) { "Send-delay IDs must be positive" }
    }

    override fun toString(): String = "SendDelayId(REDACTED)"
}

@JvmInline
value class SendDelayRevision(val updatedTimestampMillis: Long) {
    init {
        require(updatedTimestampMillis >= 0L) { "Send-delay revisions cannot be negative" }
    }

    override fun toString(): String = "SendDelayRevision(REDACTED)"
}

/** Purpose-separated participant identity; never log, display, analyze, or export it. */
class SendDelayParticipantSetKey private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is SendDelayParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "SendDelayParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64
        private val DOMAIN_SEPARATOR = "AuroraSMS.SendDelayParticipantSet.v1".encodeToByteArray()
        private val HEX = "0123456789abcdef".toCharArray()

        fun fromParticipants(participants: Iterable<ParticipantAddress>): SendDelayParticipantSetKey {
            val exactValues = ArrayList<String>()
            participants.forEach { participant ->
                require(exactValues.size < MAX_PARTICIPANTS) {
                    "A send-delay participant set cannot exceed $MAX_PARTICIPANTS entries"
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
                            "A send-delay participant address is not valid UTF-16",
                            failure,
                        )
                    }
                }
                .sortedWith(::compareUnsignedBytes)
            require(canonicalBytes.isNotEmpty()) { "A send-delay participant set cannot be empty" }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(canonicalBytes.size)
            canonicalBytes.forEach { bytes ->
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return SendDelayParticipantSetKey(STORAGE_PREFIX + digest.digest().toLowercaseHex())
        }

        internal fun fromStorageValue(value: String): SendDelayParticipantSetKey {
            require(value.length == STORAGE_CHARACTERS && value.startsWith(STORAGE_PREFIX)) {
                "A stored send-delay participant key has an invalid format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A stored send-delay participant key is not lowercase SHA-256"
            }
            return SendDelayParticipantSetKey(value)
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

enum class SendDelayPhase { PENDING, DISPATCHING, REVIEW_REQUIRED }

enum class SendDelayReviewReason {
    CLOCK_CHANGED,
    MISSED_AFTER_RESTART,
    PRECONDITION_FAILED,
    ARMING_FAILED,
    INTERRUPTED_BEFORE_RESERVATION,
}

/** Content-free durable ownership for one short Undo Send window. */
data class SendDelayOperation(
    val id: SendDelayId,
    val participantSetKey: SendDelayParticipantSetKey,
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val dueTimestampMillis: Long,
    val phase: SendDelayPhase,
    val reviewReason: SendDelayReviewReason?,
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
        require((phase == SendDelayPhase.REVIEW_REQUIRED) == (reviewReason != null)) {
            "Send-delay review phase and reason disagree"
        }
    }

    val revision: SendDelayRevision
        get() = SendDelayRevision(updatedTimestampMillis)

    override fun toString(): String =
        "SendDelayOperation(phase=$phase, hasReview=${reviewReason != null}, REDACTED)"
}

data class SendDelayRequest(
    val participantSetKey: SendDelayParticipantSetKey,
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
        require(dueTimestampMillis - createdTimestampMillis in MINIMUM_SEND_DELAY_MILLIS..MAXIMUM_SEND_DELAY_MILLIS) {
            "Send delay must be between one and ten seconds"
        }
    }
}

/** Atomic delayed-send ownership plus transient authoritative content. */
data class SendDelayReservation(
    val operation: SendDelayOperation,
    val authoritativeBody: String,
) {
    init {
        require(authoritativeBody.isNotBlank())
    }

    override fun toString(): String = "SendDelayReservation(REDACTED)"
}

enum class SendDelayStorageOperation { CREATE, READ, TRANSITION, REMOVE, RECOVER }

sealed interface SendDelayResult<out T> {
    data class Success<T>(val value: T) : SendDelayResult<T> {
        override fun toString(): String = "SendDelayResult.Success(REDACTED)"
    }

    data object NotFound : SendDelayResult<Nothing>
    data object StaleWrite : SendDelayResult<Nothing>
    data object PhaseMismatch : SendDelayResult<Nothing>
    data object LimitExceeded : SendDelayResult<Nothing>
    data object IneligibleDraft : SendDelayResult<Nothing>
    data object InvalidTimestamp : SendDelayResult<Nothing>
    data object CorruptData : SendDelayResult<Nothing>
    data class StorageFailure(val operation: SendDelayStorageOperation) : SendDelayResult<Nothing>
}

enum class SendDelayDispatchReconciliation { IN_PROGRESS, COMPLETED_AND_REMOVED, REVIEW_REQUIRED }

interface SendDelayRepository {
    suspend fun create(request: SendDelayRequest): SendDelayResult<SendDelayReservation>
    suspend fun read(id: SendDelayId): SendDelayResult<SendDelayOperation>
    suspend fun readByThread(providerThreadId: ProviderThreadId): SendDelayResult<SendDelayOperation>
    fun observeByThread(providerThreadId: ProviderThreadId): Flow<SendDelayResult<SendDelayOperation?>>
    suspend fun recoverySnapshot(): SendDelayResult<List<SendDelayOperation>>
    suspend fun markDispatching(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayOperation>
    suspend fun markReviewRequired(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        reason: SendDelayReviewReason,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayOperation>
    suspend fun remove(id: SendDelayId, expectedRevision: SendDelayRevision): SendDelayResult<Unit>
    suspend fun reconcileDispatch(
        id: SendDelayId,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayDispatchReconciliation>
}

const val MINIMUM_SEND_DELAY_MILLIS: Long = 1_000L
const val MAXIMUM_SEND_DELAY_MILLIS: Long = 10_000L
const val MAXIMUM_SEND_DELAY_OPERATIONS: Int = 128
