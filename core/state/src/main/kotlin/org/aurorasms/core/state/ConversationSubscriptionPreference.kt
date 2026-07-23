// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.text.Normalizer
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

/**
 * Private, purpose-separated identity for one exact conversation participant set.
 *
 * The token is deliberately different from appearance and draft identities so separate durable
 * state tables cannot be correlated by copying the same participant-set value. It must never be
 * logged, displayed, analyzed, or exported.
 */
class ConversationSubscriptionParticipantSetKey private constructor(
    private val storageValue: String,
) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is ConversationSubscriptionParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "ConversationSubscriptionParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64

        private val DOMAIN_SEPARATOR: ByteArray =
            "AuroraSMS.ConversationSubscriptionParticipantSet.v1".encodeToByteArray()
        private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()

        fun fromParticipants(
            participants: Iterable<ParticipantAddress>,
        ): ConversationSubscriptionParticipantSetKey {
            val exactValues = ArrayList<String>()
            participants.forEach { participant ->
                require(exactValues.size < MAX_PARTICIPANTS) {
                    "A conversation subscription participant set cannot exceed " +
                        "$MAX_PARTICIPANTS entries"
                }
                exactValues += participant.value
            }
            val canonicalBytes = exactValues
                .map { value ->
                    ParticipantAddress(Normalizer.normalize(value, Normalizer.Form.NFC)).value
                }
                .distinct()
                .map { value ->
                    try {
                        value.encodeToByteArray(throwOnInvalidSequence = true)
                    } catch (failure: CharacterCodingException) {
                        throw IllegalArgumentException(
                            "A conversation subscription participant address is not valid UTF-16",
                            failure,
                        )
                    }
                }
                .sortedWith(::compareUnsignedBytes)
            require(canonicalBytes.isNotEmpty()) {
                "A conversation subscription participant set cannot be empty"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(canonicalBytes.size)
            canonicalBytes.forEach { bytes ->
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return ConversationSubscriptionParticipantSetKey(
                STORAGE_PREFIX + digest.digest().toLowercaseHex(),
            )
        }

        internal fun fromStorageValue(value: String): ConversationSubscriptionParticipantSetKey {
            require(value.length == STORAGE_CHARACTERS) {
                "A stored conversation subscription participant key has an invalid length"
            }
            require(value.startsWith(STORAGE_PREFIX)) {
                "A stored conversation subscription participant key has an unsupported format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A stored conversation subscription participant key is not lowercase SHA-256"
            }
            return ConversationSubscriptionParticipantSetKey(value)
        }

        private fun MessageDigest.updateInt(value: Int) {
            require(value >= 0) { "A participant hash length cannot be negative" }
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }

        private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
            this@toLowercaseHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(LOWERCASE_HEX[value ushr 4])
                append(LOWERCASE_HEX[value and 0x0f])
            }
        }

        private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
            val sharedLength = minOf(first.size, second.size)
            for (index in 0 until sharedLength) {
                val comparison = (first[index].toInt() and 0xff) -
                    (second[index].toInt() and 0xff)
                if (comparison != 0) return comparison
            }
            return first.size - second.size
        }
    }
}

data class ConversationSubscriptionScope(
    val participantSetKey: ConversationSubscriptionParticipantSetKey,
    val providerThreadId: ProviderThreadId,
) {
    override fun toString(): String = "ConversationSubscriptionScope(REDACTED)"
}

@JvmInline
value class ConversationSubscriptionRevision(val value: Long) {
    init {
        require(value > 0L) { "Conversation subscription revisions must be positive" }
    }

    override fun toString(): String = "ConversationSubscriptionRevision(REDACTED)"
}

data class ConversationSubscriptionPreference(
    val scope: ConversationSubscriptionScope,
    val subscriptionId: AuroraSubscriptionId,
    val revision: ConversationSubscriptionRevision,
    val updatedTimestampMillis: Long,
) {
    init {
        require(updatedTimestampMillis >= 0L) {
            "Conversation subscription timestamps cannot be negative"
        }
    }

    override fun toString(): String = "ConversationSubscriptionPreference(REDACTED)"
}

enum class ConversationSubscriptionStorageOperation {
    READ,
    SET,
}

sealed interface ConversationSubscriptionRepositoryResult<out T> {
    data class Success<T>(val value: T) : ConversationSubscriptionRepositoryResult<T> {
        override fun toString(): String = "ConversationSubscriptionRepositoryResult.Success(REDACTED)"
    }

    data object NotFound : ConversationSubscriptionRepositoryResult<Nothing>
    data object StaleWrite : ConversationSubscriptionRepositoryResult<Nothing>
    data object InvalidTimestamp : ConversationSubscriptionRepositoryResult<Nothing>
    data object CorruptData : ConversationSubscriptionRepositoryResult<Nothing>

    data class StorageFailure(
        val operation: ConversationSubscriptionStorageOperation,
    ) : ConversationSubscriptionRepositoryResult<Nothing>
}

interface ConversationSubscriptionPreferenceRepository {
    suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference>

    /**
     * Creates or revision-checks one preference. A null expected revision requires no stored row.
     */
    suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference>
}
