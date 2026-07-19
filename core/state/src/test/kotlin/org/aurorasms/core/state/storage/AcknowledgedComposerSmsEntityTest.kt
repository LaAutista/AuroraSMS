// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.state.AcknowledgedComposerSmsCallbackProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AcknowledgedComposerSmsEntityTest {
    @Test
    fun everyStableCallbackProofRoundTripsWithoutContentColumns() {
        AcknowledgedComposerSmsCallbackProof.entries.forEach { proof ->
            val updated = if (proof == AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK) {
                40L
            } else {
                41L
            }
            val entity = validEntity(proof.storageCode, updated)

            val receipt = entity.toDomain()

            assertEquals(proof, receipt.callbackProof)
            assertEquals(COMPOSER_OPERATION_ID_BOUNDARY + 7L, receipt.operationId.value)
            assertTrue(entity.toString().endsWith("REDACTED)"))
        }
    }

    @Test
    fun unknownProofOutOfNamespaceAndInvalidProofTimestampFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            validEntity("future_proof_v2", 41L).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK.storageCode,
                41L,
            ).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                AcknowledgedComposerSmsCallbackProof.SENT.storageCode,
                40L,
            ).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEntity(
                AcknowledgedComposerSmsCallbackProof.SENT.storageCode,
                41L,
                localOperationId = COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE,
            ).toDomain()
        }
    }

    private fun validEntity(
        callbackProofCode: String,
        updatedTimestampMillis: Long,
        localOperationId: Long = 7L,
    ): AcknowledgedComposerSmsEntity = AcknowledgedComposerSmsEntity(
        localOperationId = localOperationId,
        providerMessageId = 101L,
        providerConversationId = 202L,
        unitCount = 1,
        callbackProofCode = callbackProofCode,
        acknowledgedTimestampMillis = 40L,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}
