// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.MessageSignature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageSignaturePreferenceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before fun clear() { preferences.edit().clear().commit() }
    @After fun clean() { preferences.edit().clear().commit() }

    @Test
    fun globalAndConversationModesPersistWithPurposeSeparatedCanonicalKey() {
        val store = SharedPreferencesMessageSignaturePreferenceStore(context)
        val global = checkNotNull(MessageSignature.fromUserInput("Global"))
        val custom = checkNotNull(MessageSignature.fromUserInput("Custom"))
        val first = MessageSignatureConversationKey.fromParticipants(
            listOf(ParticipantAddress("+15550000001"), ParticipantAddress("+15550000002")),
        )
        val reordered = MessageSignatureConversationKey.fromParticipants(
            listOf(ParticipantAddress("+15550000002"), ParticipantAddress("+15550000001")),
        )
        assertEquals(first, reordered)

        assertTrue(store.setGlobal(global))
        assertEquals(global, store.snapshot.value.resolve(first))
        assertTrue(store.setConversation(first, ConversationSignatureOverride.Custom(custom)))

        val restored = SharedPreferencesMessageSignaturePreferenceStore(context)
        assertEquals(custom, restored.snapshot.value.resolve(reordered))
        assertTrue(restored.setConversation(first, ConversationSignatureOverride.Disabled))
        assertNull(restored.snapshot.value.resolve(first))
        assertTrue(restored.setConversation(first, ConversationSignatureOverride.Inherit))
        assertEquals(global, restored.snapshot.value.resolve(first))
    }

    @Test
    fun corruptStateFailsClosedAndIsNotOverwritten() {
        assertTrue(preferences.edit().putString("global", "corrupt").commit())
        val store = SharedPreferencesMessageSignaturePreferenceStore(context)

        assertFalse(store.snapshot.value.available)
        assertFalse(store.snapshot.value.sendAllowed)
        assertNull(store.snapshot.value.global)
        assertFalse(store.setGlobal(checkNotNull(MessageSignature.fromUserInput("replacement"))))
        assertEquals("corrupt", preferences.getString("global", null))
    }

    @Test
    fun conversationOverrideCountIsBoundedWithoutEviction() {
        val store = SharedPreferencesMessageSignaturePreferenceStore(
            context = context,
            maximumConversationOverrides = 1,
        )
        val first = MessageSignatureConversationKey.fromParticipants(
            listOf(ParticipantAddress("+15550000001")),
        )
        val second = MessageSignatureConversationKey.fromParticipants(
            listOf(ParticipantAddress("+15550000002")),
        )

        assertTrue(store.setConversation(first, ConversationSignatureOverride.Disabled))
        assertFalse(store.setConversation(second, ConversationSignatureOverride.Disabled))
        assertEquals(1, store.snapshot.value.conversations.size)
        assertEquals(ConversationSignatureOverride.Disabled, store.snapshot.value.conversations[first])
    }

    private companion object {
        const val PREFERENCES_NAME = "aurora_message_signatures_v1"
    }
}
