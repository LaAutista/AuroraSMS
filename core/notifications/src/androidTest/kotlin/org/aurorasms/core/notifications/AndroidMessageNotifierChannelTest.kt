// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.ConversationId
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMessageNotifierChannelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun disabledReplyFailureChannelIsNotReportedAsPosted() {
        manager.deleteNotificationChannel(NotificationChannels.REPLY_FAILURES)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.REPLY_FAILURES,
                "Disabled reply failures",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        try {
            val notifier = AndroidMessageNotifier(context) {
                error("A disabled channel must short-circuit before intent creation")
            }

            assertSame(
                NotificationPostResult.NotificationsDisabled,
                notifier.notifyInlineReplyFailure(ConversationId(71L)),
            )
        } finally {
            manager.deleteNotificationChannel(NotificationChannels.REPLY_FAILURES)
            NotificationChannels.ensureCreated(context)
        }
    }
}
