// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ConversationReadThroughOutcome
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSmsProviderStatusTransitionTest {
    @Test
    fun markReadThroughExactSourcePreservesNewerAndOtherConversationRows() = runTest {
        val source = SyntheticMessages.smsProviderMessage()
        val older = source.copy(id = ProviderMessageId(ProviderKind.SMS, source.id.value - 1L))
        val newer = source.copy(id = ProviderMessageId(ProviderKind.SMS, source.id.value + 1L))
        val other = source.copy(
            id = ProviderMessageId(ProviderKind.SMS, source.id.value + 2L),
            providerThreadId = org.aurorasms.core.model.ProviderThreadId(
                source.providerThreadId.value + 1L,
            ),
        )
        val fake = FakeSmsProviderDataSource(listOf(older, source, newer, other))

        assertEquals(
            ProviderAccessResult.Success(ConversationReadThroughOutcome.APPLIED_OR_ALREADY_READ),
            fake.markConversationReadThrough(SyntheticMessages.conversationId, source.id),
        )

        val byId = fake.snapshot().associateBy { it.id }
        assertTrue(requireNotNull(byId[older.id]).read)
        assertTrue(requireNotNull(byId[source.id]).read)
        assertEquals(false, requireNotNull(byId[newer.id]).read)
        assertEquals(false, requireNotNull(byId[other.id]).read)
    }

    @Test
    fun markReadThroughMismatchedSourceFailsClosedWithoutMutation() = runTest {
        val source = SyntheticMessages.smsProviderMessage()
        val fake = FakeSmsProviderDataSource(listOf(source))

        assertEquals(
            ProviderAccessResult.Success(
                ConversationReadThroughOutcome.SOURCE_ABSENT_OR_MISMATCH,
            ),
            fake.markConversationReadThrough(
                ConversationId(source.providerThreadId.value + 1L),
                source.id,
            ),
        )
        assertEquals(listOf(source), fake.snapshot())
        assertTrue(fake.markedReadThrough.isEmpty())
    }

    @Test
    fun transitionMatrixNeverRegressesAndSkipsNoOpRewrites() = runTest {
        SmsProviderStatus.entries.forEach { currentStatus ->
            SmsProviderStatus.entries.forEach { requestedStatus ->
                val fake = FakeSmsProviderDataSource()
                val stored = fake.insertTestOutgoing()
                if (currentStatus != SmsProviderStatus.FAILED) {
                    assertTrue(
                        fake.armOutgoing(stored.providerId) is ProviderAccessResult.Success,
                    )
                }
                if (
                    currentStatus != SmsProviderStatus.PENDING &&
                    currentStatus != SmsProviderStatus.FAILED
                ) {
                    assertTrue(
                        fake.updateStatus(stored.providerId, currentStatus) is ProviderAccessResult.Success,
                    )
                }
                fake.updatedStatuses.clear()
                val before = fake.snapshot().single()

                val result = fake.updateStatus(stored.providerId, requestedStatus)

                assertTrue("$currentStatus -> $requestedStatus", result is ProviderAccessResult.Success)
                val shouldAdvance = requestedStatus.testRank > currentStatus.testRank
                val expectedStatus = if (shouldAdvance) requestedStatus else currentStatus
                assertEquals(expectedStatus.testBox, fake.snapshot().single().box)
                assertEquals(expectedStatus.testMessageStatus, fake.snapshot().single().status)
                if (shouldAdvance) {
                    assertEquals(requestedStatus, fake.updatedStatuses[stored.providerId])
                } else {
                    assertTrue(fake.updatedStatuses.isEmpty())
                    assertEquals(before, fake.snapshot().single())
                }
            }
        }
    }

    @Test
    fun outgoingRowsRemainFailedUntilOneExactArm() = runTest {
        val fake = FakeSmsProviderDataSource()
        val stored = fake.insertTestOutgoing()

        with(fake.snapshot().single()) {
            assertEquals(MessageBox.FAILED, box)
            assertEquals(MessageStatus.FAILED, status)
            assertEquals(64, rawStatus)
            assertEquals(Int.MIN_VALUE, rawErrorCode)
        }

        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Success)
        with(fake.snapshot().single()) {
            assertEquals(MessageBox.OUTBOX, box)
            assertEquals(MessageStatus.PENDING, status)
            assertEquals(32, rawStatus)
            assertEquals(0, rawErrorCode)
        }
        assertEquals(listOf(stored.providerId), fake.armedOutgoing)

        val beforeDuplicate = fake.snapshot()
        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Unavailable)
        assertEquals(beforeDuplicate, fake.snapshot())
        assertEquals(listOf(stored.providerId), fake.armedOutgoing)

        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED),
            fake.rollbackOutgoing(stored.providerId, stored.conversationId),
        )
        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED),
            fake.rollbackOutgoing(stored.providerId, stored.conversationId),
        )
        val terminal = fake.snapshot()
        with(terminal.single()) {
            assertEquals(MessageBox.FAILED, box)
            assertEquals(MessageStatus.FAILED, status)
            assertEquals(0, rawErrorCode)
        }
        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Unavailable)
        assertEquals(terminal, fake.snapshot())
        assertEquals(listOf(stored.providerId), fake.armedOutgoing)
    }

    @Test
    fun outgoingArmRejectsWrongKindAndMissingIdentityWithoutMutation() = runTest {
        val fake = FakeSmsProviderDataSource()
        val stored = fake.insertTestOutgoing()
        val staged = fake.snapshot()

        assertTrue(
            fake.armOutgoing(ProviderMessageId(ProviderKind.MMS, stored.providerId.value)) is
                ProviderAccessResult.InvalidInput,
        )
        assertTrue(
            fake.armOutgoing(ProviderMessageId(ProviderKind.SMS, Long.MAX_VALUE)) is
                ProviderAccessResult.Unavailable,
        )
        assertEquals(staged, fake.snapshot())
        assertTrue(fake.armedOutgoing.isEmpty())
    }

    @Test
    fun outgoingRollbackRejectsWrongKindMissingAndPostSubmissionState() = runTest {
        val fake = FakeSmsProviderDataSource()
        val stored = fake.insertTestOutgoing()

        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Success)
        assertTrue(
            fake.updateStatus(stored.providerId, SmsProviderStatus.COMPLETE) is
                ProviderAccessResult.Success,
        )
        val complete = fake.snapshot()

        assertTrue(
            fake.rollbackOutgoing(
                ProviderMessageId(ProviderKind.MMS, stored.providerId.value),
                stored.conversationId,
            ) is
                ProviderAccessResult.InvalidInput,
        )
        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.ROW_ABSENT),
            fake.rollbackOutgoing(
                ProviderMessageId(ProviderKind.SMS, Long.MAX_VALUE),
                stored.conversationId,
            ),
        )
        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT),
            fake.rollbackOutgoing(stored.providerId, stored.conversationId),
        )
        assertEquals(complete, fake.snapshot())
    }

    @Test
    fun unknownSeedStateFailsClosedWithoutMutation() = runTest {
        val original = SyntheticMessages.smsProviderMessage()
        val fake = FakeSmsProviderDataSource(listOf(original))

        val result = fake.updateStatus(original.id, SmsProviderStatus.FAILED)

        assertTrue(result is ProviderAccessResult.Unavailable)
        assertEquals(listOf(original), fake.snapshot())
        assertTrue(fake.updatedStatuses.isEmpty())
    }

    @Test
    fun seededForeignRowReportsOwnershipConflictWithoutMutation() = runTest {
        val original = SyntheticMessages.smsProviderMessage()
        val fake = FakeSmsProviderDataSource(listOf(original))

        val result = fake.rollbackOutgoing(
            original.id,
            ConversationId(original.providerThreadId.value),
        )

        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT),
            result,
        )
        assertEquals(listOf(original), fake.snapshot())
    }

    @Test
    fun exactOutgoingStatusRequiresAppOwnerConversationAndArmedState() = runTest {
        val fake = FakeSmsProviderDataSource()
        val stored = fake.insertTestOutgoing()
        val staged = fake.snapshot()

        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT),
            fake.updateOutgoingStatus(
                stored.providerId,
                stored.conversationId,
                SmsProviderStatus.COMPLETE,
            ),
        )
        assertEquals(staged, fake.snapshot())

        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Success)
        val armed = fake.snapshot()
        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT),
            fake.updateOutgoingStatus(
                stored.providerId,
                ConversationId(stored.conversationId.value + 1L),
                SmsProviderStatus.COMPLETE,
            ),
        )
        assertEquals(armed, fake.snapshot())

        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED),
            fake.updateOutgoingStatus(
                stored.providerId,
                stored.conversationId,
                SmsProviderStatus.COMPLETE,
            ),
        )
        assertEquals(MessageBox.SENT, fake.snapshot().single().box)
        assertEquals(SmsProviderStatus.COMPLETE, fake.updatedStatuses[stored.providerId])
    }

    @Test
    fun exactOutgoingStatusDistinguishesMissingAndForeignRowsWithoutMutation() = runTest {
        val original = SyntheticMessages.smsProviderMessage()
        val fake = FakeSmsProviderDataSource(listOf(original))

        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.ROW_ABSENT),
            fake.updateOutgoingStatus(
                ProviderMessageId(ProviderKind.SMS, Long.MAX_VALUE),
                ConversationId(original.providerThreadId.value),
                SmsProviderStatus.FAILED,
            ),
        )
        assertEquals(
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT),
            fake.updateOutgoingStatus(
                original.id,
                ConversationId(original.providerThreadId.value),
                SmsProviderStatus.FAILED,
            ),
        )
        assertEquals(listOf(original), fake.snapshot())
        assertTrue(fake.updatedStatuses.isEmpty())
    }
}

private suspend fun FakeSmsProviderDataSource.insertTestOutgoing(): ProviderStoredMessage {
    val result = insertOutgoing(
        OutgoingSmsRecord(
            recipient = SyntheticPeople.MILO.address,
            body = SyntheticMessages.SECOND_BODY,
            timestampMillis = SyntheticMessages.FIXED_TIMESTAMP_MILLIS,
            subscriptionId = SyntheticMessages.subscriptionId,
        ),
    )
    return (result as ProviderAccessResult.Success).value
}

private val SmsProviderStatus.testRank: Int
    get() = when (this) {
        SmsProviderStatus.PENDING -> 0
        SmsProviderStatus.COMPLETE -> 1
        SmsProviderStatus.DELIVERY_FAILED -> 2
        SmsProviderStatus.FAILED -> 3
    }

private val SmsProviderStatus.testBox: MessageBox
    get() = when (this) {
        SmsProviderStatus.PENDING -> MessageBox.OUTBOX
        SmsProviderStatus.COMPLETE,
        SmsProviderStatus.DELIVERY_FAILED,
        -> MessageBox.SENT
        SmsProviderStatus.FAILED -> MessageBox.FAILED
    }

private val SmsProviderStatus.testMessageStatus: MessageStatus
    get() = when (this) {
        SmsProviderStatus.PENDING -> MessageStatus.PENDING
        SmsProviderStatus.COMPLETE -> MessageStatus.COMPLETE
        SmsProviderStatus.DELIVERY_FAILED,
        SmsProviderStatus.FAILED,
        -> MessageStatus.FAILED
    }
