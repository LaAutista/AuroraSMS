// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

/** Stable Aurora-owned identity for one persisted draft. */
@JvmInline
value class DraftId(val value: Long) {
    init {
        require(value > 0L) { "Draft IDs must be positive" }
    }

    override fun toString(): String = "DraftId(REDACTED)"
}

/** Optimistic-concurrency token derived from the last committed update time. */
@JvmInline
value class DraftRevision(val updatedTimestampMillis: Long) {
    init {
        require(updatedTimestampMillis >= 0L) { "Draft revisions cannot be negative" }
    }

    override fun toString(): String = "DraftRevision(REDACTED)"
}

/**
 * Deterministic private-storage key for a not-yet-created provider thread.
 *
 * This key deliberately does not claim E.164 or equivalent-address
 * normalization. It only removes exact duplicates and sorts the already
 * validated platform addresses so the same set has one stable representation.
 */
class DraftParticipantSetKey private constructor(
    private val storageValue: String,
) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is DraftParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "DraftParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        const val MAX_STORAGE_CHARACTERS: Int =
            (ParticipantAddress.MAX_ADDRESS_CHARACTERS * MAX_PARTICIPANTS) +
                (MAX_PARTICIPANTS - 1)

        private const val SEPARATOR: Char = '\u001f'

        fun fromParticipants(participants: Iterable<ParticipantAddress>): DraftParticipantSetKey {
            val values = ArrayList<String>()
            participants.forEach { participant ->
                require(values.size < MAX_PARTICIPANTS) {
                    "A draft participant set cannot exceed $MAX_PARTICIPANTS entries"
                }
                values += participant.value
            }
            val canonicalValues = values.distinct().sorted()
            require(canonicalValues.isNotEmpty()) { "A draft participant set cannot be empty" }
            return fromCanonicalValues(canonicalValues)
        }

        internal fun fromStorageValue(value: String): DraftParticipantSetKey {
            require(value.isNotEmpty()) { "A stored draft participant set cannot be empty" }
            require(value.length <= MAX_STORAGE_CHARACTERS) {
                "A stored draft participant set is too large"
            }
            val values = value.split(SEPARATOR)
            require(values.size in 1..MAX_PARTICIPANTS) {
                "A stored draft participant set has an invalid size"
            }
            val addresses = values.map(::ParticipantAddress)
            val canonical = addresses.map(ParticipantAddress::value).distinct().sorted()
            require(values == canonical) { "A stored draft participant set is not canonical" }
            return fromCanonicalValues(canonical)
        }

        private fun fromCanonicalValues(values: List<String>): DraftParticipantSetKey {
            val value = values.joinToString(separator = SEPARATOR.toString())
            require(value.length <= MAX_STORAGE_CHARACTERS) {
                "A draft participant set is too large"
            }
            return DraftParticipantSetKey(value)
        }
    }
}

/** A provider-backed conversation or a canonical set of new-chat participants. */
sealed interface DraftIdentity {
    data class ProviderThread(val providerThreadId: ProviderThreadId) : DraftIdentity {
        override fun toString(): String = "DraftIdentity.ProviderThread(REDACTED)"
    }

    data class ParticipantSet(val key: DraftParticipantSetKey) : DraftIdentity {
        override fun toString(): String = "DraftIdentity.ParticipantSet(REDACTED)"
    }
}

/** Validated content and identity used to create a persisted draft. */
data class NewDraft(
    val identity: DraftIdentity,
    val body: String?,
    val subject: String?,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        validateDraftFields(body, subject, createdTimestampMillis, updatedTimestampMillis)
    }

    override fun toString(): String = "NewDraft(REDACTED)"
}

/** One durable draft with a stable Aurora-owned local ID. */
data class Draft(
    val id: DraftId,
    val identity: DraftIdentity,
    val body: String?,
    val subject: String?,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        validateDraftFields(body, subject, createdTimestampMillis, updatedTimestampMillis)
    }

    val revision: DraftRevision
        get() = DraftRevision(updatedTimestampMillis)

    override fun toString(): String = "Draft(REDACTED)"

    companion object {
        const val MAX_BODY_CHARACTERS: Int = 100_000
        const val MAX_SUBJECT_CHARACTERS: Int = 1_000
    }
}

private fun validateDraftFields(
    body: String?,
    subject: String?,
    createdTimestampMillis: Long,
    updatedTimestampMillis: Long,
) {
    require(body == null || body.length <= Draft.MAX_BODY_CHARACTERS) {
        "A draft body is too large"
    }
    require(subject == null || subject.length <= Draft.MAX_SUBJECT_CHARACTERS) {
        "A draft subject is too large"
    }
    require(createdTimestampMillis >= 0L) { "A draft creation timestamp cannot be negative" }
    require(updatedTimestampMillis >= createdTimestampMillis) {
        "A draft update timestamp cannot precede its creation timestamp"
    }
}
