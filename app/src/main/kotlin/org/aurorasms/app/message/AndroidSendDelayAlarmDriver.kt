// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import org.aurorasms.app.receiver.SendDelayAlarmReceiver
import org.aurorasms.core.state.SendDelayId

internal interface SendDelayAlarmDriver {
    fun arm(id: SendDelayId, dueTimestampMillis: Long): Boolean
    fun cancel(id: SendDelayId)
}

internal class AndroidSendDelayAlarmDriver(context: Context) : SendDelayAlarmDriver {
    private val appContext = context.applicationContext
    private val alarms = checkNotNull(appContext.getSystemService(AlarmManager::class.java))

    override fun arm(id: SendDelayId, dueTimestampMillis: Long): Boolean {
        if (dueTimestampMillis <= 0L) return false
        cancel(id)
        return try {
            if (canScheduleExact()) {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    dueTimestampMillis,
                    pendingIntent(id),
                )
            } else {
                // The process-local timer is the normal short-delay path. This
                // inexact alarm is only a process-death recovery wake-up.
                alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    dueTimestampMillis,
                    pendingIntent(id),
                )
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun cancel(id: SendDelayId) {
        runCatching { alarms.cancel(pendingIntent(id)) }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun pendingIntent(id: SendDelayId): PendingIntent {
        val intent = Intent(appContext, SendDelayAlarmReceiver::class.java)
            .setAction(ACTION_SEND_DELAY_DUE)
            .setData("aurorasms://send-delay/${id.value}".toUri())
            .putExtra(SendDelayAlarmReceiver.EXTRA_SEND_DELAY_ID, id.value)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ACTION_SEND_DELAY_DUE = "org.aurorasms.app.action.SEND_DELAY_DUE"
    }
}
