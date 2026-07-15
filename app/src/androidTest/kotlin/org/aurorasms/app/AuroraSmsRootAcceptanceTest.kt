// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.aurorasms.app.appearance.AppearanceController
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_APPLY_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_CANCEL_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_RESET_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_SELECTOR_TEST_TAG
import org.aurorasms.app.appearance.THEME_STUDIO_SCREEN_TEST_TAG
import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.index.SearchPage
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult
import org.aurorasms.core.index.conversation.ConversationInvalidation
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPage
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearancePalette
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileName
import org.aurorasms.core.state.AppearanceProfileRepository
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.NewAppearanceProfile
import org.aurorasms.core.state.NewDraft
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.feature.conversations.AttachmentPreviewResult
import org.aurorasms.feature.conversations.BoundedPreviewLoader
import org.aurorasms.feature.conversations.COMPOSER_TEST_TAG
import org.aurorasms.feature.conversations.CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_LIST_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCREEN_TEST_TAG
import org.aurorasms.feature.conversations.SEARCH_FIELD_TEST_TAG
import org.aurorasms.feature.conversations.SEARCH_HIT_TEST_TAG
import org.aurorasms.feature.conversations.SEARCH_SCREEN_TEST_TAG
import org.aurorasms.feature.conversations.THREAD_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.THREAD_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.THREAD_LIST_TEST_TAG
import org.aurorasms.feature.conversations.THREAD_SCREEN_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuroraSmsRootAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun clearStaleHarness() {
        AuroraSmsRootTestHarnessRegistry.clear()
    }

    @After
    fun clearHarness() {
        AuroraSmsRootTestHarnessRegistry.clear()
    }

    @Test
    fun inboxAndGlobalModalsDoNotReloadInboxPresentation() {
        val fixture = SyntheticFixture()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) { fixture.conversations.inboxLoadCount.get() == 1 }
            val initialInboxLoads = fixture.conversations.inboxLoadCount.get()
            compose.onNodeWithTag(INBOX_LIST_TEST_TAG).performScrollToIndex(INBOX_ANCHOR_INDEX)
            compose.onNodeWithText("Synthetic inbox preview 16").assertIsDisplayed()

            openInboxModal(INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG)
            compose.onNodeWithTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG).performClick()
            waitForDialogToClose()

            openInboxModal(INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG)
            selectDaylight()
            compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
            waitForDialogToClose()

            openInboxModal(INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG)
            compose.onNodeWithTag(SCOPED_APPEARANCE_RESET_TEST_TAG).performClick()
            compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
            waitForDialogToClose()

            openInboxModal(CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG)
            selectDaylight()
            onView(isRoot()).inRoot(isDialog()).perform(pressBack())
            waitForDialogToClose()

            compose.onNodeWithTag(INBOX_SCREEN_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithText("Synthetic inbox preview 16").assertIsDisplayed()
            compose.onNodeWithTag(THEME_STUDIO_SCREEN_TEST_TAG).assertDoesNotExist()
            assertEquals(initialInboxLoads, fixture.conversations.inboxLoadCount.get())
            assertNull(
                fixture.appearance.overrideFor(
                    AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD),
                ),
            )
        }
    }

    @Test
    fun exactAnchorThreadRestoresModalDraftComposerAndSearchQuery() {
        val fixture = SyntheticFixture()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(org.aurorasms.feature.conversations.INBOX_SEARCH_ACTION_TEST_TAG)
                .performClick()
            waitForTag(SEARCH_SCREEN_TEST_TAG)
            compose.onNodeWithTag(SEARCH_FIELD_TEST_TAG).performTextReplacement(SYNTHETIC_QUERY)
            waitForTag(SEARCH_HIT_TEST_TAG)
            compose.onNodeWithTag(SEARCH_HIT_TEST_TAG).performClick()

            waitForTag(THREAD_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) { fixture.index.anchorLoadCount.get() == 1 }
            waitForTag(THREAD_LIST_TEST_TAG)
            waitForTag(THREAD_MORE_ACTION_TEST_TAG)
            compose.onNodeWithTag(THREAD_LIST_TEST_TAG).performScrollToIndex(THREAD_ANCHOR_INDEX)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).performTextReplacement(SYNTHETIC_DRAFT)
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)

            val anchorLoadsBeforeModal = fixture.index.anchorLoadCount.get()
            val timelineLoadsBeforeModal = fixture.timeline.latestLoadCount.get()
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).performClick()
            waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            selectDaylight()
            compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains(DAYLIGHT_NAME)
            assertEquals(anchorLoadsBeforeModal, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeModal, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
            waitForDialogToClose()
            assertEquals(anchorLoadsBeforeModal, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeModal, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)

            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).performClick()
            waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            selectProfile(EVENING_ID)
            compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains(EVENING_NAME)
            assertEquals(anchorLoadsBeforeModal, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeModal, fixture.timeline.latestLoadCount.get())
            assertEquals(
                AppearanceProfileId(DAYLIGHT_ID),
                fixture.appearance.overrideFor(SYNTHETIC_CONVERSATION_SCOPE)?.profileId,
            )

            val anchorLoadsBeforeRecreation = fixture.index.anchorLoadCount.get()
            val recreationLookup = fixture.conversations.deferNextExactLookup()
            scenario.recreate()

            compose.waitUntil(TIMEOUT_MILLIS) { recreationLookup.requested }
            waitForTag(THREAD_SCREEN_TEST_TAG)
            waitForTag(THREAD_LIST_TEST_TAG)
            compose.onNodeWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.index.anchorLoadCount.get() == anchorLoadsBeforeRecreation + 1
            }
            assertEquals(0, fixture.timeline.latestLoadCount.get())
            assertEquals(
                AppearanceProfileId(DAYLIGHT_ID),
                fixture.appearance.overrideFor(SYNTHETIC_CONVERSATION_SCOPE)?.profileId,
            )

            recreationLookup.release()

            waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains(EVENING_NAME)
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            assertEquals(anchorLoadsBeforeRecreation + 1, fixture.index.anchorLoadCount.get())
            assertEquals(0, fixture.timeline.latestLoadCount.get())
            assertEquals(
                AppearanceProfileId(DAYLIGHT_ID),
                fixture.appearance.overrideFor(SYNTHETIC_CONVERSATION_SCOPE)?.profileId,
            )

            compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
            waitForDialogToClose()
            assertEquals(anchorLoadsBeforeRecreation + 1, fixture.index.anchorLoadCount.get())
            assertEquals(0, fixture.timeline.latestLoadCount.get())
            assertEquals(
                AppearanceProfileId(EVENING_ID),
                fixture.appearance.overrideFor(SYNTHETIC_CONVERSATION_SCOPE)?.profileId,
            )
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithTag(THREAD_LIST_TEST_TAG).performScrollToIndex(THREAD_REENTRY_STALE_INDEX)
            compose.onNodeWithText("Synthetic anchor row 10").assertIsDisplayed()
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            waitForTag(SEARCH_SCREEN_TEST_TAG)
            compose.onNodeWithTag(SEARCH_FIELD_TEST_TAG).assertTextContains(SYNTHETIC_QUERY)
            compose.onNodeWithTag(THEME_STUDIO_SCREEN_TEST_TAG).assertDoesNotExist()
            val anchorLoadsBeforeReentry = fixture.index.anchorLoadCount.get()
            compose.onNodeWithTag(SEARCH_HIT_TEST_TAG).performClick()
            waitForTag(THREAD_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.index.anchorLoadCount.get() == anchorLoadsBeforeReentry + 1
            }
            waitForTag(THREAD_LIST_TEST_TAG)
            compose.onNodeWithText(SYNTHETIC_EXACT_ANCHOR).assertIsDisplayed()
        }
    }

    @Test
    fun nineParticipantIdentityIsEligibleAndInvalidationDismissesItsOpenEditor() {
        val fixture = SyntheticFixture()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(org.aurorasms.feature.conversations.INBOX_SEARCH_ACTION_TEST_TAG)
                .performClick()
            waitForTag(SEARCH_SCREEN_TEST_TAG)
            compose.onNodeWithTag(SEARCH_FIELD_TEST_TAG).performTextReplacement(SYNTHETIC_QUERY)
            waitForTag(SEARCH_HIT_TEST_TAG)
            compose.onNodeWithTag(SEARCH_HIT_TEST_TAG).performClick()

            waitForTag(THREAD_SCREEN_TEST_TAG)
            waitForTag(THREAD_MORE_ACTION_TEST_TAG)
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
            waitForTag(THREAD_APPEARANCE_ACTION_TEST_TAG)
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).performClick()
            waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)

            fixture.conversations.invalidateVerifiedIdentity()

            waitForDialogToClose()
            compose.onNodeWithTag(THREAD_SCREEN_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).assertDoesNotExist()
        }
    }

    private fun openInboxModal(actionTag: String) {
        compose.onNodeWithTag(INBOX_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(actionTag).performClick()
        waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(org.aurorasms.app.appearance.SCOPED_APPEARANCE_LOADING_TEST_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun selectDaylight() {
        selectProfile(DAYLIGHT_ID)
    }

    private fun selectProfile(profileId: Long) {
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).performClick()
        compose.onNodeWithTag("$SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX$profileId").performClick()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDialogToClose() {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).fetchSemanticsNodes().isEmpty()
        }
    }
}

private class SyntheticFixture : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val conversations = SyntheticConversationRepository()
    val index = SyntheticMessageIndex()
    val timeline = RejectingTimelineRepository()
    val appearance = InMemoryAppearanceRepository()
    private val services = SyntheticRootServices(
        scope = scope,
        conversations = conversations,
        index = index,
        timeline = timeline,
    )
    private val controller = AppearanceController(appearance, scope) { 20_000L }
    val harness = AuroraSmsRootTestHarness(
        services = services,
        appearanceController = controller,
        onClear = ::close,
    )

    override fun close() {
        services.close()
        scope.cancel()
    }
}

private class SyntheticRootServices(
    private val scope: CoroutineScope,
    conversations: SyntheticConversationRepository,
    index: SyntheticMessageIndex,
    timeline: RejectingTimelineRepository,
) : AuroraSmsRootServices, AutoCloseable {
    private val drafts = InMemoryDraftRepository()
    private val clock = AtomicLong(30_000L)
    private val writers = ConcurrentHashMap.newKeySet<SerializedDraftWriter>()

    override val conversationRepository: ConversationRepository = conversations
    override val threadTimelineRepository: ThreadTimelineRepository = timeline
    override val messageIndex: MessageIndex = index
    override val contactCache: ContactCache = SyntheticContactCache
    override val subscriptionRepository: SubscriptionRepository = SyntheticSubscriptions
    override val mmsAttachmentRepository: MmsAttachmentRepository = RejectingAttachments
    override val previewLoader: BoundedPreviewLoader = RejectingPreviewLoader

    override fun createDraftWriter(
        identity: DraftIdentity,
        restoredUnacknowledged: DraftEditorContent?,
    ): SerializedDraftWriter = SerializedDraftWriter(
        repository = drafts,
        identity = identity,
        scope = scope,
        restoredUnacknowledged = restoredUnacknowledged,
        nowMillis = clock::incrementAndGet,
    ).also(writers::add)

    override fun releaseDraftWriter(writer: SerializedDraftWriter) {
        scope.launch {
            try {
                writer.flush()
            } finally {
                writer.close()
                writers.remove(writer)
            }
        }
    }

    override fun close() {
        writers.toList().forEach(SerializedDraftWriter::close)
        writers.clear()
    }
}

private class SyntheticConversationRepository : ConversationRepository {
    val inboxLoadCount = AtomicInteger()
    private val inbox = (1..30).map(::syntheticInboxSummary)
    private val thread = syntheticThreadSummary()
    private val mutableInvalidations = MutableSharedFlow<ConversationInvalidation>(extraBufferCapacity = 1)

    @Volatile
    private var verifiedIdentityAvailable = true
    private val deferredLookupLock = Any()
    private var nextDeferredExactLookup: DeferredExactConversationLookup? = null

    override val invalidations: Flow<ConversationInvalidation> = mutableInvalidations

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult {
        inboxLoadCount.incrementAndGet()
        return ConversationPageResult.Page(
            ConversationPage(
                items = inbox.take(request.limit),
                next = null,
                direction = request.direction,
                coverage = COMPLETE_COVERAGE,
            ),
        )
    }

    override suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult {
        if (providerThreadId != SYNTHETIC_THREAD_ID) {
            return ConversationLookupResult.Missing(COMPLETE_COVERAGE)
        }
        synchronized(deferredLookupLock) {
            nextDeferredExactLookup.also { nextDeferredExactLookup = null }
        }?.awaitRelease()
        return ConversationLookupResult.Found(
            summary = thread,
            coverage = COMPLETE_COVERAGE,
            verifiedIdentity = SYNTHETIC_VERIFIED_IDENTITY.takeIf { verifiedIdentityAvailable },
        )
    }

    fun deferNextExactLookup(): DeferredExactConversationLookup = synchronized(deferredLookupLock) {
        check(nextDeferredExactLookup == null)
        DeferredExactConversationLookup().also { nextDeferredExactLookup = it }
    }

    fun invalidateVerifiedIdentity() {
        verifiedIdentityAvailable = false
        check(mutableInvalidations.tryEmit(ConversationInvalidation))
    }
}

private class DeferredExactConversationLookup {
    private val requestObserved = CompletableDeferred<Unit>()
    private val releaseSignal = CompletableDeferred<Unit>()

    val requested: Boolean
        get() = requestObserved.isCompleted

    suspend fun awaitRelease() {
        requestObserved.complete(Unit)
        releaseSignal.await()
    }

    fun release() {
        check(releaseSignal.complete(Unit))
    }
}

private class SyntheticMessageIndex : MessageIndex {
    val anchorLoadCount = AtomicInteger()
    private val anchorWindow = (0 until ANCHOR_WINDOW_SIZE).map(::syntheticAnchorHit)
    private val exact = anchorWindow[ANCHOR_POSITION]

    override suspend fun coverage(): IndexCoverage = COMPLETE_COVERAGE

    override suspend fun search(request: SearchRequest): SearchResult = if (request.rawQuery.isBlank()) {
        SearchResult.NoQuery
    } else {
        SearchResult.Page(SearchPage(listOf(exact), next = null, coverage = COMPLETE_COVERAGE))
    }

    override suspend fun loadAnchor(anchor: SearchAnchor, halfWindow: Int): AnchorWindowResult {
        anchorLoadCount.incrementAndGet()
        if (anchor != expectedAnchor || halfWindow <= 0) {
            return AnchorWindowResult.NotFound(COMPLETE_COVERAGE)
        }
        return AnchorWindowResult.Found(
            messages = anchorWindow,
            highlightedLocalRowId = exact.localRowId,
            anchorPosition = ANCHOR_POSITION,
            reResolvedAfterRebuild = false,
            coverage = COMPLETE_COVERAGE,
        )
    }

    private val expectedAnchor = SearchAnchor(
        localRowId = EXACT_LOCAL_ROW,
        providerId = ProviderMessageId(ProviderKind.SMS, EXACT_LOCAL_ROW),
        providerThreadId = SYNTHETIC_THREAD_ID,
    )
}

private class RejectingTimelineRepository : ThreadTimelineRepository {
    val latestLoadCount = AtomicInteger()

    override suspend fun load(request: TimelinePageRequest): TimelinePageResult {
        latestLoadCount.incrementAndGet()
        return TimelinePageResult.MissingThread(COMPLETE_COVERAGE)
    }

    override suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: ProviderMessageId,
    ): TimelineContentResult = TimelineContentResult.Missing(COMPLETE_COVERAGE)
}

private class InMemoryAppearanceRepository : AppearanceProfileRepository {
    private val lock = Any()
    private val profiles = listOf(
        storedProfile(EVENING_ID, EVENING_NAME, AppearancePalette.AMOLED_BLACK),
        storedProfile(DAYLIGHT_ID, DAYLIGHT_NAME, AppearancePalette.LIGHT),
    )
    private val mutableSnapshots = MutableStateFlow(
        AppearanceSnapshot(
            profiles = profiles,
            activeProfileId = AppearanceProfileId(EVENING_ID),
            revision = 1L,
        ),
    )
    private val overrideFlows = HashMap<AppearanceScope, MutableStateFlow<AppearanceOverride?>>()
    private var nextOverrideRevision = 1L

    override val snapshots: Flow<AppearanceSnapshot> = mutableSnapshots

    override fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?> = synchronized(lock) {
        overrideFlows.getOrPut(scope) { MutableStateFlow(null) }
    }

    fun overrideFor(scope: AppearanceScope): AppearanceOverride? = synchronized(lock) {
        overrideFlows[scope]?.value
    }

    override suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride> {
        val flow: MutableStateFlow<AppearanceOverride?>
        val created: AppearanceOverride
        synchronized(lock) {
            if (profiles.none { it.id == profileId }) return AppearanceRepositoryResult.NotFound
            flow = overrideFlows.getOrPut(scope) { MutableStateFlow(null) }
            val current = flow.value
            if (current?.revision != expectedRevision || (current == null) != (expectedRevision == null)) {
                return AppearanceRepositoryResult.StaleWrite
            }
            created = AppearanceOverride(
                scope = scope,
                profileId = profileId,
                revision = AppearanceOverrideRevision(nextOverrideRevision++),
            )
            flow.value = created
        }
        return AppearanceRepositoryResult.Success(created)
    }

    override suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> = synchronized(lock) {
        val flow = overrideFlows.getOrPut(scope) { MutableStateFlow(null) }
        val current = flow.value
        if (current?.revision != expectedRevision || (current == null) != (expectedRevision == null)) {
            AppearanceRepositoryResult.StaleWrite
        } else {
            flow.value = null
            AppearanceRepositoryResult.Success(Unit)
        }
    }

    override suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> = AppearanceRepositoryResult.NotFound

    override suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> = AppearanceRepositoryResult.NotFound

    override suspend fun activate(id: AppearanceProfileId): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.Success(Unit)

    override suspend fun resetActive(): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.Success(Unit)

    override suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit> = AppearanceRepositoryResult.NotFound
}

private class InMemoryDraftRepository : DraftRepository {
    private val lock = Any()
    private val drafts = HashMap<DraftIdentity, Draft>()
    private var nextId = 1L

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> = synchronized(lock) {
        if (drafts.containsKey(draft.identity)) return DraftRepositoryResult.Conflict
        val stored = Draft(
            id = DraftId(nextId++),
            identity = draft.identity,
            body = draft.body,
            subject = draft.subject,
            createdTimestampMillis = draft.createdTimestampMillis,
            updatedTimestampMillis = draft.updatedTimestampMillis,
        )
        drafts[draft.identity] = stored
        DraftRepositoryResult.Success(stored)
    }

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> = synchronized(lock) {
        drafts.values.firstOrNull { it.id == id }
            ?.let { DraftRepositoryResult.Success(it) }
            ?: DraftRepositoryResult.NotFound
    }

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> = synchronized(lock) {
        drafts[identity]?.let { DraftRepositoryResult.Success(it) } ?: DraftRepositoryResult.NotFound
    }

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> = synchronized(lock) {
        val current = drafts[draft.identity] ?: return DraftRepositoryResult.NotFound
        if (current.id != draft.id || current.revision != expectedRevision) {
            return DraftRepositoryResult.StaleWrite
        }
        drafts[draft.identity] = draft
        DraftRepositoryResult.Success(draft)
    }

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> = synchronized(lock) {
        val entry = drafts.entries.firstOrNull { it.value.id == id } ?: return DraftRepositoryResult.NotFound
        drafts.remove(entry.key)
        DraftRepositoryResult.Success(Unit)
    }
}

private object SyntheticContactCache : ContactCache {
    override val invalidations: Flow<ContactCacheInvalidation> = emptyFlow()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> =
        addresses.map { ResolvedContact(it, displayName = null, photoUri = null) }

    override suspend fun invalidate() = Unit
}

private object SyntheticSubscriptions : SubscriptionRepository {
    override suspend fun activeSubscriptions(): SubscriptionSnapshot = SubscriptionSnapshot.FeatureUnavailable
}

private object RejectingAttachments : MmsAttachmentRepository {
    override suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult =
        MmsAttachmentListResult.Unavailable

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> = MmsAttachmentReadResult.Unavailable
}

private object RejectingPreviewLoader : BoundedPreviewLoader {
    override suspend fun load(
        descriptor: org.aurorasms.core.telephony.MmsAttachmentDescriptor,
    ): AttachmentPreviewResult = AttachmentPreviewResult.Unavailable

    override suspend fun clear() = Unit
}

private fun syntheticInboxSummary(index: Int): ConversationSummary = ConversationSummary(
    providerThreadId = ProviderThreadId(100L + index),
    latestLocalRowId = 1_000L + index,
    latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1_000L + index),
    latestTimestampMillis = 10_000L - index,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.NONE,
    latestSubscriptionId = null,
    latestSenderAddress = ParticipantAddress("synthetic-inbox-$index@example.invalid"),
    latestSnippet = "Synthetic inbox preview $index",
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = 1L,
    indexedUnreadCount = 0L,
    participants = listOf(ParticipantAddress("synthetic-inbox-$index@example.invalid")),
    indexedParticipantCount = 1,
    participantsTruncated = false,
)

private fun syntheticThreadSummary(): ConversationSummary = ConversationSummary(
    providerThreadId = SYNTHETIC_THREAD_ID,
    latestLocalRowId = EXACT_LOCAL_ROW,
    latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, EXACT_LOCAL_ROW),
    latestTimestampMillis = 40_000L,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.NONE,
    latestSubscriptionId = null,
    latestSenderAddress = SYNTHETIC_PARTICIPANT,
    latestSnippet = SYNTHETIC_EXACT_ANCHOR,
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = ANCHOR_WINDOW_SIZE.toLong(),
    indexedUnreadCount = 0L,
    participants = SYNTHETIC_VERIFIED_PARTICIPANTS.take(8),
    indexedParticipantCount = SYNTHETIC_VERIFIED_PARTICIPANTS.size,
    participantsTruncated = false,
)

private fun syntheticAnchorHit(index: Int): SearchHit {
    val localRow = ANCHOR_BASE_ROW + index
    return SearchHit(
        localRowId = localRow,
        providerId = ProviderMessageId(ProviderKind.SMS, localRow),
        providerThreadId = SYNTHETIC_THREAD_ID,
        timestampMillis = 30_000L + index,
        sentTimestampMillis = null,
        direction = MessageDirection.INCOMING,
        box = MessageBox.INBOX,
        status = MessageStatus.NONE,
        subscriptionId = null,
        senderAddress = SYNTHETIC_PARTICIPANT.value,
        body = if (index == ANCHOR_POSITION) SYNTHETIC_EXACT_ANCHOR else "Synthetic anchor row $index",
        subject = null,
        attachmentCount = 0,
        attachmentTypeSummary = "",
        read = true,
        seen = true,
        locked = false,
    )
}

private fun storedProfile(id: Long, name: String, palette: AppearancePalette): AppearanceProfile =
    AppearanceProfile(
        id = AppearanceProfileId(id),
        name = AppearanceProfileName.from(name),
        values = AppearanceProfileValues(palette = palette),
        revision = AppearanceRevision(1L),
        createdTimestampMillis = 1_000L + id,
        updatedTimestampMillis = 1_000L + id,
    )

private val COMPLETE_COVERAGE = IndexCoverage(
    generationId = 1L,
    state = IndexRunState.COMPLETE,
    indexedMessageCount = 100L,
    smsExhausted = true,
    mmsExhausted = true,
    pendingChanges = false,
    generationCommittedCount = 100L,
    smsCheckpointCommittedCount = 100L,
)

private val SYNTHETIC_THREAD_ID = ProviderThreadId(9_001L)
private val SYNTHETIC_VERIFIED_PARTICIPANTS = (1..9).map { index ->
    ParticipantAddress("synthetic-thread-${index.toString().padStart(2, '0')}@example.invalid")
}
private val SYNTHETIC_PARTICIPANT = SYNTHETIC_VERIFIED_PARTICIPANTS.first()
private val SYNTHETIC_VERIFIED_IDENTITY = VerifiedConversationIdentity(
    providerThreadId = SYNTHETIC_THREAD_ID,
    generationId = checkNotNull(COMPLETE_COVERAGE.generationId),
    participants = SYNTHETIC_VERIFIED_PARTICIPANTS,
)
private val SYNTHETIC_CONVERSATION_SCOPE = AppearanceScope.Conversation(
    participantSetKey = AppearanceParticipantSetKey.fromParticipants(SYNTHETIC_VERIFIED_PARTICIPANTS),
    providerThreadId = SYNTHETIC_THREAD_ID,
)
private const val ANCHOR_WINDOW_SIZE = 51
private const val ANCHOR_POSITION = 25
private const val INBOX_ANCHOR_INDEX = 15
private const val THREAD_ANCHOR_INDEX = 20
private const val THREAD_REENTRY_STALE_INDEX = 10
private const val ANCHOR_BASE_ROW = 2_000L
private const val EXACT_LOCAL_ROW = ANCHOR_BASE_ROW + ANCHOR_POSITION
private const val EVENING_ID = 7L
private const val DAYLIGHT_ID = 8L
private const val EVENING_NAME = "Synthetic Evening"
private const val DAYLIGHT_NAME = "Synthetic Daylight"
private const val SYNTHETIC_QUERY = "synthetic exact query"
private const val SYNTHETIC_DRAFT = "Synthetic restored draft"
private const val SYNTHETIC_EXACT_ANCHOR = "Synthetic exact anchor"
private const val TIMEOUT_MILLIS = 10_000L
