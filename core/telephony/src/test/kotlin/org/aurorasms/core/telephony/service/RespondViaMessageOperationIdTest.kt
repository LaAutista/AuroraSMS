// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.service

import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondViaMessageOperationIdTest {
    @Test
    fun ordinaryOperationIdsRetryZeroAndStayBelowComposerNamespace() {
        val values = ArrayDeque(listOf(Long.MIN_VALUE, Long.MAX_VALUE))

        val operationId = nextOrdinaryOperationId(values::removeFirst)

        assertEquals(INCOMING_MMS_OPERATION_ID_BOUNDARY - 1L, operationId)
        assertTrue(operationId > 0L)
        assertTrue(operationId < INCOMING_MMS_OPERATION_ID_BOUNDARY)
    }

    @Test
    fun signedRandomValuesCannotSetComposerOrInlineReplyNamespaceBits() {
        listOf(-1L, Long.MIN_VALUE + 1L, Long.MAX_VALUE, 1L).forEach { randomValue ->
            val operationId = nextOrdinaryOperationId { randomValue }

            assertTrue(operationId > 0L)
            assertTrue(operationId < INCOMING_MMS_OPERATION_ID_BOUNDARY)
        }
    }
}
