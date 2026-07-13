// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_CACHE_ENTRIES
import org.aurorasms.core.telephony.MAXIMUM_CONTACTS_PER_RESOLUTION
import org.aurorasms.core.telephony.ResolvedContact

internal class BoundedContactCache(
    private val resolver: ContactResolver,
    private val scope: CoroutineScope,
) : ContactCache {
    private val _invalidations = MutableSharedFlow<ContactCacheInvalidation>(extraBufferCapacity = 1)
    override val invalidations: Flow<ContactCacheInvalidation> = _invalidations.asSharedFlow()
    private val mutex = Mutex()
    private val entries = LinkedHashMap<ParticipantAddress, ResolvedContact>(16, 0.75f, true)
    private val inFlight = mutableMapOf<ParticipantAddress, CompletableDeferred<ResolvedContact>>()
    private var epoch = 0L

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> {
        if (addresses.isEmpty()) return emptyList()
        val uniqueAddresses = addresses.distinct()
        require(uniqueAddresses.size <= MAXIMUM_CONTACTS_PER_RESOLUTION) {
            "Contact resolution exceeds the reviewed viewport bound"
        }

        val cached = mutableMapOf<ParticipantAddress, ResolvedContact>()
        val pending = mutableMapOf<ParticipantAddress, Deferred<ResolvedContact>>()
        val owned = mutableMapOf<ParticipantAddress, CompletableDeferred<ResolvedContact>>()
        val batchEpoch = mutex.withLock {
            uniqueAddresses.forEach { address ->
                val cachedContact = entries[address]
                when {
                    cachedContact != null -> cached[address] = cachedContact
                    inFlight[address] != null -> pending[address] = checkNotNull(inFlight[address])
                    else -> {
                        val promise = CompletableDeferred<ResolvedContact>()
                        inFlight[address] = promise
                        pending[address] = promise
                        owned[address] = promise
                    }
                }
            }
            epoch
        }

        if (owned.isNotEmpty()) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                resolveOwned(batchEpoch, owned)
            }
        }

        val completed = pending.mapValues { (_, result) -> result.await() }
        return addresses.map { address ->
            cached[address] ?: checkNotNull(completed[address])
        }
    }

    override suspend fun invalidate() {
        val stale = mutex.withLock {
            epoch += 1L
            entries.clear()
            inFlight.toMap().also { inFlight.clear() }
        }
        stale.forEach { (address, promise) ->
            promise.complete(addressOnly(address))
        }
        _invalidations.tryEmit(ContactCacheInvalidation)
    }

    internal suspend fun size(): Int = mutex.withLock { entries.size }

    private suspend fun resolveOwned(
        batchEpoch: Long,
        owned: Map<ParticipantAddress, CompletableDeferred<ResolvedContact>>,
    ) {
        val addresses = owned.keys.toList()
        val resolved = try {
            val byAddress = resolver.resolve(addresses)
                .asSequence()
                .filter { it.address in owned }
                .associateBy(ResolvedContact::address)
            addresses.associateWith { address -> byAddress[address] ?: addressOnly(address) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                completeBatch(batchEpoch, owned, addresses.associateWith(::addressOnly), cacheResults = false)
            }
            throw cancelled
        } catch (_: RuntimeException) {
            addresses.associateWith(::addressOnly)
        }
        completeBatch(batchEpoch, owned, resolved, cacheResults = true)
    }

    private suspend fun completeBatch(
        batchEpoch: Long,
        owned: Map<ParticipantAddress, CompletableDeferred<ResolvedContact>>,
        resolved: Map<ParticipantAddress, ResolvedContact>,
        cacheResults: Boolean,
    ) {
        val accepted = mutex.withLock {
            val epochMatches = batchEpoch == epoch
            owned.mapValues { (address, promise) ->
                val stillOwned = inFlight[address] === promise
                if (stillOwned) inFlight.remove(address)
                if (stillOwned && epochMatches && cacheResults) {
                    entries[address] = checkNotNull(resolved[address])
                }
                stillOwned && epochMatches
            }.also {
                trimToBound()
            }
        }
        owned.forEach { (address, promise) ->
            val result = if (accepted[address] == true) {
                checkNotNull(resolved[address])
            } else {
                addressOnly(address)
            }
            promise.complete(result)
        }
    }

    private fun trimToBound() {
        while (entries.size > MAXIMUM_CONTACT_CACHE_ENTRIES) {
            val eldest = entries.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

}

private fun addressOnly(address: ParticipantAddress): ResolvedContact =
    ResolvedContact(address = address, displayName = null, photoUri = null)
