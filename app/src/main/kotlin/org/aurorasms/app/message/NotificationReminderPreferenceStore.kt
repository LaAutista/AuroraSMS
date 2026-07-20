// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface NotificationReminderPreferenceStore {
    val delayMinutes: StateFlow<Int>
    fun setDelayMinutes(value: Int): Boolean
}

internal class SharedPreferencesNotificationReminderPreferenceStore(
    context: Context,
) : NotificationReminderPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(
        preferences.getInt(DELAY_MINUTES_KEY, DEFAULT_DELAY_MINUTES)
            .takeIf(ALLOWED_DELAY_MINUTES::contains)
            ?: DEFAULT_DELAY_MINUTES,
    )

    override val delayMinutes: StateFlow<Int> = state.asStateFlow()

    @SuppressLint("UseKtx")
    override fun setDelayMinutes(value: Int): Boolean {
        if (value !in ALLOWED_DELAY_MINUTES) return false
        if (!preferences.edit().putInt(DELAY_MINUTES_KEY, value).commit()) return false
        state.value = value
        return true
    }

    companion object {
        val ALLOWED_DELAY_MINUTES: Set<Int> = linkedSetOf(0, 15, 60, 180)
        const val DEFAULT_DELAY_MINUTES: Int = 0
        private const val PREFERENCES_NAME = "aurora_notification_reminder_preferences_v1"
        private const val DELAY_MINUTES_KEY = "delay_minutes"
    }
}
