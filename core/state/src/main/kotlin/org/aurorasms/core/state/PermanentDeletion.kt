// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

@JvmInline
value class PermanentDeletionId(val value: Long) {
    init { require(value > 0L) }
    override fun toString(): String = "PermanentDeletionId(REDACTED)"
}

@JvmInline
value class PermanentDeletionRevision(val updatedTimestampMillis: Long) {
    init { require(updatedTimestampMillis >= 0L) }
    override fun toString(): String = "PermanentDeletionRevision(REDACTED)"
}

sealed interface PermanentDeletionTarget {
    val providerThreadId: ProviderThreadId

    data class Message(
        val providerMessageId: ProviderMessageId,
        override val providerThreadId: ProviderThreadId,
        val syncFingerprint: MessageSyncFingerprint,
    ) : PermanentDeletionTarget {
        init {
            require(
                providerMessageId.kind == ProviderKind.SMS ||
                    providerMessageId.kind == ProviderKind.MMS,
            )
        }
        override fun toString(): String = "PermanentDeletionTarget.Message(REDACTED)"
    }

    data class Thread(
        override val providerThreadId: ProviderThreadId,
        val smsCount: Long,
        val latestSmsId: ProviderMessageId?,
        val mmsCount: Long,
        val latestMmsId: ProviderMessageId?,
        val draftId: DraftId? = null,
        val draftRevision: DraftRevision? = null,
    ) : PermanentDeletionTarget {
        init {
            require(smsCount >= 0L && mmsCount >= 0L)
            require(smsCount <= Long.MAX_VALUE - mmsCount)
            require(smsCount + mmsCount > 0L)
            require((smsCount == 0L) == (latestSmsId == null))
            require((mmsCount == 0L) == (latestMmsId == null))
            require(latestSmsId == null || latestSmsId.kind == ProviderKind.SMS)
            require(latestMmsId == null || latestMmsId.kind == ProviderKind.MMS)
            require((draftId == null) == (draftRevision == null))
        }
        override fun toString(): String =
            "PermanentDeletionTarget.Thread(messageCount=${smsCount + mmsCount}, REDACTED)"
    }
}

enum class PermanentDeletionPhase { PENDING, COMMITTING, REVIEW_REQUIRED }

enum class PermanentDeletionReviewReason {
    CLOCK_CHANGED,
    MISSED_AFTER_RESTART,
    TARGET_CHANGED,
    PRECONDITION_FAILED,
    ARMING_FAILED,
    INTERRUPTED_DURING_COMMIT,
}

data class PermanentDeletionOperation(
    val id: PermanentDeletionId,
    val target: PermanentDeletionTarget,
    val dueTimestampMillis: Long,
    val phase: PermanentDeletionPhase,
    val reviewReason: PermanentDeletionReviewReason?,
    val armedWallTimestampMillis: Long,
    val armedElapsedRealtimeMillis: Long,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        require(dueTimestampMillis > createdTimestampMillis)
        require(armedWallTimestampMillis == createdTimestampMillis)
        require(armedElapsedRealtimeMillis >= 0L)
        require(updatedTimestampMillis in createdTimestampMillis until Long.MAX_VALUE)
        require((phase == PermanentDeletionPhase.REVIEW_REQUIRED) == (reviewReason != null))
    }

    val revision: PermanentDeletionRevision
        get() = PermanentDeletionRevision(updatedTimestampMillis)

    override fun toString(): String =
        "PermanentDeletionOperation(phase=$phase, target=${target::class.simpleName}, REDACTED)"
}

data class PermanentDeletionRequest(
    val target: PermanentDeletionTarget,
    val dueTimestampMillis: Long,
    val createdTimestampMillis: Long,
    val armedElapsedRealtimeMillis: Long,
) {
    init {
        require(createdTimestampMillis >= 0L && armedElapsedRealtimeMillis >= 0L)
        require(createdTimestampMillis <= Long.MAX_VALUE - PERMANENT_DELETION_UNDO_WINDOW_MILLIS)
        require(dueTimestampMillis == createdTimestampMillis + PERMANENT_DELETION_UNDO_WINDOW_MILLIS)
    }
}

enum class PermanentDeletionStorageOperation { CREATE, READ, TRANSITION, REMOVE, RECOVER }

sealed interface PermanentDeletionResult<out T> {
    data class Success<T>(val value: T) : PermanentDeletionResult<T> {
        override fun toString(): String = "PermanentDeletionResult.Success(REDACTED)"
    }
    data object NotFound : PermanentDeletionResult<Nothing>
    data object StaleWrite : PermanentDeletionResult<Nothing>
    data object PhaseMismatch : PermanentDeletionResult<Nothing>
    data object LimitExceeded : PermanentDeletionResult<Nothing>
    data object ConflictingAction : PermanentDeletionResult<Nothing>
    data object CorruptData : PermanentDeletionResult<Nothing>
    data class StorageFailure(
        val operation: PermanentDeletionStorageOperation,
    ) : PermanentDeletionResult<Nothing>
}

interface PermanentDeletionRepository {
    suspend fun create(
        request: PermanentDeletionRequest,
    ): PermanentDeletionResult<PermanentDeletionOperation>
    suspend fun read(id: PermanentDeletionId): PermanentDeletionResult<PermanentDeletionOperation>
    suspend fun readByThread(
        providerThreadId: ProviderThreadId,
    ): PermanentDeletionResult<PermanentDeletionOperation>
    fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<PermanentDeletionResult<PermanentDeletionOperation?>>
    suspend fun recoverySnapshot(): PermanentDeletionResult<List<PermanentDeletionOperation>>
    suspend fun validateLocalPreconditions(
        operation: PermanentDeletionOperation,
    ): PermanentDeletionResult<Boolean>
    suspend fun markCommitting(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        updatedTimestampMillis: Long,
    ): PermanentDeletionResult<PermanentDeletionOperation>
    suspend fun markReviewRequired(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        reason: PermanentDeletionReviewReason,
        updatedTimestampMillis: Long,
    ): PermanentDeletionResult<PermanentDeletionOperation>
    suspend fun removeUndoable(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ): PermanentDeletionResult<Unit>
    suspend fun removeCommitted(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ): PermanentDeletionResult<Unit>
}

const val PERMANENT_DELETION_UNDO_WINDOW_MILLIS: Long = 5_000L
const val MAXIMUM_PERMANENT_DELETION_OPERATIONS: Int = 128
