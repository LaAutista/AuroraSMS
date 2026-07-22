// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ParticipantAddressEquivalenceKey
import org.aurorasms.core.model.ProviderThreadId

@JvmInline
value class FirstContactOperationId(val value: Long) {
    init {
        require(value > 0L) { "First-contact operation IDs must be positive" }
    }

    override fun toString(): String = "FirstContactOperationId(REDACTED)"
}

@JvmInline
value class FirstContactOperationRevision(val updatedTimestampMillis: Long) {
    init {
        require(updatedTimestampMillis >= 0L) {
            "First-contact operation revisions cannot be negative"
        }
    }

    override fun toString(): String = "FirstContactOperationRevision(REDACTED)"
}

/** Purpose-separated semantic recipient identity; never log, display, analyze, or export it. */
class FirstContactParticipantSetKey private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is FirstContactParticipantSetKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "FirstContactParticipantSetKey(REDACTED)"

    companion object {
        const val MAX_PARTICIPANTS: Int = 100
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64
        private val DOMAIN_SEPARATOR: ByteArray =
            "AuroraSMS.FirstContactParticipantSet.v1".encodeToByteArray()
        private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()

        fun fromParticipants(
            participants: Iterable<ParticipantAddress>,
        ): FirstContactParticipantSetKey {
            val semanticKeys = ArrayList<ParticipantAddressEquivalenceKey>()
            participants.forEach { participant ->
                require(semanticKeys.size < MAX_PARTICIPANTS) {
                    "A first-contact participant set cannot exceed $MAX_PARTICIPANTS entries"
                }
                semanticKeys += requireNotNull(ParticipantAddressEquivalenceKey.from(participant)) {
                    "A first-contact participant address has no supported semantic identity"
                }
            }
            val canonicalKeys = semanticKeys.distinct().sorted()
            require(canonicalKeys.isNotEmpty()) {
                "A first-contact participant set cannot be empty"
            }
            require(canonicalKeys.size == semanticKeys.size) {
                "A first-contact participant set contains semantically duplicate addresses"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(canonicalKeys.size)
            canonicalKeys.forEach { it.updateDigest(digest) }
            return FirstContactParticipantSetKey(
                STORAGE_PREFIX + digest.digest().toLowercaseHex(),
            )
        }

        internal fun fromStorageValue(value: String): FirstContactParticipantSetKey {
            require(value.length == STORAGE_CHARACTERS && value.startsWith(STORAGE_PREFIX)) {
                "A stored first-contact participant key has an invalid format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A stored first-contact participant key is not lowercase SHA-256"
            }
            return FirstContactParticipantSetKey(value)
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
                append(LOWERCASE_HEX[value ushr 4])
                append(LOWERCASE_HEX[value and 0x0f])
            }
        }
    }
}

/** Purpose-separated evidence for one ordered attachment snapshot, including the empty set. */
class FirstContactAttachmentSetEvidence private constructor(private val storageValue: String) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is FirstContactAttachmentSetEvidence && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "FirstContactAttachmentSetEvidence(REDACTED)"

    companion object {
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64
        private val DOMAIN_SEPARATOR: ByteArray =
            "AuroraSMS.FirstContactAttachmentSet.v1".encodeToByteArray()
        private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()

        fun fromAttachments(
            attachments: List<DraftAttachment>,
        ): FirstContactAttachmentSetEvidence {
            require(DraftAttachment.isValidSet(attachments))
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN_SEPARATOR)
            digest.update(0.toByte())
            digest.updateInt(attachments.size)
            attachments.forEachIndexed { index, attachment ->
                digest.updateInt(index)
                val contentType = attachment.contentType.encodeToByteArray()
                digest.updateInt(contentType.size)
                digest.update(contentType)
                val bytes = attachment.copyBytes()
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return FirstContactAttachmentSetEvidence(
                STORAGE_PREFIX + digest.digest().toLowercaseHex(),
            )
        }

        internal fun fromStorageValue(value: String): FirstContactAttachmentSetEvidence {
            require(value.length == STORAGE_CHARACTERS && value.startsWith(STORAGE_PREFIX)) {
                "Stored first-contact attachment evidence has an invalid format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "Stored first-contact attachment evidence is not lowercase SHA-256"
            }
            return FirstContactAttachmentSetEvidence(value)
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
                append(LOWERCASE_HEX[value ushr 4])
                append(LOWERCASE_HEX[value and 0x0f])
            }
        }
    }
}

/** Stable checkpoints before N2C transfers this owner to the existing composer owner. */
enum class FirstContactOperationPhase {
    RESERVED,
    RESOLUTION_STARTED,
    THREAD_BOUND,
    HANDOFF_RESERVED,
    RESOLUTION_UNKNOWN,
    KNOWN_UNSENT,
}

/** Exact pre-resolution evidence required before first-contact ownership may become retryable. */
enum class FirstContactKnownUnsentProof {
    /** Provider resolution was never durably started and provider authority was never entered. */
    PROVIDER_AUTHORITY_NOT_ENTERED,
}

/** Content-free ownership for one explicit first-contact action. */
data class FirstContactOperation(
    val id: FirstContactOperationId,
    val participantSetKey: FirstContactParticipantSetKey,
    val draftId: DraftId,
    val sourceDraftRevision: DraftRevision,
    val attachmentSetEvidence: FirstContactAttachmentSetEvidence,
    val subscriptionId: AuroraSubscriptionId,
    val transport: MessageTransportKind,
    val phase: FirstContactOperationPhase,
    val providerThreadId: ProviderThreadId?,
    val handoffDraftRevision: DraftRevision?,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
    val frozenSignature: MessageSignature? = null,
) {
    init {
        require(
            createdTimestampMillis >= 0L &&
                updatedTimestampMillis in createdTimestampMillis until Long.MAX_VALUE,
        ) { "First-contact operation timestamps are invalid" }
        require(handoffDraftRevision == null || providerThreadId != null) {
            "A first-contact handoff revision requires a bound provider thread"
        }
        require(
            handoffDraftRevision == null ||
                handoffDraftRevision.updatedTimestampMillis >
                sourceDraftRevision.updatedTimestampMillis,
        ) { "A first-contact handoff revision must advance the source draft" }
        require(phase.acceptsBinding(providerThreadId, handoffDraftRevision)) {
            "First-contact phase and provider-thread binding disagree"
        }
    }

    val revision: FirstContactOperationRevision
        get() = FirstContactOperationRevision(updatedTimestampMillis)

    override fun toString(): String =
        "FirstContactOperation(phase=$phase, hasThread=${providerThreadId != null}, " +
            "hasHandoff=${handoffDraftRevision != null}, REDACTED)"
}

data class FirstContactReservationRequest(
    val participants: List<ParticipantAddress>,
    val draftId: DraftId,
    val expectedDraftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val transport: MessageTransportKind,
    val createdTimestampMillis: Long,
    val frozenSignature: MessageSignature? = null,
) {
    init {
        require(participants.size in 1..FirstContactParticipantSetKey.MAX_PARTICIPANTS)
        require(createdTimestampMillis >= 0L && createdTimestampMillis < Long.MAX_VALUE)
        FirstContactParticipantSetKey.fromParticipants(participants)
        DraftParticipantSetKey.fromParticipants(participants)
    }

    override fun toString(): String = "FirstContactReservationRequest(REDACTED)"
}

/** Exact recipients re-read with the durable pre-provider checkpoint. Never persist this value. */
data class FirstContactResolutionSnapshot(
    val operation: FirstContactOperation,
    val participants: List<ParticipantAddress>,
) {
    init {
        require(operation.phase == FirstContactOperationPhase.RESOLUTION_STARTED)
        require(participants.size in 1..FirstContactParticipantSetKey.MAX_PARTICIPANTS)
        require(
            operation.participantSetKey ==
                FirstContactParticipantSetKey.fromParticipants(participants),
        )
    }

    override fun toString(): String = "FirstContactResolutionSnapshot(REDACTED)"
}

/**
 * Transient result of the atomic participant-draft to provider-thread bridge.
 *
 * N2B deliberately leaves [operation] in [FirstContactOperationPhase.HANDOFF_RESERVED]. Only a
 * future atomic N2C Composer reservation/adjudication may retire that durable owner. The raw
 * participants and attachment bytes in this value are bounded transient data and are never copied
 * into `first_contact_operations`.
 */
data class FirstContactBridgeSnapshot(
    val operation: FirstContactOperation,
    val providerDraft: Draft,
    val participants: List<ParticipantAddress>,
    val attachments: List<DraftAttachment>,
) {
    init {
        require(operation.phase == FirstContactOperationPhase.HANDOFF_RESERVED)
        require(providerDraft.id == operation.draftId)
        require(providerDraft.revision == operation.handoffDraftRevision)
        require(
            providerDraft.identity == operation.providerThreadId?.let(DraftIdentity::ProviderThread),
        )
        require(participants.size in 1..FirstContactParticipantSetKey.MAX_PARTICIPANTS)
        require(
            operation.participantSetKey ==
                FirstContactParticipantSetKey.fromParticipants(participants),
        )
        require(DraftAttachment.isValidSet(attachments))
        require(
            operation.attachmentSetEvidence ==
                FirstContactAttachmentSetEvidence.fromAttachments(attachments),
        )
    }

    override fun toString(): String = "FirstContactBridgeSnapshot(REDACTED)"
}

enum class FirstContactStorageOperation {
    RESERVE,
    READ,
    OBSERVE,
    RECOVER,
    TRANSITION,
    BRIDGE,
    RELEASE,
}

sealed interface FirstContactOperationResult<out T> {
    data class Success<T>(val value: T) : FirstContactOperationResult<T> {
        override fun toString(): String = "FirstContactOperationResult.Success(REDACTED)"
    }

    data object NotFound : FirstContactOperationResult<Nothing>
    data object Conflict : FirstContactOperationResult<Nothing>
    data object LimitExceeded : FirstContactOperationResult<Nothing>
    data object StaleWrite : FirstContactOperationResult<Nothing>
    data object IneligibleDraft : FirstContactOperationResult<Nothing>
    data object PhaseMismatch : FirstContactOperationResult<Nothing>
    data object InvalidTimestamp : FirstContactOperationResult<Nothing>
    data object CorruptData : FirstContactOperationResult<Nothing>
    data class StorageFailure(
        val operation: FirstContactStorageOperation,
    ) : FirstContactOperationResult<Nothing>
}

interface FirstContactOperationRepository {
    suspend fun reserve(
        request: FirstContactReservationRequest,
    ): FirstContactOperationResult<FirstContactOperation>

    suspend fun read(id: FirstContactOperationId): FirstContactOperationResult<FirstContactOperation>

    suspend fun readByDraft(draftId: DraftId): FirstContactOperationResult<FirstContactOperation>

    fun observeByDraft(
        draftId: DraftId,
    ): Flow<FirstContactOperationResult<FirstContactOperation?>>

    suspend fun recoverySnapshot(): FirstContactOperationResult<List<FirstContactOperation>>

    suspend fun markResolutionStarted(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactResolutionSnapshot>

    suspend fun bindThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        providerThreadId: ProviderThreadId,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation>

    suspend fun markResolutionUnknown(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation>

    suspend fun bridgeToProviderThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactBridgeSnapshot>

    suspend fun markKnownUnsent(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        proof: FirstContactKnownUnsentProof,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation>

    /** Deletes only exact KNOWN_UNSENT ownership; HANDOFF_RESERVED cannot be released in N2B. */
    suspend fun release(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
    ): FirstContactOperationResult<Unit>
}

internal fun FirstContactOperationPhase.acceptsBinding(
    providerThreadId: ProviderThreadId?,
    handoffDraftRevision: DraftRevision?,
): Boolean = when (this) {
    FirstContactOperationPhase.RESERVED,
    FirstContactOperationPhase.RESOLUTION_STARTED,
    FirstContactOperationPhase.RESOLUTION_UNKNOWN,
    -> providerThreadId == null && handoffDraftRevision == null
    FirstContactOperationPhase.THREAD_BOUND ->
        providerThreadId != null && handoffDraftRevision == null
    FirstContactOperationPhase.HANDOFF_RESERVED ->
        providerThreadId != null && handoffDraftRevision != null
    FirstContactOperationPhase.KNOWN_UNSENT ->
        providerThreadId == null && handoffDraftRevision == null
}

const val MAXIMUM_FIRST_CONTACT_OPERATIONS: Int = 128
