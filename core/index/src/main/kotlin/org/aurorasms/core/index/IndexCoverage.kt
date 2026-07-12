// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

enum class IndexRunState {
    NOT_STARTED,
    SCANNING,
    VERIFYING,
    COMPLETE,
    PAUSED,
    FAILED,
}

enum class IndexFailureCode {
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,
    NON_ADVANCING_CURSOR,
    UNKNOWN,
}

data class IndexCoverage(
    val generationId: Long?,
    val state: IndexRunState,
    /** Physical index rows currently searchable, including stale rows until verification. */
    val indexedMessageCount: Long,
    val smsExhausted: Boolean,
    val mmsExhausted: Boolean,
    val pendingChanges: Boolean,
    /** Rows durably processed by the latest generation, independent of current table size. */
    val generationCommittedCount: Long = 0L,
    /** SMS provider projections durably consumed at the generation checkpoint. */
    val smsCheckpointCommittedCount: Long = 0L,
    /** MMS provider projections durably consumed at the generation checkpoint. */
    val mmsCheckpointCommittedCount: Long = 0L,
    val failureCode: IndexFailureCode? = null,
) {
    init {
        require(generationId == null || generationId > 0L) { "Generation IDs must be positive" }
        require(indexedMessageCount >= 0L) { "Indexed message counts cannot be negative" }
        require(generationCommittedCount >= 0L) { "Generation committed counts cannot be negative" }
        require(smsCheckpointCommittedCount >= 0L) { "SMS checkpoint counts cannot be negative" }
        require(mmsCheckpointCommittedCount >= 0L) { "MMS checkpoint counts cannot be negative" }
        require(
            generationId != null ||
                (
                    generationCommittedCount == 0L &&
                        smsCheckpointCommittedCount == 0L &&
                        mmsCheckpointCommittedCount == 0L
                    )
        ) { "Coverage without a generation cannot carry committed progress" }
        require((state == IndexRunState.FAILED) == (failureCode != null)) {
            "Only failed coverage carries a failure code"
        }
    }

    val verifiedComplete: Boolean
        get() = state == IndexRunState.COMPLETE && smsExhausted && mmsExhausted && !pendingChanges

    override fun toString(): String =
        "IndexCoverage(state=$state, indexedMessageCount=$indexedMessageCount, " +
            "generationCommittedCount=$generationCommittedCount, " +
            "smsCheckpointCommittedCount=$smsCheckpointCommittedCount, " +
            "mmsCheckpointCommittedCount=$mmsCheckpointCommittedCount, " +
            "smsExhausted=$smsExhausted, mmsExhausted=$mmsExhausted, " +
            "pendingChanges=$pendingChanges, failureCode=$failureCode)"

    companion object {
        val NOT_STARTED: IndexCoverage = IndexCoverage(
            generationId = null,
            state = IndexRunState.NOT_STARTED,
            indexedMessageCount = 0L,
            smsExhausted = false,
            mmsExhausted = false,
            pendingChanges = false,
            generationCommittedCount = 0L,
            smsCheckpointCommittedCount = 0L,
            mmsCheckpointCommittedCount = 0L,
        )
    }
}
