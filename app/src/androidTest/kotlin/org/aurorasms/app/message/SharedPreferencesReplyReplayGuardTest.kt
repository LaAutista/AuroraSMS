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
