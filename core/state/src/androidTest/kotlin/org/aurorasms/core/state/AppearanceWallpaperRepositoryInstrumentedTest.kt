// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.RoomAppearanceProfileRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceWallpaperRepositoryInstrumentedTest {
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
    fun assignmentsShareSequenceButResetIndependentlyAndReportLastReference() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val profile = repository.create(newProfile("Wallpaper profile"), false).successValue()
            val screen = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
            val conversation = conversationScope(71L, "wallpaper@example.invalid")
            val profileOverride = repository.setOverride(screen, profile.id, null).successValue()
            val mediaId = wallpaperMediaId(1)

            val created = repository.setWallpaper(
                scope = screen,
                mediaId = mediaId,
                dimPermill = 500,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ).successValue()
            val createdAssignment = checkNotNull(created.assignment)
            assertTrue(createdAssignment.revision.value > profileOverride.revision.value)
            assertNull(created.mediaIdNowUnreferenced)

            val exactNoOp = repository.setWallpaper(
                scope = screen,
                mediaId = mediaId,
                dimPermill = 500,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = createdAssignment.revision,
            ).successValue()
            assertEquals(created, exactNoOp)
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.setWallpaper(screen, mediaId, 500, 500, 500, null),
            )

            val treated = repository.setWallpaper(
                scope = screen,
                mediaId = mediaId,
                dimPermill = 700,
                focalXPermill = 250,
                focalYPermill = 750,
                expectedRevision = createdAssignment.revision,
            ).successValue()
            val treatedAssignment = checkNotNull(treated.assignment)
            assertTrue(treatedAssignment.revision.value > createdAssignment.revision.value)

            val shared = repository.setWallpaper(
                scope = conversation,
                mediaId = mediaId,
                dimPermill = 600,
                focalXPermill = 400,
                focalYPermill = 600,
                expectedRevision = null,
            ).successValue()
            val sharedAssignment = checkNotNull(shared.assignment)
            assertTrue(sharedAssignment.revision.value > treatedAssignment.revision.value)

            val screenReset = repository.resetWallpaper(
                screen,
                treatedAssignment.revision,
            ).successValue()
            assertNull(screenReset.assignment)
            assertNull(screenReset.mediaIdNowUnreferenced)
            assertEquals(profileOverride, repository.observeOverride(screen).first { it != null })

            val conversationReset = repository.resetWallpaper(
                conversation,
                sharedAssignment.revision,
            ).successValue()
            assertNull(conversationReset.assignment)
            assertEquals(mediaId, conversationReset.mediaIdNowUnreferenced)
            assertEquals(
                emptySet<AppearanceWallpaperMediaId>(),
                repository.referencedMediaIds().successValue(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun conversationRebindsByFingerprintAndSurvivesProfileDeletion() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val profile = repository.create(newProfile("Disposable profile"), false).successValue()
            val originalScope = conversationScope(40L, "Beta", "Cafe\u0301")
            val profileOverride = repository.setOverride(originalScope, profile.id, null).successValue()
            val mediaId = wallpaperMediaId(2)
            val created = repository.setWallpaper(
                scope = originalScope,
                mediaId = mediaId,
                dimPermill = 650,
                focalXPermill = 300,
                focalYPermill = 700,
                expectedRevision = null,
            ).successValue()
            val createdAssignment = checkNotNull(created.assignment)
            assertTrue(createdAssignment.revision.value > profileOverride.revision.value)

            repository.delete(profile.id, profile.revision).successValue()
            assertNull(repository.observeOverride(originalScope).first())
            assertEquals(
                createdAssignment,
                repository.observeWallpaper(originalScope).first { it != null },
            )

            val recreatedScope = conversationScope(99L, "Café", "Beta", "Beta")
            val durable = checkNotNull(repository.observeWallpaper(recreatedScope).first { it != null })
            assertEquals(createdAssignment.copy(scope = recreatedScope), durable)
            val rebound = repository.setWallpaper(
                scope = recreatedScope,
                mediaId = mediaId,
                dimPermill = durable.dimPermill,
                focalXPermill = durable.focalXPermill,
                focalYPermill = durable.focalYPermill,
                expectedRevision = durable.revision,
            ).successValue()
            val reboundAssignment = checkNotNull(rebound.assignment)
            assertTrue(reboundAssignment.revision.value > durable.revision.value)
            assertNull(rebound.mediaIdNowUnreferenced)
            assertEquals(
                99L,
                database.longValue(
                    "SELECT provider_thread_id FROM appearance_conversation_wallpapers",
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun prospectiveSetEnforcesCasAndProjectsSharedAndExclusiveMediaWithoutMutation() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val screen = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
            val sharedScope = conversationScope(81L, "shared-projection@example.invalid")
            val exclusiveScope = conversationScope(82L, "exclusive-projection@example.invalid")
            val absentScope = conversationScope(83L, "absent-projection@example.invalid")
            val sharedMedia = wallpaperMediaId(20)
            val exclusiveMedia = wallpaperMediaId(21)
            val candidate = wallpaperMediaId(22)
            val screenAssignment = checkNotNull(
                repository.setWallpaper(
                    screen,
                    sharedMedia,
                    500,
                    500,
                    500,
                    null,
                ).successValue().assignment,
            )
            repository.setWallpaper(
                sharedScope,
                sharedMedia,
                500,
                500,
                500,
                null,
            ).successValue()
            val exclusiveAssignment = checkNotNull(
                repository.setWallpaper(
                    exclusiveScope,
                    exclusiveMedia,
                    500,
                    500,
                    500,
                    null,
                ).successValue().assignment,
            )
            val allocatedBeforeProjection = database.longValue(
                "SELECT last_allocated_revision FROM appearance_override_revision_sequence",
            )

            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.prospectiveMediaIdsForSet(screen, candidate, null),
            )
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.prospectiveMediaIdsForSet(
                    screen,
                    candidate,
                    AppearanceWallpaperRevision(screenAssignment.revision.value + 100L),
                ),
            )
            assertEquals(
                setOf(sharedMedia, exclusiveMedia, candidate),
                repository.prospectiveMediaIdsForSet(
                    screen,
                    candidate,
                    screenAssignment.revision,
                ).successValue(),
            )
            assertEquals(
                setOf(sharedMedia, candidate),
                repository.prospectiveMediaIdsForSet(
                    exclusiveScope,
                    candidate,
                    exclusiveAssignment.revision,
                ).successValue(),
            )
            assertEquals(
                setOf(sharedMedia, exclusiveMedia, candidate),
                repository.prospectiveMediaIdsForSet(absentScope, candidate, null).successValue(),
            )
            assertEquals(
                AppearanceRepositoryResult.StaleWrite,
                repository.prospectiveMediaIdsForSet(
                    absentScope,
                    candidate,
                    screenAssignment.revision,
                ),
            )
            assertEquals(
                setOf(sharedMedia, exclusiveMedia),
                repository.referencedMediaIds().successValue(),
            )
            assertEquals(
                allocatedBeforeProjection,
                database.longValue(
                    "SELECT last_allocated_revision FROM appearance_override_revision_sequence",
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unsupportedScreenScopesFailBeforeDatabaseAccess() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        database.close()
        val unsupported = AppearanceScope.Screen(AppearanceScreenScope.INBOX)

        assertNull(repository.observeWallpaper(unsupported).first())
        assertEquals(
            AppearanceRepositoryResult.CorruptData,
            repository.setWallpaper(unsupported, wallpaperMediaId(3), 500, 500, 500, null),
        )
        assertEquals(
            AppearanceRepositoryResult.CorruptData,
            repository.resetWallpaper(unsupported, null),
        )
        assertEquals(
            AppearanceRepositoryResult.CorruptData,
            repository.prospectiveMediaIdsForSet(unsupported, wallpaperMediaId(4), null),
        )
    }

    @Test
    fun unsupportedStoredScreenFailsClosedAndCannotBecomeObservable() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = last_allocated_revision + 1",
            )
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO appearance_screen_wallpapers(" +
                    "screen_code, media_kind_code, media_id, dim_permill, " +
                    "focal_x_permill, focal_y_permill, revision" +
                    ") VALUES('inbox', 'static_raster_v1', ?, 500, 500, 500, 1)",
                arrayOf(wallpaperMediaId(5).toPrivateStorageToken()),
            )

            val unsupported = AppearanceScope.Screen(AppearanceScreenScope.INBOX)
            assertNull(repository.observeWallpaper(unsupported).first())
            assertEquals(AppearanceRepositoryResult.CorruptData, repository.referencedMediaIds())
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setWallpaper(
                    AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD),
                    wallpaperMediaId(6),
                    500,
                    500,
                    500,
                    null,
                ),
            )
            assertEquals(
                1L,
                database.longValue("SELECT COUNT(*) FROM appearance_screen_wallpapers"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedGlobalWallpaperRowsBlockTheGarbageCollectionSnapshot() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = last_allocated_revision + 1",
            )
            val mediaToken = wallpaperMediaId(7).toPrivateStorageToken()
            val corruptions = listOf(
                StoredColumnCorruption("media_kind_code", "future_kind"),
                StoredColumnCorruption("media_id", "sha256-v1:${"A".repeat(64)}"),
                StoredColumnCorruption("dim_permill", 349),
                StoredColumnCorruption("dim_permill", "not-an-integer"),
                StoredColumnCorruption("focal_x_permill", -1),
                StoredColumnCorruption("focal_y_permill", 1_001),
                StoredColumnCorruption("revision", 0),
                StoredColumnCorruption("revision", "not-an-integer"),
            )
            corruptions.forEach { corruption ->
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO appearance_screen_wallpapers(" +
                        "screen_code, media_kind_code, media_id, dim_permill, " +
                        "focal_x_permill, focal_y_permill, revision" +
                        ") VALUES('global_thread', 'static_raster_v1', ?, 500, 500, 500, 1)",
                    arrayOf(mediaToken),
                )
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE appearance_screen_wallpapers SET ${corruption.column} = ?",
                    arrayOf(corruption.value),
                )

                assertEquals(
                    corruption.column,
                    AppearanceRepositoryResult.CorruptData,
                    repository.referencedMediaIds(),
                )
                assertEquals(
                    corruption.column,
                    AppearanceRepositoryResult.CorruptData,
                    repository.prospectiveMediaIdsForSet(
                        AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD),
                        wallpaperMediaId(9),
                        null,
                    ),
                )
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM appearance_screen_wallpapers",
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedConversationWallpaperRowsBlockTheGarbageCollectionSnapshot() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = last_allocated_revision + 1",
            )
            val scope = conversationScope(73L, "gc-health@example.invalid")
            val participantToken = scope.participantSetKey.toPrivateStorageToken()
            val mediaToken = wallpaperMediaId(8).toPrivateStorageToken()
            val corruptions = listOf(
                StoredColumnCorruption("participant_set_key", "not-a-private-fingerprint"),
                StoredColumnCorruption(
                    "participant_set_key",
                    "sha256-v1:${"A".repeat(64)}",
                ),
                StoredColumnCorruption("provider_thread_id", 0),
                StoredColumnCorruption("provider_thread_id", "not-an-integer"),
                StoredColumnCorruption("media_kind_code", "future_kind"),
                StoredColumnCorruption("media_id", "content://synthetic.invalid/wallpaper"),
                StoredColumnCorruption("dim_permill", 901),
                StoredColumnCorruption("focal_x_permill", 1_001),
                StoredColumnCorruption("focal_y_permill", -1),
                StoredColumnCorruption("revision", 0),
            )
            corruptions.forEach { corruption ->
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO appearance_conversation_wallpapers(" +
                        "participant_set_key, provider_thread_id, media_kind_code, media_id, " +
                        "dim_permill, focal_x_permill, focal_y_permill, revision" +
                        ") VALUES(?, 73, 'static_raster_v1', ?, 500, 500, 500, 1)",
                    arrayOf(participantToken, mediaToken),
                )
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE appearance_conversation_wallpapers " +
                        "SET ${corruption.column} = ?",
                    arrayOf(corruption.value),
                )

                assertEquals(
                    corruption.column,
                    AppearanceRepositoryResult.CorruptData,
                    repository.referencedMediaIds(),
                )
                assertEquals(
                    corruption.column,
                    AppearanceRepositoryResult.CorruptData,
                    repository.prospectiveMediaIdsForSet(
                        scope,
                        wallpaperMediaId(10),
                        null,
                    ),
                )
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM appearance_conversation_wallpapers",
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun resetFailsClosedBeforeMutationWhenAnyWallpaperReferenceIsInvalid() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val screen = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
            val screenMedia = wallpaperMediaId(30)
            val screenAssignment = checkNotNull(
                repository.setWallpaper(
                    screen,
                    screenMedia,
                    500,
                    500,
                    500,
                    null,
                ).successValue().assignment,
            )
            val unrelated = conversationScope(300L, "invalid-reset-reference@example.invalid")
            repository.setWallpaper(
                unrelated,
                wallpaperMediaId(31),
                500,
                500,
                500,
                null,
            ).successValue()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_conversation_wallpapers " +
                    "SET media_kind_code = 'future_kind'",
            )

            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.resetWallpaper(screen, screenAssignment.revision),
            )
            assertEquals(
                screenAssignment,
                repository.observeWallpaper(screen).first { it != null },
            )
            assertEquals(
                2L,
                database.longValue(
                    "SELECT " +
                        "(SELECT COUNT(*) FROM appearance_screen_wallpapers) + " +
                        "(SELECT COUNT(*) FROM appearance_conversation_wallpapers)",
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun mediaIdsAreBoundedAndEnumerationFailsClosedAtOverflowSentinel() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            repeat(MAX_APPEARANCE_WALLPAPER_MEDIA_IDS) { index ->
                repository.setWallpaper(
                    scope = conversationScope(
                        index.toLong() + 1L,
                        "wallpaper-$index@example.invalid",
                    ),
                    mediaId = wallpaperMediaId(index + 1),
                    dimPermill = 500,
                    focalXPermill = 500,
                    focalYPermill = 500,
                    expectedRevision = null,
                ).successValue()
            }
            assertEquals(
                MAX_APPEARANCE_WALLPAPER_MEDIA_IDS,
                repository.referencedMediaIds().successValue().size,
            )
            val firstScope = conversationScope(1L, "wallpaper-0@example.invalid")
            val firstAssignment = checkNotNull(
                repository.observeWallpaper(firstScope).first { it != null },
            )
            val overflowScope = conversationScope(10_000L, "overflow@example.invalid")
            val overflowMediaId = wallpaperMediaId(MAX_APPEARANCE_WALLPAPER_MEDIA_IDS + 1)
            assertEquals(
                AppearanceRepositoryResult.LimitExceeded(AppearanceLimit.WALLPAPER_MEDIA_COUNT),
                repository.prospectiveMediaIdsForSet(
                    overflowScope,
                    overflowMediaId,
                    null,
                ),
            )
            val exclusiveProjection = repository.prospectiveMediaIdsForSet(
                firstScope,
                overflowMediaId,
                firstAssignment.revision,
            ).successValue()
            assertEquals(MAX_APPEARANCE_WALLPAPER_MEDIA_IDS, exclusiveProjection.size)
            assertTrue(wallpaperMediaId(1) !in exclusiveProjection)
            assertTrue(overflowMediaId in exclusiveProjection)
            assertTrue(overflowMediaId !in repository.referencedMediaIds().successValue())

            repository.setWallpaper(
                scope = conversationScope(20_000L, "shared-at-limit@example.invalid"),
                mediaId = wallpaperMediaId(1),
                dimPermill = 500,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ).successValue()
            assertEquals(
                AppearanceRepositoryResult.LimitExceeded(AppearanceLimit.WALLPAPER_MEDIA_COUNT),
                repository.prospectiveMediaIdsForSet(
                    firstScope,
                    overflowMediaId,
                    firstAssignment.revision,
                ),
            )
            assertEquals(
                AppearanceRepositoryResult.LimitExceeded(AppearanceLimit.WALLPAPER_MEDIA_COUNT),
                repository.setWallpaper(
                    overflowScope,
                    overflowMediaId,
                    500,
                    500,
                    500,
                    null,
                ),
            )

            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence " +
                    "SET last_allocated_revision = last_allocated_revision + 1",
            )
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO appearance_conversation_wallpapers(" +
                    "participant_set_key, provider_thread_id, media_kind_code, media_id, " +
                    "dim_permill, focal_x_permill, focal_y_permill, revision" +
                    ") VALUES(?, 10000, 'static_raster_v1', ?, 500, 500, 500, 130)",
                arrayOf(
                    overflowScope.participantSetKey.toPrivateStorageToken(),
                    overflowMediaId.toPrivateStorageToken(),
                ),
            )
            assertEquals(AppearanceRepositoryResult.CorruptData, repository.referencedMediaIds())
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.prospectiveMediaIdsForSet(
                    firstScope,
                    overflowMediaId,
                    firstAssignment.revision,
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun revisionSequenceRollbackBelowLiveRowFailsClosed() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomAppearanceProfileRepository(database)
        try {
            val scope = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
            val mediaId = wallpaperMediaId(4)
            val created = repository.setWallpaper(
                scope = scope,
                mediaId = mediaId,
                dimPermill = 500,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ).successValue()
            val assignment = checkNotNull(created.assignment)
            database.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER appearance_override_revision_sequence_require_singleton_update",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE appearance_override_revision_sequence SET last_allocated_revision = 0",
            )

            assertEquals(AppearanceRepositoryResult.CorruptData, repository.referencedMediaIds())
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.prospectiveMediaIdsForSet(
                    scope,
                    wallpaperMediaId(11),
                    assignment.revision,
                ),
            )
            assertEquals(
                AppearanceRepositoryResult.CorruptData,
                repository.setWallpaper(
                    scope,
                    mediaId,
                    600,
                    500,
                    500,
                    assignment.revision,
                ),
            )
            assertEquals(assignment, repository.observeWallpaper(scope).first { it != null })
        } finally {
            database.close()
        }
    }

    private fun openStateDatabase(): AuroraStateDatabase =
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

    private fun wallpaperMediaId(index: Int): AppearanceWallpaperMediaId =
        AppearanceWallpaperMediaId.fromPrivateStorageToken(
            "sha256-v1:${index.toString(16).padStart(64, '0')}",
        )
}

private fun <T> AppearanceRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is AppearanceRepositoryResult.Success<T>)
    return (this as AppearanceRepositoryResult.Success<T>).value
}

private fun AuroraStateDatabase.longValue(sql: String): Long =
    openHelper.writableDatabase.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private data class StoredColumnCorruption(
    val column: String,
    val value: Any,
)
