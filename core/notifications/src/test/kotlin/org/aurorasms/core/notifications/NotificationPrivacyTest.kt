// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationPrivacyTest {
    private val privateContent = NotificationContent(
        senderDisplayName = "Nova Reed",
        body = "The cobalt kite is packed.",
        conversationTitle = "Observatory Crew",
        isGroupConversation = true,
    )
    private val localizedText = NotificationPrivacyText(
        genericTitle = "AuroraSMS",
        genericBody = "New message",
        senderOnlyBody = "New message",
    )

    @Test
    fun senderAndBody_retainsAllowedContent() {
        val presentation = NotificationPrivacy.SENDER_AND_BODY.present(
            privateContent,
            localizedText,
        )

        assertEquals("Observatory Crew", presentation.title)
        assertEquals("The cobalt kite is packed.", presentation.body)
        assertEquals("Nova Reed", presentation.messagingPersonName)
        assertEquals("The cobalt kite is packed.", presentation.messagingText)
    }

    @Test
    fun senderOnly_removesBodyBeforeNotificationConstruction() {
        val presentation = NotificationPrivacy.SENDER_ONLY.present(
            privateContent,
            localizedText,
        )

        assertEquals("Nova Reed", presentation.title)
        assertEquals("Nova Reed", presentation.messagingPersonName)
        assertEquals("New message", presentation.body)
        assertEquals(null, presentation.conversationTitle)
        assertFalse(presentation.toString().contains(privateContent.body))
    }

    @Test
    fun generic_removesSenderBodyAndConversationBeforeNotificationConstruction() {
        val presentation = NotificationPrivacy.GENERIC.present(
            privateContent,
            localizedText,
        )

        assertEquals("AuroraSMS", presentation.title)
        assertEquals("New message", presentation.body)
        assertEquals(null, presentation.conversationTitle)
        assertFalse(presentation.toString().contains(privateContent.senderDisplayName))
        assertFalse(presentation.toString().contains(privateContent.body))
        assertFalse(presentation.toString().contains(privateContent.conversationTitle!!))
        assertEquals(
            GENERIC_NOTIFICATION_PERSON_KEY,
            NotificationPrivacy.GENERIC.personKey("synthetic-nova-0101"),
        )
        assertEquals(
            "synthetic-nova-0101",
            NotificationPrivacy.SENDER_ONLY.personKey("synthetic-nova-0101"),
        )
    }

    @Test
    fun incomingNotification_toStringRedactsContentAndIdentifiers() {
        val notification = IncomingMessageNotification(
            messageId = MessageId(ProviderKind.SMS, 88),
            conversationId = ConversationId(77),
            senderDisplayName = "Nova Reed",
            senderPersonKey = "synthetic-nova-0101",
            body = "The cobalt kite is packed.",
            receivedAtEpochMillis = 1_704_067_200_000L,
        )

        val rendered = notification.toString()
        assertFalse(rendered.contains("Nova Reed"))
        assertFalse(rendered.contains("synthetic-nova-0101"))
        assertFalse(rendered.contains("The cobalt kite is packed."))
        assertFalse(rendered.contains("77"))
        assertFalse(rendered.contains("88"))
    }
}
