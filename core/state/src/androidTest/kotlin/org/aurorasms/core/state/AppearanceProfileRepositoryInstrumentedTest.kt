// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomAppearanceProfileRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceProfileRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
    }

    @After
    fun cleanDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun snapshotsStartEmptyThenCreateAndActivateAtomicallyAndSurviveReopen() = runBlocking {
        var database = openStateDatabase()
        var repository = RoomAppearanceProfileRepository(database)
        assertEquals(AppearanceSnapshot.Empty, repository.snapshots.first())
        val durableInitial = repository.snapshots.drop(1).first()
        assertTrue(durableInitial.revision > 0L)
        assertSame(AppearanceProfileValues.CanonicalDefault, durableInitial.activeValues)

        val created = repository.create(newProfile("Aurora One"), activate = true).successValue()
        val active = repository.snapshots.first { it.activeProfileId == created.id }
        assertEquals(listOf(created), active.profiles)
        assertEquals(created.values, active.activeValues)
        database.close()

        database = openStateDatabase()
        repository = RoomAppearanceProfileRepository(database)
        try {
            val reopened = repository.snapshots.first { it.activeProfileId == created.id }
            assertEquals(created, reopened.activeProfile)
        } finally {
            database.close()
        }
    }

    @Test
    fun updateUsesOptimisticRevisionAndCanActivateInSameTransaction() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val created = repository.create(newProfile("Editable"), activate = false).successValue()
            val edit = AppearanceProfileEdit(
                id = created.id,
                name = AppearanceProfileName.from("Edited"),
                values = created.values.copy(
                    palette = AppearancePalette.AMOLED_BLACK,
                    hueDegrees = 240,
                    reducedMotion = true,
                ),
                updatedTimestampMillis = 2_000L,
            )
            val updated = repository.update(
                edit = edit,
                expectedRevision = created.revision,
                activate = true,
            ).successValue()

            assertEquals(created.revision.value + 1L, updated.revision.value)
            assertEquals(AppearancePalette.AMOLED_BLACK, updated.values.palette)
            assertEquals(updated, repository.snapshots.first { it.activeProfileId == updated.id }.activeProfile)
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.update(
                    edit = edit.copy(updatedTimestampMillis = 3_000L),
                    expectedRevision = created.revision,
                ),
            )
            assertEquals(
                AppearanceRepositoryResult.InvalidTimestamp,
                repository.update(
                    edit = edit.copy(updatedTimestampMillis = 1_999L),
                    expectedRevision = updated.revision,
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun normalizedNamesConflictAndProfileCountIsBounded() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            repository.create(newProfile("Café"), activate = false).successValue()
            assertEquals(
                AppearanceRepositoryResult.Conflict,
                repository.create(newProfile("  CAFE\u0301  "), activate = false),
            )
            for (index in 2..MAX_APPEARANCE_PROFILE_COUNT) {
                repository.create(newProfile("Profile $index"), activate = false).successValue()
            }
            assertEquals(
                AppearanceRepositoryResult.LimitExceeded(AppearanceLimit.PROFILE_COUNT),
                repository.create(newProfile("One too many"), activate = false),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun resetAndDeletingActiveProfileFallBackToCanonicalWithoutDeletingOtherProfiles() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val first = repository.create(newProfile("First"), activate = true).successValue()
            val second = repository.create(newProfile("Second"), activate = false).successValue()

            repository.resetActive().successValue()
            var snapshot = repository.snapshots.first { it.activeProfileId == null && it.profiles.size == 2 }
            assertSame(AppearanceProfileValues.CanonicalDefault, snapshot.activeValues)

            repository.activate(first.id).successValue()
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.delete(first.id, AppearanceRevision(first.revision.value + 1L)),
            )
            repository.delete(first.id, first.revision).successValue()
            snapshot = repository.snapshots.first { it.activeProfileId == null && it.profiles.size == 1 }
            assertEquals(listOf(second), snapshot.profiles)
            assertSame(AppearanceProfileValues.CanonicalDefault, snapshot.activeValues)
            assertEquals(AppearanceRepositoryResult.NotFound, repository.activate(first.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun screenOverrideUsesOptimisticCreateUpdateAndReset() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val first = repository.create(newProfile("Screen first"), activate = false).successValue()
            val second = repository.create(newProfile("Screen second"), activate = false).successValue()
            val scope = AppearanceScope.Screen(AppearanceScreenScope.INBOX)

            assertEquals(null, repository.observeOverride(scope).first())
            val created = repository.setOverride(scope, first.id, expectedRevision = null).successValue()
            assertEquals(AppearanceOverrideRevision(1L), created.revision)
            assertEquals(first.id, created.profileId)
            assertEquals(created, repository.observeOverride(scope).first { it != null })
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.setOverride(scope, second.id, expectedRevision = null),
            )

            val updated = repository.setOverride(scope, second.id, created.revision).successValue()
            assertEquals(AppearanceOverrideRevision(2L), updated.revision)
            assertEquals(second.id, updated.profileId)
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.resetOverride(scope, created.revision),
            )
            repository.resetOverride(scope, updated.revision).successValue()
            assertEquals(0L, database.longValue("SELECT COUNT(*) FROM appearance_screen_overrides"))
            repository.resetOverride(scope, expectedRevision = null).successValue()
        } finally {
            database.close()
        }
    }

    @Test
    fun conversationOverrideSurvivesReopenAndFollowsFingerprintAcrossThreadRecreation() = runBlocking {
        var database = openStateDatabase()
        var repository = RoomAppearanceProfileRepository(database)
        val profile = repository.create(newProfile("Conversation profile"), activate = false).successValue()
        val originalScope = conversationScope(
            40L,
            "Beta",
            "Cafe\u0301",
        )
        val created = repository.setOverride(originalScope, profile.id, expectedRevision = null).successValue()
        database.close()

        database = openStateDatabase()
        repository = RoomAppearanceProfileRepository(database)
        try {
            val recreatedScope = conversationScope(
                99L,
                "Café",
                "Beta",
                "Beta",
            )
            val reopened = checkNotNull(
                repository.observeOverride(recreatedScope).first { it != null },
            )
            assertEquals(profile.id, reopened.profileId)
            assertEquals(recreatedScope, reopened.scope)
            assertEquals(created.revision, reopened.revision)

            val rebound = repository.setOverride(
                recreatedScope,
                profile.id,
                expectedRevision = reopened.revision,
            ).successValue()
            assertEquals(AppearanceOverrideRevision(created.revision.value + 1L), rebound.revision)
            assertEquals(
                99L,
                database.longValue(
                    "SELECT provider_thread_id FROM appearance_conversation_overrides",
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun overrideMissingTargetAndProfileDeletionFallBackWithoutTouchingOtherScopes() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        val screenEvents = Channel<Pair<Unit, AppearanceOverride?>>(Channel.UNLIMITED)
        val conversationEvents = Channel<Pair<Unit, AppearanceOverride?>>(Channel.UNLIMITED)
        val unrelatedEvents = Channel<Pair<Unit, AppearanceOverride?>>(Channel.UNLIMITED)
        val collectors = mutableListOf<kotlinx.coroutines.Job>()
        try {
            val profile = repository.create(newProfile("Assigned"), activate = false).successValue()
            val retained = repository.create(newProfile("Retained"), activate = true).successValue()
            val screen = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
            val conversation = conversationScope(51L, "synthetic@example.invalid")
            val unrelated = AppearanceScope.Screen(AppearanceScreenScope.SETTINGS)

            assertEquals(
                AppearanceRepositoryResult.NotFound,
                repository.setOverride(screen, AppearanceProfileId(Long.MAX_VALUE), null),
            )
            repository.setOverride(screen, profile.id, null).successValue()
            repository.setOverride(conversation, profile.id, null).successValue()
            val unrelatedOverride = repository.setOverride(unrelated, retained.id, null).successValue()
            val screenCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeOverride(screen).collect { screenEvents.send(Unit to it) }
            }.also(collectors::add)
            val conversationCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeOverride(conversation).collect { conversationEvents.send(Unit to it) }
            }.also(collectors::add)
            val unrelatedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeOverride(unrelated).collect { unrelatedEvents.send(Unit to it) }
            }.also(collectors::add)
            assertEquals(profile.id, withTimeout(5_000L) { screenEvents.receive().second?.profileId })
            assertEquals(profile.id, withTimeout(5_000L) { conversationEvents.receive().second?.profileId })
            assertEquals(
                unrelatedOverride,
                withTimeout(5_000L) { unrelatedEvents.receive().second },
            )

            repository.delete(profile.id, profile.revision).successValue()

            assertNull(withTimeout(5_000L) { screenEvents.receive().second })
            assertNull(withTimeout(5_000L) { conversationEvents.receive().second })
            assertNull(withTimeoutOrNull(250L) { unrelatedEvents.receive() })
            assertEquals(1L, database.longValue("SELECT COUNT(*) FROM appearance_screen_overrides"))
            assertEquals(0L, database.longValue("SELECT COUNT(*) FROM appearance_conversation_overrides"))
            assertEquals(
                retained.id.value,
                database.longValue(
                    "SELECT profile_id FROM appearance_screen_overrides " +
                        "WHERE screen_code = 'settings'",
                ),
            )
            assertEquals(retained.id, repository.snapshots.first { it.activeProfileId != null }.activeProfileId)
            screenCollector.cancel()
            conversationCollector.cancel()
            unrelatedCollector.cancel()
        } finally {
            collectors.forEach { it.cancel() }
            database.close()
        }
    }

    @Test
    fun corruptKnownOverrideFailsClosedForObservationSetAndReset() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val profile = repository.create(newProfile("Corrupt override target"), false).successValue()
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO appearance_screen_overrides(screen_code, profile_id, revision) " +
                    "VALUES('inbox', ${profile.id.value}, 0)",
            )
            val scope = AppearanceScope.Screen(AppearanceScreenScope.INBOX)

            assertNull(repository.observeOverride(scope).first())
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setOverride(scope, profile.id, expectedRevision = null),
            )
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.resetOverride(scope, expectedRevision = null),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun overrideRevisionSequencePreventsDeleteRecreateAbaWrites() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val first = repository.create(newProfile("ABA first"), false).successValue()
            val second = repository.create(newProfile("ABA second"), false).successValue()
            val scope = AppearanceScope.Screen(AppearanceScreenScope.ARCHIVE)

            val original = repository.setOverride(scope, first.id, null).successValue()
            repository.resetOverride(scope, original.revision).successValue()
            val recreated = repository.setOverride(scope, second.id, null).successValue()

            assertTrue(recreated.revision.value > original.revision.value)
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.setOverride(scope, first.id, original.revision),
            )
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.resetOverride(scope, original.revision),
            )
            assertEquals(recreated, repository.observeOverride(scope).first { it != null })
        } finally {
            database.close()
        }
    }

    @Test
    fun overrideRevisionSequencePhysicallyRejectsRollbackAndDeletion() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val profile = repository.create(newProfile("Protected sequence"), false).successValue()
            val scope = AppearanceScope.Screen(AppearanceScreenScope.ARCHIVE)
            repository.setOverride(scope, profile.id, null).successValue()
            val sqlite = database.openHelper.writableDatabase

            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "UPDATE appearance_override_revision_sequence " +
                        "SET last_allocated_revision = 0",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL("DELETE FROM appearance_override_revision_sequence")
            }
            assertEquals(
                1L,
                database.longValue(
                    "SELECT last_allocated_revision " +
                        "FROM appearance_override_revision_sequence",
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun overrideRevisionSequenceRollbackBelowLiveRowsFailsClosedIfStorageIsTampered() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val first = repository.create(newProfile("Rollback first"), false).successValue()
            val second = repository.create(newProfile("Rollback second"), false).successValue()
            val scope = AppearanceScope.Screen(AppearanceScreenScope.SETTINGS)
            val created = repository.setOverride(scope, first.id, null).successValue()
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "DROP TRIGGER appearance_override_revision_sequence_require_singleton_update",
            )
            sqlite.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = 0",
            )

            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setOverride(scope, second.id, created.revision),
            )
            assertEquals(created, repository.observeOverride(scope).first { it != null })
        } finally {
            database.close()
        }
    }

    @Test
    fun missingOrExhaustedOverrideRevisionSequenceBlocksActualWrites() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val first = repository.create(newProfile("Sequence first"), false).successValue()
            val second = repository.create(newProfile("Sequence second"), false).successValue()
            val scope = AppearanceScope.Screen(AppearanceScreenScope.SPAM_BLOCKED)
            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER appearance_override_revision_sequence_reject_delete",
            )
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM appearance_override_revision_sequence",
            )
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setOverride(scope, first.id, null),
            )

            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO appearance_override_revision_sequence(" +
                    "singleton_id, last_allocated_revision" +
                    ") VALUES(1, 0)",
            )
            val created = repository.setOverride(scope, first.id, null).successValue()
            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER appearance_override_revision_sequence_require_singleton_update",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = ${Long.MAX_VALUE}",
            )
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setOverride(scope, second.id, created.revision),
            )
            assertEquals(created, repository.observeOverride(scope).first { it != null })
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidStoredCodeFailsClosedAndBlocksMutationWithoutLeakingTheRow() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val created = repository.create(newProfile("Corrupt me"), activate = true).successValue()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_profiles SET palette_code = 'unsupported_future_code' " +
                    "WHERE profile_id = ${created.id.value}",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_selection SET snapshot_revision = snapshot_revision + 1",
            )

            assertEquals(AppearanceSnapshot.Empty, repository.snapshots.drop(1).first())
            assertEquals(AppearanceRepositoryResult.CorruptData, repository.activate(created.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun missingSelectionRemainsMissingOnReopenAndFailsClosed() = runBlocking {
        var database = openStateDatabase()
        database.openHelper.writableDatabase.execSQL("DELETE FROM appearance_selection")
        database.close()

        database = openStateDatabase()
        try {
            val selectionCount = database.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM appearance_selection")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            assertEquals(0L, selectionCount)
            val repository = RoomAppearanceProfileRepository(database)
            assertEquals(AppearanceSnapshot.Empty, repository.snapshots.first())
            assertEquals(AppearanceRepositoryResult.CorruptData, repository.resetActive())
        } finally {
            database.close()
        }
    }

    private fun openStateDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database

    private fun newProfile(name: String): NewAppearanceProfile = NewAppearanceProfile(
        name = AppearanceProfileName.from(name),
        values = AppearanceProfileValues(),
        createdTimestampMillis = 1_000L,
        updatedTimestampMillis = 1_000L,
    )

    private fun conversationScope(
        providerThreadId: Long,
        vararg participants: String,
    ): AppearanceScope.Conversation = AppearanceScope.Conversation(
        participantSetKey = AppearanceParticipantSetKey.fromParticipants(
            participants.map(::ParticipantAddress),
        ),
        providerThreadId = ProviderThreadId(providerThreadId),
    )
}

private fun <T> AppearanceRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is AppearanceRepositoryResult.Success<T>)
    return (this as AppearanceRepositoryResult.Success<T>).value
}

private fun org.aurorasms.core.state.storage.AuroraStateDatabase.longValue(sql: String): Long =
    openHelper.writableDatabase.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
