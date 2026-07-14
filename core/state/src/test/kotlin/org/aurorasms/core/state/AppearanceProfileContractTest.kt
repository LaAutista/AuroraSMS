// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceProfileContractTest {
    @Test
    fun canonicalDefaultIsBoundedAndStable() {
        val values = AppearanceProfileValues.CanonicalDefault

        assertEquals(CURRENT_APPEARANCE_PROFILE_SCHEMA, values.schemaVersion)
        assertEquals(AppearancePalette.AURORA_DARK, values.palette)
        assertEquals(174, values.hueDegrees)
        assertEquals(AppearanceRowDensity.COMFORTABLE, values.rowDensity)
        assertEquals(AppearanceAvatarMask.CIRCLE, values.avatarMask)
        assertEquals(AppearanceNavigationStyle.CLASSIC, values.navigationStyle)
        assertEquals(AppearanceBubbleGeometry.ROUNDED, values.bubbleGeometry)
        assertEquals(520, values.wallpaperDimPermill)
        assertEquals(500, values.focalXPermill)
        assertEquals(500, values.focalYPermill)
    }

    @Test
    fun everyStorageCodeIsExplicitUniqueAndStrictlyParsed() {
        assertStableCodes(AppearancePalette.entries, { it.storageCode }, AppearancePalette::fromStorageCode)
        assertStableCodes(
            AppearanceRowDensity.entries,
            { it.storageCode },
            AppearanceRowDensity::fromStorageCode,
        )
        assertStableCodes(
            AppearanceAvatarMask.entries,
            { it.storageCode },
            AppearanceAvatarMask::fromStorageCode,
        )
        assertStableCodes(
            AppearanceNavigationStyle.entries,
            { it.storageCode },
            AppearanceNavigationStyle::fromStorageCode,
        )
        assertStableCodes(
            AppearanceBubbleGeometry.entries,
            { it.storageCode },
            AppearanceBubbleGeometry::fromStorageCode,
        )
    }

    @Test
    fun profileNamesNormalizeForDisplayAndUniquenessWithoutLeaking() {
        val first = AppearanceProfileName.from("  Cafe\u0301  ")
        val duplicateKey = AppearanceProfileName.from("CAFÉ")

        assertEquals("Café", first.value)
        assertEquals(first.normalizedKey, duplicateKey.normalizedKey)
        assertEquals("AppearanceProfileName(REDACTED)", first.toString())
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileName.from("x".repeat(MAX_APPEARANCE_PROFILE_NAME_CODE_POINTS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileName.from("unsafe\u0000name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileName.from("unsafe\u202Ename")
        }
    }

    @Test
    fun hostileProfileRangesAndSchemasAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileValues(schemaVersion = CURRENT_APPEARANCE_PROFILE_SCHEMA + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileValues(hueDegrees = 360)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileValues(wallpaperDimPermill = 349)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileValues(focalXPermill = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceProfileValues(focalYPermill = 1_001)
        }
    }

    @Test
    fun emptySnapshotImmediatelyResolvesToCanonicalAndNamedProfileCanBecomeActive() {
        assertSame(AppearanceProfileValues.CanonicalDefault, AppearanceSnapshot.Empty.activeValues)
        assertNull(AppearanceSnapshot.Empty.activeProfile)

        val profile = storedProfile()
        val snapshot = AppearanceSnapshot(
            profiles = listOf(profile),
            activeProfileId = profile.id,
            revision = 2L,
        )

        assertEquals(profile, snapshot.activeProfile)
        assertEquals(profile.values, snapshot.activeValues)
        assertEquals(
            "AppearanceSnapshot(profileCount=1, hasActiveProfile=true, revision=2, REDACTED)",
            snapshot.toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(activeProfileId = AppearanceProfileId(2L))
        }
    }

    @Test
    fun profileObjectsAndMutationResultsRedactNames() {
        val profile = storedProfile()
        val newProfile = NewAppearanceProfile(
            name = profile.name,
            values = profile.values,
            createdTimestampMillis = 1L,
        )
        val edit = AppearanceProfileEdit(
            id = profile.id,
            name = profile.name,
            values = profile.values,
            updatedTimestampMillis = 2L,
        )

        assertEquals("AppearanceProfile(REDACTED)", profile.toString())
        assertEquals("NewAppearanceProfile(REDACTED)", newProfile.toString())
        assertEquals("AppearanceProfileEdit(REDACTED)", edit.toString())
        assertEquals("AppearanceProfileId(REDACTED)", profile.id.toString())
        assertEquals("AppearanceRevision(REDACTED)", profile.revision.toString())
        assertEquals(
            "AppearanceRepositoryResult.Success(REDACTED)",
            AppearanceRepositoryResult.Success(profile).toString(),
        )
    }

    private fun storedProfile(): AppearanceProfile = AppearanceProfile(
        id = AppearanceProfileId(1L),
        name = AppearanceProfileName.from("Synthetic profile"),
        values = AppearanceProfileValues(palette = AppearancePalette.AMOLED_BLACK),
        revision = AppearanceRevision(1L),
        createdTimestampMillis = 1L,
        updatedTimestampMillis = 1L,
    )

    private fun <T> assertStableCodes(
        entries: List<T>,
        code: (T) -> String,
        parse: (String) -> T?,
    ) {
        assertEquals(entries.size, entries.map(code).toSet().size)
        entries.forEach { entry -> assertEquals(entry, parse(code(entry))) }
        assertNull(parse("unsupported_future_code"))
        assertTrue(entries.map(code).all { storageCode -> storageCode.any(Char::isLetter) })
    }
}
