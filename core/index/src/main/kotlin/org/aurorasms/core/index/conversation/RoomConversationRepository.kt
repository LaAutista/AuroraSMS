// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import android.database.sqlite.SQLiteException
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexedConversationEntity
import org.aurorasms.core.index.storage.toIndexRunState
import org.aurorasms.core.index.storage.toIndexedMessageDirection
import org.aurorasms.core.index.storage.toIndexedProviderKind
import org.aurorasms.core.index.search.toCoverage
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

class RoomConversationRepository(
    private val database: AuroraIndexDatabase,
) : ConversationRepository {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.indexedMessageDao()
    private val syncDao = database.indexSyncDao()

    override val invalidations: Flow<ConversationInvalidation> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(
            "indexed_conversations",
            "indexed_conversation_participants",
            "indexed_messages",
            "index_generations",
        ) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(ConversationInvalidation)
            }
        }
        database.invalidationTracker.addObserver(observer)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }.conflate()

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult = try {
        database.withTransaction {
            val generation = syncDao.latestGeneration()
            if (generation == null) {
                return@withTransaction ConversationPageResult.Page(
                    ConversationPage(
                        items = emptyList(),
                        next = null,
                        direction = request.direction,
                        coverage = IndexCoverage.NOT_STARTED.copy(indexedMessageCount = messageDao.count()),
                    ),
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
            if (request.cursor != null && request.cursor.generationId != generation.generationId) {
                return@withTransaction ConversationPageResult.StaleGeneration(coverage)
            }

            val requestedRows = request.limit + 1
            val stored = when {
                request.cursor == null -> conversationDao.inboxFirst(generation.generationId, requestedRows)
                request.direction == ConversationPageDirection.OLDER -> conversationDao.inboxOlder(
                    generationId = generation.generationId,
                    timestampMillis = request.cursor.latestTimestampMillis,
                    rowId = request.cursor.latestLocalRowId,
                    limit = requestedRows,
                )
                else -> conversationDao.inboxNewer(
                    generationId = generation.generationId,
                    timestampMillis = request.cursor.latestTimestampMillis,
                    rowId = request.cursor.latestLocalRowId,
                    limit = requestedRows,
                )
            }
            val queryOrderedPage = stored.take(request.limit)
            val canonicalPage = if (request.direction == ConversationPageDirection.NEWER) {
                queryOrderedPage.asReversed()
            } else {
                queryOrderedPage
            }
            val participantsByThread = if (canonicalPage.isEmpty()) {
                emptyMap()
            } else {
                conversationDao.participantPreviews(
                    generationId = generation.generationId,
                    providerThreadIds = canonicalPage.map(IndexedConversationEntity::providerThreadId),
                    perThreadLimit = MAXIMUM_PARTICIPANT_PREVIEW,
                ).groupBy { it.providerThreadId }
            }
            val next = if (stored.size > request.limit) {
                queryOrderedPage.last().toCursor(generation.generationId)
            } else {
                null
            }
            ConversationPageResult.Page(
                ConversationPage(
                    items = canonicalPage.map { entity ->
                        entity.toSummary(
                            participantsByThread[entity.providerThreadId]
                                .orEmpty()
                                .map { ParticipantAddress(it.address) },
                        )
                    },
                    next = next,
                    direction = request.direction,
                    coverage = coverage,
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteException) {
        ConversationPageResult.StorageUnavailable(safeCoverage())
    }

    private suspend fun safeCoverage(): IndexCoverage = try {
        val generation = syncDao.latestGeneration() ?: return IndexCoverage.NOT_STARTED
        generation.toCoverage(syncDao.checkpoints(generation.generationId), messageDao.count())
    } catch (_: SQLiteException) {
        IndexCoverage.NOT_STARTED
    }
}

private fun IndexedConversationEntity.toCursor(generationId: Long): ConversationCursor = ConversationCursor(
    generationId = generationId,
    latestTimestampMillis = latestTimestampMillis,
    latestLocalRowId = latestRowId,
)

private fun IndexedConversationEntity.toSummary(
    participants: List<ParticipantAddress>,
): ConversationSummary = ConversationSummary(
    providerThreadId = ProviderThreadId(providerThreadId),
    latestLocalRowId = latestRowId,
    latestProviderMessageId = ProviderMessageId(latestProviderKind.toIndexedProviderKind(), latestProviderId),
    latestTimestampMillis = latestTimestampMillis,
    latestSentTimestampMillis = latestSentTimestampMillis,
    latestDirection = latestDirection.toIndexedMessageDirection(),
    latestBox = MessageBox.fromStorageCode(latestMessageBox),
    latestStatus = MessageStatus.fromStorageCode(latestMessageStatus),
    latestSubscriptionId = latestSubscriptionId?.let(::AuroraSubscriptionId),
    latestSenderAddress = latestSenderAddress?.let(::ParticipantAddress),
    latestSnippet = latestSnippet,
    latestAttachmentCount = latestAttachmentCount,
    latestAttachmentTypeSummary = latestAttachmentTypeSummary,
    latestRead = latestIsRead,
    indexedMessageCount = indexedMessageCount,
    indexedUnreadCount = indexedUnreadCount,
    participants = participants,
    indexedParticipantCount = indexedParticipantCount,
    participantsTruncated = participantsTruncated,
)
