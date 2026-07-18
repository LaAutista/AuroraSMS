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
import org.aurorasms.core.telephony.internal.IncomingSmsProviderContentDigest
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

        assertTrue(
            journal.begin(
                fingerprint,
                1_000L,
                900L,
                AuroraSubscriptionId(2),
                providerDigest(1),
            ),
        )
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

        assertTrue(journal.begin(first, 10L, 9L, null, providerDigest(11)))
        assertTrue(journal.markStored(first, ProviderMessageId(ProviderKind.SMS, 1L), ConversationId(1L)))
        assertTrue(journal.markComplete(first, ProviderMessageId(ProviderKind.SMS, 1L), ConversationId(1L)))
        assertTrue(journal.begin(pending, 20L, 19L, null, providerDigest(12)))
        assertTrue(journal.begin(newest, 30L, 29L, null, providerDigest(13)))

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

        assertTrue(journal.begin(fingerprint, 40L, 39L, null, providerDigest(21)))
        assertFalse(journal.markComplete(fingerprint, providerId, conversationId))
        assertTrue(journal.markStored(fingerprint, providerId, conversationId))
        assertTrue(journal.markComplete(fingerprint, providerId, conversationId))
        assertFalse(journal.markStored(fingerprint, providerId, conversationId))
        assertFalse(journal.resetPending(fingerprint))

        journal.clear()
        val invalidTokenJournal = IncomingSmsReplayJournal(
            context,
            name,
            newRecoveryToken = { fingerprint.toStorageToken() },
        )
        assertFalse(invalidTokenJournal.begin(fingerprint, 40L, 39L, null, providerDigest(21)))
        assertTrue(invalidTokenJournal.lookup(fingerprint) is IncomingSmsReplayJournal.LookupResult.Missing)
        invalidTokenJournal.clear()
    }

    @Test
    fun recoveryEntriesAreOldestFirstBoundedAndExcludeCompletedDeliveries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var now = 1_000L
        val name = "incoming-replay-enumeration-test"
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            nowMillis = { now++ },
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val oldest = fingerprint(31)
        val completed = fingerprint(32)
        val newest = fingerprint(33)

        assertTrue(journal.begin(oldest, 100L, 90L, null, providerDigest(31)))
        assertTrue(journal.markStored(oldest, ProviderMessageId(ProviderKind.SMS, 101L), ConversationId(201L)))
        assertTrue(journal.begin(completed, 200L, 190L, null, providerDigest(32)))
        assertTrue(
            journal.markStored(completed, ProviderMessageId(ProviderKind.SMS, 102L), ConversationId(202L)),
        )
        assertTrue(
            journal.markComplete(completed, ProviderMessageId(ProviderKind.SMS, 102L), ConversationId(202L)),
        )
        assertTrue(
            journal.begin(newest, 300L, 290L, AuroraSubscriptionId(3), providerDigest(33)),
        )
        assertTrue(journal.markStored(newest, ProviderMessageId(ProviderKind.SMS, 103L), ConversationId(203L)))

        val bounded = journal.recoveryEntries(1) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(listOf(oldest), bounded.entries.map { it.fingerprint })
        val all = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(listOf(oldest, newest), all.entries.map { it.fingerprint })
        assertEquals(AuroraSubscriptionId(3), all.entries.last().subscriptionId)
        journal.clear()
        val empty = journal.recoveryEntries(1) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun corruptStoredEntryIsQuarantinedWithoutBlockingHealthyRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-enumeration-corrupt-test"
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val poisoned = fingerprint(41)
        val healthy = fingerprint(42)
        assertTrue(journal.begin(poisoned, 100L, 90L, null, providerDigest(41)))
        assertTrue(journal.markStored(poisoned, ProviderMessageId(ProviderKind.SMS, 111L), ConversationId(211L)))
        assertTrue(journal.begin(healthy, 200L, 190L, null, providerDigest(42)))
        assertTrue(journal.markStored(healthy, ProviderMessageId(ProviderKind.SMS, 112L), ConversationId(212L)))
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString(journalKey(poisoned), "corrupt").commit())

        val recovered = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(listOf(healthy), recovered.entries.map { it.fingerprint })
        val quarantine = journal.lookup(poisoned) as IncomingSmsReplayJournal.LookupResult.Quarantined
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.MALFORMED_JOURNAL_RECORD,
            quarantine.entry.reason,
        )
        val recreated = IncomingSmsReplayJournal(context, name)
        assertTrue(recreated.lookup(poisoned) is IncomingSmsReplayJournal.LookupResult.Quarantined)
        assertFalse(recreated.begin(poisoned, 300L, 290L, null, providerDigest(41)))
        assertTrue(runCatching { journal.recoveryEntries(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { journal.recoveryEntries(513) }.exceptionOrNull() is IllegalArgumentException)
        journal.clear()
    }

    @Test
    fun pendingProviderCheckpointIsRecoverableAndNonStringEntryIsQuarantined() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-pending-recovery-test"
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val fingerprint = fingerprint(51)
        val digest = IncomingSmsProviderContentDigest.fromContent(
            sender = "+15550102020",
            body = "provider-backed body",
        )
        assertTrue(journal.begin(fingerprint, 2_000L, 1_900L, null, digest))

        val pending = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(listOf(fingerprint), pending.entries.map { it.fingerprint })
        assertEquals(digest, pending.entries.single().providerContentDigest)
        val serialized = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toString()
        assertFalse(serialized.contains("+15550102020"))
        assertFalse(serialized.contains("provider-backed body"))

        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entryKey = preferences.all.keys.single()
        assertTrue(preferences.edit().putLong(entryKey, 7L).commit())
        assertTrue(journal.lookup(fingerprint) is IncomingSmsReplayJournal.LookupResult.Corrupt)
        val recovered = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertTrue(recovered.entries.isEmpty())
        assertTrue(journal.lookup(fingerprint) is IncomingSmsReplayJournal.LookupResult.Quarantined)
        assertFalse(journal.begin(fingerprint, 2_000L, 1_900L, null, digest))
        journal.clear()
    }

    @Test
    fun checksummedEntryRejectsValidLookingMutationAndDifferentFingerprintKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-checksum-test"
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            nowMillis = { 100L },
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val first = fingerprint(61)
        val second = fingerprint(62)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        assertTrue(journal.begin(first, 90L, 80L, null, providerDigest(61)))
        val firstKey = journalKey(first)
        val encoded = preferences.getString(firstKey, null)!!
        val fields = encoded.split(',').toMutableList()
        assertEquals(11, fields.size)
        assertEquals("4", fields[0])

        fields[8] = "101"
        assertTrue(preferences.edit().putString(firstKey, fields.joinToString(",")).commit())
        assertTrue(journal.lookup(first) is IncomingSmsReplayJournal.LookupResult.Corrupt)

        journal.clear()
        assertTrue(journal.begin(first, 90L, 80L, null, providerDigest(61)))
        val boundValue = preferences.getString(firstKey, null)!!
        assertTrue(preferences.edit().putString(journalKey(second), boundValue).commit())
        assertTrue(journal.lookup(first) is IncomingSmsReplayJournal.LookupResult.Found)
        assertTrue(journal.lookup(second) is IncomingSmsReplayJournal.LookupResult.Corrupt)
        val recovered = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(listOf(first), recovered.entries.map { it.fingerprint })
        assertTrue(journal.lookup(second) is IncomingSmsReplayJournal.LookupResult.Quarantined)
        journal.clear()
    }

    @Test
    fun canonicalLegacyVersionsMigrateOnReadAndAlternateNumericEncodingFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-legacy-migration-test"
        val journal = IncomingSmsReplayJournal(context, name)
        journal.clear()
        val legacyTwoFingerprint = fingerprint(71)
        val legacyThreeFingerprint = fingerprint(72)
        val invalidFingerprint = fingerprint(73)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val digest = providerDigest(72).toStorageToken()
        val legacyTwo = "2,$RECOVERY_TOKEN,P,0,0,100,90,-1,101"
        val legacyThree = "3,$RECOVERY_TOKEN,P,0,0,200,190,2,201,$digest"
        val nonCanonicalLegacy = "3,$RECOVERY_TOKEN,P,00,0,300,290,-1,301,$digest"
        assertTrue(
            preferences.edit()
                .putString(journalKey(legacyTwoFingerprint), legacyTwo)
                .putString(journalKey(legacyThreeFingerprint), legacyThree)
                .commit(),
        )

        val legacyTwoEntry = journal.lookup(legacyTwoFingerprint) as IncomingSmsReplayJournal.LookupResult.Found
        assertEquals(null, legacyTwoEntry.entry.providerContentDigest)
        val migratedTwo = preferences.getString(journalKey(legacyTwoFingerprint), null)!!.split(',')
        assertEquals(11, migratedTwo.size)
        assertEquals("4", migratedTwo[0])
        assertEquals("-", migratedTwo[9])

        val legacyThreeEntry = journal.lookup(legacyThreeFingerprint) as IncomingSmsReplayJournal.LookupResult.Found
        assertEquals(providerDigest(72), legacyThreeEntry.entry.providerContentDigest)
        val migratedThree = preferences.getString(journalKey(legacyThreeFingerprint), null)!!.split(',')
        assertEquals(11, migratedThree.size)
        assertEquals("4", migratedThree[0])
        assertEquals(digest, migratedThree[9])
        val unresolved = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(
            setOf(legacyTwoFingerprint, legacyThreeFingerprint),
            unresolved.entries.mapTo(mutableSetOf()) { it.fingerprint },
        )

        assertTrue(
            preferences.edit()
                .putString(journalKey(invalidFingerprint), nonCanonicalLegacy)
                .commit(),
        )
        assertTrue(journal.lookup(invalidFingerprint) is IncomingSmsReplayJournal.LookupResult.Corrupt)
        val afterPoison = journal.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success
        assertEquals(
            setOf(legacyTwoFingerprint, legacyThreeFingerprint),
            afterPoison.entries.mapTo(mutableSetOf()) { it.fingerprint },
        )
        assertTrue(journal.lookup(invalidFingerprint) is IncomingSmsReplayJournal.LookupResult.Quarantined)
        journal.clear()
    }

    @Test
    fun providerTerminalQuarantineIsKeyBoundDurableAndCountsAgainstCapacity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "incoming-replay-quarantine-capacity-test"
        var now = 400L
        val journal = IncomingSmsReplayJournal(
            context,
            name,
            maximumEntries = 2,
            nowMillis = { now++ },
            newRecoveryToken = { RECOVERY_TOKEN },
        )
        journal.clear()
        val first = fingerprint(81)
        val second = fingerprint(82)
        val third = fingerprint(83)
        assertTrue(journal.begin(first, 100L, 90L, null, providerDigest(81)))
        assertTrue(journal.begin(second, 200L, 190L, null, providerDigest(82)))
        assertTrue(
            journal.quarantine(
                first,
                IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISSING,
            ),
        )
        assertTrue(
            journal.quarantine(
                second,
                IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISMATCH,
            ),
        )

        assertFalse(journal.begin(first, 100L, 90L, null, providerDigest(81)))
        assertFalse(journal.begin(third, 300L, 290L, null, providerDigest(83)))
        val recreated = IncomingSmsReplayJournal(context, name, maximumEntries = 2)
        val firstEvidence = recreated.lookup(first) as IncomingSmsReplayJournal.LookupResult.Quarantined
        val secondEvidence = recreated.lookup(second) as IncomingSmsReplayJournal.LookupResult.Quarantined
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISSING,
            firstEvidence.entry.reason,
        )
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISMATCH,
            secondEvidence.entry.reason,
        )
        assertTrue(
            (recreated.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success)
                .entries.isEmpty(),
        )
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val copiedTombstone = preferences.getString(journalKey(first), null)!!
        assertTrue(preferences.edit().putString(journalKey(third), copiedTombstone).commit())
        assertTrue(recreated.lookup(third) is IncomingSmsReplayJournal.LookupResult.Corrupt)
        assertTrue(
            (recreated.recoveryEntries(64) as IncomingSmsReplayJournal.RecoveryEntriesResult.Success)
                .entries.isEmpty(),
        )
        val reboundEvidence = recreated.lookup(third) as IncomingSmsReplayJournal.LookupResult.Quarantined
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.MALFORMED_JOURNAL_RECORD,
            reboundEvidence.entry.reason,
        )
        journal.clear()
    }

    private fun fingerprint(seed: Int): MessageDeliveryFingerprint =
        MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (seed + it).toByte() },
        )

    private fun providerDigest(seed: Int): IncomingSmsProviderContentDigest =
        IncomingSmsProviderContentDigest.fromContent("sender-$seed", "body-$seed")

    private fun journalKey(fingerprint: MessageDeliveryFingerprint): String =
        "delivery.${fingerprint.toStorageToken()}"

    private companion object {
        const val RECOVERY_TOKEN = "123e4567-e89b-42d3-a456-426614174000"
    }
}
