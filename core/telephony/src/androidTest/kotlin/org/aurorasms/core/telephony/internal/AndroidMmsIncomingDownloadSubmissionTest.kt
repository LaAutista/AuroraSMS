// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.IncomingMmsDownloadResult
import org.aurorasms.core.telephony.IncomingMmsRecoveryResult
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.codec.aosp.pdu.CharacterSets
import org.aurorasms.core.telephony.codec.aosp.pdu.EncodedStringValue
import org.aurorasms.core.telephony.codec.aosp.pdu.PduBody
import org.aurorasms.core.telephony.codec.aosp.pdu.PduComposer
import org.aurorasms.core.telephony.codec.aosp.pdu.PduHeaders
import org.aurorasms.core.telephony.codec.aosp.pdu.PduPart
import org.aurorasms.core.telephony.codec.aosp.pdu.SendReq
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMmsIncomingDownloadSubmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val submitted = mutableListOf<StagedMmsPdu>()

    @get:Rule
    val sendPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.SEND_SMS)

    @Before
    @After
    fun clearProductionJournalAndFiles() {
        if (incomingMmsColdRestartGateEnabled()) return
        runBlocking {
            submitted.forEach { staged ->
                MmsPduStagingStore(context).cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            }
        }
        submitted.clear()
        context.getSharedPreferences(JOURNAL_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun completedIncomingMmsSurvivesHostForceStopUntilFreshProcessNotificationAcknowledgement() =
        runBlocking {
            when (requireIncomingMmsColdRestartPhase()) {
                COLD_RESTART_PHASE_PREPARE -> prepareIncomingMmsColdRestart()
                COLD_RESTART_PHASE_VERIFY -> verifyIncomingMmsColdRestart()
                COLD_RESTART_PHASE_CLEANUP -> cleanupIncomingMmsColdRestart()
                else -> throw AssertionError(COLD_RESTART_PHASE_INVALID)
            }
        }

    @Test
    fun journalIsSubmittingBeforePlatformBoundaryAndDuplicateDoesNotSubmitAgain() = runBlocking {
        val observedStates = mutableListOf<IncomingMmsDownloadJournal.State>()
        val transport = transport { _, staged, _ ->
            submitted += staged
            observedStates += journalRecords().single().state
        }

        val first = transport.downloadMms(request(OPERATION))
        val duplicate = transport.downloadMms(request(OTHER_OPERATION))

        assertTrue(first is TransportResult.Submitted)
        assertEquals(OPERATION, first.operationId)
        assertTrue(duplicate is TransportResult.Submitted)
        assertEquals(OPERATION, duplicate.operationId)
        assertEquals(listOf(IncomingMmsDownloadJournal.State.SUBMITTING), observedStates)
        assertEquals(listOf(IncomingMmsDownloadJournal.State.SUBMITTING), journalRecords().map { it.state })
        assertEquals(1, submitted.size)
        assertEquals(
            submitted.single(),
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, submitted.single().fileName),
        )
    }

    @Test
    fun exceptionAfterSubmittingCheckpointRetainsExactFileAsSubmissionUnknown() = runBlocking {
        val transport = transport { _, staged, _ ->
            submitted += staged
            assertEquals(IncomingMmsDownloadJournal.State.SUBMITTING, journalRecords().single().state)
            throw IllegalStateException("synthetic platform uncertainty")
        }

        val result = transport.downloadMms(request(OPERATION))

        assertTrue(result is TransportResult.Failed)
        result as TransportResult.Failed
        assertEquals(TransportResult.FailureStage.SUBMISSION_UNKNOWN, result.stage)
        assertEquals(false, result.retryable)
        val record = journalRecords().single()
        assertEquals(IncomingMmsDownloadJournal.State.SUBMISSION_UNKNOWN, record.state)
        assertEquals(submitted.single().fileName, record.fileName)
        assertEquals(
            submitted.single(),
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, submitted.single().fileName),
        )
    }

    @Test
    fun successfulGroupCallbackPersistsProviderThenAwaitsExactNotificationAcknowledgement() = runBlocking {
        val provider = CapturingProvider()
        val encoded = encoded(retrieveConfPdu())
        val transport = transport(
            provider = provider,
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, staged, _ ->
            submitted += staged
            context.contentResolver.openOutputStream(staged.uri, "w")!!.use { output ->
                output.write(encoded.copyBytes())
            }
        }

        assertTrue(transport.downloadMms(request(OPERATION)) is TransportResult.Submitted)
        val result = transport.reconcileDownloadedMms(OPERATION, submitted.single().uri, encoded)
            as IncomingMmsDownloadResult.ReadyForNotification

        assertEquals(PROVIDER, result.delivery.providerId)
        assertEquals(CONVERSATION, result.delivery.conversationId)
        assertEquals(SENDER, result.delivery.sender)
        assertEquals(listOf(SENDER, GROUP_MEMBER), result.delivery.participants)
        assertEquals(TEXT, result.delivery.body)
        assertEquals(listOf(SENDER, GROUP_MEMBER), provider.records.single().participants)
        assertEquals(listOf(LOCAL_RECIPIENT, GROUP_MEMBER), provider.records.single().to)
        assertEquals(IncomingMmsDownloadJournal.State.PERSISTED, journalRecords().single().state)
        assertTrue(transport.acknowledgeDownloadedMms(result.delivery))
        assertTrue(journalRecords().isEmpty())
    }

    @Test
    fun providerRuntimeFailureDefersAndRetainsAuthenticatedCallbackForRecovery() = runBlocking {
        val encoded = encoded(retrieveConfPdu())
        val transport = transport(
            provider = CapturingProvider(failInsert = true),
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, staged, _ ->
            submitted += staged
            context.contentResolver.openOutputStream(staged.uri, "w")!!.use { output ->
                output.write(encoded.copyBytes())
            }
        }
        assertTrue(transport.downloadMms(request(OPERATION)) is TransportResult.Submitted)

        val result = transport.reconcileDownloadedMms(OPERATION, submitted.single().uri, encoded)

        assertEquals(IncomingMmsDownloadResult.Deferred, result)
        assertEquals(IncomingMmsDownloadJournal.State.CALLBACK_SUCCEEDED, journalRecords().single().state)
        assertEquals(
            submitted.single(),
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, submitted.single().fileName),
        )
    }

    @Test
    fun startupRecoveryReplaysPersistedNotificationWithoutResubmittingCarrierDownload() = runBlocking {
        val provider = CapturingProvider()
        val encoded = encoded(retrieveConfPdu())
        val firstTransport = transport(
            provider = provider,
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, staged, _ ->
            submitted += staged
            context.contentResolver.openOutputStream(staged.uri, "w")!!.use { output ->
                output.write(encoded.copyBytes())
            }
        }
        assertTrue(firstTransport.downloadMms(request(OPERATION)) is TransportResult.Submitted)
        val original = firstTransport.reconcileDownloadedMms(OPERATION, submitted.single().uri, encoded)
            as IncomingMmsDownloadResult.ReadyForNotification

        var restartedPlatformSubmissions = 0
        val restartedTransport = transport(
            provider = provider,
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, _, _ -> restartedPlatformSubmissions += 1 }
        val recovery = restartedTransport.recoverIncomingDownloads() as IncomingMmsRecoveryResult.Available

        assertEquals(0, restartedPlatformSubmissions)
        assertEquals(0, recovery.recoveredCount)
        assertEquals(0, recovery.deferredCount)
        assertEquals(0, recovery.unknownSubmissionCount)
        assertEquals(listOf(original.delivery), recovery.pendingNotifications)
        assertEquals(2, provider.records.size)
        assertTrue(restartedTransport.acknowledgeDownloadedMms(recovery.pendingNotifications.single()))
        assertTrue(journalRecords().isEmpty())
        assertEquals(
            null,
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, submitted.single().fileName),
        )
    }

    @Test
    fun startupRecoveryMarksSubmittingUnknownWithoutResubmittingCarrierDownload() = runBlocking {
        val firstTransport = transport { _, staged, _ -> submitted += staged }
        assertTrue(firstTransport.downloadMms(request(OPERATION)) is TransportResult.Submitted)

        var restartedPlatformSubmissions = 0
        val restartedTransport = transport { _, _, _ -> restartedPlatformSubmissions += 1 }
        val firstRecovery = restartedTransport.recoverIncomingDownloads() as IncomingMmsRecoveryResult.Available
        val secondRecovery = restartedTransport.recoverIncomingDownloads() as IncomingMmsRecoveryResult.Available

        assertEquals(0, restartedPlatformSubmissions)
        assertEquals(1, firstRecovery.recoveredCount)
        assertEquals(1, firstRecovery.unknownSubmissionCount)
        assertEquals(0, secondRecovery.recoveredCount)
        assertEquals(1, secondRecovery.unknownSubmissionCount)
        assertEquals(IncomingMmsDownloadJournal.State.SUBMISSION_UNKNOWN, journalRecords().single().state)
    }

    @Test
    fun startupRecoveryRemovesStagedTargetThatNeverCrossedPlatformBoundary() = runBlocking {
        val journal = IncomingMmsDownloadJournal(context)
        assertTrue(journal.reserve(request(OPERATION)) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        val staged = (
            MmsPduStagingStore(context).createDownloadTarget(OPERATION) as MmsStagingResult.Ready
            ).staged
        submitted += staged
        assertTrue(
            journal.markStaged(OPERATION, staged.fileName) is
                IncomingMmsDownloadJournal.TransitionResult.Applied,
        )

        var restartedPlatformSubmissions = 0
        val recovery = transport { _, _, _ -> restartedPlatformSubmissions += 1 }
            .recoverIncomingDownloads() as IncomingMmsRecoveryResult.Available

        assertEquals(0, restartedPlatformSubmissions)
        assertEquals(1, recovery.recoveredCount)
        assertEquals(0, recovery.unknownSubmissionCount)
        assertTrue(journalRecords().isEmpty())
        assertEquals(null, MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, staged.fileName))
    }

    @Test
    fun wapNotificationSubmissionPreservesEnvelopeAndDeduplicatesBeforePlatform() = runBlocking {
        val submittedRequests = mutableListOf<MmsDownloadRequest>()
        val transport = transport { request, staged, _ ->
            submittedRequests += request
            submitted += staged
        }
        val notification = IncomingMessage.MmsWapPush(
            pdu = notificationPdu(),
            mimeType = IncomingMessage.MmsWapPush.MMS_MIME_TYPE,
            receivedTimestampMillis = RECEIVED_TIMESTAMP_MILLIS,
            subscriptionId = SUBSCRIPTION,
        )

        val first = transport.submitIncomingNotification(notification) as IncomingPersistResult.Pending
        val duplicate = transport.submitIncomingNotification(notification) as IncomingPersistResult.Pending

        assertEquals(first.operationId, duplicate.operationId)
        assertEquals(1, submittedRequests.size)
        val request = submittedRequests.single()
        assertEquals(first.operationId, request.operationId)
        assertEquals(CONTENT_LOCATION, request.contentLocation)
        assertEquals(TRANSACTION_ID, request.notificationTransactionId)
        assertEquals(4_096L, request.expectedSizeBytes)
        assertEquals(RECEIVED_TIMESTAMP_MILLIS, request.receivedTimestampMillis)
        assertEquals(SUBSCRIPTION, request.subscriptionId)
    }

    private fun incomingMmsColdRestartGateEnabled(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString(COLD_RESTART_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true

    private fun requireIncomingMmsColdRestartPhase(): String {
        assumeTrue(COLD_RESTART_GATE_REQUIRED, incomingMmsColdRestartGateEnabled())
        assumeTrue(
            COLD_RESTART_EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
        assumeTrue(
            COLD_RESTART_API_REQUIRED,
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA,
        )
        return InstrumentationRegistry.getArguments()
            .getString(COLD_RESTART_PHASE_ARGUMENT)
            .orEmpty()
            .also { phase ->
                coldRestartRequire(
                    phase == COLD_RESTART_PHASE_PREPARE ||
                        phase == COLD_RESTART_PHASE_VERIFY ||
                        phase == COLD_RESTART_PHASE_CLEANUP,
                    COLD_RESTART_PHASE_INVALID,
                )
            }
    }

    private suspend fun prepareIncomingMmsColdRestart() {
        val checkpoint = incomingMmsColdRestartCheckpoint()
        coldRestartRequire(checkpoint.all.isEmpty(), COLD_RESTART_STALE_CHECKPOINT)
        coldRestartRequire(journalRecords().isEmpty(), COLD_RESTART_STALE_JOURNAL)
        val preparedPid = Process.myPid()
        val preparedStartUptimeMillis = Process.getStartUptimeMillis()
        coldRestartRequire(
            checkpoint.edit()
                .putInt(COLD_RESTART_KEY_VERSION, COLD_RESTART_VERSION)
                .putString(COLD_RESTART_KEY_STATE, COLD_RESTART_STATE_PREPARING)
                .putInt(COLD_RESTART_KEY_PREPARED_PID, preparedPid)
                .putLong(COLD_RESTART_KEY_PREPARED_START, preparedStartUptimeMillis)
                .commit(),
            COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )

        val provider = CapturingProvider()
        val encoded = encoded(retrieveConfPdu())
        val stagedTargets = mutableListOf<StagedMmsPdu>()
        val transport = transport(
            provider = provider,
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, staged, _ ->
            stagedTargets += staged
            context.contentResolver.openOutputStream(staged.uri, "w")!!.use { output ->
                output.write(encoded.copyBytes())
            }
        }

        coldRestartRequire(
            transport.downloadMms(request(OPERATION)) is TransportResult.Submitted,
            COLD_RESTART_PREPARATION_FAILED,
        )
        val staged = stagedTargets.singleOrNull()
            ?: throw AssertionError(COLD_RESTART_PREPARATION_FAILED)
        val ready = transport.reconcileDownloadedMms(OPERATION, staged.uri, encoded)
            as? IncomingMmsDownloadResult.ReadyForNotification
            ?: throw AssertionError(COLD_RESTART_PREPARATION_FAILED)
        coldRestartRequire(
            ready.delivery == expectedIncomingMmsDelivery(staged.fileName) &&
                provider.records.size == 1,
            COLD_RESTART_DELIVERY_MISMATCH,
        )
        coldRestartRequire(
            journalRecords().singleOrNull()?.let { record ->
                record.operationId == OPERATION &&
                    record.fileName == staged.fileName &&
                    record.providerId == PROVIDER &&
                    record.conversationId == CONVERSATION &&
                    record.state == IncomingMmsDownloadJournal.State.PERSISTED
            } == true,
            COLD_RESTART_JOURNAL_MISMATCH,
        )
        coldRestartRequire(
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, staged.fileName) == staged,
            COLD_RESTART_STAGING_MISMATCH,
        )
        coldRestartRequire(
            checkpoint.edit()
                .clear()
                .putInt(COLD_RESTART_KEY_VERSION, COLD_RESTART_VERSION)
                .putString(COLD_RESTART_KEY_STATE, COLD_RESTART_STATE_PREPARED)
                .putString(COLD_RESTART_KEY_FILE_NAME, staged.fileName)
                .putInt(COLD_RESTART_KEY_PREPARED_PID, preparedPid)
                .putLong(COLD_RESTART_KEY_PREPARED_START, preparedStartUptimeMillis)
                .commit(),
            COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
    }

    private suspend fun verifyIncomingMmsColdRestart() {
        val checkpoint = incomingMmsColdRestartCheckpoint()
        val evidence = readIncomingMmsColdRestartEvidence(
            checkpoint = checkpoint,
            expectedState = COLD_RESTART_STATE_PREPARED,
        )
        val currentPid = Process.myPid()
        val currentStartUptimeMillis = Process.getStartUptimeMillis()
        coldRestartRequire(
            currentPid != evidence.preparedPid &&
                currentStartUptimeMillis > evidence.preparedStartUptimeMillis,
            COLD_RESTART_PROCESS_NOT_RESTARTED,
        )

        var platformSubmissions = 0
        val provider = CapturingProvider()
        val transport = transport(
            provider = provider,
            ownAddress = { LOCAL_RECIPIENT },
        ) { _, _, _ -> platformSubmissions += 1 }
        val recovery = transport.recoverIncomingDownloads() as? IncomingMmsRecoveryResult.Available
            ?: throw AssertionError(COLD_RESTART_RECOVERY_FAILED)

        coldRestartRequire(platformSubmissions == 0, COLD_RESTART_PLATFORM_RESUBMITTED)
        coldRestartRequire(
            recovery.recoveredCount == 0 &&
                recovery.deferredCount == 0 &&
                recovery.unknownSubmissionCount == 0 &&
                recovery.pendingNotifications ==
                listOf(expectedIncomingMmsDelivery(evidence.fileName)) &&
                provider.records.size == 1,
            COLD_RESTART_DELIVERY_MISMATCH,
        )
        coldRestartRequire(
            journalRecords().singleOrNull()?.state == IncomingMmsDownloadJournal.State.PERSISTED,
            COLD_RESTART_JOURNAL_MISMATCH,
        )
        coldRestartRequire(
            MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, evidence.fileName) != null,
            COLD_RESTART_STAGING_MISMATCH,
        )
        coldRestartRequire(
            checkpoint.edit()
                .putString(COLD_RESTART_KEY_STATE, COLD_RESTART_STATE_VERIFIED)
                .putInt(COLD_RESTART_KEY_VERIFIED_PID, currentPid)
                .putLong(COLD_RESTART_KEY_VERIFIED_START, currentStartUptimeMillis)
                .commit(),
            COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
    }

    private suspend fun cleanupIncomingMmsColdRestart() {
        val checkpoint = incomingMmsColdRestartCheckpoint()
        val state = checkpoint.getString(COLD_RESTART_KEY_STATE, null)
        if (state == COLD_RESTART_STATE_VERIFIED) {
            val evidence = readIncomingMmsColdRestartEvidence(
                checkpoint = checkpoint,
                expectedState = COLD_RESTART_STATE_VERIFIED,
            )
            coldRestartRequire(
                evidence.verifiedPid != null &&
                    Process.myPid() != evidence.verifiedPid &&
                    Process.getStartUptimeMillis() > checkNotNull(evidence.verifiedStartUptimeMillis),
                COLD_RESTART_CLEANUP_PROCESS_NOT_RESTARTED,
            )
            var platformSubmissions = 0
            val transport = transport(
                provider = CapturingProvider(),
                ownAddress = { LOCAL_RECIPIENT },
            ) { _, _, _ -> platformSubmissions += 1 }
            val recovery = transport.recoverIncomingDownloads()
                as? IncomingMmsRecoveryResult.Available
                ?: throw AssertionError(COLD_RESTART_RECOVERY_FAILED)
            val delivery = recovery.pendingNotifications.singleOrNull()
                ?: throw AssertionError(COLD_RESTART_DELIVERY_MISMATCH)
            coldRestartRequire(
                delivery == expectedIncomingMmsDelivery(evidence.fileName) &&
                    platformSubmissions == 0,
                COLD_RESTART_DELIVERY_MISMATCH,
            )
            coldRestartRequire(
                transport.acknowledgeDownloadedMms(delivery),
                COLD_RESTART_ACKNOWLEDGEMENT_FAILED,
            )
            coldRestartRequire(journalRecords().isEmpty(), COLD_RESTART_JOURNAL_NOT_CLEARED)
            coldRestartRequire(
                MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, evidence.fileName) == null,
                COLD_RESTART_STAGING_NOT_CLEARED,
            )
        } else {
            cleanupInterruptedIncomingMmsColdRestart()
        }
        coldRestartRequire(
            checkpoint.edit().clear().commit(),
            COLD_RESTART_CHECKPOINT_CLEAR_FAILED,
        )
    }

    private suspend fun cleanupInterruptedIncomingMmsColdRestart() {
        val snapshot = IncomingMmsDownloadJournal(context).recoverySnapshot()
            as? IncomingMmsDownloadJournal.RecoveryResult.Available
            ?: throw AssertionError(COLD_RESTART_CLEANUP_FAILED)
        coldRestartRequire(
            snapshot.records.all { it.operationId == OPERATION },
            COLD_RESTART_CLEANUP_FAILED,
        )
        snapshot.records.forEach { record ->
            record.fileName?.let { fileName ->
                MmsPduStagingStore(context).recoverDownloadTarget(OPERATION, fileName)?.let { staged ->
                    coldRestartRequire(
                        MmsPduStagingStore(context).cleanup(
                            staged.uri,
                            MmsPduDirection.DOWNLOAD_TARGET,
                        ),
                        COLD_RESTART_STAGING_NOT_CLEARED,
                    )
                }
            }
        }
        coldRestartRequire(
            context.getSharedPreferences(JOURNAL_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
            COLD_RESTART_JOURNAL_NOT_CLEARED,
        )
    }

    private fun expectedIncomingMmsDelivery(fileName: String) =
        org.aurorasms.core.telephony.IncomingMmsDelivery(
            operationId = OPERATION,
            stagedFileName = fileName,
            providerId = PROVIDER,
            conversationId = CONVERSATION,
            sender = SENDER,
            participants = listOf(SENDER, GROUP_MEMBER),
            body = TEXT,
            receivedTimestampMillis = FIXED_DATE_SECONDS * 1_000L,
            subscriptionId = SUBSCRIPTION,
        )

    private fun incomingMmsColdRestartCheckpoint() = context.getSharedPreferences(
        COLD_RESTART_CHECKPOINT_NAME,
        Context.MODE_PRIVATE,
    )

    private fun readIncomingMmsColdRestartEvidence(
        checkpoint: android.content.SharedPreferences,
        expectedState: String,
    ): IncomingMmsColdRestartEvidence {
        val evidence = IncomingMmsColdRestartEvidence(
            fileName = checkpoint.getString(COLD_RESTART_KEY_FILE_NAME, null).orEmpty(),
            preparedPid = checkpoint.getInt(COLD_RESTART_KEY_PREPARED_PID, -1),
            preparedStartUptimeMillis = checkpoint.getLong(COLD_RESTART_KEY_PREPARED_START, -1L),
            verifiedPid = checkpoint.getInt(COLD_RESTART_KEY_VERIFIED_PID, -1).takeIf { it > 0 },
            verifiedStartUptimeMillis = checkpoint.getLong(COLD_RESTART_KEY_VERIFIED_START, -1L)
                .takeIf { it >= 0L },
        )
        coldRestartRequire(
            checkpoint.getInt(COLD_RESTART_KEY_VERSION, 0) == COLD_RESTART_VERSION &&
                checkpoint.getString(COLD_RESTART_KEY_STATE, null) == expectedState &&
                evidence.fileName.isNotEmpty() &&
                evidence.preparedPid > 0 &&
                evidence.preparedStartUptimeMillis >= 0L &&
                (expectedState == COLD_RESTART_STATE_VERIFIED) ==
                (evidence.verifiedPid != null && evidence.verifiedStartUptimeMillis != null),
            COLD_RESTART_CHECKPOINT_INVALID,
        )
        return evidence
    }

    private fun coldRestartRequire(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun transport(
        provider: MmsProviderDataSource = UnusedProvider,
        ownAddress: ((AuroraSubscriptionId) -> ParticipantAddress?)? = null,
        submitter: (MmsDownloadRequest, StagedMmsPdu, PendingIntent) -> Unit,
    ): AndroidMmsTransport = AndroidMmsTransport(
        context = context,
        roleState = HeldRole,
        subscriptions = ActiveSubscriptions,
        stagingStore = MmsPduStagingStore(context),
        provider = provider,
        downloadSubmitter = submitter,
        ownAddressResolver = ownAddress,
    )

    private fun retrieveConfPdu(): ByteArray {
        val body = PduBody().apply {
            addPart(
                PduPart().apply {
                    setContentType("text/plain".ascii())
                    setContentLocation("text.txt".ascii())
                    setName("text.txt".ascii())
                    setFilename("text.txt".ascii())
                    setContentId("text".ascii())
                    setCharset(CharacterSets.UTF_8)
                    setData(TEXT.toByteArray(StandardCharsets.UTF_8))
                },
            )
        }
        val request = SendReq(
            "application/vnd.wap.multipart.related".ascii(),
            EncodedStringValue(CharacterSets.UTF_8, SENDER.value.utf8()),
            PduHeaders.CURRENT_MMS_VERSION,
            TRANSACTION_ID.ascii(),
        ).apply {
            setTo(
                arrayOf(
                    EncodedStringValue(CharacterSets.UTF_8, LOCAL_RECIPIENT.value.utf8()),
                    EncodedStringValue(CharacterSets.UTF_8, GROUP_MEMBER.value.utf8()),
                ),
            )
            setDate(FIXED_DATE_SECONDS)
            setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.ascii())
            setBody(body)
        }
        val bytes = requireNotNull(PduComposer(context, request).make())
        check(bytes[1].toInt() and 0xff == PduHeaders.MESSAGE_TYPE_SEND_REQ)
        bytes[1] = PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF.toByte()
        return bytes
    }

    private fun notificationPdu(): ByteArray = ByteArrayOutputStream().apply {
        header(PduHeaders.MESSAGE_TYPE)
        octet(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
        header(PduHeaders.TRANSACTION_ID)
        text(TRANSACTION_ID)
        header(PduHeaders.MMS_VERSION)
        octet(PduHeaders.CURRENT_MMS_VERSION or 0x80)
        header(PduHeaders.FROM)
        val from = ByteArrayOutputStream().apply {
            octet(PduHeaders.FROM_ADDRESS_PRESENT_TOKEN)
            text(SENDER.value)
        }.toByteArray()
        valueLength(from.size)
        write(from)
        header(PduHeaders.MESSAGE_CLASS)
        octet(PduHeaders.MESSAGE_CLASS_PERSONAL)
        header(PduHeaders.MESSAGE_SIZE)
        longInteger(4_096L)
        header(PduHeaders.EXPIRY)
        val expiry = ByteArrayOutputStream().apply {
            octet(PduHeaders.VALUE_RELATIVE_TOKEN)
            longInteger(7L * 24L * 60L * 60L)
        }.toByteArray()
        valueLength(expiry.size)
        write(expiry)
        header(PduHeaders.CONTENT_LOCATION)
        text(CONTENT_LOCATION)
    }.toByteArray()

    private fun ByteArrayOutputStream.header(value: Int) = octet(value)

    private fun ByteArrayOutputStream.octet(value: Int) {
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.text(value: String) {
        write(value.ascii())
        write(0)
    }

    private fun ByteArrayOutputStream.valueLength(value: Int) {
        require(value in 0..30)
        write(value)
    }

    private fun ByteArrayOutputStream.longInteger(value: Long) {
        require(value >= 0L)
        var remaining = value
        val reversed = ByteArray(8)
        var count = 0
        do {
            reversed[count++] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        } while (remaining != 0L)
        write(count)
        for (index in count - 1 downTo 0) write(reversed[index].toInt() and 0xff)
    }

    private fun encoded(bytes: ByteArray): EncodedMmsPdu =
        (EncodedMmsPdu.create(bytes) as EncodedMmsPdu.CreationResult.Valid).pdu

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun request(operationId: MessageId): MmsDownloadRequest = MmsDownloadRequest(
        operationId = operationId,
        contentLocation = CONTENT_LOCATION,
        subscriptionId = SUBSCRIPTION,
        notificationTransactionId = TRANSACTION_ID,
        expectedSizeBytes = 4_096L,
        receivedTimestampMillis = 1_720_000_000_000L,
    )

    private fun journalRecords(): List<IncomingMmsDownloadJournal.Record> =
        (
            IncomingMmsDownloadJournal(context).recoverySnapshot() as
                IncomingMmsDownloadJournal.RecoveryResult.Available
            ).records

    private object HeldRole : DefaultSmsRoleState {
        override fun isRoleAvailable(): Boolean = true
        override fun isRoleHeld(): Boolean = true
    }

    private object ActiveSubscriptions : SubscriptionRepository {
        override suspend fun activeSubscriptions(): SubscriptionSnapshot =
            SubscriptionSnapshot.Available(
                listOf(ActiveSubscription(SUBSCRIPTION, 0, "Synthetic", true)),
            )
    }

    private object UnusedProvider : MmsProviderDataSource {
        override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

        override suspend fun readPage(
            request: ProviderPageRequest,
        ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> =
            ProviderAccessResult.Success(ProviderPage(emptyList(), null, true))

        override suspend fun insertIncoming(
            message: DecodedIncomingMmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> = ProviderAccessResult.Unsupported("synthetic")

        override suspend fun updateOutgoingStatus(
            id: ProviderMessageId,
            conversationId: ConversationId,
            status: OutgoingMmsProviderStatus,
        ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = ProviderAccessResult.Unsupported("synthetic")
    }

    private class CapturingProvider(
        private val failInsert: Boolean = false,
    ) : MmsProviderDataSource {
        val records = mutableListOf<DecodedIncomingMmsRecord>()

        override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

        override suspend fun readPage(
            request: ProviderPageRequest,
        ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> =
            ProviderAccessResult.Success(ProviderPage(emptyList(), null, true))

        override suspend fun insertIncoming(
            message: DecodedIncomingMmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> {
            if (failInsert) error("synthetic provider failure")
            records += message
            return ProviderAccessResult.Success(ProviderStoredMessage(PROVIDER, CONVERSATION))
        }

        override suspend fun updateOutgoingStatus(
            id: ProviderMessageId,
            conversationId: ConversationId,
            status: OutgoingMmsProviderStatus,
        ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = ProviderAccessResult.Unsupported("synthetic")
    }

    private companion object {
        const val JOURNAL_NAME = "aurora_incoming_mms_download_journal_v1"
        const val TRANSACTION_ID = "Tincoming-submission"
        const val CONTENT_LOCATION = "https://fixtures.example.invalid/mms/Tincoming-submission"
        const val FIXED_DATE_SECONDS = 1_720_000_000L
        const val RECEIVED_TIMESTAMP_MILLIS = 1_720_000_000_000L
        const val TEXT = "Synthetic incoming MMS body"
        val SUBSCRIPTION = AuroraSubscriptionId(1)
        val SENDER = ParticipantAddress("+15551230000")
        val LOCAL_RECIPIENT = ParticipantAddress("+15551230001")
        val GROUP_MEMBER = ParticipantAddress("+15551230002")
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 501L)
        val CONVERSATION = ConversationId(601L)
        val OPERATION = MessageId(
            ProviderKind.PENDING_OPERATION,
            INCOMING_MMS_OPERATION_ID_BOUNDARY + 101L,
        )
        val OTHER_OPERATION = MessageId(
            ProviderKind.PENDING_OPERATION,
            INCOMING_MMS_OPERATION_ID_BOUNDARY + 103L,
        )

        const val COLD_RESTART_GATE_ARGUMENT = "auroraEmulatorIncomingMmsColdRestart"
        const val COLD_RESTART_PHASE_ARGUMENT = "auroraEmulatorIncomingMmsColdRestartPhase"
        const val COLD_RESTART_PHASE_PREPARE = "prepare"
        const val COLD_RESTART_PHASE_VERIFY = "verify"
        const val COLD_RESTART_PHASE_CLEANUP = "cleanup"
        const val COLD_RESTART_CHECKPOINT_NAME = "aurora_incoming_mms_cold_restart_evidence"
        const val COLD_RESTART_VERSION = 1
        const val COLD_RESTART_KEY_VERSION = "version"
        const val COLD_RESTART_KEY_STATE = "state"
        const val COLD_RESTART_STATE_PREPARING = "preparing"
        const val COLD_RESTART_STATE_PREPARED = "prepared"
        const val COLD_RESTART_STATE_VERIFIED = "verified"
        const val COLD_RESTART_KEY_FILE_NAME = "file_name"
        const val COLD_RESTART_KEY_PREPARED_PID = "prepared_pid"
        const val COLD_RESTART_KEY_PREPARED_START = "prepared_start_uptime"
        const val COLD_RESTART_KEY_VERIFIED_PID = "verified_pid"
        const val COLD_RESTART_KEY_VERIFIED_START = "verified_start_uptime"
        const val COLD_RESTART_GATE_REQUIRED = "incoming MMS cold-restart gate was not enabled"
        const val COLD_RESTART_EMULATOR_REQUIRED = "incoming MMS cold-restart test requires an emulator"
        const val COLD_RESTART_API_REQUIRED = "incoming MMS cold-restart test requires API 26 or API 36"
        const val COLD_RESTART_PHASE_INVALID = "incoming MMS cold-restart phase was invalid"
        const val COLD_RESTART_STALE_CHECKPOINT = "incoming MMS cold-restart checkpoint already exists"
        const val COLD_RESTART_STALE_JOURNAL = "incoming MMS cold-restart journal was not empty"
        const val COLD_RESTART_CHECKPOINT_WRITE_FAILED = "incoming MMS checkpoint write failed"
        const val COLD_RESTART_CHECKPOINT_CLEAR_FAILED = "incoming MMS checkpoint clear failed"
        const val COLD_RESTART_CHECKPOINT_INVALID = "incoming MMS checkpoint was invalid"
        const val COLD_RESTART_PREPARATION_FAILED = "incoming MMS preparation failed"
        const val COLD_RESTART_DELIVERY_MISMATCH = "incoming MMS delivery changed across restart"
        const val COLD_RESTART_JOURNAL_MISMATCH = "incoming MMS journal changed across restart"
        const val COLD_RESTART_STAGING_MISMATCH = "incoming MMS staged PDU changed across restart"
        const val COLD_RESTART_PROCESS_NOT_RESTARTED = "incoming MMS verification reused its process"
        const val COLD_RESTART_CLEANUP_PROCESS_NOT_RESTARTED =
            "incoming MMS cleanup reused the verification process"
        const val COLD_RESTART_RECOVERY_FAILED = "incoming MMS recovery failed"
        const val COLD_RESTART_PLATFORM_RESUBMITTED = "incoming MMS recovery resubmitted to the platform"
        const val COLD_RESTART_ACKNOWLEDGEMENT_FAILED = "incoming MMS acknowledgement failed"
        const val COLD_RESTART_JOURNAL_NOT_CLEARED = "incoming MMS journal was not cleared"
        const val COLD_RESTART_STAGING_NOT_CLEARED = "incoming MMS staged PDU was not cleared"
        const val COLD_RESTART_CLEANUP_FAILED = "incoming MMS interrupted cleanup failed"
    }

    private data class IncomingMmsColdRestartEvidence(
        val fileName: String,
        val preparedPid: Int,
        val preparedStartUptimeMillis: Long,
        val verifiedPid: Int?,
        val verifiedStartUptimeMillis: Long?,
    )
}
