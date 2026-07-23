// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.core.model.ConversationId

class InlineReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationProtocol.ACTION_INLINE_REPLY) return

        val entryPoint = context.applicationContext as? NotificationEntryPoint ?: return
        val request = inlineReplyRequestFromIntent(entryPoint, intent) ?: return

        val pendingResult = goAsync()
        val handlerWork = InlineReplyReceiverScope.scope.launch {
            runInlineReplyHandlerSafely(entryPoint.inlineReplyHandler, request)
        }
        InlineReplyReceiverScope.scope.launch {
            finishAfterBoundedSiblingJoin(
                sibling = handlerWork,
                maximumWaitMillis = InlineReplyReceiverScope.MAXIMUM_WORK_MILLIS,
                finish = pendingResult::finish,
            )
        }
    }
}

internal fun inlineReplyRequestFromIntent(
    entryPoint: NotificationEntryPoint,
    intent: Intent,
): InlineReplyRequest? {
    val entryPointMaximum = entryPoint.maximumInlineReplyCharacters
    if (entryPointMaximum !in 1..NotificationConfig.ABSOLUTE_MAXIMUM_REPLY_CHARACTERS) return null
    val protocolData = parseInlineReplyData(intent.data) ?: return null
    val maximumCharacters = minOf(entryPointMaximum, protocolData.maximumCharacters)
    val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return null
    val reply = remoteInput.getCharSequence(NotificationProtocol.REMOTE_INPUT_REPLY)?.toString()
    return validatedInlineReplyRequest(
        conversationValue = protocolData.conversationValue,
        notificationId = notificationIdForConversation(
            ConversationId(protocolData.conversationValue),
        ),
        requestId = protocolData.requestId,
        reply = reply,
        maximumCharacters = maximumCharacters,
    )
}

internal suspend fun runInlineReplyHandlerSafely(
    handler: InlineReplyHandler,
    request: InlineReplyRequest,
) {
    try {
        handler.handle(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // A corrupt local record or platform runtime failure must not crash the
        // broadcast process. Durable operation recovery owns any accepted work.
    }
}

internal suspend fun finishAfterBoundedSiblingJoin(
    sibling: Job,
    maximumWaitMillis: Long,
    finish: () -> Unit,
) {
    require(maximumWaitMillis > 0L) { "maximumWaitMillis must be positive" }
    try {
        withTimeoutOrNull(maximumWaitMillis) {
            sibling.join()
        }
    } finally {
        finish()
    }
}

private object InlineReplyReceiverScope {
    const val MAXIMUM_WORK_MILLIS: Long = 8_000L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

private val REPLY_REQUEST_PATTERN = Regex("(?:SMS|MMS):[1-9][0-9]{0,18}")

internal fun validatedInlineReplyRequest(
    conversationValue: Long,
    notificationId: Int,
    requestId: String?,
    reply: String?,
    maximumCharacters: Int,
): InlineReplyRequest? {
    if (conversationValue <= 0L) return null
    if (maximumCharacters !in 1..NotificationConfig.ABSOLUTE_MAXIMUM_REPLY_CHARACTERS) return null
    val conversationId = ConversationId(conversationValue)
    if (notificationId != notificationIdForConversation(conversationId)) return null
    val validRequestId = requestId
        ?.takeIf { value -> value.length <= 24 && REPLY_REQUEST_PATTERN.matches(value) }
        ?: return null
    val validReply = reply
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maximumCharacters }
        ?.takeIf { value -> value.none { it.isISOControl() && it != '\n' && it != '\t' } }
        ?: return null
    return InlineReplyRequest(
        conversationId = conversationId,
        notificationId = notificationId,
        replyRequestId = validRequestId,
        text = validReply,
    )
}

internal data class InlineReplyProtocolData(
    val conversationValue: Long,
    val requestId: String,
    val maximumCharacters: Int,
)

internal fun parseInlineReplyData(data: Uri?): InlineReplyProtocolData? {
    if (data?.scheme != NotificationProtocol.INLINE_REPLY_SCHEME) return null
    if (data.authority != NotificationProtocol.INLINE_REPLY_AUTHORITY) return null
    val segments = data.pathSegments
    if (segments.size != 4) return null
    val conversationValue = segments[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val requestId = "${segments[1]}:${segments[2]}"
        .takeIf { value -> value.length <= 24 && REPLY_REQUEST_PATTERN.matches(value) }
        ?: return null
    val maximumCharacters = segments[3].toIntOrNull()
        ?.takeIf { it in 1..NotificationConfig.ABSOLUTE_MAXIMUM_REPLY_CHARACTERS }
        ?: return null
    return InlineReplyProtocolData(
        conversationValue = conversationValue,
        requestId = requestId,
        maximumCharacters = maximumCharacters,
    )
}
