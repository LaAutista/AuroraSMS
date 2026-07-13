// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.drafts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedDraftWriterTest {
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
    fun `restored unacknowledged content is persisted before it is acknowledged`() = runTest {
        val repository = InMemoryDraftRepository(
            initial = draft(body = "persisted", subject = null, updated = 10L),
        )
        val restored = DraftEditorContent("restored", "restored subject")
        val writer = SerializedDraftWriter(
            repository = repository,
            identity = IDENTITY,
            scope = backgroundScope,
            restoredUnacknowledged = restored,
            nowMillis = { 20L },
        )

        assertTrue(writer.flush())

        assertEquals(restored, repository.stored?.editorContent())
        assertEquals(restored, (writer.status.value as DraftWriteStatus.Active).latest)
        assertFalse((writer.status.value as DraftWriteStatus.Active).saving)
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
}

private class InMemoryDraftRepository(
    initial: Draft? = null,
    val createGate: CompletableDeferred<Unit>? = null,
    private val staleFirstUpdate: Boolean = false,
    private val failCreates: Boolean = false,
) : DraftRepository {
    var stored: Draft? = initial
    val acceptedWrites = mutableListOf<DraftEditorContent>()
    var updateAttempts = 0

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> {
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

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> =
        stored?.takeIf { it.identity == identity }?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound

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

private val IDENTITY = DraftIdentity.ProviderThread(ProviderThreadId(9L))

private fun draft(
    body: String?,
    subject: String?,
    updated: Long,
): Draft = Draft(
    id = DraftId(1L),
    identity = IDENTITY,
    body = body,
    subject = subject,
    createdTimestampMillis = 0L,
    updatedTimestampMillis = updated,
)

private fun Draft.editorContent(): DraftEditorContent = DraftEditorContent(body, subject)
