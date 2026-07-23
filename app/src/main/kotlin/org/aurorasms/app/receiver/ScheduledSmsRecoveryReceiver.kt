// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.message.ScheduledSmsRecoveryReason

class ScheduledSmsRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ScheduledSmsRecoveryReason.BOOT_COMPLETED
            Intent.ACTION_TIME_CHANGED -> ScheduledSmsRecoveryReason.WALL_CLOCK_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ScheduledSmsRecoveryReason.TIMEZONE_CHANGED
            Intent.ACTION_MY_PACKAGE_REPLACED -> ScheduledSmsRecoveryReason.PACKAGE_REPLACED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                ScheduledSmsRecoveryReason.EXACT_ACCESS_CHANGED
            else -> return
        }
        val pending = goAsync()
        val application = context.applicationContext as? AuroraSmsApplication ?: run {
            pending.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.onScheduledSmsRecovery(reason)
            } finally {
                pending.finish()
            }
        }
    }
}
