// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.drafts

import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.NewDraft

data class DraftEditorContent(
    val body: String?,
    val subject: String?,
) {
    init {
        require(body == null || body.length <= Draft.MAX_BODY_CHARACTERS) { "Draft editor body is too large" }
        require(subject == null || subject.length <= Draft.MAX_SUBJECT_CHARACTERS) {
            "Draft editor subject is too large"
        }
    }

    override fun toString(): String = "DraftEditorContent(REDACTED)"

    companion object {
        val EMPTY = DraftEditorContent(body = null, subject = null)
    }
}

enum class DraftWriteFailure {
    STORAGE,
    CONFLICT,
    CORRUPT_DATA,
    INVALID_REVISION,
}

sealed interface DraftWriteStatus {
    data object Loading : DraftWriteStatus

    data class Active(
        val latest: DraftEditorContent,
        val acknowledgedRevision: DraftRevision?,
        val saving: Boolean,
    ) : DraftWriteStatus {
        override fun toString(): String =
            "DraftWriteStatus.Active(hasRevision=${acknowledgedRevision != null}, saving=$saving, REDACTED)"
    }

    data class Failed(
        val latest: DraftEditorContent,
        val failure: DraftWriteFailure,
    ) : DraftWriteStatus {
        override fun toString(): String = "DraftWriteStatus.Failed(failure=$failure, REDACTED)"
    }
}

class SerializedDraftWriter(
    private val repository: DraftRepository,
    private val identity: DraftIdentity,
    scope: CoroutineScope,
    restoredUnacknowledged: DraftEditorContent? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    private val writes = Channel<QueuedEdit>(capacity = Channel.CONFLATED)
    private val _status = MutableStateFlow<DraftWriteStatus>(DraftWriteStatus.Loading)
    val status: StateFlow<DraftWriteStatus> = _status.asStateFlow()
    private var latestAccepted: QueuedEdit? = restoredUnacknowledged?.let { QueuedEdit(1L, it) }
    private var nextSequence = if (restoredUnacknowledged == null) 1L else 2L
    private var acknowledgedSequence = 0L
    private var terminal = false
    private val worker: Job

    init {
        restoredUnacknowledged?.let { writes.trySend(checkNotNull(latestAccepted)) }
        worker = scope.launch { initializeAndWrite() }
    }

    fun submit(content: DraftEditorContent): Boolean = synchronized(lock) {
        if (terminal) return false
        val edit = QueuedEdit(nextSequence++, content)
        latestAccepted = edit
        val revision = (_status.value as? DraftWriteStatus.Active)?.acknowledgedRevision
        _status.value = DraftWriteStatus.Active(content, revision, saving = true)
        if (!writes.trySend(edit).isSuccess) {
            terminal = true
            _status.value = DraftWriteStatus.Failed(content, DraftWriteFailure.STORAGE)
            false
        } else {
            true
        }
    }

    suspend fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0L) { "Draft flush timeout must be positive" }
        val target = synchronized(lock) { latestAccepted?.sequence ?: 0L }
        return withTimeoutOrNull(timeoutMillis) {
            status.first {
                synchronized(lock) {
                    terminal || (it !is DraftWriteStatus.Loading && acknowledgedSequence >= target)
                }
            }
            synchronized(lock) { !terminal && acknowledgedSequence >= target }
        } ?: false
    }

    override fun close() {
        synchronized(lock) {
            terminal = true
            writes.close()
        }
        worker.cancel()
    }

    private suspend fun initializeAndWrite() {
        var currentDraft = when (val result = safeRead()) {
            is DraftRepositoryResult.Success -> result.value
            DraftRepositoryResult.NotFound -> null
            else -> {
                fail(latestContent(), result.toFailure())
                return
            }
        }
        synchronized(lock) {
            val persistedContent = currentDraft?.toEditorContent() ?: DraftEditorContent.EMPTY
            val latest = latestAccepted?.content ?: persistedContent
            val restoredNeedsWrite = latestAccepted != null && latest != persistedContent
            if (!restoredNeedsWrite) {
                acknowledgedSequence = latestAccepted?.sequence ?: 0L
            }
            _status.value = DraftWriteStatus.Active(
                latest = latest,
                acknowledgedRevision = currentDraft?.revision,
                saving = restoredNeedsWrite,
            )
        }

        for (edit in writes) {
            if (synchronized(lock) { edit.sequence <= acknowledgedSequence }) continue
            val write = writeWithOneRefresh(currentDraft, edit.content)
            when (write) {
                is WriteOutcome.Success -> {
                    currentDraft = write.draft
                    synchronized(lock) {
                        acknowledgedSequence = max(acknowledgedSequence, edit.sequence)
                        val latest = latestAccepted ?: edit
                        _status.value = DraftWriteStatus.Active(
                            latest = latest.content,
                            acknowledgedRevision = write.draft.revision,
                            saving = latest.sequence > acknowledgedSequence,
                        )
                    }
                }
                is WriteOutcome.Failed -> {
                    fail(latestContent(), write.failure)
                    return
                }
            }
        }
    }

    private suspend fun writeWithOneRefresh(
        startingDraft: Draft?,
        content: DraftEditorContent,
    ): WriteOutcome {
        var current = startingDraft
        repeat(MAXIMUM_WRITE_ATTEMPTS) {
            val result = if (current == null) {
                val timestamp = nowMillis().coerceAtLeast(0L)
                safeCreate(
                    NewDraft(
                        identity = identity,
                        body = content.body,
                        subject = content.subject,
                        createdTimestampMillis = timestamp,
                        updatedTimestampMillis = timestamp,
                    ),
                )
            } else {
                val nextTimestamp = nextRevisionTimestamp(current.updatedTimestampMillis)
                    ?: return WriteOutcome.Failed(DraftWriteFailure.INVALID_REVISION)
                safeUpdate(
                    draft = current.copy(
                        body = content.body,
                        subject = content.subject,
                        updatedTimestampMillis = nextTimestamp,
                    ),
                    expectedRevision = current.revision,
                )
            }
            when (result) {
                is DraftRepositoryResult.Success -> return WriteOutcome.Success(result.value)
                DraftRepositoryResult.Conflict,
                DraftRepositoryResult.StaleWrite,
                DraftRepositoryResult.NotFound,
                -> {
                    current = when (val refreshed = safeRead()) {
                        is DraftRepositoryResult.Success -> refreshed.value
                        DraftRepositoryResult.NotFound -> null
                        else -> return WriteOutcome.Failed(refreshed.toFailure())
                    }
                }
                else -> return WriteOutcome.Failed(result.toFailure())
            }
        }
        return WriteOutcome.Failed(DraftWriteFailure.CONFLICT)
    }

    private fun nextRevisionTimestamp(previous: Long): Long? {
        if (previous == Long.MAX_VALUE) return null
        return max(nowMillis().coerceAtLeast(0L), previous + 1L)
    }

    private suspend fun safeRead(): DraftRepositoryResult<Draft> = try {
        repository.read(identity)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        DraftRepositoryResult.StorageFailure(org.aurorasms.core.state.DraftStorageOperation.READ)
    }

    private suspend fun safeCreate(draft: NewDraft): DraftRepositoryResult<Draft> = try {
        repository.create(draft)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        DraftRepositoryResult.StorageFailure(org.aurorasms.core.state.DraftStorageOperation.CREATE)
    }

    private suspend fun safeUpdate(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> = try {
        repository.update(draft, expectedRevision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        DraftRepositoryResult.StorageFailure(org.aurorasms.core.state.DraftStorageOperation.UPDATE)
    }

    private fun fail(content: DraftEditorContent, failure: DraftWriteFailure) {
        synchronized(lock) {
            terminal = true
            _status.value = DraftWriteStatus.Failed(content, failure)
            writes.close()
        }
    }

    private fun latestContent(): DraftEditorContent =
        synchronized(lock) { latestAccepted?.content ?: DraftEditorContent.EMPTY }

    private data class QueuedEdit(
        val sequence: Long,
        val content: DraftEditorContent,
    )

    private sealed interface WriteOutcome {
        data class Success(val draft: Draft) : WriteOutcome
        data class Failed(val failure: DraftWriteFailure) : WriteOutcome
    }

    companion object {
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS: Long = 1_500L
        private const val MAXIMUM_WRITE_ATTEMPTS: Int = 2
    }
}

private fun Draft.toEditorContent(): DraftEditorContent = DraftEditorContent(body = body, subject = subject)

private fun DraftRepositoryResult<*>.toFailure(): DraftWriteFailure = when (this) {
    DraftRepositoryResult.Conflict,
    DraftRepositoryResult.StaleWrite,
    DraftRepositoryResult.NotFound,
    -> DraftWriteFailure.CONFLICT
    DraftRepositoryResult.CorruptData -> DraftWriteFailure.CORRUPT_DATA
    DraftRepositoryResult.InvalidRevision -> DraftWriteFailure.INVALID_REVISION
    is DraftRepositoryResult.StorageFailure -> DraftWriteFailure.STORAGE
    is DraftRepositoryResult.Success<*> -> error("A successful result is not a failure")
}
