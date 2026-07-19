// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.SearchCursor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.index.SearchValidationFailure
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelineMessageContent
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.ResolvedContact

enum class ConversationLoadFailure {
    STORAGE,
    MISSING_THREAD,
}

sealed interface InboxUiState {
    data object Loading : InboxUiState

    data class Ready(
        val window: BoundedInboxWindow,
        val coverage: IndexCoverage,
        val contacts: Map<ParticipantAddress, ResolvedContact>,
        val loadingOlder: Boolean,
        val restoreAnchor: WindowAnchor<org.aurorasms.core.model.ProviderThreadId>? = null,
    ) : InboxUiState {
        override fun toString(): String =
            "InboxUiState.Ready(itemCount=${window.items.size}, contactCount=${contacts.size}, " +
                "loadingOlder=$loadingOlder, coverage=$coverage, REDACTED)"
    }

    data class Failed(
        val failure: ConversationLoadFailure,
        val coverage: IndexCoverage,
    ) : InboxUiState
}

sealed interface ThreadUiState {
    data object Loading : ThreadUiState

    data class Ready(
        val window: BoundedThreadWindow,
        val coverage: IndexCoverage,
        val conversation: ConversationSummary?,
        val verifiedConversationIdentity: VerifiedConversationIdentity? = null,
        val verifiedConversationIdentityResolved: Boolean = false,
        val activeSubscription: ActiveSubscription?,
        val activeSubscriptions: List<ActiveSubscription> =
            activeSubscription?.let(::listOf).orEmpty(),
        val contacts: Map<ParticipantAddress, ResolvedContact>,
        val loadingOlder: Boolean,
        val loadingNewer: Boolean,
        val highlightedMessageId: ProviderMessageId? = null,
        val restoreAnchor: WindowAnchor<ProviderMessageId>? = null,
        val expandedMessageId: ProviderMessageId? = null,
        val expandedContent: TimelineMessageContent? = null,
        val expandingMessage: Boolean = false,
        val expansionFailed: Boolean = false,
    ) : ThreadUiState {
        init {
            require(verifiedConversationIdentity == null || verifiedConversationIdentityResolved) {
                "A verified conversation identity requires a completed identity lookup"
            }
            require(activeSubscriptions.distinctBy { it.id }.size == activeSubscriptions.size) {
                "Active subscriptions must be unique"
            }
            require(activeSubscription == null || activeSubscriptions.any {
                it.id == activeSubscription.id
            }) {
                "The associated subscription must be active"
            }
            require(expandedContent == null || expandedContent.providerMessageId == expandedMessageId)
            require(
                window.retainedTextUnits + expandedContent.retainedTextUnits() <=
                    MAXIMUM_RETAINED_THREAD_TEXT_UNITS,
            ) { "Expanded content must share the bounded thread text budget" }
        }

        override fun toString(): String =
            "ThreadUiState.Ready(itemCount=${window.items.size}, contactCount=${contacts.size}, " +
                "hasConversation=${conversation != null}, " +
                "hasVerifiedConversationIdentity=${verifiedConversationIdentity != null}, " +
                "verifiedConversationIdentityResolved=$verifiedConversationIdentityResolved, " +
                "hasSubscription=${activeSubscription != null}, " +
                "loadingOlder=$loadingOlder, loadingNewer=$loadingNewer, " +
                "hasExpandedContent=${expandedContent != null}, expansionFailed=$expansionFailed, " +
                "coverage=$coverage, REDACTED)"
    }

    data class Failed(
        val failure: ConversationLoadFailure,
        val coverage: IndexCoverage,
    ) : ThreadUiState
}

private fun TimelineMessageContent?.retainedTextUnits(): Int =
    this?.let { (it.body?.length ?: 0) + (it.subject?.length ?: 0) } ?: 0

sealed interface SearchUiState {
    val query: String

    data class Empty(override val query: String = "") : SearchUiState
    data class Searching(override val query: String) : SearchUiState

    data class Page(
        override val query: String,
        val items: List<SearchHit>,
        val next: SearchCursor?,
        val coverage: IndexCoverage,
        val loadingMore: Boolean = false,
    ) : SearchUiState {
        init {
            require(items.size <= MAXIMUM_RETAINED_SEARCH_ROWS) { "Search UI rows must remain bounded" }
        }

        override fun toString(): String =
            "SearchUiState.Page(itemCount=${items.size}, hasNext=${next != null}, " +
                "loadingMore=$loadingMore, coverage=$coverage, REDACTED)"
    }

    data class Invalid(
        override val query: String,
        val reason: SearchValidationFailure,
    ) : SearchUiState
}

data class ComposerUiState(
    val body: String,
    val saving: Boolean,
    val failed: Boolean,
    val sendState: ComposerSendState = ComposerSendState.UNAVAILABLE,
    val unavailableReason: ComposerUnavailableReason? = null,
    val segmentCount: Int? = null,
    val scheduleState: ComposerScheduleState = ComposerScheduleState.None,
    val sendDelayDueTimestampMillis: Long? = null,
) {
    init {
        require(segmentCount == null || segmentCount > 0) { "SMS segment count must be positive" }
        require(sendState != ComposerSendState.READY || unavailableReason == null) {
            "A ready composer cannot have an unavailable reason"
        }
        require(sendState == ComposerSendState.UNAVAILABLE || unavailableReason == null) {
            "Only an unavailable composer can have an unavailable reason"
        }
        require(
            (sendState == ComposerSendState.DELAY_PENDING ||
                sendState == ComposerSendState.DELAY_REVIEW) ==
                (sendDelayDueTimestampMillis != null),
        ) { "A delayed-send UI phase requires its due timestamp" }
    }

    override fun toString(): String =
        "ComposerUiState(bodyLength=${body.length}, saving=$saving, failed=$failed, " +
            "sendState=$sendState, unavailableReason=$unavailableReason, " +
            "segmentCount=$segmentCount, scheduleState=$scheduleState, " +
            "hasSendDelay=${sendDelayDueTimestampMillis != null}, REDACTED)"
}

sealed interface ComposerScheduleState {
    data object Loading : ComposerScheduleState
    data object None : ComposerScheduleState
    data class Pending(val dueTimestampMillis: Long, val exact: Boolean) : ComposerScheduleState {
        init { require(dueTimestampMillis >= 0L) }
        override fun toString(): String = "ComposerScheduleState.Pending(exact=$exact, REDACTED)"
    }
    data class Dispatching(val dueTimestampMillis: Long) : ComposerScheduleState {
        init { require(dueTimestampMillis >= 0L) }
        override fun toString(): String = "ComposerScheduleState.Dispatching(REDACTED)"
    }
    data class ReviewRequired(val dueTimestampMillis: Long) : ComposerScheduleState {
        init { require(dueTimestampMillis >= 0L) }
        override fun toString(): String = "ComposerScheduleState.ReviewRequired(REDACTED)"
    }
}

data class ConversationSubscriptionUiState(
    val options: List<ActiveSubscription> = emptyList(),
    val selected: ActiveSubscription? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val rememberedSelectionUnavailable: Boolean = false,
    val storageFailed: Boolean = false,
) {
    init {
        require(options.distinctBy { it.id }.size == options.size) {
            "Conversation subscription options must be unique"
        }
        require(selected == null || options.any { it.id == selected.id }) {
            "The selected subscription must be an available option"
        }
        require(!rememberedSelectionUnavailable || selected == null) {
            "An unavailable remembered subscription cannot also be selected"
        }
    }

    override fun toString(): String =
        "ConversationSubscriptionUiState(optionCount=${options.size}, " +
            "hasSelection=${selected != null}, loading=$loading, saving=$saving, " +
            "rememberedSelectionUnavailable=$rememberedSelectionUnavailable, " +
            "storageFailed=$storageFailed, REDACTED)"
}

enum class ComposerSendState {
    UNAVAILABLE,
    READY,
    DELAY_PENDING,
    DELAY_REVIEW,
    SENDING,
    KNOWN_UNSENT,
    SUBMISSION_UNKNOWN,
}

enum class ComposerUnavailableReason {
    EMPTY_MESSAGE,
    DRAFT_NOT_DURABLE,
    CONVERSATION_UNVERIFIED,
    GROUP_REQUIRES_MMS,
    SUBSCRIPTION_UNAVAILABLE,
    MULTIPART_UNAVAILABLE,
    RECOVERY_PENDING,
    MESSAGING_UNAVAILABLE,
}

internal fun conversationAddresses(items: List<ConversationSummary>): List<ParticipantAddress> =
    items.take(MAXIMUM_VIEWPORT_CONVERSATIONS).asSequence()
        .flatMap { summary -> sequenceOf(summary.latestSenderAddress) + summary.participants.asSequence() }
        .filterNotNull()
        .distinct()
        .take(MAXIMUM_VIEWPORT_CONTACTS)
        .toList()

internal fun timelineAddresses(items: List<TimelineMessage>): List<ParticipantAddress> =
    items.asSequence()
        .mapNotNull(TimelineMessage::senderAddress)
        .distinct()
        .take(MAXIMUM_VIEWPORT_CONTACTS)
        .toList()

const val MAXIMUM_VIEWPORT_CONVERSATIONS: Int = 50
const val MAXIMUM_VIEWPORT_CONTACTS: Int = 100
const val MAXIMUM_RETAINED_SEARCH_ROWS: Int = 100
