// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyReceiverWorkTest {
    @Test
    fun broadcastDeadlineDoesNotCancelDurableReceiverWork() = runTest {
        var completed = false
        val work = launch {
            delay(1_000L)
            completed = true
        }

        awaitReceiverWorkWithoutCancelling(work, timeoutMillis = 100L)

        assertTrue(work.isActive)
        assertFalse(completed)
        advanceUntilIdle()
        assertTrue(work.isCompleted)
        assertTrue(completed)
    }

    @Test
    fun completedWorkReturnsWithinBroadcastWindow() = runTest {
        val work = launch { }

        awaitReceiverWorkWithoutCancelling(work, timeoutMillis = 100L)

        assertTrue(work.isCompleted)
    }
}
