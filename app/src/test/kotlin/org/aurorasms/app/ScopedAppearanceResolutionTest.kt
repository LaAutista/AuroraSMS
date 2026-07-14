// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.appearance.AppAppearanceOverride
import org.aurorasms.app.appearance.AppAppearanceProfile
import org.aurorasms.app.appearance.AppAppearanceState
import org.aurorasms.app.appearance.profileFor
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.feature.conversations.BoundedThreadWindow
import org.aurorasms.feature.conversations.ThreadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedAppearanceResolutionTest {
    @Test
    fun conversationResolutionFallsThroughGlobalThreadThenActiveThenCanonical() {
        val active = AuroraMaterialProfile(palette = AuroraPalette.AMOLED_BLACK)
        val global = AuroraMaterialProfile(palette = AuroraPalette.LIGHT)
        val conversation = AuroraMaterialProfile(hueDegrees = 42)
        val appearance = AppAppearanceState(
            profiles = listOf(
                appProfile(7L, "Active", active),
                appProfile(8L, "Global", global),
                appProfile(9L, "Conversation", conversation),
            ),
            activeProfileId = 7L,
            activeProfile = active,
            snapshotRevision = 1L,
            profileSnapshotReady = true,
        )

        val globalResolved = appearance.profileFor(AppAppearanceOverride(8L, 1L), active)
        assertEquals(global, globalResolved)
        assertEquals(
            conversation,
            appearance.profileFor(AppAppearanceOverride(9L, 1L), globalResolved),
        )
        assertEquals(
            global,
            appearance.profileFor(AppAppearanceOverride(99L, 1L), globalResolved),
        )
        assertEquals(
            active,
            appearance.profileFor(AppAppearanceOverride(99L, 1L), active),
        )
        assertEquals(
            AuroraMaterialProfile.Default,
            AppAppearanceState.Default.profileFor(null, AuroraMaterialProfile.Default),
        )
    }

    @Test
    fun conversationScopeRequiresCompleteExactBoundedParticipants() {
        val providerThreadId = ProviderThreadId(41L)
        val ready = readyState(
            summary = summary(
                providerThreadId = providerThreadId,
                participants = listOf(ParticipantAddress("+15550000001")),
            ),
        )

        assertNotNull(trustedConversationAppearanceScope(providerThreadId, ready))
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(coverage = ready.coverage.copy(pendingChanges = true)),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(conversation = ready.conversation?.copy(participantsTruncated = true)),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(conversation = ready.conversation?.copy(indexedParticipantCount = 2)),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(conversation = ready.conversation?.copy(providerThreadId = ProviderThreadId(42L))),
            ),
        )
    }

    @Test
    fun sameThreadParticipantChangeProducesANewPrivateRestorationTarget() {
        val providerThreadId = ProviderThreadId(41L)
        val first = trustedConversationAppearanceScope(
            providerThreadId,
            readyState(
                summary(
                    providerThreadId,
                    listOf(ParticipantAddress("+15550000001")),
                ),
            ),
        )
        val second = trustedConversationAppearanceScope(
            providerThreadId,
            readyState(
                summary(
                    providerThreadId,
                    listOf(ParticipantAddress("+15550000002")),
                ),
            ),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
        assertTrue(
            checkNotNull(first).privateScopedAppearanceRestorationKey() !=
                checkNotNull(second).privateScopedAppearanceRestorationKey(),
        )
    }

    private fun appProfile(
        id: Long,
        name: String,
        profile: AuroraMaterialProfile,
    ): AppAppearanceProfile = AppAppearanceProfile(
        id = id,
        revision = 1L,
        name = name,
        profile = profile,
        focalXPermill = 500,
        focalYPermill = 500,
    )

    private fun readyState(summary: ConversationSummary): ThreadUiState.Ready = ThreadUiState.Ready(
        window = BoundedThreadWindow.EMPTY,
        coverage = VERIFIED_COVERAGE,
        conversation = summary,
        activeSubscription = null,
        contacts = emptyMap(),
        loadingOlder = false,
        loadingNewer = false,
    )

    private fun summary(
        providerThreadId: ProviderThreadId,
        participants: List<ParticipantAddress>,
    ): ConversationSummary = ConversationSummary(
        providerThreadId = providerThreadId,
        latestLocalRowId = 1L,
        latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
        latestTimestampMillis = 1L,
        latestSentTimestampMillis = null,
        latestDirection = MessageDirection.INCOMING,
        latestBox = MessageBox.INBOX,
        latestStatus = MessageStatus.COMPLETE,
        latestSubscriptionId = null,
        latestSenderAddress = null,
        latestSnippet = null,
        latestAttachmentCount = 0,
        latestAttachmentTypeSummary = "",
        latestRead = true,
        indexedMessageCount = 1L,
        indexedUnreadCount = 0L,
        participants = participants,
        indexedParticipantCount = participants.size,
        participantsTruncated = false,
    )

    private companion object {
        val VERIFIED_COVERAGE = IndexCoverage(
            generationId = 1L,
            state = IndexRunState.COMPLETE,
            indexedMessageCount = 1L,
            smsExhausted = true,
            mmsExhausted = true,
            pendingChanges = false,
        )
    }
}
