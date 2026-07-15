// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * App-wide admission control for bounded bitmap decodes.
 *
 * MMS previews and appearance wallpapers share this gate so independently safe inputs cannot
 * combine into an unsafe allocation burst.
 */
class BoundedMediaDecodeGate(
    permits: Int = MAXIMUM_CONCURRENT_MEDIA_DECODES,
) {
    private val semaphore = Semaphore(permits.also { require(it > 0) })

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}

internal const val MAXIMUM_CONCURRENT_MEDIA_DECODES: Int = 2
