// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.aurorasms.core.state.AppearanceLimit
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileRepository
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.AppearanceStorageOperation
import org.aurorasms.core.state.MAX_APPEARANCE_PROFILE_COUNT
import org.aurorasms.core.state.NewAppearanceProfile

class RoomAppearanceProfileRepository internal constructor(
    private val database: AuroraStateDatabase,
    private val dao: AppearanceProfileDao,
    private val overrideDao: AppearanceOverrideDao,
) : AppearanceProfileRepository {
    constructor(database: AuroraStateDatabase) : this(
        database,
        database.appearanceProfileDao(),
        database.appearanceOverrideDao(),
    )

    override val snapshots: Flow<AppearanceSnapshot> = dao.observeSelection()
        .map { loadSnapshotFailClosed() }
        .distinctUntilChanged()
        .onStart { emit(AppearanceSnapshot.Empty) }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            when (failure) {
                is SQLiteException,
                is SecurityException,
                is IllegalArgumentException,
                is IllegalStateException,
                is CorruptAppearanceStateException,
                -> emit(AppearanceSnapshot.Empty)
                else -> throw failure
            }
        }

    override fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?> {
        val stored = when (scope) {
            is AppearanceScope.Screen -> overrideDao
                .observeScreenOverride(scope.screen.storageCode)
                .map { entity -> entity?.toDomainOrCorrupt() }
            is AppearanceScope.Conversation -> overrideDao
                .observeConversationOverride(scope.participantSetKey.toStorageValue())
                .map { entity -> entity?.toDomainOrCorrupt(scope) }
        }
        return stored
            .distinctUntilChanged()
            .catch { failure ->
                if (failure is CancellationException) throw failure
                when (failure) {
                    is SQLiteException,
                    is SecurityException,
                    is IllegalArgumentException,
                    is IllegalStateException,
                    is CorruptAppearanceStateException,
                    -> emit(null)
                    else -> throw failure
                }
            }
    }

    override suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride> = mutate(AppearanceStorageOperation.SET_OVERRIDE) {
        database.withTransaction {
            val revisionSequence = loadValidatedOverrideSequence()
            when (scope) {
                is AppearanceScope.Screen -> setScreenOverride(
                    scope = scope,
                    profileId = profileId,
                    expectedRevision = expectedRevision,
                    revisionSequence = revisionSequence,
                )
                is AppearanceScope.Conversation -> setConversationOverride(
                    scope = scope,
                    profileId = profileId,
                    expectedRevision = expectedRevision,
                    revisionSequence = revisionSequence,
                )
            }
        }
    }

    override suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> = mutate(AppearanceStorageOperation.RESET_OVERRIDE) {
        database.withTransaction {
            loadValidatedOverrideSequence()
            when (scope) {
                is AppearanceScope.Screen -> resetScreenOverride(scope, expectedRevision)
                is AppearanceScope.Conversation -> resetConversationOverride(scope, expectedRevision)
            }
        }
    }

    override suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> = mutate(AppearanceStorageOperation.CREATE) {
        database.withTransaction {
            val currentSnapshot = loadValidatedSnapshot()
            if (currentSnapshot.profiles.size >= MAX_APPEARANCE_PROFILE_COUNT) {
                return@withTransaction AppearanceRepositoryResult.LimitExceeded(
                    AppearanceLimit.PROFILE_COUNT,
                )
            }
            val entity = profile.toEntity()
            val profileId = dao.insertProfile(entity)
            if (profileId <= 0L) throw CorruptAppearanceStateException()
            val stored = entity.copy(profileId = profileId).toDomainOrCorrupt()
            if (activate) {
                setActiveOrCorrupt(profileId)
            } else {
                bumpSnapshotOrCorrupt()
            }
            AppearanceRepositoryResult.Success(stored)
        }
    }

    override suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> = mutate(AppearanceStorageOperation.UPDATE) {
        database.withTransaction {
            val currentSnapshot = loadValidatedSnapshot()
            val current = currentSnapshot.profiles.singleOrNull { it.id == edit.id }
                ?: return@withTransaction AppearanceRepositoryResult.NotFound
            if (current.revision != expectedRevision) {
                return@withTransaction AppearanceRepositoryResult.StaleWrite
            }
            if (edit.updatedTimestampMillis < current.updatedTimestampMillis) {
                return@withTransaction AppearanceRepositoryResult.InvalidTimestamp
            }
            val updatedEntity = try {
                edit.toUpdatedEntity(current)
            } catch (_: IllegalArgumentException) {
                throw CorruptAppearanceStateException()
            }
            val affected = dao.updateEntityIfRevision(updatedEntity, expectedRevision.value)
            if (affected != 1) throw CorruptAppearanceStateException()
            val updated = updatedEntity.toDomainOrCorrupt()
            if (activate) {
                setActiveOrCorrupt(updated.id.value)
            } else {
                bumpSnapshotOrCorrupt()
            }
            AppearanceRepositoryResult.Success(updated)
        }
    }

    override suspend fun activate(
        id: AppearanceProfileId,
    ): AppearanceRepositoryResult<Unit> = mutate(AppearanceStorageOperation.ACTIVATE) {
        database.withTransaction {
            val snapshot = loadValidatedSnapshot()
            if (snapshot.profiles.none { it.id == id }) {
                return@withTransaction AppearanceRepositoryResult.NotFound
            }
            setActiveOrCorrupt(id.value)
            AppearanceRepositoryResult.Success(Unit)
        }
    }

    override suspend fun resetActive(): AppearanceRepositoryResult<Unit> =
        mutate(AppearanceStorageOperation.RESET_ACTIVE) {
            database.withTransaction {
                loadValidatedSnapshot()
                setActiveOrCorrupt(null)
                AppearanceRepositoryResult.Success(Unit)
            }
        }

    override suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit> = mutate(AppearanceStorageOperation.DELETE) {
        database.withTransaction {
            val snapshot = loadValidatedSnapshot()
            val current = snapshot.profiles.singleOrNull { it.id == id }
            if (current == null) {
                return@withTransaction AppearanceRepositoryResult.NotFound
            }
            if (current.revision != expectedRevision) {
                return@withTransaction AppearanceRepositoryResult.StaleWrite
            }
            if (dao.deleteProfile(id.value, expectedRevision.value) != 1) {
                throw CorruptAppearanceStateException()
            }
            // ON DELETE SET NULL handles an active profile before this revision becomes observable.
            bumpSnapshotOrCorrupt()
            AppearanceRepositoryResult.Success(Unit)
        }
    }

    private suspend fun loadValidatedOverrideSequence(): AppearanceOverrideSequenceEntity {
        if (overrideDao.revisionSequenceCount() != 1) throw CorruptAppearanceStateException()
        val sequence = overrideDao.loadRevisionSequence() ?: throw CorruptAppearanceStateException()
        if (
            sequence.singletonId != APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID ||
            sequence.lastAllocatedRevision < INITIAL_APPEARANCE_OVERRIDE_SEQUENCE ||
            sequence.lastAllocatedRevision < (overrideDao.maximumStoredOverrideRevision() ?: 0L)
        ) {
            throw CorruptAppearanceStateException()
        }
        return sequence
    }

    private suspend fun allocateNextOverrideRevision(
        sequence: AppearanceOverrideSequenceEntity,
    ): AppearanceOverrideRevision {
        if (sequence.lastAllocatedRevision == Long.MAX_VALUE) {
            throw CorruptAppearanceStateException()
        }
        val nextRevision = sequence.lastAllocatedRevision + 1L
        if (
            overrideDao.updateRevisionSequenceIfUnchanged(
                expectedRevision = sequence.lastAllocatedRevision,
                newRevision = nextRevision,
            ) != 1
        ) {
            throw CorruptAppearanceStateException()
        }
        return AppearanceOverrideRevision(nextRevision)
    }

    private suspend fun setScreenOverride(
        scope: AppearanceScope.Screen,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
        revisionSequence: AppearanceOverrideSequenceEntity,
    ): AppearanceRepositoryResult<AppearanceOverride> {
        val currentEntity = overrideDao.findScreenOverride(scope.screen.storageCode)
        val current = currentEntity?.toDomainOrCorrupt()
        if (!current.matchesExpected(expectedRevision)) {
            return AppearanceRepositoryResult.StaleWrite
        }
        val target = dao.findProfile(profileId.value) ?: return AppearanceRepositoryResult.NotFound
        target.toDomainOrCorrupt()

        if (currentEntity == null) {
            val revision = allocateNextOverrideRevision(revisionSequence)
            val created = AppearanceScreenOverrideEntity(
                screenCode = scope.screen.storageCode,
                profileId = profileId.value,
                revision = revision.value,
            )
            overrideDao.insertScreenOverride(created)
            return AppearanceRepositoryResult.Success(created.toDomainOrCorrupt())
        }
        if (currentEntity.profileId == profileId.value) {
            return AppearanceRepositoryResult.Success(checkNotNull(current))
        }
        if (currentEntity.revision == Long.MAX_VALUE) throw CorruptAppearanceStateException()
        val revision = allocateNextOverrideRevision(revisionSequence)
        val updated = currentEntity.copy(
            profileId = profileId.value,
            revision = revision.value,
        )
        if (
            overrideDao.updateScreenOverrideIfRevision(
                screenCode = updated.screenCode,
                profileId = updated.profileId,
                newRevision = updated.revision,
                expectedRevision = currentEntity.revision,
            ) != 1
        ) {
            throw CorruptAppearanceStateException()
        }
        return AppearanceRepositoryResult.Success(updated.toDomainOrCorrupt())
    }

    private suspend fun setConversationOverride(
        scope: AppearanceScope.Conversation,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
        revisionSequence: AppearanceOverrideSequenceEntity,
    ): AppearanceRepositoryResult<AppearanceOverride> {
        val participantSetKey = scope.participantSetKey.toStorageValue()
        val currentEntity = overrideDao.findConversationOverride(participantSetKey)
        val current = currentEntity?.toDomainOrCorrupt(scope)
        if (!current.matchesExpected(expectedRevision)) {
            return AppearanceRepositoryResult.StaleWrite
        }
        val target = dao.findProfile(profileId.value) ?: return AppearanceRepositoryResult.NotFound
        target.toDomainOrCorrupt()

        if (currentEntity == null) {
            val revision = allocateNextOverrideRevision(revisionSequence)
            val created = AppearanceConversationOverrideEntity(
                participantSetKey = participantSetKey,
                providerThreadId = scope.providerThreadId.value,
                profileId = profileId.value,
                revision = revision.value,
            )
            overrideDao.insertConversationOverride(created)
            return AppearanceRepositoryResult.Success(created.toDomainOrCorrupt(scope))
        }
        if (
            currentEntity.profileId == profileId.value &&
            currentEntity.providerThreadId == scope.providerThreadId.value
        ) {
            return AppearanceRepositoryResult.Success(checkNotNull(current))
        }
        if (currentEntity.revision == Long.MAX_VALUE) throw CorruptAppearanceStateException()
        val revision = allocateNextOverrideRevision(revisionSequence)
        val updated = currentEntity.copy(
            providerThreadId = scope.providerThreadId.value,
            profileId = profileId.value,
            revision = revision.value,
        )
        if (
            overrideDao.updateConversationOverrideIfRevision(
                participantSetKey = updated.participantSetKey,
                providerThreadId = updated.providerThreadId,
                profileId = updated.profileId,
                newRevision = updated.revision,
                expectedRevision = currentEntity.revision,
            ) != 1
        ) {
            throw CorruptAppearanceStateException()
        }
        return AppearanceRepositoryResult.Success(updated.toDomainOrCorrupt(scope))
    }

    private suspend fun resetScreenOverride(
        scope: AppearanceScope.Screen,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> {
        val current = overrideDao.findScreenOverride(scope.screen.storageCode)?.toDomainOrCorrupt()
        if (!current.matchesExpected(expectedRevision)) {
            return AppearanceRepositoryResult.StaleWrite
        }
        if (current == null) return AppearanceRepositoryResult.Success(Unit)
        if (
            overrideDao.deleteScreenOverrideIfRevision(
                screenCode = scope.screen.storageCode,
                expectedRevision = current.revision.value,
            ) != 1
        ) {
            throw CorruptAppearanceStateException()
        }
        return AppearanceRepositoryResult.Success(Unit)
    }

    private suspend fun resetConversationOverride(
        scope: AppearanceScope.Conversation,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> {
        val participantSetKey = scope.participantSetKey.toStorageValue()
        val current = overrideDao.findConversationOverride(participantSetKey)
            ?.toDomainOrCorrupt(scope)
        if (!current.matchesExpected(expectedRevision)) {
            return AppearanceRepositoryResult.StaleWrite
        }
        if (current == null) return AppearanceRepositoryResult.Success(Unit)
        if (
            overrideDao.deleteConversationOverrideIfRevision(
                participantSetKey = participantSetKey,
                expectedRevision = current.revision.value,
            ) != 1
        ) {
            throw CorruptAppearanceStateException()
        }
        return AppearanceRepositoryResult.Success(Unit)
    }

    private suspend fun loadSnapshotFailClosed(): AppearanceSnapshot = try {
        database.withTransaction { loadValidatedSnapshot() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SQLiteException) {
        AppearanceSnapshot.Empty
    } catch (_: SecurityException) {
        AppearanceSnapshot.Empty
    } catch (_: IllegalArgumentException) {
        AppearanceSnapshot.Empty
    } catch (_: IllegalStateException) {
        AppearanceSnapshot.Empty
    } catch (_: CorruptAppearanceStateException) {
        AppearanceSnapshot.Empty
    }

    private suspend fun loadValidatedSnapshot(): AppearanceSnapshot {
        if (dao.selectionCount() != 1) throw CorruptAppearanceStateException()
        val selection = dao.loadSelection() ?: throw CorruptAppearanceStateException()
        if (
            selection.singletonId != APPEARANCE_SELECTION_SINGLETON_ID ||
            selection.snapshotRevision <= 0L ||
            selection.snapshotRevision == Long.MAX_VALUE ||
            selection.activeProfileId?.let { it <= 0L } == true
        ) {
            throw CorruptAppearanceStateException()
        }
        val profiles = try {
            dao.loadProfiles().map(AppearanceProfileEntity::toDomain)
        } catch (_: IllegalArgumentException) {
            throw CorruptAppearanceStateException()
        } catch (_: IllegalStateException) {
            throw CorruptAppearanceStateException()
        }
        return try {
            AppearanceSnapshot(
                profiles = profiles,
                activeProfileId = selection.activeProfileId?.let(::AppearanceProfileId),
                revision = selection.snapshotRevision,
            )
        } catch (_: IllegalArgumentException) {
            throw CorruptAppearanceStateException()
        } catch (_: IllegalStateException) {
            throw CorruptAppearanceStateException()
        }
    }

    private suspend fun setActiveOrCorrupt(profileId: Long?) {
        if (dao.setActiveProfile(profileId) != 1) throw CorruptAppearanceStateException()
    }

    private suspend fun bumpSnapshotOrCorrupt() {
        if (dao.bumpSnapshotRevision() != 1) throw CorruptAppearanceStateException()
    }

    private fun AppearanceProfileEntity.toDomainOrCorrupt(): AppearanceProfile = try {
        toDomain()
    } catch (_: IllegalArgumentException) {
        throw CorruptAppearanceStateException()
    } catch (_: IllegalStateException) {
        throw CorruptAppearanceStateException()
    }

    private fun AppearanceScreenOverrideEntity.toDomainOrCorrupt(): AppearanceOverride = try {
        toDomain()
    } catch (_: IllegalArgumentException) {
        throw CorruptAppearanceStateException()
    } catch (_: IllegalStateException) {
        throw CorruptAppearanceStateException()
    }

    private fun AppearanceConversationOverrideEntity.toDomainOrCorrupt(
        requestedScope: AppearanceScope.Conversation? = null,
    ): AppearanceOverride = try {
        toDomain(requestedScope)
    } catch (_: IllegalArgumentException) {
        throw CorruptAppearanceStateException()
    } catch (_: IllegalStateException) {
        throw CorruptAppearanceStateException()
    }

    private suspend fun <T> mutate(
        operation: AppearanceStorageOperation,
        block: suspend () -> AppearanceRepositoryResult<T>,
    ): AppearanceRepositoryResult<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SQLiteConstraintException) {
        AppearanceRepositoryResult.Conflict
    } catch (_: SQLiteException) {
        AppearanceRepositoryResult.StorageFailure(operation)
    } catch (_: SecurityException) {
        AppearanceRepositoryResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        AppearanceRepositoryResult.CorruptData
    } catch (_: IllegalStateException) {
        AppearanceRepositoryResult.StorageFailure(operation)
    } catch (_: CorruptAppearanceStateException) {
        AppearanceRepositoryResult.CorruptData
    }
}

private suspend fun AppearanceProfileDao.updateEntityIfRevision(
    entity: AppearanceProfileEntity,
    expectedRevision: Long,
): Int = updateProfileIfRevision(
    profileId = entity.profileId,
    name = entity.name,
    normalizedName = entity.normalizedName,
    profileSchemaVersion = entity.profileSchemaVersion,
    paletteCode = entity.paletteCode,
    hueDegrees = entity.hueDegrees,
    rowDensityCode = entity.rowDensityCode,
    avatarMaskCode = entity.avatarMaskCode,
    navigationStyleCode = entity.navigationStyleCode,
    bubbleGeometryCode = entity.bubbleGeometryCode,
    reducedMotion = entity.reducedMotion,
    highContrast = entity.highContrast,
    wallpaperDimPermill = entity.wallpaperDimPermill,
    focalXPermill = entity.focalXPermill,
    focalYPermill = entity.focalYPermill,
    newRevision = entity.revision,
    updatedTimestampMillis = entity.updatedTimestampMillis,
    expectedRevision = expectedRevision,
)

private fun AppearanceOverride?.matchesExpected(
    expectedRevision: AppearanceOverrideRevision?,
): Boolean = when (this) {
    null -> expectedRevision == null
    else -> revision == expectedRevision
}

private class CorruptAppearanceStateException : RuntimeException()
