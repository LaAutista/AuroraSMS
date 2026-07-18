// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSubmissionObserverTest {
    @Test
    fun acceptedObserverRunsInExactOrderImmediatelyBeforePlatformSubmission() = runTest {
        val events = mutableListOf<String>()
        val observer = recordingObserver(
            events = events,
            prepared = { true },
            submitting = { true },
        )

        val result = runObservedSmsSubmission(
            observer = observer,
            providerId = PROVIDER_ID,
            providerConversationId = PROVIDER_CONVERSATION_ID,
            unitCount = UNIT_COUNT,
            markFailed = { events += "provider-failed" },
            armProvider = {
                events += "provider-armed"
                true
            },
            prepareSubmission = {
                events += "platform-prepared"
                val submit: () -> Unit = { events += "platform-submitted" }
                submit
            },
        )

        assertEquals(ObservedSmsSubmissionResult.SUBMITTED, result)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "platform-submitted",
            ),
            events,
        )
    }

    @Test
    fun preparedRefusalOrExceptionFailsProviderWithoutPreparingOrSubmitting() = runTest {
        listOf<() -> Boolean>(
            { false },
            { throw IllegalStateException("synthetic prepared failure") },
        ).forEach { prepared ->
            val events = mutableListOf<String>()
            val observer = recordingObserver(
                events = events,
                prepared = prepared,
                submitting = { error("onSubmitting must not run") },
            )

            val result = runObservedSmsSubmission(
                observer = observer,
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = { error("provider must not be armed") },
                prepareSubmission = { error("platform preparation must not run") },
            )

            assertEquals(ObservedSmsSubmissionResult.OBSERVER_REJECTED, result)
            assertEquals(
                listOf("observer-prepared:SMS:73:3", "provider-failed"),
                events,
            )
        }
    }

    @Test
    fun submittingRefusalOrExceptionNeverInvokesPlatformSideEffect() = runTest {
        listOf<() -> Boolean>(
            { false },
            { throw IllegalStateException("synthetic submitting failure") },
        ).forEachIndexed { index, submitting ->
            val events = mutableListOf<String>()
            val observer = recordingObserver(
                events = events,
                prepared = { true },
                submitting = submitting,
            )

            val result = runObservedSmsSubmission(
                observer = observer,
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = {
                    events += "provider-failed"
                    if (index == 1) throw SecurityException("synthetic provider failure")
                },
                armProvider = {
                    events += "provider-armed"
                    true
                },
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = { events += "platform-submitted" }
                    submit
                },
            )

            assertEquals(ObservedSmsSubmissionResult.OBSERVER_REJECTED, result)
            assertEquals(
                listOf(
                    "observer-prepared:SMS:73:3",
                    "platform-prepared",
                    "provider-armed",
                    "observer-submitting:SMS:73:3",
                    "provider-failed",
                ),
                events,
            )
        }
    }

    @Test
    fun roleLossAtFinalBoundaryCheckRollsBackWithoutInvokingPlatform() = runTest {
        val events = mutableListOf<String>()

        val result = runObservedSmsSubmission(
            observer = recordingObserver(
                events = events,
                prepared = { true },
                submitting = { true },
            ),
            providerId = PROVIDER_ID,
            providerConversationId = PROVIDER_CONVERSATION_ID,
            unitCount = UNIT_COUNT,
            markFailed = { events += "provider-failed" },
            armProvider = {
                events += "provider-armed"
                true
            },
            submissionBoundaryAllowed = {
                events += "boundary-role-check"
                false
            },
            prepareSubmission = {
                events += "platform-prepared"
                val submit: () -> Unit = { events += "platform-submitted" }
                submit
            },
        )

        assertEquals(ObservedSmsSubmissionResult.OBSERVER_REJECTED, result)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "boundary-role-check",
                "provider-failed",
            ),
            events,
        )
    }

    @Test
    fun exceptionFromIrreversiblePlatformCallIsSubmissionUnknownAndDoesNotFailProvider() = runTest {
        val events = mutableListOf<String>()

        val result = runObservedSmsSubmission(
            observer = recordingObserver(
                events = events,
                prepared = { true },
                submitting = { true },
            ),
            providerId = PROVIDER_ID,
            providerConversationId = PROVIDER_CONVERSATION_ID,
            unitCount = UNIT_COUNT,
            markFailed = { events += "provider-failed" },
            armProvider = {
                events += "provider-armed"
                true
            },
            prepareSubmission = {
                events += "platform-prepared"
                val submit: () -> Unit = {
                    events += "platform-invoked"
                    throw SecurityException("synthetic post-boundary uncertainty")
                }
                submit
            },
        )

        assertEquals(ObservedSmsSubmissionResult.SUBMISSION_UNKNOWN, result)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "platform-invoked",
            ),
            events,
        )
    }

    @Test
    fun preparedCheckpointCancellationRollsBackThenPropagatesBeforePlatformSubmission() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { throw CancellationException("synthetic cancellation") },
                    submitting = { error("onSubmitting must not run") },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = { error("provider must not be armed") },
                prepareSubmission = { error("platform preparation must not run") },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(listOf("observer-prepared:SMS:73:3", "provider-failed"), events)
    }

    @Test
    fun parentCancellationAfterOutgoingInsertRollsBackExactBindingBeforeAnyCheckpoint() = runTest {
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var returned: ProviderAccessResult<ProviderStoredMessage>? = null
        var cancellationPropagated = false
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            returned = awaitOutgoingInsertResult {
                insertStarted.complete(Unit)
                releaseInsert.await()
                ProviderAccessResult.Success(STORED_PROVIDER_MESSAGE)
            }
            try {
                ensureActiveOutgoingSmsOrRollback {
                    val exact = (returned as ProviderAccessResult.Success<ProviderStoredMessage>).value
                    events += "provider-failed:${exact.providerId.value}:${exact.conversationId.value}"
                }
                events += "observer-prepared"
                events += "provider-armed"
                events += "platform-submitted"
            } catch (cancelled: CancellationException) {
                cancellationPropagated = true
                throw cancelled
            }
        }
        insertStarted.await()

        job.cancel()
        releaseInsert.complete(Unit)
        job.join()

        assertEquals(ProviderAccessResult.Success(STORED_PROVIDER_MESSAGE), returned)
        assertEquals(true, cancellationPropagated)
        assertEquals(listOf("provider-failed:73:79"), events)
    }

    @Test
    fun submittingCancellationRollsBackArmedProviderBeforePropagating() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { true },
                    submitting = { throw CancellationException("synthetic submitting cancellation") },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = {
                    events += "provider-armed"
                    true
                },
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = { events += "platform-submitted" }
                    submit
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "provider-failed",
            ),
            events,
        )
    }

    @Test
    fun submittingCheckpointCancellationRollsBackThenPreservesCancellation() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { true },
                    submitting = { throw CancellationException("synthetic submitting cancellation") },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = {
                    events += "provider-armed"
                    true
                },
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = { events += "platform-submitted" }
                    submit
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "provider-failed",
            ),
            events,
        )
    }

    @Test
    fun cancellationFromIrreversiblePlatformCallPropagatesAfterSubmittingCheckpoint() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { true },
                    submitting = { true },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = {
                    events += "provider-armed"
                    true
                },
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = {
                        events += "platform-invoked"
                        throw CancellationException("synthetic platform cancellation")
                    }
                    submit
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-armed",
                "observer-submitting:SMS:73:3",
                "platform-invoked",
            ),
            events,
        )
    }

    @Test
    fun armRefusalOrExceptionRollsBackWithoutSubmitting() = runTest {
        listOf<suspend () -> Boolean>(
            { false },
            { throw IllegalStateException("synthetic arm failure") },
        ).forEach { arm ->
            val events = mutableListOf<String>()

            val result = runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { true },
                    submitting = { error("onSubmitting must not run") },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = arm,
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = { events += "platform-submitted" }
                    submit
                },
            )

            assertEquals(ObservedSmsSubmissionResult.OBSERVER_REJECTED, result)
            assertEquals(
                listOf(
                    "observer-prepared:SMS:73:3",
                    "platform-prepared",
                    "provider-failed",
                ),
                events,
            )
        }
    }

    @Test
    fun cancellationWhileArmingRollsBackThenPreservesCancellation() = runTest {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runObservedSmsSubmission(
                observer = recordingObserver(
                    events = events,
                    prepared = { true },
                    submitting = { error("onSubmitting must not run") },
                ),
                providerId = PROVIDER_ID,
                providerConversationId = PROVIDER_CONVERSATION_ID,
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = {
                    events += "provider-arm-invoked"
                    throw CancellationException("synthetic arm cancellation")
                },
                prepareSubmission = {
                    events += "platform-prepared"
                    val submit: () -> Unit = { events += "platform-submitted" }
                    submit
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(
            listOf(
                "observer-prepared:SMS:73:3",
                "platform-prepared",
                "provider-arm-invoked",
                "provider-failed",
            ),
            events,
        )
    }

    private fun recordingObserver(
        events: MutableList<String>,
        prepared: () -> Boolean,
        submitting: () -> Boolean,
    ) = object : SmsSubmissionObserver {
        override suspend fun onPrepared(
            providerId: ProviderMessageId,
            providerConversationId: ConversationId,
            unitCount: Int,
        ): Boolean {
            assertEquals(PROVIDER_CONVERSATION_ID, providerConversationId)
            events += "observer-prepared:${providerId.kind}:${providerId.value}:$unitCount"
            return prepared()
        }

        override suspend fun onSubmitting(
            providerId: ProviderMessageId,
            providerConversationId: ConversationId,
            unitCount: Int,
        ): Boolean {
            assertEquals(PROVIDER_CONVERSATION_ID, providerConversationId)
            events += "observer-submitting:${providerId.kind}:${providerId.value}:$unitCount"
            return submitting()
        }
    }

    private companion object {
        val PROVIDER_ID = ProviderMessageId(ProviderKind.SMS, 73L)
        val PROVIDER_CONVERSATION_ID = ConversationId(79L)
        val STORED_PROVIDER_MESSAGE = ProviderStoredMessage(
            providerId = PROVIDER_ID,
            conversationId = PROVIDER_CONVERSATION_ID,
        )
        const val UNIT_COUNT = 3
    }
}
