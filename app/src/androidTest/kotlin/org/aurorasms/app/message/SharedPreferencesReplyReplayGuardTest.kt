// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesReplyReplayGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearJournal() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun claimSurvivesGuardRecreationAndExpiresOnlyAfterRetention() {
        val first = SharedPreferencesReplyReplayGuard(
            context = context,
            retentionMillis = 1_000L,
            maximumActiveClaims = 4,
        )
        assertTrue(first.claim(claim("SMS:71", expiresAt = 20_000L), 10_000L))

        val recreated = SharedPreferencesReplyReplayGuard(
            context = context,
            retentionMillis = 1_000L,
            maximumActiveClaims = 4,
        )
        assertFalse(recreated.claim(claim("SMS:71", expiresAt = 20_000L), 11_000L))
        assertFalse(recreated.claim(claim("SMS:71", expiresAt = 20_000L), 11_001L))
        assertTrue(recreated.claim(claim("SMS:71", expiresAt = 30_000L), 20_001L))
    }

    @Test
    fun fullActiveJournalRejectsInsteadOfEvictingReplayProtection() {
        val guard = SharedPreferencesReplyReplayGuard(
            context = context,
            retentionMillis = 10_000L,
            maximumActiveClaims = 2,
        )

        assertTrue(guard.claim(claim("SMS:81"), 20_000L))
        assertTrue(guard.claim(claim("SMS:82"), 20_001L))
        assertFalse(guard.claim(claim("SMS:83"), 20_002L))
        assertFalse(guard.claim(claim("SMS:81"), 20_003L))
    }

    @Test
    fun malformedExactClaimFailsClosedInsteadOfAllowingReplay() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("claim.SMS:91", "malformed")
            .commit()
        val guard = SharedPreferencesReplyReplayGuard(context)

        assertFalse(guard.claim(claim("SMS:91"), 20_000L))
    }

    @Test
    fun nonStringExactClaimFailsClosedInsteadOfAllowingReplay() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("claim.SMS:92", 1L)
            .commit()
        val guard = SharedPreferencesReplyReplayGuard(context)

        assertFalse(guard.claim(claim("SMS:92"), 20_000L))
    }

    @Test
    fun validLookingKeyMutationPoisonsJournalInsteadOfChangingRequestBinding() {
        val guard = guard()
        assertTrue(guard.claim(claim("SMS:93"), 20_000L))
        val preferences = preferences()
        val encoded = requireNotNull(preferences.getString("claim.SMS:93", null))
        assertTrue(
            preferences.edit()
                .remove("claim.SMS:93")
                .putString("claim.SMS:94", encoded)
                .commit(),
        )

        assertFalse(guard.claim(claim("SMS:95"), 20_001L))
    }

    @Test
    fun validLookingConversationMutationPoisonsJournal() {
        assertMutatedClaimRejectsFreshClaim(
            fieldIndex = 3,
            replacement = "78",
            storedRequestId = "SMS:96",
            freshRequestId = "SMS:97",
        )
    }

    @Test
    fun validLookingTimestampMutationPoisonsJournal() {
        assertMutatedClaimRejectsFreshClaim(
            fieldIndex = 2,
            replacement = "19999",
            storedRequestId = "SMS:98",
            freshRequestId = "SMS:99",
        )
    }

    @Test
    fun validLookingRecipientDigestMutationPoisonsJournal() {
        assertMutatedClaimRejectsFreshClaim(
            fieldIndex = 6,
            replacement = "a".repeat(64),
            storedRequestId = "SMS:100",
            freshRequestId = "SMS:101",
        )
    }

    @Test
    fun validLegacyClaimIsAtomicallyMigratedBeforeDistinctClaim() {
        val preferences = preferences()
        assertTrue(
            preferences.edit().putString(
                "claim.SMS:102",
                "20000|77|2|40000|${"b".repeat(64)}",
            ).commit(),
        )
        val guard = guard()

        assertTrue(guard.claim(claim("SMS:103"), 20_001L))
        assertCurrentFormat(requireNotNull(preferences.getString("claim.SMS:102", null)))
        assertCurrentFormat(requireNotNull(preferences.getString("claim.SMS:103", null)))
        assertFalse(guard.claim(claim("SMS:102"), 20_002L))
    }

    @Test
    fun semanticallyInvalidLegacyClaimPoisonsJournalInsteadOfMigrating() {
        val preferences = preferences()
        val legacy = "40000|77|2|20000|${"b".repeat(64)}"
        assertTrue(preferences.edit().putString("claim.SMS:104", legacy).commit())

        assertFalse(guard().claim(claim("SMS:105"), 20_001L))
        assertTrue(preferences.getString("claim.SMS:104", null) == legacy)
    }

    private fun assertCurrentFormat(serialized: String) {
        val fields = serialized.split('|')
        assertTrue(fields.size == 8)
        assertTrue(fields[0] == "2")
        assertTrue(fields.last().length == 64)
    }

    private fun assertMutatedClaimRejectsFreshClaim(
        fieldIndex: Int,
        replacement: String,
        storedRequestId: String,
        freshRequestId: String,
    ) {
        val guard = guard()
        assertTrue(guard.claim(claim(storedRequestId), 20_000L))
        val preferences = preferences()
        val key = "claim.$storedRequestId"
        val fields = requireNotNull(preferences.getString(key, null)).split('|').toMutableList()
        fields[fieldIndex] = replacement
        assertTrue(preferences.edit().putString(key, fields.joinToString("|")).commit())

        assertFalse(guard.claim(claim(freshRequestId), 20_001L))
    }

    private fun guard() = SharedPreferencesReplyReplayGuard(
        context = context,
        retentionMillis = 1_000L,
        maximumActiveClaims = 8,
    )

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun claim(
        requestId: String,
        expiresAt: Long = 40_000L,
    ) = ReplyReplayClaim(
        requestId = requestId,
        conversationId = org.aurorasms.core.model.ConversationId(77L),
        recipient = org.aurorasms.core.model.ParticipantAddress("+12025550177"),
        subscriptionId = org.aurorasms.core.model.AuroraSubscriptionId(2),
        expiresAtMillis = expiresAt,
    )

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_replay"
    }
}
