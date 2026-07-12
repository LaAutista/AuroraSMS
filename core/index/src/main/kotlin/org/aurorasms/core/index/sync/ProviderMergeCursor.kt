// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CancellationException
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage

/**
 * Stages one bounded, globally ordered provider batch without retaining a
 * provider cursor beyond this call.
 *
 * A caller persists [ProviderMergeProgress.stagedCursor] only in the same
 * transaction as [ProviderMergeBatch.messages]. If the process dies first,
 * prefetched but unconsumed rows are intentionally read again.
 */
internal class ProviderMergeCursor(
    private val smsSource: SmsProviderDataSource,
    private val mmsSource: MmsProviderDataSource,
    private val pageSize: Int = ProviderPageRequest.MAX_PROVIDER_PAGE_SIZE,
    private val maxBatchSize: Int = MAX_BATCH_SIZE,
    private val emptyPageBudgetPerSource: Int = DEFAULT_EMPTY_PAGE_BUDGET,
) {
    init {
        require(pageSize in 1..ProviderPageRequest.MAX_PROVIDER_PAGE_SIZE) {
            "Provider merge page size is outside the bounded provider contract"
        }
        require(maxBatchSize in 1..MAX_BATCH_SIZE) {
            "Provider merge batch size is outside the bounded transaction contract"
        }
        require(emptyPageBudgetPerSource in 1..MAX_EMPTY_PAGE_BUDGET) {
            "Provider empty-page budget is outside the bounded merge contract"
        }
    }

    suspend fun stageBatch(
        smsCursor: ProviderPageCursor?,
        mmsCursor: ProviderPageCursor?,
        smsExhausted: Boolean = false,
        mmsExhausted: Boolean = false,
    ): ProviderMergeResult {
        val sms = SourceLookahead(
            source = ProviderMergeSource.SMS,
            expectedKind = ProviderKind.SMS,
            initialCursor = smsCursor,
            initiallyExhausted = smsExhausted,
            pageSize = pageSize,
            maxPageFetches = maxBatchSize + emptyPageBudgetPerSource + 1,
            emptyPageBudget = emptyPageBudgetPerSource,
            readPage = smsSource::readPage,
            cursorOf = SmsProviderMessage::providerCursor,
        )
        val mms = SourceLookahead(
            source = ProviderMergeSource.MMS,
            expectedKind = ProviderKind.MMS,
            initialCursor = mmsCursor,
            initiallyExhausted = mmsExhausted,
            pageSize = pageSize,
            maxPageFetches = maxBatchSize + emptyPageBudgetPerSource + 1,
            emptyPageBudget = emptyPageBudgetPerSource,
            readPage = mmsSource::readPage,
            cursorOf = MmsProviderMessage::providerCursor,
        )
        val staged = ArrayList<ProviderMergeItem>(maxBatchSize)

        while (staged.size < maxBatchSize) {
            // Both lookaheads must be current before choosing either head. In
            // particular, refill a consumed SMS/MMS page before selecting from
            // the other source, otherwise global timestamp order can be lost.
            when (val outcome = sms.ensureHead()) {
                EnsureHeadOutcome.Ready -> Unit
                EnsureHeadOutcome.ProgressBoundary -> return readyBatch(staged, sms, mms)
                is EnsureHeadOutcome.Failed -> return ProviderMergeResult.Failure(outcome.failure)
            }
            when (val outcome = mms.ensureHead()) {
                EnsureHeadOutcome.Ready -> Unit
                EnsureHeadOutcome.ProgressBoundary -> return readyBatch(staged, sms, mms)
                is EnsureHeadOutcome.Failed -> return ProviderMergeResult.Failure(outcome.failure)
            }

            val smsHead = sms.peek()
            val mmsHead = mms.peek()
            if (smsHead == null && mmsHead == null) break

            val next = when {
                smsHead == null -> ProviderMergeItem.Mms(requireNotNull(mms.consume()))
                mmsHead == null -> ProviderMergeItem.Sms(requireNotNull(sms.consume()))
                compareProviderRows(
                    leftTimestamp = smsHead.timestampMillis,
                    leftSource = ProviderMergeSource.SMS,
                    leftProviderId = smsHead.id.value,
                    rightTimestamp = mmsHead.timestampMillis,
                    rightSource = ProviderMergeSource.MMS,
                    rightProviderId = mmsHead.id.value,
                ) <= 0 -> ProviderMergeItem.Sms(requireNotNull(sms.consume()))
                else -> ProviderMergeItem.Mms(requireNotNull(mms.consume()))
            }
            staged += next
        }

        return readyBatch(staged, sms, mms)
    }

    private fun readyBatch(
        staged: List<ProviderMergeItem>,
        sms: SourceLookahead<SmsProviderMessage>,
        mms: SourceLookahead<MmsProviderMessage>,
    ): ProviderMergeResult.Ready = ProviderMergeResult.Ready(
        ProviderMergeBatch(
            messages = staged,
            smsProgress = sms.progress(),
            mmsProgress = mms.progress(),
        ),
    )

    private class SourceLookahead<T>(
        private val source: ProviderMergeSource,
        private val expectedKind: ProviderKind,
        initialCursor: ProviderPageCursor?,
        initiallyExhausted: Boolean,
        private val pageSize: Int,
        private val maxPageFetches: Int,
        private val emptyPageBudget: Int,
        private val readPage: suspend (ProviderPageRequest) -> ProviderAccessResult<ProviderPage<T>>,
        private val cursorOf: (T) -> ProviderPageCursor,
    ) {
        private val lookahead = ArrayDeque<T>(pageSize)
        private var requestCursor: ProviderPageCursor? = initialCursor
        private var stagedCursor: ProviderPageCursor? = initialCursor
        private var activePageNext: ProviderPageCursor? = null
        private var activePageExhausted: Boolean = false
        private var exhausted: Boolean = initiallyExhausted
        private var fetchedPageCount: Int = 0
        private var emptyPageCount: Int = 0
        private var consumedItemCount: Int = 0

        fun peek(): T? = lookahead.peekFirst()

        fun consume(): T? {
            val item = lookahead.pollFirst() ?: return null
            stagedCursor = cursorOf(item)
            consumedItemCount += 1
            if (lookahead.isEmpty()) finishConsumedPage()
            return item
        }

        suspend fun ensureHead(): EnsureHeadOutcome {
            while (lookahead.isEmpty() && !exhausted) {
                if (fetchedPageCount >= maxPageFetches) {
                    return EnsureHeadOutcome.ProgressBoundary
                }
                val request = ProviderPageRequest(limit = pageSize, before = requestCursor)
                val result = try {
                    readPage(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return failed(ProviderMergeFailureReason.PROVIDER_EXCEPTION)
                }
                fetchedPageCount += 1

                val page = when (result) {
                    is ProviderAccessResult.Success -> result.value
                    ProviderAccessResult.RoleRequired -> {
                        return failed(ProviderMergeFailureReason.ROLE_REQUIRED)
                    }
                    ProviderAccessResult.PermissionDenied -> {
                        return failed(ProviderMergeFailureReason.PERMISSION_DENIED)
                    }
                    is ProviderAccessResult.Unsupported -> {
                        return failed(ProviderMergeFailureReason.UNSUPPORTED)
                    }
                    is ProviderAccessResult.Unavailable -> {
                        return failed(ProviderMergeFailureReason.UNAVAILABLE)
                    }
                    is ProviderAccessResult.InvalidInput -> {
                        return failed(ProviderMergeFailureReason.INVALID_INPUT)
                    }
                }

                validatePage(page)?.let { return EnsureHeadOutcome.Failed(it) }
                if (page.items.isEmpty()) {
                    if (page.exhausted) {
                        exhausted = true
                    } else {
                        // With no projected row left in the page, advancing
                        // through its raw next cursor is safe to commit.
                        stagedCursor = requireNotNull(page.next)
                        requestCursor = page.next
                        emptyPageCount += 1
                        if (emptyPageCount >= emptyPageBudget) {
                            // The next projected head is still unknown. Yield
                            // only the validated raw-cursor progress rather
                            // than consuming the other source out of order.
                            return EnsureHeadOutcome.ProgressBoundary
                        }
                    }
                    continue
                }

                lookahead.addAll(page.items)
                activePageNext = page.next
                activePageExhausted = page.exhausted
            }
            return EnsureHeadOutcome.Ready
        }

        fun progress(): ProviderMergeProgress = ProviderMergeProgress(
            stagedCursor = stagedCursor,
            exhausted = exhausted,
            fetchedPageCount = fetchedPageCount,
            consumedItemCount = consumedItemCount,
        )

        private fun finishConsumedPage() {
            if (activePageExhausted) {
                exhausted = true
            } else {
                // The full projected page was consumed, so this may advance
                // across malformed raw rows hidden behind the projection.
                stagedCursor = requireNotNull(activePageNext)
                requestCursor = activePageNext
            }
            activePageNext = null
            activePageExhausted = false
        }

        private fun validatePage(page: ProviderPage<T>): ProviderMergeFailure? {
            if (page.items.size > pageSize) {
                return failure(ProviderMergeFailureReason.PAGE_TOO_LARGE)
            }
            if (page.exhausted != (page.next == null)) {
                return failure(ProviderMergeFailureReason.INCONSISTENT_EXHAUSTION)
            }

            var previous = requestCursor
            page.items.forEach { item ->
                val cursor = cursorOf(item)
                val providerKind = when (item) {
                    is SmsProviderMessage -> item.id.kind
                    is MmsProviderMessage -> item.id.kind
                    else -> return failure(ProviderMergeFailureReason.WRONG_PROVIDER_KIND)
                }
                if (providerKind != expectedKind) {
                    return failure(ProviderMergeFailureReason.WRONG_PROVIDER_KIND)
                }
                if (previous != null && !cursor.isStrictlyOlderThan(previous)) {
                    return failure(
                        if (previous === requestCursor) {
                            ProviderMergeFailureReason.ITEM_OUTSIDE_REQUEST_CURSOR
                        } else {
                            ProviderMergeFailureReason.OUT_OF_ORDER_PAGE
                        },
                    )
                }
                previous = cursor
            }

            page.next?.let { next ->
                requestCursor?.let { requestBefore ->
                    if (!next.isStrictlyOlderThan(requestBefore)) {
                        return failure(ProviderMergeFailureReason.NON_ADVANCING_CURSOR)
                    }
                }
                page.items.lastOrNull()?.let { last ->
                    val lastCursor = cursorOf(last)
                    if (next.isStrictlyNewerThan(lastCursor)) {
                        return failure(ProviderMergeFailureReason.INCONSISTENT_PAGE_CURSOR)
                    }
                }
            }
            return null
        }

        private fun failure(reason: ProviderMergeFailureReason): ProviderMergeFailure =
            ProviderMergeFailure(source = source, reason = reason)

        private fun failed(reason: ProviderMergeFailureReason): EnsureHeadOutcome.Failed =
            EnsureHeadOutcome.Failed(failure(reason))
    }

    private sealed interface EnsureHeadOutcome {
        data object Ready : EnsureHeadOutcome
        data object ProgressBoundary : EnsureHeadOutcome
        class Failed(val failure: ProviderMergeFailure) : EnsureHeadOutcome
    }

    companion object {
        const val MAX_BATCH_SIZE: Int = 500
        private const val DEFAULT_EMPTY_PAGE_BUDGET: Int = 8
        private const val MAX_EMPTY_PAGE_BUDGET: Int = 32
    }
}

internal enum class ProviderMergeSource {
    SMS,
    MMS,
}

internal enum class ProviderMergeFailureReason {
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    UNSUPPORTED,
    UNAVAILABLE,
    INVALID_INPUT,
    PROVIDER_EXCEPTION,
    PAGE_TOO_LARGE,
    WRONG_PROVIDER_KIND,
    ITEM_OUTSIDE_REQUEST_CURSOR,
    OUT_OF_ORDER_PAGE,
    INCONSISTENT_EXHAUSTION,
    NON_ADVANCING_CURSOR,
    INCONSISTENT_PAGE_CURSOR,
}

internal class ProviderMergeFailure(
    val source: ProviderMergeSource,
    val reason: ProviderMergeFailureReason,
) {
    override fun toString(): String = "ProviderMergeFailure(source=$source, reason=$reason)"
}

internal sealed interface ProviderMergeResult {
    class Ready(val batch: ProviderMergeBatch) : ProviderMergeResult {
        override fun toString(): String = "ProviderMergeResult.Ready($batch)"
    }

    class Failure(val failure: ProviderMergeFailure) : ProviderMergeResult {
        override fun toString(): String = "ProviderMergeResult.Failure($failure)"
    }
}

internal class ProviderMergeBatch(
    messages: List<ProviderMergeItem>,
    val smsProgress: ProviderMergeProgress,
    val mmsProgress: ProviderMergeProgress,
) {
    val messages: List<ProviderMergeItem> = Collections.unmodifiableList(messages.toList())

    init {
        require(messages.size <= ProviderMergeCursor.MAX_BATCH_SIZE) {
            "A provider merge batch cannot exceed the transaction bound"
        }
        require(smsProgress.consumedItemCount + mmsProgress.consumedItemCount == messages.size) {
            "Provider progress must account for every staged message"
        }
    }

    override fun toString(): String =
        "ProviderMergeBatch(messageCount=${messages.size}, " +
            "smsProgress=$smsProgress, mmsProgress=$mmsProgress)"
}

internal class ProviderMergeProgress(
    val stagedCursor: ProviderPageCursor?,
    val exhausted: Boolean,
    val fetchedPageCount: Int,
    val consumedItemCount: Int,
) {
    init {
        require(fetchedPageCount >= 0) { "Fetched page count cannot be negative" }
        require(consumedItemCount >= 0) { "Consumed item count cannot be negative" }
    }

    override fun toString(): String =
        "ProviderMergeProgress(exhausted=$exhausted, fetchedPageCount=$fetchedPageCount, " +
            "consumedItemCount=$consumedItemCount)"
}

internal sealed interface ProviderMergeItem {
    val providerId: ProviderMessageId
    val timestampMillis: Long

    class Sms(val message: SmsProviderMessage) : ProviderMergeItem {
        override val providerId: ProviderMessageId = message.id
        override val timestampMillis: Long = message.timestampMillis

        override fun toString(): String = "ProviderMergeItem.Sms(REDACTED)"
    }

    class Mms(val message: MmsProviderMessage) : ProviderMergeItem {
        override val providerId: ProviderMessageId = message.id
        override val timestampMillis: Long = message.timestampMillis

        override fun toString(): String = "ProviderMergeItem.Mms(REDACTED)"
    }
}

private fun SmsProviderMessage.providerCursor(): ProviderPageCursor = ProviderPageCursor(
    timestampMillis = timestampMillis,
    providerRowId = id.value,
)

private fun MmsProviderMessage.providerCursor(): ProviderPageCursor = ProviderPageCursor(
    timestampMillis = timestampMillis,
    providerRowId = id.value,
)

/** Negative means left sorts before right in the descending merge. */
private fun compareProviderRows(
    leftTimestamp: Long,
    leftSource: ProviderMergeSource,
    leftProviderId: Long,
    rightTimestamp: Long,
    rightSource: ProviderMergeSource,
    rightProviderId: Long,
): Int {
    if (leftTimestamp != rightTimestamp) return rightTimestamp.compareTo(leftTimestamp)
    if (leftSource != rightSource) {
        return if (leftSource == ProviderMergeSource.SMS) -1 else 1
    }
    return rightProviderId.compareTo(leftProviderId)
}

private fun ProviderPageCursor.isStrictlyOlderThan(other: ProviderPageCursor): Boolean =
    timestampMillis < other.timestampMillis ||
        (timestampMillis == other.timestampMillis && providerRowId < other.providerRowId)

private fun ProviderPageCursor.isStrictlyNewerThan(other: ProviderPageCursor): Boolean =
    timestampMillis > other.timestampMillis ||
        (timestampMillis == other.timestampMillis && providerRowId > other.providerRowId)
