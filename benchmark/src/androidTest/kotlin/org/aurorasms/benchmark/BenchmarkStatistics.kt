// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.benchmark

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlin.math.ceil

internal object BenchmarkStatistics {
    suspend fun measure(
        warmupCount: Int,
        sampleCount: Int,
        operation: suspend () -> Unit,
    ): TimingSummary {
        require(warmupCount >= 0) { "Warmup counts cannot be negative" }
        require(sampleCount > 0) { "At least one measured sample is required" }
        repeat(warmupCount) { operation() }
        val samples = LongArray(sampleCount) {
            val started = SystemClock.elapsedRealtimeNanos()
            operation()
            (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
        }
        return summarize(warmupCount, samples)
    }

    fun summarize(
        warmupCount: Int,
        samplesNanos: LongArray,
    ): TimingSummary {
        require(warmupCount >= 0) { "Warmup counts cannot be negative" }
        require(samplesNanos.isNotEmpty()) { "At least one measured sample is required" }
        require(samplesNanos.all { it > 0L }) { "Measured durations must be positive" }
        val sorted = samplesNanos.sortedArray()
        return TimingSummary(
            warmupCount = warmupCount,
            sampleCount = sorted.size,
            p50Nanos = nearestRank(sorted, 50),
            p95Nanos = nearestRank(sorted, 95),
        )
    }

    private fun nearestRank(sorted: LongArray, percentile: Int): Long {
        require(percentile in 1..100)
        val rank = ceil((percentile / 100.0) * sorted.size).toInt().coerceAtLeast(1)
        return sorted[rank - 1]
    }
}

internal data class TimingSummary(
    val warmupCount: Int,
    val sampleCount: Int,
    val p50Nanos: Long,
    val p95Nanos: Long,
) {
    val p50Millis: Double
        get() = p50Nanos / NANOS_PER_MILLISECOND
    val p95Millis: Double
        get() = p95Nanos / NANOS_PER_MILLISECOND

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

internal data class BenchmarkEvidence(
    val operation: BenchmarkOperation,
    val shape: FixtureShape,
    val timing: TimingSummary,
    val databaseSizeBytes: Long,
    val commit: String,
) {
    init {
        require(databaseSizeBytes >= 0L) { "Database sizes cannot be negative" }
        require(commit == UNSPECIFIED_COMMIT || COMMIT_PATTERN.matches(commit)) {
            "Only a redacted hexadecimal commit may enter benchmark evidence"
        }
    }

    fun toRedactedJson(): String = buildString {
        append('{')
        appendJson("schema", "aurora-index-benchmark-v1")
        append(',')
        appendJson("variant", "debugAndroidTest")
        append(',')
        appendJson("commit", commit)
        append(',')
        appendJson("api", Build.VERSION.SDK_INT)
        append(',')
        appendJson("seed", DeterministicIndexFixtures.FIXED_SEED.toString())
        append(',')
        appendJson("shape", shape.argumentValue)
        append(',')
        appendJson("messages", shape.messageCount)
        append(',')
        appendJson("threads", shape.threadCount)
        append(',')
        appendJson("warmups", timing.warmupCount)
        append(',')
        appendJson("samples", timing.sampleCount)
        append(',')
        appendJsonDecimal("p50_ms", timing.p50Millis)
        append(',')
        appendJsonDecimal("p95_ms", timing.p95Millis)
        append(',')
        appendJson("database_bytes", databaseSizeBytes)
        append(',')
        appendJson("operation", operation.reportName)
        append('}')
    }

    private fun StringBuilder.appendJson(key: String, value: String) {
        append('"').append(key).append("\":\"").append(value).append('"')
    }

    private fun StringBuilder.appendJson(key: String, value: Number) {
        append('"').append(key).append("\":").append(value)
    }

    private fun StringBuilder.appendJsonDecimal(key: String, value: Double) {
        append('"').append(key).append("\":").append(String.format(Locale.ROOT, "%.3f", value))
    }

    companion object {
        const val UNSPECIFIED_COMMIT: String = "unspecified"
        private val COMMIT_PATTERN = Regex("[0-9a-f]{7,64}")

        fun sanitizedCommit(raw: String?): String = raw
            ?.lowercase(Locale.ROOT)
            ?.takeIf(COMMIT_PATTERN::matches)
            ?: UNSPECIFIED_COMMIT
    }
}

internal enum class BenchmarkOperation(val reportName: String) {
    BUILD("build"),
    REBUILD("rebuild"),
    COMMITTED_BATCH_500("committed_batch_500"),
    SEARCH_GLOBAL_COMMON("search_global_common"),
    SEARCH_GLOBAL_NO_HIT("search_global_no_hit"),
    SEARCH_THREAD_COMMON("search_thread_common"),
    KEYSET_FORWARD("keyset_forward"),
    KEYSET_BACKWARD("keyset_backward"),
    ANCHOR_NEWEST("anchor_newest"),
    ANCHOR_MIDDLE("anchor_middle"),
    ANCHOR_OLDEST("anchor_oldest"),
    RECONCILE_DELETIONS("reconcile_deletions"),
    REOPEN_CHECKPOINT("reopen_checkpoint"),
}

internal class RedactedEvidenceReporter {
    private var sequence: Int = 0

    fun report(evidence: BenchmarkEvidence) {
        val json = evidence.toRedactedJson()
        check(!FORBIDDEN_REPORT_PATTERN.containsMatchIn(json)) {
            "Benchmark evidence crossed the redaction boundary"
        }
        InstrumentationRegistry.getInstrumentation().addResults(
            Bundle().apply {
                putString("aurora_benchmark_${sequence++}", json)
            },
        )
    }

    companion object {
        // Evidence keys and aggregate values are allowlisted above; these reject accidental content fields.
        private val FORBIDDEN_REPORT_PATTERN = Regex(
            "(?i)(message_body|sender_address|provider_id|device_id|serial|query_text|fingerprint)",
        )
    }
}
