// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.internal.AndroidMmsAttachmentRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmsAttachmentRepositoryTest {
    @Test
    fun roleAndPermissionGates_precedeEveryProviderRead() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val mmsId = ProviderMessageId(ProviderKind.MMS, 1L)
        val attachmentId = MmsAttachmentId(mmsId, 2L)
        val roleDenied = AndroidMmsAttachmentRepository(base, FixedRoleState(held = false))

        assertEquals(MmsAttachmentListResult.RoleRequired, roleDenied.listStaticImages(mmsId))
        assertEquals(
            MmsAttachmentReadResult.RoleRequired,
            roleDenied.read(attachmentId) { error("Reader must not run") },
        )

        val permissionDenied = AndroidMmsAttachmentRepository(
            DeniedReadSmsContext(base),
            FixedRoleState(held = true),
        )
        assertEquals(MmsAttachmentListResult.PermissionDenied, permissionDenied.listStaticImages(mmsId))
        assertEquals(
            MmsAttachmentReadResult.PermissionDenied,
            permissionDenied.read(attachmentId) { error("Reader must not run") },
        )
    }

    @Test
    fun nonMmsListIdentity_isRejectedWithoutProviderAccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AndroidMmsAttachmentRepository(context, FixedRoleState(held = true))

        assertEquals(
            MmsAttachmentListResult.InvalidMessageKind,
            repository.listStaticImages(ProviderMessageId(ProviderKind.SMS, 1L)),
        )
    }
}

private class FixedRoleState(
    private val held: Boolean,
) : DefaultSmsRoleState {
    override fun isRoleAvailable(): Boolean = true

    override fun isRoleHeld(): Boolean = held
}

private class DeniedReadSmsContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission == Manifest.permission.READ_SMS) {
            PackageManager.PERMISSION_DENIED
        } else {
            super.checkPermission(permission, pid, uid)
        }
}
