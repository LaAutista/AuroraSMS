// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.internal.IncomingSmsReplayJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingSmsReplayJournalTest {
    @Test
    fun pendingStoredCompleteTransitionsSurviveJournalRecreationWithoutContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var now = 100L
        val name = "incoming-replay-transition-test"
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            nowMillis = { now++ },
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val fingerprint = fingerprint(1)
        val providerId = ProviderMessageId(ProviderKind.SMS, 41L)
        val conversationId = ConversationId(51L)

        assertTrue(journal.begin(fingerprint, 1_000L, 900L, AuroraSubscriptionId(2)))
        assertEquals(
            IncomingSmsReplayJournal.State.PENDING,
            (journal.lookup(fingerprint) as IncomingSmsReplayJournal.LookupResult.Found).entry.state,
        )
        assertTrue(journal.markStored(fingerprint, providerId, conversationId))
        assertTrue(journal.markComplete(fingerprint, providerId, conversationId))

        val recreated = IncomingSmsReplayJournal(context, name)
        val complete = (recreated.lookup(fingerprint) as IncomingSmsReplayJournal.LookupResult.Found).entry
        assertEquals(IncomingSmsReplayJournal.State.COMPLETE, complete.state)
        assertEquals(providerId, complete.providerId)
        assertEquals(conversationId, complete.conversationId)
        assertEquals(RECOVERY_TOKEN, complete.providerRecoveryToken)
        val serialized = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toString()
        assertFalse(serialized.contains("synthetic message body", ignoreCase = true))
        assertFalse(serialized.contains("+15550102020"))
        recreated.clear()
    }

    @Test
    fun boundedJournalPrunesOnlyCompletedEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-bound-test"
        val journal = IncomingSmsReplayJournal(context, name, maximumEntries = 2)
        journal.clear()
        val first = fingerprint(11)
        val pending = fingerprint(12)
        val newest = fingerprint(13)

        assertTrue(journal.begin(first, 10L, 9L, null))
        assertTrue(journal.markComplete(first, ProviderMessageId(ProviderKind.SMS, 1L), ConversationId(1L)))
        assertTrue(journal.begin(pending, 20L, 19L, null))
        assertTrue(journal.begin(newest, 30L, 29L, null))

        assertTrue(journal.lookup(first) is IncomingSmsReplayJournal.LookupResult.Missing)
        assertTrue(journal.lookup(pending) is IncomingSmsReplayJournal.LookupResult.Found)
        assertTrue(journal.lookup(newest) is IncomingSmsReplayJournal.LookupResult.Found)
        journal.clear()
    }

    @Test
    fun completedEntryCannotRollBackAndInvalidRecoveryTokenFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-state-test"
        val fingerprint = fingerprint(21)
        val providerId = ProviderMessageId(ProviderKind.SMS, 71L)
        val conversationId = ConversationId(81L)
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()

        assertTrue(journal.begin(fingerprint, 40L, 39L, null))
        assertTrue(journal.markComplete(fingerprint, providerId, conversationId))
        assertFalse(journal.markStored(fingerprint, providerId, conversationId))
        assertFalse(journal.resetPending(fingerprint))

        journal.clear()
        val invalidTokenJournal = IncomingSmsReplayJournal(
            context,
            name,
            newRecoveryToken = { fingerprint.toStorageToken() },
        )
        assertFalse(invalidTokenJournal.begin(fingerprint, 40L, 39L, null))
        assertTrue(invalidTokenJournal.lookup(fingerprint) is IncomingSmsReplayJournal.LookupResult.Missing)
        invalidTokenJournal.clear()
    }

    private fun fingerprint(seed: Int): MessageDeliveryFingerprint =
        MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (seed + it).toByte() },
        )

    private companion object {
        const val RECOVERY_TOKEN = "123e4567-e89b-42d3-a456-426614174000"
    }
}
