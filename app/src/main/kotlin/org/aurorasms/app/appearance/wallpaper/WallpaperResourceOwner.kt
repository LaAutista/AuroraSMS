// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

/**
 * Owns at most one decoded resource and rejects late coroutine handoffs after disposal.
 * Release callbacks run outside the monitor and must be idempotent.
 */
internal class WallpaperResourceOwner<T>(
    private val release: (T) -> Unit,
) {
    private val lock = Any()
    private var current: T? = null
    private var disposed = false

    /** Returns true when this owner retained [next]; false means [next] was released immediately. */
    fun replace(next: T?): Boolean {
        val previous: T?
        val accepted: Boolean
        synchronized(lock) {
            if (disposed) {
                previous = next
                accepted = false
            } else if (current === next) {
                return true
            } else {
                previous = current
                current = next
                accepted = true
            }
        }
        previous?.let(release)
        return accepted
    }

    fun clear() {
        replace(null)
    }

    fun dispose() {
        val previous = synchronized(lock) {
            if (disposed) return
            disposed = true
            current.also { current = null }
        }
        previous?.let(release)
    }
}
