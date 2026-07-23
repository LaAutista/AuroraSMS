// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpamSafetyTest {
    @Test
    fun participantIdentityIsOrderIndependentPurposeSeparatedAndRedacted() {
        val first = ParticipantAddress("+12025550101")
        val second = ParticipantAddress("+12025550102")

        val forward = SpamParticipantSetKey.fromParticipants(listOf(first, second))
        val reverse = SpamParticipantSetKey.fromParticipants(listOf(second, first))
        val sender = BlockedSenderKey.fromSender(first)

        assertEquals(forward, reverse)
        assertNotEquals(forward.toStorageValue(), sender.toStorageValue())
        assertEquals("SpamParticipantSetKey(REDACTED)", forward.toString())
        assertEquals("BlockedSenderKey(REDACTED)", sender.toString())
    }

    @Test
    fun onlyExplicitOnePersonBlocksAndMeaningfulRowsAreValid() {
        val groupScope = SpamSafetyScope(
            participantSetKey = SpamParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("+12025550101"), ParticipantAddress("+12025550102")),
            ),
            providerThreadId = ProviderThreadId(7L),
            singleSenderKey = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SpamSafetyDecision(
                scope = groupScope,
                classification = SpamClassification.SPAM,
                blocked = true,
                revision = SpamSafetyRevision(1L),
                updatedTimestampMillis = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpamSafetyDecision(
                scope = groupScope,
                classification = SpamClassification.NEUTRAL,
                blocked = false,
                revision = SpamSafetyRevision(1L),
                updatedTimestampMillis = 1L,
            )
        }
    }
}
