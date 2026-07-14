// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.os.Build
import android.os.Trace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fixed, content-free trace slices used by release-equivalent Phase 3 measurements. */
internal object PresentationTrace {
    const val THREAD_OPEN: String = "AuroraThreadOpen"
    const val SEARCH_RESULTS: String = "AuroraSearchResults"
    const val EXACT_JUMP: String = "AuroraExactJump"

    private val nextCookie = AtomicInteger(1)
    private val activeCookies = ConcurrentHashMap<String, Int>()

    fun begin(name: String) {
        end(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val cookie = nextCookie.getAndUpdate { current ->
                if (current == Int.MAX_VALUE) 1 else current + 1
            }
            activeCookies[name] = cookie
            Trace.beginAsyncSection(name, cookie)
        }
    }

    fun end(name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            activeCookies.remove(name)?.let { cookie -> Trace.endAsyncSection(name, cookie) }
        }
    }
}
