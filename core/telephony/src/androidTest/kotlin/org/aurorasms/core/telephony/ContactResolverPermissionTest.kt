// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.internal.AndroidContactDiscovery
import org.aurorasms.core.telephony.internal.AndroidContactResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactResolverPermissionTest {
    @Test
    fun deniedContactsPermissionReturnsAddressOnlyRows() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = DeniedContactsContext(base)
        val address = ParticipantAddress("+15550102020")

        val result = AndroidContactResolver(context).resolve(listOf(address))

        assertEquals(1, result.size)
        assertEquals(address, result.single().address)
        assertEquals(address.value, result.single().displayNameOrAddress)
        assertNull(result.single().displayName)
        assertNull(result.single().photoUri)
    }

    @Test
    fun deniedContactsPermissionReturnsDiscoveryStateWithoutProviderRows() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = DeniedContactsContext(base)

        val result = AndroidContactDiscovery(context).discover(
            query = "synthetic",
            resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
        )

        assertEquals(ContactDiscoveryResult.PermissionDenied, result)
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
}
