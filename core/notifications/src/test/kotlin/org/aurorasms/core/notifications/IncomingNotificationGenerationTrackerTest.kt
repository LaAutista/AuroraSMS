// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingNotificationGenerationTrackerTest {
    @Test
    fun latestGenerationSurvivesTrackerRecreationAndExactForget() {
        val store = InMemoryIncomingNotificationGenerationStore()
        val first = IncomingNotificationGenerationTracker(store)

        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            first.record(CONVERSATION_ONE, MESSAGE_ONE),
        )
        val recreated = IncomingNotificationGenerationTracker(store)
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            recreated.lookup(CONVERSATION_ONE),
        )

        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            recreated.record(CONVERSATION_ONE, MESSAGE_ONE_REPLACEMENT),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE_REPLACEMENT),
            first.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Success,
            first.forgetIfCurrent(CONVERSATION_ONE, MESSAGE_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE_REPLACEMENT),
            recreated.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Success,
            recreated.forgetIfCurrent(CONVERSATION_ONE, MESSAGE_ONE_REPLACEMENT),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            IncomingNotificationGenerationTracker(store).lookup(CONVERSATION_ONE),
        )
    }

    @Test
    fun authoritativeReconciliationClearsOverflowWhileReclaimingOnlyAbsentEntries() {
        val store = InMemoryIncomingNotificationGenerationStore()
        val tracker = IncomingNotificationGenerationTracker(
            store = store,
            maximumTrackedConversations = 2,
        )

        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            tracker.record(CONVERSATION_ONE, MESSAGE_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            tracker.record(CONVERSATION_TWO, MESSAGE_TWO),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Full,
            tracker.record(CONVERSATION_THREE, MESSAGE_THREE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            tracker.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_TWO),
            tracker.lookup(CONVERSATION_TWO),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Success,
            tracker.markUntrackedOverflow(),
        )

        val recreated = IncomingNotificationGenerationTracker(
            store = store,
            maximumTrackedConversations = 2,
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.UntrackedAfterOverflow,
            recreated.lookup(CONVERSATION_THREE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Success,
            recreated.reconcileProvablyAbsent(setOf(CONVERSATION_TWO)),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            recreated.lookup(CONVERSATION_THREE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            recreated.record(CONVERSATION_THREE, MESSAGE_THREE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            recreated.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_TWO),
            recreated.lookup(CONVERSATION_TWO),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_THREE),
            recreated.lookup(CONVERSATION_THREE),
        )
    }

    @Test
    fun corruptStateFailsClosedWithoutBeingRewritten() {
        val store = InMemoryIncomingNotificationGenerationStore(encoded = "not-a-generation-state")
        val tracker = IncomingNotificationGenerationTracker(store)

        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Corrupt,
            tracker.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Corrupt,
            tracker.record(CONVERSATION_ONE, MESSAGE_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Corrupt,
            tracker.reconcileProvablyAbsent(emptySet()),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.Corrupt,
            tracker.markUntrackedOverflow(),
        )
        assertEquals("not-a-generation-state", store.encoded)
    }

    @Test
    fun readAndWriteFailuresNeverCreateFalseOrderingEvidence() {
        val failedWriteStore = ControllableStore(writeSucceeds = false)
        val failedWriteTracker = IncomingNotificationGenerationTracker(failedWriteStore)
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.PersistenceFailure,
            failedWriteTracker.record(CONVERSATION_ONE, MESSAGE_ONE),
        )
        assertNull(failedWriteStore.encoded)

        val seededStore = InMemoryIncomingNotificationGenerationStore()
        IncomingNotificationGenerationTracker(seededStore).record(CONVERSATION_ONE, MESSAGE_ONE)
        val failedReconcileStore = ControllableStore(
            encoded = seededStore.encoded,
            writeSucceeds = false,
        )
        val failedReconcileTracker = IncomingNotificationGenerationTracker(failedReconcileStore)
        assertEquals(
            IncomingNotificationGenerationTracker.MutationResult.PersistenceFailure,
            failedReconcileTracker.reconcileProvablyAbsent(emptySet()),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            failedReconcileTracker.lookup(CONVERSATION_ONE),
        )

        failedReconcileStore.readResult =
            IncomingNotificationGenerationStore.ReadResult.PersistenceFailure
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.PersistenceFailure,
            failedReconcileTracker.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.PersistenceFailure,
            failedReconcileTracker.record(CONVERSATION_TWO, MESSAGE_TWO),
        )
    }

    @Test
    fun checksummedStateRejectsValidLookingOneCharacterMutation() {
        val store = InMemoryIncomingNotificationGenerationStore()
        val tracker = IncomingNotificationGenerationTracker(store)
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            tracker.record(CONVERSATION_ONE, MESSAGE_ONE),
        )
        val encoded = store.encoded!!
        assertEquals("2", encoded.substringBefore('|'))
        store.encoded = encoded.replace("\n1|SMS|11", "\n1|SMS|12")

        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Corrupt,
            tracker.lookup(CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Corrupt,
            tracker.record(CONVERSATION_TWO, MESSAGE_TWO),
        )
        assertEquals(encoded.replace("\n1|SMS|11", "\n1|SMS|12"), store.encoded)
    }

    @Test
    fun canonicalLegacyStateMigratesOnReadAndAlternateEncodingFailsClosed() {
        val legacy = "1|1\n1|SMS|11\n2|MMS|21"
        val store = InMemoryIncomingNotificationGenerationStore(encoded = legacy)
        val tracker = IncomingNotificationGenerationTracker(store)

        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            tracker.lookup(CONVERSATION_ONE),
        )
        val migrated = store.encoded!!
        assertEquals("2", migrated.substringBefore('|'))
        assertEquals(3, migrated.substringBefore('\n').split('|').size)
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_TWO),
            IncomingNotificationGenerationTracker(store).lookup(CONVERSATION_TWO),
        )

        store.encoded = "1|0\n01|SMS|11"
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Corrupt,
            tracker.lookup(CONVERSATION_ONE),
        )
        assertEquals("1|0\n01|SMS|11", store.encoded)
    }

    private class ControllableStore(
        var encoded: String? = null,
        var writeSucceeds: Boolean = true,
    ) : IncomingNotificationGenerationStore {
        var readResult: IncomingNotificationGenerationStore.ReadResult? = null

        override fun read(): IncomingNotificationGenerationStore.ReadResult =
            readResult ?: IncomingNotificationGenerationStore.ReadResult.Available(encoded)

        override fun write(encoded: String): Boolean {
            if (!writeSucceeds) return false
            this.encoded = encoded
            return true
        }
    }

    private companion object {
        val CONVERSATION_ONE = ConversationId(1L)
        val CONVERSATION_TWO = ConversationId(2L)
        val CONVERSATION_THREE = ConversationId(3L)
        val MESSAGE_ONE = MessageId(ProviderKind.SMS, 11L)
        val MESSAGE_ONE_REPLACEMENT = MessageId(ProviderKind.SMS, 12L)
        val MESSAGE_TWO = MessageId(ProviderKind.MMS, 21L)
        val MESSAGE_THREE = MessageId(ProviderKind.SMS, 31L)
    }
}
