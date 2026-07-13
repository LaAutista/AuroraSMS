// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class AndroidContactChangeObserver(
    context: Context,
    private val onContactsChanged: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val registered = AtomicBoolean(false)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onContactsChanged()
        }
    }

    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!registered.compareAndSet(false, true)) return true
        return try {
            appContext.contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI,
                true,
                observer,
            )
            true
        } catch (_: SecurityException) {
            registered.set(false)
            false
        } catch (_: RuntimeException) {
            registered.set(false)
            false
        }
    }

    override fun close() {
        if (!registered.compareAndSet(true, false)) return
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
    }
}
