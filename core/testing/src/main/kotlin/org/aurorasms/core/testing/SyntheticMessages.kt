// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsProviderMessage

object SyntheticMessages {
    const val FIXED_TIMESTAMP_MILLIS: Long = 1_704_067_200_000L
    const val FIRST_BODY: String = "The cobalt kite is packed."
    const val SECOND_BODY: String = "Meet at Observatory Plaza at 19:30."

    val subscriptionId = AuroraSubscriptionId(1)
    val conversationId = ConversationId(101)
    val incomingSmsId = ProviderMessageId(ProviderKind.SMS, 1_001)
    val incomingMmsId = ProviderMessageId(ProviderKind.MMS, 2_001)
    val pendingOperationId = MessageId(ProviderKind.PENDING_OPERATION, 9_001)
    val deliveryFingerprint = MessageDeliveryFingerprint.fromSha256(
        ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + 1).toByte() },
    )

    fun oneToOneRecipients(): RecipientSet = validRecipients(
        SyntheticPeople.NOVA.address.value,
    )

    fun groupRecipients(): RecipientSet = validRecipients(
        SyntheticPeople.NOVA.address.value,
        SyntheticPeople.MILO.address.value,
    )

    fun incomingSmsRecord(): IncomingSmsRecord = IncomingSmsRecord(
        deliveryFingerprint = deliveryFingerprint,
        sender = SyntheticPeople.NOVA.address,
        body = FIRST_BODY,
        sentTimestampMillis = FIXED_TIMESTAMP_MILLIS - 500,
        receivedTimestampMillis = FIXED_TIMESTAMP_MILLIS,
        subscriptionId = subscriptionId,
    )

    fun smsProviderMessage(): SmsProviderMessage = SmsProviderMessage(
        id = incomingSmsId,
        threadId = conversationId.value,
        address = SyntheticPeople.NOVA.address,
        body = FIRST_BODY,
        direction = MessageDirection.INCOMING,
        timestampMillis = FIXED_TIMESTAMP_MILLIS,
        sentTimestampMillis = FIXED_TIMESTAMP_MILLIS - 500,
        subscriptionId = subscriptionId,
        read = false,
        seen = false,
    )

    fun decodedIncomingMmsRecord(): DecodedIncomingMmsRecord = DecodedIncomingMmsRecord(
        participants = listOf(SyntheticPeople.NOVA.address, SyntheticPeople.MILO.address),
        subject = "Observatory checklist",
        text = SECOND_BODY,
        sentTimestampMillis = FIXED_TIMESTAMP_MILLIS - 1_000,
        receivedTimestampMillis = FIXED_TIMESTAMP_MILLIS,
        subscriptionId = subscriptionId,
    )

    fun mmsProviderMessage(): MmsProviderMessage = MmsProviderMessage(
        id = incomingMmsId,
        threadId = conversationId.value,
        participants = listOf(SyntheticPeople.NOVA.address, SyntheticPeople.MILO.address),
        subject = "Observatory checklist",
        direction = MessageDirection.INCOMING,
        timestampMillis = FIXED_TIMESTAMP_MILLIS,
        sentTimestampMillis = FIXED_TIMESTAMP_MILLIS - 1_000,
        subscriptionId = subscriptionId,
        read = false,
        seen = false,
    )

    fun incomingNotification(): IncomingMessageNotification = IncomingMessageNotification(
        messageId = incomingSmsId.asMessageId(),
        conversationId = conversationId,
        senderDisplayName = SyntheticPeople.NOVA.displayName,
        senderPersonKey = "synthetic-nova-0101",
        body = FIRST_BODY,
        receivedAtEpochMillis = FIXED_TIMESTAMP_MILLIS,
        canReply = true,
    )

    private fun validRecipients(vararg addresses: String): RecipientSet =
        when (val result = RecipientSet.parse(addresses.asIterable())) {
            is RecipientSet.CreationResult.Valid -> result.recipients
            is RecipientSet.CreationResult.Rejected -> error(
                "Invalid synthetic recipient fixture: ${result.reason}",
            )
        }
}
