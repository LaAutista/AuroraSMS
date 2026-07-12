// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.NewDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoomDraftRepositoryCancellationTest {
    private val repository = RoomDraftRepository(CancellingDraftDao)

    @Test
    fun cancellationIsRethrownByEveryRepositoryOperation() {
        val newDraft = NewDraft(
            identity = DraftIdentity.ProviderThread(ProviderThreadId(3L)),
            body = "Synthetic cancellation",
            subject = null,
            createdTimestampMillis = 10L,
            updatedTimestampMillis = 10L,
        )
        val draft = Draft(
            id = DraftId(4L),
            identity = newDraft.identity,
            body = newDraft.body,
            subject = newDraft.subject,
            createdTimestampMillis = newDraft.createdTimestampMillis,
            updatedTimestampMillis = 20L,
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.create(newDraft) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { repository.read(draft.id) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { repository.update(draft, DraftRevision(10L)) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { repository.delete(draft.id) }
        }
    }

    @Test
    fun entityToStringRedactsAllStoredContent() {
        val entity = DraftEntity(
            draftId = 9L,
            providerThreadId = 10L,
            participantSetKey = null,
            body = "synthetic-secret-body",
            subject = "synthetic-secret-subject",
            createdTimestampMillis = 1L,
            updatedTimestampMillis = 2L,
        )

        assertEquals("DraftEntity(REDACTED)", entity.toString())
    }
}

private object CancellingDraftDao : DraftDao {
    override suspend fun insert(entity: DraftEntity): Long = throwCancellation()

    override suspend fun findById(draftId: Long): DraftEntity? = throwCancellation()

    override suspend fun updateIfUnchanged(
        draftId: Long,
        providerThreadId: Long?,
        participantSetKey: String?,
        body: String?,
        subject: String?,
        createdTimestampMillis: Long,
        updatedTimestampMillis: Long,
        expectedUpdatedTimestampMillis: Long,
    ): Int = throwCancellation()

    override suspend fun deleteById(draftId: Long): Int = throwCancellation()

    private fun <T> throwCancellation(): T =
        throw CancellationException("synthetic cancellation")
}
