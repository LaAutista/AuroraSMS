// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.ContactDiscoveryResult
import org.aurorasms.core.telephony.DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidContactDiscoveryTest {
    @Test
    fun `query dispatches to injected io context and returns deterministic projection`() = runTest {
        var queried = false
        val discovery = AndroidContactDiscovery(
            permissionChecker = ContactDiscoveryPermissionChecker { true },
            providerQuery = ContactDiscoveryProviderQuery {
                queried = true
                listOf(RawDiscoveredContactRow("+15550102020", "Ada", null))
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = async { discovery.discover("Ada", DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT) }

        assertFalse(queried)
        runCurrent()
        assertTrue(queried)
        assertEquals(
            ParticipantAddress("+15550102020"),
            (result.await() as ContactDiscoveryResult.Available).contacts.single().address,
        )
    }

    @Test
    fun `blank and denied requests never reach provider query`() = runTest {
        var queryCount = 0
        val denied = AndroidContactDiscovery(
            permissionChecker = ContactDiscoveryPermissionChecker { false },
            providerQuery = ContactDiscoveryProviderQuery {
                queryCount += 1
                emptyList()
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            ContactDiscoveryResult.InvalidRequest,
            denied.discover("   ", DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT),
        )
        assertEquals(
            ContactDiscoveryResult.PermissionDenied,
            denied.discover("Ada", DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT),
        )
        assertEquals(0, queryCount)
    }

    @Test
    fun `permission and provider failures become content-free result states`() = runTest {
        val permissionRevoked = discoveryWithQuery(StandardTestDispatcher(testScheduler)) {
            throw SecurityException("private provider detail")
        }
        val providerFailed = discoveryWithQuery(StandardTestDispatcher(testScheduler)) {
            throw IllegalStateException("private provider detail")
        }
        val nullCursor = discoveryWithQuery(StandardTestDispatcher(testScheduler)) { null }

        assertEquals(
            ContactDiscoveryResult.PermissionDenied,
            permissionRevoked.discover("Ada", 1),
        )
        assertEquals(ContactDiscoveryResult.Unavailable, providerFailed.discover("Ada", 1))
        assertEquals(ContactDiscoveryResult.Unavailable, nullCursor.discover("Ada", 1))
    }

    @Test
    fun `coroutine cancellation propagates through provider query`() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val queryCancelled = CompletableDeferred<Unit>()
        val discovery = discoveryWithQuery(StandardTestDispatcher(testScheduler)) {
            queryStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                queryCancelled.complete(Unit)
            }
        }
        val job = launch { discovery.discover("Ada", 1) }
        queryStarted.await()

        job.cancelAndJoin()

        assertTrue(queryCancelled.isCompleted)
    }

    @Test
    fun `projection validates rows deduplicates exact addresses and is order independent`() {
        val oversizedName = "n".repeat(MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS + 1)
        val oversizedPhoto = "p".repeat(MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS + 1)
        val rows = listOf(
            RawDiscoveredContactRow(" +1 (555) 010-0001 ", "Zulu", "content://photo/zulu"),
            RawDiscoveredContactRow("+15550100001", "Ada", "content://photo/ada"),
            RawDiscoveredContactRow("+15550100002", "  Grace  ", oversizedPhoto),
            RawDiscoveredContactRow("+15550100003", oversizedName, " content://photo/three "),
            RawDiscoveredContactRow(" ", "Invalid", null),
            RawDiscoveredContactRow("+", "Invalid", null),
            RawDiscoveredContactRow("+1555\u00000100004", "Invalid", null),
            RawDiscoveredContactRow("1".repeat(ParticipantAddress.MAX_ADDRESS_CHARACTERS + 1), "Invalid", null),
        )

        val forward = projectContactDiscoveryRows(rows, resultLimit = 20)
        val reverse = projectContactDiscoveryRows(rows.reversed(), resultLimit = 20)

        assertEquals(forward, reverse)
        assertEquals(
            listOf("+15550100001", "+15550100002", "+15550100003"),
            forward.contacts.map { it.address.value },
        )
        assertEquals("Ada", forward.contacts[0].displayName)
        assertEquals("Grace", forward.contacts[1].displayName)
        assertNull(forward.contacts[1].photoUri)
        assertNull(forward.contacts[2].displayName)
        assertEquals("content://photo/three", forward.contacts[2].photoUri)
        assertFalse(forward.truncated)
        assertEquals("RawDiscoveredContactRow(REDACTED)", rows.first().toString())
    }

    @Test
    fun `projection inspects at most one row beyond requested results`() {
        val rows = (1..100).map { index ->
            RawDiscoveredContactRow(
                address = "+1555${index.toString().padStart(7, '0')}",
                displayName = "Contact ${index.toString().padStart(3, '0')}",
                photoUri = null,
            )
        }

        val result = projectContactDiscoveryRows(rows, resultLimit = 3)

        assertEquals(3, result.contacts.size)
        assertEquals(listOf("Contact 001", "Contact 002", "Contact 003"), result.contacts.map { it.displayName })
        assertTrue(result.truncated)
    }

    private fun discoveryWithQuery(
        dispatcher: CoroutineDispatcher,
        query: suspend (ValidatedContactDiscoveryRequest) -> List<RawDiscoveredContactRow>?,
    ): AndroidContactDiscovery = AndroidContactDiscovery(
        permissionChecker = ContactDiscoveryPermissionChecker { true },
        providerQuery = ContactDiscoveryProviderQuery(query),
        ioDispatcher = dispatcher,
    )
}
