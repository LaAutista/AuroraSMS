// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.PermanentDeletionOperation
import org.aurorasms.core.state.PermanentDeletionPhase
import org.aurorasms.core.state.PermanentDeletionReviewReason
import org.aurorasms.core.state.PermanentDeletionTarget

internal const val PERMANENT_DELETION_OPERATIONS_TABLE = "permanent_deletion_operations"

@Entity(
    tableName = PERMANENT_DELETION_OPERATIONS_TABLE,
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["provider_kind", "provider_message_id"], unique = true),
        Index(value = ["due_timestamp_ms", "deletion_id"]),
    ],
)
internal data class PermanentDeletionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "deletion_id") val deletionId: Long = 0L,
    @ColumnInfo(name = "target_kind_code") val targetKindCode: String,
    @ColumnInfo(name = "provider_thread_id") val providerThreadId: Long,
    @ColumnInfo(name = "provider_kind") val providerKind: Int?,
    @ColumnInfo(name = "provider_message_id") val providerMessageId: Long?,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String?,
    @ColumnInfo(name = "sms_count") val smsCount: Long?,
    @ColumnInfo(name = "latest_sms_id") val latestSmsId: Long?,
    @ColumnInfo(name = "mms_count") val mmsCount: Long?,
    @ColumnInfo(name = "latest_mms_id") val latestMmsId: Long?,
    @ColumnInfo(name = "draft_id") val draftId: Long?,
    @ColumnInfo(name = "draft_revision_ms") val draftRevisionMillis: Long?,
    @ColumnInfo(name = "due_timestamp_ms") val dueTimestampMillis: Long,
    @ColumnInfo(name = "phase_code") val phaseCode: String,
    @ColumnInfo(name = "review_reason_code") val reviewReasonCode: String?,
    @ColumnInfo(name = "armed_wall_timestamp_ms") val armedWallTimestampMillis: Long,
    @ColumnInfo(name = "armed_elapsed_realtime_ms") val armedElapsedRealtimeMillis: Long,
    @ColumnInfo(name = "created_timestamp_ms") val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms") val updatedTimestampMillis: Long,
) {
    override fun toString(): String = "PermanentDeletionEntity(phaseCode=$phaseCode, REDACTED)"
}

internal fun PermanentDeletionEntity.toDomain(): PermanentDeletionOperation {
    val target = when (targetKindCode) {
        "message_v1" -> PermanentDeletionTarget.Message(
            providerMessageId = ProviderMessageId(
                permanentDeletionProviderKind(providerKind ?: error("Missing provider kind")),
                providerMessageId ?: error("Missing provider message ID"),
            ),
            providerThreadId = ProviderThreadId(providerThreadId),
            syncFingerprint = MessageSyncFingerprint.fromStorageToken(
                syncFingerprint ?: error("Missing sync fingerprint"),
            ),
        )
        "thread_v1" -> PermanentDeletionTarget.Thread(
            providerThreadId = ProviderThreadId(providerThreadId),
            smsCount = smsCount ?: error("Missing SMS count"),
            latestSmsId = latestSmsId?.let { ProviderMessageId(ProviderKind.SMS, it) },
            mmsCount = mmsCount ?: error("Missing MMS count"),
            latestMmsId = latestMmsId?.let { ProviderMessageId(ProviderKind.MMS, it) },
            draftId = draftId?.let(::DraftId),
            draftRevision = draftRevisionMillis?.let(::DraftRevision),
        )
        else -> error("Unknown permanent-deletion target")
    }
    return PermanentDeletionOperation(
        id = PermanentDeletionId(deletionId),
        target = target,
        dueTimestampMillis = dueTimestampMillis,
        phase = permanentDeletionPhaseFromCode(phaseCode)
            ?: error("Unknown permanent-deletion phase"),
        reviewReason = reviewReasonCode?.let {
            permanentDeletionReviewReasonFromCode(it)
                ?: error("Unknown permanent-deletion review reason")
        },
        armedWallTimestampMillis = armedWallTimestampMillis,
        armedElapsedRealtimeMillis = armedElapsedRealtimeMillis,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal val PermanentDeletionPhase.storageCode: String
    get() = when (this) {
        PermanentDeletionPhase.PENDING -> "pending_v1"
        PermanentDeletionPhase.COMMITTING -> "committing_v1"
        PermanentDeletionPhase.REVIEW_REQUIRED -> "review_required_v1"
    }

internal val PermanentDeletionReviewReason.storageCode: String
    get() = when (this) {
        PermanentDeletionReviewReason.CLOCK_CHANGED -> "clock_changed_v1"
        PermanentDeletionReviewReason.MISSED_AFTER_RESTART -> "missed_after_restart_v1"
        PermanentDeletionReviewReason.TARGET_CHANGED -> "target_changed_v1"
        PermanentDeletionReviewReason.PRECONDITION_FAILED -> "precondition_failed_v1"
        PermanentDeletionReviewReason.ARMING_FAILED -> "arming_failed_v1"
        PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT ->
            "interrupted_during_commit_v1"
    }

private fun permanentDeletionPhaseFromCode(value: String): PermanentDeletionPhase? = when (value) {
    "pending_v1" -> PermanentDeletionPhase.PENDING
    "committing_v1" -> PermanentDeletionPhase.COMMITTING
    "review_required_v1" -> PermanentDeletionPhase.REVIEW_REQUIRED
    else -> null
}

private fun permanentDeletionReviewReasonFromCode(
    value: String,
): PermanentDeletionReviewReason? = when (value) {
    "clock_changed_v1" -> PermanentDeletionReviewReason.CLOCK_CHANGED
    "missed_after_restart_v1" -> PermanentDeletionReviewReason.MISSED_AFTER_RESTART
    "target_changed_v1" -> PermanentDeletionReviewReason.TARGET_CHANGED
    "precondition_failed_v1" -> PermanentDeletionReviewReason.PRECONDITION_FAILED
    "arming_failed_v1" -> PermanentDeletionReviewReason.ARMING_FAILED
    "interrupted_during_commit_v1" -> PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT
    else -> null
}

private fun permanentDeletionProviderKind(value: Int): ProviderKind = when (value) {
    1 -> ProviderKind.SMS
    2 -> ProviderKind.MMS
    else -> error("Unknown permanent-deletion provider kind")
}
