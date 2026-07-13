// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.strictmode

import android.os.Build
import android.os.StrictMode
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal object BuildVariantStrictMode {
    private val listenerExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "aurora-strictmode-sanitizer")
        }
    }

    fun install() {
        if (Build.VERSION.SDK_INT < 28) return
        installWithSanitizedListener()
    }

    @RequiresApi(28)
    private fun installWithSanitizedListener() {
        val listener = StrictMode.OnThreadViolationListener { violation ->
            StrictModeViolationLedger.record(violation.javaClass.simpleName)
        }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyListener(listenerExecutor, listener)
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .penaltyListener(listenerExecutor) { violation ->
                    StrictModeViolationLedger.record(violation.javaClass.simpleName)
                }
                .build(),
        )
    }
}
