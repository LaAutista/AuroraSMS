// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugSmsSnapshotProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Suppress("DEPRECATION")
    fun debugManifestExportsOnlyTheDumpProtectedAuthority() {
        val provider = requireNotNull(
            context.packageManager.resolveContentProvider(
                DebugSmsSnapshotProvider.AUTHORITY,
                PackageManager.GET_META_DATA,
            ),
        )

        assertEquals(DebugSmsSnapshotProvider::class.java.name, provider.name)
        assertEquals(DebugSmsSnapshotProvider.AUTHORITY, provider.authority)
        assertTrue(provider.exported)
        assertEquals(Manifest.permission.DUMP, provider.readPermission)
        assertEquals(Manifest.permission.DUMP, provider.writePermission)
    }

    @Test
    fun appUidCannotQueryTheShellProbe() {
        assertThrows(SecurityException::class.java) {
            context.contentResolver.query(
                DebugSmsSnapshotProvider.CONTENT_URI,
                DebugSmsSnapshotProvider.SNAPSHOT_COLUMNS.toTypedArray(),
                null,
                null,
                null,
            )?.close()
        }
    }

    @Test
    fun shellCanReadOnlyTheFixedSnapshotSchema() {
        val packageName = context.packageName
        val grantOutput = shell("pm grant $packageName ${Manifest.permission.READ_SMS}")
        assertFalse(grantOutput, grantOutput.contains("Exception", ignoreCase = true))

        val output = shell(
            "content query --uri ${DebugSmsSnapshotProvider.CONTENT_URI} " +
                "--projection _id:thread_id:type",
        )

        assertFalse(output, output.contains("Exception", ignoreCase = true))
        assertFalse(output, output.contains("address=", ignoreCase = true))
        assertFalse(output, output.contains("body=", ignoreCase = true))
        assertFalse(output, output.contains("date=", ignoreCase = true))

        assertEquals(
            DebugSmsSnapshotProvider.SNAPSHOT_COLUMNS,
            DebugSmsSnapshotProvider.snapshotColumns(null),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DebugSmsSnapshotProvider.snapshotColumns(arrayOf("body"))
        }

        val provider = DebugSmsSnapshotProvider()
        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(DebugSmsSnapshotProvider.CONTENT_URI, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(DebugSmsSnapshotProvider.CONTENT_URI, null, null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(DebugSmsSnapshotProvider.CONTENT_URI, null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.call("unsupported", null, null)
        }
        assertThrows(java.io.FileNotFoundException::class.java) {
            provider.openFile(DebugSmsSnapshotProvider.CONTENT_URI, "r")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }
}
