// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class ExternalProviderChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_EXTERNAL_PROVIDER_CHANGE) return
        dispatchAsync(context) { it.onExternalProviderChanged() }
    }
}
