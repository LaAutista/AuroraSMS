// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmsRoleLifecycleFenceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun serializesTransitionsAndRevalidatesCurrentRoleAfterSuspendingLossCleanup() = runTest {
        var roleHeld = false
        val observedStates = mutableListOf<Boolean>()
        var staleLossActions = 0
        val lossCleanupEntered = CompletableDeferred<Unit>()
        val releaseLossCleanup = CompletableDeferred<Unit>()
        val fence = DefaultSmsRoleLifecycleFence(currentRoleHeld = { roleHeld })

        val lossTransition = launch {
            fence.reconcile { currentRoleHeld ->
                observedStates += currentRoleHeld
                if (!currentRoleHeld) {
                    lossCleanupEntered.complete(Unit)
                    releaseLossCleanup.await()
                    if (!roleHeld) staleLossActions += 1
                }
            }
        }
        runCurrent()
        lossCleanupEntered.await()

        roleHeld = true
        runCurrent()

        assertEquals(listOf(false), observedStates)

        releaseLossCleanup.complete(Unit)
        runCurrent()
        lossTransition.join()

        assertEquals(listOf(false, true), observedStates)
        assertEquals(0, staleLossActions)
    }
}
