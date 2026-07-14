// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class BenchmarkBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val resolver = context.contentResolver
    private val uri = Uri.parse("content://$AUTHORITY")

    @Test
    fun controlSurface_isSignatureProtectedAndOwnedByTarget() {
        val permission = context.packageManager.getPermissionInfo(CONTROL_PERMISSION, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(CONTROL_PERMISSION))

        val provider = requireNotNull(context.packageManager.resolveContentProvider(AUTHORITY, 0))
        assertEquals(TARGET_PACKAGE, provider.packageName)
        assertTrue(provider.exported)
        assertEquals(CONTROL_PERMISSION, provider.readPermission)
        assertEquals(CONTROL_PERMISSION, provider.writePermission)
    }

    @Test
    fun controlSurface_rejectsUnknownArgumentsAndArbitraryExtras() {
        assertFailure(resolver.call(uri, "unknown", null, null), "unknown_method")
        assertFailure(resolver.call(uri, "seed", "argument", null), "unexpected_argument")
        assertFailure(resolver.call(uri, "seed", null, Bundle()), "invalid_extras")
        assertFailure(
            resolver.call(
                uri,
                "seed",
                null,
                Bundle().apply {
                    putString("shape", "not_a_shape")
                    putLong("seed", Long.MIN_VALUE)
                },
            ),
            "invalid_shape",
        )
        assertFailure(
            resolver.call(
                uri,
                "cleanup",
                null,
                Bundle().apply { putString("path", "/data/local/tmp") },
            ),
            "invalid_extras",
        )
    }

    @Test
    fun standardProviderOperationsExposeNoDataPlane() {
        assertNull(resolver.query(uri, null, null, null, null))
        assertNull(resolver.insert(uri, ContentValues()))
        assertEquals(0, resolver.update(uri, ContentValues(), null, null))
        assertEquals(0, resolver.delete(uri, null, null))
    }

    private fun assertFailure(result: Bundle?, expected: String) {
        requireNotNull(result)
        assertFalse(result.getBoolean("success"))
        assertEquals(expected, result.getString("error"))
    }

    private companion object {
        const val AUTHORITY = "org.aurorasms.app.benchmark.fixture"
        const val CONTROL_PERMISSION = "org.aurorasms.app.permission.BENCHMARK_CONTROL"
    }
}
