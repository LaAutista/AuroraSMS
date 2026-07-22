// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstContactOperationEntityTest {
    @Test
    fun everyStablePhaseCodeRoundTripsWithOnlyCoherentBindings() {
        FirstContactOperationPhase.entries.forEach { phase ->
            val thread = 44L.takeIf {
                phase == FirstContactOperationPhase.THREAD_BOUND ||
                    phase == FirstContactOperationPhase.HANDOFF_RESERVED
            }
            val handoff = 55L.takeIf {
                phase == FirstContactOperationPhase.HANDOFF_RESERVED
            }
            val entity = validEntity(
                phaseCode = phase.storageCode,
                providerThreadId = thread,
                handoffDraftRevisionMillis = handoff,
            )

            assertEquals(phase, entity.toDomain().phase)
            assertTrue(entity.toString().endsWith("REDACTED)"))
        }
    }

    @Test
    fun unknownPhaseMalformedEvidenceAndBindingFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            validEntity(phaseCode = "future_v2").toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(attachmentSetEvidence = "sha256-v1:not-a-digest").toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                phaseCode = FirstContactOperationPhase.THREAD_BOUND.storageCode,
                providerThreadId = null,
            ).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                phaseCode = FirstContactOperationPhase.HANDOFF_RESERVED.storageCode,
                providerThreadId = 44L,
                handoffDraftRevisionMillis = 3L,
            ).toDomain()
        }
    }

    private fun validEntity(
        phaseCode: String = FirstContactOperationPhase.RESERVED.storageCode,
        providerThreadId: Long? = null,
        handoffDraftRevisionMillis: Long? = null,
        attachmentSetEvidence: String =
            FirstContactAttachmentSetEvidence.fromAttachments(emptyList()).toStorageValue(),
    ) = FirstContactOperationEntity(
        firstContactId = 1L,
        participantSetKey = FirstContactParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("+15550000100")),
        ).toStorageValue(),
        draftId = 2L,
        sourceDraftRevisionMillis = 3L,
        attachmentSetEvidence = attachmentSetEvidence,
        subscriptionId = 0,
        transportCode = "sms_v1",
        phaseCode = phaseCode,
        providerThreadId = providerThreadId,
        handoffDraftRevisionMillis = handoffDraftRevisionMillis,
        createdTimestampMillis = 4L,
        updatedTimestampMillis = 5L,
    )
}
