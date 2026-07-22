// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.FirstContactBridgeSnapshot
import org.aurorasms.core.state.FirstContactKnownUnsentProof
import org.aurorasms.core.state.FirstContactOperation
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.FirstContactOperationRepository
import org.aurorasms.core.state.FirstContactOperationResult
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.aurorasms.core.state.FirstContactReservationRequest
import org.aurorasms.core.state.FirstContactResolutionSnapshot
import org.aurorasms.core.telephony.ActiveSmsSubscriptionValidation
import org.aurorasms.core.telephony.ProviderThreadResolution
import org.aurorasms.core.telephony.ProviderThreadResolver
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.validateActiveSmsSubscription

internal fun interface FirstContactClock {
    fun nowMillis(): Long
}

internal object SystemFirstContactClock : FirstContactClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Headless N2B participant-draft ownership and provider-thread binding.
 *
 * This coordinator deliberately has no transport, provider-message staging, callback, or
 * existing-thread send dependency. Its only successful endpoint is durable HANDOFF_RESERVED.
 */
internal class FirstContactOwnershipCoordinator(
    private val authorityPreflight: FirstContactAuthorityPreflightGate,
    private val subscriptions: SubscriptionRepository,
    private val operations: FirstContactOperationRepository,
    private val threadResolver: ProviderThreadResolver,
    private val clock: FirstContactClock = SystemFirstContactClock,
) : FirstContactOwnershipController {
    private val mutex = Mutex()

    override suspend fun reserveAndBind(
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult = mutex.withLock {
        val participants = command.recipients.addresses
        val participantSetKey = runCatching {
            FirstContactParticipantSetKey.fromParticipants(participants)
        }.getOrNull() ?: return@withLock conflict(
            FirstContactOwnershipConflictReason.INELIGIBLE_DRAFT,
        )
        val exactDraftKey = runCatching {
            DraftParticipantSetKey.fromParticipants(participants)
        }.getOrNull() ?: return@withLock conflict(
            FirstContactOwnershipConflictReason.INELIGIBLE_DRAFT,
        )
        if (command.participantDraftIdentity.key != exactDraftKey) {
            return@withLock conflict(FirstContactOwnershipConflictReason.STALE_DRAFT)
        }
        val transport = command.transport

        when (val validation = validateSubscription(command)) {
            SubscriptionCheck.Valid -> Unit
            is SubscriptionCheck.Denied -> return@withLock denied(validation.reason)
            SubscriptionCheck.Cancelled -> return@withLock cancelled(null, resolutionUnknown = false)
            SubscriptionCheck.Failed -> return@withLock failure(
                FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
            )
        }

        val createdTimestamp = safeClockTimestamp()
            ?: return@withLock failure(FirstContactOwnershipFailureReason.INVALID_TIMESTAMP)
        val reservationRequest = FirstContactReservationRequest(
            participants = participants,
            draftId = command.draftId,
            expectedDraftRevision = command.expectedDraftRevision,
            subscriptionId = command.subscriptionId,
            transport = transport,
            createdTimestampMillis = createdTimestamp,
            frozenSignature = command.frozenSignature,
        )
        val reservation = try {
            operations.reserve(reservationRequest)
        } catch (_: CancellationException) {
            return@withLock cancelled(null, resolutionUnknown = false)
        } catch (_: RuntimeException) {
            return@withLock failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE)
        }
        val operation = when (
            val adjudicated = adjudicateReservation(
                result = reservation,
                command = command,
                participantSetKey = participantSetKey,
                transport = transport,
            )
        ) {
            is ReservationAdjudication.Ready -> adjudicated.operation
            is ReservationAdjudication.Finished -> return@withLock adjudicated.result
        }
        continueFromDurable(operation, command)
    }

    private suspend fun continueFromDurable(
        operation: FirstContactOperation,
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult = when (operation.phase) {
        FirstContactOperationPhase.RESERVED -> resolveReserved(operation, command)
        FirstContactOperationPhase.RESOLUTION_STARTED -> {
            // A prior process may have crossed provider authority. Never resolve it again.
            markResolutionUnknown(operation)
            unknown(operation)
        }
        FirstContactOperationPhase.THREAD_BOUND -> bridgeBound(operation, command)
        FirstContactOperationPhase.HANDOFF_RESERVED -> operation.toHandoffResultOrFailure()
        FirstContactOperationPhase.RESOLUTION_UNKNOWN -> unknown(operation)
        FirstContactOperationPhase.KNOWN_UNSENT -> conflict(
            FirstContactOwnershipConflictReason.INVALID_PHASE,
            operation,
        )
    }

    private suspend fun resolveReserved(
        reserved: FirstContactOperation,
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult {
        val preflightDenial = evaluatePreflight()
        if (preflightDenial != null) {
            return settleReservedDenial(reserved, preflightDenial)
        }
        when (val validation = validateSubscription(command)) {
            SubscriptionCheck.Valid -> Unit
            is SubscriptionCheck.Denied -> return settleReservedDenial(reserved, validation.reason)
            SubscriptionCheck.Cancelled -> {
                val settled = settleKnownUnsent(reserved)
                return if (settled) {
                    cancelled(reserved, resolutionUnknown = false)
                } else {
                    failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE, reserved)
                }
            }
            SubscriptionCheck.Failed -> {
                settleKnownUnsent(reserved)
                return failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE, reserved)
            }
        }

        val resolutionStarted = when (
            val result = transitionResolutionStarted(reserved)
        ) {
            is ResolutionStartAdjudication.Ready -> result.snapshot
            is ResolutionStartAdjudication.Finished -> return result.result
        }

        val resolution = try {
            threadResolver.resolveExact(
                recipients = resolutionStarted.toRecipientSetOrNull()
                    ?: error("Durable first-contact recipients are not a valid bounded set"),
            )
        } catch (_: CancellationException) {
            // Once RESOLUTION_STARTED is durable, a thrown cancellation cannot prove that a
            // resolver implementation stayed outside provider authority. Never make it retryable.
            withContext(NonCancellable) {
                markResolutionUnknown(resolutionStarted.operation)
            }
            return cancelled(resolutionStarted.operation, resolutionUnknown = true)
        } catch (_: RuntimeException) {
            // An implementation that violates the typed resolver contract still fails closed.
            withContext(NonCancellable) { markResolutionUnknown(resolutionStarted.operation) }
            return unknown(resolutionStarted.operation)
        }

        return when (resolution) {
            ProviderThreadResolution.RoleRequired,
            ProviderThreadResolution.PermissionDenied,
            ProviderThreadResolution.PlatformUnavailable,
            -> settleResolutionUnknown(resolutionStarted.operation)
            ProviderThreadResolution.ExactParticipantsUnverified -> {
                withContext(NonCancellable) {
                    markResolutionUnknown(resolutionStarted.operation)
                }
                conflict(
                    FirstContactOwnershipConflictReason.EXACT_PARTICIPANTS_UNVERIFIED,
                    resolutionStarted.operation,
                )
            }
            ProviderThreadResolution.MutationOutcomeUnknown -> {
                withContext(NonCancellable) {
                    markResolutionUnknown(resolutionStarted.operation)
                }
                unknown(resolutionStarted.operation)
            }
            is ProviderThreadResolution.Verified -> {
                if (resolution.participantCount != resolutionStarted.participants.size) {
                    withContext(NonCancellable) {
                        markResolutionUnknown(resolutionStarted.operation)
                    }
                    return conflict(
                        FirstContactOwnershipConflictReason.EXACT_PARTICIPANTS_UNVERIFIED,
                        resolutionStarted.operation,
                    )
                }
                // Persist the exact verified ID before any later suspension can discard it.
                val bound = withContext(NonCancellable) {
                    bindVerifiedThread(resolutionStarted.operation, resolution.providerThreadId)
                }
                when (bound) {
                    is BindingAdjudication.Ready -> bridgeBound(bound.operation, command)
                    is BindingAdjudication.Finished -> bound.result
                }
            }
        }
    }

    private suspend fun bridgeBound(
        bound: FirstContactOperation,
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult {
        val providerThreadId = bound.providerThreadId
            ?: return failure(FirstContactOwnershipFailureReason.CORRUPT_STATE, bound)
        val preflightDenial = evaluatePreflight()
        if (preflightDenial != null) return denied(preflightDenial, bound)
        when (val validation = validateSubscription(command)) {
            SubscriptionCheck.Valid -> Unit
            is SubscriptionCheck.Denied -> return denied(validation.reason, bound)
            SubscriptionCheck.Cancelled -> return cancelled(bound, resolutionUnknown = false)
            SubscriptionCheck.Failed -> return failure(
                FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
                bound,
            )
        }
        val timestamp = nextTimestamp(bound)
            ?: return failure(FirstContactOwnershipFailureReason.INVALID_TIMESTAMP, bound)
        val bridged = try {
            operations.bridgeToProviderThread(
                id = bound.id,
                expectedRevision = bound.revision,
                updatedTimestampMillis = timestamp,
            )
        } catch (_: CancellationException) {
            return cancelled(bound, resolutionUnknown = false)
        } catch (_: RuntimeException) {
            return failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE, bound)
        }
        return when (bridged) {
            is FirstContactOperationResult.Success -> {
                val snapshot = bridged.value
                if (
                    snapshot.operation.providerThreadId != providerThreadId ||
                    !snapshot.operation.matchesCommand(command, bound.participantSetKey, bound.transport)
                ) {
                    failure(FirstContactOwnershipFailureReason.CORRUPT_STATE, bound)
                } else {
                    snapshot.toHandoffResultOrFailure()
                }
            }
            else -> adjudicateBridgeFailure(bridged, bound, command)
        }
    }

    private suspend fun adjudicateReservation(
        result: FirstContactOperationResult<FirstContactOperation>,
        command: FirstContactOwnershipCommand,
        participantSetKey: FirstContactParticipantSetKey,
        transport: org.aurorasms.core.model.MessageTransportKind,
    ): ReservationAdjudication = when (result) {
        is FirstContactOperationResult.Success -> {
            if (result.value.matchesCommand(command, participantSetKey, transport)) {
                ReservationAdjudication.Ready(result.value)
            } else {
                ReservationAdjudication.Finished(
                    conflict(FirstContactOwnershipConflictReason.ACTIVE_OPERATION, result.value),
                )
            }
        }
        FirstContactOperationResult.Conflict,
        is FirstContactOperationResult.StorageFailure,
        -> recoverEquivalentReservation(command, participantSetKey, transport, result)
        FirstContactOperationResult.StaleWrite -> ReservationAdjudication.Finished(
            conflict(FirstContactOwnershipConflictReason.STALE_DRAFT),
        )
        FirstContactOperationResult.IneligibleDraft -> ReservationAdjudication.Finished(
            conflict(FirstContactOwnershipConflictReason.INELIGIBLE_DRAFT),
        )
        FirstContactOperationResult.PhaseMismatch -> ReservationAdjudication.Finished(
            conflict(FirstContactOwnershipConflictReason.INVALID_PHASE),
        )
        FirstContactOperationResult.InvalidTimestamp -> ReservationAdjudication.Finished(
            failure(FirstContactOwnershipFailureReason.INVALID_TIMESTAMP),
        )
        FirstContactOperationResult.CorruptData -> ReservationAdjudication.Finished(
            failure(FirstContactOwnershipFailureReason.CORRUPT_STATE),
        )
        FirstContactOperationResult.NotFound -> ReservationAdjudication.Finished(
            conflict(FirstContactOwnershipConflictReason.STALE_DRAFT),
        )
        FirstContactOperationResult.LimitExceeded -> ReservationAdjudication.Finished(
            conflict(FirstContactOwnershipConflictReason.ACTIVE_OPERATION),
        )
    }

    private suspend fun recoverEquivalentReservation(
        command: FirstContactOwnershipCommand,
        participantSetKey: FirstContactParticipantSetKey,
        transport: org.aurorasms.core.model.MessageTransportKind,
        original: FirstContactOperationResult<FirstContactOperation>,
    ): ReservationAdjudication {
        val existing = try {
            operations.readByDraft(command.draftId)
        } catch (_: CancellationException) {
            return ReservationAdjudication.Finished(cancelled(null, resolutionUnknown = false))
        } catch (_: RuntimeException) {
            return ReservationAdjudication.Finished(
                failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE),
            )
        }
        return when (existing) {
            is FirstContactOperationResult.Success -> {
                if (existing.value.matchesCommand(command, participantSetKey, transport)) {
                    ReservationAdjudication.Ready(existing.value)
                } else {
                    ReservationAdjudication.Finished(
                        conflict(
                            FirstContactOwnershipConflictReason.ACTIVE_OPERATION,
                            existing.value,
                        ),
                    )
                }
            }
            else -> ReservationAdjudication.Finished(
                if (original is FirstContactOperationResult.StorageFailure) {
                    failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE)
                } else {
                    conflict(FirstContactOwnershipConflictReason.ACTIVE_OPERATION)
                },
            )
        }
    }

    private suspend fun transitionResolutionStarted(
        reserved: FirstContactOperation,
    ): ResolutionStartAdjudication {
        val timestamp = nextTimestamp(reserved)
            ?: return ResolutionStartAdjudication.Finished(
                failure(FirstContactOwnershipFailureReason.INVALID_TIMESTAMP, reserved),
            )
        val result = try {
            operations.markResolutionStarted(reserved.id, reserved.revision, timestamp)
        } catch (_: CancellationException) {
            return withContext(NonCancellable) {
                val current = readOperation(reserved)
                if (current != null && (
                    current.phase == FirstContactOperationPhase.RESOLUTION_STARTED ||
                        current.phase == FirstContactOperationPhase.RESOLUTION_UNKNOWN
                    )
                ) {
                    if (current.phase == FirstContactOperationPhase.RESOLUTION_STARTED) {
                        markResolutionUnknown(current)
                    }
                    ResolutionStartAdjudication.Finished(
                        cancelled(current, resolutionUnknown = true),
                    )
                } else {
                    ResolutionStartAdjudication.Finished(
                        cancelled(current ?: reserved, resolutionUnknown = false),
                    )
                }
            }
        } catch (_: RuntimeException) {
            return ResolutionStartAdjudication.Finished(
                failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE, reserved),
            )
        }
        if (result is FirstContactOperationResult.Success) {
            return ResolutionStartAdjudication.Ready(result.value)
        }
        val current = readOperation(reserved)
        return when (current?.phase) {
            FirstContactOperationPhase.RESOLUTION_STARTED -> {
                markResolutionUnknown(current)
                ResolutionStartAdjudication.Finished(unknown(current))
            }
            FirstContactOperationPhase.THREAD_BOUND -> ResolutionStartAdjudication.Finished(
                conflict(FirstContactOwnershipConflictReason.INVALID_PHASE, current),
            )
            FirstContactOperationPhase.HANDOFF_RESERVED -> ResolutionStartAdjudication.Finished(
                current.toHandoffResultOrFailure(),
            )
            FirstContactOperationPhase.RESOLUTION_UNKNOWN -> ResolutionStartAdjudication.Finished(
                unknown(current),
            )
            FirstContactOperationPhase.KNOWN_UNSENT,
            FirstContactOperationPhase.RESERVED,
            null,
            -> ResolutionStartAdjudication.Finished(mapTransitionFailure(result, reserved))
        }
    }

    private suspend fun bindVerifiedThread(
        resolutionStarted: FirstContactOperation,
        providerThreadId: org.aurorasms.core.model.ProviderThreadId,
    ): BindingAdjudication {
        val timestamp = nextTimestamp(resolutionStarted)
            ?: return BindingAdjudication.Finished(
                failure(FirstContactOwnershipFailureReason.INVALID_TIMESTAMP, resolutionStarted),
            )
        val result = try {
            operations.bindThread(
                id = resolutionStarted.id,
                expectedRevision = resolutionStarted.revision,
                providerThreadId = providerThreadId,
                updatedTimestampMillis = timestamp,
            )
        } catch (_: RuntimeException) {
            null
        }
        val direct = (result as? FirstContactOperationResult.Success)?.value
        if (direct != null) {
            return if (
                direct.phase == FirstContactOperationPhase.THREAD_BOUND &&
                direct.providerThreadId == providerThreadId
            ) {
                BindingAdjudication.Ready(direct)
            } else {
                BindingAdjudication.Finished(
                    conflict(FirstContactOwnershipConflictReason.THREAD_BINDING, direct),
                )
            }
        }
        val current = readOperation(resolutionStarted)
        return when {
            current?.phase == FirstContactOperationPhase.HANDOFF_RESERVED &&
                current.providerThreadId == providerThreadId -> BindingAdjudication.Finished(
                current.toHandoffResultOrFailure(),
            )
            current?.phase == FirstContactOperationPhase.THREAD_BOUND &&
                current.providerThreadId == providerThreadId -> BindingAdjudication.Ready(current)
            current?.phase == FirstContactOperationPhase.RESOLUTION_UNKNOWN ->
                BindingAdjudication.Finished(unknown(current))
            current?.phase == FirstContactOperationPhase.RESOLUTION_STARTED -> {
                markResolutionUnknown(current)
                BindingAdjudication.Finished(unknown(current))
            }
            current != null -> BindingAdjudication.Finished(
                conflict(FirstContactOwnershipConflictReason.THREAD_BINDING, current),
            )
            else -> {
                // Verified provider authority exists but durable binding cannot be proven.
                markResolutionUnknown(resolutionStarted)
                BindingAdjudication.Finished(unknown(resolutionStarted))
            }
        }
    }

    private suspend fun adjudicateBridgeFailure(
        result: FirstContactOperationResult<FirstContactBridgeSnapshot>,
        bound: FirstContactOperation,
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult {
        val current = readOperation(bound)
        if (
            current?.phase == FirstContactOperationPhase.HANDOFF_RESERVED &&
            current.providerThreadId == bound.providerThreadId &&
            current.matchesCommand(command, bound.participantSetKey, bound.transport)
        ) {
            return current.toHandoffResultOrFailure()
        }
        return when (result) {
            FirstContactOperationResult.Conflict -> conflict(
                FirstContactOwnershipConflictReason.TARGET_DRAFT,
                current ?: bound,
            )
            FirstContactOperationResult.StaleWrite -> conflict(
                FirstContactOwnershipConflictReason.STALE_DRAFT,
                current ?: bound,
            )
            FirstContactOperationResult.IneligibleDraft -> conflict(
                FirstContactOwnershipConflictReason.INELIGIBLE_DRAFT,
                current ?: bound,
            )
            FirstContactOperationResult.PhaseMismatch -> conflict(
                FirstContactOwnershipConflictReason.INVALID_PHASE,
                current ?: bound,
            )
            FirstContactOperationResult.InvalidTimestamp -> failure(
                FirstContactOwnershipFailureReason.INVALID_TIMESTAMP,
                current ?: bound,
            )
            FirstContactOperationResult.CorruptData -> failure(
                FirstContactOwnershipFailureReason.CORRUPT_STATE,
                current ?: bound,
            )
            is FirstContactOperationResult.StorageFailure -> failure(
                FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
                current ?: bound,
            )
            FirstContactOperationResult.NotFound,
            FirstContactOperationResult.LimitExceeded,
            -> failure(FirstContactOwnershipFailureReason.CORRUPT_STATE, current ?: bound)
            is FirstContactOperationResult.Success -> failure(
                FirstContactOwnershipFailureReason.CORRUPT_STATE,
                current ?: bound,
            )
        }
    }

    private suspend fun settleReservedDenial(
        reserved: FirstContactOperation,
        reason: FirstContactOwnershipDenialReason,
    ): FirstContactOwnershipResult = if (settleKnownUnsent(reserved)) {
        denied(reason, reserved)
    } else {
        failure(FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE, reserved)
    }

    private suspend fun settleResolutionUnknown(
        resolutionStarted: FirstContactOperation,
    ): FirstContactOwnershipResult = withContext(NonCancellable) {
        // Even typed preflight outcomes arrive after Aurora's durable provider-entry fence. A
        // future resolver must not be able to turn that checkpoint into retryable ownership.
        markResolutionUnknown(resolutionStarted)
        unknown(resolutionStarted)
    }

    private suspend fun settleKnownUnsent(operation: FirstContactOperation): Boolean {
        if (operation.phase == FirstContactOperationPhase.KNOWN_UNSENT) return true
        val timestamp = nextTimestamp(operation) ?: return false
        val result = try {
            operations.markKnownUnsent(
                id = operation.id,
                expectedRevision = operation.revision,
                proof = FirstContactKnownUnsentProof.PROVIDER_AUTHORITY_NOT_ENTERED,
                updatedTimestampMillis = timestamp,
            )
        } catch (_: CancellationException) {
            return readOperation(operation)?.phase == FirstContactOperationPhase.KNOWN_UNSENT
        } catch (_: RuntimeException) {
            return false
        }
        return when (result) {
            is FirstContactOperationResult.Success -> true
            else -> readOperation(operation)?.phase == FirstContactOperationPhase.KNOWN_UNSENT
        }
    }

    private suspend fun markResolutionUnknown(operation: FirstContactOperation): Boolean {
        if (operation.phase == FirstContactOperationPhase.RESOLUTION_UNKNOWN) return true
        val timestamp = nextTimestamp(operation) ?: return false
        val result = try {
            operations.markResolutionUnknown(operation.id, operation.revision, timestamp)
        } catch (_: CancellationException) {
            return readOperation(operation)?.phase == FirstContactOperationPhase.RESOLUTION_UNKNOWN
        } catch (_: RuntimeException) {
            return false
        }
        return when (result) {
            is FirstContactOperationResult.Success -> true
            else -> readOperation(operation)?.phase == FirstContactOperationPhase.RESOLUTION_UNKNOWN
        }
    }

    private suspend fun readOperation(fallback: FirstContactOperation): FirstContactOperation? =
        try {
            (operations.read(fallback.id) as? FirstContactOperationResult.Success)?.value
        } catch (_: CancellationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun evaluatePreflight(): FirstContactOwnershipDenialReason? = when (
        try {
            authorityPreflight.evaluate()
        } catch (_: RuntimeException) {
            FirstContactAuthorityPreflight.PLATFORM_UNAVAILABLE
        }
    ) {
        FirstContactAuthorityPreflight.READY -> null
        FirstContactAuthorityPreflight.ROLE_REQUIRED -> FirstContactOwnershipDenialReason.ROLE_REQUIRED
        FirstContactAuthorityPreflight.PERMISSION_DENIED ->
            FirstContactOwnershipDenialReason.PERMISSION_DENIED
        FirstContactAuthorityPreflight.PLATFORM_UNAVAILABLE ->
            FirstContactOwnershipDenialReason.PLATFORM_UNAVAILABLE
    }

    private suspend fun validateSubscription(
        command: FirstContactOwnershipCommand,
    ): SubscriptionCheck = try {
        when (val result = subscriptions.validateActiveSmsSubscription(command.subscriptionId)) {
            is ActiveSmsSubscriptionValidation.Valid -> if (
                result.subscription.id == command.subscriptionId && result.subscription.smsCapable
            ) {
                SubscriptionCheck.Valid
            } else {
                SubscriptionCheck.Denied(FirstContactOwnershipDenialReason.SUBSCRIPTION_AMBIGUOUS)
            }
            ActiveSmsSubscriptionValidation.Inactive,
            ActiveSmsSubscriptionValidation.NotSmsCapable,
            -> SubscriptionCheck.Denied(FirstContactOwnershipDenialReason.SUBSCRIPTION_UNAVAILABLE)
            ActiveSmsSubscriptionValidation.Ambiguous -> SubscriptionCheck.Denied(
                FirstContactOwnershipDenialReason.SUBSCRIPTION_AMBIGUOUS,
            )
            ActiveSmsSubscriptionValidation.PermissionDenied -> SubscriptionCheck.Denied(
                FirstContactOwnershipDenialReason.SUBSCRIPTION_PERMISSION_DENIED,
            )
            ActiveSmsSubscriptionValidation.FeatureUnavailable,
            ActiveSmsSubscriptionValidation.PlatformUnavailable,
            -> SubscriptionCheck.Denied(FirstContactOwnershipDenialReason.PLATFORM_UNAVAILABLE)
        }
    } catch (_: CancellationException) {
        SubscriptionCheck.Cancelled
    } catch (_: RuntimeException) {
        SubscriptionCheck.Failed
    }

    private fun FirstContactResolutionSnapshot.toRecipientSetOrNull():
        org.aurorasms.core.telephony.RecipientSet? =
        when (val parsed = org.aurorasms.core.telephony.RecipientSet.from(participants)) {
            is org.aurorasms.core.telephony.RecipientSet.CreationResult.Valid -> parsed.recipients
            is org.aurorasms.core.telephony.RecipientSet.CreationResult.Rejected -> null
        }

    private fun FirstContactOperation.matchesCommand(
        command: FirstContactOwnershipCommand,
        expectedParticipantSetKey: FirstContactParticipantSetKey,
        expectedTransport: org.aurorasms.core.model.MessageTransportKind,
    ): Boolean =
        participantSetKey == expectedParticipantSetKey &&
            draftId == command.draftId &&
            sourceDraftRevision == command.expectedDraftRevision &&
            subscriptionId == command.subscriptionId &&
            transport == expectedTransport &&
            frozenSignature == command.frozenSignature

    private fun FirstContactBridgeSnapshot.toHandoffResultOrFailure(): FirstContactOwnershipResult =
        operation.toHandoffResultOrFailure()

    private fun FirstContactOperation.toHandoffResultOrFailure(): FirstContactOwnershipResult {
        val thread = providerThreadId
        val draftRevision = handoffDraftRevision
        return if (
            phase == FirstContactOperationPhase.HANDOFF_RESERVED &&
            thread != null &&
            draftRevision != null
        ) {
            FirstContactOwnershipResult.HandoffReserved(id, thread, draftId, draftRevision)
        } else {
            failure(FirstContactOwnershipFailureReason.CORRUPT_STATE, this)
        }
    }

    private fun safeClockTimestamp(): Long? = runCatching { clock.nowMillis() }
        .getOrNull()
        ?.takeIf { it in 0L until Long.MAX_VALUE }

    private fun nextTimestamp(operation: FirstContactOperation): Long? {
        if (operation.updatedTimestampMillis >= Long.MAX_VALUE - 1L) return null
        val now = safeClockTimestamp() ?: return null
        return maxOf(now, operation.updatedTimestampMillis + 1L).takeIf { it < Long.MAX_VALUE }
    }

    private fun mapTransitionFailure(
        result: FirstContactOperationResult<*>,
        operation: FirstContactOperation,
    ): FirstContactOwnershipResult = when (result) {
        FirstContactOperationResult.Conflict -> conflict(
            FirstContactOwnershipConflictReason.ACTIVE_OPERATION,
            operation,
        )
        FirstContactOperationResult.StaleWrite -> conflict(
            FirstContactOwnershipConflictReason.STALE_DRAFT,
            operation,
        )
        FirstContactOperationResult.IneligibleDraft -> conflict(
            FirstContactOwnershipConflictReason.INELIGIBLE_DRAFT,
            operation,
        )
        FirstContactOperationResult.PhaseMismatch -> conflict(
            FirstContactOwnershipConflictReason.INVALID_PHASE,
            operation,
        )
        FirstContactOperationResult.InvalidTimestamp -> failure(
            FirstContactOwnershipFailureReason.INVALID_TIMESTAMP,
            operation,
        )
        FirstContactOperationResult.CorruptData -> failure(
            FirstContactOwnershipFailureReason.CORRUPT_STATE,
            operation,
        )
        is FirstContactOperationResult.StorageFailure -> failure(
            FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
            operation,
        )
        FirstContactOperationResult.NotFound,
        FirstContactOperationResult.LimitExceeded,
        is FirstContactOperationResult.Success,
        -> failure(FirstContactOwnershipFailureReason.CORRUPT_STATE, operation)
    }

    private fun denied(
        reason: FirstContactOwnershipDenialReason,
        operation: FirstContactOperation? = null,
    ) = FirstContactOwnershipResult.Denied(reason, operation?.id)

    private fun conflict(
        reason: FirstContactOwnershipConflictReason,
        operation: FirstContactOperation? = null,
    ) = FirstContactOwnershipResult.Conflict(reason, operation?.id)

    private fun failure(
        reason: FirstContactOwnershipFailureReason,
        operation: FirstContactOperation? = null,
    ) = FirstContactOwnershipResult.Failure(reason, operation?.id)

    private fun unknown(operation: FirstContactOperation) =
        FirstContactOwnershipResult.ResolutionUnknown(operation.id)

    private fun cancelled(
        operation: FirstContactOperation?,
        resolutionUnknown: Boolean,
    ) = FirstContactOwnershipResult.Cancelled(operation?.id, resolutionUnknown)

    private sealed interface ReservationAdjudication {
        data class Ready(val operation: FirstContactOperation) : ReservationAdjudication
        data class Finished(val result: FirstContactOwnershipResult) : ReservationAdjudication
    }

    private sealed interface ResolutionStartAdjudication {
        data class Ready(val snapshot: FirstContactResolutionSnapshot) : ResolutionStartAdjudication
        data class Finished(val result: FirstContactOwnershipResult) : ResolutionStartAdjudication
    }

    private sealed interface BindingAdjudication {
        data class Ready(val operation: FirstContactOperation) : BindingAdjudication
        data class Finished(val result: FirstContactOwnershipResult) : BindingAdjudication
    }

    private sealed interface SubscriptionCheck {
        data object Valid : SubscriptionCheck
        data class Denied(val reason: FirstContactOwnershipDenialReason) : SubscriptionCheck
        data object Cancelled : SubscriptionCheck
        data object Failed : SubscriptionCheck
    }
}
