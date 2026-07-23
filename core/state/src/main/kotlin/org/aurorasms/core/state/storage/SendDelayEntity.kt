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
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.state.SendDelayId
import org.aurorasms.core.state.SendDelayOperation
import org.aurorasms.core.state.SendDelayParticipantSetKey
import org.aurorasms.core.state.SendDelayPhase
import org.aurorasms.core.state.SendDelayReviewReason

internal const val SEND_DELAY_OPERATIONS_TABLE = "send_delay_operations"

@Entity(
    tableName = SEND_DELAY_OPERATIONS_TABLE,
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["draft_id"], unique = true),
        Index(value = ["due_timestamp_ms", "send_delay_id"]),
    ],
)
internal data class SendDelayEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "send_delay_id") val sendDelayId: Long = 0L,
    @ColumnInfo(name = "participant_set_key") val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id") val providerThreadId: Long,
    @ColumnInfo(name = "draft_id") val draftId: Long,
    @ColumnInfo(name = "draft_revision_ms") val draftRevisionMillis: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    @ColumnInfo(name = "due_timestamp_ms") val dueTimestampMillis: Long,
    @ColumnInfo(name = "phase_code") val phaseCode: String,
    @ColumnInfo(name = "review_reason_code") val reviewReasonCode: String?,
    @ColumnInfo(name = "armed_wall_timestamp_ms") val armedWallTimestampMillis: Long,
    @ColumnInfo(name = "armed_elapsed_realtime_ms") val armedElapsedRealtimeMillis: Long,
    @ColumnInfo(name = "created_timestamp_ms") val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms") val updatedTimestampMillis: Long,
    @ColumnInfo(name = "signature_text") val signatureText: String? = null,
) {
    override fun toString(): String = "SendDelayEntity(phaseCode=$phaseCode, REDACTED)"
}

internal fun SendDelayEntity.toDomain(): SendDelayOperation = SendDelayOperation(
    id = SendDelayId(sendDelayId),
    participantSetKey = SendDelayParticipantSetKey.fromStorageValue(participantSetKey),
    providerThreadId = ProviderThreadId(providerThreadId),
    draftId = DraftId(draftId),
    draftRevision = DraftRevision(draftRevisionMillis),
    subscriptionId = AuroraSubscriptionId(subscriptionId),
    dueTimestampMillis = dueTimestampMillis,
    phase = sendDelayPhaseFromCode(phaseCode) ?: error("Unknown send-delay phase"),
    reviewReason = reviewReasonCode?.let {
        sendDelayReviewReasonFromCode(it) ?: error("Unknown send-delay review reason")
    },
    armedWallTimestampMillis = armedWallTimestampMillis,
    armedElapsedRealtimeMillis = armedElapsedRealtimeMillis,
    createdTimestampMillis = createdTimestampMillis,
    updatedTimestampMillis = updatedTimestampMillis,
    frozenSignature = signatureText?.let(MessageSignature::fromStorageValue),
)

internal val SendDelayPhase.storageCode: String
    get() = when (this) {
        SendDelayPhase.PENDING -> "pending_v1"
        SendDelayPhase.DISPATCHING -> "dispatching_v1"
        SendDelayPhase.REVIEW_REQUIRED -> "review_required_v1"
    }

internal val SendDelayReviewReason.storageCode: String
    get() = when (this) {
        SendDelayReviewReason.CLOCK_CHANGED -> "clock_changed_v1"
        SendDelayReviewReason.MISSED_AFTER_RESTART -> "missed_after_restart_v1"
        SendDelayReviewReason.PRECONDITION_FAILED -> "precondition_failed_v1"
        SendDelayReviewReason.ARMING_FAILED -> "arming_failed_v1"
        SendDelayReviewReason.INTERRUPTED_BEFORE_RESERVATION ->
            "interrupted_before_reservation_v1"
    }

private fun sendDelayPhaseFromCode(value: String): SendDelayPhase? = when (value) {
    "pending_v1" -> SendDelayPhase.PENDING
    "dispatching_v1" -> SendDelayPhase.DISPATCHING
    "review_required_v1" -> SendDelayPhase.REVIEW_REQUIRED
    else -> null
}

private fun sendDelayReviewReasonFromCode(value: String): SendDelayReviewReason? = when (value) {
    "clock_changed_v1" -> SendDelayReviewReason.CLOCK_CHANGED
    "missed_after_restart_v1" -> SendDelayReviewReason.MISSED_AFTER_RESTART
    "precondition_failed_v1" -> SendDelayReviewReason.PRECONDITION_FAILED
    "arming_failed_v1" -> SendDelayReviewReason.ARMING_FAILED
    "interrupted_before_reservation_v1" -> SendDelayReviewReason.INTERRUPTED_BEFORE_RESERVATION
    else -> null
}
