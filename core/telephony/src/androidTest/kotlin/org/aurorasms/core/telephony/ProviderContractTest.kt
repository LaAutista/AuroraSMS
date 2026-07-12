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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun malformedRawProjectionRetainsAForwardProgressCursorOnDeviceRuntime() {
        val page = buildProviderPageFromRaw(
            request = ProviderPageRequest(limit = 2),
            rawRows = listOf(3L, 2L, 1L),
            cursorFor = { id -> ProviderPageCursor(timestampMillis = id * 1_000L, providerRowId = id) },
            project = { id -> id.takeIf { it == 1L } },
        )

        assertEquals(listOf(1L), page.items)
        assertEquals(ProviderPageCursor(timestampMillis = 1_000L, providerRowId = 1L), page.next)
        assertFalse(page.exhausted)
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
