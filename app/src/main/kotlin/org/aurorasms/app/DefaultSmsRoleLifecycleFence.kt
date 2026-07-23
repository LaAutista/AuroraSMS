// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes role lifecycle work and derives each transition from authoritative
 * platform state instead of trusting potentially stale broadcast extras.
 */
internal class DefaultSmsRoleLifecycleFence(
    private val currentRoleHeld: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun reconcile(block: suspend (roleHeld: Boolean) -> Unit) {
        mutex.withLock {
            var observedRoleHeld = currentRoleHeld()
            while (true) {
                block(observedRoleHeld)
                val refreshedRoleHeld = currentRoleHeld()
                if (refreshedRoleHeld == observedRoleHeld) return@withLock
                observedRoleHeld = refreshedRoleHeld
            }
        }
    }
}
