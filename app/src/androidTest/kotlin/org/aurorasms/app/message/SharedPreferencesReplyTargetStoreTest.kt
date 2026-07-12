// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesReplyTargetStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearStore() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun exactTargetSurvivesStoreRecreation() {
        val target = target("SMS:91", expiresAtMillis = 20_000L)
        assertTrue(
            SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
                .put(target, nowMillis = 10_000L),
        )

        val recreated = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)

        assertEquals(target, recreated.get("SMS:91", nowMillis = 10_001L))
        assertNull(recreated.get("SMS:91", nowMillis = 20_000L))
    }

    @Test
    fun roleLossClearRemovesPersistedRoutingState() {
        val store = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
        assertTrue(store.put(target("SMS:92", 20_000L), nowMillis = 10_000L))

        assertTrue(store.clear())

        assertNull(
            SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
                .get("SMS:92", nowMillis = 10_001L),
        )
    }

    private fun target(requestId: String, expiresAtMillis: Long) = ReplyTarget(
        requestId = requestId,
        conversationId = ConversationId(91L),
        recipient = ParticipantAddress("+12025550191"),
        subscriptionId = AuroraSubscriptionId(2),
        expiresAtMillis = expiresAtMillis,
    )

    private companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_targets"
    }
}
