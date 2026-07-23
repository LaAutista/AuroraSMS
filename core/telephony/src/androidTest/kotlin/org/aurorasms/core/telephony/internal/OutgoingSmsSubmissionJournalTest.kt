// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutgoingSmsSubmissionJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exactLifecycleSurvivesStoreReopenAndExpiresOnlyUnknownTombstone() = withStore { name ->
        var now = 1_000L
        val first = journal(name, nowMillis = { now })

        assertTrue(first.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(prepared = true), first.stateView())

        val reopened = journal(name, nowMillis = { now })
        assertTrue(reopened.recordSubmitting(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(submitting = true), reopened.stateView())
        assertTrue(reopened.recordSubmissionUnknown(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(unknown = true), reopened.stateView())

        now = Long.MAX_VALUE
        assertEquals(StateView(), reopened.stateView())
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun knownUnsentAcknowledgementIsExactAndIdempotentOnlyWhenMissing() = withStore { name ->
        val journal = journal(name)

        assertTrue(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertFalse(
            journal.acknowledgeKnownUnsent(OPERATION, OTHER_PROVIDER, CONVERSATION, UNIT_COUNT),
        )
        assertEquals(StateView(prepared = true), journal.stateView())
        assertTrue(journal.acknowledgeKnownUnsent(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertTrue(journal.acknowledgeKnownUnsent(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(), journal.stateView())
    }

    @Test
    fun checksumAndCanonicalEncodingFailClosedAfterValidLookingMutation() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = requireNotNull(preferences.all.keys.singleOrNull())
        val fields = requireNotNull(preferences.getString(key, null)).split('|').toMutableList()
        fields[2] = OTHER_PROVIDER.value.toString()
        assertTrue(preferences.edit().putString(key, fields.joinToString("|")).commit())

        assertTrue(
            journal.recoverySnapshot() is
                OutgoingSmsSubmissionJournal.RecoverySnapshotResult.PersistenceFailure,
        )
        assertFalse(journal.recordSubmitting(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
    }

    @Test
    fun correctlyChecksummedInlineNamespaceRecordFailsClosedOnDecode() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val originalKey = requireNotNull(preferences.all.keys.singleOrNull())
        val fields = requireNotNull(preferences.getString(originalKey, null))
            .split('|')
            .toMutableList()
        val highOperationId = INLINE_REPLY_OPERATION_ID_BOUNDARY
        val highKey = "operation:$highOperationId"
        fields[1] = highOperationId.toString()
        val payload = fields.take(8).joinToString("|")
        fields[8] = sha256("$highKey\n$payload")
        assertTrue(
            preferences.edit()
                .remove(originalKey)
                .putString(highKey, fields.joinToString("|"))
                .commit(),
        )

        assertEquals(
            OutgoingSmsSubmissionJournal.RecoverySnapshotResult.PersistenceFailure,
            journal.recoverySnapshot(),
        )
    }

    @Test
    fun grandfatheredTransportRecordInComposerRangeRemainsReadableAfterReopen() =
        withStore { name ->
            val inheritedOperation = MessageId(
                ProviderKind.PENDING_OPERATION,
                COMPOSER_OPERATION_ID_BOUNDARY + 17L,
            )
            val first = journal(name)

            assertTrue(
                first.recordPrepared(
                    inheritedOperation,
                    PROVIDER,
                    CONVERSATION,
                    UNIT_COUNT,
                ),
            )

            val reopened = journal(name)
            val snapshot = reopened.recoverySnapshot() as
                OutgoingSmsSubmissionJournal.RecoverySnapshotResult.Available
            assertEquals(listOf(inheritedOperation.value), snapshot.records.map { it.operationId })
            assertTrue(
                reopened.recordSubmitting(
                    inheritedOperation,
                    PROVIDER,
                    CONVERSATION,
                    UNIT_COUNT,
                ),
            )
        }

    @Test
    fun knownUnsentQuarantineIsContentFreeAndExpiresAsTombstone() = withStore { name ->
        var now = 1_000L
        val journal = journal(name, nowMillis = { now })

        assertTrue(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        now += EIGHT_DAYS_MILLIS
        assertTrue(
            journal.recordKnownUnsentQuarantined(
                OPERATION,
                PROVIDER,
                CONVERSATION,
                UNIT_COUNT,
            ),
        )
        assertEquals(StateView(quarantined = true), journal.stateView())
        val encoded = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.single()
        assertTrue(encoded is String)
        assertFalse((encoded as String).contains("recipient", ignoreCase = true))

        now += EIGHT_DAYS_MILLIS
        assertEquals(StateView(), journal.stateView())
    }

    @Test
    fun nonAdvancingExpiryFailsClosedWithoutWritingAnUndecodableRecord() = withStore { name ->
        val journal = journal(name, nowMillis = { Long.MAX_VALUE })

        assertFalse(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(), journal.stateView())
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun activeCapacityNeverEvictsAndInlineNamespaceIsRejected() = withStore { name ->
        var now = 1_000L
        val journal = journal(name, maximumEntries = 1, nowMillis = { now })
        val secondOperation = MessageId(ProviderKind.PENDING_OPERATION, OPERATION.value + 1L)
        val inlineOperation = MessageId(
            ProviderKind.PENDING_OPERATION,
            INLINE_REPLY_OPERATION_ID_BOUNDARY,
        )

        assertTrue(journal.recordPrepared(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertFalse(journal.recordPrepared(secondOperation, OTHER_PROVIDER, CONVERSATION, UNIT_COUNT))
        assertFalse(journal.recordPrepared(inlineOperation, OTHER_PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(prepared = true), journal.stateView())

        assertTrue(journal.recordSubmitting(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        assertTrue(journal.recordSubmissionUnknown(OPERATION, PROVIDER, CONVERSATION, UNIT_COUNT))
        now += EIGHT_DAYS_MILLIS
        assertTrue(journal.recordPrepared(secondOperation, OTHER_PROVIDER, CONVERSATION, UNIT_COUNT))
        assertEquals(StateView(prepared = true), journal.stateView())
    }

    private fun journal(
        name: String,
        maximumEntries: Int = 128,
        nowMillis: () -> Long = { 1_000L },
    ): OutgoingSmsSubmissionJournal = OutgoingSmsSubmissionJournal(
        context = context,
        preferenceName = name,
        maximumEntries = maximumEntries,
        nowMillis = nowMillis,
    )

    private fun OutgoingSmsSubmissionJournal.stateView(): StateView {
        val records = (
            recoverySnapshot() as
                OutgoingSmsSubmissionJournal.RecoverySnapshotResult.Available
            ).records
        return StateView(
            prepared = records.any { it.state == OutgoingSmsSubmissionJournal.State.PREPARED },
            submitting = records.any { it.state == OutgoingSmsSubmissionJournal.State.SUBMITTING },
            unknown = records.any { it.state == OutgoingSmsSubmissionJournal.State.SUBMISSION_UNKNOWN },
            quarantined = records.any {
                it.state == OutgoingSmsSubmissionJournal.State.KNOWN_UNSENT_QUARANTINED
            },
        )
    }

    private inline fun withStore(block: (String) -> Unit) {
        val name = "aurora_outgoing_submission_test_${UUID.randomUUID()}"
        try {
            block(name)
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private data class StateView(
        val prepared: Boolean = false,
        val submitting: Boolean = false,
        val unknown: Boolean = false,
        val quarantined: Boolean = false,
    )

    private companion object {
        val OPERATION = MessageId(ProviderKind.PENDING_OPERATION, 71L)
        val PROVIDER = ProviderMessageId(ProviderKind.SMS, 73L)
        val OTHER_PROVIDER = ProviderMessageId(ProviderKind.SMS, 74L)
        val CONVERSATION = ConversationId(75L)
        const val UNIT_COUNT = 3
        const val EIGHT_DAYS_MILLIS = 8L * 24L * 60L * 60L * 1_000L
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
