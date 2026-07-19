// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.core.state.SendDelayId

class SendDelayAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_SEND_DELAY_ID, -1L).takeIf { it > 0L } ?: return
        val pending = goAsync()
        val application = context.applicationContext as? AuroraSmsApplication ?: run {
            pending.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.onSendDelayAlarm(SendDelayId(id))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SEND_DELAY_ID = "org.aurorasms.app.extra.SEND_DELAY_ID"
    }
}
