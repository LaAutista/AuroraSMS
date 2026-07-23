// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsProviderStatusTransitionTest {
    @Test
    fun outgoingArmRequiresOneFreshExactConditionalWrite() {
        ConditionalSmsStatusWriteResult.entries.forEach { writeResult ->
            var writes = 0

            val result = armOutgoingSmsProviderRow {
                writes += 1
                writeResult
            }

            assertEquals(1, writes)
            assertEquals(
                if (writeResult == ConditionalSmsStatusWriteResult.UPDATED) {
                    OutgoingSmsArmResult.ARMED
                } else {
                    OutgoingSmsArmResult.UNAVAILABLE
                },
                result,
            )
        }
    }

    @Test
    fun transitionMatrixOnlyAdvancesTowardStrongerStates() {
        SmsProviderStatus.entries.forEach { current ->
            SmsProviderStatus.entries.forEach { requested ->
                val expected = if (requested.testRank > current.testRank) {
                    SmsProviderStatusTransition.ADVANCE
                } else {
                    SmsProviderStatusTransition.KEEP_CURRENT
                }

                assertEquals(
                    "$current -> $requested",
                    expected,
                    smsProviderStatusTransition(current, requested),
                )
            }
        }
    }

    @Test
    fun staleWriteRereadsAndAcceptsConcurrentStrongerState() {
        var reads = 0
        var writes = 0

        val result = updateSmsStatusMonotonically(
            requested = SmsProviderStatus.COMPLETE,
            maxWriteAttempts = 4,
            readCurrent = {
                reads += 1
                if (reads == 1) SmsProviderStatus.PENDING else SmsProviderStatus.DELIVERY_FAILED
            },
            conditionalWrite = { expected, requested ->
                assertEquals(SmsProviderStatus.PENDING, expected)
                assertEquals(SmsProviderStatus.COMPLETE, requested)
                writes += 1
                ConditionalSmsStatusWriteResult.STALE
            },
        )

        assertEquals(MonotonicSmsStatusUpdateResult.SUCCESS, result)
        assertEquals(2, reads)
        assertEquals(1, writes)
    }

    @Test
    fun repeatedStaleWritesStopAfterBoundedFinalReread() {
        var reads = 0
        var writes = 0

        val result = updateSmsStatusMonotonically(
            requested = SmsProviderStatus.FAILED,
            maxWriteAttempts = 3,
            readCurrent = {
                reads += 1
                SmsProviderStatus.PENDING
            },
            conditionalWrite = { _, _ ->
                writes += 1
                ConditionalSmsStatusWriteResult.STALE
            },
        )

        assertEquals(MonotonicSmsStatusUpdateResult.UNAVAILABLE, result)
        assertEquals(4, reads)
        assertEquals(3, writes)
    }

    @Test
    fun missingOrUnknownCurrentStateFailsClosedWithoutWriting() {
        var writes = 0

        val result = updateSmsStatusMonotonically(
            requested = SmsProviderStatus.FAILED,
            maxWriteAttempts = 4,
            readCurrent = { null },
            conditionalWrite = { _, _ ->
                writes += 1
                ConditionalSmsStatusWriteResult.UPDATED
            },
        )

        assertEquals(MonotonicSmsStatusUpdateResult.UNAVAILABLE, result)
        assertEquals(0, writes)
    }

    @Test
    fun equalOrStrongerCurrentStateSucceedsWithoutWriting() {
        var writes = 0

        val result = updateSmsStatusMonotonically(
            requested = SmsProviderStatus.COMPLETE,
            maxWriteAttempts = 4,
            readCurrent = { SmsProviderStatus.FAILED },
            conditionalWrite = { _, _ ->
                writes += 1
                ConditionalSmsStatusWriteResult.UPDATED
            },
        )

        assertEquals(MonotonicSmsStatusUpdateResult.SUCCESS, result)
        assertEquals(0, writes)
    }

    @Test
    fun exactStatusRetryRechecksOwnershipAfterAStaleWrite() {
        var reads = 0
        var writes = 0

        val result = updateExactOutgoingSmsStatusMonotonically(
            requested = SmsProviderStatus.COMPLETE,
            maxWriteAttempts = 4,
            readCurrent = {
                reads += 1
                if (reads == 1) {
                    ExactOutgoingSmsStatusReadResult.Found(SmsProviderStatus.PENDING)
                } else {
                    ExactOutgoingSmsStatusReadResult.OwnershipConflict
                }
            },
            conditionalWrite = { _, _ ->
                writes += 1
                ConditionalSmsStatusWriteResult.STALE
            },
        )

        assertEquals(ExactOutgoingSmsStatusUpdateResult.OWNERSHIP_CONFLICT, result)
        assertEquals(2, reads)
        assertEquals(1, writes)
    }

    @Test
    fun exactStatusDistinguishesMissingAndUnavailableWithoutWriting() {
        var writes = 0
        val write: (SmsProviderStatus, SmsProviderStatus) -> ConditionalSmsStatusWriteResult =
            { _, _ ->
                writes += 1
                ConditionalSmsStatusWriteResult.UPDATED
            }

        assertEquals(
            ExactOutgoingSmsStatusUpdateResult.ROW_ABSENT,
            updateExactOutgoingSmsStatusMonotonically(
                requested = SmsProviderStatus.FAILED,
                maxWriteAttempts = 1,
                readCurrent = { ExactOutgoingSmsStatusReadResult.RowAbsent },
                conditionalWrite = write,
            ),
        )
        assertEquals(
            ExactOutgoingSmsStatusUpdateResult.UNAVAILABLE,
            updateExactOutgoingSmsStatusMonotonically(
                requested = SmsProviderStatus.FAILED,
                maxWriteAttempts = 1,
                readCurrent = { ExactOutgoingSmsStatusReadResult.Unavailable },
                conditionalWrite = write,
            ),
        )
        assertEquals(0, writes)
    }
}

private val SmsProviderStatus.testRank: Int
    get() = when (this) {
        SmsProviderStatus.PENDING -> 0
        SmsProviderStatus.COMPLETE -> 1
        SmsProviderStatus.DELIVERY_FAILED -> 2
        SmsProviderStatus.FAILED -> 3
    }
