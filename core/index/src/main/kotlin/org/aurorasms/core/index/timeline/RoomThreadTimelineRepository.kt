// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.timeline

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlin.coroutines.cancellation.CancellationException
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.search.toCoverage
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.StoredTimelineMessage
import org.aurorasms.core.index.storage.toIndexStorageCode
import org.aurorasms.core.index.storage.toIndexedMessageDirection
import org.aurorasms.core.index.storage.toIndexedProviderKind
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

class RoomThreadTimelineRepository(
    private val database: AuroraIndexDatabase,
) : ThreadTimelineRepository {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.indexedMessageDao()
    private val syncDao = database.indexSyncDao()

    override suspend fun load(request: TimelinePageRequest): TimelinePageResult = try {
        database.withTransaction {
            val generation = syncDao.latestGeneration()
            if (generation == null) {
                return@withTransaction TimelinePageResult.MissingThread(
                    IndexCoverage.NOT_STARTED.copy(indexedMessageCount = messageDao.count()),
                )
            }
            val coverage = generation.toCoverage(
                checkpoints = syncDao.checkpoints(generation.generationId),
                indexedMessageCount = if (generation.state == GenerationStateCode.COMPLETE) {
                    generation.committedCount
                } else {
                    messageDao.count()
                },
            )
            val includeUnverifiedCache = !coverage.verifiedComplete
            if (request.cursor != null && request.cursor.generationId != generation.generationId) {
                return@withTransaction TimelinePageResult.StaleGeneration(coverage)
            }
            val requestedRows = request.limit + 1
            val stored = when (request.direction) {
                TimelinePageDirection.LATEST -> conversationDao.timelineLatest(
                    providerThreadId = request.providerThreadId.value,
                    generationId = generation.generationId,
                    limit = requestedRows,
                    includeUnverifiedCache = includeUnverifiedCache,
                )
                TimelinePageDirection.OLDER -> conversationDao.timelineOlder(
                    providerThreadId = request.providerThreadId.value,
                    generationId = generation.generationId,
                    timestampMillis = requireNotNull(request.cursor).timestampMillis,
                    rowId = request.cursor.localRowId,
                    limit = requestedRows,
                    includeUnverifiedCache = includeUnverifiedCache,
                )
                TimelinePageDirection.NEWER -> conversationDao.timelineNewer(
                    providerThreadId = request.providerThreadId.value,
                    generationId = generation.generationId,
                    timestampMillis = requireNotNull(request.cursor).timestampMillis,
                    rowId = request.cursor.localRowId,
                    limit = requestedRows,
                    includeUnverifiedCache = includeUnverifiedCache,
                )
            }
            if (stored.isEmpty() && request.direction == TimelinePageDirection.LATEST) {
                val conversationExists = if (includeUnverifiedCache) {
                    conversationDao.cachedConversation(request.providerThreadId.value) != null
                } else {
                    conversationDao.conversation(request.providerThreadId.value, generation.generationId) != null
                }
                if (!conversationExists) {
                    return@withTransaction TimelinePageResult.MissingThread(coverage)
                }
            }
            val queryOrderedPage = stored.take(request.limit)
            val canonicalPage = if (request.direction == TimelinePageDirection.NEWER) {
                queryOrderedPage
            } else {
                queryOrderedPage.asReversed()
            }
            val next = if (stored.size > request.limit) {
                queryOrderedPage.last().toCursor(generation.generationId)
            } else {
                null
            }
            TimelinePageResult.Page(
                TimelinePage(
                    items = canonicalPage.map(StoredTimelineMessage::toTimelineMessage),
                    next = next,
                    direction = request.direction,
                    coverage = coverage,
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        TimelinePageResult.StorageUnavailable(safeCoverage())
    }

    override suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: ProviderMessageId,
    ): TimelineContentResult = try {
        database.withTransaction {
            val generation = syncDao.latestGeneration()
                ?: return@withTransaction TimelineContentResult.Missing(
                    IndexCoverage.NOT_STARTED.copy(indexedMessageCount = messageDao.count()),
                )
            val coverage = generation.toCoverage(
                checkpoints = syncDao.checkpoints(generation.generationId),
                indexedMessageCount = if (generation.state == GenerationStateCode.COMPLETE) {
                    generation.committedCount
                } else {
                    messageDao.count()
                },
            )
            val includeUnverifiedCache = !coverage.verifiedComplete
            val stored = conversationDao.timelineContent(
                providerKind = providerMessageId.kind.toIndexStorageCode(),
                providerId = providerMessageId.value,
                providerThreadId = providerThreadId.value,
                generationId = generation.generationId,
                includeUnverifiedCache = includeUnverifiedCache,
            ) ?: return@withTransaction TimelineContentResult.Missing(coverage)
            TimelineContentResult.Found(
                content = TimelineMessageContent(
                    providerMessageId = providerMessageId,
                    body = stored.body,
                    subject = stored.subject,
                    sourceTruncated = stored.sourceTruncated,
                ),
                coverage = coverage,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        TimelineContentResult.StorageUnavailable(safeCoverage())
    }

    private suspend fun safeCoverage(): IndexCoverage = try {
        val generation = syncDao.latestGeneration() ?: return IndexCoverage.NOT_STARTED
        generation.toCoverage(syncDao.checkpoints(generation.generationId), messageDao.count())
    } catch (_: SQLiteException) {
        IndexCoverage.NOT_STARTED
    }
}

private fun StoredTimelineMessage.toCursor(generationId: Long): TimelineCursor = TimelineCursor(
    generationId = generationId,
    providerThreadId = ProviderThreadId(providerThreadId),
    timestampMillis = timestampMillis,
    localRowId = rowId,
)

private fun StoredTimelineMessage.toTimelineMessage(): TimelineMessage = TimelineMessage(
    localRowId = rowId,
    providerMessageId = ProviderMessageId(providerKind.toIndexedProviderKind(), providerId),
    providerThreadId = ProviderThreadId(providerThreadId),
    timestampMillis = timestampMillis,
    sentTimestampMillis = sentTimestampMillis,
    direction = direction.toIndexedMessageDirection(),
    box = MessageBox.fromStorageCode(messageBox),
    status = MessageStatus.fromStorageCode(messageStatus),
    subscriptionId = subscriptionId?.let(::AuroraSubscriptionId),
    senderAddress = senderAddress?.let(::ParticipantAddress),
    bodyPreview = bodyPreview,
    bodyTruncated = bodyTruncated,
    subject = subject,
    attachmentCount = attachmentCount,
    attachmentTypeSummary = attachmentTypeSummary,
    read = isRead,
    seen = isSeen,
    locked = isLocked,
    syncFingerprint = MessageSyncFingerprint.fromStorageToken(syncFingerprint),
)
