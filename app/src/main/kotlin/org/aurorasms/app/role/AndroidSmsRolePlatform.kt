// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.role

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony

class AndroidSmsRolePlatform(
    private val context: Context,
    private val launchRoleRequest: (Intent) -> Unit,
) : SmsRolePlatform {
    override fun snapshot(): SmsRoleSnapshot {
        val packageManager = context.packageManager
        val telephonyCapable = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val messagingCapable = Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        val subscriptionCapable = Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)

        val roleManager = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }
        val roleAvailable = if (Build.VERSION.SDK_INT >= 29) {
            roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true
        } else {
            telephonyCapable
        }
        val roleHeld = if (Build.VERSION.SDK_INT >= 29) {
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

        return SmsRoleSnapshot(
            telephonyCapable = telephonyCapable,
            messagingCapable = messagingCapable,
            subscriptionCapable = subscriptionCapable,
            roleAvailable = roleAvailable,
            roleHeld = roleHeld,
        )
    }

    override fun requestRole(): RoleRequestStart {
        val current = snapshot()
        if (current.roleHeld) return RoleRequestStart.ALREADY_HELD
        if (!current.telephonyCapable || !current.messagingCapable || !current.roleAvailable) {
            return RoleRequestStart.UNAVAILABLE
        }

        val intent = if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = context.getSystemService(RoleManager::class.java)
                ?: return RoleRequestStart.UNAVAILABLE
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
                context.packageName,
            )
        }

        return try {
            launchRoleRequest(intent)
            RoleRequestStart.STARTED
        } catch (_: ActivityNotFoundException) {
            RoleRequestStart.UNAVAILABLE
        } catch (_: SecurityException) {
            RoleRequestStart.UNAVAILABLE
        }
    }
}
