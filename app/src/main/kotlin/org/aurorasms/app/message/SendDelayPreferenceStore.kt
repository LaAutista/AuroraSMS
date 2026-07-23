// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface SendDelayPreferenceStore {
    val delaySeconds: StateFlow<Int>
    fun setDelaySeconds(value: Int): Boolean
}

internal object UnavailableSendDelayPreferenceStore : SendDelayPreferenceStore {
    private val state = MutableStateFlow(0)
    override val delaySeconds: StateFlow<Int> = state.asStateFlow()
    override fun setDelaySeconds(value: Int): Boolean = false
}

internal class SharedPreferencesSendDelayPreferenceStore(context: Context) : SendDelayPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(
        preferences.getInt(DELAY_SECONDS_KEY, DEFAULT_SEND_DELAY_SECONDS)
            .takeIf(ALLOWED_SEND_DELAY_SECONDS::contains)
            ?: DEFAULT_SEND_DELAY_SECONDS,
    )

    override val delaySeconds: StateFlow<Int> = state.asStateFlow()

    @SuppressLint("UseKtx")
    override fun setDelaySeconds(value: Int): Boolean {
        if (value !in ALLOWED_SEND_DELAY_SECONDS) return false
        if (!preferences.edit().putInt(DELAY_SECONDS_KEY, value).commit()) return false
        state.value = value
        return true
    }

    companion object {
        val ALLOWED_SEND_DELAY_SECONDS: Set<Int> = linkedSetOf(0, 1, 3, 5, 10)
        const val DEFAULT_SEND_DELAY_SECONDS: Int = 0
        private const val PREFERENCES_NAME = "aurora_send_preferences_v1"
        private const val DELAY_SECONDS_KEY = "send_delay_seconds"
    }
}
