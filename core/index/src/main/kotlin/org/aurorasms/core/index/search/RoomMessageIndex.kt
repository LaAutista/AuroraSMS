// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.search

import androidx.room.withTransaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.MAXIMUM_ANCHOR_HALF_WINDOW
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchCursor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.index.SearchPage
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult
import org.aurorasms.core.index.SearchValidationFailure
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexGenerationEntity
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.storage.ProviderKindCode
import org.aurorasms.core.index.storage.StoredSearchOrder
import org.aurorasms.core.index.storage.toIndexFailureCode
import org.aurorasms.core.index.storage.toIndexRunState
import org.aurorasms.core.index.storage.toIndexStorageCode
import org.aurorasms.core.index.storage.toIndexedMessageDirection
import org.aurorasms.core.index.storage.toIndexedProviderKind
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

class RoomMessageIndex(
    private val database: AuroraIndexDatabase,
) : MessageIndex {
    private val messageDao = database.indexedMessageDao()
    private val syncDao = database.indexSyncDao()

    override suspend fun coverage(): IndexCoverage {
        val generation = syncDao.latestGeneration()
            ?: return IndexCoverage.NOT_STARTED.copy(indexedMessageCount = messageDao.count())
        val indexedMessageCount = if (generation.state == GenerationStateCode.COMPLETE) {
            // Completion and every steady-state content mutation persist a physical COUNT(*) in
            // the same transaction, so a complete generation already owns an exact row count.
            generation.committedCount
        } else {
            messageDao.count()
        }
        return generation.toCoverage(
            checkpoints = syncDao.checkpoints(generation.generationId),
            indexedMessageCount = indexedMessageCount,
        )
    }

    override suspend fun search(request: SearchRequest): SearchResult {
        val parsed = when (val result = SafeFts4QueryParser.parse(request.rawQuery)) {
            is ParsedFts4Query.Invalid -> return SearchResult.Invalid(result.reason)
            ParsedFts4Query.NoQuery -> return SearchResult.NoQuery
            is ParsedFts4Query.Ready -> result
        }
        val queryFingerprint = queryFingerprint(parsed.canonicalQuery, request.threadId)
        val cursor = request.cursor
        if (cursor != null && cursor.queryFingerprint != queryFingerprint) {
            return SearchResult.Invalid(SearchValidationFailure.CURSOR_MISMATCH)
        }

        val requestedRows = request.limit + 1
        val entities = database.withTransaction {
            val orderedRowIds = when {
                request.threadId == null -> globalRowIds(
                    matchExpression = parsed.matchExpression,
                    requestedRows = requestedRows,
                    cursor = cursor,
                )
                cursor == null -> messageDao.searchThreadFirstRowIds(
                    matchExpression = parsed.matchExpression,
                    providerThreadId = request.threadId.value,
                    limit = requestedRows,
                )
                else -> messageDao.searchThreadAfterRowIds(
                    matchExpression = parsed.matchExpression,
                    providerThreadId = request.threadId.value,
                    beforeTimestampMillis = cursor.timestampMillis,
                    beforeRowId = cursor.localRowId,
                    limit = requestedRows,
                )
            }
            val entitiesByRowId = if (orderedRowIds.isEmpty()) {
                emptyMap()
            } else {
                messageDao.messagesByLocalRowIds(orderedRowIds)
                    .associateBy(IndexedMessageEntity::rowId)
            }
            orderedRowIds.map { rowId ->
                checkNotNull(entitiesByRowId[rowId]) {
                    "An FTS result did not resolve to indexed content"
                }
            }
        }
        val pageEntities = entities.take(request.limit)
        val next = if (entities.size > request.limit) {
            pageEntities.last().let { last ->
                SearchCursor(
                    timestampMillis = last.timestampMillis,
                    localRowId = last.rowId,
                    queryFingerprint = queryFingerprint,
                )
            }
        } else {
            null
        }
        return SearchResult.Page(
            SearchPage(
                items = pageEntities.map(IndexedMessageEntity::toSearchHit),
                next = next,
                coverage = coverage(),
            ),
        )
    }

    /**
     * FTS4 yields a cheap bounded docid prefix but cannot directly order by the message timeline.
     * A normal-index proof makes that prefix exact when every non-candidate row is older than the
     * requested boundary. Arbitrary or out-of-order row IDs fail the proof and use the compact,
     * exact FTS-first row-id sort instead.
     */
    private suspend fun globalRowIds(
        matchExpression: String,
        requestedRows: Int,
        cursor: SearchCursor?,
    ): List<Long> {
        val candidateLimit = maxOf(MINIMUM_FAST_SEARCH_CANDIDATES, requestedRows)
        val candidateRowIds = if (cursor == null) {
            messageDao.searchCandidateRowIds(matchExpression, candidateLimit)
        } else {
            messageDao.searchCandidateRowIdsAfterLocalRowId(
                matchExpression = matchExpression,
                afterRowId = cursor.localRowId,
                limit = candidateLimit,
            )
        }
        if (candidateRowIds.isEmpty()) {
            return if (cursor == null) {
                emptyList()
            } else {
                messageDao.searchGlobalAfterRowIds(
                    matchExpression = matchExpression,
                    beforeTimestampMillis = cursor.timestampMillis,
                    beforeRowId = cursor.localRowId,
                    limit = requestedRows,
                )
            }
        }
        val resolvedOrders = messageDao.searchOrdersByLocalRowIds(candidateRowIds)
        check(resolvedOrders.size == candidateRowIds.size) {
            "An FTS candidate did not resolve to indexed content"
        }
        val candidateOrders = resolvedOrders
            .asSequence()
            .filter { order -> cursor == null || order.isOlderThan(cursor) }
            .sortedWith(SEARCH_ORDER)
            .toList()
        if (cursor == null && candidateRowIds.size < candidateLimit) {
            return candidateOrders.map(StoredSearchOrder::rowId)
        }

        val boundary = candidateOrders.getOrNull(requestedRows - 1)
        if (boundary != null) {
            val newestOutside = if (cursor == null) {
                messageDao.newestGlobalOrderOutside(candidateRowIds)
            } else {
                messageDao.newestGlobalOrderOutsideAfter(
                    excludedRowIds = candidateRowIds,
                    beforeTimestampMillis = cursor.timestampMillis,
                    beforeRowId = cursor.localRowId,
                )
            }
            if (newestOutside == null || !newestOutside.isNewerThan(boundary)) {
                return candidateOrders.take(requestedRows).map(StoredSearchOrder::rowId)
            }
        }
        return if (cursor == null) {
            messageDao.searchGlobalFirstRowIds(matchExpression, requestedRows)
        } else {
            messageDao.searchGlobalAfterRowIds(
                matchExpression = matchExpression,
                beforeTimestampMillis = cursor.timestampMillis,
                beforeRowId = cursor.localRowId,
                limit = requestedRows,
            )
        }
    }

    override suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int,
    ): AnchorWindowResult {
        require(halfWindow in 0..MAXIMUM_ANCHOR_HALF_WINDOW) {
            "Anchor half-window is outside the reviewed bound"
        }
        val storedWindow = messageDao.anchorWindow(
            localRowId = anchor.localRowId,
            providerKind = anchor.providerId.kind.toIndexStorageCode(),
            providerId = anchor.providerId.value,
            halfWindow = halfWindow,
        )
        val currentCoverage = coverage()
        if (storedWindow == null) return AnchorWindowResult.NotFound(currentCoverage)

        val messages = (storedWindow.newer + storedWindow.anchor + storedWindow.older)
            .map(IndexedMessageEntity::toSearchHit)
        return AnchorWindowResult.Found(
            messages = messages,
            highlightedLocalRowId = storedWindow.anchor.rowId,
            anchorPosition = storedWindow.newer.size,
            reResolvedAfterRebuild = storedWindow.reResolvedAfterRebuild,
            coverage = currentCoverage,
        )
    }
}

internal fun IndexGenerationEntity.toCoverage(
    checkpoints: List<IndexCheckpointEntity>,
    indexedMessageCount: Long,
): IndexCoverage {
    val runState = state.toIndexRunState()
    val smsCheckpoint = checkpoints.firstOrNull { it.providerKind == ProviderKindCode.SMS }
    val mmsCheckpoint = checkpoints.firstOrNull { it.providerKind == ProviderKindCode.MMS }
    val failure = if (runState == IndexRunState.FAILED) {
        failureCode.toIndexFailureCode()
    } else {
        null
    }
    return IndexCoverage(
        generationId = generationId,
        state = runState,
        indexedMessageCount = indexedMessageCount,
        smsExhausted = smsCheckpoint?.exhausted == true,
        mmsExhausted = mmsCheckpoint?.exhausted == true,
        pendingChanges = pendingChanges,
        generationCommittedCount = committedCount,
        smsCheckpointCommittedCount = smsCheckpoint?.committedCount ?: 0L,
        mmsCheckpointCommittedCount = mmsCheckpoint?.committedCount ?: 0L,
        failureCode = failure,
    )
}

private fun IndexedMessageEntity.toSearchHit(): SearchHit = SearchHit(
    localRowId = rowId,
    providerId = ProviderMessageId(providerKind.toIndexedProviderKind(), providerId),
    providerThreadId = ProviderThreadId(providerThreadId),
    timestampMillis = timestampMillis,
    sentTimestampMillis = sentTimestampMillis,
    direction = direction.toIndexedMessageDirection(),
    box = MessageBox.fromStorageCode(messageBox),
    status = MessageStatus.fromStorageCode(messageStatus),
    subscriptionId = subscriptionId?.let(::AuroraSubscriptionId),
    senderAddress = senderAddress,
    body = body,
    subject = subject,
    attachmentCount = attachmentCount,
    attachmentTypeSummary = attachmentTypeSummary,
    read = isRead,
    seen = isSeen,
    locked = isLocked,
)

private fun queryFingerprint(
    canonicalQuery: String,
    threadId: ProviderThreadId?,
): String {
    val framed = buildString {
        append("aurora-fts4-v1\u0000")
        append(canonicalQuery.length)
        append(':')
        append(canonicalQuery)
        append("\u0000thread:")
        append(threadId?.value ?: "global")
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(framed.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(LocaleHolder.ROOT, byte.toInt() and 0xff) }
}

private object LocaleHolder {
    val ROOT: java.util.Locale = java.util.Locale.ROOT
}

private val SEARCH_ORDER = compareByDescending<StoredSearchOrder>(StoredSearchOrder::timestampMillis)
    .thenByDescending(StoredSearchOrder::rowId)

private fun StoredSearchOrder.isNewerThan(other: StoredSearchOrder): Boolean =
    timestampMillis > other.timestampMillis ||
        (timestampMillis == other.timestampMillis && rowId > other.rowId)

private fun StoredSearchOrder.isOlderThan(cursor: SearchCursor): Boolean =
    timestampMillis < cursor.timestampMillis ||
        (timestampMillis == cursor.timestampMillis && rowId < cursor.localRowId)

private const val MINIMUM_FAST_SEARCH_CANDIDATES: Int = 64
