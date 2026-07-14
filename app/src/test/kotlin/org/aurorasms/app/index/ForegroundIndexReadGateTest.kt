// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundIndexReadGateTest {
    @Test
    fun providerReadIsDeniedUntilTheMessagingUiStarts() {
        val gate = ForegroundIndexReadGate()
        assertFalse(gate.isProviderReadPermitted())

        assertTrue(gate.onActivityStarted())
        assertTrue(gate.isProviderReadPermitted())
    }

    @Test
    fun everyStartedActivityMustStopBeforeReadsAreDeniedAgain() {
        val gate = ForegroundIndexReadGate()
        assertTrue(gate.onActivityStarted())
        assertFalse(gate.onActivityStarted())
        gate.onActivityStopped()
        assertTrue(gate.isProviderReadPermitted())

        gate.onActivityStopped()
        gate.onActivityStopped() // A duplicate stop cannot make the count negative.
        assertFalse(gate.isProviderReadPermitted())

        assertTrue(gate.onActivityStarted())
        assertTrue(gate.isProviderReadPermitted())
    }
}
