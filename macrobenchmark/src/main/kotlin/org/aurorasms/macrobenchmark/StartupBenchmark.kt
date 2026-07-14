// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun warmInboxWithoutCompilation() = measure(CompilationMode.None())

    @Test
    fun warmInboxWithBaselineProfile() = measure(BASELINE_COMPILATION)

    private fun measure(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.WARM,
            iterations = measurementIterations(),
            setupBlock = {
                startInbox()
                device.pressHome()
            },
            measureBlock = { startInbox() },
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedInboxOnce() {
            FixtureController.requireMessagingEligibility()
            FixtureController.seed(FixtureShape.INBOX_20K)
        }
    }
}
