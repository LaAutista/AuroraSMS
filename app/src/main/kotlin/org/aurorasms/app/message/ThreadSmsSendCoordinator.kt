// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.model.asConversationId
import org.aurorasms.core.state.AcknowledgedComposerSmsCallbackProof
import org.aurorasms.core.state.AcknowledgedComposerSmsReceipt
import org.aurorasms.core.state.ComposerSmsOperation
import org.aurorasms.core.state.ComposerSmsOperationPhase
import org.aurorasms.core.state.ComposerSmsOperationRepository
import org.aurorasms.core.state.ComposerSmsOperationResult
import org.aurorasms.core.state.ComposerSmsProviderBinding
import org.aurorasms.core.state.ComposerSmsReservationRequest
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.SubscriptionRepository

internal class ThreadSmsSendCoordinator(
    private val applicationScope: CoroutineScope,
    private val roleState: DefaultSmsRoleState,
    private val conversations: ConversationRepository,
    private val subscriptions: SubscriptionRepository,
    private val operations: ComposerSmsOperationRepository,
    private val transport: MessageTransport,
    private val smsProvider: SmsProviderDataSource,
    private val subscriptionPreferences: ConversationSubscriptionPreferenceRepository =
        NoConversationSubscriptionPreferenceRepository,
    private val segmentCounter: SmsSegmentCounter = AndroidSmsSegmentCounter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ThreadSmsSendController {
    private val operationMutex = Mutex()
    private val fenceGeneration = AtomicLong(0L)
    private val classificationRecoveryScheduled = AtomicBoolean(false)
    private val exactCallbackRetries = ConcurrentHashMap.newKeySet<ExactCallbackRetryKey>()
    private val terminalVerifications = ConcurrentHashMap.newKeySet<TerminalVerificationKey>()
    private val completionSignaledOperationIds = LinkedHashSet<Long>()
    private val unknownAcknowledgedOperationIds = LinkedHashSet<Long>()
    private val recoveryGate = MutableStateFlow(RecoveryGate.NOT_READY)
    private val completionEpochs = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val unknownAcknowledgementEpochs = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private var completionEpochSequence: Long = 0L
    private var unknownAcknowledgementEpochSequence: Long = 0L

    override fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation> =
        combine(
            recoveryGate,
            operations.observeByThread(providerThreadId),
            completionEpochs,
            unknownAcknowledgementEpochs,
        ) { recovery, stored, epochs, acknowledgementEpochs ->
            val phase = if (recovery != RecoveryGate.READY) {
                ThreadSmsSendPhase.RECOVERY_PENDING
            } else {
                stored.toUiPhase()
            }
            ThreadSmsSendObservation(
                phase = phase,
                completionEpoch = epochs[providerThreadId.value] ?: 0L,
                unknownAcknowledgementEpoch = acknowledgementEpochs[providerThreadId.value] ?: 0L,
            )
        }

    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt {
        var reservationAccepted = false
        return try {
            operationMutex.withLock {
                val acceptedGeneration = fenceGeneration.get()
                if (
                    recoveryGate.value != RecoveryGate.READY ||
                    !isEligible(acceptedGeneration)
                ) {
                    return@withLock ThreadSmsSendAttempt.REFUSED
                }
                if (!prepareExistingTerminal(command.identity.providerThreadId)) {
                    return@withLock ThreadSmsSendAttempt.REFUSED
                }
                val recipient = revalidateCommand(command) ?: return@withLock ThreadSmsSendAttempt.REFUSED
                val recipients = when (val parsed = RecipientSet.from(listOf(recipient))) {
                    is RecipientSet.CreationResult.Valid -> parsed.recipients
                    is RecipientSet.CreationResult.Rejected -> return@withLock ThreadSmsSendAttempt.REFUSED
                }
                val reservation = when (
                    val result = operations.reserve(
                        ComposerSmsReservationRequest(
                            providerThreadId = command.identity.providerThreadId,
                            draftId = command.draftId,
                            expectedDraftRevision = command.draftRevision,
                            subscriptionId = command.subscriptionId,
                            createdTimestampMillis = safeNow(),
                        ),
                    )
                ) {
                    is ComposerSmsOperationResult.Success -> result.value
                    is ComposerSmsOperationResult.StorageFailure,
                    ComposerSmsOperationResult.CorruptData,
                    -> return@withLock classifyExceptionalPreReservationExit(
                        command.identity.providerThreadId,
                    )
                    else -> return@withLock ThreadSmsSendAttempt.REFUSED
                }
                reservationAccepted = true
                var ownedOperation = reservation.operation
                try {
                    if (segmentCounter.count(reservation.authoritativeBody) != REQUIRED_SMS_UNIT_COUNT) {
                        if (markKnownUnsent(ownedOperation) == null) requestClassificationRecovery()
                        return@withLock ThreadSmsSendAttempt.STARTED
                    }
                    val expectedConversationId = command.identity.providerThreadId.asConversationId()
                    val observer = object : SmsSubmissionObserver {
                        override suspend fun onPrepared(
                            providerId: ProviderMessageId,
                            providerConversationId: ConversationId,
                            unitCount: Int,
                        ): Boolean {
                            if (
                                !isEligible(acceptedGeneration) ||
                                providerConversationId != expectedConversationId ||
                                unitCount != REQUIRED_SMS_UNIT_COUNT
                            ) {
                                return false
                            }
                            val binding = ComposerSmsProviderBinding(
                                providerMessageId = providerId,
                                providerConversationId = providerConversationId,
                                unitCount = unitCount,
                            )
                            return when (
                                val result = operations.markPrepared(
                                    operationId = ownedOperation.operationId,
                                    expectedRevision = ownedOperation.revision,
                                    providerBinding = binding,
                                    updatedTimestampMillis = nextTimestamp(ownedOperation) ?: return false,
                                )
                            ) {
                                is ComposerSmsOperationResult.Success -> {
                                    ownedOperation = result.value
                                    isEligible(acceptedGeneration)
                                }
                                else -> false
                            }
                        }

                        override suspend fun onSubmitting(
                            providerId: ProviderMessageId,
                            providerConversationId: ConversationId,
                            unitCount: Int,
                        ): Boolean {
                            val binding = ownedOperation.providerBinding
                            if (
                                !isEligible(acceptedGeneration) ||
                                binding == null ||
                                binding.providerMessageId != providerId ||
                                binding.providerConversationId != providerConversationId ||
                                binding.unitCount != unitCount
                            ) {
                                return false
                            }
                            return when (
                                val result = operations.markSubmitting(
                                    operationId = ownedOperation.operationId,
                                    expectedRevision = ownedOperation.revision,
                                    providerBinding = binding,
                                    updatedTimestampMillis = nextTimestamp(ownedOperation) ?: return false,
                                )
                            ) {
                                is ComposerSmsOperationResult.Success -> {
                                    ownedOperation = result.value
                                    isEligible(acceptedGeneration)
                                }
                                else -> false
                            }
                        }
                    }
                    val result = transport.sendSms(
                        request = SmsSendRequest(
                            operationId = ownedOperation.operationId,
                            recipients = recipients,
                            body = reservation.authoritativeBody,
                            subscriptionId = command.subscriptionId,
                            requestDeliveryReport = false,
                            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
                        ),
                        ownership = SmsSubmissionOwnership.CallerOwned(observer),
                    )
                    // Once a durable reservation exists, immediate transport
                    // classification is part of the same safety envelope. Caller
                    // cancellation must not strand RESERVED or SUBMITTING work.
                    val classified = withContext(NonCancellable) {
                        handleImmediateResult(result, ownedOperation)
                    }
                    if (!classified) requestClassificationRecovery()
                } catch (cancelled: CancellationException) {
                    val classified = withContext(NonCancellable) {
                        reconcileInterrupted(ownedOperation)
                    }
                    if (!classified) requestClassificationRecovery()
                    throw cancelled
                } catch (_: RuntimeException) {
                    val classified = withContext(NonCancellable) {
                        reconcileInterrupted(ownedOperation)
                    }
                    if (!classified) requestClassificationRecovery()
                    return@withLock ThreadSmsSendAttempt.STARTED
                }
                ThreadSmsSendAttempt.STARTED
            }
        } catch (cancelled: CancellationException) {
            if (reservationAccepted) throw cancelled
            classifyExceptionalPreReservationExit(command.identity.providerThreadId)
        } catch (_: RuntimeException) {
            if (reservationAccepted) {
                requestClassificationRecovery()
                ThreadSmsSendAttempt.STARTED
            } else {
                classifyExceptionalPreReservationExit(command.identity.providerThreadId)
            }
        }
    }

    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean =
        operationMutex.withLock {
            val operation = currentOperation(providerThreadId) ?: return@withLock false
            if (operation.phase != ComposerSmsOperationPhase.SUBMISSION_UNKNOWN) {
                return@withLock false
            }
            when (
                operations.acknowledgeAndRemove(
                    operation.operationId,
                    operation.revision,
                    nextTimestamp(operation) ?: return@withLock false,
                )
            ) {
                is ComposerSmsOperationResult.Success,
                ComposerSmsOperationResult.NotFound,
                -> {
                    recordUnknownAcknowledged(operation, publishEpoch = false)
                    true
                }
                else -> when (operations.read(operation.operationId)) {
                    ComposerSmsOperationResult.NotFound -> {
                        recordUnknownAcknowledged(operation, publishEpoch = false)
                        true
                    }
                    else -> {
                        requestTerminalVerification(operation, TerminalVerificationKind.UNKNOWN_ACKNOWLEDGEMENT)
                        false
                    }
                }
            }
        }

    override suspend fun recover(): ThreadSmsRecoveryResult = operationMutex.withLock {
        val recoveryGeneration = fenceGeneration.get()
        recoveryGate.value = RecoveryGate.NOT_READY
        if (!roleState.isRoleHeld()) return@withLock ThreadSmsRecoveryResult.DEFERRED
        val snapshot = when (val result = operations.recoverySnapshot()) {
            is ComposerSmsOperationResult.Success -> result.value
            else -> {
                if (isEligible(recoveryGeneration)) {
                    recoveryGate.value = RecoveryGate.STORAGE_BLOCKED
                }
                return@withLock ThreadSmsRecoveryResult.STORAGE_BLOCKED
            }
        }
        val acknowledgedSnapshot = when (val result = operations.acknowledgedRecoverySnapshot()) {
            is ComposerSmsOperationResult.Success -> result.value
            else -> {
                if (isEligible(recoveryGeneration)) {
                    recoveryGate.value = RecoveryGate.STORAGE_BLOCKED
                }
                return@withLock ThreadSmsRecoveryResult.STORAGE_BLOCKED
            }
        }
        var deferred = false
        snapshot.forEach { operation ->
            if (!isEligible(recoveryGeneration)) {
                deferred = true
                return@forEach
            }
            val settled = when (operation.phase) {
                ComposerSmsOperationPhase.RESERVED -> markKnownUnsent(operation) != null
                ComposerSmsOperationPhase.PREPARED ->
                    settlePreBoundaryKnownUnsent(operation)
                ComposerSmsOperationPhase.SUBMITTING,
                ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
                -> markSubmissionUnknown(operation) != null
                ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED ->
                    settleSuccessfulCallback(operation)
                ComposerSmsOperationPhase.SUBMISSION_UNKNOWN -> true
                ComposerSmsOperationPhase.KNOWN_UNSENT ->
                    operation.providerBinding?.let {
                        settleProviderStatus(it, SmsProviderStatus.FAILED)
                    } ?: true
            }
            if (!settled) deferred = true
        }
        acknowledgedSnapshot.forEach { receipt ->
            if (!isEligible(recoveryGeneration)) {
                deferred = true
                return@forEach
            }
            if (!settleAcknowledgedCallback(receipt)) deferred = true
        }
        if (!isEligible(recoveryGeneration)) {
            return@withLock ThreadSmsRecoveryResult.DEFERRED
        }
        // A valid Room snapshot is the global readiness authority. Provider
        // cleanup remains owned by its exact row and must not brick other threads.
        recoveryGate.value = RecoveryGate.READY
        if (deferred) {
            ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS
        } else {
            ThreadSmsRecoveryResult.READY
        }
    }

    override fun fence() {
        fenceGeneration.incrementAndGet()
        recoveryGate.value = RecoveryGate.NOT_READY
    }

    override suspend fun handleTransportResult(result: TransportResult): Boolean =
        operationMutex.withLock {
            if (
                result.operationOrigin != TransportResult.OperationOrigin.COMPOSER ||
                result.transport != MessageTransportKind.SMS
            ) {
                return@withLock false
            }
            val operation = when (val read = operations.read(result.operationId)) {
                is ComposerSmsOperationResult.Success -> read.value
                ComposerSmsOperationResult.NotFound ->
                    return@withLock handleAcknowledgedTransportResult(result)
                else -> {
                    if (!requestExactCallbackRetry(result)) requestClassificationRecovery()
                    return@withLock false
                }
            }
            val binding = result.exactSingleUnitBindingOrNull() ?: return@withLock true
            if (operation.providerBinding != binding) return@withLock true
            when (result) {
                is TransportResult.Sent -> {
                    if (operation.phase == ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED) {
                        // Duplicate exact callbacks retry the checkpointed settlement.
                        if (!settleSuccessfulCallback(operation)) {
                            requestClassificationRecovery()
                        }
                        return@withLock true
                    }
                    val checkpoint = when (
                        val marked = operations.markSentCallbackSucceeded(
                            operationId = operation.operationId,
                            expectedRevision = operation.revision,
                            providerBinding = binding,
                            updatedTimestampMillis = nextTimestamp(operation) ?: return@withLock true,
                        )
                    ) {
                        is ComposerSmsOperationResult.Success -> marked.value
                        else -> {
                            requestExactCallbackRetry(result)
                            return@withLock true
                        }
                    }
                    if (!settleSuccessfulCallback(checkpoint)) {
                        requestClassificationRecovery()
                    }
                }
                is TransportResult.Failed -> {
                    if (result.stage != TransportResult.FailureStage.SENT_CALLBACK) {
                        return@withLock true
                    }
                    when (
                        val marked = operations.markSentCallbackFailed(
                            operationId = operation.operationId,
                            expectedRevision = operation.revision,
                            providerBinding = binding,
                            updatedTimestampMillis = nextTimestamp(operation) ?: return@withLock true,
                        )
                    ) {
                        is ComposerSmsOperationResult.Success -> {
                            if (!settleProviderStatus(binding, SmsProviderStatus.FAILED)) {
                                requestClassificationRecovery()
                            }
                        }
                        else -> requestExactCallbackRetry(result)
                    }
                }
                else -> Unit
            }
            true
        }

    private suspend fun revalidateCommand(
        command: ThreadSmsSendCommand,
    ): org.aurorasms.core.model.ParticipantAddress? {
        val found = conversations.loadConversation(command.identity.providerThreadId)
            as? ConversationLookupResult.Found
            ?: return null
        val verifiedIdentity = found.verifiedIdentity ?: return null
        if (verifiedIdentity != command.identity) return null
        val recipient = verifiedIdentity.participants.singleOrNull() ?: return null
        val subscriptionScope = runCatching {
            ConversationSubscriptionScope(
                participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                    verifiedIdentity.participants,
                ),
                providerThreadId = verifiedIdentity.providerThreadId,
            )
        }.getOrNull() ?: return null
        val authoritativeSubscriptionId = when (
            val preference = subscriptionPreferences.read(subscriptionScope)
        ) {
            is ConversationSubscriptionRepositoryResult.Success -> preference.value.subscriptionId
            ConversationSubscriptionRepositoryResult.NotFound -> found.summary.latestSubscriptionId
            ConversationSubscriptionRepositoryResult.StaleWrite,
            ConversationSubscriptionRepositoryResult.CorruptData,
            ConversationSubscriptionRepositoryResult.InvalidTimestamp,
            is ConversationSubscriptionRepositoryResult.StorageFailure,
            -> null
        }
        if (authoritativeSubscriptionId != command.subscriptionId) return null
        val subscription = subscriptions.findActive(command.subscriptionId) ?: return null
        return recipient.takeIf { subscription.smsCapable && roleState.isRoleHeld() }
    }

    private suspend fun prepareExistingTerminal(providerThreadId: ProviderThreadId): Boolean {
        val existing = currentOperation(providerThreadId) ?: return true
        if (existing.phase != ComposerSmsOperationPhase.KNOWN_UNSENT) return false
        val providerSettled = existing.providerBinding?.let {
            settleProviderStatus(it, SmsProviderStatus.FAILED)
        } ?: true
        if (!providerSettled) return false
        return operations.acknowledgeAndRemove(
            existing.operationId,
            existing.revision,
            nextTimestamp(existing) ?: return false,
        ) is
            ComposerSmsOperationResult.Success
    }

    private suspend fun handleAcknowledgedTransportResult(result: TransportResult): Boolean {
        val receipt = when (val read = operations.readAcknowledged(result.operationId)) {
            is ComposerSmsOperationResult.Success -> read.value
            ComposerSmsOperationResult.NotFound -> return false
            else -> {
                if (!requestExactCallbackRetry(result)) requestClassificationRecovery()
                return false
            }
        }
        val binding = result.exactSingleUnitBindingOrNull() ?: return true
        if (receipt.providerBinding != binding) return true
        val expectedProof = when (result) {
            is TransportResult.Sent -> AcknowledgedComposerSmsCallbackProof.SENT
            is TransportResult.Failed -> {
                if (result.stage != TransportResult.FailureStage.SENT_CALLBACK) return true
                AcknowledgedComposerSmsCallbackProof.FAILED
            }
            else -> return true
        }
        if (receipt.callbackProof != AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK) {
            if (receipt.callbackProof == expectedProof && !settleAcknowledgedCallback(receipt)) {
                requestClassificationRecovery()
            }
            return true
        }
        val checkpoint = when (expectedProof) {
            AcknowledgedComposerSmsCallbackProof.SENT -> operations.markAcknowledgedSent(
                operationId = receipt.operationId,
                expectedRevision = receipt.revision,
                providerBinding = binding,
                updatedTimestampMillis = nextTimestamp(receipt) ?: return true,
            )
            AcknowledgedComposerSmsCallbackProof.FAILED -> operations.markAcknowledgedFailed(
                operationId = receipt.operationId,
                expectedRevision = receipt.revision,
                providerBinding = binding,
                updatedTimestampMillis = nextTimestamp(receipt) ?: return true,
            )
            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK -> return true
        }
        when (checkpoint) {
            is ComposerSmsOperationResult.Success -> {
                if (!settleAcknowledgedCallback(checkpoint.value)) requestClassificationRecovery()
            }
            else -> requestExactCallbackRetry(result)
        }
        return true
    }

    private suspend fun currentOperation(providerThreadId: ProviderThreadId): ComposerSmsOperation? =
        when (val result = operations.observeByThread(providerThreadId).first()) {
            is ComposerSmsOperationResult.Success -> result.value
            else -> null
        }

    /**
     * Resolves an exceptional exit before [reserve] returned. Room remains the
     * authority because its transaction may have committed immediately before
     * cancellation was observed by the caller.
     */
    private suspend fun classifyExceptionalPreReservationExit(
        providerThreadId: ProviderThreadId,
    ): ThreadSmsSendAttempt = withContext(NonCancellable) {
        val observation = try {
            operations.observeByThread(providerThreadId).first()
        } catch (_: RuntimeException) {
            null
        }
        when (observation) {
            is ComposerSmsOperationResult.Success -> {
                if (observation.value == null) {
                    ThreadSmsSendAttempt.REFUSED
                } else {
                    // reserve() may have committed immediately before its caller
                    // observed an exception. No transport or callback timeout owns
                    // that RESERVED row, so hand it to bounded non-sending recovery.
                    requestClassificationRecovery()
                    ThreadSmsSendAttempt.STARTED
                }
            }
            else -> {
                requestClassificationRecovery()
                // Unknown durable acceptance must remain frozen and fail closed.
                ThreadSmsSendAttempt.STARTED
            }
        }
    }

    private suspend fun handleImmediateResult(
        result: TransportResult,
        lastKnown: ComposerSmsOperation,
    ): Boolean {
        val current = when (val read = operations.read(lastKnown.operationId)) {
            is ComposerSmsOperationResult.Success -> read.value
            ComposerSmsOperationResult.NotFound -> return true
            else -> lastKnown
        }
        if (
            result.operationId != current.operationId ||
            result.transport != MessageTransportKind.SMS
        ) {
            return reconcileInterrupted(current)
        }
        return when (result) {
            is TransportResult.Submitted -> {
                val binding = current.providerBinding
                if (
                    current.phase != ComposerSmsOperationPhase.SUBMITTING ||
                    binding == null ||
                    result.unitCount != binding.unitCount ||
                    result.providerMessageId != binding.providerMessageId ||
                    result.providerConversationId != binding.providerConversationId
                ) {
                    return reconcileInterrupted(current)
                }
                when (
                    val accepted = operations.markPlatformAccepted(
                        operationId = current.operationId,
                        expectedRevision = current.revision,
                        providerBinding = binding,
                        updatedTimestampMillis = nextTimestamp(current) ?: return false,
                    )
                ) {
                    is ComposerSmsOperationResult.Success -> {
                        scheduleCallbackTimeout(accepted.value)
                        true
                    }
                    ComposerSmsOperationResult.NotFound -> true
                    else -> markSubmissionUnknown(current) != null
                }
            }
            is TransportResult.Failed -> {
                if (result.stage == TransportResult.FailureStage.SUBMISSION_UNKNOWN) {
                    markSubmissionUnknown(current) != null
                } else {
                    reconcileKnownUnsent(current)
                }
            }
            is TransportResult.Rejected -> reconcileKnownUnsent(current)
            else -> reconcileInterrupted(current)
        }
    }

    private suspend fun reconcileInterrupted(operation: ComposerSmsOperation): Boolean {
        val current = when (val read = operations.read(operation.operationId)) {
            is ComposerSmsOperationResult.Success -> read.value
            ComposerSmsOperationResult.NotFound -> return true
            else -> return false
        }
        return when (current.phase) {
            ComposerSmsOperationPhase.RESERVED -> markKnownUnsent(current) != null
            ComposerSmsOperationPhase.PREPARED -> settlePreBoundaryKnownUnsent(current)
            ComposerSmsOperationPhase.SUBMITTING,
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
            -> markSubmissionUnknown(current) != null
            ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED -> settleSuccessfulCallback(current)
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            ComposerSmsOperationPhase.KNOWN_UNSENT,
            -> true
        }
    }

    private suspend fun reconcileKnownUnsent(operation: ComposerSmsOperation): Boolean =
        when (operation.phase) {
            ComposerSmsOperationPhase.RESERVED -> markKnownUnsent(operation) != null
            ComposerSmsOperationPhase.PREPARED,
            ComposerSmsOperationPhase.SUBMITTING,
            -> settlePreBoundaryKnownUnsent(operation)
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED -> markSubmissionUnknown(operation) != null
            ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED -> settleSuccessfulCallback(operation)
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            ComposerSmsOperationPhase.KNOWN_UNSENT,
            -> true
        }

    private suspend fun rollbackPreBoundaryProvider(operation: ComposerSmsOperation): Boolean {
        val binding = operation.providerBinding ?: return false
        return when (
            val rolledBack = smsProvider.rollbackOutgoing(
                binding.providerMessageId,
                binding.providerConversationId,
            )
        ) {
            is ProviderAccessResult.Success -> when (rolledBack.value) {
                OutgoingSmsRollbackOutcome.TERMINALIZED,
                OutgoingSmsRollbackOutcome.ROW_ABSENT,
                OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT,
                -> true
            }
            else -> false
        }
    }

    private suspend fun settlePreBoundaryKnownUnsent(operation: ComposerSmsOperation): Boolean {
        // The typed live result (or inherited PREPARED phase) proves that the
        // platform boundary was not crossed. Persist that fact first; exact
        // provider cleanup can then remain retryable without losing the proof.
        val knownUnsent = markKnownUnsentBeforeBoundary(operation) ?: return false
        return knownUnsent.providerBinding?.let { rollbackPreBoundaryProvider(knownUnsent) } ?: true
    }

    private suspend fun markKnownUnsent(operation: ComposerSmsOperation): ComposerSmsOperation? =
        when (
            val result = operations.markKnownUnsent(
                operationId = operation.operationId,
                expectedRevision = operation.revision,
                updatedTimestampMillis = nextTimestamp(operation) ?: return null,
            )
        ) {
            is ComposerSmsOperationResult.Success -> result.value
            else -> null
        }

    /**
     * A live transport refusal after the SUBMITTING checkpoint still proves
     * that SmsManager was never invoked. The exact-callback transition is the
     * existing bound SUBMITTING -> KNOWN_UNSENT CAS for that durable fact.
     */
    private suspend fun markKnownUnsentBeforeBoundary(
        operation: ComposerSmsOperation,
    ): ComposerSmsOperation? = when (operation.phase) {
        ComposerSmsOperationPhase.SUBMITTING -> {
            val binding = operation.providerBinding ?: return null
            when (
                val result = operations.markSentCallbackFailed(
                    operationId = operation.operationId,
                    expectedRevision = operation.revision,
                    providerBinding = binding,
                    updatedTimestampMillis = nextTimestamp(operation) ?: return null,
                )
            ) {
                is ComposerSmsOperationResult.Success -> result.value
                else -> null
            }
        }
        else -> markKnownUnsent(operation)
    }

    private suspend fun markSubmissionUnknown(operation: ComposerSmsOperation): ComposerSmsOperation? {
        val binding = operation.providerBinding ?: return null
        return when (
            val result = operations.markSubmissionUnknown(
                operationId = operation.operationId,
                expectedRevision = operation.revision,
                providerBinding = binding,
                updatedTimestampMillis = nextTimestamp(operation) ?: return null,
            )
        ) {
            is ComposerSmsOperationResult.Success -> result.value
            else -> null
        }
    }

    private suspend fun settleSuccessfulCallback(operation: ComposerSmsOperation): Boolean {
        val binding = operation.providerBinding ?: return false
        if (!settleProviderStatus(binding, SmsProviderStatus.COMPLETE)) return false
        return when (
            operations.completeSentAndRemove(
                operationId = operation.operationId,
                expectedRevision = operation.revision,
                providerBinding = binding,
            )
        ) {
            is ComposerSmsOperationResult.Success -> {
                recordSentCompletion(operation)
                true
            }
            ComposerSmsOperationResult.NotFound -> {
                recordSentCompletion(operation)
                true
            }
            else -> when (operations.read(operation.operationId)) {
                ComposerSmsOperationResult.NotFound -> {
                    recordSentCompletion(operation)
                    true
                }
                else -> {
                    requestTerminalVerification(operation, TerminalVerificationKind.SENT_COMPLETION)
                    false
                }
            }
        }
    }

    private suspend fun settleProviderStatus(
        binding: ComposerSmsProviderBinding,
        status: SmsProviderStatus,
    ): Boolean = when (
        val result = smsProvider.updateOutgoingStatus(
            id = binding.providerMessageId,
            conversationId = binding.providerConversationId,
            status = status,
        )
    ) {
        is ProviderAccessResult.Success -> when (result.value) {
            OutgoingSmsStatusUpdateOutcome.APPLIED,
            OutgoingSmsStatusUpdateOutcome.ROW_ABSENT,
            OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            -> true
        }
        else -> false
    }

    private suspend fun settleAcknowledgedCallback(
        receipt: AcknowledgedComposerSmsReceipt,
    ): Boolean {
        val providerStatus = when (receipt.callbackProof) {
            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK -> return true
            AcknowledgedComposerSmsCallbackProof.SENT -> SmsProviderStatus.COMPLETE
            AcknowledgedComposerSmsCallbackProof.FAILED -> SmsProviderStatus.FAILED
        }
        if (!settleProviderStatus(receipt.providerBinding, providerStatus)) return false
        return when (
            operations.completeAcknowledged(
                operationId = receipt.operationId,
                expectedRevision = receipt.revision,
                providerBinding = receipt.providerBinding,
                callbackProof = receipt.callbackProof,
            )
        ) {
            is ComposerSmsOperationResult.Success,
            ComposerSmsOperationResult.NotFound,
            -> true
            else -> false
        }
    }

    private fun scheduleCallbackTimeout(operation: ComposerSmsOperation) {
        applicationScope.launch {
            delay(SENT_CALLBACK_TIMEOUT_MILLIS)
            operationMutex.withLock {
                val current = when (val read = operations.read(operation.operationId)) {
                    is ComposerSmsOperationResult.Success -> read.value
                    ComposerSmsOperationResult.NotFound -> return@withLock
                    else -> {
                        requestClassificationRecovery()
                        return@withLock
                    }
                }
                if (current.phase == ComposerSmsOperationPhase.PLATFORM_ACCEPTED) {
                    if (markSubmissionUnknown(current) == null) {
                        requestClassificationRecovery()
                    }
                }
            }
        }
    }

    /**
     * Typed storage failures do not throw, so they need an explicit retry path
     * after the active send releases [operationMutex]. This loop is bounded;
     * durable recovery remains non-sending and fail-closed on unreadable state.
     */
    private fun requestClassificationRecovery() {
        if (!classificationRecoveryScheduled.compareAndSet(false, true)) return
        applicationScope.launch {
            try {
                var retryDelayMillis = CLASSIFICATION_RECOVERY_INITIAL_DELAY_MILLIS
                repeat(CLASSIFICATION_RECOVERY_MAXIMUM_ATTEMPTS) {
                    delay(retryDelayMillis)
                    val result = try {
                        recover()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        ThreadSmsRecoveryResult.DEFERRED
                    }
                    if (result == ThreadSmsRecoveryResult.READY) return@launch
                    retryDelayMillis = (retryDelayMillis * 2L)
                        .coerceAtMost(CLASSIFICATION_RECOVERY_MAXIMUM_DELAY_MILLIS)
                }
            } finally {
                classificationRecoveryScheduled.set(false)
            }
        }
    }

    /** Retains an exact content-free callback proof across transient Room failures. */
    private fun requestExactCallbackRetry(result: TransportResult): Boolean {
        if (
            result.operationOrigin != TransportResult.OperationOrigin.COMPOSER ||
            result.transport != MessageTransportKind.SMS ||
            result.exactSingleUnitBindingOrNull() == null
        ) {
            return false
        }
        val proof = when (result) {
            is TransportResult.Sent -> ExactCallbackProof.SENT
            is TransportResult.Failed -> {
                if (result.stage != TransportResult.FailureStage.SENT_CALLBACK) return false
                ExactCallbackProof.NOT_SENT
            }
            else -> return false
        }
        val key = ExactCallbackRetryKey(result.operationId.value, proof)
        if (!exactCallbackRetries.add(key)) return true
        applicationScope.launch {
            try {
                var retryDelayMillis = EXACT_CALLBACK_RETRY_INITIAL_DELAY_MILLIS
                repeat(EXACT_CALLBACK_RETRY_MAXIMUM_ATTEMPTS) {
                    delay(retryDelayMillis)
                    try {
                        handleTransportResult(result)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        // Retain the exact proof for the next bounded attempt.
                    }
                    retryDelayMillis = (retryDelayMillis * 2L)
                        .coerceAtMost(EXACT_CALLBACK_RETRY_MAXIMUM_DELAY_MILLIS)
                }
            } finally {
                exactCallbackRetries.remove(key)
            }
        }
        return true
    }

    /**
     * Verifies commit-ambiguous terminal transactions without resending. A
     * missing exact operation proves its atomic terminal transaction committed;
     * a still-present exact operation is retried with its current revision.
     */
    private fun requestTerminalVerification(
        operation: ComposerSmsOperation,
        kind: TerminalVerificationKind,
    ) {
        val key = TerminalVerificationKey(operation.operationId.value, kind)
        if (!terminalVerifications.add(key)) return
        applicationScope.launch {
            try {
                var retryDelayMillis = TERMINAL_VERIFICATION_INITIAL_DELAY_MILLIS
                repeat(TERMINAL_VERIFICATION_MAXIMUM_ATTEMPTS) {
                    delay(retryDelayMillis)
                    val verified = try {
                        operationMutex.withLock {
                            verifyTerminalTransaction(operation, kind)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (verified) return@launch
                    retryDelayMillis = (retryDelayMillis * 2L)
                        .coerceAtMost(TERMINAL_VERIFICATION_MAXIMUM_DELAY_MILLIS)
                }
            } finally {
                terminalVerifications.remove(key)
            }
        }
    }

    private suspend fun verifyTerminalTransaction(
        original: ComposerSmsOperation,
        kind: TerminalVerificationKind,
    ): Boolean = when (val read = operations.read(original.operationId)) {
        ComposerSmsOperationResult.NotFound -> {
            publishVerifiedTerminalSignal(original, kind)
            true
        }
        is ComposerSmsOperationResult.Success -> {
            val current = read.value
            if (!current.hasSameImmutableOwnershipAs(original)) return true
            when (kind) {
                TerminalVerificationKind.SENT_COMPLETION -> {
                    if (current.phase != ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED) return true
                    val binding = current.providerBinding ?: return true
                    when (
                        operations.completeSentAndRemove(
                            operationId = current.operationId,
                            expectedRevision = current.revision,
                            providerBinding = binding,
                        )
                    ) {
                        is ComposerSmsOperationResult.Success,
                        ComposerSmsOperationResult.NotFound,
                        -> {
                            recordSentCompletion(original)
                            true
                        }
                        else -> false
                    }
                }
                TerminalVerificationKind.UNKNOWN_ACKNOWLEDGEMENT -> {
                    if (current.phase != ComposerSmsOperationPhase.SUBMISSION_UNKNOWN) return true
                    when (
                        operations.acknowledgeAndRemove(
                            operationId = current.operationId,
                            expectedRevision = current.revision,
                            acknowledgedTimestampMillis =
                                nextTimestamp(current) ?: return false,
                        )
                    ) {
                        is ComposerSmsOperationResult.Success,
                        ComposerSmsOperationResult.NotFound,
                        -> {
                            recordUnknownAcknowledged(original, publishEpoch = true)
                            true
                        }
                        else -> false
                    }
                }
            }
        }
        else -> false
    }

    private fun publishVerifiedTerminalSignal(
        operation: ComposerSmsOperation,
        kind: TerminalVerificationKind,
    ) {
        when (kind) {
            TerminalVerificationKind.SENT_COMPLETION -> recordSentCompletion(operation)
            TerminalVerificationKind.UNKNOWN_ACKNOWLEDGEMENT ->
                recordUnknownAcknowledged(operation, publishEpoch = true)
        }
    }

    private fun ComposerSmsOperation.hasSameImmutableOwnershipAs(other: ComposerSmsOperation): Boolean =
        operationId == other.operationId &&
            providerThreadId == other.providerThreadId &&
            draftId == other.draftId &&
            draftRevision == other.draftRevision &&
            subscriptionId == other.subscriptionId &&
            providerBinding == other.providerBinding

    private fun recordSentCompletion(operation: ComposerSmsOperation) {
        if (!recordTerminalOperationId(completionSignaledOperationIds, operation.operationId.value)) return
        if (completionEpochSequence < Long.MAX_VALUE) completionEpochSequence += 1L
        completionEpochs.value = boundedCompletionEpochsAfterRecord(
            current = completionEpochs.value,
            providerThreadId = operation.providerThreadId,
            completionEpoch = completionEpochSequence,
        )
    }

    private fun recordUnknownAcknowledged(
        operation: ComposerSmsOperation,
        publishEpoch: Boolean,
    ) {
        if (!recordTerminalOperationId(unknownAcknowledgedOperationIds, operation.operationId.value)) return
        if (!publishEpoch) return
        if (unknownAcknowledgementEpochSequence < Long.MAX_VALUE) {
            unknownAcknowledgementEpochSequence += 1L
        }
        unknownAcknowledgementEpochs.value = boundedCompletionEpochsAfterRecord(
            current = unknownAcknowledgementEpochs.value,
            providerThreadId = operation.providerThreadId,
            completionEpoch = unknownAcknowledgementEpochSequence,
        )
    }

    private fun recordTerminalOperationId(ids: LinkedHashSet<Long>, operationId: Long): Boolean {
        if (!ids.add(operationId)) return false
        while (ids.size > MAXIMUM_TERMINAL_SIGNAL_OPERATION_IDS) {
            ids.remove(ids.first())
        }
        return true
    }

    private fun isEligible(expectedGeneration: Long): Boolean =
        fenceGeneration.get() == expectedGeneration && roleState.isRoleHeld()

    private fun nextTimestamp(operation: ComposerSmsOperation): Long? {
        if (operation.updatedTimestampMillis == Long.MAX_VALUE) return null
        return maxOf(safeNow(), operation.updatedTimestampMillis + 1L)
    }

    private fun nextTimestamp(receipt: AcknowledgedComposerSmsReceipt): Long? {
        if (receipt.updatedTimestampMillis == Long.MAX_VALUE) return null
        return maxOf(safeNow(), receipt.updatedTimestampMillis + 1L)
    }

    private fun safeNow(): Long = nowMillis().coerceAtLeast(0L)

    private fun ComposerSmsOperationResult<ComposerSmsOperation?>.toUiPhase(): ThreadSmsSendPhase =
        when (this) {
            is ComposerSmsOperationResult.Success -> when (value?.phase) {
                null -> ThreadSmsSendPhase.IDLE
                ComposerSmsOperationPhase.RESERVED,
                ComposerSmsOperationPhase.PREPARED,
                ComposerSmsOperationPhase.SUBMITTING,
                ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
                ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
                -> ThreadSmsSendPhase.SENDING
                ComposerSmsOperationPhase.KNOWN_UNSENT -> ThreadSmsSendPhase.KNOWN_UNSENT
                ComposerSmsOperationPhase.SUBMISSION_UNKNOWN ->
                    ThreadSmsSendPhase.SUBMISSION_UNKNOWN
            }
            else -> ThreadSmsSendPhase.RECOVERY_PENDING
        }

    private fun TransportResult.exactSingleUnitBindingOrNull(): ComposerSmsProviderBinding? {
        val providerId: ProviderMessageId?
        val conversationId: ConversationId?
        val unitIndex: Int
        val unitCount: Int
        when (this) {
            is TransportResult.Sent -> {
                providerId = providerMessageId
                conversationId = providerConversationId
                unitIndex = this.unitIndex
                unitCount = this.unitCount
            }
            is TransportResult.Failed -> {
                providerId = providerMessageId
                conversationId = providerConversationId
                unitIndex = this.unitIndex
                unitCount = this.unitCount
            }
            else -> return null
        }
        if (
            providerId == null ||
            conversationId == null ||
            unitIndex != 0 ||
            unitCount != REQUIRED_SMS_UNIT_COUNT
        ) {
            return null
        }
        return ComposerSmsProviderBinding(providerId, conversationId, unitCount)
    }

    private companion object {
        const val REQUIRED_SMS_UNIT_COUNT: Int = 1
        const val SENT_CALLBACK_TIMEOUT_MILLIS: Long = 120_000L
        const val CLASSIFICATION_RECOVERY_INITIAL_DELAY_MILLIS: Long = 250L
        const val CLASSIFICATION_RECOVERY_MAXIMUM_DELAY_MILLIS: Long = 2_000L
        const val CLASSIFICATION_RECOVERY_MAXIMUM_ATTEMPTS: Int = 4
        const val EXACT_CALLBACK_RETRY_INITIAL_DELAY_MILLIS: Long = 250L
        const val EXACT_CALLBACK_RETRY_MAXIMUM_DELAY_MILLIS: Long = 2_000L
        const val EXACT_CALLBACK_RETRY_MAXIMUM_ATTEMPTS: Int = 4
        const val TERMINAL_VERIFICATION_INITIAL_DELAY_MILLIS: Long = 250L
        const val TERMINAL_VERIFICATION_MAXIMUM_DELAY_MILLIS: Long = 2_000L
        const val TERMINAL_VERIFICATION_MAXIMUM_ATTEMPTS: Int = 4
        const val MAXIMUM_TERMINAL_SIGNAL_OPERATION_IDS: Int = 256
    }
}

private object NoConversationSubscriptionPreferenceRepository :
    ConversationSubscriptionPreferenceRepository {
    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.NotFound

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: org.aurorasms.core.model.AuroraSubscriptionId,
        expectedRevision: org.aurorasms.core.state.ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.CorruptData
}

private enum class ExactCallbackProof {
    SENT,
    NOT_SENT,
}

private data class ExactCallbackRetryKey(
    val operationId: Long,
    val proof: ExactCallbackProof,
)

private enum class TerminalVerificationKind {
    SENT_COMPLETION,
    UNKNOWN_ACKNOWLEDGEMENT,
}

private data class TerminalVerificationKey(
    val operationId: Long,
    val kind: TerminalVerificationKind,
)

private enum class RecoveryGate {
    NOT_READY,
    READY,
    STORAGE_BLOCKED,
}

internal const val MAXIMUM_COMPLETION_EPOCH_THREADS: Int = 256

internal fun boundedCompletionEpochsAfterRecord(
    current: Map<Long, Long>,
    providerThreadId: ProviderThreadId,
    completionEpoch: Long,
): Map<Long, Long> {
    require(completionEpoch >= 0L) { "Completion epoch cannot be negative" }
    val threadId = providerThreadId.value
    val bounded = LinkedHashMap<Long, Long>(minOf(current.size + 1, MAXIMUM_COMPLETION_EPOCH_THREADS))
    current.forEach { (existingThreadId, epoch) ->
        if (existingThreadId != threadId) bounded[existingThreadId] = epoch
    }
    bounded[threadId] = completionEpoch
    while (bounded.size > MAXIMUM_COMPLETION_EPOCH_THREADS) {
        bounded.remove(bounded.keys.first())
    }
    return bounded
}
