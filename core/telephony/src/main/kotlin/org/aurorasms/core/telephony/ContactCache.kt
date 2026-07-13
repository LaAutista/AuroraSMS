// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.internal.BoundedContactCache

/** Bounded, process-local contact metadata; it never owns message content. */
data object ContactCacheInvalidation

interface ContactCache : ContactResolver {
    val invalidations: Flow<ContactCacheInvalidation>

    /** Returns one result per input address, preserving order and duplicates. */
    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact>

    /** Drops names, photo references, negative entries, and stale in-flight results. */
    suspend fun invalidate()
}

fun ContactResolver.boundedContactCache(scope: CoroutineScope): ContactCache =
    BoundedContactCache(resolver = this, scope = scope)

const val MAXIMUM_CONTACT_CACHE_ENTRIES: Int = 512
const val MAXIMUM_CONTACTS_PER_RESOLUTION: Int = 100
