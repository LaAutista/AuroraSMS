// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BenchmarkShellPreflightTest {
    @Test
    fun standardShellCanControlTargetWithoutChangingEligibility() {
        FixtureController.requireMessagingEligibility()
        MacrobenchmarkScope(
            packageName = TARGET_PACKAGE,
            launchWithClearTask = true,
        ).killProcess()
        FixtureController.requireMessagingEligibility()
    }
}
