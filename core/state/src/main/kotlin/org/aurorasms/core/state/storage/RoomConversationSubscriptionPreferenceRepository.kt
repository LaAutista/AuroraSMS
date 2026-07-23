// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.ConversationSubscriptionStorageOperation

class RoomConversationSubscriptionPreferenceRepository internal constructor(
    private val database: AuroraStateDatabase,
    private val dao: ConversationSubscriptionPreferenceDao,
) : ConversationSubscriptionPreferenceRepository {
    constructor(database: AuroraStateDatabase) : this(
        database,
        database.conversationSubscriptionPreferenceDao(),
    )

    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> = try {
        val entity = dao.find(scope.participantSetKey.toStorageValue())
            ?: return ConversationSubscriptionRepositoryResult.NotFound
        ConversationSubscriptionRepositoryResult.Success(entity.toDomain(scope))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SQLiteException) {
        ConversationSubscriptionRepositoryResult.StorageFailure(
            ConversationSubscriptionStorageOperation.READ,
        )
    } catch (_: SecurityException) {
        ConversationSubscriptionRepositoryResult.StorageFailure(
            ConversationSubscriptionStorageOperation.READ,
        )
    } catch (_: IllegalArgumentException) {
        ConversationSubscriptionRepositoryResult.CorruptData
    } catch (_: IllegalStateException) {
        ConversationSubscriptionRepositoryResult.CorruptData
    }

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> {
        if (updatedTimestampMillis < 0L) {
            return ConversationSubscriptionRepositoryResult.InvalidTimestamp
        }
        return try {
            database.withTransaction {
                val stored = dao.find(scope.participantSetKey.toStorageValue())
                when {
                    stored == null && expectedRevision == null -> {
                        val preference = ConversationSubscriptionPreference(
                            scope = scope,
                            subscriptionId = subscriptionId,
                            revision = ConversationSubscriptionRevision(1L),
                            updatedTimestampMillis = updatedTimestampMillis,
                        )
                        dao.insert(preference.toEntity())
                        ConversationSubscriptionRepositoryResult.Success(preference)
                    }
                    stored == null || expectedRevision == null ||
                        stored.revision != expectedRevision.value ->
                        ConversationSubscriptionRepositoryResult.StaleWrite
                    updatedTimestampMillis <= stored.updatedTimestampMillis ->
                        ConversationSubscriptionRepositoryResult.InvalidTimestamp
                    else -> {
                        val nextRevision = ConversationSubscriptionRevision(stored.revision + 1L)
                        val updated = ConversationSubscriptionPreference(
                            scope = scope,
                            subscriptionId = subscriptionId,
                            revision = nextRevision,
                            updatedTimestampMillis = updatedTimestampMillis,
                        )
                        when (
                            dao.updateIfRevision(
                                participantSetKey = scope.participantSetKey.toStorageValue(),
                                providerThreadId = scope.providerThreadId.value,
                                subscriptionId = subscriptionId.value,
                                newRevision = nextRevision.value,
                                updatedTimestampMillis = updatedTimestampMillis,
                                expectedRevision = expectedRevision.value,
                            )
                        ) {
                            1 -> ConversationSubscriptionRepositoryResult.Success(updated)
                            0 -> ConversationSubscriptionRepositoryResult.StaleWrite
                            else -> ConversationSubscriptionRepositoryResult.CorruptData
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            ConversationSubscriptionRepositoryResult.StaleWrite
        } catch (_: SQLiteException) {
            ConversationSubscriptionRepositoryResult.StorageFailure(
                ConversationSubscriptionStorageOperation.SET,
            )
        } catch (_: SecurityException) {
            ConversationSubscriptionRepositoryResult.StorageFailure(
                ConversationSubscriptionStorageOperation.SET,
            )
        } catch (_: IllegalArgumentException) {
            ConversationSubscriptionRepositoryResult.CorruptData
        } catch (_: IllegalStateException) {
            ConversationSubscriptionRepositoryResult.CorruptData
        }
    }

    override fun toString(): String =
        "RoomConversationSubscriptionPreferenceRepository(content=REDACTED)"
}
