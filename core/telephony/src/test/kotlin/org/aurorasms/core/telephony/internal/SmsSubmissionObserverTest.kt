// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSubmissionObserverTest {
    @Test
    fun transportOwnedNamespaceAcceptsOnlyOrdinaryPendingOperationIds() {
        assertEquals(
            true,
            MessageId(ProviderKind.PENDING_OPERATION, INLINE_REPLY_OPERATION_ID_BOUNDARY - 1L)
                .isTransportOwnedSmsOperation(),
        )
        assertEquals(
            false,
            MessageId(ProviderKind.PENDING_OPERATION, INLINE_REPLY_OPERATION_ID_BOUNDARY)
                .isTransportOwnedSmsOperation(),
        )
        assertEquals(
            false,
            MessageId(ProviderKind.SMS, 1L).isTransportOwnedSmsOperation(),
        )
    }

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
    fun exceptionFromIrreversiblePlatformCallIsSubmissionUnknownAndDoesNotFailProvider() = runTest {
        val events = mutableListOf<String>()

        val result = runObservedSmsSubmission(
            observer = recordingObserver(
                events = events,
                prepared = { true },
                submitting = { true },
            ),
            providerId = PROVIDER_ID,
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
    fun observerCancellationPropagatesBeforePlatformSubmission() = runTest {
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
                unitCount = UNIT_COUNT,
                markFailed = { events += "provider-failed" },
                armProvider = { error("provider must not be armed") },
                prepareSubmission = { error("platform preparation must not run") },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
        assertEquals(listOf("observer-prepared:SMS:73:3"), events)
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
        override fun onPrepared(providerId: ProviderMessageId, unitCount: Int): Boolean {
            events += "observer-prepared:${providerId.kind}:${providerId.value}:$unitCount"
            return prepared()
        }

        override fun onSubmitting(providerId: ProviderMessageId, unitCount: Int): Boolean {
            events += "observer-submitting:${providerId.kind}:${providerId.value}:$unitCount"
            return submitting()
        }
    }

    private companion object {
        val PROVIDER_ID = ProviderMessageId(ProviderKind.SMS, 73L)
        const val UNIT_COUNT = 3
    }
}
