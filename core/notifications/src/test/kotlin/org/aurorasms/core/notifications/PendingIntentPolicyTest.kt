// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIntentPolicyTest {
    @Test
    fun ordinaryPendingIntents_areAlwaysImmutable() {
        val flags = PendingIntentPolicy.immutableUpdateCurrent()

        assertTrue(flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(0, flags and PendingIntent.FLAG_MUTABLE)
        assertTrue(flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test
    fun inlineReply_isMutableOnlyWhenPlatformRequiresExplicitMutability() {
        val api30 = PendingIntentPolicy.inlineReplyUpdateCurrent(sdkInt = 30)
        val api31 = PendingIntentPolicy.inlineReplyUpdateCurrent(sdkInt = 31)

        assertEquals(0, api30 and PendingIntent.FLAG_IMMUTABLE)
        assertEquals(0, api30 and PendingIntent.FLAG_MUTABLE)
        assertTrue(api30 and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertEquals(0, api31 and PendingIntent.FLAG_IMMUTABLE)
        assertTrue(api31 and PendingIntent.FLAG_MUTABLE != 0)
        assertTrue(api31 and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }
}
