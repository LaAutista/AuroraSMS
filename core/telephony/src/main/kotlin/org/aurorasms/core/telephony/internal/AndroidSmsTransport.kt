// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.ArrayList
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.TransportOwnedSmsRecoveryResult
import org.aurorasms.core.telephony.acceptsNewSubmissions
import org.aurorasms.core.telephony.hasValidOperationOwnership
import org.aurorasms.core.telephony.receiver.SmsDeliveredReceiver
import org.aurorasms.core.telephony.receiver.SmsSentReceiver

class AndroidSmsTransport(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val subscriptions: SubscriptionRepository,
    private val smsProvider: SmsProviderDataSource,
    private val mmsTransport: AndroidMmsTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MessageTransport {
    private val appContext = context.applicationContext
    private val transportOwnedJournal = OutgoingSmsSubmissionJournal(appContext, nowMillis = nowMillis)
    private val transportOwnedSubmissionMutex = Mutex()

    override suspend fun sendSms(
        request: SmsSendRequest,
        ownership: SmsSubmissionOwnership,
    ): TransportResult {
        if (!request.hasValidOperationOwnership(ownership)) {
            return request.failed(
                reason = TransportResult.FailureReason.INTERNAL_ERROR,
                retryable = false,
            )
        }
        return when (ownership) {
            is SmsSubmissionOwnership.CallerOwned -> sendSmsWithOwner(
                request = request,
                submissionObserver = ownership.observer,
                transportJournalOwned = false,
            )
            SmsSubmissionOwnership.TransportOwned -> transportOwnedSubmissionMutex.withLock {
                if (!recoverTransportOwnedSubmissionsLocked().acceptsNewSubmissions) {
                    return@withLock request.failed(
                        reason = TransportResult.FailureReason.INTERNAL_ERROR,
                        retryable = true,
                    )
                }
                sendSmsWithOwner(
                    request = request,
                    submissionObserver = null,
                    transportJournalOwned = true,
                )
            }
        }
    }

    /** Reconciles only records inherited by this process; live sends share the same mutex. */
    suspend fun recoverTransportOwnedSubmissions(): TransportOwnedSmsRecoveryResult =
        transportOwnedSubmissionMutex.withLock { recoverTransportOwnedSubmissionsLocked() }

    private suspend fun sendSmsWithOwner(
        request: SmsSendRequest,
        submissionObserver: SmsSubmissionObserver?,
        transportJournalOwned: Boolean,
    ): TransportResult {
        val recipient = request.recipients.singleSmsRecipientOrNull()
        val rejection = TransportPolicy.smsRejection(
            roleHeld = roleState.isRoleHeld(),
            featureAvailable = hasMessagingFeature(),
            permissionGranted = hasSendPermission(),
            subscriptionActive = subscriptions.findActive(request.subscriptionId)?.smsCapable == true,
            singleRecipient = recipient != null,
            contentPresent = request.body.isNotEmpty(),
            emergencyRecipient = recipient?.let(::isEmergencyNumber) == true,
        )
        if (rejection != null) {
            return request.rejected(rejection)
        }
        checkNotNull(recipient)

        val manager: SmsManager
        val parts: ArrayList<String>
        try {
            manager = smsManager(request.subscriptionId.value)
            parts = manager.divideMessage(request.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false)
        } catch (_: UnsupportedOperationException) {
            return request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false)
        } catch (_: IllegalArgumentException) {
            return request.failed(TransportResult.FailureReason.INVALID_RECIPIENT, false)
        } catch (_: RuntimeException) {
            return request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true)
        }
        if (parts.isEmpty() || parts.size > MAX_SMS_PARTS) {
            return request.failed(
                reason = TransportResult.FailureReason.PAYLOAD_TOO_LARGE,
                retryable = false,
                unitCount = parts.size.coerceAtLeast(1),
            )
        }

        val stored = when (
            val result = awaitOutgoingInsertResult {
                smsProvider.insertOutgoing(
                    OutgoingSmsRecord(
                        recipient = recipient,
                        body = request.body,
                        timestampMillis = nowMillis().coerceAtLeast(0L),
                        subscriptionId = request.subscriptionId,
                    ),
                )
            }
        ) {
            is ProviderAccessResult.Success -> result.value
            ProviderAccessResult.RoleRequired -> return request.rejected(TransportResult.FailureReason.ROLE_NOT_HELD)
            ProviderAccessResult.PermissionDenied -> return request.rejected(TransportResult.FailureReason.PERMISSION_DENIED)
            is ProviderAccessResult.InvalidInput -> return request.rejected(TransportResult.FailureReason.INVALID_RECIPIENT)
            is ProviderAccessResult.Unsupported,
            is ProviderAccessResult.Unavailable
            -> return request.rejected(TransportResult.FailureReason.PROVIDER_UNAVAILABLE)
        }

        val markKnownUnsent: suspend () -> Unit = {
            val rollback = rollbackProviderBestEffort(
                providerId = stored.providerId,
                conversationId = stored.conversationId,
            )
            if (transportJournalOwned) {
                try {
                    when ((rollback as? ProviderAccessResult.Success)?.value) {
                        OutgoingSmsRollbackOutcome.TERMINALIZED,
                        OutgoingSmsRollbackOutcome.ROW_ABSENT,
                        -> transportOwnedJournal.acknowledgeKnownUnsent(
                            operationId = request.operationId,
                            providerId = stored.providerId,
                            conversationId = stored.conversationId,
                            unitCount = parts.size,
                        )
                        OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT -> {
                            transportOwnedJournal.recordKnownUnsentQuarantined(
                                operationId = request.operationId,
                                providerId = stored.providerId,
                                conversationId = stored.conversationId,
                                unitCount = parts.size,
                            )
                        }
                        null -> Unit
                    }
                } catch (_: RuntimeException) {
                    // The PREPARED record remains retryable recovery ownership.
                }
            }
        }
        ensureActiveOutgoingSmsOrRollback(markKnownUnsent)
        val effectiveSubmissionObserver = submissionObserver
            ?: transportOwnedObserver(request.operationId, stored.conversationId)
        return try {
            when (
                runObservedSmsSubmission(
                    observer = effectiveSubmissionObserver,
                    providerId = stored.providerId,
                    providerConversationId = stored.conversationId,
                    unitCount = parts.size,
                    markFailed = markKnownUnsent,
                    armProvider = {
                        smsProvider.armOutgoing(stored.providerId) is ProviderAccessResult.Success
                    },
                    submissionBoundaryAllowed = roleState::isRoleHeld,
                    prepareSubmission = {
                        val sent = ArrayList<PendingIntent>(parts.size)
                        val delivered = if (request.requestDeliveryReport) {
                            ArrayList<PendingIntent>(parts.size)
                        } else {
                            null
                        }
                        parts.indices.forEach { index ->
                            sent += sentPendingIntent(request, stored, index, parts.size)
                            delivered?.add(
                                deliveredPendingIntent(request, stored, index, parts.size),
                            )
                        }
                        val platformSubmission: () -> Unit = {
                            if (parts.size == 1) {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    manager.sendTextMessage(
                                        recipient.value,
                                        null,
                                        parts.single(),
                                        sent.single(),
                                        delivered?.singleOrNull(),
                                        request.operationId.value,
                                    )
                                } else {
                                    manager.sendTextMessage(
                                        recipient.value,
                                        null,
                                        parts.single(),
                                        sent.single(),
                                        delivered?.singleOrNull(),
                                    )
                                }
                            } else if (Build.VERSION.SDK_INT >= 30) {
                                manager.sendMultipartTextMessage(
                                    recipient.value,
                                    null,
                                    parts,
                                    sent,
                                    delivered,
                                    request.operationId.value,
                                )
                            } else {
                                manager.sendMultipartTextMessage(
                                    recipient.value,
                                    null,
                                    parts,
                                    sent,
                                    delivered,
                                )
                            }
                        }
                        platformSubmission
                    },
                )
            ) {
                ObservedSmsSubmissionResult.OBSERVER_REJECTED ->
                    return request.failed(
                        reason = TransportResult.FailureReason.INTERNAL_ERROR,
                        retryable = true,
                        provider = stored,
                        unitCount = parts.size,
                    )
                ObservedSmsSubmissionResult.SUBMISSION_UNKNOWN -> {
                    if (transportJournalOwned) {
                        try {
                            transportOwnedJournal.recordSubmissionUnknown(
                                operationId = request.operationId,
                                providerId = stored.providerId,
                                conversationId = stored.conversationId,
                                unitCount = parts.size,
                            )
                        } catch (_: RuntimeException) {
                            // SUBMITTING remains the conservative durable state.
                        }
                    }
                    return request.failed(
                        reason = TransportResult.FailureReason.INTERNAL_ERROR,
                        retryable = false,
                        provider = stored,
                        unitCount = parts.size,
                        stage = TransportResult.FailureStage.SUBMISSION_UNKNOWN,
                    )
                }
                ObservedSmsSubmissionResult.SUBMITTED -> Unit
            }
            if (
                transportJournalOwned &&
                !try {
                    transportOwnedJournal.acknowledgeSubmitted(
                        operationId = request.operationId,
                        providerId = stored.providerId,
                        conversationId = stored.conversationId,
                        unitCount = parts.size,
                    )
                } catch (_: RuntimeException) {
                    false
                }
            ) {
                return request.failed(
                    reason = TransportResult.FailureReason.INTERNAL_ERROR,
                    retryable = false,
                    provider = stored,
                    unitCount = parts.size,
                    stage = TransportResult.FailureStage.SUBMISSION_UNKNOWN,
                )
            }
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = parts.size,
                providerMessageId = stored.providerId,
                providerConversationId = stored.conversationId,
                operationOrigin = request.operationOrigin,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            markKnownUnsent()
            request.failed(
                TransportResult.FailureReason.PERMISSION_DENIED,
                false,
                stored,
                parts.size,
            )
        } catch (_: UnsupportedOperationException) {
            markKnownUnsent()
            request.failed(
                TransportResult.FailureReason.FEATURE_UNAVAILABLE,
                false,
                stored,
                parts.size,
            )
        } catch (_: IllegalArgumentException) {
            markKnownUnsent()
            request.failed(
                TransportResult.FailureReason.INVALID_RECIPIENT,
                false,
                stored,
                parts.size,
            )
        } catch (_: RuntimeException) {
            markKnownUnsent()
            request.failed(
                TransportResult.FailureReason.INTERNAL_ERROR,
                true,
                stored,
                parts.size,
            )
        }
    }

    override suspend fun sendMms(
        request: MmsSendRequest,
        ownership: org.aurorasms.core.telephony.MmsSubmissionOwnership,
    ): TransportResult = mmsTransport.sendMms(request, ownership)

    override suspend fun downloadMms(request: MmsDownloadRequest): TransportResult =
        mmsTransport.downloadMms(request)

    private fun transportOwnedObserver(
        operationId: MessageId,
        conversationId: ConversationId,
    ): SmsSubmissionObserver =
        object : SmsSubmissionObserver {
            override suspend fun onPrepared(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean =
                providerConversationId == conversationId &&
                    transportOwnedJournal.recordPrepared(
                        operationId,
                        providerId,
                        providerConversationId,
                        unitCount,
                    )

            override suspend fun onSubmitting(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean =
                providerConversationId == conversationId &&
                    transportOwnedJournal.recordSubmitting(
                        operationId,
                        providerId,
                        providerConversationId,
                        unitCount,
                    )
        }

    private suspend fun recoverTransportOwnedSubmissionsLocked(): TransportOwnedSmsRecoveryResult =
        recoverTransportOwnedSubmissionRecords(
            recoverySnapshot = transportOwnedJournal::recoverySnapshot,
            rollbackOutgoing = { record ->
                rollbackProviderBestEffort(record.providerId, record.conversationId)
            },
            acknowledgeKnownUnsent = { record ->
                transportOwnedJournal.acknowledgeKnownUnsent(
                    operationId = record.operationMessageId(),
                    providerId = record.providerId,
                    conversationId = record.conversationId,
                    unitCount = record.unitCount,
                )
            },
            quarantineKnownUnsent = { record ->
                transportOwnedJournal.recordKnownUnsentQuarantined(
                    operationId = record.operationMessageId(),
                    providerId = record.providerId,
                    conversationId = record.conversationId,
                    unitCount = record.unitCount,
                )
            },
            recordSubmissionUnknown = { record ->
                transportOwnedJournal.recordSubmissionUnknown(
                    operationId = record.operationMessageId(),
                    providerId = record.providerId,
                    conversationId = record.conversationId,
                    unitCount = record.unitCount,
                )
            },
        )

    private fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMessagingFeature(): Boolean {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    }

    private fun isEmergencyNumber(address: org.aurorasms.core.model.ParticipantAddress): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                appContext.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(address.value) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(address.value)
            }
        } catch (_: RuntimeException) {
            true
        }

    private suspend fun rollbackProviderBestEffort(
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<OutgoingSmsRollbackOutcome> =
        try {
            smsProvider.rollbackOutgoing(providerId, conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // The original submission failure remains authoritative.
            ProviderAccessResult.Unavailable("rollback outgoing SMS")
        }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            checkNotNull(appContext.getSystemService(SmsManager::class.java)).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    private fun sentPendingIntent(
        request: SmsSendRequest,
        stored: ProviderStoredMessage,
        unitIndex: Int,
        unitCount: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode(request.operationId.value, unitIndex, SENT_CHANNEL),
        SmsSentReceiver.createIntent(
            context = appContext,
            operationId = request.operationId,
            providerMessageId = stored.providerId,
            unitIndex = unitIndex,
            unitCount = unitCount,
            operationOrigin = request.operationOrigin,
            providerConversationId = stored.conversationId,
        ),
        CALLBACK_FLAGS,
    )

    private fun deliveredPendingIntent(
        request: SmsSendRequest,
        stored: ProviderStoredMessage,
        unitIndex: Int,
        unitCount: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode(request.operationId.value, unitIndex, DELIVERED_CHANNEL),
        SmsDeliveredReceiver.createIntent(
            context = appContext,
            operationId = request.operationId,
            providerMessageId = stored.providerId,
            unitIndex = unitIndex,
            unitCount = unitCount,
            operationOrigin = request.operationOrigin,
            providerConversationId = stored.conversationId,
        ),
        CALLBACK_FLAGS,
    )

    companion object {
        private const val MAX_SMS_PARTS = 255
        private const val SENT_CHANNEL = 0x51
        private const val DELIVERED_CHANNEL = 0x71
        private const val CALLBACK_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE

        internal fun requestCode(operationId: Long, unitIndex: Int, channel: Int): Int =
            (operationId xor (unitIndex.toLong() shl 32) xor channel.toLong()).hashCode() and Int.MAX_VALUE
    }
}

/**
 * Pure recovery coordinator used by the real transport and focused host tests.
 * Provider access failures defer only their exact records; journal integrity
 * failures remain the sole global gate for new transport-owned submissions.
 */
internal suspend fun recoverTransportOwnedSubmissionRecords(
    recoverySnapshot: () -> OutgoingSmsSubmissionJournal.RecoverySnapshotResult,
    rollbackOutgoing: suspend (
        OutgoingSmsSubmissionJournal.Record,
    ) -> ProviderAccessResult<OutgoingSmsRollbackOutcome>,
    acknowledgeKnownUnsent: (OutgoingSmsSubmissionJournal.Record) -> Boolean,
    quarantineKnownUnsent: (OutgoingSmsSubmissionJournal.Record) -> Boolean,
    recordSubmissionUnknown: (OutgoingSmsSubmissionJournal.Record) -> Boolean,
): TransportOwnedSmsRecoveryResult {
    val snapshot = try {
        recoverySnapshot()
    } catch (_: RuntimeException) {
        return TransportOwnedSmsRecoveryResult.JournalBlocked
    }
    val records = when (snapshot) {
        is OutgoingSmsSubmissionJournal.RecoverySnapshotResult.Available -> snapshot.records
        OutgoingSmsSubmissionJournal.RecoverySnapshotResult.PersistenceFailure -> {
            return TransportOwnedSmsRecoveryResult.JournalBlocked
        }
    }
    var recoveredCount = 0
    var deferredCount = 0
    for (record in records) {
        when (record.state) {
            OutgoingSmsSubmissionJournal.State.PREPARED -> {
                val rollback = try {
                    rollbackOutgoing(record)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    ProviderAccessResult.Unavailable("rollback outgoing SMS")
                }
                val journalUpdated = when (rollback) {
                    is ProviderAccessResult.Success -> try {
                        when (rollback.value) {
                            OutgoingSmsRollbackOutcome.TERMINALIZED,
                            OutgoingSmsRollbackOutcome.ROW_ABSENT,
                            -> acknowledgeKnownUnsent(record)
                            OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT -> {
                                quarantineKnownUnsent(record)
                            }
                        }
                    } catch (_: RuntimeException) {
                        return TransportOwnedSmsRecoveryResult.JournalBlocked
                    }
                    is ProviderAccessResult.InvalidInput,
                    ProviderAccessResult.PermissionDenied,
                    ProviderAccessResult.RoleRequired,
                    is ProviderAccessResult.Unavailable,
                    is ProviderAccessResult.Unsupported,
                    -> null
                }
                when (journalUpdated) {
                    true -> recoveredCount += 1
                    false -> return TransportOwnedSmsRecoveryResult.JournalBlocked
                    null -> deferredCount += 1
                }
            }
            OutgoingSmsSubmissionJournal.State.SUBMITTING -> {
                val journalUpdated = try {
                    recordSubmissionUnknown(record)
                } catch (_: RuntimeException) {
                    return TransportOwnedSmsRecoveryResult.JournalBlocked
                }
                if (!journalUpdated) return TransportOwnedSmsRecoveryResult.JournalBlocked
                recoveredCount += 1
            }
            OutgoingSmsSubmissionJournal.State.SUBMISSION_UNKNOWN,
            OutgoingSmsSubmissionJournal.State.KNOWN_UNSENT_QUARANTINED,
            -> Unit
        }
    }
    return TransportOwnedSmsRecoveryResult.Available(
        recoveredCount = recoveredCount,
        deferredCount = deferredCount,
    )
}

private fun OutgoingSmsSubmissionJournal.Record.operationMessageId(): MessageId =
    MessageId(ProviderKind.PENDING_OPERATION, operationId)

internal object TransportPolicy {
    fun smsRejection(
        roleHeld: Boolean,
        featureAvailable: Boolean,
        permissionGranted: Boolean,
        subscriptionActive: Boolean,
        singleRecipient: Boolean,
        contentPresent: Boolean,
        emergencyRecipient: Boolean,
    ): TransportResult.FailureReason? = when {
        !roleHeld -> TransportResult.FailureReason.ROLE_NOT_HELD
        !featureAvailable -> TransportResult.FailureReason.FEATURE_UNAVAILABLE
        !permissionGranted -> TransportResult.FailureReason.PERMISSION_DENIED
        !subscriptionActive -> TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE
        !singleRecipient || emergencyRecipient -> TransportResult.FailureReason.INVALID_RECIPIENT
        !contentPresent -> TransportResult.FailureReason.EMPTY_CONTENT
        else -> null
    }

    fun mmsRejection(
        roleHeld: Boolean,
        featureAvailable: Boolean,
        permissionGranted: Boolean,
        subscriptionActive: Boolean,
    ): TransportResult.FailureReason? = when {
        !roleHeld -> TransportResult.FailureReason.ROLE_NOT_HELD
        !featureAvailable -> TransportResult.FailureReason.FEATURE_UNAVAILABLE
        !permissionGranted -> TransportResult.FailureReason.PERMISSION_DENIED
        !subscriptionActive -> TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE
        else -> null
    }
}

private fun SmsSendRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        reason = reason,
        operationOrigin = operationOrigin,
    )

private fun SmsSendRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
    provider: ProviderStoredMessage? = null,
    unitCount: Int = 1,
    stage: TransportResult.FailureStage = TransportResult.FailureStage.SUBMISSION,
    operationOrigin: TransportResult.OperationOrigin = this.operationOrigin,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.SMS,
    reason = reason,
    retryable = retryable,
    unitCount = unitCount,
    providerMessageId = provider?.providerId,
    providerConversationId = provider?.conversationId,
    stage = stage,
    operationOrigin = operationOrigin,
)

internal enum class ObservedSmsSubmissionResult {
    SUBMITTED,
    OBSERVER_REJECTED,
    SUBMISSION_UNKNOWN,
}

internal suspend fun runObservedSmsSubmission(
    observer: SmsSubmissionObserver,
    providerId: ProviderMessageId,
    providerConversationId: ConversationId,
    unitCount: Int,
    markFailed: suspend () -> Unit,
    armProvider: suspend () -> Boolean,
    submissionBoundaryAllowed: () -> Boolean = { true },
    prepareSubmission: () -> (() -> Unit),
): ObservedSmsSubmissionResult {
    require(unitCount > 0) { "SMS submission must contain at least one unit" }
    val preparedAllowed = try {
        observerAllows { observer.onPrepared(providerId, providerConversationId, unitCount) }
    } catch (cancelled: CancellationException) {
        markFailedAfterCancellation(markFailed)
        throw cancelled
    }
    if (!preparedAllowed) {
        markFailedIgnoringRuntimeException(markFailed)
        return ObservedSmsSubmissionResult.OBSERVER_REJECTED
    }
    val submit = try {
        prepareSubmission()
    } catch (cancelled: CancellationException) {
        markFailedAfterCancellation(markFailed)
        throw cancelled
    }
    val armed = try {
        armProvider()
    } catch (cancelled: CancellationException) {
        markFailedAfterCancellation(markFailed)
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }
    if (!armed) {
        markFailedIgnoringRuntimeException(markFailed)
        return ObservedSmsSubmissionResult.OBSERVER_REJECTED
    }
    val submittingAllowed = try {
        observerAllows { observer.onSubmitting(providerId, providerConversationId, unitCount) }
    } catch (cancelled: CancellationException) {
        markFailedAfterCancellation(markFailed)
        throw cancelled
    }
    if (!submittingAllowed) {
        markFailedIgnoringRuntimeException(markFailed)
        return ObservedSmsSubmissionResult.OBSERVER_REJECTED
    }
    // This is deliberately the final check before the SmsManager Binder call.
    // A role loss here is still provably pre-boundary and can be rolled back.
    if (!submissionBoundaryAllowedIgnoringRuntimeException(submissionBoundaryAllowed)) {
        markFailedIgnoringRuntimeException(markFailed)
        return ObservedSmsSubmissionResult.OBSERVER_REJECTED
    }
    return try {
        submit()
        ObservedSmsSubmissionResult.SUBMITTED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // The Binder/service boundary may have accepted the SMS before
        // surfacing an exception. Never label or retry this as known-unsent.
        ObservedSmsSubmissionResult.SUBMISSION_UNKNOWN
    }
}

private suspend fun observerAllows(callback: suspend () -> Boolean): Boolean =
    try {
        callback()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }

private fun submissionBoundaryAllowedIgnoringRuntimeException(callback: () -> Boolean): Boolean =
    try {
        callback()
    } catch (_: RuntimeException) {
        false
    }

private suspend fun markFailedIgnoringRuntimeException(markFailed: suspend () -> Unit) {
    try {
        markFailed()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Observer refusal still returns a typed transport failure.
    }
}

private suspend fun markFailedAfterCancellation(markFailed: suspend () -> Unit) {
    withContext(NonCancellable) {
        try {
            markFailed()
        } catch (_: RuntimeException) {
            // Preserve the original cancellation after a best-effort rollback.
        }
    }
}

/**
 * Lets a provider insert return its exact row binding even if the parent is
 * cancelled while the provider call is committing. The next suspend checkpoint
 * observes cancellation and can then roll back that exact row.
 */
internal suspend fun awaitOutgoingInsertResult(
    insert: suspend () -> ProviderAccessResult<ProviderStoredMessage>,
): ProviderAccessResult<ProviderStoredMessage> = withContext(NonCancellable) { insert() }

/** Fails closed if cancellation arrived while the exact provider row was being inserted. */
internal suspend fun ensureActiveOutgoingSmsOrRollback(markFailed: suspend () -> Unit) {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancelled: CancellationException) {
        markFailedAfterCancellation(markFailed)
        throw cancelled
    }
}
