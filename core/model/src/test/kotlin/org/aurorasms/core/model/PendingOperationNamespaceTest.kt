// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingOperationNamespaceTest {
    @Test
    fun futureAllocationRegionsArePositiveDisjointAndBoundaryExact() {
        assertNamespace(1L, PendingOperationNamespace.RESPOND_VIA)
        assertNamespace(
            INCOMING_MMS_OPERATION_ID_BOUNDARY - 1L,
            PendingOperationNamespace.RESPOND_VIA,
        )
        assertNamespace(
            INCOMING_MMS_OPERATION_ID_BOUNDARY,
            PendingOperationNamespace.INCOMING_MMS,
        )
        assertNamespace(
            COMPOSER_OPERATION_ID_BOUNDARY - 1L,
            PendingOperationNamespace.INCOMING_MMS,
        )
        assertNamespace(
            COMPOSER_OPERATION_ID_BOUNDARY,
            PendingOperationNamespace.COMPOSER,
        )
        assertNamespace(
            INLINE_REPLY_OPERATION_ID_BOUNDARY - 1L,
            PendingOperationNamespace.COMPOSER,
        )
        assertNamespace(
            INLINE_REPLY_OPERATION_ID_BOUNDARY,
            PendingOperationNamespace.INLINE_REPLY,
        )
        assertNamespace(Long.MAX_VALUE, PendingOperationNamespace.INLINE_REPLY)
    }

    @Test
    fun nonPendingKindNeverClassifiesAsOperationOwnership() {
        ProviderKind.entries
            .filterNot { it == ProviderKind.PENDING_OPERATION }
            .forEach { kind ->
                assertNull(
                    MessageId(kind, COMPOSER_OPERATION_ID_BOUNDARY)
                        .pendingOperationNamespaceOrNull(),
                )
            }
    }

    @Test
    fun zeroAndNegativeIdentifiersRemainInvalidAtTheModelBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            MessageId(ProviderKind.PENDING_OPERATION, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageId(ProviderKind.PENDING_OPERATION, -1L)
        }
    }

    private fun assertNamespace(value: Long, expected: PendingOperationNamespace) {
        assertEquals(
            expected,
            MessageId(ProviderKind.PENDING_OPERATION, value)
                .pendingOperationNamespaceOrNull(),
        )
    }
}
