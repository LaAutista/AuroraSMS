// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.telephony.SmsMessage

internal fun interface SmsSegmentCounter {
    /** Returns null when Android cannot produce a trustworthy bounded count. */
    fun count(body: String): Int?
}

internal object AndroidSmsSegmentCounter : SmsSegmentCounter {
    override fun count(body: String): Int? {
        if (body.isEmpty()) return null
        return try {
            SmsMessage.calculateLength(body, false)
                .getOrNull(0)
                ?.takeIf { it in 1..MAXIMUM_SMS_SEGMENTS }
        } catch (_: RuntimeException) {
            null
        }
    }

    private const val MAXIMUM_SMS_SEGMENTS: Int = 255
}
