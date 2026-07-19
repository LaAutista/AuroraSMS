// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import org.aurorasms.app.receiver.ScheduledSmsAlarmReceiver
import org.aurorasms.core.state.ScheduledSmsId

internal enum class ScheduledAlarmArmResult { EXACT, INEXACT, FAILED }

internal interface ScheduledSmsAlarmDriver {
    fun arm(id: ScheduledSmsId, dueTimestampMillis: Long): ScheduledAlarmArmResult
    fun cancel(id: ScheduledSmsId)
}

internal class AndroidScheduledSmsAlarmDriver(context: Context) : ScheduledSmsAlarmDriver {
    private val appContext = context.applicationContext
    private val alarms = checkNotNull(appContext.getSystemService(AlarmManager::class.java))

    override fun arm(id: ScheduledSmsId, dueTimestampMillis: Long): ScheduledAlarmArmResult {
        if (dueTimestampMillis <= 0L) return ScheduledAlarmArmResult.FAILED
        cancel(id)
        if (runCatching(::canScheduleExact).getOrDefault(false)) {
            try {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    dueTimestampMillis,
                    pendingIntent(id, ACTION_EXACT),
                )
                // A distinct inexact safety alarm survives later exact-access revocation.
                if (dueTimestampMillis <= Long.MAX_VALUE - EXACT_FALLBACK_DELAY_MILLIS) {
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        dueTimestampMillis + EXACT_FALLBACK_DELAY_MILLIS,
                        pendingIntent(id, ACTION_INEXACT),
                    )
                }
                return ScheduledAlarmArmResult.EXACT
            } catch (_: SecurityException) {
                alarms.cancel(pendingIntent(id, ACTION_EXACT))
            } catch (_: RuntimeException) {
                alarms.cancel(pendingIntent(id, ACTION_EXACT))
            }
        }
        return try {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                dueTimestampMillis,
                pendingIntent(id, ACTION_INEXACT),
            )
            ScheduledAlarmArmResult.INEXACT
        } catch (_: SecurityException) {
            ScheduledAlarmArmResult.FAILED
        } catch (_: RuntimeException) {
            ScheduledAlarmArmResult.FAILED
        }
    }

    override fun cancel(id: ScheduledSmsId) {
        runCatching { alarms.cancel(pendingIntent(id, ACTION_EXACT)) }
        runCatching { alarms.cancel(pendingIntent(id, ACTION_INEXACT)) }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun pendingIntent(id: ScheduledSmsId, action: String): PendingIntent {
        val intent = Intent(appContext, ScheduledSmsAlarmReceiver::class.java)
            .setAction(action)
            .setData(
                "aurorasms://scheduled-sms/${id.value}/${action.substringAfterLast('.')}".toUri(),
            )
            .putExtra(ScheduledSmsAlarmReceiver.EXTRA_SCHEDULE_ID, id.value)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val ACTION_EXACT = "org.aurorasms.app.action.SCHEDULED_SMS_EXACT"
        private const val ACTION_INEXACT = "org.aurorasms.app.action.SCHEDULED_SMS_INEXACT"
        private const val EXACT_FALLBACK_DELAY_MILLIS = 5L * 60L * 1_000L
    }
}
