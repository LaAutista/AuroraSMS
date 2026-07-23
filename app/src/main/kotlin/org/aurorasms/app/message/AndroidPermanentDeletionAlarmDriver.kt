// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import org.aurorasms.app.receiver.PermanentDeletionAlarmReceiver
import org.aurorasms.core.state.PermanentDeletionId

internal interface PermanentDeletionAlarmDriver {
    fun arm(id: PermanentDeletionId, dueTimestampMillis: Long): Boolean
    fun cancel(id: PermanentDeletionId)
}

internal class AndroidPermanentDeletionAlarmDriver(context: Context) : PermanentDeletionAlarmDriver {
    private val appContext = context.applicationContext
    private val alarms = checkNotNull(appContext.getSystemService(AlarmManager::class.java))

    override fun arm(id: PermanentDeletionId, dueTimestampMillis: Long): Boolean {
        if (dueTimestampMillis <= 0L) return false
        cancel(id)
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    dueTimestampMillis,
                    pendingIntent(id),
                )
            } else {
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

    override fun cancel(id: PermanentDeletionId) {
        runCatching { alarms.cancel(pendingIntent(id)) }
    }

    private fun pendingIntent(id: PermanentDeletionId): PendingIntent {
        val intent = Intent(appContext, PermanentDeletionAlarmReceiver::class.java)
            .setAction(ACTION_PERMANENT_DELETION_DUE)
            .setData("aurorasms://permanent-deletion/${id.value}".toUri())
            .putExtra(PermanentDeletionAlarmReceiver.EXTRA_DELETION_ID, id.value)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ACTION_PERMANENT_DELETION_DUE =
            "org.aurorasms.app.action.PERMANENT_DELETION_DUE"
    }
}
