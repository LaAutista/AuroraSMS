// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import org.aurorasms.core.telephony.DefaultSmsRoleState

class AndroidDefaultSmsRoleState(context: Context) : DefaultSmsRoleState {
    private val appContext = context.applicationContext

    override fun isRoleAvailable(): Boolean {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        ) {
            return false
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                appContext.getSystemService(RoleManager::class.java)
                    ?.isRoleAvailable(RoleManager.ROLE_SMS) == true
            }.getOrDefault(false)
        } else {
            true
        }
    }

    override fun isRoleHeld(): Boolean {
        if (!isRoleAvailable()) return false
        return if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                appContext.getSystemService(RoleManager::class.java)
                    ?.isRoleHeld(RoleManager.ROLE_SMS) == true
            }.getOrDefault(false)
        } else {
            runCatching {
                Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
            }.getOrDefault(false)
        }
    }
}
