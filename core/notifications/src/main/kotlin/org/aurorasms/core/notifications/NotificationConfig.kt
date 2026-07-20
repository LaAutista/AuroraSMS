// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.PendingIntent
import android.annotation.SuppressLint

data class NotificationConfig(
    val privacy: NotificationPrivacy = NotificationPrivacy.GENERIC,
    val maximumReplyCharacters: Int = DEFAULT_MAXIMUM_REPLY_CHARACTERS,
) {
    init {
        require(maximumReplyCharacters in 1..ABSOLUTE_MAXIMUM_REPLY_CHARACTERS) {
            "maximumReplyCharacters must be between 1 and $ABSOLUTE_MAXIMUM_REPLY_CHARACTERS"
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_REPLY_CHARACTERS: Int = 4_000
        const val ABSOLUTE_MAXIMUM_REPLY_CHARACTERS: Int = 16_000
    }
}

/** Centralized flags so every PendingIntent path has an auditable mutability policy. */
object PendingIntentPolicy {
    fun immutableUpdateCurrent(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    @SuppressLint("InlinedApi")
    fun inlineReplyUpdateCurrent(sdkInt: Int): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (sdkInt >= 31) PendingIntent.FLAG_MUTABLE else 0
}

internal object NotificationProtocol {
    const val ACTION_INLINE_REPLY =
        "org.aurorasms.core.notifications.action.INLINE_REPLY"
    const val ACTION_MARK_AS_READ =
        "org.aurorasms.core.notifications.action.MARK_AS_READ"
    const val INLINE_REPLY_SCHEME = "aurorasms-internal"
    const val INLINE_REPLY_AUTHORITY = "inline-reply"
    const val MARK_AS_READ_AUTHORITY = "mark-as-read"
    const val REMOTE_INPUT_REPLY =
        "org.aurorasms.core.notifications.remote_input.REPLY"
    const val CATEGORY_CONVERSATION_PREFIX =
        "org.aurorasms.core.notifications.category.CONVERSATION."
}

internal fun stableRequestCode(key: String, salt: Int): Int =
    (31 * key.hashCode() + salt) and Int.MAX_VALUE
