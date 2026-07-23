// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.BaseColumns
import android.provider.Telephony
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderThreadParticipantQueryTest {
    @Test
    fun boundedTwoStageReadbackUsesExactCanonicalIdsAndClosesCursors() = runBlocking {
        val threadCursor = MatrixCursor(THREAD_PROJECTION).apply {
            addRow(arrayOf<Any?>(41L, "3 9"))
        }
        val addressCursor = MatrixCursor(ADDRESS_PROJECTION).apply {
            addRow(arrayOf<Any?>(9L, "+15550102020"))
            addRow(arrayOf<Any?>(3L, "Local@example.invalid"))
        }
        val calls = mutableListOf<QueryCall>()
        val query = AndroidProviderThreadParticipantQuery(
            ProviderThreadCursorQuery { uri, projection, selection, args, sort, signal ->
                calls += QueryCall(uri, projection.copyOf(), selection, args?.copyOf(), sort, signal)
                if (uri.lastPathSegment == "recipients") threadCursor else addressCursor
            },
        )
        val cancellationSignal = CancellationSignal()

        val result = query.query(ProviderThreadId(41), cancellationSignal)

        assertEquals(
            ProviderThreadParticipantSnapshot(
                providerThreadId = ProviderThreadId(41),
                addresses = listOf("Local@example.invalid", "+15550102020"),
            ),
            result,
        )
        assertEquals(2, calls.size)
        assertEquals("recipients", calls[0].uri.lastPathSegment)
        assertSame(cancellationSignal, calls[0].signal)
        assertSame(cancellationSignal, calls[1].signal)
        assertEquals("${BaseColumns._ID} IN (?,?)", calls[1].selection)
        assertArrayEquals(arrayOf("3", "9"), calls[1].selectionArgs)
        assertEquals("${BaseColumns._ID} ASC", calls[1].sortOrder)
        assertTrue(threadCursor.isClosed)
        assertTrue(addressCursor.isClosed)
    }

    @Test
    fun duplicateThreadRowsFailClosedBeforeCanonicalQuery() = runBlocking {
        val threadCursor = MatrixCursor(THREAD_PROJECTION).apply {
            addRow(arrayOf<Any?>(41L, "9"))
            addRow(arrayOf<Any?>(41L, "9"))
        }
        var queryCount = 0
        val query = AndroidProviderThreadParticipantQuery(
            ProviderThreadCursorQuery { _, _, _, _, _, _ ->
                queryCount += 1
                threadCursor
            },
        )

        val result = query.query(ProviderThreadId(41), CancellationSignal())

        assertNull(result)
        assertEquals(1, queryCount)
        assertTrue(threadCursor.isClosed)
    }

    @Test
    fun ignoredCanonicalSelectionAndUnexpectedRowsFailClosedAtBound() = runBlocking {
        val threadCursor = MatrixCursor(THREAD_PROJECTION).apply {
            addRow(arrayOf<Any?>(41L, "9"))
        }
        val addressCursor = MatrixCursor(ADDRESS_PROJECTION).apply {
            addRow(arrayOf<Any?>(9L, "+15550102020"))
            addRow(arrayOf<Any?>(999L, "+15550102999"))
        }
        var queryCount = 0
        val query = AndroidProviderThreadParticipantQuery(
            ProviderThreadCursorQuery { uri, _, _, _, _, _ ->
                queryCount += 1
                if (uri.lastPathSegment == "recipients") threadCursor else addressCursor
            },
        )

        val result = query.query(ProviderThreadId(41), CancellationSignal())

        assertNull(result)
        assertEquals(2, queryCount)
        assertTrue(threadCursor.isClosed)
        assertTrue(addressCursor.isClosed)
    }

    private data class QueryCall(
        val uri: Uri,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String?,
        val signal: CancellationSignal,
    )

    private companion object {
        val THREAD_PROJECTION: Array<String> = arrayOf(
            BaseColumns._ID,
            Telephony.Threads.RECIPIENT_IDS,
        )
        val ADDRESS_PROJECTION: Array<String> = arrayOf(
            BaseColumns._ID,
            Telephony.CanonicalAddressesColumns.ADDRESS,
        )
    }
}
