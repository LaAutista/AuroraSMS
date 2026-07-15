// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.storage.IndexedConversationEntity
import org.aurorasms.core.index.storage.IndexedConversationParticipantEntity
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedConversationIdentityTest {
    @Test
    fun `verified projection is exact bounded canonical and redacted`() {
        val addresses = (9 downTo 1).map { index -> "+1555000000$index" }
        val identity = projectVerifiedConversationIdentity(
            requestedThreadId = THREAD_ID,
            generationId = GENERATION_ID,
            entity = conversation(participantCount = addresses.size),
            coverage = verifiedCoverage(),
            rows = addresses.map(::participant),
        )

        requireNotNull(identity)
        assertEquals(THREAD_ID, identity.providerThreadId)
        assertEquals(GENERATION_ID, identity.generationId)
        assertEquals(addresses.sorted(), identity.participants.map(ParticipantAddress::value))
        assertEquals(addresses.size, identity.participants.size)
        assertTrue(identity.toString().contains("participantCount=9"))
        addresses.forEach { address -> assertFalse(identity.toString().contains(address)) }
        assertFalse(identity.toString().contains(THREAD_ID.value.toString()))
        assertFalse(identity.toString().contains(GENERATION_ID.toString()))
    }

    @Test
    fun `projection fails closed unless generation and participant proof agree`() {
        val rows = listOf(participant("+15550000001"), participant("+15550000002"))
        val entity = conversation(participantCount = rows.size)

        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity,
                verifiedCoverage(pendingChanges = true),
                rows,
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity,
                verifiedCoverage().copy(generationId = GENERATION_ID + 1L),
                rows,
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity.copy(lastSeenGeneration = GENERATION_ID + 1L),
                verifiedCoverage(),
                rows,
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity.copy(indexedParticipantCount = rows.size + 1),
                verifiedCoverage(),
                rows,
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity.copy(participantsTruncated = true),
                verifiedCoverage(),
                rows,
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity,
                verifiedCoverage(),
                rows.map { row -> row.copy(lastSeenGeneration = GENERATION_ID + 1L) },
            ),
        )
        assertNull(
            projectVerifiedConversationIdentity(
                THREAD_ID,
                GENERATION_ID,
                entity,
                verifiedCoverage(),
                listOf(rows.first(), rows.first()),
            ),
        )
    }

    @Test
    fun `oversized or noncanonical public identities are rejected`() {
        val oversizedRows = (1..MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS + 1).map { index ->
            participant("participant-${index.toString().padStart(3, '0')}@example.invalid")
        }
        assertNull(
            projectVerifiedConversationIdentity(
                requestedThreadId = THREAD_ID,
                generationId = GENERATION_ID,
                entity = conversation(participantCount = oversizedRows.size),
                coverage = verifiedCoverage(),
                rows = oversizedRows,
            ),
        )

        assertTrue(
            runCatching {
                VerifiedConversationIdentity(THREAD_ID, GENERATION_ID, emptyList())
            }.isFailure,
        )
        assertTrue(
            runCatching {
                VerifiedConversationIdentity(
                    THREAD_ID,
                    GENERATION_ID,
                    listOf(ParticipantAddress("b"), ParticipantAddress("a")),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                VerifiedConversationIdentity(
                    THREAD_ID,
                    GENERATION_ID,
                    listOf(ParticipantAddress("a"), ParticipantAddress("a")),
                )
            }.isFailure,
        )
    }

    private fun conversation(
        participantCount: Int,
        participantsTruncated: Boolean = false,
    ): IndexedConversationEntity = IndexedConversationEntity(
        providerThreadId = THREAD_ID.value,
        latestRowId = 1L,
        latestProviderKind = 1,
        latestProviderId = 1L,
        latestTimestampMillis = 1L,
        latestSentTimestampMillis = null,
        latestDirection = 1,
        latestMessageBox = "inbox",
        latestMessageStatus = "complete",
        latestSubscriptionId = null,
        latestSenderAddress = null,
        latestSnippet = null,
        latestAttachmentCount = 0,
        latestAttachmentTypeSummary = "",
        latestIsRead = true,
        indexedMessageCount = 1L,
        indexedUnreadCount = 0L,
        indexedParticipantCount = participantCount,
        participantsTruncated = participantsTruncated,
        lastSeenGeneration = GENERATION_ID,
    )

    private fun participant(address: String): IndexedConversationParticipantEntity =
        IndexedConversationParticipantEntity(
            providerThreadId = THREAD_ID.value,
            address = address,
            lastSeenGeneration = GENERATION_ID,
        )

    private fun verifiedCoverage(pendingChanges: Boolean = false): IndexCoverage = IndexCoverage(
        generationId = GENERATION_ID,
        state = IndexRunState.COMPLETE,
        indexedMessageCount = 1L,
        smsExhausted = true,
        mmsExhausted = true,
        pendingChanges = pendingChanges,
        generationCommittedCount = 1L,
        smsCheckpointCommittedCount = 1L,
        mmsCheckpointCommittedCount = 0L,
    )

    private companion object {
        val THREAD_ID = ProviderThreadId(41L)
        const val GENERATION_ID: Long = 7L
    }
}
