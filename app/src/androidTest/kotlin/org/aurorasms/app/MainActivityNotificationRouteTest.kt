// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val scenario = ActivityScenario.launch<MainActivity>(notificationIntent(601L))
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
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(ConversationId(602L), activity.openedConversationId)
            }
        } finally {
            scenario.close()
        }
    }

    private fun notificationIntent(conversationId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION)
            .putExtra(AppNotificationIntentFactory.EXTRA_CONVERSATION_ID, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
