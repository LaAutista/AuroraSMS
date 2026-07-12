// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class DefaultSmsRoleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
        if (!intent.hasExtra(Telephony.Sms.Intents.EXTRA_IS_DEFAULT_SMS_APP)) return
        val isDefault = intent.getBooleanExtra(Telephony.Sms.Intents.EXTRA_IS_DEFAULT_SMS_APP, false)
        dispatchAsync(context) { it.onDefaultSmsRoleChanged(isDefault) }
    }
}
