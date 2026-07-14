// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.security.MessageDigest
import java.text.Normalizer
import java.nio.charset.CharacterCodingException
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

/** The bounded product surfaces that may inherit or select a named appearance profile. */
enum class AppearanceScreenScope(val storageCode: String) {
    INBOX("inbox"),
    ARCHIVE("archive"),
    SETTINGS("settings"),
    SPAM_BLOCKED("spam_blocked"),
    GLOBAL_THREAD("global_thread"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceScreenScope? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

/**
 * Private, fixed SHA-256 identity for one exact participant set.
 *
 * Values are already-validated [ParticipantAddress] instances. Each value is narrowly normalized
 * to NFC without case folding or number rewriting, then exact-deduplicated and sorted by unsigned
 * UTF-8 bytes. The hash frames a count followed by big-endian byte lengths and bytes. This does not
 * claim E.164 or any other address equivalence.
 */
class AppearanceParticipantSetKey private constructor(
    private val storageValue: String,
) {
    /**
     * Returns the versioned one-way token only for app-private persistence or SavedState identity.
     * This linkable private token must never be logged, displayed, analyzed, or exported.
     */
    fun toPrivateStorageToken(): String = storageValue

    internal fun toStorageValue(): String = toPrivateStorageToken()

    override fun equals(other: Any?): Boolean =
        other is AppearanceParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "AppearanceParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64

        private val DOMAIN_SEPARATOR: ByteArray =
            "AuroraSMS.ConversationAppearanceParticipantSet.v1".encodeToByteArray()
        private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()

        fun fromParticipants(participants: Iterable<ParticipantAddress>): AppearanceParticipantSetKey {
            val exactValues = ArrayList<String>()
            participants.forEach { participant ->
                require(exactValues.size < MAX_PARTICIPANTS) {
                    "An appearance participant set cannot exceed $MAX_PARTICIPANTS entries"
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
                            "An appearance participant address is not valid UTF-16",
                            failure,
                        )
                    }
                }
                .sortedWith(::compareUnsignedBytes)
            require(canonicalBytes.isNotEmpty()) {
                "An appearance participant set cannot be empty"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(canonicalBytes.size)
            canonicalBytes.forEach { bytes ->
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return AppearanceParticipantSetKey(STORAGE_PREFIX + digest.digest().toLowercaseHex())
        }

        internal fun fromStorageValue(value: String): AppearanceParticipantSetKey {
            require(value.length == STORAGE_CHARACTERS) {
                "A stored appearance participant key has an invalid length"
            }
            require(value.startsWith(STORAGE_PREFIX)) {
                "A stored appearance participant key has an unsupported format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A stored appearance participant key is not lowercase SHA-256"
            }
            return AppearanceParticipantSetKey(value)
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

sealed interface AppearanceScope {
    data class Screen(val screen: AppearanceScreenScope) : AppearanceScope

    data class Conversation(
        val participantSetKey: AppearanceParticipantSetKey,
        val providerThreadId: ProviderThreadId,
    ) : AppearanceScope {
        override fun toString(): String = "AppearanceScope.Conversation(REDACTED)"
    }
}

@JvmInline
value class AppearanceOverrideRevision(val value: Long) {
    init {
        require(value > 0L) { "Appearance override revisions must be positive" }
    }

    override fun toString(): String = "AppearanceOverrideRevision(REDACTED)"
}

data class AppearanceOverride(
    val scope: AppearanceScope,
    val profileId: AppearanceProfileId,
    val revision: AppearanceOverrideRevision,
) {
    override fun toString(): String = "AppearanceOverride(REDACTED)"
}
