// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingMmsDelivery
import org.aurorasms.core.telephony.IncomingMmsDownloadResult
import org.aurorasms.core.telephony.IncomingMmsRecoveryResult
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsStagedPduDisposition
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.OutgoingMmsProviderRecord
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsRecoveryResult
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.OutgoingVoiceMemoProviderRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.acceptsNewSubmissions
import org.aurorasms.core.telephony.receiver.MmsDownloadResultReceiver
import org.aurorasms.core.telephony.receiver.MmsSendResultReceiver

class AndroidMmsTransport(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val subscriptions: SubscriptionRepository,
    private val stagingStore: MmsPduStagingStore,
    private val provider: MmsProviderDataSource,
    private val sendSubmitter: ((MmsSendRequest, StagedMmsPdu, PendingIntent) -> Unit)? = null,
    private val downloadSubmitter: ((MmsDownloadRequest, StagedMmsPdu, PendingIntent) -> Unit)? = null,
    private val ownAddressResolver: ((org.aurorasms.core.model.AuroraSubscriptionId) -> ParticipantAddress?)? = null,
) {
    private val appContext = context.applicationContext
    private val voiceMemoEncoder = VoiceMemoMmsEncoder(appContext)
    private val generalMmsEncoder = GeneralMmsEncoder(appContext)
    private val submissionJournal = OutgoingMmsSubmissionJournal(appContext)
    private val submissionMutex = Mutex()
    private val incomingDownloadJournal = IncomingMmsDownloadJournal(appContext)
    private val incomingDownloadMutex = Mutex()
    private val incomingDecoder = BoundedMmsPduDecoder()

    suspend fun sendMms(request: MmsSendRequest): TransportResult =
        if (
            request.payload is OutgoingMmsPayload.VoiceMemo ||
            request.payload is OutgoingMmsPayload.Message
        ) {
            submissionMutex.withLock {
                if (!recoverOutgoingSubmissionsLocked().acceptsNewSubmissions) {
                    request.failed(
                        reason = TransportResult.FailureReason.INTERNAL_ERROR,
                        retryable = true,
                    )
                } else {
                    sendMmsLocked(request)
                }
            }
        } else {
            sendMmsLocked(request)
        }

    suspend fun recoverOutgoingSubmissions(): OutgoingMmsRecoveryResult =
        submissionMutex.withLock { recoverOutgoingSubmissionsLocked() }

    /** Durably authenticates one private PendingIntent callback before provider mutation. */
    suspend fun reconcileTransportResult(result: TransportResult): OutgoingMmsCallbackDisposition =
        submissionMutex.withLock {
            if (result.transport != MessageTransportKind.MMS) {
                return@withLock OutgoingMmsCallbackDisposition.IGNORED
            }
            val callback = when (result) {
                is TransportResult.Sent -> Triple(
                    result.providerMessageId,
                    result.providerConversationId,
                    true,
                )
                is TransportResult.Failed -> {
                    if (result.stage != TransportResult.FailureStage.SENT_CALLBACK) {
                        return@withLock OutgoingMmsCallbackDisposition.IGNORED
                    }
                    Triple(
                        result.providerMessageId,
                        result.providerConversationId,
                        false,
                    )
                }
                else -> return@withLock OutgoingMmsCallbackDisposition.IGNORED
            }
            val providerId = callback.first
                ?: return@withLock OutgoingMmsCallbackDisposition.IGNORED
            val conversationId = callback.second
                ?: return@withLock OutgoingMmsCallbackDisposition.IGNORED
            val sent = callback.third
            if (
                !submissionJournal.recordCallback(
                    operationId = result.operationId,
                    providerId = providerId,
                    conversationId = conversationId,
                    sent = sent,
                )
            ) {
                return@withLock OutgoingMmsCallbackDisposition.IGNORED
            }
            reconcileRecordedCallback(
                operationId = result.operationId,
                providerId = providerId,
                conversationId = conversationId,
                sent = sent,
            )
        }

    private suspend fun sendMmsLocked(request: MmsSendRequest): TransportResult {
        val rejection = preflight(request.subscriptionId.value)
        if (rejection != null) return request.rejected(rejection)
        val encodedVoiceMemo = when (val payload = request.payload) {
            is OutgoingMmsPayload.Encoded -> null
            is OutgoingMmsPayload.Message -> null
            is OutgoingMmsPayload.VoiceMemo -> when (
                val result = voiceMemoEncoder.encode(request.recipients, payload)
            ) {
                is VoiceMemoMmsEncodingResult.Encoded -> result
                is VoiceMemoMmsEncodingResult.Rejected -> return request.rejected(
                    when (result.reason) {
                        VoiceMemoMmsEncodingFailure.UNSUPPORTED_RECIPIENT_SET ->
                            TransportResult.FailureReason.FEATURE_UNAVAILABLE
                        VoiceMemoMmsEncodingFailure.INVALID_METADATA ->
                            TransportResult.FailureReason.PLATFORM_REJECTED
                        VoiceMemoMmsEncodingFailure.PAYLOAD_TOO_LARGE ->
                            TransportResult.FailureReason.PAYLOAD_TOO_LARGE
                        VoiceMemoMmsEncodingFailure.COMPOSITION_FAILED ->
                            TransportResult.FailureReason.INTERNAL_ERROR
                    },
                )
            }
            is OutgoingMmsPayload.RequiresEncoding -> {
                return request.rejected(TransportResult.FailureReason.CODEC_UNAVAILABLE)
            }
        }
        val encodedGeneral = when (val payload = request.payload) {
            is OutgoingMmsPayload.Message -> when (val result = generalMmsEncoder.encode(request.recipients, payload)) {
                is GeneralMmsEncodingResult.Encoded -> result
                is GeneralMmsEncodingResult.Rejected -> return request.rejected(
                    when (result.reason) {
                        GeneralMmsEncodingFailure.INVALID_METADATA ->
                            TransportResult.FailureReason.PLATFORM_REJECTED
                        GeneralMmsEncodingFailure.PAYLOAD_TOO_LARGE ->
                            TransportResult.FailureReason.PAYLOAD_TOO_LARGE
                        GeneralMmsEncodingFailure.COMPOSITION_FAILED ->
                            TransportResult.FailureReason.INTERNAL_ERROR
                    },
                )
            }
            is OutgoingMmsPayload.Encoded,
            is OutgoingMmsPayload.RequiresEncoding,
            is OutgoingMmsPayload.VoiceMemo,
            -> null
        }
        val encoded = encodedVoiceMemo?.pdu
            ?: encodedGeneral?.pdu
            ?: (request.payload as OutgoingMmsPayload.Encoded).pdu
        val stored = when {
            encodedVoiceMemo != null -> prepareVoiceMemoProviderRow(
                request,
                request.payload as OutgoingMmsPayload.VoiceMemo,
                encodedVoiceMemo,
            )
            encodedGeneral != null -> prepareGeneralMmsProviderRow(
                request,
                request.payload as OutgoingMmsPayload.Message,
                encodedGeneral,
            )
            else -> null
        }
        if ((encodedVoiceMemo != null || encodedGeneral != null) && stored == null) {
            return request.rejected(TransportResult.FailureReason.PROVIDER_UNAVAILABLE)
        }
        val staged = when (val result = stagingStore.stageSend(request.operationId, encoded)) {
            is MmsStagingResult.Ready -> result.staged
            is MmsStagingResult.Failed -> {
                stored?.let { terminalizeKnownUnsent(request, it) }
                return request.rejected(
                    if (result.reason == MmsStagingResult.Reason.PAYLOAD_TOO_LARGE) {
                        TransportResult.FailureReason.PAYLOAD_TOO_LARGE
                    } else {
                        TransportResult.FailureReason.INTERNAL_ERROR
                    },
                )
            }
        }
        val resultIntent: PendingIntent
        val manager: SmsManager?
        try {
            resultIntent = PendingIntent.getBroadcast(
                appContext,
                AndroidSmsTransport.requestCode(request.operationId.value, 0, MMS_SEND_CHANNEL),
                MmsSendResultReceiver.createIntent(
                    context = appContext,
                    operationId = request.operationId,
                    stagedUri = staged.uri,
                    providerId = stored?.providerId,
                    conversationId = stored?.conversationId,
                ),
                CALLBACK_FLAGS,
            )
            manager = if (sendSubmitter == null) smsManager(request.subscriptionId.value) else null
        } catch (_: SecurityException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            stored?.let { terminalizeKnownUnsent(request, it) }
            return request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false)
        } catch (_: UnsupportedOperationException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            stored?.let { terminalizeKnownUnsent(request, it) }
            return request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false)
        } catch (_: IllegalArgumentException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            stored?.let { terminalizeKnownUnsent(request, it) }
            return request.failed(TransportResult.FailureReason.PLATFORM_REJECTED, false)
        } catch (_: RuntimeException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            stored?.let { terminalizeKnownUnsent(request, it) }
            return request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true)
        }
        if (stored != null) {
            val armed = provider.updateOutgoingStatus(
                stored.providerId,
                stored.conversationId,
                OutgoingMmsProviderStatus.OUTBOX,
            )
            if (!armed.isApplied() ||
                !submissionJournal.markSubmitting(
                    request.operationId,
                    stored.providerId,
                    stored.conversationId,
                )
            ) {
                stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
                terminalizeKnownUnsent(request, stored)
                return request.rejected(TransportResult.FailureReason.PROVIDER_UNAVAILABLE)
            }
        }
        return try {
            val submitter = sendSubmitter
            if (submitter != null) {
                submitter(request, staged, resultIntent)
            } else if (Build.VERSION.SDK_INT >= 31) {
                checkNotNull(manager).sendMultimediaMessage(
                    appContext,
                    staged.uri,
                    null,
                    null,
                    resultIntent,
                    request.operationId.value,
                )
            } else {
                checkNotNull(manager).sendMultimediaMessage(appContext, staged.uri, null, null, resultIntent)
            }
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.MMS,
                unitCount = 1,
                providerMessageId = stored?.providerId,
                providerConversationId = stored?.conversationId,
            )
        } catch (_: RuntimeException) {
            if (stored == null) {
                stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            } else {
                submissionJournal.markSubmissionUnknown(
                    request.operationId,
                    stored.providerId,
                    stored.conversationId,
                )
            }
            request.failed(
                reason = TransportResult.FailureReason.INTERNAL_ERROR,
                retryable = false,
                providerId = stored?.providerId,
                conversationId = stored?.conversationId,
                stage = if (stored == null) {
                    TransportResult.FailureStage.SUBMISSION
                } else {
                    TransportResult.FailureStage.SUBMISSION_UNKNOWN
                },
            )
        }
    }

    private suspend fun prepareVoiceMemoProviderRow(
        request: MmsSendRequest,
        payload: OutgoingMmsPayload.VoiceMemo,
        encoded: VoiceMemoMmsEncodingResult.Encoded,
    ): ProviderStoredMessage? {
        val threadId = request.providerThreadId ?: return null
        val conversationId = threadId.let { org.aurorasms.core.model.ConversationId(it.value) }
        if (!submissionJournal.reserve(request.operationId, conversationId, encoded.transactionId)) return null
        val stored = when (
            val inserted = provider.insertOutgoingVoiceMemo(
                OutgoingVoiceMemoProviderRecord(
                    operationId = request.operationId,
                    providerThreadId = threadId,
                    recipients = request.recipients,
                    text = payload.text,
                    subject = payload.subject,
                    memo = payload.memo,
                    encodedSize = encoded.pdu.size,
                    transactionId = encoded.transactionId,
                    timestampMillis = encoded.timestampMillis,
                    subscriptionId = request.subscriptionId,
                ),
            )
        ) {
            is ProviderAccessResult.Success -> inserted.value
            else -> {
                rollbackVoiceMemoPreparation(request, conversationId, encoded.transactionId)
                return null
            }
        }
        if (!submissionJournal.markPrepared(request.operationId, stored.providerId, stored.conversationId)) {
            rollbackVoiceMemoPreparation(request, stored.conversationId, encoded.transactionId)
            return null
        }
        return stored
    }

    private suspend fun prepareGeneralMmsProviderRow(
        request: MmsSendRequest,
        payload: OutgoingMmsPayload.Message,
        encoded: GeneralMmsEncodingResult.Encoded,
    ): ProviderStoredMessage? {
        val threadId = request.providerThreadId ?: return null
        val conversationId = threadId.let { org.aurorasms.core.model.ConversationId(it.value) }
        if (!submissionJournal.reserve(request.operationId, conversationId, encoded.transactionId)) return null
        val stored = when (
            val inserted = provider.insertOutgoing(
                OutgoingMmsProviderRecord(
                    operationId = request.operationId,
                    providerThreadId = threadId,
                    recipients = request.recipients,
                    payload = payload,
                    encodedSize = encoded.pdu.size,
                    transactionId = encoded.transactionId,
                    timestampMillis = encoded.timestampMillis,
                    subscriptionId = request.subscriptionId,
                ),
            )
        ) {
            is ProviderAccessResult.Success -> inserted.value
            else -> {
                rollbackVoiceMemoPreparation(request, conversationId, encoded.transactionId)
                return null
            }
        }
        if (!submissionJournal.markPrepared(request.operationId, stored.providerId, stored.conversationId)) {
            rollbackVoiceMemoPreparation(request, stored.conversationId, encoded.transactionId)
            return null
        }
        return stored
    }

    private suspend fun rollbackVoiceMemoPreparation(
        request: MmsSendRequest,
        conversationId: org.aurorasms.core.model.ConversationId,
        transactionId: String,
    ) {
        val rollback = provider.rollbackOutgoingPreparation(
            operationId = request.operationId,
            conversationId = conversationId,
            transactionId = transactionId,
        )
        if (rollback.isAppliedOrAbsent()) {
            submissionJournal.acknowledgeKnownUnsent(request.operationId)
        }
    }

    private suspend fun terminalizeKnownUnsent(
        request: MmsSendRequest,
        stored: ProviderStoredMessage,
    ) {
        val terminal = provider.updateOutgoingStatus(
            stored.providerId,
            stored.conversationId,
            OutgoingMmsProviderStatus.FAILED,
        )
        if (terminal.isAppliedOrAbsent()) {
            submissionJournal.acknowledgeKnownUnsent(request.operationId)
        }
    }

    private suspend fun recoverOutgoingSubmissionsLocked(): OutgoingMmsRecoveryResult =
        recoverOutgoingMmsSubmissionRecords(
            recoverySnapshot = submissionJournal::recoverySnapshot,
            rollbackPreparing = { record ->
                provider.rollbackOutgoingPreparation(
                    operationId = record.operationId,
                    conversationId = record.conversationId,
                    transactionId = record.transactionId,
                )
            },
            updateStatus = { record, status ->
                record.providerId?.let { providerId ->
                    provider.updateOutgoingStatus(providerId, record.conversationId, status)
                } ?: ProviderAccessResult.InvalidInput("outgoing MMS recovery identity")
            },
            acknowledgeKnownUnsent = { record ->
                submissionJournal.acknowledgeKnownUnsent(record.operationId)
            },
            markSubmissionUnknown = { record ->
                submissionJournal.markSubmissionUnknown(
                    record.operationId,
                    checkNotNull(record.providerId),
                    record.conversationId,
                )
            },
            acknowledgeCallback = { record ->
                submissionJournal.acknowledgeCallback(
                    record.operationId,
                    checkNotNull(record.providerId),
                    record.conversationId,
                )
            },
        )

    private suspend fun reconcileRecordedCallback(
        operationId: org.aurorasms.core.model.MessageId,
        providerId: org.aurorasms.core.model.ProviderMessageId,
        conversationId: org.aurorasms.core.model.ConversationId,
        sent: Boolean,
    ): OutgoingMmsCallbackDisposition {
        val update = provider.updateOutgoingStatus(
            id = providerId,
            conversationId = conversationId,
            status = if (sent) OutgoingMmsProviderStatus.SENT else OutgoingMmsProviderStatus.FAILED,
        )
        if (!update.isAppliedOrAbsent()) return OutgoingMmsCallbackDisposition.AUTHENTICATED_DEFERRED
        if (!submissionJournal.acknowledgeCallback(operationId, providerId, conversationId)) {
            return OutgoingMmsCallbackDisposition.AUTHENTICATED_DEFERRED
        }
        return if (
            (update as ProviderAccessResult.Success).value == OutgoingMmsStatusUpdateOutcome.APPLIED
        ) {
            OutgoingMmsCallbackDisposition.APPLIED
        } else {
            OutgoingMmsCallbackDisposition.AUTHENTICATED_NO_ROW
        }
    }

    suspend fun downloadMms(request: MmsDownloadRequest): TransportResult = incomingDownloadMutex.withLock {
        downloadMmsLocked(request)
    }

    suspend fun submitIncomingNotification(message: IncomingMessage.MmsWapPush): IncomingPersistResult {
        if (!roleState.isRoleHeld()) {
            return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
        }
        val subscriptionId = message.subscriptionId
            ?: return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.MALFORMED_INPUT)
        val encoded = when (val created = EncodedMmsPdu.create(message.copyPdu())) {
            is EncodedMmsPdu.CreationResult.Valid -> created.pdu
            is EncodedMmsPdu.CreationResult.Rejected ->
                return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.MALFORMED_INPUT)
        }
        val notification = when (val decoded = incomingDecoder.decode(encoded)) {
            is BoundedMmsDecodeResult.Decoded -> decoded.pdu as? BoundedMmsPdu.Notification
            is BoundedMmsDecodeResult.Rejected -> null
        } ?: return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.MALFORMED_INPUT)
        val request = MmsDownloadRequest(
            operationId = allocateIncomingMmsOperationId(),
            contentLocation = notification.contentLocation,
            subscriptionId = subscriptionId,
            notificationTransactionId = notification.transactionId,
            expectedSizeBytes = notification.messageSizeBytes,
            receivedTimestampMillis = message.receivedTimestampMillis,
        )
        return when (val result = downloadMms(request)) {
            is TransportResult.Submitted -> IncomingPersistResult.Pending(result.operationId)
            is TransportResult.Rejected -> IncomingPersistResult.Rejected(result.reason.toIncomingRejection())
            is TransportResult.Failed -> IncomingPersistResult.Rejected(result.reason.toIncomingRejection())
            is TransportResult.Sent,
            is TransportResult.Delivered,
            is TransportResult.Downloaded,
            -> IncomingPersistResult.Rejected(IncomingPersistResult.Reason.PROVIDER_UNAVAILABLE)
        }
    }

    private suspend fun downloadMmsLocked(request: MmsDownloadRequest): TransportResult {
        val rejection = preflight(request.subscriptionId.value)
        if (rejection != null) return request.rejected(rejection)
        if (!isValidContentLocation(request.contentLocation)) {
            return request.rejected(TransportResult.FailureReason.PLATFORM_REJECTED)
        }
        when (val reservation = incomingDownloadJournal.reserve(request)) {
            is IncomingMmsDownloadJournal.ReserveResult.Reserved -> Unit
            is IncomingMmsDownloadJournal.ReserveResult.Duplicate -> return TransportResult.Submitted(
                operationId = reservation.record.operationId,
                transport = MessageTransportKind.MMS,
                unitCount = 1,
                providerMessageId = reservation.record.providerId,
                providerConversationId = reservation.record.conversationId,
            )
            IncomingMmsDownloadJournal.ReserveResult.Rejected,
            IncomingMmsDownloadJournal.ReserveResult.PersistenceFailure,
            -> return request.rejected(TransportResult.FailureReason.INTERNAL_ERROR)
        }
        val staged = when (val result = stagingStore.createDownloadTarget(request.operationId)) {
            is MmsStagingResult.Ready -> result.staged
            is MmsStagingResult.Failed -> {
                incomingDownloadJournal.abandonBeforeSubmission(request.operationId, null)
                return request.rejected(TransportResult.FailureReason.INTERNAL_ERROR)
            }
        }
        if (
            incomingDownloadJournal.markStaged(request.operationId, staged.fileName) !is
            IncomingMmsDownloadJournal.TransitionResult.Applied
        ) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, null)
            return request.rejected(TransportResult.FailureReason.INTERNAL_ERROR)
        }
        val resultIntent: PendingIntent
        try {
            resultIntent = PendingIntent.getBroadcast(
                appContext,
                AndroidSmsTransport.requestCode(request.operationId.value, 0, MMS_DOWNLOAD_CHANNEL),
                MmsDownloadResultReceiver.createIntent(appContext, request.operationId, staged.uri),
                CALLBACK_FLAGS,
            )
        } catch (_: SecurityException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, staged.fileName)
            return request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false)
        } catch (_: UnsupportedOperationException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, staged.fileName)
            return request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false)
        } catch (_: IllegalArgumentException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, staged.fileName)
            return request.failed(TransportResult.FailureReason.PLATFORM_REJECTED, false)
        } catch (_: RuntimeException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, staged.fileName)
            return request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true)
        }
        if (
            incomingDownloadJournal.markSubmitting(request.operationId, staged.fileName) !is
            IncomingMmsDownloadJournal.TransitionResult.Applied
        ) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            incomingDownloadJournal.abandonBeforeSubmission(request.operationId, staged.fileName)
            return request.rejected(TransportResult.FailureReason.INTERNAL_ERROR)
        }
        return try {
            val submitter = downloadSubmitter ?: ::submitDownloadToPlatform
            submitter(request, staged, resultIntent)
            TransportResult.Submitted(request.operationId, MessageTransportKind.MMS, unitCount = 1)
        } catch (_: RuntimeException) {
            incomingDownloadJournal.markSubmissionUnknown(request.operationId, staged.fileName)
            request.failed(
                reason = TransportResult.FailureReason.INTERNAL_ERROR,
                retryable = false,
                stage = TransportResult.FailureStage.SUBMISSION_UNKNOWN,
            )
        }
    }

    private fun submitDownloadToPlatform(
        request: MmsDownloadRequest,
        staged: StagedMmsPdu,
        resultIntent: PendingIntent,
    ) {
        val manager = smsManager(request.subscriptionId.value)
        if (Build.VERSION.SDK_INT >= 31) {
            manager.downloadMultimediaMessage(
                appContext,
                request.contentLocation,
                staged.uri,
                null,
                resultIntent,
                request.operationId.value,
            )
        } else {
            manager.downloadMultimediaMessage(
                appContext,
                request.contentLocation,
                staged.uri,
                null,
                resultIntent,
            )
        }
    }

    /** Authenticates and durably persists one exact successful private download callback. */
    suspend fun reconcileDownloadedMms(
        operationId: MessageId,
        stagedUri: Uri,
        pdu: EncodedMmsPdu,
    ): IncomingMmsDownloadResult = incomingDownloadMutex.withLock {
        reconcileDownloadedMmsLocked(operationId, stagedUri, pdu)
    }

    private suspend fun reconcileDownloadedMmsLocked(
        operationId: MessageId,
        stagedUri: Uri,
        pdu: EncodedMmsPdu,
    ): IncomingMmsDownloadResult {
        val fileName = stagedUri.lastPathSegment
            ?: return IncomingMmsDownloadResult.Ignored
        val staged = stagingStore.recoverDownloadTarget(operationId, fileName)
            ?: return IncomingMmsDownloadResult.Deferred
        if (staged.uri != stagedUri) return IncomingMmsDownloadResult.Ignored
        val callback = when (
            val transition = incomingDownloadJournal.recordSuccessCallback(operationId, fileName)
        ) {
            is IncomingMmsDownloadJournal.TransitionResult.Applied -> transition.record
            IncomingMmsDownloadJournal.TransitionResult.PersistenceFailure ->
                return IncomingMmsDownloadResult.Deferred
            IncomingMmsDownloadJournal.TransitionResult.Rejected ->
                return IncomingMmsDownloadResult.Ignored
        }
        val retrieved = when (val decoded = incomingDecoder.decode(pdu)) {
            is BoundedMmsDecodeResult.Decoded -> decoded.pdu as? BoundedMmsPdu.Retrieved
            is BoundedMmsDecodeResult.Rejected -> null
        } ?: return terminalizeMalformedDownload(callback)
        val ownAddress = try {
            (ownAddressResolver ?: ::resolveOwnMmsAddress)(callback.subscriptionId)
        } catch (_: RuntimeException) {
            null
        }
        val projected = retrieved.toIncomingProviderRecord(callback, ownAddress)
        val record = when (projected) {
            is IncomingMmsProjectionResult.Ready -> projected.record
            IncomingMmsProjectionResult.Malformed ->
                return terminalizeMalformedDownload(callback)
            IncomingMmsProjectionResult.OwnAddressUnavailable ->
                return IncomingMmsDownloadResult.Deferred
        }
        val insert = try {
            provider.insertIncoming(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return IncomingMmsDownloadResult.Deferred
        }
        val stored = when (insert) {
            is ProviderAccessResult.Success -> insert.value
            is ProviderAccessResult.InvalidInput ->
                return terminalizeMalformedDownload(callback)
            ProviderAccessResult.RoleRequired,
            ProviderAccessResult.PermissionDenied,
            is ProviderAccessResult.Unsupported,
            is ProviderAccessResult.Unavailable,
            -> return IncomingMmsDownloadResult.Deferred
        }
        val persisted = incomingDownloadJournal.markPersisted(
            operationId = operationId,
            fileName = fileName,
            providerId = stored.providerId,
            conversationId = stored.conversationId,
        )
        if (persisted !is IncomingMmsDownloadJournal.TransitionResult.Applied) {
            return IncomingMmsDownloadResult.Deferred
        }
        val body = record.text
            ?.takeIf(String::isNotBlank)
            ?: record.subject?.takeIf(String::isNotBlank)
            ?: MMS_NOTIFICATION_FALLBACK_BODY
        return IncomingMmsDownloadResult.ReadyForNotification(
            IncomingMmsDelivery(
                operationId = operationId,
                stagedFileName = fileName,
                providerId = stored.providerId,
                conversationId = stored.conversationId,
                sender = record.sender,
                participants = record.participants,
                body = body,
                receivedTimestampMillis = record.receivedTimestampMillis,
                subscriptionId = record.subscriptionId,
            ),
        )
    }

    suspend fun acknowledgeDownloadedMms(delivery: IncomingMmsDelivery): Boolean =
        incomingDownloadMutex.withLock {
            val acknowledged = incomingDownloadJournal.acknowledgePersisted(
                operationId = delivery.operationId,
                fileName = delivery.stagedFileName,
                providerId = delivery.providerId,
                conversationId = delivery.conversationId,
            )
            if (acknowledged) cleanupRecoveredDownload(delivery.operationId, delivery.stagedFileName)
            acknowledged
        }

    suspend fun reconcileFailedMmsDownload(
        operationId: MessageId,
        stagedUri: Uri,
    ): MmsStagedPduDisposition = incomingDownloadMutex.withLock {
        val fileName = stagedUri.lastPathSegment
            ?: return@withLock MmsStagedPduDisposition.RETAIN
        when (incomingDownloadJournal.recordFailureCallback(operationId, fileName)) {
            is IncomingMmsDownloadJournal.TransitionResult.Applied ->
                if (incomingDownloadJournal.acknowledgeFailure(operationId, fileName)) {
                    cleanupRecoveredDownload(operationId, fileName)
                    MmsStagedPduDisposition.CLEANUP
                } else {
                    MmsStagedPduDisposition.RETAIN
                }
            IncomingMmsDownloadJournal.TransitionResult.PersistenceFailure,
            IncomingMmsDownloadJournal.TransitionResult.Rejected,
            -> MmsStagedPduDisposition.RETAIN
        }
    }

    /** Recovers bounded completed work without ever reissuing an uncertain carrier download. */
    suspend fun recoverIncomingDownloads(): IncomingMmsRecoveryResult = incomingDownloadMutex.withLock {
        val records = when (val snapshot = incomingDownloadJournal.recoverySnapshot()) {
            is IncomingMmsDownloadJournal.RecoveryResult.Available -> snapshot.records
            IncomingMmsDownloadJournal.RecoveryResult.PersistenceFailure ->
                return@withLock IncomingMmsRecoveryResult.JournalBlocked
        }
        val pendingNotifications = mutableListOf<IncomingMmsDelivery>()
        var recoveredCount = 0
        var deferredCount = 0
        var unknownSubmissionCount = 0
        for (record in records) {
            val fileName = record.fileName
            when (record.state) {
                IncomingMmsDownloadJournal.State.RESERVED -> {
                    if (incomingDownloadJournal.abandonBeforeSubmission(record.operationId, null)) {
                        recoveredCount += 1
                    } else {
                        return@withLock IncomingMmsRecoveryResult.JournalBlocked
                    }
                }
                IncomingMmsDownloadJournal.State.STAGED -> {
                    checkNotNull(fileName)
                    cleanupRecoveredDownload(record.operationId, fileName)
                    if (incomingDownloadJournal.abandonBeforeSubmission(record.operationId, fileName)) {
                        recoveredCount += 1
                    } else {
                        return@withLock IncomingMmsRecoveryResult.JournalBlocked
                    }
                }
                IncomingMmsDownloadJournal.State.SUBMITTING -> {
                    checkNotNull(fileName)
                    if (
                        incomingDownloadJournal.markSubmissionUnknown(record.operationId, fileName) is
                        IncomingMmsDownloadJournal.TransitionResult.Applied
                    ) {
                        recoveredCount += 1
                        unknownSubmissionCount += 1
                    } else {
                        return@withLock IncomingMmsRecoveryResult.JournalBlocked
                    }
                }
                IncomingMmsDownloadJournal.State.SUBMISSION_UNKNOWN -> unknownSubmissionCount += 1
                IncomingMmsDownloadJournal.State.CALLBACK_FAILED -> {
                    checkNotNull(fileName)
                    cleanupRecoveredDownload(record.operationId, fileName)
                    if (incomingDownloadJournal.acknowledgeFailure(record.operationId, fileName)) {
                        recoveredCount += 1
                    } else {
                        return@withLock IncomingMmsRecoveryResult.JournalBlocked
                    }
                }
                IncomingMmsDownloadJournal.State.CALLBACK_SUCCEEDED,
                IncomingMmsDownloadJournal.State.PERSISTED,
                -> {
                    checkNotNull(fileName)
                    val staged = stagingStore.recoverDownloadTarget(record.operationId, fileName)
                    if (staged == null) {
                        deferredCount += 1
                        continue
                    }
                    val pdu = when (val read = stagingStore.readCompletedDownload(staged)) {
                        is EncodedMmsPdu.CreationResult.Valid -> read.pdu
                        is EncodedMmsPdu.CreationResult.Rejected -> {
                            val terminal = terminalizeMalformedDownload(record)
                            if (terminal == IncomingMmsDownloadResult.TerminalRejected) {
                                cleanupRecoveredDownload(record.operationId, fileName)
                                recoveredCount += 1
                            } else {
                                deferredCount += 1
                            }
                            continue
                        }
                    }
                    when (val reconciled = reconcileDownloadedMmsLocked(record.operationId, staged.uri, pdu)) {
                        is IncomingMmsDownloadResult.ReadyForNotification ->
                            pendingNotifications += reconciled.delivery
                        IncomingMmsDownloadResult.TerminalRejected -> {
                            cleanupRecoveredDownload(record.operationId, fileName)
                            recoveredCount += 1
                        }
                        IncomingMmsDownloadResult.Deferred,
                        IncomingMmsDownloadResult.Ignored,
                        -> deferredCount += 1
                    }
                }
            }
        }
        IncomingMmsRecoveryResult.Available(
            pendingNotifications = pendingNotifications,
            recoveredCount = recoveredCount,
            deferredCount = deferredCount,
            unknownSubmissionCount = unknownSubmissionCount,
        )
    }

    private suspend fun cleanupRecoveredDownload(operationId: MessageId, fileName: String) {
        stagingStore.recoverDownloadTarget(operationId, fileName)?.let { staged ->
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
        }
    }

    private fun terminalizeMalformedDownload(
        record: IncomingMmsDownloadJournal.Record,
    ): IncomingMmsDownloadResult = if (
        record.fileName != null &&
        record.state == IncomingMmsDownloadJournal.State.CALLBACK_SUCCEEDED &&
        incomingDownloadJournal.acknowledgeMalformed(record.operationId, record.fileName)
    ) {
        IncomingMmsDownloadResult.TerminalRejected
    } else {
        IncomingMmsDownloadResult.Deferred
    }

    // The default SMS app needs the active line only to exclude itself from a
    // received group-MMS thread. The value is used ephemerally and never stored,
    // logged, or exposed outside this projection boundary.
    @SuppressLint("HardwareIds", "MissingPermission")
    @Suppress("DEPRECATION")
    private fun resolveOwnMmsAddress(
        subscriptionId: org.aurorasms.core.model.AuroraSubscriptionId,
    ): ParticipantAddress? = runCatching {
        val manager = checkNotNull(appContext.getSystemService(TelephonyManager::class.java))
            .createForSubscriptionId(subscriptionId.value)
        manager.line1Number
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::ParticipantAddress)
    }.getOrNull()

    private suspend fun preflight(subscriptionId: Int): TransportResult.FailureReason? =
        TransportPolicy.mmsRejection(
            roleHeld = roleState.isRoleHeld(),
            featureAvailable = hasMessagingFeature(),
            permissionGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            subscriptionActive = subscriptions.activeSubscriptions().let { snapshot ->
                (snapshot as? org.aurorasms.core.telephony.SubscriptionSnapshot.Available)
                    ?.subscriptions
                    ?.any { it.id.value == subscriptionId && it.smsCapable } == true
            },
        )

    private fun hasMessagingFeature(): Boolean {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            checkNotNull(appContext.getSystemService(SmsManager::class.java)).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    // The dependency policy intentionally admits androidx.core, not core-ktx;
    // keep the platform parser rather than widening runtime dependencies.
    @SuppressLint("UseKtx")
    private fun isValidContentLocation(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    companion object {
        private const val MMS_SEND_CHANNEL = 0x4D51
        private const val MMS_DOWNLOAD_CHANNEL = 0x4D71
        private const val MMS_NOTIFICATION_FALLBACK_BODY = "Multimedia message"
        private const val CALLBACK_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    }
}

enum class OutgoingMmsCallbackDisposition {
    IGNORED,
    AUTHENTICATED_DEFERRED,
    AUTHENTICATED_NO_ROW,
    APPLIED,
    ;

    val authenticated: Boolean
        get() = this != IGNORED
}

/** Pure coordinator so every crash-ordering state is exhaustively host-tested. */
internal suspend fun recoverOutgoingMmsSubmissionRecords(
    recoverySnapshot: () -> OutgoingMmsSubmissionJournal.RecoveryResult,
    rollbackPreparing: suspend (
        OutgoingMmsSubmissionJournal.Record,
    ) -> ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>,
    updateStatus: suspend (
        OutgoingMmsSubmissionJournal.Record,
        OutgoingMmsProviderStatus,
    ) -> ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>,
    acknowledgeKnownUnsent: (OutgoingMmsSubmissionJournal.Record) -> Boolean,
    markSubmissionUnknown: (OutgoingMmsSubmissionJournal.Record) -> Boolean,
    acknowledgeCallback: (OutgoingMmsSubmissionJournal.Record) -> Boolean,
): OutgoingMmsRecoveryResult {
    val snapshot = try {
        recoverySnapshot()
    } catch (_: RuntimeException) {
        return OutgoingMmsRecoveryResult.JournalBlocked
    }
    val records = when (snapshot) {
        is OutgoingMmsSubmissionJournal.RecoveryResult.Available -> snapshot.records
        OutgoingMmsSubmissionJournal.RecoveryResult.PersistenceFailure -> {
            return OutgoingMmsRecoveryResult.JournalBlocked
        }
    }
    var recoveredCount = 0
    var deferredCount = 0
    var unknownCount = 0
    for (record in records) {
        when (record.state) {
            OutgoingMmsSubmissionJournal.State.PREPARING -> {
                val rollback = providerRecoveryCall { rollbackPreparing(record) }
                when {
                    rollback.isAppliedOrAbsent() -> {
                        if (!journalRecoveryCall { acknowledgeKnownUnsent(record) }) {
                            return OutgoingMmsRecoveryResult.JournalBlocked
                        }
                        recoveredCount += 1
                    }
                    rollback.isOwnershipConflict() -> deferredCount += 1
                    else -> deferredCount += 1
                }
            }
            OutgoingMmsSubmissionJournal.State.PREPARED -> {
                val update = providerRecoveryCall {
                    updateStatus(record, OutgoingMmsProviderStatus.FAILED)
                }
                when {
                    update.isAppliedOrAbsent() -> {
                        if (!journalRecoveryCall { acknowledgeKnownUnsent(record) }) {
                            return OutgoingMmsRecoveryResult.JournalBlocked
                        }
                        recoveredCount += 1
                    }
                    update.isOwnershipConflict() -> deferredCount += 1
                    else -> deferredCount += 1
                }
            }
            OutgoingMmsSubmissionJournal.State.SUBMITTING -> {
                if (!journalRecoveryCall { markSubmissionUnknown(record) }) {
                    return OutgoingMmsRecoveryResult.JournalBlocked
                }
                recoveredCount += 1
                unknownCount += 1
            }
            OutgoingMmsSubmissionJournal.State.SUBMISSION_UNKNOWN -> unknownCount += 1
            OutgoingMmsSubmissionJournal.State.CALLBACK_SENT,
            OutgoingMmsSubmissionJournal.State.CALLBACK_FAILED,
            -> {
                val target = if (record.state == OutgoingMmsSubmissionJournal.State.CALLBACK_SENT) {
                    OutgoingMmsProviderStatus.SENT
                } else {
                    OutgoingMmsProviderStatus.FAILED
                }
                val update = providerRecoveryCall { updateStatus(record, target) }
                when {
                    update.isAppliedOrAbsent() -> {
                        if (!journalRecoveryCall { acknowledgeCallback(record) }) {
                            return OutgoingMmsRecoveryResult.JournalBlocked
                        }
                        recoveredCount += 1
                    }
                    update.isOwnershipConflict() -> deferredCount += 1
                    else -> deferredCount += 1
                }
            }
        }
    }
    return OutgoingMmsRecoveryResult.Available(
        recoveredCount = recoveredCount,
        deferredCount = deferredCount,
        unknownSubmissionCount = unknownCount,
    )
}

private suspend fun providerRecoveryCall(
    block: suspend () -> ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>,
): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    ProviderAccessResult.Unavailable("recover outgoing MMS")
}

private fun journalRecoveryCall(block: () -> Boolean): Boolean =
    try {
        block()
    } catch (_: RuntimeException) {
        false
    }

private fun ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>.isAppliedOrAbsent(): Boolean =
    this is ProviderAccessResult.Success &&
        (value == OutgoingMmsStatusUpdateOutcome.APPLIED ||
            value == OutgoingMmsStatusUpdateOutcome.ROW_ABSENT)

internal fun ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>.isApplied(): Boolean =
    this is ProviderAccessResult.Success && value == OutgoingMmsStatusUpdateOutcome.APPLIED

private fun ProviderAccessResult<OutgoingMmsStatusUpdateOutcome>.isOwnershipConflict(): Boolean =
    this is ProviderAccessResult.Success && value == OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT

private fun MmsSendRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(operationId, MessageTransportKind.MMS, reason)

private fun MmsSendRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
    providerId: org.aurorasms.core.model.ProviderMessageId? = null,
    conversationId: org.aurorasms.core.model.ConversationId? = null,
    stage: TransportResult.FailureStage = TransportResult.FailureStage.SUBMISSION,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.MMS,
    reason = reason,
    retryable = retryable,
    providerMessageId = providerId,
    providerConversationId = conversationId,
    stage = stage,
)

private fun MmsDownloadRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(operationId, MessageTransportKind.MMS, reason)

private fun MmsDownloadRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
    stage: TransportResult.FailureStage = TransportResult.FailureStage.SUBMISSION,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.MMS,
    reason = reason,
    retryable = retryable,
    stage = stage,
)

private fun TransportResult.FailureReason.toIncomingRejection(): IncomingPersistResult.Reason = when (this) {
    TransportResult.FailureReason.ROLE_NOT_HELD -> IncomingPersistResult.Reason.ROLE_NOT_HELD
    TransportResult.FailureReason.PERMISSION_DENIED -> IncomingPersistResult.Reason.PERMISSION_DENIED
    TransportResult.FailureReason.PAYLOAD_TOO_LARGE,
    TransportResult.FailureReason.INVALID_RECIPIENT,
    TransportResult.FailureReason.EMPTY_CONTENT,
    TransportResult.FailureReason.PLATFORM_REJECTED,
    -> IncomingPersistResult.Reason.MALFORMED_INPUT
    TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE,
    TransportResult.FailureReason.FEATURE_UNAVAILABLE,
    TransportResult.FailureReason.CODEC_UNAVAILABLE,
    TransportResult.FailureReason.PROVIDER_UNAVAILABLE,
    TransportResult.FailureReason.CANCELLED,
    TransportResult.FailureReason.INTERNAL_ERROR,
    -> IncomingPersistResult.Reason.PROVIDER_UNAVAILABLE
}
