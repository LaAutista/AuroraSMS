// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.state.storage.RoomAppearanceProfileRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
}

private fun <T> AppearanceRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is AppearanceRepositoryResult.Success<T>)
    return (this as AppearanceRepositoryResult.Success<T>).value
}
