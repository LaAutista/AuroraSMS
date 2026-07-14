// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class ConversationFrameBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun threadOpenWithoutCompilation() = measureThreadOpen(CompilationMode.None())

    @Test
    fun threadOpenWithBaselineProfile() = measureThreadOpen(BASELINE_COMPILATION)

    @Test
    fun threadFlingAndPrependAtTwoHundredFiftyThousandMessages() {
        FixtureController.seed(FixtureShape.THREAD_250K)
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = BASELINE_COMPILATION,
            iterations = frameIterations(),
            setupBlock = {
                killProcess()
                startInbox()
                openFirstConversation()
            },
            measureBlock = {
                prependOlderMessages()
                flingForFiveSeconds(towardOlder = false)
            },
        )
    }

    private fun measureThreadOpen(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                TraceSectionMetric(THREAD_OPEN_TRACE, TraceSectionMetric.Mode.First),
                FrameTimingMetric(),
            ),
            compilationMode = compilationMode,
            iterations = measurementIterations(),
            setupBlock = {
                killProcess()
                startInbox()
            },
            measureBlock = { openFirstConversation() },
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedThreadOnce() {
            FixtureController.requireMessagingEligibility()
            FixtureController.seed(FixtureShape.THREAD_250K)
        }
    }
}
