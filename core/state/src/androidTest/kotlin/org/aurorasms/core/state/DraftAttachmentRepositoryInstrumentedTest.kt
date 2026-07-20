// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomDraftAttachmentRepository
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftAttachmentRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun cleanDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun replaceReadReopenAndDraftDeletePreserveExactOwnership() = runBlocking {
        var database = openStateDatabase()
        val draft = RoomDraftRepository(database).create(newDraft()).successValue()
        val attachments = syntheticAttachments()
        assertEquals(
            attachments,
            RoomDraftAttachmentRepository(database)
                .replace(draft.id, draft.revision, attachments)
                .successValue(),
        )
        database.close()

        database = openStateDatabase()
        try {
            val attachmentRepository = RoomDraftAttachmentRepository(database)
            assertEquals(attachments, attachmentRepository.read(draft.id).successValue())
            assertEquals(Unit, RoomDraftRepository(database).delete(draft.id).successValue())
            assertEquals(DraftRepositoryResult.NotFound, attachmentRepository.read(draft.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun staleRevisionCannotReplaceExistingAttachmentSet() = runBlocking {
        val database = openStateDatabase()
        try {
            val drafts = RoomDraftRepository(database)
            val attachments = RoomDraftAttachmentRepository(database)
            val original = drafts.create(newDraft()).successValue()
            val first = syntheticAttachments().take(1)
            attachments.replace(original.id, original.revision, first).successValue()
            val updated = drafts.update(
                original.copy(body = "Synthetic newer body", updatedTimestampMillis = 2_000L),
                original.revision,
            ).successValue()

            assertEquals(
                DraftRepositoryResult.StaleWrite,
                attachments.replace(original.id, original.revision, syntheticAttachments()),
            )
            assertEquals(first, attachments.read(updated.id).successValue())
        } finally {
            database.close()
        }
    }

    private fun openStateDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database

    private fun newDraft() = NewDraft(
        identity = DraftIdentity.ProviderThread(ProviderThreadId(4242L)),
        body = "Synthetic image draft",
        subject = null,
        createdTimestampMillis = 1_000L,
        updatedTimestampMillis = 1_000L,
    )

    private fun syntheticAttachments(): List<DraftAttachment> = listOf(
        validAttachment(DraftAttachment.IMAGE_JPEG, byteArrayOf(1, 2, 3)),
        validAttachment(DraftAttachment.IMAGE_PNG, byteArrayOf(4, 5)),
    )

    private fun validAttachment(contentType: String, bytes: ByteArray): DraftAttachment =
        (DraftAttachment.create(contentType, bytes) as DraftAttachment.CreationResult.Valid).attachment
}

private fun <T> DraftRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is DraftRepositoryResult.Success<T>)
    return (this as DraftRepositoryResult.Success<T>).value
}
