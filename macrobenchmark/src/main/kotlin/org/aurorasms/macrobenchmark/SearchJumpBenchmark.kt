// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
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
class SearchJumpBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun searchWithoutCompilation() = measureSearch(CompilationMode.None())

    @Test
    fun searchWithBaselineProfile() = measureSearch(BASELINE_COMPILATION)

    @Test
    fun exactOldJumpWithoutCompilation() = measureJump(CompilationMode.None())

    @Test
    fun exactOldJumpWithBaselineProfile() = measureJump(BASELINE_COMPILATION)

    private fun measureSearch(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric(SEARCH_TRACE, TraceSectionMetric.Mode.First)),
            compilationMode = compilationMode,
            iterations = measurementIterations(),
            setupBlock = {
                killProcess()
                startInbox()
                openSearch()
            },
            measureBlock = { enterSearchQuery(query) },
        )
    }

    private fun measureJump(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric(EXACT_JUMP_TRACE, TraceSectionMetric.Mode.First)),
            compilationMode = compilationMode,
            iterations = measurementIterations(),
            setupBlock = {
                killProcess()
                startInbox()
                openSearch()
            },
            measureBlock = { openSearchHit(enterSearchQuery(query)) },
        )
    }

    companion object {
        private lateinit var query: String

        @JvmStatic
        @BeforeClass
        fun seedSearchCorpusOnce() {
            FixtureController.requireSyntheticIsolation()
            FixtureController.seed(FixtureShape.SEARCH_500K)
            query = FixtureController.oldestSearchToken()
        }
    }
}
