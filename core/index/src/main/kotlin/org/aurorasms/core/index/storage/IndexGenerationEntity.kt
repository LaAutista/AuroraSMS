// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "index_generations",
    indices = [Index(value = ["state"]), Index(value = ["updated_at_ms"])],
)
data class IndexGenerationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "generation_id")
    val generationId: Long = 0L,
    val state: Int,
    @ColumnInfo(name = "started_at_ms")
    val startedAtMillis: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMillis: Long,
    @ColumnInfo(name = "completed_at_ms")
    val completedAtMillis: Long?,
    @ColumnInfo(name = "committed_count")
    val committedCount: Long,
    @ColumnInfo(name = "pending_changes")
    val pendingChanges: Boolean,
    @ColumnInfo(name = "failure_code")
    val failureCode: Int?,
    @ColumnInfo(name = "target_batch_size")
    val targetBatchSize: Int,
    @ColumnInfo(name = "signal_sequence")
    val signalSequence: Long = 0L,
) {
    init {
        require(generationId >= 0L) { "Generation IDs cannot be negative" }
        require(state in GenerationStateCode.SCANNING..GenerationStateCode.FAILED) {
            "Unknown index generation state"
        }
        require(startedAtMillis >= 0L && updatedAtMillis >= startedAtMillis) {
            "Generation timestamps are invalid"
        }
        require(completedAtMillis == null || completedAtMillis >= startedAtMillis) {
            "Completion cannot predate generation start"
        }
        require(committedCount >= 0L) { "Committed counts cannot be negative" }
        require(targetBatchSize in MINIMUM_INDEX_BATCH_SIZE..MAXIMUM_INDEX_BATCH_SIZE) {
            "Index batch size is outside the reviewed bound"
        }
        require(signalSequence >= 0L) { "Signal sequences cannot be negative" }
        require((state == GenerationStateCode.FAILED) == (failureCode != null)) {
            "Only failed generations carry a failure code"
        }
        require(failureCode == null || failureCode in 1..7) { "Unknown index failure code" }
        require((state == GenerationStateCode.COMPLETE) == (completedAtMillis != null)) {
            "Only complete generations carry a completion time"
        }
    }
}

internal object GenerationStateCode {
    const val SCANNING: Int = 1
    const val VERIFYING: Int = 2
    const val COMPLETE: Int = 3
    const val PAUSED: Int = 4
    const val FAILED: Int = 5
}

const val INITIAL_INDEX_BATCH_SIZE: Int = 500
const val MINIMUM_INDEX_BATCH_SIZE: Int = 100
const val MAXIMUM_INDEX_BATCH_SIZE: Int = 500
