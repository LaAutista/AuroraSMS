// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.appearance.AppAppearanceOverride
import org.aurorasms.app.appearance.AppAppearanceProfile
import org.aurorasms.app.appearance.AppAppearanceState
import org.aurorasms.app.appearance.profileFor
import org.aurorasms.app.message.MessageSignatureConversationKey
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.feature.conversations.BoundedThreadWindow
import org.aurorasms.feature.conversations.ConversationLoadFailure
import org.aurorasms.feature.conversations.ThreadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun conversationScopeRequiresCompleteExactVerifiedIdentity() {
        val providerThreadId = ProviderThreadId(41L)
        val verifiedParticipants = (1..9).map { index ->
            ParticipantAddress("verified-$index@example.invalid")
        }
        val ready = readyState(
            summary = summary(
                providerThreadId = ProviderThreadId(99L),
                participants = verifiedParticipants.take(8),
                indexedParticipantCount = verifiedParticipants.size,
                participantsTruncated = false,
            ),
            verifiedIdentity = identityFor(providerThreadId, verifiedParticipants),
        )

        assertNotNull(trustedConversationAppearanceScope(providerThreadId, ready))
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(
                    verifiedConversationIdentity = null,
                    verifiedConversationIdentityResolved = false,
                ),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(coverage = ready.coverage.copy(pendingChanges = true)),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(verifiedConversationIdentity = null),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(coverage = ready.coverage.copy(generationId = 2L)),
            ),
        )
        assertNull(
            trustedConversationAppearanceScope(
                providerThreadId,
                ready.copy(
                    verifiedConversationIdentity = identityFor(
                        providerThreadId = ProviderThreadId(42L),
                        participants = verifiedParticipants,
                    ),
                ),
            ),
        )
    }

    @Test
    fun sameThreadParticipantChangeProducesANewPrivateRestorationTarget() {
        val providerThreadId = ProviderThreadId(41L)
        val first = trustedConversationAppearanceScope(
            providerThreadId,
            readyState(
                summary(providerThreadId, listOf(ParticipantAddress("+15550000001"))),
                identityFor(
                    providerThreadId,
                    listOf(ParticipantAddress("+15550000001")),
                ),
            ),
        )
        val second = trustedConversationAppearanceScope(
            providerThreadId,
            readyState(
                summary(providerThreadId, listOf(ParticipantAddress("+15550000001"))),
                identityFor(
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

    @Test
    fun signatureKeyRequiresCompleteIdentityAndCanonicalizesParticipantOrder() {
        val providerThreadId = ProviderThreadId(41L)
        val firstParticipant = ParticipantAddress("+15550000001")
        val secondParticipant = ParticipantAddress("+15550000002")
        val ready = readyState(
            summary(providerThreadId, listOf(firstParticipant, secondParticipant)),
            identityFor(providerThreadId, listOf(firstParticipant, secondParticipant)),
        )

        val trusted = trustedMessageSignatureConversationKey(providerThreadId, ready)
        assertNotNull(trusted)
        assertEquals(
            trusted,
            MessageSignatureConversationKey.fromParticipants(
                listOf(secondParticipant, firstParticipant),
            ),
        )
        assertFalse(checkNotNull(trusted).toString().contains(firstParticipant.value))
        assertNull(
            trustedMessageSignatureConversationKey(
                providerThreadId,
                ready.copy(coverage = ready.coverage.copy(pendingChanges = true)),
            ),
        )
        assertNull(
            trustedMessageSignatureConversationKey(
                providerThreadId,
                ready.copy(
                    verifiedConversationIdentity = null,
                    verifiedConversationIdentityResolved = false,
                ),
            ),
        )
    }

    @Test
    fun openConversationEditorClearsOnlyAfterCompletedTargetChangeOrLoss() {
        val original = "conversation:original"
        val providerThreadId = ProviderThreadId(41L)
        val unresolvedReady = readyState(
            summary = summary(providerThreadId, listOf(ParticipantAddress("+15550000001"))),
            verifiedIdentity = null,
            identityResolved = false,
        )
        val resolvedMissingReady = unresolvedReady.copy(
            verifiedConversationIdentityResolved = true,
        )

        assertFalse(isConversationIdentityLookupComplete(ThreadUiState.Loading))
        assertFalse(isConversationIdentityLookupComplete(unresolvedReady))
        assertTrue(isConversationIdentityLookupComplete(resolvedMissingReady))
        assertTrue(
            isConversationIdentityLookupComplete(
                ThreadUiState.Failed(ConversationLoadFailure.STORAGE, VERIFIED_COVERAGE),
            ),
        )
        assertFalse(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = original,
                currentPrivateRestorationKey = null,
                identityLookupComplete = isConversationIdentityLookupComplete(unresolvedReady),
            ),
        )
        assertTrue(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = original,
                currentPrivateRestorationKey = null,
                identityLookupComplete = isConversationIdentityLookupComplete(resolvedMissingReady),
            ),
        )
        assertFalse(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = original,
                currentPrivateRestorationKey = original,
                identityLookupComplete = true,
            ),
        )
        assertTrue(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = original,
                currentPrivateRestorationKey = "conversation:changed",
                identityLookupComplete = true,
            ),
        )
        assertTrue(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = original,
                currentPrivateRestorationKey = null,
                identityLookupComplete = true,
            ),
        )
        assertFalse(
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = null,
                currentPrivateRestorationKey = null,
                identityLookupComplete = true,
            ),
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

    private fun readyState(
        summary: ConversationSummary,
        verifiedIdentity: VerifiedConversationIdentity? = identityFor(
            summary.providerThreadId,
            summary.participants,
        ),
        identityResolved: Boolean = true,
    ): ThreadUiState.Ready = ThreadUiState.Ready(
        window = BoundedThreadWindow.EMPTY,
        coverage = VERIFIED_COVERAGE,
        conversation = summary,
        verifiedConversationIdentity = verifiedIdentity,
        verifiedConversationIdentityResolved = identityResolved,
        activeSubscription = null,
        contacts = emptyMap(),
        loadingOlder = false,
        loadingNewer = false,
    )

    private fun summary(
        providerThreadId: ProviderThreadId,
        participants: List<ParticipantAddress>,
        indexedParticipantCount: Int = participants.size,
        participantsTruncated: Boolean = false,
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
        indexedParticipantCount = indexedParticipantCount,
        participantsTruncated = participantsTruncated,
    )

    private fun identityFor(
        providerThreadId: ProviderThreadId,
        participants: List<ParticipantAddress>,
        generationId: Long = 1L,
    ): VerifiedConversationIdentity = VerifiedConversationIdentity(
        providerThreadId = providerThreadId,
        generationId = generationId,
        participants = participants,
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
