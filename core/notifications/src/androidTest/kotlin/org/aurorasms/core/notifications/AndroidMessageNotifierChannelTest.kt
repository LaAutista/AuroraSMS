// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMessageNotifierChannelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun disabledReplyFailureChannelIsNotReportedAsPosted() {
        manager.deleteNotificationChannel(DISABLED_TEST_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                DISABLED_TEST_CHANNEL_ID,
                "Disabled reply failures",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        try {
            val notifier = AndroidMessageNotifier(
                context = context,
                intentFactory = NotificationIntentFactory {
                    error("A disabled channel must short-circuit before intent creation")
                },
                incomingGenerationTracker = IncomingNotificationGenerationTracker(
                    InMemoryIncomingNotificationGenerationStore(),
                ),
                notificationGateway = null,
                replyFailureChannelId = DISABLED_TEST_CHANNEL_ID,
            )

            assertSame(
                NotificationPostResult.NotificationsDisabled,
                notifier.notifyInlineReplyFailure(
                    InlineReplyFailureKey(
                        ConversationId(71L),
                        MessageId(
                            ProviderKind.PENDING_OPERATION,
                            INLINE_REPLY_OPERATION_ID_BOUNDARY + 91L,
                        ),
                    ),
                ),
            )
        } finally {
            manager.deleteNotificationChannel(DISABLED_TEST_CHANNEL_ID)
        }
    }

    private companion object {
        const val DISABLED_TEST_CHANNEL_ID = "aurora_reply_failures_disabled_test_v1"
    }
}
