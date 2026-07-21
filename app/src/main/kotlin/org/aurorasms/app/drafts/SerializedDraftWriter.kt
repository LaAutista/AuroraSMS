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
import org.aurorasms.core.state.DraftId
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

/**
 * App-private saved-state handoff for an edit that may be newer than Room.
 *
 * [expectedDraftId] and [expectedRevision] name the exact durable draft on
 * which [content] was based. A token without a base is valid only for the
 * genuinely-unsaved case where Room still has no draft for [identity].
 */
data class DraftRestorationToken(
    val content: DraftEditorContent,
    val expectedDraftId: DraftId?,
    val expectedRevision: DraftRevision?,
) {
    init {
        require((expectedDraftId == null) == (expectedRevision == null)) {
            "A draft restoration base must be wholly present or absent"
        }
    }

    internal fun matches(current: Draft?): Boolean = if (expectedDraftId == null) {
        current == null
    } else {
        current?.id == expectedDraftId && current.revision == expectedRevision
    }

    override fun toString(): String =
        "DraftRestorationToken(hasBase=${expectedDraftId != null}, REDACTED)"
}

/** The exact durable draft revision captured by the edit-acceptance freeze. */
data class FrozenDraftSnapshot(
    val content: DraftEditorContent,
    val draftId: DraftId,
    val revision: DraftRevision,
) {
    override fun toString(): String = "FrozenDraftSnapshot(REDACTED)"
}

enum class DraftUnfreezeReason {
    SEND_REFUSED,
    SCHEDULE_REFUSED,
    SCHEDULE_CANCELLED,
    SEND_DELAY_UNDONE,
    KNOWN_UNSENT,
    SUBMISSION_UNKNOWN_ACKNOWLEDGED,
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
        val acknowledgedDraftId: DraftId?,
        val acknowledgedRevision: DraftRevision?,
        val saving: Boolean,
        val frozenForSend: Boolean,
        val initialized: Boolean,
    ) : DraftWriteStatus {
        override fun toString(): String =
            "DraftWriteStatus.Active(hasDraft=${acknowledgedDraftId != null}, " +
                "hasRevision=${acknowledgedRevision != null}, saving=$saving, " +
                "frozenForSend=$frozenForSend, initialized=$initialized, REDACTED)"
    }

    data class Failed(
        val latest: DraftEditorContent,
        val failure: DraftWriteFailure,
        val acknowledgedDraftId: DraftId?,
        val acknowledgedRevision: DraftRevision?,
    ) : DraftWriteStatus {
        override fun toString(): String = "DraftWriteStatus.Failed(failure=$failure, REDACTED)"
    }
}

class SerializedDraftWriter(
    private val repository: DraftRepository,
    private val identity: DraftIdentity,
    scope: CoroutineScope,
    private val restorationToken: DraftRestorationToken? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    private val writes = Channel<QueuedEdit>(capacity = Channel.CONFLATED)
    private val _status = MutableStateFlow<DraftWriteStatus>(DraftWriteStatus.Loading)
    val status: StateFlow<DraftWriteStatus> = _status.asStateFlow()
    private var latestAccepted: QueuedEdit? = null
    private var nextSequence = 1L
    private var acknowledgedSequence = 0L
    private var initialized = false
    private var frozenForSend = false
    private var terminal = false
    private val worker: Job

    init {
        worker = scope.launch { initializeAndWrite() }
    }

    fun submit(content: DraftEditorContent): Boolean =
        submitWithRestorationToken(content) != null

    /** Admits one edit and captures its exact durable base under the same lock. */
    internal fun submitWithRestorationToken(
        content: DraftEditorContent,
    ): DraftRestorationToken? = synchronized(lock) {
        if (terminal || frozenForSend) return null
        val edit = QueuedEdit(nextSequence++, content)
        latestAccepted = edit
        val active = _status.value as? DraftWriteStatus.Active
        val restorationToken = DraftRestorationToken(
            content = content,
            expectedDraftId = active?.acknowledgedDraftId,
            expectedRevision = active?.acknowledgedRevision,
        )
        _status.value = DraftWriteStatus.Active(
            latest = content,
            acknowledgedDraftId = active?.acknowledgedDraftId,
            acknowledgedRevision = active?.acknowledgedRevision,
            saving = true,
            frozenForSend = false,
            initialized = active?.initialized == true,
        )
        if (!writes.trySend(edit).isSuccess) {
            terminal = true
            _status.value = DraftWriteStatus.Failed(
                latest = content,
                failure = DraftWriteFailure.STORAGE,
                acknowledgedDraftId = active?.acknowledgedDraftId,
                acknowledgedRevision = active?.acknowledgedRevision,
            )
            null
        } else {
            restorationToken
        }
    }

    /**
     * Atomically closes edit acceptance, waits for every edit accepted before
     * that barrier, and returns that exact acknowledged revision.
     *
     * A failed/timeout result deliberately remains frozen. The caller must
     * explicitly classify the attempt as refused before reopening editing.
     */
    suspend fun freezeForSend(
        timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    ): FrozenDraftSnapshot? {
        require(timeoutMillis > 0L) { "Draft freeze timeout must be positive" }
        synchronized(lock) {
            if (terminal) return null
            frozenForSend = true
            val active = _status.value as? DraftWriteStatus.Active
            if (active != null && !active.frozenForSend) {
                _status.value = active.copy(frozenForSend = true)
            }
        }
        val target = withTimeoutOrNull(timeoutMillis) {
            status.first {
                synchronized(lock) {
                    terminal || (initialized && it is DraftWriteStatus.Active && it.initialized)
                }
            }
            synchronized(lock) { latestAccepted?.sequence ?: 0L }
        } ?: return null
        val acknowledged = withTimeoutOrNull(timeoutMillis) {
            status.first {
                synchronized(lock) {
                    terminal ||
                        (initialized && it is DraftWriteStatus.Active && acknowledgedSequence >= target)
                }
            }
            synchronized(lock) {
                val active = _status.value as? DraftWriteStatus.Active
                if (
                    terminal ||
                    !frozenForSend ||
                    active == null ||
                    active.saving ||
                    acknowledgedSequence < target
                ) {
                    null
                } else {
                    val draftId = active.acknowledgedDraftId ?: return@synchronized null
                    val revision = active.acknowledgedRevision ?: return@synchronized null
                    FrozenDraftSnapshot(active.latest, draftId, revision)
                }
            }
        }
        return acknowledged
    }

    /** Reopens editing only after the caller has durably classified the send. */
    fun unfreezeAfterSendSettled(reason: DraftUnfreezeReason): Boolean = synchronized(lock) {
        // The required reason makes unsafe generic "unfreeze" calls impossible
        // at call sites. Every value preserves the exact draft revision.
        when (reason) {
            DraftUnfreezeReason.SEND_REFUSED,
            DraftUnfreezeReason.SCHEDULE_REFUSED,
            DraftUnfreezeReason.SCHEDULE_CANCELLED,
            DraftUnfreezeReason.SEND_DELAY_UNDONE,
            DraftUnfreezeReason.KNOWN_UNSENT,
            DraftUnfreezeReason.SUBMISSION_UNKNOWN_ACKNOWLEDGED -> Unit
        }
        if (terminal || !frozenForSend) return false
        frozenForSend = false
        val active = _status.value as? DraftWriteStatus.Active
        if (active != null) {
            _status.value = active.copy(frozenForSend = false)
        }
        true
    }

    suspend fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0L) { "Draft flush timeout must be positive" }
        return withTimeoutOrNull(timeoutMillis) {
            status.first {
                synchronized(lock) {
                    terminal || (initialized && it is DraftWriteStatus.Active && it.initialized)
                }
            }
            val target = synchronized(lock) {
                if (terminal) null else latestAccepted?.sequence ?: 0L
            } ?: return@withTimeoutOrNull false
            status.first {
                synchronized(lock) {
                    terminal ||
                        (initialized && it is DraftWriteStatus.Active && acknowledgedSequence >= target)
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
                failInitialization(result.toFailure())
                return
            }
        }
        synchronized(lock) {
            if (terminal) return
            val persistedContent = currentDraft?.toEditorContent() ?: DraftEditorContent.EMPTY
            if (latestAccepted == null && restorationToken?.matches(currentDraft) == true) {
                val restored = QueuedEdit(
                    sequence = nextSequence++,
                    content = restorationToken.content,
                    isRestoration = true,
                    discardIfCreateConflicts = restorationToken.expectedDraftId == null,
                )
                latestAccepted = restored
                if (restored.content == persistedContent) {
                    acknowledgedSequence = restored.sequence
                } else if (!writes.trySend(restored).isSuccess) {
                    terminal = true
                    _status.value = DraftWriteStatus.Failed(
                        latest = restored.content,
                        failure = DraftWriteFailure.STORAGE,
                        acknowledgedDraftId = currentDraft?.id,
                        acknowledgedRevision = currentDraft?.revision,
                    )
                    writes.close()
                    return
                }
            }
            val latest = latestAccepted?.content ?: persistedContent
            val restoredNeedsWrite = latestAccepted?.sequence?.let { it > acknowledgedSequence } == true
            initialized = true
            _status.value = DraftWriteStatus.Active(
                latest = latest,
                acknowledgedDraftId = currentDraft?.id,
                acknowledgedRevision = currentDraft?.revision,
                saving = restoredNeedsWrite,
                frozenForSend = frozenForSend,
                initialized = true,
            )
        }

        for (edit in writes) {
            if (synchronized(lock) { edit.sequence <= acknowledgedSequence }) continue
            val write = writeWithOneRefresh(currentDraft, edit)
            when (write) {
                is WriteOutcome.Success -> {
                    currentDraft = write.draft
                    synchronized(lock) {
                        acknowledgedSequence = max(acknowledgedSequence, edit.sequence)
                        val latest = latestAccepted ?: edit
                        _status.value = DraftWriteStatus.Active(
                            latest = latest.content,
                            acknowledgedDraftId = write.draft.id,
                            acknowledgedRevision = write.draft.revision,
                            saving = latest.sequence > acknowledgedSequence,
                            frozenForSend = frozenForSend,
                            initialized = true,
                        )
                    }
                }
                is WriteOutcome.ConcurrentDraftPreserved -> {
                    currentDraft = write.draft
                    var stopWriting = false
                    synchronized(lock) {
                        acknowledgedSequence = max(acknowledgedSequence, edit.sequence)
                        val latest = latestAccepted ?: edit
                        if (
                            latest.sequence > edit.sequence &&
                            latest.content == write.draft.toEditorContent()
                        ) {
                            acknowledgedSequence = max(acknowledgedSequence, latest.sequence)
                        } else if (
                            identity is DraftIdentity.ParticipantSet &&
                            edit.discardIfCreateConflicts &&
                            latest.sequence > edit.sequence
                        ) {
                            failLocked(
                                content = latest.content,
                                failure = DraftWriteFailure.CONFLICT,
                            )
                            stopWriting = true
                        } else if (latestAccepted?.sequence == edit.sequence) {
                            latestAccepted = edit.copy(
                                content = write.draft.toEditorContent(),
                                discardIfCreateConflicts = false,
                            )
                        }
                        if (!stopWriting) {
                            val reconciledLatest = latestAccepted ?: edit
                            _status.value = DraftWriteStatus.Active(
                                latest = reconciledLatest.content,
                                acknowledgedDraftId = write.draft.id,
                                acknowledgedRevision = write.draft.revision,
                                saving = reconciledLatest.sequence > acknowledgedSequence,
                                frozenForSend = frozenForSend,
                                initialized = true,
                            )
                        }
                    }
                    if (stopWriting) return
                }
                is WriteOutcome.Failed -> {
                    failLatest(write.failure)
                    return
                }
            }
        }
    }

    private suspend fun writeWithOneRefresh(
        startingDraft: Draft?,
        edit: QueuedEdit,
    ): WriteOutcome {
        var current = startingDraft
        repeat(MAXIMUM_WRITE_ATTEMPTS) {
            val wasCreateAttempt = current == null
            val result = if (current == null) {
                val timestamp = nowMillis().coerceAtLeast(0L)
                safeCreate(
                    NewDraft(
                        identity = identity,
                        body = edit.content.body,
                        subject = edit.content.subject,
                        createdTimestampMillis = timestamp,
                        updatedTimestampMillis = timestamp,
                    ),
                )
            } else {
                val nextTimestamp = nextRevisionTimestamp(current.updatedTimestampMillis)
                    ?: return WriteOutcome.Failed(DraftWriteFailure.INVALID_REVISION)
                safeUpdate(
                    draft = current.copy(
                        body = edit.content.body,
                        subject = edit.content.subject,
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
                    val participantExactRestorationLostBase =
                        identity is DraftIdentity.ParticipantSet &&
                            edit.isRestoration &&
                            !edit.discardIfCreateConflicts
                    // Participant-set routes can briefly have independent writers. Retry order
                    // is not user-edit order, so retain the local text and leave Room untouched.
                    if (
                        identity is DraftIdentity.ParticipantSet &&
                        !edit.isRestoration &&
                        (
                            result == DraftRepositoryResult.Conflict ||
                                result == DraftRepositoryResult.StaleWrite
                            )
                    ) {
                        return WriteOutcome.Failed(DraftWriteFailure.CONFLICT)
                    }
                    current = when (val refreshed = safeRead()) {
                        is DraftRepositoryResult.Success -> {
                            // A base-free SavedState hint loses authority once another writer
                            // creates the durable draft between our read and create attempt.
                            if (
                                wasCreateAttempt &&
                                result == DraftRepositoryResult.Conflict &&
                                edit.discardIfCreateConflicts
                            ) {
                                return WriteOutcome.ConcurrentDraftPreserved(refreshed.value)
                            }
                            if (participantExactRestorationLostBase) {
                                return if (refreshed.value.toEditorContent() == edit.content) {
                                    WriteOutcome.ConcurrentDraftPreserved(refreshed.value)
                                } else {
                                    WriteOutcome.Failed(DraftWriteFailure.CONFLICT)
                                }
                            }
                            refreshed.value
                        }
                        DraftRepositoryResult.NotFound -> {
                            if (participantExactRestorationLostBase) {
                                return WriteOutcome.Failed(DraftWriteFailure.CONFLICT)
                            }
                            null
                        }
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

    private fun failLatest(failure: DraftWriteFailure) = synchronized(lock) {
        failLocked(
            content = latestAccepted?.content ?: DraftEditorContent.EMPTY,
            failure = failure,
        )
    }

    private fun failInitialization(failure: DraftWriteFailure) = synchronized(lock) {
        val latest = latestAccepted?.content ?: restorationToken?.content ?: DraftEditorContent.EMPTY
        failLocked(
            content = latest,
            failure = failure,
            fallbackDraftId = restorationToken?.expectedDraftId,
            fallbackRevision = restorationToken?.expectedRevision,
        )
    }

    private fun failLocked(
        content: DraftEditorContent,
        failure: DraftWriteFailure,
        fallbackDraftId: DraftId? = null,
        fallbackRevision: DraftRevision? = null,
    ) {
        terminal = true
        val active = _status.value as? DraftWriteStatus.Active
        _status.value = DraftWriteStatus.Failed(
            latest = content,
            failure = failure,
            acknowledgedDraftId = active?.acknowledgedDraftId ?: fallbackDraftId,
            acknowledgedRevision = active?.acknowledgedRevision ?: fallbackRevision,
        )
        writes.close()
    }

    private data class QueuedEdit(
        val sequence: Long,
        val content: DraftEditorContent,
        val isRestoration: Boolean = false,
        val discardIfCreateConflicts: Boolean = false,
    )

    private sealed interface WriteOutcome {
        data class Success(val draft: Draft) : WriteOutcome
        data class ConcurrentDraftPreserved(val draft: Draft) : WriteOutcome
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
