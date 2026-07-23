// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlin.math.abs
import org.aurorasms.core.testing.SyntheticIndexFixtures
import org.aurorasms.core.testing.SyntheticIndexRecord
import org.aurorasms.core.testing.SyntheticIndexShape

internal const val PRODUCTION_PACKAGE: String = "org.aurorasms.app"
internal const val TARGET_PACKAGE: String = "$PRODUCTION_PACKAGE.benchmark"
internal const val THREAD_OPEN_TRACE: String = "AuroraThreadOpen"
internal const val SEARCH_TRACE: String = "AuroraSearchResults"
internal const val EXACT_JUMP_TRACE: String = "AuroraExactJump"

internal val BASELINE_COMPILATION: CompilationMode = CompilationMode.Partial(
    baselineProfileMode = BaselineProfileMode.Require,
    warmupIterations = PERFORMANCE_WARMUP_ITERATIONS,
)

internal enum class FixtureShape(val wireValue: String) {
    INBOX_20K("inbox_20k"),
    THREAD_250K("thread_250k"),
    SEARCH_500K("search_500k"),
}

internal object FixtureController {
    private const val AUTHORITY = "org.aurorasms.app.benchmark.fixture"
    private const val CONTROL_PERMISSION =
        "org.aurorasms.app.benchmark.permission.BENCHMARK_CONTROL"
    private const val METHOD_SEED = "seed"
    private const val KEY_SHAPE = "shape"
    private const val KEY_SEED = "seed"
    private const val KEY_SUCCESS = "success"
    private const val KEY_MESSAGE_COUNT = "message_count"
    private val uri = Uri.parse("content://$AUTHORITY")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context

    fun requireSyntheticIsolation() {
        check(TARGET_PACKAGE != PRODUCTION_PACKAGE) {
            "Benchmark target must not share the production application ID"
        }
        val forbiddenMessagingPermissions = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_PHONE_STATE,
        )
        check(forbiddenMessagingPermissions.all { permission ->
            context.packageManager.checkPermission(permission, TARGET_PACKAGE) ==
                PackageManager.PERMISSION_DENIED
        }) {
            "Benchmark target must not hold messaging authority"
        }
        check(Telephony.Sms.getDefaultSmsPackage(context) != TARGET_PACKAGE) {
            "Benchmark target must not hold the default SMS role"
        }
        check(context.checkSelfPermission(CONTROL_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Benchmark control permission was not granted to the same-signed test APK"
        }
    }

    fun seed(shape: FixtureShape) {
        forceStopTarget()
        val extras = Bundle().apply {
            putString(KEY_SHAPE, shape.wireValue)
            putLong(KEY_SEED, SyntheticIndexFixtures.FIXED_SEED)
        }
        val result = try {
            checkNotNull(context.contentResolver.call(uri, METHOD_SEED, null, extras))
        } finally {
            forceStopTarget()
        }
        check(result.getBoolean(KEY_SUCCESS)) { "Synthetic benchmark fixture seed failed" }
        val expectedCount = when (shape) {
            FixtureShape.INBOX_20K -> 20_000
            FixtureShape.THREAD_250K -> 250_000
            FixtureShape.SEARCH_500K -> 500_000
        }
        check(result.getInt(KEY_MESSAGE_COUNT) == expectedCount) {
            "Synthetic benchmark fixture returned an unexpected count"
        }
    }

    fun forceStopTarget() {
        UiDevice.getInstance(instrumentation)
            .executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    fun oldestSearchToken(): String {
        val fixture = SyntheticIndexFixtures.fixture(SyntheticIndexShape.MESSAGES_500_THOUSAND)
        val record = fixture.recordAt(fixture.size - 1)
        val body = when (record) {
            is SyntheticIndexRecord.Sms -> record.providerMessage.body
            is SyntheticIndexRecord.Mms -> record.providerMessage.body.orEmpty()
        }
        return body.substringAfterLast("Record ").substringBefore('.').also { token ->
            check(token.isNotBlank() && token.all(Char::isLetterOrDigit))
        }
    }
}

internal fun MacrobenchmarkScope.startInbox() {
    startActivityAndWait()
    waitForTag(INBOX_ROW_TAG, INDEX_READY_TIMEOUT_MILLIS)
}

internal fun MacrobenchmarkScope.openFirstConversation() {
    clickVisibleClickableTag(INBOX_ROW_TAG)
    waitForThreadPresentationTrace()
}

internal fun MacrobenchmarkScope.openSearch() {
    waitForTag(INBOX_SEARCH_ACTION_TAG).click()
    waitForTag(SEARCH_FIELD_TAG)
}

internal fun MacrobenchmarkScope.enterSearchQuery(query: String): UiObject2 {
    val field = waitForTag(SEARCH_FIELD_TAG)
    field.text = query
    return waitForTag(SEARCH_HIT_TAG)
}

internal fun MacrobenchmarkScope.openSearchHit(hit: UiObject2) {
    hit.click()
    waitForThreadPresentationTrace()
}

private fun MacrobenchmarkScope.waitForThreadPresentationTrace() {
    waitForTag(THREAD_LIST_TAG)
    // The list semantics can become observable one frame before ThreadRoute's
    // post-placement effect closes the async presentation trace. Keep the
    // measurement open long enough for that exact trace boundary to close.
    SystemClock.sleep(PRESENTATION_TRACE_COMPLETION_GRACE_MILLIS)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.scrollInboxOnce() {
    device.swipe(
        device.displayWidth / 2,
        device.displayHeight * 4 / 5,
        device.displayWidth / 2,
        device.displayHeight / 5,
        20,
    )
    device.waitForIdle()
    SystemClock.sleep(SCROLL_SETTLE_MILLIS)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.prependOlderMessages() {
    repeat(3) {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight / 3,
            device.displayWidth / 2,
            device.displayHeight * 4 / 5,
            20,
        )
        device.waitForIdle()
    }
}

internal fun MacrobenchmarkScope.flingForFiveSeconds(towardOlder: Boolean) {
    val started = SystemClock.elapsedRealtime()
    while (SystemClock.elapsedRealtime() - started < FIVE_SECONDS_MILLIS) {
        val startY = if (towardOlder) device.displayHeight * 4 / 5 else device.displayHeight / 4
        val endY = if (towardOlder) device.displayHeight / 5 else device.displayHeight * 4 / 5
        device.swipe(device.displayWidth / 2, startY, device.displayWidth / 2, endY, 8)
    }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.returnToInbox() {
    repeat(MAXIMUM_BACK_STEPS) {
        device.pressBack()
        val inboxRow = device.wait(
            Until.findObject(By.res(INBOX_ROW_TAG)),
            BACK_STEP_TIMEOUT_MILLIS,
        )
        if (inboxRow != null) return
    }
    error("Inbox did not become visible after bounded back navigation")
}

internal fun MacrobenchmarkScope.waitForTag(
    tag: String,
    timeoutMillis: Long = UI_TIMEOUT_MILLIS,
): UiObject2 = checkNotNull(
    device.wait(Until.findObject(By.res(tag)), timeoutMillis),
) { "Expected synthetic journey surface was not visible: $tag" }

private fun MacrobenchmarkScope.clickVisibleClickableTag(
    tag: String,
    timeoutMillis: Long = UI_TIMEOUT_MILLIS,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        val centerY = device.displayHeight / 2
        val match = device.findObjects(By.res(tag))
            .mapNotNull { candidate ->
                try {
                    val bounds = candidate.visibleBounds
                    (candidate to bounds).takeIf {
                        candidate.isClickable &&
                            bounds.width() > 0 &&
                            bounds.height() > 0 &&
                            bounds.centerX() in 0 until device.displayWidth &&
                            bounds.centerY() in 0 until device.displayHeight
                    }
                } catch (_: StaleObjectException) {
                    null
                }
            }
            .minByOrNull { (_, bounds) -> abs(bounds.centerY() - centerY) }
        if (match != null) {
            try {
                match.first.click()
                return
            } catch (_: StaleObjectException) {
                // Compose recycled this row between discovery and the accessibility action.
            }
        }
        SystemClock.sleep(UI_POLL_INTERVAL_MILLIS)
    }
    error("Expected visible clickable synthetic journey surface was not visible: $tag")
}

internal fun measurementIterations(): Int =
    if (fullPerformanceEvidence()) 30 else 3

internal fun frameIterations(): Int =
    if (fullPerformanceEvidence()) 10 else 1

internal fun memoryIterations(): Int =
    if (fullPerformanceEvidence()) 10 else 2

internal fun fullPerformanceEvidence(): Boolean =
    InstrumentationRegistry.getArguments().getString(ARGUMENT_FULL) == "true"

@OptIn(ExperimentalMetricApi::class)
internal fun rendererIndependentReachabilityMetrics(): List<Metric> = listOf(
    MemoryUsageMetric(
        mode = MemoryUsageMetric.Mode.Last,
        subMetrics = listOf(MemoryUsageMetric.SubMetric.RssAnon),
    ),
)

private const val ARGUMENT_FULL = "auroraBenchmarkFull"
private const val PERFORMANCE_WARMUP_ITERATIONS = 5
private const val UI_TIMEOUT_MILLIS = 30_000L
private const val UI_POLL_INTERVAL_MILLIS = 100L
private const val SCROLL_SETTLE_MILLIS = 500L
private const val PRESENTATION_TRACE_COMPLETION_GRACE_MILLIS = 100L
private const val INDEX_READY_TIMEOUT_MILLIS = 120_000L
private const val FIVE_SECONDS_MILLIS = 5_000L
private const val BACK_STEP_TIMEOUT_MILLIS = 2_000L
private const val MAXIMUM_BACK_STEPS = 4
private const val INBOX_ROW_TAG = "aurora-inbox-row"
internal const val INBOX_SEARCH_ACTION_TAG = "aurora-inbox-search-action"
private const val SEARCH_FIELD_TAG = "aurora-search-field"
private const val SEARCH_HIT_TAG = "aurora-search-hit"
private const val THREAD_LIST_TAG = "aurora-thread-list"
