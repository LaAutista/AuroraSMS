// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.aurorasms.core.telephony.internal.AndroidContactChangeObserver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactChangeObserverTest {
    @Test
    fun deniedPermission_neverRegistersOrInvokesMetadataCallback() {
        val callbackCount = AtomicInteger(0)
        val context = DeniedContactsContext(ApplicationProvider.getApplicationContext())
        val observer = AndroidContactChangeObserver(context) { callbackCount.incrementAndGet() }

        assertFalse(observer.start())
        observer.close()

        assertEquals(0, callbackCount.get())
    }
}

private class DeniedContactsContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission == Manifest.permission.READ_CONTACTS) {
            PackageManager.PERMISSION_DENIED
        } else {
            super.checkPermission(permission, pid, uid)
        }
}
