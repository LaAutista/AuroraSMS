// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.state.AppearanceProfileName
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.NewAppearanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppearanceProfileEntityTest {
    @Test
    fun entityRoundTripPreservesEverySchemaV1Value() {
        val profile = NewAppearanceProfile(
            name = AppearanceProfileName.from("Synthetic profile"),
            values = AppearanceProfileValues(
                reducedMotion = true,
                highContrast = true,
                focalXPermill = 125,
                focalYPermill = 875,
            ),
            createdTimestampMillis = 10L,
            updatedTimestampMillis = 20L,
        ).toEntity().copy(profileId = 7L).toDomain()

        assertEquals(7L, profile.id.value)
        assertEquals(true, profile.values.reducedMotion)
        assertEquals(true, profile.values.highContrast)
        assertEquals(125, profile.values.focalXPermill)
        assertEquals(875, profile.values.focalYPermill)
    }

    @Test
    fun unknownCodesNonCanonicalNamesAndIntegerBooleansFailClosed() {
        val valid = validEntity()

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(paletteCode = "future_palette").toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(normalizedName = "wrong-key").toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(reducedMotion = 2).toDomain()
        }
    }

    @Test
    fun entityToStringDoesNotExposeStoredName() {
        assertEquals("AppearanceProfileEntity(REDACTED)", validEntity().toString())
    }

    private fun validEntity(): AppearanceProfileEntity = NewAppearanceProfile(
        name = AppearanceProfileName.from("Synthetic stored name"),
        values = AppearanceProfileValues(),
        createdTimestampMillis = 1L,
    ).toEntity().copy(profileId = 1L)
}
