// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.core.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRouteTest {
    @Test
    fun coldRouteAcceptsOnlyTheExactNotificationProtocol() {
        assertEquals(
            ConversationId(812L),
            notificationConversationId(
                AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
                812L,
            ),
        )
        assertNull(notificationConversationId("android.intent.action.MAIN", 812L))
        assertNull(
            notificationConversationId(
                AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
                0L,
            ),
        )
    }

    @Test
    fun warmRouteCanReplaceOneValidConversationWithAnother() {
        var route = notificationConversationId(
            AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
            901L,
        )
        route = notificationConversationId(
            AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
            902L,
        )

        assertEquals(ConversationId(902L), route)
    }

    @Test
    fun newNotificationWinsOverRestoredEmptyOrOlderRoute() {
        assertEquals(
            ConversationId(904L),
            initialConversationId(
                action = AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
                rawConversationId = 904L,
                savedConversationId = -1L,
            ),
        )
        assertEquals(
            ConversationId(905L),
            initialConversationId(
                action = AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION,
                rawConversationId = 905L,
                savedConversationId = 903L,
            ),
        )
        assertEquals(
            ConversationId(903L),
            initialConversationId(
                action = "android.intent.action.MAIN",
                rawConversationId = null,
                savedConversationId = 903L,
            ),
        )
    }
}
