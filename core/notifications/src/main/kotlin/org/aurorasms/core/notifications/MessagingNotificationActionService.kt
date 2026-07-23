// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package org.aurorasms.core.notifications

import android.app.IntentService
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

/** Background action endpoint used by Android Auto and ordinary system notifications. */
class MessagingNotificationActionService : IntentService(SERVICE_NAME) {
    override fun onHandleIntent(intent: Intent?) {
        val actionIntent = intent ?: return
        val entryPoint = applicationContext as? NotificationEntryPoint ?: return
        when (actionIntent.action) {
            NotificationProtocol.ACTION_INLINE_REPLY -> {
                val request = inlineReplyRequestFromIntent(entryPoint, actionIntent) ?: return
                runBlocking { runInlineReplyHandlerSafely(entryPoint.inlineReplyHandler, request) }
            }

            NotificationProtocol.ACTION_MARK_AS_READ -> {
                val request = parseMarkConversationReadData(actionIntent.data) ?: return
                runBlocking {
                    runMarkConversationReadHandlerSafely(
                        entryPoint.markConversationReadHandler,
                        request,
                    )
                }
            }
        }
    }

    private companion object {
        const val SERVICE_NAME = "AuroraNotificationActions"
    }
}

internal suspend fun runMarkConversationReadHandlerSafely(
    handler: MarkConversationReadHandler,
    request: MarkConversationReadRequest,
) {
    try {
        handler.handle(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Provider or local-state failures must not crash a system action process.
    }
}

internal fun markConversationReadData(request: MarkConversationReadRequest): Uri = Uri.Builder()
    .scheme(NotificationProtocol.INLINE_REPLY_SCHEME)
    .authority(NotificationProtocol.MARK_AS_READ_AUTHORITY)
    .appendPath(request.conversationId.value.toString())
    .appendPath(request.throughMessageId.kind.name)
    .appendPath(request.throughMessageId.value.toString())
    .build()

internal fun parseMarkConversationReadData(data: Uri?): MarkConversationReadRequest? {
    if (data?.scheme != NotificationProtocol.INLINE_REPLY_SCHEME) return null
    if (data.authority != NotificationProtocol.MARK_AS_READ_AUTHORITY) return null
    val segments = data.pathSegments
    if (segments.size != 3) return null
    val conversationId = segments[0].toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let(::ConversationId)
        ?: return null
    if (segments[1] != ProviderKind.SMS.name) return null
    val messageId = segments[2].toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { MessageId(ProviderKind.SMS, it) }
        ?: return null
    return MarkConversationReadRequest(conversationId, messageId)
        .takeIf { markConversationReadData(it) == data }
}
