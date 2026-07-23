// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.aurorasms.app.receiver.NotificationReminderAlarmReceiver

internal interface NotificationReminderAlarmDriver {
    fun arm(id: NotificationReminderId, dueTimestampMillis: Long): Boolean
    fun cancel(id: NotificationReminderId)
}

internal class AndroidNotificationReminderAlarmDriver(context: Context) :
    NotificationReminderAlarmDriver {
    private val appContext = context.applicationContext
    private val alarms = checkNotNull(appContext.getSystemService(AlarmManager::class.java))

    override fun arm(id: NotificationReminderId, dueTimestampMillis: Long): Boolean {
        if (dueTimestampMillis <= 0L) return false
        cancel(id)
        return try {
            // A reminder is intentionally approximate and one-shot. It does not
            // consume exact-alarm access and cannot create a repeating wake-up.
            alarms.setWindow(
                AlarmManager.RTC_WAKEUP,
                dueTimestampMillis,
                ALARM_WINDOW_MILLIS,
                pendingIntent(id),
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun cancel(id: NotificationReminderId) {
        runCatching { alarms.cancel(pendingIntent(id)) }
    }

    private fun pendingIntent(id: NotificationReminderId): PendingIntent {
        val intent = Intent(appContext, NotificationReminderAlarmReceiver::class.java)
            .setAction(ACTION_NOTIFICATION_REMINDER_DUE)
            .setData("aurorasms://notification-reminder/${id.value}".toUri())
            .putExtra(NotificationReminderAlarmReceiver.EXTRA_REMINDER_ID, id.value)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ACTION_NOTIFICATION_REMINDER_DUE =
            "org.aurorasms.app.action.NOTIFICATION_REMINDER_DUE"
        const val ALARM_WINDOW_MILLIS = 5 * 60 * 1_000L
    }
}
