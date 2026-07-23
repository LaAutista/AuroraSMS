// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.MmsSubmissionOwnership
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceMemoControllerTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stagingDirectory().deleteRecursively()
    }

    @After
    fun tearDown() {
        scope.cancel()
        stagingDirectory().deleteRecursively()
    }

    @Test
    fun virtualMicrophoneCaptureIsReviewedThenDeletedAfterFakeSubmission() = runBlocking {
        val transport = CapturingTransport()
        val controller = VoiceMemoController(context, transport, scope)
        val target = target()

        assertTrue(controller.start(target))
        assertTrue(controller.state.value is VoiceMemoCaptureState.Recording)
        delay(STABLE_CAPTURE_MILLIS)
        assertTrue(controller.stop())
        val ready = controller.state.value as VoiceMemoCaptureState.Ready
        assertTrue(ready.durationMillis in 1L..60_000L)
        assertTrue(ready.sizeBytes in 1..524_288)
        assertEquals(1, stagingDirectory().listFiles().orEmpty().size)

        val submitted = controller.send()
        assertTrue(submitted is TransportResult.Submitted)
        assertTrue(controller.state.value is VoiceMemoCaptureState.AwaitingCallback)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
        assertNotNull(transport.request)
        val request = checkNotNull(transport.request)
        assertEquals(target.providerThreadId, request.providerThreadId)
        assertEquals(target.recipients, request.recipients)
        assertTrue(request.payload is OutgoingMmsPayload.VoiceMemo)

        controller.handleTransportResult(
            TransportResult.Sent(
                operationId = request.operationId,
                transport = MessageTransportKind.MMS,
                platformResultCode = Activity.RESULT_OK,
            ),
        )
        assertTrue(controller.state.value is VoiceMemoCaptureState.Sent)
        controller.cancel()
        assertEquals(VoiceMemoCaptureState.Idle, controller.state.value)
    }

    @Test
    fun cancellationDeletesCaptureWithoutCallingTransport() = runBlocking {
        val transport = CapturingTransport()
        val controller = VoiceMemoController(context, transport, scope)

        assertTrue(controller.start(target()))
        delay(500L)
        controller.cancel()

        assertEquals(VoiceMemoCaptureState.Idle, controller.state.value)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
        assertEquals(null, transport.request)
    }

    @Test
    fun leavingTheThreadDeletesAReviewedCaptureWithoutCallingTransport() = runBlocking {
        val transport = CapturingTransport()
        val controller = VoiceMemoController(context, transport, scope)
        val target = target()

        assertTrue(controller.start(target))
        delay(STABLE_CAPTURE_MILLIS)
        assertTrue(controller.stop())
        assertTrue(controller.state.value is VoiceMemoCaptureState.Ready)

        controller.onThreadHidden(target.providerThreadId)

        assertEquals(VoiceMemoCaptureState.Idle, controller.state.value)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
        assertEquals(null, transport.request)
    }

    private fun target(): VoiceMemoTarget {
        val recipients = RecipientSet.from(listOf(ParticipantAddress("+15551234567"))) as
            RecipientSet.CreationResult.Valid
        return VoiceMemoTarget(
            providerThreadId = ProviderThreadId(301L),
            recipients = recipients.recipients,
            subscriptionId = AuroraSubscriptionId(1),
            caption = "Synthetic signature",
        )
    }

    private fun stagingDirectory(): File = File(context.noBackupFilesDir, "voice_memo_staging")

    private companion object {
        const val STABLE_CAPTURE_MILLIS = 2_500L
    }

    private class CapturingTransport : MessageTransport {
        var request: MmsSendRequest? = null

        override suspend fun sendSms(
            request: SmsSendRequest,
            ownership: SmsSubmissionOwnership,
        ): TransportResult = error("SMS must not be used for a voice memo")

        override suspend fun sendMms(
            request: MmsSendRequest,
            ownership: MmsSubmissionOwnership,
        ): TransportResult {
            this.request = request
            return TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.MMS,
                unitCount = 1,
            )
        }

        override suspend fun downloadMms(request: MmsDownloadRequest): TransportResult =
            error("Download must not be used for a voice memo")
    }
}
