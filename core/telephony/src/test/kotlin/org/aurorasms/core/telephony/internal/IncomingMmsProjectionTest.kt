// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMmsProjectionTest {
    @Test
    fun directMessageWithoutLineNumberUsesOnlyTheSenderForThreadIdentity() {
        val result = retrieved(to = listOf(LOCAL)).toIncomingProviderRecord(
            journal = journal(),
            ownAddress = null,
            addressesMatch = EXACT_MATCH,
        ) as IncomingMmsProjectionResult.Ready

        assertEquals(listOf(SENDER), result.record.participants)
        assertEquals(listOf(LOCAL), result.record.to)
        assertEquals(TEXT, result.record.text)
    }

    @Test
    fun groupFailsClosedWithoutOwnAddressAndExcludesItWhenAvailable() {
        val retrieved = retrieved(to = listOf(LOCAL, GROUP_MEMBER))

        assertEquals(
            IncomingMmsProjectionResult.OwnAddressUnavailable,
            retrieved.toIncomingProviderRecord(journal(), null, EXACT_MATCH),
        )
        val ready = retrieved.toIncomingProviderRecord(
            journal = journal(),
            ownAddress = LOCAL,
            addressesMatch = EXACT_MATCH,
        ) as IncomingMmsProjectionResult.Ready

        assertEquals(listOf(SENDER, GROUP_MEMBER), ready.record.participants)
        assertEquals(listOf(LOCAL, GROUP_MEMBER), ready.record.to)
    }

    @Test
    fun callbackTransactionMismatchAndMissingSenderAreMalformed() {
        assertEquals(
            IncomingMmsProjectionResult.Malformed,
            retrieved(transactionId = "Twrong").toIncomingProviderRecord(journal(), LOCAL, EXACT_MATCH),
        )
        assertEquals(
            IncomingMmsProjectionResult.Malformed,
            retrieved(sender = null).toIncomingProviderRecord(journal(), LOCAL, EXACT_MATCH),
        )
    }

    @Test
    fun projectionAndPartsRemainContentRedactedInDiagnostics() {
        val result = retrieved(to = listOf(LOCAL)).toIncomingProviderRecord(
            journal(),
            LOCAL,
            EXACT_MATCH,
        ) as IncomingMmsProjectionResult.Ready

        assertTrue(!result.toString().contains(TEXT))
        assertTrue(!result.record.toString().contains(TEXT))
        assertTrue(!result.record.parts.single().toString().contains(TEXT))
    }

    private fun retrieved(
        sender: ParticipantAddress? = SENDER,
        to: List<ParticipantAddress> = listOf(LOCAL),
        transactionId: String = TRANSACTION,
    ): BoundedMmsPdu.Retrieved = BoundedMmsPdu.Retrieved(
        sender = sender,
        to = to,
        cc = emptyList(),
        participants = listOfNotNull(sender) + to,
        subject = "Synthetic subject",
        sentTimestampMillis = 1_720_000_000_000L,
        messageId = "synthetic-message",
        transactionId = transactionId,
        parts = listOf(
            BoundedMmsPart(
                contentType = "text/plain",
                charsetMibEnum = 106,
                name = "text.txt",
                filename = "text.txt",
                contentLocation = "text.txt",
                contentId = "<text>",
                contentDisposition = "inline",
                decodedText = TEXT,
                bytes = TEXT.encodeToByteArray(),
            ),
        ),
    )

    private fun journal(): IncomingMmsDownloadJournal.Record = IncomingMmsDownloadJournal.Record(
        operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            INCOMING_MMS_OPERATION_ID_BOUNDARY + 211L,
        ),
        subscriptionId = AuroraSubscriptionId(2),
        transactionId = TRANSACTION,
        notificationDigest = "a".repeat(64),
        expectedSizeBytes = 4_096L,
        receivedTimestampMillis = 1_720_000_005_000L,
        fileName = "11111111-1111-4111-8111-111111111111.pdu",
        providerId = null,
        conversationId = null,
        state = IncomingMmsDownloadJournal.State.CALLBACK_SUCCEEDED,
        createdAtMillis = 1_720_000_000_100L,
    )

    private companion object {
        val SENDER = ParticipantAddress("+15551230000")
        val LOCAL = ParticipantAddress("+15551230001")
        val GROUP_MEMBER = ParticipantAddress("+15551230002")
        val EXACT_MATCH: (ParticipantAddress, ParticipantAddress) -> Boolean = { first, second ->
            first.value == second.value
        }
        const val TRANSACTION = "Tincoming-projection"
        const val TEXT = "Synthetic incoming body"
    }
}
