// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.OutgoingVoiceMemo
import org.aurorasms.core.telephony.RecipientSet

internal data class VoiceMemoTarget(
    val providerThreadId: ProviderThreadId,
    val recipients: RecipientSet,
    val subscriptionId: AuroraSubscriptionId,
    val caption: String? = null,
) {
    init {
        require(recipients.size == 1) { "Voice memos support one verified recipient" }
        require(caption == null || caption.length <= 160)
        require(caption?.any { it.isISOControl() && it != '\n' } != true)
    }

    override fun toString(): String =
        "VoiceMemoTarget(hasThread=true, recipientCount=${recipients.size}, " +
            "hasCaption=${caption != null}, REDACTED)"
}

internal sealed interface VoiceMemoCaptureState {
    val providerThreadId: ProviderThreadId?

    data object Idle : VoiceMemoCaptureState {
        override val providerThreadId: ProviderThreadId? = null
    }

    data class Preparing(
        override val providerThreadId: ProviderThreadId,
    ) : VoiceMemoCaptureState

    data class Recording(
        override val providerThreadId: ProviderThreadId,
        val elapsedMillis: Long,
    ) : VoiceMemoCaptureState {
        init { require(elapsedMillis in 0L..OutgoingVoiceMemo.MAX_DURATION_MILLIS) }
    }

    data class Ready(
        override val providerThreadId: ProviderThreadId,
        val durationMillis: Long,
        val sizeBytes: Int,
        val retryAfterKnownFailure: Boolean = false,
    ) : VoiceMemoCaptureState {
        init {
            require(durationMillis in 1L..OutgoingVoiceMemo.MAX_DURATION_MILLIS)
            require(sizeBytes in 1..OutgoingVoiceMemo.MAX_BYTES)
        }
    }

    data class Sending(
        override val providerThreadId: ProviderThreadId,
    ) : VoiceMemoCaptureState

    data class AwaitingCallback(
        override val providerThreadId: ProviderThreadId,
        val operationId: MessageId,
    ) : VoiceMemoCaptureState

    data class Sent(
        override val providerThreadId: ProviderThreadId,
    ) : VoiceMemoCaptureState

    data class Failed(
        override val providerThreadId: ProviderThreadId?,
        val reason: VoiceMemoFailure,
    ) : VoiceMemoCaptureState
}

internal enum class VoiceMemoFailure {
    PERMISSION_DENIED,
    CAPTURE_UNAVAILABLE,
    CAPTURE_TOO_SHORT,
    CAPTURE_INVALID,
    SEND_REJECTED,
    SUBMISSION_UNKNOWN,
    SEND_FAILED,
}

/** Owns one foreground capture; audio bytes never enter state, logs, preferences, or backup. */
internal class VoiceMemoController(
    context: Context,
    private val transport: MessageTransport,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val randomLong: () -> Long = SecureRandom()::nextLong,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val root = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
    private val mutableState = MutableStateFlow<VoiceMemoCaptureState>(VoiceMemoCaptureState.Idle)
    val state: StateFlow<VoiceMemoCaptureState> = mutableState.asStateFlow()

    private var active: ActiveCapture? = null
    private var ready: ReadyCapture? = null
    private var ticker: Job? = null

    init {
        scope.launch(ioDispatcher) { removeAbandonedFiles() }
    }

    suspend fun start(target: VoiceMemoTarget): Boolean = mutex.withLock {
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            mutableState.value = VoiceMemoCaptureState.Failed(
                target.providerThreadId,
                VoiceMemoFailure.PERMISSION_DENIED,
            )
            return@withLock false
        }
        when (mutableState.value) {
            is VoiceMemoCaptureState.Preparing,
            is VoiceMemoCaptureState.Recording,
            is VoiceMemoCaptureState.Ready,
            is VoiceMemoCaptureState.Sending,
            is VoiceMemoCaptureState.AwaitingCallback,
            -> return@withLock false
            VoiceMemoCaptureState.Idle,
            is VoiceMemoCaptureState.Failed,
            is VoiceMemoCaptureState.Sent,
            -> Unit
        }
        mutableState.value = VoiceMemoCaptureState.Preparing(target.providerThreadId)
        val file = createCaptureFile() ?: return@withLock failCapture(
            target.providerThreadId,
            VoiceMemoFailure.CAPTURE_UNAVAILABLE,
        )
        val recorder = newMediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            recorder.setMaxDuration(OutgoingVoiceMemo.MAX_DURATION_MILLIS.toInt())
            recorder.setMaxFileSize(OutgoingVoiceMemo.MAX_BYTES.toLong())
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    scope.launch(ioDispatcher) { stop() }
                }
            }
            recorder.prepare()
            recorder.start()
        } catch (_: RuntimeException) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            file.delete()
            return@withLock failCapture(
                target.providerThreadId,
                VoiceMemoFailure.CAPTURE_UNAVAILABLE,
            )
        } catch (_: java.io.IOException) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            file.delete()
            return@withLock failCapture(
                target.providerThreadId,
                VoiceMemoFailure.CAPTURE_UNAVAILABLE,
            )
        }
        val capture = ActiveCapture(
            recorder = recorder,
            file = file,
            target = target,
            startedElapsedMillis = elapsedRealtime(),
        )
        active = capture
        mutableState.value = VoiceMemoCaptureState.Recording(target.providerThreadId, 0L)
        ticker?.cancel()
        ticker = scope.launch(ioDispatcher) { updateElapsedWhileRecording(capture) }
        true
    }

    suspend fun stop(): Boolean = mutex.withLock {
        val capture = active ?: return@withLock false
        active = null
        ticker?.cancel()
        ticker = null
        val stopped = try {
            capture.recorder.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { capture.recorder.reset() }
            runCatching { capture.recorder.release() }
        }
        if (!stopped) {
            capture.file.delete()
            return@withLock failCapture(
                capture.target.providerThreadId,
                VoiceMemoFailure.CAPTURE_TOO_SHORT,
            )
        }
        val duration = (elapsedRealtime() - capture.startedElapsedMillis)
            .coerceIn(1L, OutgoingVoiceMemo.MAX_DURATION_MILLIS)
        val size = validatedCaptureSize(capture.file)
            ?: run {
                capture.file.delete()
                return@withLock failCapture(
                    capture.target.providerThreadId,
                    VoiceMemoFailure.CAPTURE_INVALID,
                )
            }
        ready = ReadyCapture(capture.file, capture.target, duration, size)
        mutableState.value = VoiceMemoCaptureState.Ready(
            providerThreadId = capture.target.providerThreadId,
            durationMillis = duration,
            sizeBytes = size,
        )
        true
    }

    suspend fun cancel() = mutex.withLock {
        cancelCaptureLocked()
        if (mutableState.value !is VoiceMemoCaptureState.Sending &&
            mutableState.value !is VoiceMemoCaptureState.AwaitingCallback
        ) {
            mutableState.value = VoiceMemoCaptureState.Idle
        }
    }

    suspend fun onThreadHidden(providerThreadId: ProviderThreadId) = mutex.withLock {
        val current = mutableState.value
        if (
            current.providerThreadId == providerThreadId &&
            current !is VoiceMemoCaptureState.Sending &&
            current !is VoiceMemoCaptureState.AwaitingCallback
        ) {
            cancelCaptureLocked()
            mutableState.value = VoiceMemoCaptureState.Idle
        }
    }

    fun onThreadHiddenAsync(providerThreadId: ProviderThreadId) {
        scope.launch(ioDispatcher) { onThreadHidden(providerThreadId) }
    }

    suspend fun send(): TransportResult? = mutex.withLock {
        val capture = ready ?: return@withLock null
        val target = capture.target
        val size = validatedCaptureSize(capture.file) ?: run {
            capture.file.delete()
            ready = null
            failCapture(target.providerThreadId, VoiceMemoFailure.CAPTURE_INVALID)
            return@withLock null
        }
        val bytes = try {
            capture.file.readBytes()
        } catch (_: java.io.IOException) {
            failCapture(target.providerThreadId, VoiceMemoFailure.CAPTURE_INVALID)
            return@withLock null
        }
        if (bytes.size != size) {
            capture.file.delete()
            ready = null
            failCapture(target.providerThreadId, VoiceMemoFailure.CAPTURE_INVALID)
            return@withLock null
        }
        val memo = when (val created = OutgoingVoiceMemo.create(bytes, capture.durationMillis)) {
            is OutgoingVoiceMemo.CreationResult.Valid -> created.memo
            is OutgoingVoiceMemo.CreationResult.Rejected -> {
                capture.file.delete()
                ready = null
                failCapture(target.providerThreadId, VoiceMemoFailure.CAPTURE_INVALID)
                return@withLock null
            }
        }
        val operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            nextVoiceMemoOperationId(randomLong),
        )
        mutableState.value = VoiceMemoCaptureState.Sending(target.providerThreadId)
        val result = try {
            transport.sendMms(
                MmsSendRequest(
                    operationId = operationId,
                    recipients = target.recipients,
                    payload = OutgoingMmsPayload.VoiceMemo(
                        text = target.caption,
                        subject = null,
                        memo = memo,
                    ),
                    subscriptionId = target.subscriptionId,
                    providerThreadId = target.providerThreadId,
                ),
            )
        } catch (cancelled: CancellationException) {
            capture.file.delete()
            ready = null
            mutableState.value = VoiceMemoCaptureState.Failed(
                target.providerThreadId,
                VoiceMemoFailure.SUBMISSION_UNKNOWN,
            )
            throw cancelled
        } catch (_: RuntimeException) {
            capture.file.delete()
            ready = null
            mutableState.value = VoiceMemoCaptureState.Failed(
                target.providerThreadId,
                VoiceMemoFailure.SUBMISSION_UNKNOWN,
            )
            return@withLock null
        }
        when (result) {
            is TransportResult.Submitted -> {
                capture.file.delete()
                ready = null
                mutableState.value = VoiceMemoCaptureState.AwaitingCallback(
                    target.providerThreadId,
                    operationId,
                )
            }
            is TransportResult.Sent -> {
                capture.file.delete()
                ready = null
                mutableState.value = VoiceMemoCaptureState.Sent(target.providerThreadId)
            }
            is TransportResult.Failed -> if (
                result.stage == TransportResult.FailureStage.SUBMISSION_UNKNOWN
            ) {
                capture.file.delete()
                ready = null
                mutableState.value = VoiceMemoCaptureState.Failed(
                    target.providerThreadId,
                    VoiceMemoFailure.SUBMISSION_UNKNOWN,
                )
            } else {
                mutableState.value = capture.readyState(retryAfterKnownFailure = true)
            }
            is TransportResult.Rejected -> {
                mutableState.value = capture.readyState(retryAfterKnownFailure = true)
            }
            is TransportResult.Delivered,
            is TransportResult.Downloaded,
            -> mutableState.value = capture.readyState(retryAfterKnownFailure = true)
        }
        result
    }

    suspend fun handleTransportResult(result: TransportResult) = mutex.withLock {
        val awaiting = mutableState.value as? VoiceMemoCaptureState.AwaitingCallback
            ?: return@withLock
        if (result.operationId != awaiting.operationId) return@withLock
        mutableState.value = when (result) {
            is TransportResult.Sent -> VoiceMemoCaptureState.Sent(awaiting.providerThreadId)
            is TransportResult.Failed -> VoiceMemoCaptureState.Failed(
                awaiting.providerThreadId,
                VoiceMemoFailure.SEND_FAILED,
            )
            else -> awaiting
        }
    }

    private suspend fun updateElapsedWhileRecording(expected: ActiveCapture) {
        while (true) {
            delay(ELAPSED_UPDATE_MILLIS)
            val continueRecording = mutex.withLock {
                if (active !== expected) return@withLock false
                val elapsed = (elapsedRealtime() - expected.startedElapsedMillis)
                    .coerceIn(0L, OutgoingVoiceMemo.MAX_DURATION_MILLIS)
                mutableState.value = VoiceMemoCaptureState.Recording(
                    expected.target.providerThreadId,
                    elapsed,
                )
                true
            }
            if (!continueRecording) return
        }
    }

    private fun cancelCaptureLocked() {
        ticker?.cancel()
        ticker = null
        active?.let { capture ->
            active = null
            runCatching { capture.recorder.stop() }
            runCatching { capture.recorder.reset() }
            runCatching { capture.recorder.release() }
            capture.file.delete()
        }
        ready?.file?.delete()
        ready = null
    }

    private fun createCaptureFile(): File? = try {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return null
        File.createTempFile(FILE_PREFIX, FILE_SUFFIX, root)
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun validatedCaptureSize(file: File): Int? = try {
        if (!file.isFile || file.parentFile?.canonicalFile != root.canonicalFile) return null
        file.length().takeIf { it in 1L..OutgoingVoiceMemo.MAX_BYTES.toLong() }?.toInt()
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun removeAbandonedFiles() {
        if (!root.exists()) return
        try {
            java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
                var inspected = 0
                for (entry in entries) {
                    if (inspected++ >= MAXIMUM_ABANDONED_FILES_PER_PASS) break
                    val file = entry.toFile()
                    if (file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)) {
                        file.delete()
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // A later explicit capture retries directory creation and bounded cleanup.
        } catch (_: SecurityException) {
            // Fail closed: no recording starts unless a private output can be created.
        }
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()

    private fun failCapture(threadId: ProviderThreadId?, reason: VoiceMemoFailure): Boolean {
        mutableState.value = VoiceMemoCaptureState.Failed(threadId, reason)
        return false
    }

    private data class ActiveCapture(
        val recorder: MediaRecorder,
        val file: File,
        val target: VoiceMemoTarget,
        val startedElapsedMillis: Long,
    )

    private data class ReadyCapture(
        val file: File,
        val target: VoiceMemoTarget,
        val durationMillis: Long,
        val sizeBytes: Int,
    ) {
        fun readyState(retryAfterKnownFailure: Boolean): VoiceMemoCaptureState.Ready =
            VoiceMemoCaptureState.Ready(
                providerThreadId = target.providerThreadId,
                durationMillis = durationMillis,
                sizeBytes = sizeBytes,
                retryAfterKnownFailure = retryAfterKnownFailure,
            )
    }

    private companion object {
        const val DIRECTORY_NAME = "voice_memo_staging"
        const val FILE_PREFIX = "voice-"
        const val FILE_SUFFIX = ".m4a"
        const val AUDIO_BIT_RATE = 48_000
        const val AUDIO_SAMPLE_RATE = 44_100
        const val ELAPSED_UPDATE_MILLIS = 250L
        const val MAXIMUM_ABANDONED_FILES_PER_PASS = 256
    }
}

internal fun nextVoiceMemoOperationId(nextLong: () -> Long): Long {
    var candidate: Long
    do {
        candidate = nextLong() and (COMPOSER_OPERATION_ID_BOUNDARY - 1L)
    } while (candidate == 0L)
    return candidate
}
