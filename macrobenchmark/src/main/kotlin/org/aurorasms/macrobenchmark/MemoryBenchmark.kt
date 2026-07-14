// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun fixedTextBrowsePss() {
        val samples = LongArray(memoryIterations())
        samples.indices.forEach { index ->
            FixtureController.forceStopTarget()
            device.executeShellCommand("am start -W -n $TARGET_PACKAGE/.MainActivity")
            checkNotNull(
                device.wait(Until.findObject(By.res(INBOX_SEARCH_ACTION_TAG)), UI_TIMEOUT_MILLIS),
            )
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 4 / 5,
                device.displayWidth / 2,
                device.displayHeight / 5,
                20,
            )
            device.waitForIdle()
            SystemClock.sleep(QUIESCENCE_MILLIS)
            samples[index] = parseTotalPssKiB(
                device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE"),
            )
        }
        val median = samples.sorted()[samples.size / 2]
        instrumentation.sendStatus(
            STATUS_EVIDENCE,
            Bundle().apply {
                putLong("auroraPssMedianKiB", median)
                putInt("auroraPssSampleCount", samples.size)
            },
        )
        check(median in 1..MAXIMUM_MEDIAN_PSS_KIB) {
            "Fixed synthetic browse exceeded the Phase 3 median PSS aim"
        }
    }

    private fun parseTotalPssKiB(output: String): Long {
        val match = TOTAL_PSS_REGEX.find(output)
        return checkNotNull(match?.groupValues?.get(1)?.toLongOrNull()) {
            "Sanitized total PSS was unavailable"
        }
    }

    companion object {
        private const val STATUS_EVIDENCE = 2
        private const val UI_TIMEOUT_MILLIS = 30_000L
        private const val QUIESCENCE_MILLIS = 5_000L
        private const val MAXIMUM_MEDIAN_PSS_KIB = 150L * 1_024L
        private val TOTAL_PSS_REGEX = Regex("TOTAL PSS:\\s+(\\d+)")

        @JvmStatic
        @BeforeClass
        fun seedInboxOnce() {
            FixtureController.requireMessagingEligibility()
            FixtureController.seed(FixtureShape.INBOX_20K)
        }
    }
}
