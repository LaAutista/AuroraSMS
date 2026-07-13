// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.contacts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.boundedContactCache
import org.aurorasms.core.telephony.internal.AndroidContactChangeObserver

class AppContactCacheController(
    context: Context,
    resolver: ContactResolver,
    private val applicationScope: CoroutineScope,
) : AutoCloseable {
    val cache: ContactCache = resolver.boundedContactCache(applicationScope)
    private val observer = AndroidContactChangeObserver(context) {
        applicationScope.launch { cache.invalidate() }
    }

    init {
        observer.start()
    }

    fun onContactsPermissionChanged() {
        observer.close()
        applicationScope.launch { cache.invalidate() }
        observer.start()
    }

    override fun close() {
        observer.close()
    }
}
