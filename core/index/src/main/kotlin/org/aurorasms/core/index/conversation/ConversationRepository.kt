// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.model.ProviderThreadId

/** A content-free signal; callers must coalesce and re-read one bounded page. */
data object ConversationInvalidation

interface ConversationRepository {
    val invalidations: Flow<ConversationInvalidation>

    suspend fun loadInbox(request: ConversationPageRequest = ConversationPageRequest()): ConversationPageResult

    /** Reads one exact bounded summary; callers never enumerate the inbox to find a thread. */
    suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult
}

sealed interface ConversationLookupResult {
    data class Found(
        val summary: ConversationSummary,
        val coverage: IndexCoverage,
    ) : ConversationLookupResult {
        override fun toString(): String = "ConversationLookupResult.Found(coverage=$coverage, REDACTED)"
    }

    data class Missing(val coverage: IndexCoverage) : ConversationLookupResult

    data class StorageUnavailable(val coverage: IndexCoverage) : ConversationLookupResult
}
