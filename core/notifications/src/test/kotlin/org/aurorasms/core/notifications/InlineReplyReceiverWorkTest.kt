// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InlineReplyReceiverWorkTest {
    @Test
    fun handlerRuntimeFailureIsContained() = runTest {
        var calls = 0
        val request = InlineReplyRequest(
            conversationId = org.aurorasms.core.model.ConversationId(7L),
            notificationId = 8,
            replyRequestId = "SMS:9",
            text = "hello",
        )

        runInlineReplyHandlerSafely(
            handler = InlineReplyHandler {
                calls += 1
                throw IllegalStateException("test failure")
            },
            request = request,
        )

        assertEquals(1, calls)
    }

    @Test
    fun timeoutFinishesPendingResultWithoutCancellingIndependentWork() = runTest {
        val sibling = backgroundScope.launch { awaitCancellation() }
        var finished = false
        val waiter = launch {
            finishAfterBoundedSiblingJoin(
                sibling = sibling,
                maximumWaitMillis = 100L,
                finish = { finished = true },
            )
        }
        runCurrent()

        advanceTimeBy(99L)
        runCurrent()
        assertFalse(finished)
        assertTrue(sibling.isActive)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(finished)
        assertTrue(waiter.isCompleted)
        assertTrue(sibling.isActive)

        sibling.cancel()
    }

    @Test
    fun completedWorkFinishesPendingResultWithoutWaitingForBudget() = runTest {
        val sibling = launch { }
        var finished = false

        finishAfterBoundedSiblingJoin(
            sibling = sibling,
            maximumWaitMillis = 8_000L,
            finish = { finished = true },
        )

        assertTrue(finished)
        assertTrue(sibling.isCompleted)
    }

    @Test
    fun cancellingWaiterFinishesPendingResultWithoutCancellingSibling() = runTest {
        val sibling = backgroundScope.launch { awaitCancellation() }
        var finished = false
        val waiter = launch {
            finishAfterBoundedSiblingJoin(
                sibling = sibling,
                maximumWaitMillis = 8_000L,
                finish = { finished = true },
            )
        }
        runCurrent()

        waiter.cancel()
        runCurrent()

        assertTrue(finished)
        assertTrue(waiter.isCancelled)
        assertTrue(sibling.isActive)
        sibling.cancel()
    }
}
