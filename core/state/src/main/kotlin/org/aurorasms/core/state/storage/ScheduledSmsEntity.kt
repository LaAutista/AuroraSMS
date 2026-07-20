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
import org.aurorasms.core.state.ScheduledSms
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.ScheduledSmsParticipantSetKey
import org.aurorasms.core.state.ScheduledSmsPhase
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.ScheduledSmsReviewReason

internal const val SCHEDULED_SMS_OPERATIONS_TABLE = "scheduled_sms_operations"

@Entity(
    tableName = SCHEDULED_SMS_OPERATIONS_TABLE,
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["draft_id"], unique = true),
        Index(value = ["due_timestamp_ms", "schedule_id"]),
    ],
)
internal data class ScheduledSmsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "schedule_id") val scheduleId: Long = 0L,
    @ColumnInfo(name = "participant_set_key") val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id") val providerThreadId: Long,
    @ColumnInfo(name = "draft_id") val draftId: Long,
    @ColumnInfo(name = "draft_revision_ms") val draftRevisionMillis: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    @ColumnInfo(name = "due_timestamp_ms") val dueTimestampMillis: Long,
    @ColumnInfo(name = "phase_code") val phaseCode: String,
    @ColumnInfo(name = "precision_code") val precisionCode: String,
    @ColumnInfo(name = "review_reason_code") val reviewReasonCode: String?,
    @ColumnInfo(name = "armed_wall_timestamp_ms") val armedWallTimestampMillis: Long,
    @ColumnInfo(name = "armed_elapsed_realtime_ms") val armedElapsedRealtimeMillis: Long,
    @ColumnInfo(name = "created_timestamp_ms") val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms") val updatedTimestampMillis: Long,
    @ColumnInfo(name = "signature_text") val signatureText: String? = null,
) {
    override fun toString(): String = "ScheduledSmsEntity(phaseCode=$phaseCode, REDACTED)"
}

internal fun ScheduledSmsEntity.toDomain(): ScheduledSms = ScheduledSms(
    id = ScheduledSmsId(scheduleId),
    participantSetKey = ScheduledSmsParticipantSetKey.fromStorageValue(participantSetKey),
    providerThreadId = ProviderThreadId(providerThreadId),
    draftId = DraftId(draftId),
    draftRevision = DraftRevision(draftRevisionMillis),
    subscriptionId = AuroraSubscriptionId(subscriptionId),
    dueTimestampMillis = dueTimestampMillis,
    phase = scheduledPhaseFromCode(phaseCode) ?: error("Unknown scheduled SMS phase"),
    precision = scheduledPrecisionFromCode(precisionCode) ?: error("Unknown scheduled SMS precision"),
    reviewReason = reviewReasonCode?.let {
        scheduledReviewReasonFromCode(it) ?: error("Unknown scheduled SMS review reason")
    },
    armedWallTimestampMillis = armedWallTimestampMillis,
    armedElapsedRealtimeMillis = armedElapsedRealtimeMillis,
    createdTimestampMillis = createdTimestampMillis,
    updatedTimestampMillis = updatedTimestampMillis,
    frozenSignature = signatureText?.let(MessageSignature::fromStorageValue),
)

internal val ScheduledSmsPhase.storageCode: String
    get() = when (this) {
        ScheduledSmsPhase.PENDING -> "pending_v1"
        ScheduledSmsPhase.DISPATCHING -> "dispatching_v1"
        ScheduledSmsPhase.REVIEW_REQUIRED -> "review_required_v1"
    }

internal val ScheduledSmsPrecision.storageCode: String
    get() = when (this) {
        ScheduledSmsPrecision.EXACT -> "exact_v1"
        ScheduledSmsPrecision.INEXACT -> "inexact_v1"
    }

internal val ScheduledSmsReviewReason.storageCode: String
    get() = when (this) {
        ScheduledSmsReviewReason.CLOCK_CHANGED -> "clock_changed_v1"
        ScheduledSmsReviewReason.MISSED_AFTER_RESTART -> "missed_after_restart_v1"
        ScheduledSmsReviewReason.PRECONDITION_FAILED -> "precondition_failed_v1"
        ScheduledSmsReviewReason.ARMING_FAILED -> "arming_failed_v1"
        ScheduledSmsReviewReason.INTERRUPTED_BEFORE_RESERVATION -> "interrupted_before_reservation_v1"
    }

private fun scheduledPhaseFromCode(value: String) = when (value) {
    "pending_v1" -> ScheduledSmsPhase.PENDING
    "dispatching_v1" -> ScheduledSmsPhase.DISPATCHING
    "review_required_v1" -> ScheduledSmsPhase.REVIEW_REQUIRED
    else -> null
}

private fun scheduledPrecisionFromCode(value: String) = when (value) {
    "exact_v1" -> ScheduledSmsPrecision.EXACT
    "inexact_v1" -> ScheduledSmsPrecision.INEXACT
    else -> null
}

private fun scheduledReviewReasonFromCode(value: String) = when (value) {
    "clock_changed_v1" -> ScheduledSmsReviewReason.CLOCK_CHANGED
    "missed_after_restart_v1" -> ScheduledSmsReviewReason.MISSED_AFTER_RESTART
    "precondition_failed_v1" -> ScheduledSmsReviewReason.PRECONDITION_FAILED
    "arming_failed_v1" -> ScheduledSmsReviewReason.ARMING_FAILED
    "interrupted_before_reservation_v1" -> ScheduledSmsReviewReason.INTERRUPTED_BEFORE_RESERVATION
    else -> null
}
