// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.state.ComposerSmsOperationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSmsOperationEntityTest {
    @Test
    fun everyStablePhaseCodeRoundTripsWithoutContentColumns() {
        ComposerSmsOperationPhase.entries.forEach { phase ->
            val bound = phase != ComposerSmsOperationPhase.RESERVED
            val entity = validEntity(
                phaseCode = phase.storageCode,
                providerMessageId = if (bound) 101L else null,
                providerConversationId = if (bound) 202L else null,
                unitCount = if (bound) 1 else null,
            )

            val operation = entity.toDomain()

            assertEquals(phase, operation.phase)
            assertEquals(bound, operation.providerBinding != null)
            assertEquals(COMPOSER_OPERATION_ID_BOUNDARY + 7L, operation.operationId.value)
            assertTrue(entity.toString().endsWith("REDACTED)"))
        }
    }

    @Test
    fun unknownPhasePartialBindingAndOutOfNamespaceLocalIdFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            validEntity(phaseCode = "future_phase_v2").toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                phaseCode = ComposerSmsOperationPhase.PREPARED.storageCode,
                providerMessageId = 101L,
                providerConversationId = null,
                unitCount = 1,
            ).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(localOperationId = COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE).toDomain()
        }
    }

    private fun validEntity(
        localOperationId: Long = 7L,
        phaseCode: String = ComposerSmsOperationPhase.RESERVED.storageCode,
        providerMessageId: Long? = null,
        providerConversationId: Long? = null,
        unitCount: Int? = null,
    ): ComposerSmsOperationEntity = ComposerSmsOperationEntity(
        localOperationId = localOperationId,
        providerThreadId = 10L,
        draftId = 11L,
        draftRevisionMillis = 12L,
        subscriptionId = 13,
        phaseCode = phaseCode,
        providerMessageId = providerMessageId,
        providerConversationId = providerConversationId,
        unitCount = unitCount,
        createdTimestampMillis = 14L,
        updatedTimestampMillis = 15L,
    )
}
