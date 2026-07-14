// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.index

/**
 * Allows a new bounded Telephony provider reconciliation unit only while at
 * least one messaging activity is started. A unit already admitted may finish
 * after the activity stops, but the next batch/head verification is denied.
 * A cached default-SMS process may still receive and ledger a content-free
 * index signal without continuously issuing provider binder calls.
 */
internal class ForegroundIndexReadGate {
    private val lifecycleLock = Any()
    @Volatile
    private var providerReadsPermitted: Boolean = false
    private var startedActivityCount: Int = 0

    /** Returns true only for the transition from zero to one started activity. */
    fun onActivityStarted(): Boolean = synchronized(lifecycleLock) {
        val enteredForeground = startedActivityCount == 0
        check(startedActivityCount < Int.MAX_VALUE) { "Started activity count overflow" }
        startedActivityCount += 1
        providerReadsPermitted = true
        enteredForeground
    }

    fun onActivityStopped() {
        synchronized(lifecycleLock) {
            if (startedActivityCount == 0) return
            startedActivityCount -= 1
            providerReadsPermitted = startedActivityCount > 0
        }
    }

    fun isProviderReadPermitted(): Boolean = providerReadsPermitted
}
