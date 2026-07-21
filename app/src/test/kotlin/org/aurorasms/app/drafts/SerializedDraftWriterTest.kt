// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.drafts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.app.compose.ExternalPrefillResolution
import org.aurorasms.app.compose.resolveExternalPrefill
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedDraftWriterTest {
    @Test
    fun `accepted edit token captures the base advanced by an immediately prior write`() = runTest {
        val initial = draft(
            body = "initial durable body",
            subject = null,
            updated = 10L,
            identity = PARTICIPANT_IDENTITY,
        )
        val repository = FirstMutationGatedDraftRepository(initial)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 20L },
        )
        runCurrent()
        val intermediate = DraftEditorContent("intermediate durable body", null)
        assertTrue(writer.submit(intermediate))
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)
        val oldBase = initial.revision

        repository.allowFirstUpdate.complete(Unit)
        runCurrent()
        val advancedBase = checkNotNull(repository.stored).revision
        assertTrue(advancedBase != oldBase)

        val newest = DraftEditorContent("newly accepted body", null)
        val token = checkNotNull(writer.submitWithRestorationToken(newest))
        assertEquals(newest, token.content)
        assertEquals(initial.id, token.expectedDraftId)
        assertEquals(advancedBase, token.expectedRevision)
        assertTrue(writer.flush())
        writer.close()
    }

    @Test
    fun `review overlay stays locked until a concurrent durable draft is classified`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val repository = InMemoryDraftRepository(
            readGate = allowRead,
            readStarted = readStarted,
        )
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
        )
        val externalPrefill = "external review text"
        runCurrent()

        assertTrue(readStarted.isCompleted)
        assertEquals(DraftWriteStatus.Loading, writer.status.value)
        assertEquals(
            ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
            resolveExternalPrefill(
                current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
                prefill = externalPrefill,
                draftStatus = writer.status.value,
            ),
        )

        val durableWinner = draft(
            body = "concurrent durable winner",
            subject = null,
            updated = 7L,
            identity = PARTICIPANT_IDENTITY,
        )
        repository.stored = durableWinner
        allowRead.complete(Unit)
        runCurrent()

        assertEquals(
            ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT,
            resolveExternalPrefill(
                current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
                prefill = externalPrefill,
                draftStatus = writer.status.value,
            ),
        )
        assertEquals(durableWinner, repository.stored)
        assertTrue(repository.acceptedWrites.isEmpty())
        writer.close()
    }

    @Test
    fun `writes serialize and pending edits conflate to the newest accepted content`() = runTest {
        val repository = InMemoryDraftRepository(createGate = CompletableDeferred())
        val writer = SerializedDraftWriter(repository, IDENTITY, backgroundScope, nowMillis = { 100L })
        runCurrent()
        val first = DraftEditorContent("first", null)
        val skipped = DraftEditorContent("skipped", null)
        val newest = DraftEditorContent("newest", "subject")

        assertTrue(writer.submit(first))
        runCurrent()
        assertTrue(writer.submit(skipped))
        assertTrue(writer.submit(newest))
        assertFalse(writer.flush(timeoutMillis = 50L))

        repository.createGate?.complete(Unit)
        assertTrue(writer.flush())

        assertEquals(listOf(first, newest), repository.acceptedWrites)
        assertEquals(newest, repository.stored?.editorContent())
        val status = writer.status.value as DraftWriteStatus.Active
        assertEquals(newest, status.latest)
        assertFalse(status.saving)
        assertTrue(status.acknowledgedRevision != null)
        writer.close()
    }

    @Test
    fun `restoration based on the exact Room revision is replayed`() = runTest {
        val persisted = draft(body = "persisted", subject = null, updated = 10L)
        val repository = InMemoryDraftRepository(initial = persisted)
        val restored = DraftEditorContent("restored", "restored subject")
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = persisted.id,
                expectedRevision = persisted.revision,
            ),
            nowMillis = { 20L },
        )

        assertTrue(writer.flush())

        assertEquals(restored, repository.stored?.editorContent())
        assertEquals(restored, (writer.status.value as DraftWriteStatus.Active).latest)
        assertFalse((writer.status.value as DraftWriteStatus.Active).saving)
        writer.close()
    }

    @Test
    fun `stale post-send restoration cannot resurrect a draft deleted from Room`() = runTest {
        val stale = DraftRestorationToken(
            content = DraftEditorContent("already sent", null),
            expectedDraftId = DraftId(1L),
            expectedRevision = DraftRevision(10L),
        )
        val repository = InMemoryDraftRepository(initial = null)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = IDENTITY,
            scope = backgroundScope,
            restorationToken = stale,
            nowMillis = { 20L },
        )

        assertTrue(writer.flush())

        assertEquals(null, repository.stored)
        assertTrue(repository.acceptedWrites.isEmpty())
        assertEquals(
            DraftEditorContent.EMPTY,
            (writer.status.value as DraftWriteStatus.Active).latest,
        )
        writer.close()
    }

    @Test
    fun `base-free restoration survives only when Room also has no draft`() = runTest {
        val restored = DraftEditorContent("first unsaved edit", null)
        val repository = InMemoryDraftRepository(initial = null)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = null,
                expectedRevision = null,
            ),
            nowMillis = { 20L },
        )

        assertTrue(writer.flush())

        assertEquals(restored, repository.stored?.editorContent())
        assertEquals(listOf(restored), repository.acceptedWrites)
        writer.close()
    }

    @Test
    fun `base-free restoration cannot overwrite a draft created after the initial read`() = runTest {
        val createStarted = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()
        val repository = InMemoryDraftRepository(
            createGate = allowCreate,
            createStarted = createStarted,
        )
        val restored = DraftEditorContent("stale restored text", null)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = null,
                expectedRevision = null,
            ),
            nowMillis = { 20L },
        )
        runCurrent()
        assertTrue(createStarted.isCompleted)

        val concurrent = draft(
            body = "concurrent durable text",
            subject = "kept",
            updated = 30L,
            identity = PARTICIPANT_IDENTITY,
        )
        repository.stored = concurrent
        allowCreate.complete(Unit)

        assertTrue(writer.flush())

        assertEquals(concurrent, repository.stored)
        assertTrue(repository.acceptedWrites.isEmpty())
        assertEquals(0, repository.updateAttempts)
        val status = writer.status.value as DraftWriteStatus.Active
        assertEquals(concurrent.editorContent(), status.latest)
        assertEquals(concurrent.id, status.acknowledgedDraftId)
        assertEquals(concurrent.revision, status.acknowledgedRevision)
        assertFalse(status.saving)
        assertTrue(status.initialized)
        writer.close()
    }

    @Test
    fun `queued participant edit cannot cross a lost base-free restoration create`() = runTest {
        val createStarted = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()
        val repository = InMemoryDraftRepository(
            createGate = allowCreate,
            createStarted = createStarted,
        )
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = DraftEditorContent("restoration text", null),
                expectedDraftId = null,
                expectedRevision = null,
            ),
            nowMillis = { 20L },
        )
        runCurrent()
        assertTrue(createStarted.isCompleted)

        val queued = DraftEditorContent("newer local edit", null)
        assertTrue(writer.submit(queued))
        val concurrent = draft(
            body = "concurrent durable text",
            subject = null,
            updated = 30L,
            identity = PARTICIPANT_IDENTITY,
        )
        repository.stored = concurrent
        allowCreate.complete(Unit)

        assertFalse(writer.flush())

        assertEquals(concurrent, repository.stored)
        assertEquals(0, repository.updateAttempts)
        val failed = writer.status.value as DraftWriteStatus.Failed
        assertEquals(queued, failed.latest)
        assertEquals(DraftWriteFailure.CONFLICT, failed.failure)
        assertNull(failed.acknowledgedDraftId)
        assertNull(failed.acknowledgedRevision)

        val recreated = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = failed.latest,
                expectedDraftId = failed.acknowledgedDraftId,
                expectedRevision = failed.acknowledgedRevision,
            ),
            nowMillis = { 40L },
        )
        assertTrue(recreated.flush())
        assertEquals(concurrent, repository.stored)
        assertEquals(0, repository.updateAttempts)
        writer.close()
        recreated.close()
    }

    @Test
    fun `participant exact-base restoration fails when a concurrent update differs`() = runTest {
        val initial = draft(
            body = "shared base",
            subject = null,
            updated = 10L,
            identity = PARTICIPANT_IDENTITY,
        )
        val restored = DraftEditorContent("restored local text", null)
        val repository = FirstMutationGatedDraftRepository(initial = initial)
        val restoringWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = initial.id,
                expectedRevision = initial.revision,
            ),
            nowMillis = { 20L },
        )
        val competingWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 30L },
        )
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)

        val durableWinner = DraftEditorContent("concurrent durable text", null)
        assertTrue(competingWriter.submit(durableWinner))
        assertTrue(competingWriter.flush())
        repository.allowFirstUpdate.complete(Unit)

        assertFalse(restoringWriter.flush())

        assertEquals(durableWinner, repository.stored?.editorContent())
        assertEquals(2, repository.updateAttempts)
        val failed = restoringWriter.status.value as DraftWriteStatus.Failed
        assertEquals(restored, failed.latest)
        assertEquals(DraftWriteFailure.CONFLICT, failed.failure)
        restoringWriter.close()
        competingWriter.close()
    }

    @Test
    fun `participant exact-base restoration adopts an identical concurrent update`() = runTest {
        val initial = draft(
            body = "shared base",
            subject = null,
            updated = 10L,
            identity = PARTICIPANT_IDENTITY,
        )
        val restored = DraftEditorContent("same restored text", "same subject")
        val repository = FirstMutationGatedDraftRepository(initial = initial)
        val restoringWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = initial.id,
                expectedRevision = initial.revision,
            ),
            nowMillis = { 20L },
        )
        val competingWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 30L },
        )
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)

        assertTrue(competingWriter.submit(restored))
        assertTrue(competingWriter.flush())
        repository.allowFirstUpdate.complete(Unit)

        assertTrue(restoringWriter.flush())

        val durable = checkNotNull(repository.stored)
        assertEquals(restored, durable.editorContent())
        assertEquals(2, repository.updateAttempts)
        val active = restoringWriter.status.value as DraftWriteStatus.Active
        assertEquals(restored, active.latest)
        assertEquals(durable.id, active.acknowledgedDraftId)
        assertEquals(durable.revision, active.acknowledgedRevision)
        assertFalse(active.saving)
        restoringWriter.close()
        competingWriter.close()
    }

    @Test
    fun `participant exact-base restoration cannot resurrect a concurrently deleted draft`() = runTest {
        val initial = draft(
            body = "shared base",
            subject = null,
            updated = 10L,
            identity = PARTICIPANT_IDENTITY,
        )
        val restored = DraftEditorContent("must not be resurrected", null)
        val repository = FirstMutationGatedDraftRepository(initial = initial)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = restored,
                expectedDraftId = initial.id,
                expectedRevision = initial.revision,
            ),
            nowMillis = { 20L },
        )
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)

        repository.stored = null
        repository.allowFirstUpdate.complete(Unit)

        assertFalse(writer.flush())

        assertEquals(null, repository.stored)
        assertEquals(1, repository.updateAttempts)
        val failed = writer.status.value as DraftWriteStatus.Failed
        assertEquals(restored, failed.latest)
        assertEquals(DraftWriteFailure.CONFLICT, failed.failure)
        writer.close()
    }

    @Test
    fun `participant writers fail closed when a concurrent create wins`() = runTest {
        val repository = FirstMutationGatedDraftRepository()
        val firstWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 10L },
        )
        val secondWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 20L },
        )
        runCurrent()

        val preservedInFirstEditor = DraftEditorContent("first writer text", null)
        val durableWinner = DraftEditorContent("second writer text", null)
        assertTrue(firstWriter.submit(preservedInFirstEditor))
        runCurrent()
        assertTrue(repository.firstCreateStarted.isCompleted)
        assertTrue(secondWriter.submit(durableWinner))
        assertTrue(secondWriter.flush())

        repository.allowFirstCreate.complete(Unit)
        assertFalse(firstWriter.flush())

        assertEquals(durableWinner, repository.stored?.editorContent())
        assertEquals(2, repository.createAttempts)
        assertEquals(0, repository.updateAttempts)
        val failed = firstWriter.status.value as DraftWriteStatus.Failed
        assertEquals(preservedInFirstEditor, failed.latest)
        assertEquals(DraftWriteFailure.CONFLICT, failed.failure)
        firstWriter.close()
        secondWriter.close()
    }

    @Test
    fun `participant writers fail closed when a concurrent update wins`() = runTest {
        val initial = draft(
            body = "shared base",
            subject = null,
            updated = 10L,
            identity = PARTICIPANT_IDENTITY,
        )
        val repository = FirstMutationGatedDraftRepository(initial = initial)
        val firstWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 20L },
        )
        val secondWriter = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            nowMillis = { 30L },
        )
        runCurrent()

        val preservedInFirstEditor = DraftEditorContent("first writer text", null)
        val durableWinner = DraftEditorContent("second writer text", null)
        assertTrue(firstWriter.submit(preservedInFirstEditor))
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)
        assertTrue(secondWriter.submit(durableWinner))
        assertTrue(secondWriter.flush())

        repository.allowFirstUpdate.complete(Unit)
        assertFalse(firstWriter.flush())

        assertEquals(durableWinner, repository.stored?.editorContent())
        assertEquals(2, repository.updateAttempts)
        val failed = firstWriter.status.value as DraftWriteStatus.Failed
        assertEquals(preservedInFirstEditor, failed.latest)
        assertEquals(DraftWriteFailure.CONFLICT, failed.failure)
        firstWriter.close()
        secondWriter.close()
    }

    @Test
    fun `restoration with a different Room base is discarded`() = runTest {
        val current = draft(body = "new Room authority", subject = null, updated = 50L)
        val repository = InMemoryDraftRepository(initial = current)
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(
                content = DraftEditorContent("stale edit", null),
                expectedDraftId = current.id,
                expectedRevision = DraftRevision(49L),
            ),
        )

        assertTrue(writer.flush())

        assertEquals(current, repository.stored)
        assertTrue(repository.acceptedWrites.isEmpty())
        assertEquals(
            current.editorContent(),
            (writer.status.value as DraftWriteStatus.Active).latest,
        )
        writer.close()
    }

    @Test
    fun `freeze linearizes against edits and returns the exact acknowledged snapshot`() = runTest {
        val createGate = CompletableDeferred<Unit>()
        val repository = InMemoryDraftRepository(createGate = createGate)
        val writer = SerializedDraftWriter(repository, IDENTITY, backgroundScope, nowMillis = { 100L })
        runCurrent()
        val accepted = DraftEditorContent("accepted before freeze", null)

        assertTrue(writer.submit(accepted))
        runCurrent()
        val frozen = async { writer.freezeForSend() }
        runCurrent()

        assertFalse(writer.submit(DraftEditorContent("raced after freeze", null)))
        createGate.complete(Unit)
        val snapshot = frozen.await()

        assertEquals(accepted, snapshot?.content)
        assertEquals(repository.stored?.id, snapshot?.draftId)
        assertEquals(repository.stored?.revision, snapshot?.revision)
        assertEquals(listOf(accepted), repository.acceptedWrites)
        writer.close()
    }

    @Test
    fun `explicit refused-send unfreeze reopens edit acceptance`() = runTest {
        val repository = InMemoryDraftRepository()
        val writer = SerializedDraftWriter(repository, IDENTITY, backgroundScope, nowMillis = { 100L })
        runCurrent()
        assertTrue(writer.submit(DraftEditorContent("send candidate", null)))
        assertTrue(writer.flush())
        assertTrue(writer.freezeForSend() != null)
        assertFalse(writer.submit(DraftEditorContent("blocked while frozen", null)))

        assertTrue(writer.unfreezeAfterSendSettled(DraftUnfreezeReason.SEND_REFUSED))
        val replacement = DraftEditorContent("editable again", null)
        assertTrue(writer.submit(replacement))
        assertTrue(writer.flush())

        assertEquals(replacement, repository.stored?.editorContent())
        writer.close()
    }

    @Test
    fun `stale update refreshes exact identity once and retries against the new revision`() = runTest {
        val repository = InMemoryDraftRepository(
            initial = draft(body = "old", subject = null, updated = 10L),
            staleFirstUpdate = true,
        )
        val writer = SerializedDraftWriter(repository, IDENTITY, backgroundScope, nowMillis = { 30L })
        runCurrent()
        val replacement = DraftEditorContent("replacement", null)

        assertTrue(writer.submit(replacement))
        assertTrue(writer.flush())

        assertEquals(replacement, repository.stored?.editorContent())
        assertEquals(2, repository.updateAttempts)
        writer.close()
    }

    @Test
    fun `storage failure retains latest text and permanently disables further edits`() = runTest {
        val repository = InMemoryDraftRepository(failCreates = true)
        val writer = SerializedDraftWriter(repository, IDENTITY, backgroundScope, nowMillis = { 1L })
        runCurrent()
        val latest = DraftEditorContent("must remain visible", null)

        assertTrue(writer.submit(latest))
        runCurrent()

        val failed = writer.status.value as DraftWriteStatus.Failed
        assertEquals(latest, failed.latest)
        assertEquals(DraftWriteFailure.STORAGE, failed.failure)
        assertFalse(writer.submit(DraftEditorContent("rejected", null)))
        assertFalse(writer.flush())
        writer.close()
    }

    @Test
    fun `base-free restoration survives an initial read failure without inventing a base`() = runTest {
        val restored = DraftEditorContent("base-free text survives read failure", null)
        val writer = SerializedDraftWriter(
            repository = InMemoryDraftRepository(failReads = true),
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(restored, null, null),
        )
        runCurrent()

        val failed = writer.status.value as DraftWriteStatus.Failed
        assertEquals(restored, failed.latest)
        assertEquals(DraftWriteFailure.STORAGE, failed.failure)
        assertNull(failed.acknowledgedDraftId)
        assertNull(failed.acknowledgedRevision)
        writer.close()
    }

    @Test
    fun `exact-base restoration survives an initial read failure with its original base`() = runTest {
        val restored = DraftEditorContent("exact-base text survives read failure", "subject")
        val expectedId = DraftId(81L)
        val expectedRevision = DraftRevision(91L)
        val writer = SerializedDraftWriter(
            repository = InMemoryDraftRepository(failReads = true),
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(restored, expectedId, expectedRevision),
        )
        runCurrent()

        val failed = writer.status.value as DraftWriteStatus.Failed
        assertEquals(restored, failed.latest)
        assertEquals(DraftWriteFailure.STORAGE, failed.failure)
        assertEquals(expectedId, failed.acknowledgedDraftId)
        assertEquals(expectedRevision, failed.acknowledgedRevision)
        writer.close()
    }

    @Test
    fun `flush waits for initialization and the admitted restoration write`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val createStarted = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()
        val restored = DraftEditorContent("flush must wait for restored text", null)
        val repository = InMemoryDraftRepository(
            readGate = allowRead,
            readStarted = readStarted,
            createGate = allowCreate,
            createStarted = createStarted,
        )
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = PARTICIPANT_IDENTITY,
            scope = backgroundScope,
            restorationToken = DraftRestorationToken(restored, null, null),
        )
        val flushing = async { writer.flush() }
        runCurrent()
        assertTrue(readStarted.isCompleted)
        assertFalse(flushing.isCompleted)

        allowRead.complete(Unit)
        runCurrent()
        assertTrue(createStarted.isCompleted)
        assertFalse(flushing.isCompleted)

        allowCreate.complete(Unit)
        assertTrue(flushing.await())
        assertEquals(restored, repository.stored?.editorContent())
        writer.close()
    }

    @Test
    fun `participant route pool reuses a draining writer and preserves its newest edit`() = runTest {
        val repository = FirstMutationGatedDraftRepository()
        val pool = SerializedDraftWriterPool(backgroundScope) { identity, token ->
            SerializedDraftWriter(repository, identity, backgroundScope, token) { 100L }
        }
        val firstLease = pool.acquire(PARTICIPANT_IDENTITY, null, "same-route")
        runCurrent()
        val first = DraftEditorContent("intermediate edit", null)
        val newest = DraftEditorContent("newest edit", null)
        assertTrue(firstLease.writer.submit(first))
        runCurrent()
        assertTrue(repository.firstCreateStarted.isCompleted)
        assertTrue(firstLease.writer.submit(newest))

        firstLease.close()
        val replacementLease = pool.acquire(
            PARTICIPANT_IDENTITY,
            DraftRestorationToken(newest, null, null),
            "same-route",
        )
        assertSame(firstLease.writer, replacementLease.writer)

        repository.allowFirstCreate.complete(Unit)
        runCurrent()
        assertTrue(repository.firstUpdateStarted.isCompleted)
        assertEquals(newest, (replacementLease.writer.status.value as DraftWriteStatus.Active).latest)
        repository.allowFirstUpdate.complete(Unit)
        assertTrue(replacementLease.writer.flush())
        assertEquals(newest, repository.stored?.editorContent())
        replacementLease.close()
        runCurrent()
        pool.close()
    }

    @Test
    fun `participant pool separates simultaneous routes for the same identity`() = runTest {
        val repository = InMemoryDraftRepository()
        val pool = SerializedDraftWriterPool(backgroundScope) { identity, token ->
            SerializedDraftWriter(repository, identity, backgroundScope, token)
        }
        val internal = pool.acquire(PARTICIPANT_IDENTITY, null, "internal-route")
        val external = pool.acquire(
            PARTICIPANT_IDENTITY,
            DraftRestorationToken(DraftEditorContent("external review", null), null, null),
            "external-route",
        )

        assertNotSame(internal.writer, external.writer)
        runCurrent()
        assertTrue(external.writer.flush())
        assertEquals(
            DraftEditorContent("external review", null),
            repository.stored?.editorContent(),
        )
        internal.close()
        external.close()
        runCurrent()
        pool.close()
    }

    @Test
    fun `failed zero-owner writer is evicted before the same route reacquires`() = runTest {
        val repository = InMemoryDraftRepository(failCreates = true)
        val pool = SerializedDraftWriterPool(backgroundScope) { identity, token ->
            SerializedDraftWriter(repository, identity, backgroundScope, token)
        }
        val failedLease = pool.acquire(PARTICIPANT_IDENTITY, null, "retry-route")
        runCurrent()
        assertTrue(failedLease.writer.submit(DraftEditorContent("will fail", null)))
        runCurrent()
        assertTrue(failedLease.writer.status.value is DraftWriteStatus.Failed)
        failedLease.close()
        runCurrent()

        val retryLease = pool.acquire(PARTICIPANT_IDENTITY, null, "retry-route")
        assertNotSame(failedLease.writer, retryLease.writer)
        retryLease.close()
        runCurrent()
        pool.close()
    }

    @Test
    fun `rapid same-route retry replaces a terminal writer and restores its failed edit`() = runTest {
        val repository = InMemoryDraftRepository(failCreates = true)
        val pool = SerializedDraftWriterPool(backgroundScope) { identity, token ->
            SerializedDraftWriter(repository, identity, backgroundScope, token)
        }
        val failedContent = DraftEditorContent("retry after storage recovery", null)
        val failedLease = pool.acquire(PARTICIPANT_IDENTITY, null, "rapid-retry-route")
        runCurrent()
        assertTrue(failedLease.writer.submit(failedContent))
        runCurrent()
        assertTrue(failedLease.writer.status.value is DraftWriteStatus.Failed)

        repository.failCreates = false
        val retryLease = pool.acquire(
            PARTICIPANT_IDENTITY,
            DraftRestorationToken(failedContent, null, null),
            "rapid-retry-route",
        )
        assertNotSame(failedLease.writer, retryLease.writer)
        assertTrue(retryLease.writer.flush())
        assertEquals(failedContent, repository.stored?.editorContent())

        failedLease.close()
        retryLease.close()
        runCurrent()
        pool.close()
    }
}

private class InMemoryDraftRepository(
    initial: Draft? = null,
    private val readGate: CompletableDeferred<Unit>? = null,
    private val readStarted: CompletableDeferred<Unit>? = null,
    val createGate: CompletableDeferred<Unit>? = null,
    private val createStarted: CompletableDeferred<Unit>? = null,
    private val staleFirstUpdate: Boolean = false,
    var failCreates: Boolean = false,
    private val failReads: Boolean = false,
) : DraftRepository {
    var stored: Draft? = initial
    val acceptedWrites = mutableListOf<DraftEditorContent>()
    var updateAttempts = 0

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> {
        createStarted?.complete(Unit)
        createGate?.await()
        if (failCreates) return DraftRepositoryResult.StorageFailure(DraftStorageOperation.CREATE)
        if (stored != null) return DraftRepositoryResult.Conflict
        return Draft(
            id = DraftId(1L),
            identity = draft.identity,
            body = draft.body,
            subject = draft.subject,
            createdTimestampMillis = draft.createdTimestampMillis,
            updatedTimestampMillis = draft.updatedTimestampMillis,
        ).also { created ->
            stored = created
            acceptedWrites += created.editorContent()
        }.let { DraftRepositoryResult.Success(it) }
    }

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> =
        stored?.takeIf { it.id == id }?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> {
        readStarted?.complete(Unit)
        readGate?.await()
        if (failReads) return DraftRepositoryResult.StorageFailure(DraftStorageOperation.READ)
        return stored?.takeIf { it.identity == identity }?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound
    }

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> {
        updateAttempts += 1
        if (staleFirstUpdate && updateAttempts == 1) {
            stored = checkNotNull(stored).copy(updatedTimestampMillis = expectedRevision.updatedTimestampMillis + 1L)
            return DraftRepositoryResult.StaleWrite
        }
        val current = stored ?: return DraftRepositoryResult.NotFound
        if (current.revision != expectedRevision) return DraftRepositoryResult.StaleWrite
        stored = draft
        acceptedWrites += draft.editorContent()
        return DraftRepositoryResult.Success(draft)
    }

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> {
        if (stored?.id != id) return DraftRepositoryResult.NotFound
        stored = null
        return DraftRepositoryResult.Success(Unit)
    }
}

private class FirstMutationGatedDraftRepository(
    initial: Draft? = null,
) : DraftRepository {
    var stored: Draft? = initial
    val firstCreateStarted = CompletableDeferred<Unit>()
    val allowFirstCreate = CompletableDeferred<Unit>()
    val firstUpdateStarted = CompletableDeferred<Unit>()
    val allowFirstUpdate = CompletableDeferred<Unit>()
    var createAttempts = 0
    var updateAttempts = 0

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> {
        createAttempts += 1
        if (createAttempts == 1) {
            firstCreateStarted.complete(Unit)
            allowFirstCreate.await()
        }
        if (stored != null) return DraftRepositoryResult.Conflict
        return Draft(
            id = DraftId(1L),
            identity = draft.identity,
            body = draft.body,
            subject = draft.subject,
            createdTimestampMillis = draft.createdTimestampMillis,
            updatedTimestampMillis = draft.updatedTimestampMillis,
        ).also { stored = it }.let { DraftRepositoryResult.Success(it) }
    }

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> =
        stored?.takeIf { it.id == id }?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> =
        stored?.takeIf { it.identity == identity }?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> {
        updateAttempts += 1
        if (updateAttempts == 1) {
            firstUpdateStarted.complete(Unit)
            allowFirstUpdate.await()
        }
        val current = stored ?: return DraftRepositoryResult.NotFound
        if (current.revision != expectedRevision) return DraftRepositoryResult.StaleWrite
        stored = draft
        return DraftRepositoryResult.Success(draft)
    }

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> {
        if (stored?.id != id) return DraftRepositoryResult.NotFound
        stored = null
        return DraftRepositoryResult.Success(Unit)
    }
}

private val IDENTITY = DraftIdentity.ProviderThread(ProviderThreadId(9L))
private val PARTICIPANT_IDENTITY = DraftIdentity.ParticipantSet(
    DraftParticipantSetKey.fromParticipants(
        listOf(ParticipantAddress("+15551234567")),
    ),
)

private fun draft(
    body: String?,
    subject: String?,
    updated: Long,
    identity: DraftIdentity = IDENTITY,
): Draft = Draft(
    id = DraftId(1L),
    identity = identity,
    body = body,
    subject = subject,
    createdTimestampMillis = 0L,
    updatedTimestampMillis = updated,
)

private fun Draft.editorContent(): DraftEditorContent = DraftEditorContent(body, subject)
