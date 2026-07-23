// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.pendingOperationNamespaceOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMmsOperationIdTest {
    @Test
    fun allocatorOwnsExactlyTheIncomingMmsNumericRegion() {
        assertEquals(
            INCOMING_MMS_OPERATION_ID_BOUNDARY,
            allocateIncomingMmsOperationId { 0L }.value,
        )
        assertEquals(
            COMPOSER_OPERATION_ID_BOUNDARY - 1L,
            allocateIncomingMmsOperationId { -1L }.value,
        )

        repeat(1_000) {
            val operation = allocateIncomingMmsOperationId()
            assertTrue(operation.value in INCOMING_MMS_OPERATION_ID_BOUNDARY until COMPOSER_OPERATION_ID_BOUNDARY)
            assertEquals(PendingOperationNamespace.INCOMING_MMS, operation.pendingOperationNamespaceOrNull())
        }
    }
}
