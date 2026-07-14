// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceOverrideContractTest {
    @Test
    fun participantFingerprintHasStableGoldenFormat() {
        val key = participantKey("alpha", "Beta")

        assertEquals(
            "sha256-v1:438c5115b010f7a466aaeda79a8944581289a71b6ee79f06ed9913e06602b4a3",
            key.toPrivateStorageToken(),
        )
        assertEquals(
            AppearanceParticipantSetKey.STORAGE_CHARACTERS,
            key.toPrivateStorageToken().length,
        )
    }

    @Test
    fun participantFingerprintNormalizesNfcThenDeduplicatesAndSortsExactValues() {
        assertEquals(
            participantKey("Café", "Beta"),
            participantKey("Beta", "Cafe\u0301", "Café", "Beta"),
        )
        assertNotEquals(participantKey("Case"), participantKey("case"))
        assertNotEquals(participantKey("+1 555 0100"), participantKey("+15550100"))
    }

    @Test
    fun participantFingerprintInputIsNonEmptyAndBoundedBeforeDeduplication() {
        assertEquals(
            AppearanceParticipantSetKey.STORAGE_CHARACTERS,
            participantKey(*Array(AppearanceParticipantSetKey.MAX_PARTICIPANTS) { "p$it" })
                .toStorageValue()
                .length,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceParticipantSetKey.fromParticipants(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            participantKey(*Array(AppearanceParticipantSetKey.MAX_PARTICIPANTS + 1) { "p$it" })
        }
    }

    @Test
    fun participantFingerprintRejectsUnpairedUtf16Surrogates() {
        assertThrows(IllegalArgumentException::class.java) {
            participantKey("\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            participantKey("\uDC00")
        }
        assertEquals(participantKey("😀"), participantKey("\uD83D\uDE00"))
    }

    @Test
    fun storedParticipantFingerprintIsStrictAndAllIdentityStringsAreRedacted() {
        val key = participantKey("synthetic@example.invalid")
        val scope = AppearanceScope.Conversation(key, ProviderThreadId(7L))
        val override = AppearanceOverride(
            scope = scope,
            profileId = AppearanceProfileId(3L),
            revision = AppearanceOverrideRevision(2L),
        )

        assertEquals("AppearanceParticipantSetKey(REDACTED)", key.toString())
        assertEquals("AppearanceScope.Conversation(REDACTED)", scope.toString())
        assertEquals("AppearanceOverrideRevision(REDACTED)", override.revision.toString())
        assertEquals("AppearanceOverride(REDACTED)", override.toString())
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceParticipantSetKey.fromStorageValue("sha256-v1:${"A".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceOverrideRevision(0L)
        }
    }

    @Test
    fun screenStorageCodesAreExplicitUniqueAndStrict() {
        assertEquals(
            setOf("inbox", "archive", "settings", "spam_blocked", "global_thread"),
            AppearanceScreenScope.entries.map { it.storageCode }.toSet(),
        )
        assertEquals(
            AppearanceScreenScope.entries.size,
            AppearanceScreenScope.entries.map { it.storageCode }.toSet().size,
        )
        AppearanceScreenScope.entries.forEach { screen ->
            assertEquals(screen, AppearanceScreenScope.fromStorageCode(screen.storageCode))
        }
        assertNull(AppearanceScreenScope.fromStorageCode("INBOX"))
        assertNull(AppearanceScreenScope.fromStorageCode("future_surface"))
        assertTrue(AppearanceScreenScope.entries.all { it.storageCode.any(Char::isLetter) })
    }

    private fun participantKey(vararg values: String): AppearanceParticipantSetKey =
        AppearanceParticipantSetKey.fromParticipants(values.map(::ParticipantAddress))
}
