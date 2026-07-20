// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.BlockedSenderKey
import org.aurorasms.core.state.MAXIMUM_SPAM_SAFETY_DECISIONS
import org.aurorasms.core.state.SpamClassification
import org.aurorasms.core.state.SpamSafetyDecision
import org.aurorasms.core.state.SpamSafetyRepository
import org.aurorasms.core.state.SpamSafetyRepositoryResult
import org.aurorasms.core.state.SpamSafetyRevision
import org.aurorasms.core.state.SpamSafetyScope
import org.aurorasms.core.state.SpamSafetySnapshot
import org.aurorasms.core.state.SpamSafetyStorageOperation

class RoomSpamSafetyRepository internal constructor(
    private val database: AuroraStateDatabase,
    private val dao: SpamSafetyDecisionDao,
) : SpamSafetyRepository {
    constructor(database: AuroraStateDatabase) : this(database, database.spamSafetyDecisionDao())

    override val snapshots: Flow<SpamSafetySnapshot> = dao.observeAll()
        .map { entities -> SpamSafetySnapshot(entities.map(SpamSafetyDecisionEntity::toDomain)) }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SpamSafetySnapshot.Unavailable)
        }
        .conflate()

    override suspend fun read(
        scope: SpamSafetyScope,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision> = try {
        val entity = dao.find(scope.participantSetKey.toStorageValue())
            ?: return SpamSafetyRepositoryResult.NotFound
        SpamSafetyRepositoryResult.Success(entity.toDomain(scope))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SQLiteException) {
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.READ)
    } catch (_: SecurityException) {
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.READ)
    } catch (_: IllegalArgumentException) {
        SpamSafetyRepositoryResult.CorruptData
    } catch (_: IllegalStateException) {
        SpamSafetyRepositoryResult.CorruptData
    }

    override suspend fun set(
        scope: SpamSafetyScope,
        classification: SpamClassification,
        blocked: Boolean,
        expectedRevision: SpamSafetyRevision?,
        updatedTimestampMillis: Long,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision?> {
        if (updatedTimestampMillis < 0L) return SpamSafetyRepositoryResult.InvalidTimestamp
        if (blocked && scope.singleSenderKey == null) {
            return SpamSafetyRepositoryResult.InvalidBlockTarget
        }
        return try {
            database.withTransaction {
                val key = scope.participantSetKey.toStorageValue()
                val stored = dao.find(key)
                if (classification == SpamClassification.NEUTRAL && !blocked) {
                    return@withTransaction when {
                        stored == null && expectedRevision == null ->
                            SpamSafetyRepositoryResult.Success(null)
                        stored == null || expectedRevision == null ||
                            stored.revision != expectedRevision.value ->
                            SpamSafetyRepositoryResult.StaleWrite
                        updatedTimestampMillis <= stored.updatedTimestampMillis ->
                            SpamSafetyRepositoryResult.InvalidTimestamp
                        dao.deleteIfRevision(key, expectedRevision.value) == 1 ->
                            SpamSafetyRepositoryResult.Success(null)
                        else -> SpamSafetyRepositoryResult.StaleWrite
                    }
                }
                when {
                    stored == null && expectedRevision == null -> {
                        if (dao.count() >= MAXIMUM_SPAM_SAFETY_DECISIONS) {
                            SpamSafetyRepositoryResult.LimitReached
                        } else {
                            val decision = SpamSafetyDecision(
                                scope = scope,
                                classification = classification,
                                blocked = blocked,
                                revision = SpamSafetyRevision(1L),
                                updatedTimestampMillis = updatedTimestampMillis,
                            )
                            dao.insert(decision.toEntity())
                            SpamSafetyRepositoryResult.Success(decision)
                        }
                    }
                    stored == null || expectedRevision == null ||
                        stored.revision != expectedRevision.value ->
                        SpamSafetyRepositoryResult.StaleWrite
                    updatedTimestampMillis <= stored.updatedTimestampMillis ->
                        SpamSafetyRepositoryResult.InvalidTimestamp
                    else -> {
                        val decision = SpamSafetyDecision(
                            scope = scope,
                            classification = classification,
                            blocked = blocked,
                            revision = SpamSafetyRevision(stored.revision + 1L),
                            updatedTimestampMillis = updatedTimestampMillis,
                        )
                        val changed = dao.updateIfRevision(
                            key = key,
                            threadId = scope.providerThreadId.value,
                            senderKey = scope.singleSenderKey?.toStorageValue(),
                            classificationCode = classification.storageCode,
                            blocked = blocked,
                            newRevision = decision.revision.value,
                            updated = updatedTimestampMillis,
                            expectedRevision = expectedRevision.value,
                        )
                        if (changed == 1) {
                            SpamSafetyRepositoryResult.Success(decision)
                        } else {
                            SpamSafetyRepositoryResult.StaleWrite
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            SpamSafetyRepositoryResult.StaleWrite
        } catch (_: SQLiteException) {
            SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.WRITE)
        } catch (_: SecurityException) {
            SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.WRITE)
        } catch (_: IllegalArgumentException) {
            SpamSafetyRepositoryResult.CorruptData
        } catch (_: IllegalStateException) {
            SpamSafetyRepositoryResult.CorruptData
        }
    }

    override suspend fun isSenderBlocked(
        sender: ParticipantAddress,
    ): SpamSafetyRepositoryResult<Boolean> = try {
        SpamSafetyRepositoryResult.Success(
            dao.isSenderBlocked(BlockedSenderKey.fromSender(sender).toStorageValue()),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SQLiteException) {
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.BLOCK_LOOKUP)
    } catch (_: SecurityException) {
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.BLOCK_LOOKUP)
    } catch (_: IllegalArgumentException) {
        SpamSafetyRepositoryResult.CorruptData
    } catch (_: IllegalStateException) {
        SpamSafetyRepositoryResult.CorruptData
    }

    override fun toString(): String = "RoomSpamSafetyRepository(content=REDACTED)"
}
