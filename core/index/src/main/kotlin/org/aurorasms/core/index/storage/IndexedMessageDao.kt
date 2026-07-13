// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import org.aurorasms.core.index.MAXIMUM_ANCHOR_HALF_WINDOW
import org.aurorasms.core.index.conversation.MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS
import org.aurorasms.core.index.sync.IndexedProviderProjection
import org.aurorasms.core.index.sync.MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS

data class StoredMessageIdentity(
    @ColumnInfo(name = "row_id")
    val rowId: Long,
    @ColumnInfo(name = "sync_fingerprint")
    val syncFingerprint: String,
) {
    override fun toString(): String = "StoredMessageIdentity(rowId=REDACTED, syncFingerprint=REDACTED)"
}

data class StoredSyncState(
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "sync_fingerprint")
    val syncFingerprint: String,
    @ColumnInfo(name = "last_seen_generation")
    val lastSeenGeneration: Long,
) {
    override fun toString(): String =
        "StoredSyncState(providerId=REDACTED, syncFingerprint=REDACTED, generation=REDACTED)"
}

data class StoredSearchOrder(
    @ColumnInfo(name = "row_id")
    val rowId: Long,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMillis: Long,
) {
    override fun toString(): String = "StoredSearchOrder(REDACTED)"
}

data class IndexUpsertSummary(
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
) {
    init {
        require(inserted >= 0 && updated >= 0 && unchanged >= 0) {
            "Upsert summary counts cannot be negative"
        }
    }

    val total: Int
        get() = inserted + updated + unchanged
}

private enum class UpsertDisposition {
    INSERTED,
    UPDATED,
    UNCHANGED,
}

private data class PersistedUpsert(
    val entity: IndexedMessageEntity,
    val disposition: UpsertDisposition,
)

private data class UpsertBatchResult(
    val summary: IndexUpsertSummary,
    val rows: List<PersistedUpsert>,
)

data class StoredAnchorWindow(
    val anchor: IndexedMessageEntity,
    val newer: List<IndexedMessageEntity>,
    val older: List<IndexedMessageEntity>,
    val reResolvedAfterRebuild: Boolean,
) {
    init {
        require(newer.size <= MAXIMUM_ANCHOR_HALF_WINDOW) { "The newer anchor window must remain bounded" }
        require(older.size <= MAXIMUM_ANCHOR_HALF_WINDOW) { "The older anchor window must remain bounded" }
    }

    override fun toString(): String =
        "StoredAnchorWindow(newerCount=${newer.size}, olderCount=${older.size}, " +
            "reResolvedAfterRebuild=$reResolvedAfterRebuild, anchor=REDACTED)"
}

@Dao
abstract class IndexedMessageDao {
    @Query(
        """
        SELECT row_id, sync_fingerprint
        FROM indexed_messages
        WHERE provider_kind = :providerKind AND provider_id = :providerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun storedIdentity(
        providerKind: Int,
        providerId: Long,
    ): StoredMessageIdentity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringConflict(entity: IndexedMessageEntity): Long

    @Update
    protected abstract suspend fun updateByRowId(entity: IndexedMessageEntity): Int

    @Query(
        """
        UPDATE indexed_messages
        SET last_seen_generation = :generation
        WHERE row_id = :rowId
        """,
    )
    protected abstract suspend fun markSeen(rowId: Long, generation: Long): Int

    @Transaction
    internal open suspend fun upsertBatchPreservingLocalIds(
        entities: List<IndexedMessageEntity>,
    ): IndexUpsertSummary = upsertBatchInternal(entities).summary

    @Query("SELECT * FROM indexed_conversations WHERE provider_thread_id = :providerThreadId LIMIT 1")
    protected abstract suspend fun storedConversation(providerThreadId: Long): IndexedConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putConversation(entity: IndexedConversationEntity)

    @Query("DELETE FROM indexed_conversation_participants WHERE provider_thread_id = :providerThreadId")
    protected abstract suspend fun deleteConversationParticipants(providerThreadId: Long): Int

    @Query(
        """
        SELECT address FROM indexed_conversation_participants
        WHERE provider_thread_id = :providerThreadId AND last_seen_generation = :generationId
        ORDER BY address ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun storedParticipantAddresses(
        providerThreadId: Long,
        generationId: Long,
        limit: Int,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertParticipantIgnoringConflict(
        entity: IndexedConversationParticipantEntity,
    ): Long

    @Query(
        """
        SELECT COUNT(*) FROM indexed_conversation_participants
        WHERE provider_thread_id = :providerThreadId AND last_seen_generation = :generationId
        """,
    )
    protected abstract suspend fun participantCount(providerThreadId: Long, generationId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceCheckpoints(entities: List<IndexCheckpointEntity>)

    @Query(
        """
        UPDATE index_generations
        SET updated_at_ms = :nowMillis,
            committed_count = committed_count + :processedCount,
            target_batch_size = :targetBatchSize
        WHERE generation_id = :generationId AND state = :scanningState
        """,
    )
    protected abstract suspend fun advanceScanningGeneration(
        generationId: Long,
        nowMillis: Long,
        processedCount: Int,
        targetBatchSize: Int,
        scanningState: Int = GenerationStateCode.SCANNING,
    ): Int

    @Query(
        """
        UPDATE index_generations
        SET updated_at_ms = :nowMillis,
            committed_count = :indexedCount,
            pending_changes = 0
        WHERE generation_id = :generationId
          AND state = :completeState
          AND signal_sequence = :expectedSignalSequence
        """,
    )
    protected abstract suspend fun finishSteadyStateCommit(
        generationId: Long,
        expectedSignalSequence: Long,
        nowMillis: Long,
        indexedCount: Long,
        completeState: Int = GenerationStateCode.COMPLETE,
    ): Int

    /** Commits content, generation marks, and both provider cursors as one durable unit. */
    @Transaction
    open suspend fun commitScanningBatch(
        generationId: Long,
        entities: List<IndexedMessageEntity>,
        smsCheckpoint: IndexCheckpointEntity,
        mmsCheckpoint: IndexCheckpointEntity,
        nowMillis: Long,
        targetBatchSize: Int,
    ): IndexUpsertSummary = commitScanningProjectionBatch(
        generationId = generationId,
        projections = entities.map(IndexedProviderProjection::fromMessageOnly),
        smsCheckpoint = smsCheckpoint,
        mmsCheckpoint = mmsCheckpoint,
        nowMillis = nowMillis,
        targetBatchSize = targetBatchSize,
    )

    /** Adds the v2 projections to the same transaction as messages and provider cursors. */
    @Transaction
    open suspend fun commitScanningProjectionBatch(
        generationId: Long,
        projections: List<IndexedProviderProjection>,
        smsCheckpoint: IndexCheckpointEntity,
        mmsCheckpoint: IndexCheckpointEntity,
        nowMillis: Long,
        targetBatchSize: Int,
    ): IndexUpsertSummary {
        require(generationId > 0L) { "Index generations must be positive" }
        require(projections.size <= MAXIMUM_INDEX_BATCH_SIZE) { "Index batches must remain bounded" }
        require(projections.all { it.message.lastSeenGeneration == generationId }) {
            "Every batch row must belong to the committed generation"
        }
        require(smsCheckpoint.generationId == generationId && mmsCheckpoint.generationId == generationId) {
            "Provider checkpoints must belong to the committed generation"
        }
        require(smsCheckpoint.providerKind == ProviderKindCode.SMS) { "Expected the SMS checkpoint" }
        require(mmsCheckpoint.providerKind == ProviderKindCode.MMS) { "Expected the MMS checkpoint" }
        require(nowMillis >= 0L) { "Batch time cannot be negative" }
        require(targetBatchSize in MINIMUM_INDEX_BATCH_SIZE..MAXIMUM_INDEX_BATCH_SIZE) {
            "Index batch size is outside the reviewed bound"
        }

        val upserts = upsertBatchInternal(projections.map(IndexedProviderProjection::message))
        accumulateConversationProjections(
            generationId = generationId,
            projections = projections,
            persistedRows = upserts.rows,
            onlyInserted = false,
        )
        replaceCheckpoints(listOf(smsCheckpoint, mmsCheckpoint))
        check(
            advanceScanningGeneration(
                generationId = generationId,
                nowMillis = nowMillis,
                processedCount = projections.size,
                targetBatchSize = targetBatchSize,
            ) == 1,
        ) { "The scanning generation was not active during commit" }
        return upserts.summary
    }

    /** Applies one bounded verified head insert without starting a full generation. */
    @Transaction
    open suspend fun commitSteadyStateBatch(
        generationId: Long,
        expectedSignalSequence: Long,
        entities: List<IndexedMessageEntity>,
        smsCheckpoint: IndexCheckpointEntity,
        mmsCheckpoint: IndexCheckpointEntity,
        nowMillis: Long,
    ): IndexUpsertSummary = commitSteadyStateProjectionBatch(
        generationId = generationId,
        expectedSignalSequence = expectedSignalSequence,
        projections = entities.map(IndexedProviderProjection::fromMessageOnly),
        smsCheckpoint = smsCheckpoint,
        mmsCheckpoint = mmsCheckpoint,
        nowMillis = nowMillis,
    )

    /** Applies a verified owned head projection without rescanning its thread. */
    @Transaction
    open suspend fun commitSteadyStateProjectionBatch(
        generationId: Long,
        expectedSignalSequence: Long,
        projections: List<IndexedProviderProjection>,
        smsCheckpoint: IndexCheckpointEntity,
        mmsCheckpoint: IndexCheckpointEntity,
        nowMillis: Long,
    ): IndexUpsertSummary {
        require(generationId > 0L) { "Index generations must be positive" }
        require(expectedSignalSequence >= 0L) { "Signal sequences cannot be negative" }
        require(projections.size <= MAXIMUM_INDEX_BATCH_SIZE) { "Steady-state batches must remain bounded" }
        require(projections.all { it.message.lastSeenGeneration == generationId }) {
            "Steady-state rows must retain the complete generation"
        }
        require(smsCheckpoint.generationId == generationId && mmsCheckpoint.generationId == generationId) {
            "Steady-state checkpoints must belong to the complete generation"
        }
        val upserts = upsertBatchInternal(projections.map(IndexedProviderProjection::message))
        accumulateConversationProjections(
            generationId = generationId,
            projections = projections,
            persistedRows = upserts.rows,
            onlyInserted = true,
        )
        replaceCheckpoints(listOf(smsCheckpoint, mmsCheckpoint))
        val indexedCount = count()
        check(
            finishSteadyStateCommit(
                generationId = generationId,
                expectedSignalSequence = expectedSignalSequence,
                nowMillis = nowMillis,
                indexedCount = indexedCount,
            ) == 1,
        ) { "A newer provider signal superseded the steady-state commit" }
        return upserts.summary
    }

    private suspend fun upsertBatchInternal(
        entities: List<IndexedMessageEntity>,
    ): UpsertBatchResult {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        val persisted = ArrayList<PersistedUpsert>(entities.size)
        entities.forEach { candidate ->
            val existing = storedIdentity(candidate.providerKind, candidate.providerId)
            if (existing == null) {
                val insertedRowId = insertIgnoringConflict(candidate.copy(rowId = 0L))
                if (insertedRowId > 0L) {
                    inserted += 1
                    persisted += PersistedUpsert(candidate.copy(rowId = insertedRowId), UpsertDisposition.INSERTED)
                } else {
                    val raced = requireNotNull(storedIdentity(candidate.providerKind, candidate.providerId))
                    val persistedEntity = candidate.copy(rowId = raced.rowId)
                    check(updateByRowId(persistedEntity) == 1)
                    updated += 1
                    persisted += PersistedUpsert(persistedEntity, UpsertDisposition.UPDATED)
                }
            } else if (existing.syncFingerprint == candidate.syncFingerprint) {
                check(markSeen(existing.rowId, candidate.lastSeenGeneration) == 1)
                unchanged += 1
                persisted += PersistedUpsert(
                    candidate.copy(rowId = existing.rowId),
                    UpsertDisposition.UNCHANGED,
                )
            } else {
                val persistedEntity = candidate.copy(rowId = existing.rowId)
                check(updateByRowId(persistedEntity) == 1)
                updated += 1
                persisted += PersistedUpsert(persistedEntity, UpsertDisposition.UPDATED)
            }
        }
        return UpsertBatchResult(IndexUpsertSummary(inserted, updated, unchanged), persisted)
    }

    private suspend fun accumulateConversationProjections(
        generationId: Long,
        projections: List<IndexedProviderProjection>,
        persistedRows: List<PersistedUpsert>,
        onlyInserted: Boolean,
    ) {
        check(projections.size == persistedRows.size) { "Every projection must resolve to one persisted message" }
        projections.zip(persistedRows)
            .asSequence()
            .filter { (_, persisted) -> !onlyInserted || persisted.disposition == UpsertDisposition.INSERTED }
            .groupBy { (_, persisted) -> persisted.entity.providerThreadId }
            .forEach { (providerThreadId, rows) ->
                val stored = storedConversation(providerThreadId)
                val startsGeneration = stored?.lastSeenGeneration != generationId
                if (startsGeneration) deleteConversationParticipants(providerThreadId)

                var summary: IndexedConversationEntity? = if (startsGeneration) null else stored
                var participantsTruncated = summary?.participantsTruncated == true
                val participants = LinkedHashSet<String>()
                if (!startsGeneration) {
                    participants += storedParticipantAddresses(
                        providerThreadId,
                        generationId,
                        MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS,
                    )
                }
                rows.forEach { (projection, persisted) ->
                    summary = summary.accumulate(persisted.entity, generationId)
                    participantsTruncated = participantsTruncated || projection.participantsTruncated
                    projection.participantAddresses.forEach { address ->
                        if (participants.size < MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS) {
                            participants += address
                        } else if (address !in participants) {
                            participantsTruncated = true
                        }
                    }
                }
                participants.forEach { address ->
                    insertParticipantIgnoringConflict(
                        IndexedConversationParticipantEntity(
                            providerThreadId = providerThreadId,
                            address = address,
                            lastSeenGeneration = generationId,
                        ),
                    )
                }
                val participantCount = participantCount(providerThreadId, generationId)
                putConversation(
                    requireNotNull(summary).copy(
                        indexedParticipantCount = participantCount,
                        participantsTruncated = participantsTruncated,
                    ),
                )
            }
    }

    @Query("SELECT COUNT(*) FROM indexed_messages")
    abstract suspend fun count(): Long

    @Query(
        """
        SELECT * FROM indexed_messages
        WHERE row_id = :rowId
        LIMIT 1
        """,
    )
    abstract suspend fun byLocalRowId(rowId: Long): IndexedMessageEntity?

    @Query(
        """
        SELECT * FROM indexed_messages
        WHERE provider_kind = :providerKind AND provider_id = :providerId
        LIMIT 1
        """,
    )
    abstract suspend fun byProviderIdentity(
        providerKind: Int,
        providerId: Long,
    ): IndexedMessageEntity?

    /** Resolves and reads an exact bounded timeline window from one consistent database snapshot. */
    @Transaction
    open suspend fun anchorWindow(
        localRowId: Long,
        providerKind: Int,
        providerId: Long,
        halfWindow: Int,
    ): StoredAnchorWindow? {
        require(localRowId > 0L) { "Anchor row IDs must be positive" }
        require(providerKind in ProviderKindCode.SMS..ProviderKindCode.MMS) {
            "Only SMS and MMS provider kinds can identify an anchor"
        }
        require(providerId > 0L) { "Provider message IDs must be positive" }
        require(halfWindow in 0..MAXIMUM_ANCHOR_HALF_WINDOW) {
            "Anchor half-window is outside the reviewed bound"
        }

        val local = byLocalRowId(localRowId)
        val localMatches = local?.let { candidate ->
            candidate.providerKind == providerKind && candidate.providerId == providerId
        } == true
        val resolved = if (localMatches) {
            local
        } else {
            byProviderIdentity(providerKind, providerId)
        } ?: return null
        val newer = newerThanAnchor(
            providerThreadId = resolved.providerThreadId,
            anchorTimestampMillis = resolved.timestampMillis,
            anchorRowId = resolved.rowId,
            limit = halfWindow,
        ).asReversed()
        val older = olderThanAnchor(
            providerThreadId = resolved.providerThreadId,
            anchorTimestampMillis = resolved.timestampMillis,
            anchorRowId = resolved.rowId,
            limit = halfWindow,
        )
        return StoredAnchorWindow(
            anchor = resolved,
            newer = newer,
            older = older,
            reResolvedAfterRebuild = !localMatches,
        )
    }

    @Query(
        """
        SELECT provider_id, sync_fingerprint, last_seen_generation
        FROM indexed_messages
        WHERE provider_kind = :providerKind AND provider_id IN (:providerIds)
        """,
    )
    abstract suspend fun syncStates(
        providerKind: Int,
        providerIds: List<Long>,
    ): List<StoredSyncState>

    @Query("SELECT * FROM indexed_messages WHERE row_id IN (:rowIds)")
    abstract suspend fun messagesByLocalRowIds(rowIds: List<Long>): List<IndexedMessageEntity>

    @Query(
        """
        SELECT rowid
        FROM indexed_messages_fts
        WHERE indexed_messages_fts MATCH :matchExpression
        ORDER BY rowid ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchCandidateRowIds(
        matchExpression: String,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT rowid
        FROM indexed_messages_fts
        WHERE indexed_messages_fts MATCH :matchExpression
          AND rowid > :afterRowId
        ORDER BY rowid ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchCandidateRowIdsAfterLocalRowId(
        matchExpression: String,
        afterRowId: Long,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT row_id, timestamp_ms
        FROM indexed_messages
        WHERE row_id IN (:rowIds)
        """,
    )
    abstract suspend fun searchOrdersByLocalRowIds(rowIds: List<Long>): List<StoredSearchOrder>

    @Query(
        """
        SELECT row_id, timestamp_ms
        FROM indexed_messages INDEXED BY index_indexed_messages_timestamp_ms_row_id
        WHERE row_id NOT IN (:excludedRowIds)
        ORDER BY timestamp_ms DESC, row_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun newestGlobalOrderOutside(
        excludedRowIds: List<Long>,
    ): StoredSearchOrder?

    @Query(
        """
        SELECT row_id, timestamp_ms
        FROM indexed_messages INDEXED BY index_indexed_messages_timestamp_ms_row_id
        WHERE row_id NOT IN (:excludedRowIds)
          AND (
            timestamp_ms < :beforeTimestampMillis OR
            (timestamp_ms = :beforeTimestampMillis AND row_id < :beforeRowId)
          )
        ORDER BY timestamp_ms DESC, row_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun newestGlobalOrderOutsideAfter(
        excludedRowIds: List<Long>,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
    ): StoredSearchOrder?

    /** Merges FTS4 segment trees after a verified generation; content and row IDs are unchanged. */
    @Query("INSERT INTO indexed_messages_fts(indexed_messages_fts) VALUES('optimize')")
    abstract suspend fun optimizeFullTextIndex()

    @Query(
        """
        SELECT indexed_messages.row_id
        FROM indexed_messages_fts
        CROSS JOIN indexed_messages
          ON indexed_messages.row_id = indexed_messages_fts.rowid
        WHERE indexed_messages_fts MATCH :matchExpression
        ORDER BY indexed_messages.timestamp_ms DESC, indexed_messages.row_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchGlobalFirstRowIds(
        matchExpression: String,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT indexed_messages.row_id
        FROM indexed_messages_fts
        CROSS JOIN indexed_messages
          ON indexed_messages.row_id = indexed_messages_fts.rowid
        WHERE indexed_messages_fts MATCH :matchExpression
          AND (
            indexed_messages.timestamp_ms < :beforeTimestampMillis OR
            (indexed_messages.timestamp_ms = :beforeTimestampMillis AND indexed_messages.row_id < :beforeRowId)
          )
        ORDER BY indexed_messages.timestamp_ms DESC, indexed_messages.row_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchGlobalAfterRowIds(
        matchExpression: String,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT indexed_messages.row_id
        FROM indexed_messages_fts
        CROSS JOIN indexed_messages
          ON indexed_messages.row_id = indexed_messages_fts.rowid
        WHERE indexed_messages_fts MATCH :matchExpression
          AND indexed_messages.provider_thread_id = :providerThreadId
        ORDER BY indexed_messages.timestamp_ms DESC, indexed_messages.row_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchThreadFirstRowIds(
        matchExpression: String,
        providerThreadId: Long,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT indexed_messages.row_id
        FROM indexed_messages_fts
        CROSS JOIN indexed_messages
          ON indexed_messages.row_id = indexed_messages_fts.rowid
        WHERE indexed_messages_fts MATCH :matchExpression
          AND indexed_messages.provider_thread_id = :providerThreadId
          AND (
            indexed_messages.timestamp_ms < :beforeTimestampMillis OR
            (indexed_messages.timestamp_ms = :beforeTimestampMillis AND indexed_messages.row_id < :beforeRowId)
          )
        ORDER BY indexed_messages.timestamp_ms DESC, indexed_messages.row_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchThreadAfterRowIds(
        matchExpression: String,
        providerThreadId: Long,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
        limit: Int,
    ): List<Long>

    @Query(
        """
        SELECT * FROM indexed_messages
        WHERE provider_thread_id = :providerThreadId
          AND (
            timestamp_ms > :anchorTimestampMillis OR
            (timestamp_ms = :anchorTimestampMillis AND row_id > :anchorRowId)
          )
        ORDER BY timestamp_ms ASC, row_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun newerThanAnchor(
        providerThreadId: Long,
        anchorTimestampMillis: Long,
        anchorRowId: Long,
        limit: Int,
    ): List<IndexedMessageEntity>

    @Query(
        """
        SELECT * FROM indexed_messages
        WHERE provider_thread_id = :providerThreadId
          AND (
            timestamp_ms < :anchorTimestampMillis OR
            (timestamp_ms = :anchorTimestampMillis AND row_id < :anchorRowId)
          )
        ORDER BY timestamp_ms DESC, row_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun olderThanAnchor(
        providerThreadId: Long,
        anchorTimestampMillis: Long,
        anchorRowId: Long,
        limit: Int,
    ): List<IndexedMessageEntity>

    @Query("DELETE FROM indexed_messages WHERE last_seen_generation != :generationId")
    internal abstract suspend fun deleteNotSeenInGeneration(generationId: Long): Int

    @Query("DELETE FROM indexed_messages")
    internal abstract suspend fun deleteAll(): Int
}

private fun IndexedConversationEntity?.accumulate(
    message: IndexedMessageEntity,
    generationId: Long,
): IndexedConversationEntity {
    val snippet = (message.body?.takeIf(String::isNotBlank) ?: message.subject?.takeIf(String::isNotBlank))
        ?.take(MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS)
    if (this == null) {
        return IndexedConversationEntity(
            providerThreadId = message.providerThreadId,
            latestRowId = message.rowId,
            latestProviderKind = message.providerKind,
            latestProviderId = message.providerId,
            latestTimestampMillis = message.timestampMillis,
            latestSentTimestampMillis = message.sentTimestampMillis,
            latestDirection = message.direction,
            latestMessageBox = message.messageBox,
            latestMessageStatus = message.messageStatus,
            latestSubscriptionId = message.subscriptionId,
            latestSenderAddress = message.senderAddress,
            latestSnippet = snippet,
            latestAttachmentCount = message.attachmentCount,
            latestAttachmentTypeSummary = message.attachmentTypeSummary,
            latestIsRead = message.isRead,
            indexedMessageCount = 1L,
            indexedUnreadCount = if (message.isRead) 0L else 1L,
            indexedParticipantCount = 0,
            participantsTruncated = false,
            lastSeenGeneration = generationId,
        )
    }
    val candidateIsLatest = message.timestampMillis > latestTimestampMillis ||
        (message.timestampMillis == latestTimestampMillis && message.rowId > latestRowId)
    return if (candidateIsLatest) {
        copy(
            latestRowId = message.rowId,
            latestProviderKind = message.providerKind,
            latestProviderId = message.providerId,
            latestTimestampMillis = message.timestampMillis,
            latestSentTimestampMillis = message.sentTimestampMillis,
            latestDirection = message.direction,
            latestMessageBox = message.messageBox,
            latestMessageStatus = message.messageStatus,
            latestSubscriptionId = message.subscriptionId,
            latestSenderAddress = message.senderAddress,
            latestSnippet = snippet,
            latestAttachmentCount = message.attachmentCount,
            latestAttachmentTypeSummary = message.attachmentTypeSummary,
            latestIsRead = message.isRead,
            indexedMessageCount = indexedMessageCount + 1L,
            indexedUnreadCount = indexedUnreadCount + if (message.isRead) 0L else 1L,
        )
    } else {
        copy(
            indexedMessageCount = indexedMessageCount + 1L,
            indexedUnreadCount = indexedUnreadCount + if (message.isRead) 0L else 1L,
        )
    }
}
