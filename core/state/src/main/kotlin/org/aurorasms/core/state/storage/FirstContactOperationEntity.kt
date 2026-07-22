// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactOperation
import org.aurorasms.core.state.FirstContactOperationId
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.aurorasms.core.state.MessageSignature

internal const val FIRST_CONTACT_OPERATIONS_TABLE: String = "first_contact_operations"

@Entity(
    tableName = FIRST_CONTACT_OPERATIONS_TABLE,
    indices = [
        Index(value = ["participant_set_key"], unique = true),
        Index(value = ["draft_id"], unique = true),
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["updated_timestamp_ms", "first_contact_id"]),
    ],
)
internal data class FirstContactOperationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "first_contact_id")
    val firstContactId: Long = 0L,
    @ColumnInfo(name = "participant_set_key")
    val participantSetKey: String,
    @ColumnInfo(name = "draft_id")
    val draftId: Long,
    @ColumnInfo(name = "source_draft_revision_ms")
    val sourceDraftRevisionMillis: Long,
    @ColumnInfo(name = "attachment_set_evidence")
    val attachmentSetEvidence: String,
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int,
    @ColumnInfo(name = "transport_code")
    val transportCode: String,
    @ColumnInfo(name = "phase_code")
    val phaseCode: String,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long?,
    @ColumnInfo(name = "handoff_draft_revision_ms")
    val handoffDraftRevisionMillis: Long?,
    @ColumnInfo(name = "created_timestamp_ms")
    val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
    @ColumnInfo(name = "signature_text")
    val signatureText: String? = null,
) {
    override fun toString(): String =
        "FirstContactOperationEntity(phaseCode=$phaseCode, " +
            "hasThread=${providerThreadId != null}, hasHandoff=${handoffDraftRevisionMillis != null}, " +
            "REDACTED)"
}

internal fun FirstContactOperationEntity.toDomain(): FirstContactOperation = FirstContactOperation(
    id = FirstContactOperationId(firstContactId),
    participantSetKey = FirstContactParticipantSetKey.fromStorageValue(participantSetKey),
    draftId = DraftId(draftId),
    sourceDraftRevision = DraftRevision(sourceDraftRevisionMillis),
    attachmentSetEvidence =
        FirstContactAttachmentSetEvidence.fromStorageValue(attachmentSetEvidence),
    subscriptionId = AuroraSubscriptionId(subscriptionId),
    transport = messageTransportKindFromStorageCode(transportCode)
        ?: error("Stored first-contact transport code is unknown"),
    phase = firstContactPhaseFromStorageCode(phaseCode)
        ?: error("Stored first-contact phase code is unknown"),
    providerThreadId = providerThreadId?.let(::ProviderThreadId),
    handoffDraftRevision = handoffDraftRevisionMillis?.let(::DraftRevision),
    createdTimestampMillis = createdTimestampMillis,
    updatedTimestampMillis = updatedTimestampMillis,
    frozenSignature = signatureText?.let(MessageSignature::fromStorageValue),
)

internal val FirstContactOperationPhase.storageCode: String
    get() = when (this) {
        FirstContactOperationPhase.RESERVED -> "reserved_v1"
        FirstContactOperationPhase.RESOLUTION_STARTED -> "resolution_started_v1"
        FirstContactOperationPhase.THREAD_BOUND -> "thread_bound_v1"
        FirstContactOperationPhase.HANDOFF_RESERVED -> "handoff_reserved_v1"
        FirstContactOperationPhase.RESOLUTION_UNKNOWN -> "resolution_unknown_v1"
        FirstContactOperationPhase.KNOWN_UNSENT -> "known_unsent_v1"
    }

internal fun firstContactPhaseFromStorageCode(value: String): FirstContactOperationPhase? =
    when (value) {
        "reserved_v1" -> FirstContactOperationPhase.RESERVED
        "resolution_started_v1" -> FirstContactOperationPhase.RESOLUTION_STARTED
        "thread_bound_v1" -> FirstContactOperationPhase.THREAD_BOUND
        "handoff_reserved_v1" -> FirstContactOperationPhase.HANDOFF_RESERVED
        "resolution_unknown_v1" -> FirstContactOperationPhase.RESOLUTION_UNKNOWN
        "known_unsent_v1" -> FirstContactOperationPhase.KNOWN_UNSENT
        else -> null
    }
