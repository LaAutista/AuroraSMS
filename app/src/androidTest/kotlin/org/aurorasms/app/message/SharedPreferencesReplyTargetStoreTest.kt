// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun exactRemovalCannotDeleteARequestOwnedByAnotherConversation() {
        val store = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
        val target = target("SMS:921", 20_000L)
        assertTrue(store.put(target, nowMillis = 10_000L))

        assertFalse(store.remove("SMS:921", ConversationId(92L)))
        assertEquals(target, store.get("SMS:921", nowMillis = 10_001L))
        assertTrue(store.remove("SMS:921", target.conversationId))
        assertNull(store.get("SMS:921", nowMillis = 10_001L))
        assertTrue(store.remove("SMS:921", target.conversationId))
    }

    @Test
    fun nonStringExactTargetFailsClosedAndIsRemoved() {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putLong("target.SMS:93", 93L).commit())

        val store = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)

        assertNull(store.get("SMS:93", nowMillis = 10_001L))
        assertTrue(!preferences.contains("target.SMS:93"))
    }

    @Test
    fun validLookingKeyMutationFailsClosedInsteadOfChangingRequestBinding() {
        val preferences = preferences()
        val store = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
        assertTrue(store.put(target("SMS:94", 20_000L), nowMillis = 10_000L))
        val encoded = requireNotNull(preferences.getString("target.SMS:94", null))
        assertTrue(
            preferences.edit()
                .remove("target.SMS:94")
                .putString("target.SMS:95", encoded)
                .commit(),
        )

        assertNull(store.get("SMS:95", nowMillis = 10_001L))
    }

    @Test
    fun validLookingConversationMutationFailsChecksumClosed() {
        assertMutatedTargetRejected(fieldIndex = 2, replacement = "92", requestId = "SMS:96")
    }

    @Test
    fun validLookingExpiryMutationFailsChecksumClosed() {
        assertMutatedTargetRejected(fieldIndex = 4, replacement = "21000", requestId = "SMS:97")
    }

    @Test
    fun validLookingRecipientMutationFailsChecksumClosed() {
        assertMutatedTargetRejected(
            fieldIndex = 5,
            replacement = encodeText("+12025550198"),
            requestId = "SMS:98",
        )
    }

    @Test
    fun legacyUnchecksummedTargetFailsClosedAndIsRemoved() {
        val preferences = preferences()
        assertTrue(
            preferences.edit().putString(
                "target.SMS:99",
                "1|91|2|20000|${encodeText("+12025550191")}",
            ).commit(),
        )

        assertNull(
            SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
                .get("SMS:99", nowMillis = 10_001L),
        )
        assertTrue(!preferences.contains("target.SMS:99"))
    }

    private fun assertMutatedTargetRejected(
        fieldIndex: Int,
        replacement: String,
        requestId: String,
    ) {
        val preferences = preferences()
        val store = SharedPreferencesReplyTargetStore(context, maximumEntries = 4)
        assertTrue(store.put(target(requestId, 20_000L), nowMillis = 10_000L))
        val key = "target.$requestId"
        val fields = requireNotNull(preferences.getString(key, null)).split('|').toMutableList()
        fields[fieldIndex] = replacement
        assertTrue(preferences.edit().putString(key, fields.joinToString("|")).commit())

        assertNull(store.get(requestId, nowMillis = 10_001L))
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun encodeText(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
    )

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
