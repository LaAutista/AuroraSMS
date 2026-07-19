// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

/** Content-free, exact authority for deleting one provider message. */
data class ProviderMessageDeletionTarget(
    val providerMessageId: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val syncFingerprint: MessageSyncFingerprint,
) {
    override fun toString(): String = "ProviderMessageDeletionTarget(REDACTED)"
}

/**
 * Bounded provider metadata captured when a whole-thread deletion is confirmed.
 *
 * Counts plus monotonically allocated provider row IDs detect ordinary inserts
 * and removals during the Undo window without retaining message content.
 */
data class ProviderThreadDeletionSnapshot(
    val providerThreadId: ProviderThreadId,
    val smsCount: Long,
    val latestSmsId: ProviderMessageId?,
    val mmsCount: Long,
    val latestMmsId: ProviderMessageId?,
) {
    init {
        require(smsCount >= 0L && mmsCount >= 0L)
        require(smsCount <= Long.MAX_VALUE - mmsCount)
        require((smsCount == 0L) == (latestSmsId == null))
        require((mmsCount == 0L) == (latestMmsId == null))
        require(latestSmsId == null || latestSmsId.kind == ProviderKind.SMS)
        require(latestMmsId == null || latestMmsId.kind == ProviderKind.MMS)
    }

    val messageCount: Long
        get() = smsCount + mmsCount

    override fun toString(): String =
        "ProviderThreadDeletionSnapshot(messageCount=$messageCount, REDACTED)"
}

enum class ProviderDeletionCommitOutcome {
    DELETED,
    ALREADY_ABSENT,
    TARGET_CHANGED,
}

interface PermanentDeletionProvider {
    suspend fun inspectMessage(
        providerMessageId: ProviderMessageId,
    ): ProviderAccessResult<ProviderMessageDeletionTarget?>

    suspend fun inspectThread(
        providerThreadId: ProviderThreadId,
    ): ProviderAccessResult<ProviderThreadDeletionSnapshot>

    suspend fun deleteMessage(
        expected: ProviderMessageDeletionTarget,
    ): ProviderAccessResult<ProviderDeletionCommitOutcome>

    suspend fun deleteThread(
        expected: ProviderThreadDeletionSnapshot,
    ): ProviderAccessResult<ProviderDeletionCommitOutcome>
}
