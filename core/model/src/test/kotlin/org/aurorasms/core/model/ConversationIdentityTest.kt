// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationIdentityTest {
    @Test
    fun checkedCompatibilityRoundTrips() {
        val provider = ProviderThreadId(42L)

        assertEquals(provider, provider.asConversationId().asProviderThreadId())
    }
}
