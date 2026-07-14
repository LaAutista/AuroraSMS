// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.core.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNotificationRouteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun coldAndSingleTopWarmIntentsReachTheirExactConversations() {
        val coldLaunchIntent = notificationIntent(601L)
        val scenario = ActivityScenario.launch<MainActivity>(coldLaunchIntent)
        try {
            scenario.onActivity { activity ->
                assertEquals(ConversationId(601L), activity.openedConversationId)
            }

            context.startActivity(
                notificationIntent(602L).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            scenario.waitForOpenedConversation(ConversationId(602L))
        } finally {
            // MainActivity deliberately replaces a consumed notification intent with ACTION_MAIN.
            // ActivityScenario identifies its activity by the launch intent's filter fields, so
            // restore that identity before close or it ignores the real DESTROYED callback.
            scenario.onActivity { activity -> activity.intent = Intent(coldLaunchIntent) }
            scenario.close()
        }
    }

    private fun notificationIntent(conversationId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION)
            .putExtra(AppNotificationIntentFactory.EXTRA_CONVERSATION_ID, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ActivityScenario<MainActivity>.waitForOpenedConversation(
        expected: ConversationId,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + ROUTE_TIMEOUT_MILLIS
        var actual: ConversationId?
        do {
            actual = null
            onActivity { activity -> actual = activity.openedConversationId }
            if (actual == expected) return
            SystemClock.sleep(ROUTE_POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        assertEquals(expected, actual)
    }

    private companion object {
        const val ROUTE_TIMEOUT_MILLIS = 5_000L
        const val ROUTE_POLL_INTERVAL_MILLIS = 10L
    }
}
