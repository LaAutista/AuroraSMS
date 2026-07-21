// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class InboxFrameBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun inboxFlingAtTwentyThousandThreads() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = if (fullPerformanceEvidence()) {
                listOf(FrameTimingMetric())
            } else {
                rendererIndependentReachabilityMetrics()
            },
            compilationMode = BASELINE_COMPILATION,
            iterations = frameIterations(),
            setupBlock = {
                killProcess()
                startInbox()
            },
            measureBlock = { flingForFiveSeconds(towardOlder = true) },
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedInboxOnce() {
            FixtureController.requireSyntheticIsolation()
            FixtureController.seed(FixtureShape.INBOX_20K)
        }
    }
}
