// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.search

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
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexGenerationEntity
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.storage.ProviderKindCode
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
    database: AuroraIndexDatabase,
) : MessageIndex {
    private val messageDao = database.indexedMessageDao()
    private val syncDao = database.indexSyncDao()

    override suspend fun coverage(): IndexCoverage {
        val generation = syncDao.latestGeneration()
            ?: return IndexCoverage.NOT_STARTED.copy(indexedMessageCount = messageDao.count())
        return generation.toCoverage(
            checkpoints = syncDao.checkpoints(generation.generationId),
            indexedMessageCount = messageDao.count(),
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
        val candidateRowIds = messageDao.searchCandidateRowIds(
            matchExpression = parsed.matchExpression,
            limit = SPARSE_RESULT_LIMIT + 1,
        )
        val entities = if (candidateRowIds.size <= SPARSE_RESULT_LIMIT) {
            sparseResultPage(
                entities = if (candidateRowIds.isEmpty()) {
                    emptyList()
                } else {
                    messageDao.messagesByLocalRowIds(candidateRowIds)
                },
                threadId = request.threadId,
                cursor = cursor,
                limit = requestedRows,
            )
        } else when {
            request.threadId == null && cursor == null -> messageDao.searchGlobalFirst(
                matchExpression = parsed.matchExpression,
                limit = requestedRows,
            )
            request.threadId == null -> messageDao.searchGlobalAfter(
                matchExpression = parsed.matchExpression,
                beforeTimestampMillis = requireNotNull(cursor).timestampMillis,
                beforeRowId = cursor.localRowId,
                limit = requestedRows,
            )
            cursor == null -> messageDao.searchThreadFirst(
                matchExpression = parsed.matchExpression,
                providerThreadId = request.threadId.value,
                limit = requestedRows,
            )
            else -> messageDao.searchThreadAfter(
                matchExpression = parsed.matchExpression,
                providerThreadId = request.threadId.value,
                beforeTimestampMillis = cursor.timestampMillis,
                beforeRowId = cursor.localRowId,
                limit = requestedRows,
            )
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

private fun IndexGenerationEntity.toCoverage(
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

private fun sparseResultPage(
    entities: List<IndexedMessageEntity>,
    threadId: ProviderThreadId?,
    cursor: SearchCursor?,
    limit: Int,
): List<IndexedMessageEntity> = entities.asSequence()
    .filter { entity -> threadId == null || entity.providerThreadId == threadId.value }
    .filter { entity ->
        cursor == null ||
            entity.timestampMillis < cursor.timestampMillis ||
            (entity.timestampMillis == cursor.timestampMillis && entity.rowId < cursor.localRowId)
    }
    .sortedWith(
        compareByDescending<IndexedMessageEntity>(IndexedMessageEntity::timestampMillis)
            .thenByDescending(IndexedMessageEntity::rowId),
    )
    .take(limit)
    .toList()

private const val SPARSE_RESULT_LIMIT: Int = 500
