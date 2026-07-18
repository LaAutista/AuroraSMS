// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesReplyOperationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearStore() {
        preferences().edit().clear().commit()
    }

    @Test
    fun multipartBitmapAndSuccessTombstoneSurviveRecreation() {
        val first = registry(identifierGenerator = { 7_001L })
        val operationId = first.reserveOperation(CONVERSATION)
        prepareSubmitting(first, operationId, unitCount = 3)
        assertSame(ReplyOperationSubmittedResult.Tracked, first.recordSubmitted(operationId, 3))
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = false),
            first.recordSent(operationId, unitIndex = 2, unitCount = 3),
        )

        val recreated = registry(identifierGenerator = { 7_002L })
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = true),
            recreated.recordSent(operationId, unitIndex = 2, unitCount = 3),
        )
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = false),
            recreated.recordSent(operationId, unitIndex = 0, unitCount = 3),
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            recreated.recordSent(operationId, unitIndex = 1, unitCount = 3),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            recreated.acknowledgeSuccessCancellation(operationId),
        )

        val afterAck = registry(identifierGenerator = { 7_003L })
        assertEquals(
            ReplyOperationSentResult.SuccessComplete(CONVERSATION, SOURCE_MESSAGE),
            afterAck.recordSent(operationId, unitIndex = 1, unitCount = 3),
        )
        assertEquals(1, preferences().all.size)
    }

    @Test
    fun callbackBeforeSubmittedCannotBeRecreatedOrDowngradedAfterSuccessAck() {
        val registry = registry(identifierGenerator = { 7_101L })
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(
                operationId,
                unitIndex = 0,
                unitCount = 1,
                providerMessageId = DEFAULT_PROVIDER_MESSAGE,
            ),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeSuccessCancellation(operationId),
        )

        val recreated = registry(identifierGenerator = { 7_102L })
        assertSame(ReplyOperationSubmittedResult.SuccessComplete, recreated.recordSubmitted(operationId, 1))
        assertEquals(
            ReplyOperationFailureResult.SuccessTerminal(CONVERSATION),
            recreated.markFailurePending(operationId),
        )
    }

    @Test
    fun failurePendingAndNotifiedTombstoneSurviveRecreation() {
        val first = registry(identifierGenerator = { 7_201L })
        val operationId = first.reserveOperation(CONVERSATION)
        prepareSubmitting(first, operationId, unitCount = 2)
        assertSame(ReplyOperationSubmittedResult.Tracked, first.recordSubmitted(operationId, 2))
        assertTrue(first.recordSent(operationId, 1, 2) is ReplyOperationSentResult.Pending)

        assertEquals(
            ReplyOperationFailureResult.Pending(CONVERSATION, duplicate = false),
            first.markFailurePending(operationId, unitIndex = 0, unitCount = 2),
        )
        val recreated = registry(identifierGenerator = { 7_202L })
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(
                listOf(
                    ReplyOperationPendingFailure(
                        operationId,
                        CONVERSATION,
                        SOURCE_MESSAGE,
                        ReplyOperationFailureKind.KNOWN_UNSENT,
                    ),
                ),
            ),
            recreated.pendingFailures(),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            recreated.acknowledgeFailureNotification(operationId),
        )

        val afterAck = registry(identifierGenerator = { 7_203L })
        assertSame(ReplyOperationSubmittedResult.FailureNotified, afterAck.recordSubmitted(operationId, 2))
        assertEquals(
            ReplyOperationSentResult.FailureNotified(CONVERSATION),
            afterAck.recordSent(operationId, unitIndex = 0, unitCount = 2),
        )
        assertEquals(
            ReplyOperationSuccessResult.FailureNotified(CONVERSATION),
            afterAck.markSuccessPending(operationId),
        )
        assertEquals(1, preferences().all.size)
    }

    @Test
    fun saturationRejectsUntilExactExpiryCleanup() {
        var nowMillis = 10_000L
        val registry = registry(
            maximumEntries = 2,
            retentionMillis = 100L,
            clockMillis = { nowMillis },
            identifierGenerator = SequenceGenerator(7_301L, 7_302L, 7_303L, 7_304L, 7_305L),
        )
        assertTrue(
            registry.reserve(CONVERSATION, SOURCE_MESSAGE) is
                ReplyOperationReservationResult.Reserved,
        )
        assertTrue(
            registry.reserve(OTHER_CONVERSATION, OTHER_SOURCE_MESSAGE) is
                ReplyOperationReservationResult.Reserved,
        )
        assertSame(
            ReplyOperationReservationResult.Full,
            registry.reserve(THIRD_CONVERSATION, THIRD_SOURCE_MESSAGE),
        )

        nowMillis = 10_099L
        assertEquals(ReplyOperationCleanupResult.Success(0), registry.cleanupExpired())
        assertSame(
            ReplyOperationReservationResult.Full,
            registry.reserve(THIRD_CONVERSATION, THIRD_SOURCE_MESSAGE),
        )

        nowMillis = 10_100L
        assertEquals(ReplyOperationCleanupResult.Success(2), registry.cleanupExpired())
        assertTrue(
            registry.reserve(THIRD_CONVERSATION, THIRD_SOURCE_MESSAGE) is
                ReplyOperationReservationResult.Reserved,
        )
    }

    @Test
    fun terminalTombstoneKeepsCapacitySlotUntilExpiry() {
        var nowMillis = 30_000L
        val registry = registry(
            maximumEntries = 1,
            retentionMillis = 100L,
            clockMillis = { nowMillis },
            identifierGenerator = SequenceGenerator(7_351L, 7_352L, 7_353L),
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        nowMillis = 30_050L
        assertTrue(registry.markFailurePending(operationId) is ReplyOperationFailureResult.Pending)
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeFailureNotification(operationId),
        )
        assertSame(
            ReplyOperationReservationResult.Full,
            registry.reserve(OTHER_CONVERSATION, OTHER_SOURCE_MESSAGE),
        )

        nowMillis = 30_149L
        assertEquals(ReplyOperationCleanupResult.Success(0), registry.cleanupExpired())
        nowMillis = 30_150L
        assertEquals(ReplyOperationCleanupResult.Success(1), registry.cleanupExpired())
        assertTrue(
            registry.reserve(OTHER_CONVERSATION, OTHER_SOURCE_MESSAGE) is
                ReplyOperationReservationResult.Reserved,
        )
    }

    @Test
    fun malformedCanonicalRecordsAreRetainedAndExactCallbacksFailClosed() {
        assertTrue(
            preferences().edit()
                .putString("operation.7401", "malformed")
                .putInt("operation.7402", 42)
                .putString("operation.7403", "1|501|1000|2000|3|ff")
                .putString("operation.07404", "1|501|1000|2000|0|-")
                .putString("operation.not-a-number", "1|501|1000|2000|0|-")
                .putString("unexpected", "value")
                .commit(),
        )
        val registry = registry(
            maximumEntries = 4,
            identifierGenerator = SequenceGenerator(7_410L, 7_411L),
        )

        assertSame(
            ReplyOperationSentResult.CorruptOwnership,
            registry.recordSent(legacyPendingOperation(7_401L), 0, 1),
        )
        assertSame(
            ReplyOperationFailureResult.CorruptOwnership,
            registry.markFailurePending(legacyPendingOperation(7_402L)),
        )
        assertSame(
            ReplyOperationPendingProviderUpdateResult.CorruptOwnership,
            registry.pendingProviderUpdate(legacyPendingOperation(7_403L)),
        )
        assertEquals(
            ReplyOperationReservationResult.Reserved(pendingOperation(7_410L)),
            registry.reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        assertEquals(
            setOf(
                "operation.7401",
                "operation.7402",
                "operation.7403",
                "operation.${inlineOperationValue(7_410L)}",
            ),
            preferences().all.keys,
        )
    }

    @Test
    fun versionOneRecordRetainsSafeSubmissionUnknownBehaviorInVersionTwoEnvelope() {
        assertTrue(
            preferences().edit()
                .putString("operation.7451", "1|501|1000|2000|0|-")
                .commit(),
        )
        val registry = registry(identifierGenerator = { 7_452L })

        assertSame(
            ReplyOperationSubmittedResult.SubmissionUnknownPending,
            registry.recordSubmitted(legacyPendingOperation(7_451L), 1),
        )
        assertTrue(preferences().getString("operation.7451", null)?.startsWith("2|") == true)
    }

    @Test
    fun checksumRejectsValidLookingPayloadMutation() {
        val first = registry(identifierGenerator = { 7_461L })
        val operationId = first.reserveOperation(CONVERSATION)
        val key = "operation.${operationId.value}"
        val original = requireNotNull(preferences().getString(key, null))
        val mutated = original.split('|').toMutableList().also { fields ->
            assertEquals("4", fields[0])
            assertEquals(CONVERSATION.value.toString(), fields[1])
            fields[1] = OTHER_CONVERSATION.value.toString()
        }.joinToString("|")
        assertTrue(preferences().edit().putString(key, mutated).commit())

        val recreated = registry(identifierGenerator = { 7_462L })
        assertSame(
            ReplyOperationSentResult.CorruptOwnership,
            recreated.recordSent(operationId, 0, 1, DEFAULT_PROVIDER_MESSAGE),
        )
        assertEquals(original.length, mutated.length)
    }

    @Test
    fun checksumValidFalseSuccessShapeFailsClosed() {
        val operationId = inlineOperationValue(7_471L)
        val payload = listOf(
            "4",
            CONVERSATION.value.toString(),
            "1000",
            "2000",
            "sp",
            "2",
            "01",
            "s",
            SOURCE_MESSAGE.value.toString(),
            "8471",
            "c",
            "0",
        )
        assertTrue(
            preferences().edit()
                .putString(
                    "operation.$operationId",
                    (payload + versionFourChecksum(operationId, payload)).joinToString("|"),
                )
                .commit(),
        )

        val registry = registry(identifierGenerator = { 7_472L })
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(emptyList()),
            registry.pendingSuccesses(),
        )
        assertSame(
            ReplyOperationSentResult.CorruptOwnership,
            registry.recordSent(
                MessageId(ProviderKind.PENDING_OPERATION, operationId),
                1,
                2,
                ProviderMessageId(ProviderKind.SMS, 8_471L),
            ),
        )
    }

    @Test
    fun checksumValidMissingProviderSuccessAndContradictoryFailureFailClosed() {
        val malformedPayloads = listOf(
            listOf(
                "4", CONVERSATION.value.toString(), "1000", "2000", "sp", "1", "01",
                "s", SOURCE_MESSAGE.value.toString(), "-", "c", "0",
            ),
            listOf(
                "4", CONVERSATION.value.toString(), "1000", "2000", "fp", "0", "-",
                "s", SOURCE_MESSAGE.value.toString(), "8472", "f", "1",
            ),
            listOf(
                "4", CONVERSATION.value.toString(), "1000", "2000", "fp", "1", "00",
                "s", SOURCE_MESSAGE.value.toString(), "-", "-", "0",
            ),
        )

        malformedPayloads.forEachIndexed { index, payload ->
            val operationId = inlineOperationValue(7_472L + index)
            assertTrue(
                preferences().edit().putString(
                    "operation.$operationId",
                    (payload + versionFourChecksum(operationId, payload)).joinToString("|"),
                ).commit(),
            )
            assertSame(
                ReplyOperationSentResult.CorruptOwnership,
                registry(identifierGenerator = { 7_480L + index }).recordSent(
                    MessageId(ProviderKind.PENDING_OPERATION, operationId),
                    0,
                    1,
                    ProviderMessageId(ProviderKind.SMS, 8_472L + index),
                ),
            )
            preferences().edit().clear().commit()
        }
    }

    @Test
    fun checksumValidPermissionDeniedFailureWithoutProviderRowRemainsOwned() {
        val operationId = inlineOperationValue(7_477L)
        val payload = listOf(
            "4", CONVERSATION.value.toString(), "1000", "2000", "fn", "1", "00",
            "s", SOURCE_MESSAGE.value.toString(), "-", "f", "0",
        )
        assertTrue(
            preferences().edit().putString(
                "operation.$operationId",
                (payload + versionFourChecksum(operationId, payload)).joinToString("|"),
            ).commit(),
        )

        assertEquals(
            ReplyOperationSentResult.FailureNotified(CONVERSATION),
            registry(identifierGenerator = { 7_478L }).recordSent(
                MessageId(ProviderKind.PENDING_OPERATION, operationId),
                unitIndex = 0,
                unitCount = 1,
                providerMessageId = DEFAULT_PROVIDER_MESSAGE,
            ),
        )
    }

    @Test
    fun versionThreeSuccessMigratesToKnownUnsentFailureWithoutProviderOutbox() {
        val operationId = inlineOperationValue(7_481L)
        assertTrue(
            preferences().edit()
                .putString(
                    "operation.$operationId",
                    "3|501|1000|2000|sc|1|01|s|5001|8481|c|1",
                )
                .commit(),
        )

        val registry = registry(identifierGenerator = { 7_482L })
        val pendingOperation = MessageId(ProviderKind.PENDING_OPERATION, operationId)
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(
                listOf(
                    ReplyOperationPendingFailure(
                        pendingOperation,
                        CONVERSATION,
                        SOURCE_MESSAGE,
                        ReplyOperationFailureKind.KNOWN_UNSENT,
                    ),
                ),
            ),
            registry.pendingFailures(),
        )
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(emptyList()),
            registry.pendingSuccesses(),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry.pendingProviderUpdate(pendingOperation),
        )
        assertEquals(
            ReplyOperationSuccessResult.FailurePending(CONVERSATION),
            registry.markSuccessPending(pendingOperation),
        )

        val migrated = requireNotNull(
            preferences().getString("operation.$operationId", null),
        ).split('|')
        assertEquals("4", migrated[0])
        assertEquals("fp", migrated[4])
        assertEquals("0", migrated[5])
        assertEquals("-", migrated[6])
        assertEquals("-", migrated[9])
        assertEquals("-", migrated[10])
        assertEquals("0", migrated[11])
        assertEquals(
            versionFourChecksum(operationId, migrated.dropLast(1)),
            migrated.last(),
        )
    }

    @Test
    fun activeAndTerminalRecordsContainNoSensitivePlaintext() {
        val registry = registry(identifierGenerator = { 7_501L })
        val operationId = registry.reserveOperation(CONVERSATION)
        assertPrivatePersistence()
        assertTrue(persistedPreferences().contains("|r|0|-|s|5001|-|-|0"))

        prepareSubmitting(registry, operationId, unitCount = 255)
        assertSame(ReplyOperationSubmittedResult.Tracked, registry.recordSubmitted(operationId, 255))
        assertTrue(
            registry.recordSent(operationId, unitIndex = 254, unitCount = 255) is
                ReplyOperationSentResult.Pending,
        )
        assertTrue(
            registry.markFailurePending(operationId, unitIndex = 0, unitCount = 255) is
                ReplyOperationFailureResult.Pending,
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeFailureNotification(operationId),
        )

        assertPrivatePersistence()
        assertTrue(persistedPreferences().contains("|fn|"))
    }

    @Test
    fun allTwoHundredFiftyFivePartsCanReachDurableSuccess() {
        val registry = registry(identifierGenerator = { 7_601L })
        val operationId = registry.reserveOperation(CONVERSATION)
        prepareSubmitting(registry, operationId, unitCount = 255)
        assertSame(ReplyOperationSubmittedResult.Tracked, registry.recordSubmitted(operationId, 255))

        (254 downTo 1).forEach { unitIndex ->
            assertTrue(
                registry.recordSent(operationId, unitIndex, 255) is ReplyOperationSentResult.Pending,
            )
        }
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(operationId, unitIndex = 0, unitCount = 255),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeSuccessCancellation(operationId),
        )
        assertEquals(1, preferences().all.size)
    }

    @Test
    fun providerOutboxSurvivesRecreationAndSupersededAckCannotClearIt() {
        val first = registry(identifierGenerator = { 7_651L })
        val operationId = first.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_651L)
        prepareSubmitting(first, operationId, providerId, unitCount = 1)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            first.recordSubmitted(operationId, 1, providerId),
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            first.recordSent(operationId, 0, 1, providerId),
        )

        val recreated = registry(identifierGenerator = { 7_652L })
        val complete = ReplyOperationProviderUpdate(
            operationId,
            providerId,
            SmsProviderStatus.COMPLETE,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(complete),
            recreated.pendingProviderUpdate(operationId),
        )
        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            recreated.recordDeliveryFailure(operationId, 0, 1, providerId),
        )
        assertSame(
            ReplyOperationProviderAcknowledgementResult.Stale,
            recreated.acknowledgeProviderUpdate(complete),
        )

        val afterCrash = registry(identifierGenerator = { 7_653L })
        val deliveryFailed = complete.copy(status = SmsProviderStatus.DELIVERY_FAILED)
        assertEquals(
            ReplyOperationPendingProviderUpdatesResult.Available(listOf(deliveryFailed)),
            afterCrash.pendingProviderUpdates(),
        )
        assertSame(
            ReplyOperationProviderAcknowledgementResult.Acknowledged,
            afterCrash.acknowledgeProviderUpdate(deliveryFailed),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry(identifierGenerator = { 7_654L }).pendingProviderUpdate(operationId),
        )
        assertTrue(persistedPreferences().contains("|s|5001|8651|d|0"))
    }

    @Test
    fun failureBeforeProviderBindingBecomesPendingOutboxAfterRecreation() {
        val first = registry(identifierGenerator = { 7_661L })
        val operationId = first.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(operationId))
        assertTrue(first.markFailurePending(operationId) is ReplyOperationFailureResult.Pending)
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            first.pendingProviderUpdate(operationId),
        )

        val providerId = ProviderMessageId(ProviderKind.SMS, 8_661L)
        val recreated = registry(identifierGenerator = { 7_662L })
        assertSame(
            ReplyOperationSubmittedResult.FailurePending,
            recreated.recordSubmitted(operationId, 1, providerId),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (registry(identifierGenerator = { 7_663L }).pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun deliveryBeforeSubmittedBindsCountAndMarksSentEvidence() {
        val registry = registry(identifierGenerator = { 7_671L })
        val operationId = registry.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_671L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertSame(
            ReplyOperationProviderStatusResult.Tracked,
            registry.recordDeliverySuccess(operationId, 0, 2, providerId),
        )
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = true),
            registry.recordSent(operationId, 0, 2, providerId),
        )
        assertSame(
            ReplyOperationProviderStatusResult.UnitCountMismatch,
            registry.recordDeliveryFailure(operationId, 0, 1, providerId),
        )
        assertTrue(
            registry.markFailurePending(operationId, providerId, 1, 2) is
                ReplyOperationFailureResult.Pending,
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (registry.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun deliveryEvidenceSurvivesRecreationAndResolvesLostSentCallbacks() {
        val first = registry(identifierGenerator = { 7_675L })
        val operationId = first.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_675L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(operationId))
        assertSame(
            ReplyOperationProviderStatusResult.Tracked,
            first.recordDeliverySuccess(operationId, 1, 2, providerId),
        )

        val recreated = registry(identifierGenerator = { 7_676L })
        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            recreated.recordDeliverySuccess(operationId, 0, 2, providerId),
        )
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(
                listOf(ReplyOperationPending(operationId, CONVERSATION, SOURCE_MESSAGE)),
            ),
            recreated.pendingSuccesses(),
        )
        assertEquals(
            SmsProviderStatus.COMPLETE,
            (recreated.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun malformedVersionThreeOutboxAndSourceRecordsConsumeCapacityFailClosed() {
        assertTrue(
            preferences().edit()
                .putString("operation.7681", "3|501|1000|2000|a|0|-|x|5001|-|-|0")
                .putString("operation.7682", "3|501|1000|2000|a|0|-|s|5001|-|c|1")
                .putString("operation.7683", "3|501|1000|2000|a|0|-|s|5001|8661|p|0")
                .commit(),
        )
        val fullRegistry = registry(maximumEntries = 3, identifierGenerator = { 7_684L })

        assertSame(
            ReplyOperationReservationResult.Full,
            fullRegistry.reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        assertSame(
            ReplyOperationSentResult.CorruptOwnership,
            fullRegistry.recordSent(legacyPendingOperation(7_681L), 0, 1),
        )
        assertSame(
            ReplyOperationFailureResult.CorruptOwnership,
            fullRegistry.markFailurePending(legacyPendingOperation(7_682L)),
        )
        val registry = registry(maximumEntries = 4, identifierGenerator = { 7_684L })
        assertEquals(
            ReplyOperationReservationResult.Reserved(pendingOperation(7_684L)),
            registry.reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        assertEquals(
            setOf(
                "operation.7681",
                "operation.7682",
                "operation.7683",
                "operation.${inlineOperationValue(7_684L)}",
            ),
            preferences().all.keys,
        )
    }

    @Test
    fun versionTwoRecordStillDecodesAndMigratesWithoutInventingSource() {
        assertTrue(
            preferences().edit()
                .putString("operation.7691", "2|501|1000|2000|sp|1|01")
                .commit(),
        )
        val registry = registry(identifierGenerator = { 7_692L })

        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(
                listOf(ReplyOperationPending(legacyPendingOperation(7_691L), CONVERSATION, null)),
            ),
            registry.pendingSuccesses(),
        )
        assertSame(
            ReplyOperationSubmittedResult.SuccessPending,
            registry.recordSubmitted(
                legacyPendingOperation(7_691L),
                1,
                ProviderMessageId(ProviderKind.SMS, 8_691L),
            ),
        )
        assertTrue(preferences().getString("operation.7691", null)?.startsWith("2|") == true)
    }

    @Test
    fun constructorRecoveryPersistsEveryDurableBoundaryExactlyOnce() {
        val first = registry(
            maximumEntries = 5,
            identifierGenerator = SequenceGenerator(7_711L, 7_712L, 7_713L, 7_714L, 7_715L),
        )
        val reserved = first.reserveOperation(CONVERSATION)
        val claimed = first.reserveOperation(CONVERSATION).also {
            assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(it))
        }
        val prepared = first.reserveOperation(CONVERSATION)
        val preparedProvider = ProviderMessageId(ProviderKind.SMS, 8_713L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(prepared))
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            first.recordPrepared(prepared, preparedProvider, 1),
        )
        val submitting = first.reserveOperation(CONVERSATION)
        val submittingProvider = ProviderMessageId(ProviderKind.SMS, 8_714L)
        prepareSubmitting(first, submitting, submittingProvider, 2)
        val submitted = first.reserveOperation(CONVERSATION)
        val submittedProvider = ProviderMessageId(ProviderKind.SMS, 8_715L)
        prepareSubmitting(first, submitted, submittedProvider, 1)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            first.recordSubmitted(submitted, 1, submittedProvider),
        )

        val recreated = registry(maximumEntries = 5, identifierGenerator = { 7_716L })
        val expected = ReplyOperationRecoveryResult.Recovered(
            knownUnsentCount = 2,
            preparedFailureCount = 1,
            submissionUnknownCount = 2,
            corruptCount = 0,
        )
        assertEquals(expected, recreated.recoverInheritedOperations())
        assertEquals(expected, recreated.recoverInheritedOperations())
        assertEquals(
            setOf(
                reserved to ReplyOperationFailureKind.KNOWN_UNSENT,
                claimed to ReplyOperationFailureKind.KNOWN_UNSENT,
                prepared to ReplyOperationFailureKind.KNOWN_UNSENT,
                submitting to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                submitted to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
            ),
            (recreated.pendingFailures() as ReplyOperationPendingFailuresResult.Available)
                .operations
                .map { it.operationId to it.failureKind }
                .toSet(),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (recreated.pendingProviderUpdate(prepared) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            recreated.pendingProviderUpdate(submitting),
        )
        assertTrue(persistedPreferences().contains("|fp|"))
        assertTrue(persistedPreferences().contains("|up|"))
    }

    @Test
    fun explicitInterruptionRecoveryAndLegacyActiveStatesFailClosedAcrossRecreation() {
        val first = registry(identifierGenerator = { 7_721L })
        val operationId = first.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_721L)
        prepareSubmitting(first, operationId, providerId, 2)
        val pending = ReplyOperationPendingFailure(
            operationId,
            CONVERSATION,
            SOURCE_MESSAGE,
            ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
        )
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Pending(pending, transitioned = true),
            first.recoverInterruptedOperation(operationId),
        )

        val recreated = registry(identifierGenerator = { 7_722L })
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Pending(pending, transitioned = false),
            recreated.recoverInterruptedOperation(operationId),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            recreated.acknowledgeFailureNotification(operationId),
        )
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Notified(pending),
            recreated.recoverInterruptedOperation(operationId),
        )

        assertTrue(preferences().edit().clear()
            .putString("operation.7723", "1|501|1000|2000|0|-")
            .putString("operation.7724", "2|501|1000|2000|a|0|-")
            .putString("operation.7725", "3|501|1000|2000|a|1|00|s|5001|8725|-|0")
            .commit())
        val legacy = registry(identifierGenerator = { 7_726L })
        val legacyV1 = legacyPendingOperation(7_723L)
        val legacyV2 = legacyPendingOperation(7_724L)
        val legacyV3 = legacyPendingOperation(7_725L)
        assertEquals(
            mapOf(
                legacyV1 to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                legacyV2 to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                legacyV3 to ReplyOperationFailureKind.KNOWN_UNSENT,
            ),
            (legacy.pendingFailures() as ReplyOperationPendingFailuresResult.Available)
                .operations
                .associate { failure -> failure.operationId to failure.failureKind },
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, null),
            legacy.recordSent(legacyV1, 0, 1),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            legacy.acknowledgeFailureNotification(legacyV2),
        )
        assertSame(
            ReplyOperationSubmittedResult.SubmissionUnknownNotified,
            legacy.recordSubmitted(legacyV2, 1),
        )
        assertEquals(
            ReplyOperationFailureResult.Pending(CONVERSATION, duplicate = true),
            legacy.markFailurePending(
                legacyV3,
                ProviderMessageId(ProviderKind.SMS, 8_725L),
            ),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (legacy.pendingProviderUpdate(legacyV3) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun expiredRecordIsRemovedBeforeAnyTerminalMutation() {
        var nowMillis = 20_000L
        val registry = registry(
            retentionMillis = 50L,
            clockMillis = { nowMillis },
            identifierGenerator = { 7_701L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        nowMillis = 20_050L

        assertSame(ReplyOperationSubmittedResult.Untracked, registry.recordSubmitted(operationId, 1))
        assertSame(ReplyOperationFailureResult.Untracked, registry.markFailurePending(operationId))
        assertTrue(preferences().all.isEmpty())
    }

    @Test
    fun wallClockRollbackDoesNotShortenDurableTerminalExpiry() {
        var nowMillis = 40_000L
        val registry = registry(
            retentionMillis = 1_000L,
            clockMillis = { nowMillis },
            identifierGenerator = { 7_801L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)

        nowMillis = 500L
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        assertTrue(registry.markFailurePending(operationId) is ReplyOperationFailureResult.Pending)
        nowMillis = 40_999L
        assertEquals(ReplyOperationCleanupResult.Success(0), registry.cleanupExpired())
        nowMillis = 41_000L
        assertEquals(ReplyOperationCleanupResult.Success(1), registry.cleanupExpired())
    }

    private fun assertPrivatePersistence() {
        val persisted = persistedPreferences()
        assertFalse(persisted.contains(SENSITIVE_BODY))
        assertFalse(persisted.contains(SENSITIVE_RECIPIENT))
        assertFalse(persisted.contains(SENSITIVE_SUBSCRIPTION))
    }

    private fun registry(
        maximumEntries: Int = 4,
        retentionMillis: Long = 1_000L,
        clockMillis: () -> Long = { 1_000L },
        identifierGenerator: ReplyOperationIdentifierGenerator,
    ) = ReplyOperationRegistry(
        store = SharedPreferencesReplyOperationStore(
            context = context,
            maximumEntries = maximumEntries,
        ),
        retentionMillis = retentionMillis,
        clockMillis = clockMillis,
        identifierGenerator = identifierGenerator,
    )

    private fun ReplyOperationRegistry.reserveOperation(conversationId: ConversationId) =
        (reserve(conversationId, SOURCE_MESSAGE) as ReplyOperationReservationResult.Reserved)
            .operationId

    private fun prepareSubmitting(
        registry: ReplyOperationRegistry,
        operationId: MessageId,
        providerMessageId: ProviderMessageId = DEFAULT_PROVIDER_MESSAGE,
        unitCount: Int,
    ) {
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordPrepared(operationId, providerMessageId, unitCount),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordSubmitting(operationId, providerMessageId, unitCount),
        )
    }

    private fun preferences() = context.getSharedPreferences(
        SharedPreferencesReplyOperationStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun persistedPreferences(): String =
        preferences().all.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" }

    private fun versionFourChecksum(operationId: Long, payloadFields: List<String>): String {
        val canonical = "$operationId|${payloadFields.joinToString("|")}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }

    private class SequenceGenerator(vararg values: Long) : ReplyOperationIdentifierGenerator {
        private val values = ArrayDeque(values.toList())

        override fun nextPositiveLong(): Long = values.removeFirst()
    }

    private companion object {
        val CONVERSATION = ConversationId(501L)
        val OTHER_CONVERSATION = ConversationId(502L)
        val THIRD_CONVERSATION = ConversationId(503L)
        val SOURCE_MESSAGE = MessageId(ProviderKind.SMS, 5_001L)
        val OTHER_SOURCE_MESSAGE = MessageId(ProviderKind.MMS, 5_002L)
        val THIRD_SOURCE_MESSAGE = MessageId(ProviderKind.SMS, 5_003L)
        val DEFAULT_PROVIDER_MESSAGE = ProviderMessageId(ProviderKind.SMS, 8_999L)
        const val SENSITIVE_BODY = "AURORA_SENTINEL_BODY_ZETA"
        const val SENSITIVE_RECIPIENT = "+18005550142"
        const val SENSITIVE_SUBSCRIPTION = "SUBSCRIPTION_SENTINEL_23"

        fun inlineOperationValue(value: Long) = value or INLINE_REPLY_OPERATION_ID_BOUNDARY

        fun pendingOperation(value: Long) =
            MessageId(ProviderKind.PENDING_OPERATION, inlineOperationValue(value))

        fun legacyPendingOperation(value: Long) = MessageId(ProviderKind.PENDING_OPERATION, value)
    }
}
