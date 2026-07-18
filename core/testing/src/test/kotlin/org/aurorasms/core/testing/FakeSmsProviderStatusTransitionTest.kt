// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSmsProviderStatusTransitionTest {
    @Test
    fun transitionMatrixNeverRegressesAndSkipsNoOpRewrites() = runTest {
        SmsProviderStatus.entries.forEach { currentStatus ->
            SmsProviderStatus.entries.forEach { requestedStatus ->
                val fake = FakeSmsProviderDataSource()
                val stored = fake.insertTestOutgoing()
                if (currentStatus != SmsProviderStatus.PENDING) {
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
    fun unknownSeedStateFailsClosedWithoutMutation() = runTest {
        val original = SyntheticMessages.smsProviderMessage()
        val fake = FakeSmsProviderDataSource(listOf(original))

        val result = fake.updateStatus(original.id, SmsProviderStatus.FAILED)

        assertTrue(result is ProviderAccessResult.Unavailable)
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
