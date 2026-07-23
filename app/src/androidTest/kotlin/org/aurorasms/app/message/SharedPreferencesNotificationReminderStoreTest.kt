// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesNotificationReminderStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun exactOwnerSurvivesStoreRecreationWithoutMessageContent() {
        val first = SharedPreferencesNotificationReminderStore(context)
        val created = requireNotNull(first.create(MESSAGE, CONVERSATION, DUE, CREATED))

        val recreated = SharedPreferencesNotificationReminderStore(context)

        assertEquals(created.owner, recreated.read(created.owner.id))
        val raw = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .all.values.joinToString()
        assertTrue(!raw.contains("message body") && !raw.contains("+1202"))
    }

    @Test
    fun sameConversationReplacementIsAtomicAndReturnsDisplacedOwner() {
        val store = SharedPreferencesNotificationReminderStore(context)
        val first = requireNotNull(store.create(MESSAGE, CONVERSATION, DUE, CREATED)).owner

        val second = requireNotNull(
            store.create(
                ProviderMessageId(ProviderKind.SMS, 12L),
                CONVERSATION,
                DUE + 1L,
                CREATED + 1L,
            ),
        )

        assertEquals(listOf(first), second.displaced)
        assertNull(store.read(first.id))
        assertEquals(listOf(second.owner), store.all())
    }

    @Test
    fun boundedStoreEvictsOldestOwner() {
        val store = SharedPreferencesNotificationReminderStore(context, maximumEntries = 2)
        val first = requireNotNull(store.create(MESSAGE, CONVERSATION, DUE, CREATED)).owner
        store.create(
            ProviderMessageId(ProviderKind.SMS, 12L),
            ConversationId(10L),
            DUE + 1L,
            CREATED + 1L,
        )

        val third = requireNotNull(
            store.create(
                ProviderMessageId(ProviderKind.SMS, 13L),
                ConversationId(11L),
                DUE + 2L,
                CREATED + 2L,
            ),
        )

        assertTrue(first in third.displaced)
        assertEquals(2, store.all()?.size)
    }

    @Test
    fun clearPreservesMonotonicIdAgainstDelayedPendingIntentAlias() {
        val store = SharedPreferencesNotificationReminderStore(context)
        val first = requireNotNull(store.create(MESSAGE, CONVERSATION, DUE, CREATED)).owner

        assertEquals(listOf(first), store.clear())
        val second = requireNotNull(
            store.create(MESSAGE, CONVERSATION, DUE + 1L, CREATED + 1L),
        ).owner

        assertTrue(second.id.value > first.id.value)
        assertNull(store.read(first.id))
    }

    private companion object {
        const val PREFERENCES_NAME = "aurora_notification_reminder_owners_v1"
        const val CREATED = 1_704_067_200_000L
        const val DUE = CREATED + 900_000L
        val MESSAGE = ProviderMessageId(ProviderKind.SMS, 11L)
        val CONVERSATION = ConversationId(9L)
    }
}
