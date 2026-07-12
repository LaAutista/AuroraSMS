// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val MESSAGES: String = "aurora_messages_v1"
    const val REPLY_FAILURES: String = "aurora_reply_failures_v1"

    fun ensureCreated(context: Context) {
        val messages = NotificationChannel(
            MESSAGES,
            context.getString(R.string.notification_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        val replyFailures = NotificationChannel(
            REPLY_FAILURES,
            context.getString(R.string.notification_channel_reply_failures_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reply_failures_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }

        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(messages, replyFailures))
    }
}
