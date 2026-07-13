// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import kotlinx.coroutines.flow.Flow

/** A content-free signal; callers must coalesce and re-read one bounded page. */
data object ConversationInvalidation

interface ConversationRepository {
    val invalidations: Flow<ConversationInvalidation>

    suspend fun loadInbox(request: ConversationPageRequest = ConversationPageRequest()): ConversationPageResult
}
