// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSmsOperationContractTest {
    @Test
    fun pendingOperationNamespace_isDisjointAndBounded() {
        assertFalse(pendingId(COMPOSER_OPERATION_ID_BOUNDARY).isComposerSmsOperationId())
        assertTrue(pendingId(COMPOSER_OPERATION_ID_BOUNDARY + 1L).isComposerSmsOperationId())
        assertTrue(pendingId(INLINE_REPLY_OPERATION_ID_BOUNDARY - 1L).isComposerSmsOperationId())
        assertFalse(pendingId(INLINE_REPLY_OPERATION_ID_BOUNDARY).isComposerSmsOperationId())
        assertFalse(
            MessageId(ProviderKind.SMS, COMPOSER_OPERATION_ID_BOUNDARY + 1L)
                .isComposerSmsOperationId(),
        )
    }

    @Test
    fun phasesRequireCoherentContentFreeProviderBinding() {
        val binding = binding()

        validOperation(ComposerSmsOperationPhase.RESERVED, null)
        validOperation(ComposerSmsOperationPhase.KNOWN_UNSENT, null)
        ComposerSmsOperationPhase.entries
            .filterNot { it == ComposerSmsOperationPhase.RESERVED }
            .forEach { phase -> validOperation(phase, binding) }

        assertThrows(IllegalArgumentException::class.java) {
            validOperation(ComposerSmsOperationPhase.RESERVED, binding)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(ComposerSmsOperationPhase.PREPARED, null)
        }
        val mmsBinding = ComposerSmsProviderBinding(
            providerMessageId = ProviderMessageId(ProviderKind.MMS, 9L),
            providerConversationId = ConversationId(8L),
            unitCount = 1,
        )
        validOperation(
            ComposerSmsOperationPhase.PREPARED,
            mmsBinding,
            transport = MessageTransportKind.MMS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(ComposerSmsOperationPhase.PREPARED, mmsBinding)
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(unitCount = MAXIMUM_COMPOSER_SMS_UNIT_COUNT + 1)
        }
    }

    @Test
    fun reservationCarriesAuthoritativeContentOnlyInMemoryAndRedactsDiagnostics() {
        val secret = "synthetic-secret-composer-body"
        val secretSubject = "synthetic-secret-composer-subject"
        val operation = validOperation(ComposerSmsOperationPhase.RESERVED, null)
        val reservation = ComposerSmsReservation(operation, secret, secretSubject)

        assertEquals(secret, reservation.authoritativeBody)
        assertEquals(secretSubject, reservation.authoritativeSubject)
        assertFalse(operation.toString().contains(secret))
        assertFalse(reservation.toString().contains(secret))
        assertFalse(reservation.toString().contains(secretSubject))
        assertEquals("ComposerSmsOperationRevision(REDACTED)", operation.revision.toString())
        assertEquals(
            "ComposerSmsOperationResult.Success(REDACTED)",
            ComposerSmsOperationResult.Success(reservation).toString(),
        )
    }

    @Test
    fun revisionsAndTimestampsAreMonotonic() {
        assertThrows(IllegalArgumentException::class.java) {
            ComposerSmsOperationRevision(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validOperation(
                phase = ComposerSmsOperationPhase.RESERVED,
                providerBinding = null,
                createdTimestampMillis = 2L,
                updatedTimestampMillis = 1L,
            )
        }
    }

    private fun validOperation(
        phase: ComposerSmsOperationPhase,
        providerBinding: ComposerSmsProviderBinding?,
        createdTimestampMillis: Long = 10L,
        updatedTimestampMillis: Long = 20L,
        transport: MessageTransportKind = MessageTransportKind.SMS,
    ): ComposerSmsOperation = ComposerSmsOperation(
        operationId = pendingId(COMPOSER_OPERATION_ID_BOUNDARY + 7L),
        providerThreadId = ProviderThreadId(6L),
        draftId = DraftId(5L),
        draftRevision = DraftRevision(4L),
        subscriptionId = AuroraSubscriptionId(3),
        phase = phase,
        providerBinding = providerBinding,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
        transport = transport,
    )

    private fun binding(): ComposerSmsProviderBinding = ComposerSmsProviderBinding(
        providerMessageId = ProviderMessageId(ProviderKind.SMS, 9L),
        providerConversationId = ConversationId(8L),
        unitCount = 1,
    )

    private fun pendingId(value: Long): MessageId =
        MessageId(ProviderKind.PENDING_OPERATION, value)
}
