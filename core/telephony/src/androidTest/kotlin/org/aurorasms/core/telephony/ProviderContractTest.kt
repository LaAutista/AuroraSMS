// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.telephony.internal.AndroidMmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidSmsProviderDataSource
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContractTest {
    @Test
    fun providerReadsStopBeforeQueryWhenSmsPermissionIsDenied() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val denied = DeniedSmsContext(base)
        val heldRole = object : DefaultSmsRoleState {
            override fun isRoleAvailable() = true
            override fun isRoleHeld() = true
        }

        assertTrue(AndroidSmsProviderDataSource(denied, heldRole).count() is ProviderAccessResult.PermissionDenied)
        assertTrue(AndroidMmsProviderDataSource(denied, heldRole).count() is ProviderAccessResult.PermissionDenied)
    }

    private class DeniedSmsContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.READ_SMS) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkPermission(permission, pid, uid)
            }
    }
}
