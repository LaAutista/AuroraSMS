// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    @Test
    fun inlineReplyReceiver_isEnabledAndNotExported() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, InlineReplyReceiver::class.java),
            0,
        )

        assertFalse(receiver.exported)
        assertTrueCompat(receiver.enabled)
    }

    @Suppress("DEPRECATION")
    @Test
    fun messagingActionService_isEnabledAndNotExported() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, MessagingNotificationActionService::class.java),
            0,
        )

        assertFalse(service.exported)
        assertTrueCompat(service.enabled)
    }

    @Test
    fun channels_areStableAndLocalized() {
        NotificationChannels.ensureCreated(context)
        NotificationChannels.ensureCreated(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val messages = manager.getNotificationChannel(NotificationChannels.MESSAGES)
        val failures = manager.getNotificationChannel(NotificationChannels.REPLY_FAILURES)

        assertNotNull(messages)
        assertNotNull(failures)
        assertEquals(
            context.getString(R.string.notification_channel_messages_name),
            messages.name.toString(),
        )
        assertEquals(
            context.getString(R.string.notification_channel_reply_failures_name),
            failures.name.toString(),
        )
    }

    @Test
    fun inlineReplyProtocol_acceptsOnlyTheExactInternalShape() {
        val valid = Uri.Builder()
            .scheme(NotificationProtocol.INLINE_REPLY_SCHEME)
            .authority(NotificationProtocol.INLINE_REPLY_AUTHORITY)
            .appendPath("41")
            .appendPath("SMS")
            .appendPath("9001")
            .appendPath("4000")
            .build()

        val parsed = parseInlineReplyData(valid)

        assertEquals(41L, parsed?.conversationValue)
        assertEquals("SMS:9001", parsed?.requestId)
        assertEquals(4_000, parsed?.maximumCharacters)
        assertNull(parseInlineReplyData(valid.buildUpon().authority("outside").build()))
        assertNull(parseInlineReplyData(valid.buildUpon().appendPath("extra").build()))
    }
}

private fun assertTrueCompat(value: Boolean) {
    org.junit.Assert.assertTrue(value)
}
