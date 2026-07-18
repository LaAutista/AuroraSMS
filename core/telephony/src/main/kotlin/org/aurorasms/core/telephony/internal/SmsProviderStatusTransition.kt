// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.telephony.SmsProviderStatus

internal enum class SmsProviderStatusTransition {
    ADVANCE,
    KEEP_CURRENT,
}

internal fun smsProviderStatusTransition(
    current: SmsProviderStatus,
    requested: SmsProviderStatus,
): SmsProviderStatusTransition = if (requested.transitionRank > current.transitionRank) {
    SmsProviderStatusTransition.ADVANCE
} else {
    SmsProviderStatusTransition.KEEP_CURRENT
}

internal enum class ConditionalSmsStatusWriteResult {
    STALE,
    UNAVAILABLE,
    UPDATED,
}

internal enum class MonotonicSmsStatusUpdateResult {
    SUCCESS,
    UNAVAILABLE,
}

internal enum class OutgoingSmsArmResult {
    ARMED,
    UNAVAILABLE,
}

/**
 * Arms a staged outgoing row only when one exact conditional write succeeds.
 *
 * Unlike terminal status reconciliation, this transition must never accept an
 * already-applied write: doing so could authorize a second carrier submission.
 */
internal fun armOutgoingSmsProviderRow(
    conditionalWrite: () -> ConditionalSmsStatusWriteResult,
): OutgoingSmsArmResult = when (conditionalWrite()) {
    ConditionalSmsStatusWriteResult.UPDATED -> OutgoingSmsArmResult.ARMED
    ConditionalSmsStatusWriteResult.STALE,
    ConditionalSmsStatusWriteResult.UNAVAILABLE,
    -> OutgoingSmsArmResult.UNAVAILABLE
}

/**
 * Advances a provider row without allowing a stale callback to regress it.
 *
 * A conditional writer reports [ConditionalSmsStatusWriteResult.STALE] when another writer changed
 * the observed row before the write. The row is then read again, with a bounded number of write
 * attempts. A final read accepts a concurrently written equal or stronger state without rewriting.
 */
internal fun updateSmsStatusMonotonically(
    requested: SmsProviderStatus,
    maxWriteAttempts: Int,
    readCurrent: () -> SmsProviderStatus?,
    conditionalWrite: (
        expected: SmsProviderStatus,
        requested: SmsProviderStatus,
    ) -> ConditionalSmsStatusWriteResult,
): MonotonicSmsStatusUpdateResult {
    require(maxWriteAttempts > 0) { "At least one SMS status write attempt is required" }

    repeat(maxWriteAttempts) {
        val current = readCurrent() ?: return MonotonicSmsStatusUpdateResult.UNAVAILABLE
        if (smsProviderStatusTransition(current, requested) == SmsProviderStatusTransition.KEEP_CURRENT) {
            return MonotonicSmsStatusUpdateResult.SUCCESS
        }

        when (conditionalWrite(current, requested)) {
            ConditionalSmsStatusWriteResult.UPDATED -> return MonotonicSmsStatusUpdateResult.SUCCESS
            ConditionalSmsStatusWriteResult.UNAVAILABLE -> return MonotonicSmsStatusUpdateResult.UNAVAILABLE
            ConditionalSmsStatusWriteResult.STALE -> Unit
        }
    }

    val current = readCurrent() ?: return MonotonicSmsStatusUpdateResult.UNAVAILABLE
    return if (smsProviderStatusTransition(current, requested) == SmsProviderStatusTransition.KEEP_CURRENT) {
        MonotonicSmsStatusUpdateResult.SUCCESS
    } else {
        MonotonicSmsStatusUpdateResult.UNAVAILABLE
    }
}

private val SmsProviderStatus.transitionRank: Int
    get() = when (this) {
        SmsProviderStatus.PENDING -> 0
        SmsProviderStatus.COMPLETE -> 1
        SmsProviderStatus.DELIVERY_FAILED -> 2
        SmsProviderStatus.FAILED -> 3
    }
