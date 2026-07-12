// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftContractTest {
    @Test
    fun participantKey_isOrderIndependentAndDeduplicated() {
        val first = DraftParticipantSetKey.fromParticipants(
            listOf(
                ParticipantAddress("+15550000002"),
                ParticipantAddress("+15550000001"),
                ParticipantAddress("+15550000002"),
            ),
        )
        val second = DraftParticipantSetKey.fromParticipants(
            listOf(
                ParticipantAddress("+15550000001"),
                ParticipantAddress("+15550000002"),
            ),
        )

        assertEquals(first, second)
        assertEquals("DraftParticipantSetKey(REDACTED)", first.toString())
    }

    @Test
    fun identitiesRejectMissingOrInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderThreadId(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftParticipantSetKey.fromParticipants(emptyList())
        }
    }

    @Test
    fun contentAndTimestampsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            newDraft(body = "x".repeat(Draft.MAX_BODY_CHARACTERS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            newDraft(subject = "x".repeat(Draft.MAX_SUBJECT_CHARACTERS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            newDraft(createdTimestampMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            newDraft(createdTimestampMillis = 2L, updatedTimestampMillis = 1L)
        }
    }

    @Test
    fun domainToStringsRedactIdentityAndContent() {
        val newDraft = newDraft(
            body = "synthetic-secret-body",
            subject = "synthetic-secret-subject",
        )
        val draft = Draft(
            id = DraftId(7L),
            identity = newDraft.identity,
            body = newDraft.body,
            subject = newDraft.subject,
            createdTimestampMillis = newDraft.createdTimestampMillis,
            updatedTimestampMillis = newDraft.updatedTimestampMillis,
        )

        assertEquals("NewDraft(REDACTED)", newDraft.toString())
        assertEquals("Draft(REDACTED)", draft.toString())
        assertEquals("DraftId(REDACTED)", draft.id.toString())
        assertEquals("DraftRevision(REDACTED)", draft.revision.toString())
        assertEquals("DraftIdentity.ProviderThread(REDACTED)", draft.identity.toString())
        assertEquals(
            "DraftRepositoryResult.Success(REDACTED)",
            DraftRepositoryResult.Success(draft).toString(),
        )
    }

    private fun newDraft(
        body: String? = null,
        subject: String? = null,
        createdTimestampMillis: Long = 0L,
        updatedTimestampMillis: Long = createdTimestampMillis,
    ): NewDraft = NewDraft(
        identity = DraftIdentity.ProviderThread(ProviderThreadId(1L)),
        body = body,
        subject = subject,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}
