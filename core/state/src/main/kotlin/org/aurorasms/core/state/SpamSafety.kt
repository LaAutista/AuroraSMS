// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

/** User-owned classification; automatic heuristics are deliberately not persisted here. */
enum class SpamClassification {
    NEUTRAL,
    SPAM,
    NOT_SPAM,
}

/**
 * Purpose-separated one-way identity for an exact, verified conversation participant set.
 * The value must never be logged, displayed, exported, or compared with another hash domain.
 */
class SpamParticipantSetKey private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is SpamParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "SpamParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX = "sha256-v1:"
        internal const val STORAGE_CHARACTERS = STORAGE_PREFIX.length + 64
        private val DOMAIN = "AuroraSMS.SpamParticipantSet.v1".encodeToByteArray()

        fun fromParticipants(participants: Iterable<ParticipantAddress>): SpamParticipantSetKey =
            SpamParticipantSetKey(hashParticipantValues(DOMAIN, participants, MAX_PARTICIPANTS))

        internal fun fromStorageValue(value: String): SpamParticipantSetKey =
            SpamParticipantSetKey(requireStorageHash(value))
    }
}

/** Purpose-separated one-way identity used only for explicit sender-block lookups. */
class BlockedSenderKey private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is BlockedSenderKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "BlockedSenderKey(REDACTED)"

    companion object {
        internal const val STORAGE_CHARACTERS = 74
        private val DOMAIN = "AuroraSMS.BlockedSender.v1".encodeToByteArray()

        fun fromSender(sender: ParticipantAddress): BlockedSenderKey = BlockedSenderKey(
            hashParticipantValues(DOMAIN, listOf(sender), maximumParticipants = 1),
        )

        internal fun fromStorageValue(value: String): BlockedSenderKey =
            BlockedSenderKey(requireStorageHash(value))
    }
}

data class SpamSafetyScope(
    val participantSetKey: SpamParticipantSetKey,
    val providerThreadId: ProviderThreadId,
    /** Present only for a verified one-person conversation. */
    val singleSenderKey: BlockedSenderKey?,
) {
    override fun toString(): String = "SpamSafetyScope(hasSingleSender=${singleSenderKey != null}, REDACTED)"
}

@JvmInline
value class SpamSafetyRevision(val value: Long) {
    init { require(value > 0L) { "Spam-safety revisions must be positive" } }

    override fun toString(): String = "SpamSafetyRevision(REDACTED)"
}

data class SpamSafetyDecision(
    val scope: SpamSafetyScope,
    val classification: SpamClassification,
    val blocked: Boolean,
    val revision: SpamSafetyRevision,
    val updatedTimestampMillis: Long,
) {
    init {
        require(!blocked || scope.singleSenderKey != null) {
            "Only a verified one-person conversation can be blocked"
        }
        require(classification != SpamClassification.NEUTRAL || blocked) {
            "Neutral unblocked spam-safety rows must not be retained"
        }
        require(updatedTimestampMillis >= 0L) { "Spam-safety timestamps cannot be negative" }
    }

    override fun toString(): String =
        "SpamSafetyDecision(classification=$classification, blocked=$blocked, REDACTED)"
}

data class SpamSafetySnapshot(
    val decisions: List<SpamSafetyDecision>,
    val available: Boolean = true,
) {
    init {
        require(decisions.size <= MAXIMUM_SPAM_SAFETY_DECISIONS)
        require(decisions.distinctBy { it.scope.participantSetKey }.size == decisions.size)
    }

    fun decisionFor(key: SpamParticipantSetKey): SpamSafetyDecision? =
        decisions.firstOrNull { it.scope.participantSetKey == key }

    override fun toString(): String = "SpamSafetySnapshot(decisionCount=${decisions.size}, REDACTED)"

    companion object {
        val Empty = SpamSafetySnapshot(emptyList())
        val Unavailable = SpamSafetySnapshot(emptyList(), available = false)
    }
}

enum class SpamSafetyStorageOperation { READ, WRITE, DELETE, BLOCK_LOOKUP }

sealed interface SpamSafetyRepositoryResult<out T> {
    data class Success<T>(val value: T) : SpamSafetyRepositoryResult<T> {
        override fun toString(): String = "SpamSafetyRepositoryResult.Success(REDACTED)"
    }

    data object NotFound : SpamSafetyRepositoryResult<Nothing>
    data object StaleWrite : SpamSafetyRepositoryResult<Nothing>
    data object LimitReached : SpamSafetyRepositoryResult<Nothing>
    data object InvalidTimestamp : SpamSafetyRepositoryResult<Nothing>
    data object InvalidBlockTarget : SpamSafetyRepositoryResult<Nothing>
    data object CorruptData : SpamSafetyRepositoryResult<Nothing>
    data class StorageFailure(val operation: SpamSafetyStorageOperation) :
        SpamSafetyRepositoryResult<Nothing>
}

interface SpamSafetyRepository {
    val snapshots: Flow<SpamSafetySnapshot>

    suspend fun read(scope: SpamSafetyScope): SpamSafetyRepositoryResult<SpamSafetyDecision>

    /**
     * Writes one exact user decision. Neutral + unblocked clears the row. A null expected revision
     * requires no existing row; all other writes are optimistic and monotonic.
     */
    suspend fun set(
        scope: SpamSafetyScope,
        classification: SpamClassification,
        blocked: Boolean,
        expectedRevision: SpamSafetyRevision?,
        updatedTimestampMillis: Long,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision?>

    /** Failures are explicit so notification callers can safely fail open. */
    suspend fun isSenderBlocked(sender: ParticipantAddress): SpamSafetyRepositoryResult<Boolean>
}

const val MAXIMUM_SPAM_SAFETY_DECISIONS: Int = 256

private fun hashParticipantValues(
    domain: ByteArray,
    participants: Iterable<ParticipantAddress>,
    maximumParticipants: Int,
): String {
    val exact = ArrayList<String>()
    participants.forEach { participant ->
        require(exact.size < maximumParticipants) { "Participant set exceeds its bound" }
        exact += participant.value
    }
    val canonical = exact
        .map { Normalizer.normalize(it, Normalizer.Form.NFC) }
        .distinct()
        .map { value ->
            try {
                value.encodeToByteArray(throwOnInvalidSequence = true)
            } catch (failure: CharacterCodingException) {
                throw IllegalArgumentException("Participant address is not valid UTF-16", failure)
            }
        }
        .sortedWith(::compareUnsignedBytes)
    require(canonical.isNotEmpty()) { "Participant set cannot be empty" }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain)
    digest.update(0)
    digest.updateInt(canonical.size)
    canonical.forEach { bytes ->
        digest.updateInt(bytes.size)
        digest.update(bytes)
    }
    return "sha256-v1:" + digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

private fun requireStorageHash(value: String): String {
    require(value.length == 74 && value.startsWith("sha256-v1:")) { "Invalid stored hash format" }
    require(value.drop(10).all { it in '0'..'9' || it in 'a'..'f' }) {
        "Stored hash is not lowercase SHA-256"
    }
    return value
}

private fun MessageDigest.updateInt(value: Int) {
    require(value >= 0)
    update((value ushr 24).toByte())
    update((value ushr 16).toByte())
    update((value ushr 8).toByte())
    update(value.toByte())
}

private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
    val sharedLength = minOf(first.size, second.size)
    for (index in 0 until sharedLength) {
        val comparison = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return first.size - second.size
}
