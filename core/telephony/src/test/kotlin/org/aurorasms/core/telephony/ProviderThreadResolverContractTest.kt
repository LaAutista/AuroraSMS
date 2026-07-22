// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderThreadResolverContractTest {
    @Test
    fun `unavailable implementation is fail closed`() = runTest {
        val result = UnavailableProviderThreadResolver.resolveExact(valid("+15550102020"))

        assertSame(ProviderThreadResolution.PlatformUnavailable, result)
    }

    @Test
    fun `verified result redacts its provider identifier`() {
        val result = ProviderThreadResolution.Verified(ProviderThreadId(8675309), participantCount = 1)

        assertEquals(1, result.participantCount)
        assertTrue(!result.toString().contains("8675309"))
        assertTrue(result.toString().contains("REDACTED"))
    }

    private fun valid(vararg addresses: String): RecipientSet =
        (RecipientSet.parse(addresses.asList()) as RecipientSet.CreationResult.Valid).recipients
}
