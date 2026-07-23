// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirstContactOperationContractTest {
    @Test
    fun semanticParticipantFingerprintIsOrderIndependentPurposeSeparatedAndRedacted() {
        val first = ParticipantAddress("+1 (555) 000-0100")
        val second = ParticipantAddress("case@EXAMPLE.invalid")
        val key = FirstContactParticipantSetKey.fromParticipants(listOf(first, second))

        assertEquals(
            key,
            FirstContactParticipantSetKey.fromParticipants(listOf(second, first)),
        )
        assertNotEquals(
            key.toStorageValue(),
            ScheduledSmsParticipantSetKey.fromParticipants(listOf(first, second)).toStorageValue(),
        )
        assertEquals("FirstContactParticipantSetKey(REDACTED)", key.toString())
        assertFalse(key.toString().contains("555"))
    }

    @Test
    fun semanticDuplicateParticipantsFailClosedInsteadOfCollapsing() {
        assertThrows(IllegalArgumentException::class.java) {
            FirstContactParticipantSetKey.fromParticipants(
                listOf(
                    ParticipantAddress("+1 (555) 000-0100"),
                    ParticipantAddress("+15550000100"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstContactReservationRequest(
                participants = listOf(
                    ParticipantAddress("person@EXAMPLE.invalid"),
                    ParticipantAddress("person@example.invalid"),
                ),
                draftId = DraftId(1L),
                expectedDraftRevision = DraftRevision(2L),
                subscriptionId = AuroraSubscriptionId(0),
                transport = MessageTransportKind.MMS,
                createdTimestampMillis = 3L,
            )
        }
    }

    @Test
    fun attachmentEvidenceIncludesDeterministicEmptyOrderTypeAndBytes() {
        val jpegA = attachment(DraftAttachment.IMAGE_JPEG, 1, 2, 3)
        val jpegB = attachment(DraftAttachment.IMAGE_JPEG, 4, 5)
        val pngA = attachment(DraftAttachment.IMAGE_PNG, 1, 2, 3)

        assertEquals(
            FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
            FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
        )
        assertNotEquals(
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(jpegA, jpegB)),
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(jpegB, jpegA)),
        )
        assertNotEquals(
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(jpegA)),
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(pngA)),
        )
        assertNotEquals(
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(jpegA)),
            FirstContactAttachmentSetEvidence.fromAttachments(
                listOf(attachment(DraftAttachment.IMAGE_JPEG, 1, 2, 4)),
            ),
        )
        assertEquals(
            "FirstContactAttachmentSetEvidence(REDACTED)",
            FirstContactAttachmentSetEvidence.fromAttachments(listOf(jpegA)).toString(),
        )
    }

    @Test
    fun phasesAdmitOnlyTheirExactThreadAndHandoffBinding() {
        FirstContactOperationPhase.entries.forEach { phase ->
            val thread = when (phase) {
                FirstContactOperationPhase.THREAD_BOUND,
                FirstContactOperationPhase.HANDOFF_RESERVED,
                -> ProviderThreadId(9L)
                else -> null
            }
            val handoff = DraftRevision(8L)
                .takeIf { phase == FirstContactOperationPhase.HANDOFF_RESERVED }
            validOperation(phase, thread, handoff)
        }

        assertThrows(IllegalArgumentException::class.java) {
            validOperation(
                FirstContactOperationPhase.RESOLUTION_STARTED,
                ProviderThreadId(9L),
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(FirstContactOperationPhase.THREAD_BOUND, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(
                FirstContactOperationPhase.HANDOFF_RESERVED,
                ProviderThreadId(9L),
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(
                FirstContactOperationPhase.KNOWN_UNSENT,
                ProviderThreadId(9L),
                null,
            )
        }
    }

    @Test
    fun transientSnapshotsRequireTheOperationsExactSemanticParticipants() {
        val operation = validOperation(
            FirstContactOperationPhase.RESOLUTION_STARTED,
            null,
            null,
        )
        FirstContactResolutionSnapshot(operation, PARTICIPANTS)
        assertThrows(IllegalArgumentException::class.java) {
            FirstContactResolutionSnapshot(
                operation,
                listOf(ParticipantAddress("+15550000999")),
            )
        }
    }

    @Test
    fun bridgeSnapshotRequiresTheOperationsExactAttachmentEvidence() {
        val operation = validOperation(
            FirstContactOperationPhase.HANDOFF_RESERVED,
            ProviderThreadId(9L),
            DraftRevision(8L),
        )
        val providerDraft = Draft(
            id = operation.draftId,
            identity = DraftIdentity.ProviderThread(ProviderThreadId(9L)),
            body = "Synthetic",
            subject = null,
            createdTimestampMillis = 1L,
            updatedTimestampMillis = 8L,
        )
        FirstContactBridgeSnapshot(operation, providerDraft, PARTICIPANTS, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            FirstContactBridgeSnapshot(
                operation,
                providerDraft,
                PARTICIPANTS,
                listOf(attachment(DraftAttachment.IMAGE_JPEG, 1)),
            )
        }
    }

    private fun validOperation(
        phase: FirstContactOperationPhase,
        providerThreadId: ProviderThreadId?,
        handoffDraftRevision: DraftRevision?,
    ) = FirstContactOperation(
        id = FirstContactOperationId(1L),
        participantSetKey = FirstContactParticipantSetKey.fromParticipants(PARTICIPANTS),
        draftId = DraftId(2L),
        sourceDraftRevision = DraftRevision(3L),
        attachmentSetEvidence =
            FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
        subscriptionId = AuroraSubscriptionId(0),
        transport = MessageTransportKind.SMS,
        phase = phase,
        providerThreadId = providerThreadId,
        handoffDraftRevision = handoffDraftRevision,
        createdTimestampMillis = 4L,
        updatedTimestampMillis = 5L,
    )

    private fun attachment(contentType: String, vararg bytes: Int): DraftAttachment =
        (DraftAttachment.create(contentType, bytes.map(Int::toByte).toByteArray()) as
            DraftAttachment.CreationResult.Valid).attachment

    private companion object {
        val PARTICIPANTS = listOf(ParticipantAddress("+15550000100"))
    }
}
