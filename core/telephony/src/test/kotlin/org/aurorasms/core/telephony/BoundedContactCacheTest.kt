// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.internal.BoundedContactCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedContactCacheTest {
    @Test
    fun `negative results are cached and output order preserves duplicates`() = runTest {
        val resolver = RecordingResolver()
        val cache = BoundedContactCache(resolver, backgroundScope)
        val first = address(1)
        val second = address(2)

        val initial = cache.resolve(listOf(first, second, first))
        val repeated = cache.resolve(listOf(second, first))

        assertEquals(listOf(first, second, first), initial.map(ResolvedContact::address))
        assertTrue(initial.all { it.displayName == null && it.photoUri == null })
        assertEquals(listOf(second, first), repeated.map(ResolvedContact::address))
        assertEquals(1, resolver.requests.size)
        assertEquals(listOf(first, second), resolver.requests.single())
    }

    @Test
    fun `least recently used metadata is evicted at 512 entries`() = runTest {
        val resolver = RecordingResolver(namesEnabled = true)
        val cache = BoundedContactCache(resolver, backgroundScope)
        val addresses = (1..513).map(::address)

        addresses.chunked(MAXIMUM_CONTACTS_PER_RESOLUTION).forEach { cache.resolve(it) }

        assertEquals(MAXIMUM_CONTACT_CACHE_ENTRIES, cache.size())
        resolver.requests.clear()
        cache.resolve(listOf(addresses.first()))
        assertEquals(listOf(addresses.first()), resolver.requests.single())
        assertEquals(MAXIMUM_CONTACT_CACHE_ENTRIES, cache.size())
    }

    @Test
    fun `overlapping requests share one in flight lookup`() = runTest {
        val resolver = GatedResolver()
        val cache = BoundedContactCache(resolver, backgroundScope)
        val requested = address(8)

        val first = async { cache.resolve(listOf(requested)) }
        runCurrent()
        val second = async { cache.resolve(listOf(requested)) }
        runCurrent()

        assertEquals(1, resolver.requests.size)
        resolver.gate.complete(Unit)
        runCurrent()
        assertEquals("Synthetic 8", first.await().single().displayName)
        assertEquals(first.await(), second.await())
    }

    @Test
    fun `invalidation clears photos and rejects stale in flight results`() = runTest {
        val resolver = GatedResolver()
        val cache = BoundedContactCache(resolver, backgroundScope)
        val requested = address(9)
        val first = async { cache.resolve(listOf(requested)) }
        runCurrent()

        cache.invalidate()

        val invalidated = first.await().single()
        assertEquals(requested, invalidated.address)
        assertNull(invalidated.displayName)
        assertNull(invalidated.photoUri)
        resolver.gate.complete(Unit)
        runCurrent()
        resolver.gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        resolver.namePrefix = "Fresh"

        val refreshed = cache.resolve(listOf(requested)).single()

        assertEquals("Fresh 9", refreshed.displayName)
        assertEquals("content://synthetic/9", refreshed.photoUri)
        assertEquals(2, resolver.requests.size)
    }

    @Test
    fun `one refresh cannot exceed 100 unique contact addresses`() = runTest {
        val cache = BoundedContactCache(RecordingResolver(), backgroundScope)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                cache.resolve((1..101).map(::address))
            }
        }
    }

    @Test
    fun `resolved contact string representation is redacted`() {
        val contact = ResolvedContact(
            address = address(10),
            displayName = "Synthetic private name",
            photoUri = "content://synthetic/private",
        )

        assertEquals("ResolvedContact(REDACTED)", contact.toString())
    }
}

private class RecordingResolver(
    private val namesEnabled: Boolean = false,
) : ContactResolver {
    val requests = mutableListOf<List<ParticipantAddress>>()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> {
        requests += addresses.toList()
        return addresses.map { requested ->
            ResolvedContact(
                address = requested,
                displayName = requested.syntheticNumber().takeIf { namesEnabled }?.let { "Synthetic $it" },
                photoUri = null,
            )
        }
    }
}

private class GatedResolver : ContactResolver {
    val requests = mutableListOf<List<ParticipantAddress>>()
    var gate = CompletableDeferred<Unit>()
    var namePrefix = "Synthetic"

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> {
        requests += addresses.toList()
        gate.await()
        return addresses.map { requested ->
            val number = requested.syntheticNumber()
            ResolvedContact(
                address = requested,
                displayName = "$namePrefix $number",
                photoUri = "content://synthetic/$number",
            )
        }
    }
}

private fun address(number: Int): ParticipantAddress =
    ParticipantAddress("+1555${number.toString().padStart(7, '0')}")

private fun ParticipantAddress.syntheticNumber(): Int = value.takeLast(7).toInt()
