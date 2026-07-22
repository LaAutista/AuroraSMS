// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.provider.ContactsContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidContactDiscoveryProviderQueryTest {
    @Test
    fun cancellationCancelsConcreteAndroidSignal() = runBlocking {
        val cancellationObserved = CountDownLatch(1)
        val signalDelivered = CompletableDeferred<CancellationSignal>()
        val providerQuery = AndroidContactDiscoveryProviderQuery(
            ContactDiscoveryCursorQuery { _, signal ->
                signal.setOnCancelListener { cancellationObserved.countDown() }
                signalDelivered.complete(signal)
                cancellationObserved.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                null
            },
        )
        val request = checkNotNull(ValidatedContactDiscoveryRequest.create("synthetic", 1))
        val queryJob = launch(Dispatchers.IO) { providerQuery.query(request) }
        val signal = signalDelivered.await()

        queryJob.cancelAndJoin()

        assertTrue(cancellationObserved.count == 0L)
        assertTrue(signal.isCanceled)
    }

    @Test
    fun cursorClosesAndReadStopsAtProviderRowBound() = runBlocking {
        val cursor = MatrixCursor(PROJECTION).apply {
            addRow(arrayOf("+15550100001", "Synthetic one", "content://synthetic/one"))
            addRow(arrayOf("+15550100002", "Synthetic two", null))
            addRow(arrayOf("+15550100003", "Synthetic three", null))
        }
        val providerQuery = AndroidContactDiscoveryProviderQuery(
            ContactDiscoveryCursorQuery { _, _ -> cursor },
        )
        val request = checkNotNull(ValidatedContactDiscoveryRequest.create("synthetic", 1))

        val rows = checkNotNull(providerQuery.query(request))

        assertEquals(request.providerRowLimit, rows.size)
        assertEquals(listOf("+15550100001", "+15550100002"), rows.map { it.address })
        assertTrue(cursor.isClosed)
    }

    @Test
    fun absentOptionalMetadataColumnsDegradeToNull() = runBlocking {
        val cursor = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)).apply {
            addRow(arrayOf("+15550100001"))
        }
        val providerQuery = AndroidContactDiscoveryProviderQuery(
            ContactDiscoveryCursorQuery { _, _ -> cursor },
        )
        val request = checkNotNull(ValidatedContactDiscoveryRequest.create("synthetic", 1))

        val row = checkNotNull(providerQuery.query(request)).single()

        assertNull(row.displayName)
        assertNull(row.photoUri)
        assertTrue(cursor.isClosed)
    }

    private companion object {
        val PROJECTION: Array<String> = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        )
        const val CANCELLATION_TIMEOUT_SECONDS: Long = 5L
    }
}
