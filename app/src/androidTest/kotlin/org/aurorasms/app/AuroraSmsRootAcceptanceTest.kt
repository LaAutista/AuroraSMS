// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.app.appearance.AppearanceController
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_APPLY_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_CANCEL_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_RESET_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_SELECTOR_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_WALLPAPER_TEST_TAG
import org.aurorasms.app.appearance.THEME_STUDIO_SCREEN_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.DurableWallpaperQuotaResult
import org.aurorasms.app.appearance.wallpaper.AppWallpaperAssignment
import org.aurorasms.app.appearance.wallpaper.ManagedWallpaperFileClassification
import org.aurorasms.app.appearance.wallpaper.ManagedWallpaperReconcileResult
import org.aurorasms.app.appearance.wallpaper.ManagedWallpaperStore
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_APPLY_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_DIM_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_FOCAL_X_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_FOCAL_Y_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_LOADING_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.SCOPED_WALLPAPER_RESET_TEST_TAG
import org.aurorasms.app.appearance.wallpaper.WallpaperController
import org.aurorasms.app.appearance.wallpaper.WallpaperApplyControllerResult
import org.aurorasms.app.appearance.wallpaper.WallpaperImportResult
import org.aurorasms.app.appearance.wallpaper.WallpaperInspectionResult
import org.aurorasms.app.appearance.wallpaper.WallpaperLoadResult
import org.aurorasms.app.appearance.wallpaper.WallpaperMediaFailure
import org.aurorasms.app.appearance.wallpaper.WallpaperMediaStore
import org.aurorasms.app.appearance.wallpaper.classifyManagedWallpaperFileName
import org.aurorasms.app.appearance.wallpaper.wallpaperDerivativeFileName
import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.preview.BoundedMediaDecodeGate
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
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperMediaKind
import org.aurorasms.core.state.AppearanceWallpaperMutation
import org.aurorasms.core.state.AppearanceWallpaperRepository
import org.aurorasms.core.state.AppearanceWallpaperRevision
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
import org.junit.Assume.assumeTrue
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
    fun verifiedConversationWallpaperSurvivesHostForceStopAndColdTargetProcessRelaunch() {
        when (requireExplicitColdRestartPhase()) {
            COLD_RESTART_PHASE_PREPARE -> prepareColdRestartWallpaper()
            COLD_RESTART_PHASE_VERIFY -> verifyColdRestartWallpaper()
            COLD_RESTART_PHASE_CLEANUP -> cleanupColdRestartWallpaper()
            else -> throw AssertionError(COLD_RESTART_PHASE_INVALID)
        }
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

    @Test
    fun verifiedConversationWallpaperWinsGlobalAndSurvivesRecreationWithoutPresentationReload() {
        val fixture = SyntheticFixture()
        val globalAssignment = fixture.wallpapers.seed(
            scope = GLOBAL_THREAD_SCOPE,
            mediaId = GLOBAL_WALLPAPER_MEDIA_ID,
            dimPermill = 510,
            focalXPermill = 500,
            focalYPermill = 500,
        )
        val initialConversationAssignment = fixture.wallpapers.seed(
            scope = SYNTHETIC_CONVERSATION_SCOPE,
            mediaId = CONVERSATION_WALLPAPER_MEDIA_ID,
            dimPermill = 410,
            focalXPermill = 180,
            focalYPermill = 760,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            openSyntheticThread()
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(CONVERSATION_WALLPAPER_MEDIA_ID, preview = false) >= 1
            }
            waitForWallpaperPixels(CONVERSATION_WALLPAPER_COLOR_ARGB, initialConversationAssignment.dimPermill)
            assertEquals(0, fixture.wallpaperStore.loadCount(GLOBAL_WALLPAPER_MEDIA_ID, preview = false))
            assertEquals(initialConversationAssignment, fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE))
            assertEquals(globalAssignment, fixture.wallpapers.assignmentFor(GLOBAL_THREAD_SCOPE))

            compose.onNodeWithTag(THREAD_LIST_TEST_TAG).performScrollToIndex(THREAD_ANCHOR_INDEX)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).performTextReplacement(SYNTHETIC_DRAFT)
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            val anchorLoadsBeforeEditor = fixture.index.anchorLoadCount.get()
            val timelineLoadsBeforeEditor = fixture.timeline.latestLoadCount.get()

            openConversationWallpaperEditor()
            compose.onNodeWithTag(SCOPED_WALLPAPER_FOCAL_X_TEST_TAG)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(UPDATED_FOCAL_X.toFloat()) }
            compose.onNodeWithTag(SCOPED_WALLPAPER_FOCAL_Y_TEST_TAG)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(UPDATED_FOCAL_Y.toFloat()) }
            compose.onNodeWithTag(SCOPED_WALLPAPER_DIM_TEST_TAG)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(UPDATED_DIM.toFloat()) }
            val conversationLoadsBeforeApply = fixture.wallpaperStore.loadCount(
                CONVERSATION_WALLPAPER_MEDIA_ID,
                preview = false,
            )
            compose.onNodeWithTag(SCOPED_WALLPAPER_APPLY_TEST_TAG).performClick()
            waitForWallpaperDialogToClose()
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE)?.let { assignment ->
                    assignment.revision != initialConversationAssignment.revision &&
                        assignment.dimPermill == UPDATED_DIM &&
                        assignment.focalXPermill == UPDATED_FOCAL_X &&
                        assignment.focalYPermill == UPDATED_FOCAL_Y
                } == true
            }
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(
                    CONVERSATION_WALLPAPER_MEDIA_ID,
                    preview = false,
                ) > conversationLoadsBeforeApply
            }
            val updatedConversationAssignment = checkNotNull(
                fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE),
            )
            assertEquals(CONVERSATION_WALLPAPER_MEDIA_ID, updatedConversationAssignment.mediaId)
            assertEquals(globalAssignment, fixture.wallpapers.assignmentFor(GLOBAL_THREAD_SCOPE))
            assertEquals(anchorLoadsBeforeEditor, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeEditor, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()

            compose.onNodeWithTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG).performClick()
            waitForDialogToClose()
            waitForWallpaperPixels(CONVERSATION_WALLPAPER_COLOR_ARGB, UPDATED_DIM)
            val conversationLoadsBeforeRecreation = fixture.wallpaperStore.loadCount(
                CONVERSATION_WALLPAPER_MEDIA_ID,
                preview = false,
            )
            val anchorLoadsBeforeRecreation = fixture.index.anchorLoadCount.get()
            scenario.recreate()

            waitForTag(THREAD_SCREEN_TEST_TAG)
            waitForTag(THREAD_LIST_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.index.anchorLoadCount.get() == anchorLoadsBeforeRecreation + 1
            }
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(
                    CONVERSATION_WALLPAPER_MEDIA_ID,
                    preview = false,
                ) > conversationLoadsBeforeRecreation
            }
            assertEquals(updatedConversationAssignment, fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE))
            assertEquals(globalAssignment, fixture.wallpapers.assignmentFor(GLOBAL_THREAD_SCOPE))
            assertEquals(timelineLoadsBeforeEditor, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithText("Synthetic anchor row 20").assertIsDisplayed()
            waitForWallpaperPixels(CONVERSATION_WALLPAPER_COLOR_ARGB, UPDATED_DIM)

            openConversationWallpaperEditor()
            compose.onNodeWithText("Horizontal focal point: 27%")
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Vertical focal point: 83%")
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Dim: 72%")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun conversationResetAndIdentityLossFallBackToGlobalWithoutCrossTargetMutation() {
        val fixture = SyntheticFixture()
        val globalAssignment = fixture.wallpapers.seed(
            scope = GLOBAL_THREAD_SCOPE,
            mediaId = GLOBAL_WALLPAPER_MEDIA_ID,
            dimPermill = 530,
            focalXPermill = 480,
            focalYPermill = 520,
        )
        fixture.wallpapers.seed(
            scope = SYNTHETIC_CONVERSATION_SCOPE,
            mediaId = CONVERSATION_WALLPAPER_MEDIA_ID,
            dimPermill = 430,
            focalXPermill = 210,
            focalYPermill = 790,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openSyntheticThread()
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(CONVERSATION_WALLPAPER_MEDIA_ID, preview = false) >= 1
            }
            compose.onNodeWithTag(COMPOSER_TEST_TAG).performTextReplacement(SYNTHETIC_DRAFT)
            val anchorLoadsBeforeReset = fixture.index.anchorLoadCount.get()
            val timelineLoadsBeforeReset = fixture.timeline.latestLoadCount.get()

            openConversationWallpaperEditor()
            val globalLoadsBeforeReset = fixture.wallpaperStore.loadCount(
                GLOBAL_WALLPAPER_MEDIA_ID,
                preview = false,
            )
            compose.onNodeWithTag(SCOPED_WALLPAPER_RESET_TEST_TAG)
                .performScrollTo()
                .performClick()
            waitForWallpaperDialogToClose()
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE) == null
            }
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(
                    GLOBAL_WALLPAPER_MEDIA_ID,
                    preview = false,
                ) > globalLoadsBeforeReset
            }
            assertEquals(globalAssignment, fixture.wallpapers.assignmentFor(GLOBAL_THREAD_SCOPE))
            assertEquals(anchorLoadsBeforeReset, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeReset, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG).performClick()
            waitForDialogToClose()
            waitForWallpaperPixels(GLOBAL_WALLPAPER_COLOR_ARGB, globalAssignment.dimPermill)

            val replacementConversationAssignment = fixture.wallpapers.seed(
                scope = SYNTHETIC_CONVERSATION_SCOPE,
                mediaId = REPLACEMENT_CONVERSATION_WALLPAPER_MEDIA_ID,
                dimPermill = 650,
                focalXPermill = 320,
                focalYPermill = 680,
            )
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(
                    REPLACEMENT_CONVERSATION_WALLPAPER_MEDIA_ID,
                    preview = false,
                ) >= 1
            }
            waitForWallpaperPixels(
                REPLACEMENT_CONVERSATION_WALLPAPER_COLOR_ARGB,
                replacementConversationAssignment.dimPermill,
            )
            val globalLoadsBeforeIdentityLoss = fixture.wallpaperStore.loadCount(
                GLOBAL_WALLPAPER_MEDIA_ID,
                preview = false,
            )

            fixture.conversations.invalidateVerifiedIdentity()

            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.wallpaperStore.loadCount(
                    GLOBAL_WALLPAPER_MEDIA_ID,
                    preview = false,
                ) > globalLoadsBeforeIdentityLoss
            }
            waitForWallpaperPixels(GLOBAL_WALLPAPER_COLOR_ARGB, globalAssignment.dimPermill)
            assertEquals(
                replacementConversationAssignment,
                fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE),
            )
            assertEquals(globalAssignment, fixture.wallpapers.assignmentFor(GLOBAL_THREAD_SCOPE))
            assertEquals(anchorLoadsBeforeReset, fixture.index.anchorLoadCount.get())
            assertEquals(timelineLoadsBeforeReset, fixture.timeline.latestLoadCount.get())
            compose.onNodeWithTag(THREAD_SCREEN_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).assertDoesNotExist()
        }
    }

    private fun requireExplicitColdRestartPhase(): String {
        val arguments = InstrumentationRegistry.getArguments()
        val enabled = arguments.getString(COLD_RESTART_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(COLD_RESTART_GATE_REQUIRED, enabled)
        assumeTrue(
            COLD_RESTART_EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
        assumeTrue(
            COLD_RESTART_API_REQUIRED,
            Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA,
        )
        return arguments.getString(COLD_RESTART_PHASE_ARGUMENT).orEmpty().also { phase ->
            restartRequire(
                phase == COLD_RESTART_PHASE_PREPARE ||
                    phase == COLD_RESTART_PHASE_VERIFY ||
                    phase == COLD_RESTART_PHASE_CLEANUP,
                COLD_RESTART_PHASE_INVALID,
            )
        }
    }

    private fun prepareColdRestartWallpaper() {
        val environment = coldRestartEnvironment()
        val preferences = environment.context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        restartRequire(
            coldRestartCheckpointState(environment.context) == null,
            COLD_RESTART_STALE_CHECKPOINT,
        )
        restartRequire(
            readColdRestartAssignment(environment.controller) == null,
            COLD_RESTART_CONVERSATION_ASSIGNMENT_NOT_EMPTY,
        )
        val expectedMediaId = deriveColdRestartExpectedMediaId(environment.context)
        val baselineFiles = coldRestartManagedFileNames(environment.context)
        val baselineGrantCount = environment.context.contentResolver.persistedUriPermissions.size
        val recovery = ColdRestartRecovery(
            baselineFiles = baselineFiles,
            baselineGrantCount = baselineGrantCount,
            expectedMediaId = expectedMediaId,
            preparedPid = Process.myPid(),
            preparedStartUptimeMillis = Process.getStartUptimeMillis(),
        )
        restartRequire(
            writeColdRestartRecovery(preferences, recovery),
            COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
        var pendingFileName: String? = null
        var appliedAssignment: AppWallpaperAssignment? = null
        var checkpointCommitted = false
        try {
            val applyResult = runBlocking {
                withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                    environment.controller.apply(
                        scope = SYNTHETIC_CONVERSATION_SCOPE,
                        source = COLD_RESTART_SOURCE_URI,
                        dimPermill = COLD_RESTART_DIM_PERMILL,
                        focalXPermill = COLD_RESTART_FOCAL_X_PERMILL,
                        focalYPermill = COLD_RESTART_FOCAL_Y_PERMILL,
                        expectedRevision = null,
                    )
                }
            }
            restartRequire(
                applyResult == WallpaperApplyControllerResult.Success,
                COLD_RESTART_APPLY_FAILED,
            )
            val assignment = readColdRestartAssignment(environment.controller)
                ?: throw AssertionError(COLD_RESTART_ASSIGNMENT_MISSING)
            appliedAssignment = assignment
            restartRequire(
                assignment.scope == SYNTHETIC_CONVERSATION_SCOPE &&
                    assignment.mediaId == recovery.expectedMediaId &&
                    assignment.dimPermill == COLD_RESTART_DIM_PERMILL &&
                    assignment.focalXPermill == COLD_RESTART_FOCAL_X_PERMILL &&
                    assignment.focalYPermill == COLD_RESTART_FOCAL_Y_PERMILL,
                COLD_RESTART_ASSIGNMENT_MISMATCH,
            )
            assertColdRestartManagedFiles(environment.context, baselineFiles, assignment.mediaId)
            restartRequire(
                environment.context.contentResolver.persistedUriPermissions.size == baselineGrantCount,
                COLD_RESTART_GRANT_COUNT_CHANGED,
            )
            assertColdRestartWallpaperLoad(environment.controller, assignment)
            val createdPendingFileName = createColdRestartPendingFile(environment.context)
            pendingFileName = createdPendingFileName
            val evidence = ColdRestartEvidence(
                mediaId = assignment.mediaId,
                revision = assignment.revision,
                dimPermill = assignment.dimPermill,
                focalXPermill = assignment.focalXPermill,
                focalYPermill = assignment.focalYPermill,
                baselineFiles = baselineFiles,
                baselineGrantCount = baselineGrantCount,
                pendingFileName = createdPendingFileName,
                preparedPid = recovery.preparedPid,
                preparedStartUptimeMillis = recovery.preparedStartUptimeMillis,
                verifiedPid = null,
                verifiedStartUptimeMillis = null,
            )
            checkpointCommitted = writeColdRestartEvidence(preferences, evidence)
            restartRequire(checkpointCommitted, COLD_RESTART_CHECKPOINT_WRITE_FAILED)
        } finally {
            if (!checkpointCommitted) {
                val restored = bestEffortRollbackColdRestartPrepare(
                    environment,
                    recovery,
                    pendingFileName,
                    appliedAssignment,
                )
                if (restored) preferences.edit().clear().commit()
            }
        }
    }

    private fun verifyColdRestartWallpaper() {
        val environment = coldRestartEnvironment()
        val evidence = readColdRestartEvidence(environment.context)
            ?: throw AssertionError(COLD_RESTART_CHECKPOINT_MISSING)
        val currentPid = Process.myPid()
        val currentStartUptimeMillis = Process.getStartUptimeMillis()
        restartRequire(
            currentPid != evidence.preparedPid &&
                currentStartUptimeMillis > evidence.preparedStartUptimeMillis,
            COLD_RESTART_PROCESS_NOT_RESTARTED,
        )
        val assignment = readColdRestartAssignment(environment.controller)
            ?: throw AssertionError(COLD_RESTART_ASSIGNMENT_MISSING)
        assertColdRestartAssignment(evidence, assignment)
        restartRequire(
            evidence.pendingFileName !in coldRestartManagedFileNames(environment.context),
            COLD_RESTART_PENDING_FILE_SURVIVED,
        )
        assertColdRestartManagedFiles(environment.context, evidence.baselineFiles, evidence.mediaId)
        restartRequire(
            environment.context.contentResolver.persistedUriPermissions.size ==
                evidence.baselineGrantCount,
            COLD_RESTART_GRANT_COUNT_CHANGED,
        )
        assertColdRestartWallpaperLoad(environment.controller, assignment)

        val fixture = SyntheticFixture(wallpaperControllerOverride = environment.controller)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openSyntheticThread()
            waitForWallpaperPixels(COLD_RESTART_WALLPAPER_COLOR_ARGB, evidence.dimPermill)
            restartRequire(
                fixture.wallpapers.assignmentFor(SYNTHETIC_CONVERSATION_SCOPE) == null,
                COLD_RESTART_SYNTHETIC_WALLPAPER_USED,
            )
        }

        val preferences = environment.context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        restartRequire(
            writeColdRestartEvidence(
                preferences,
                evidence.copy(
                    verifiedPid = currentPid,
                    verifiedStartUptimeMillis = currentStartUptimeMillis,
                ),
            ),
            COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
    }

    private fun cleanupColdRestartWallpaper() {
        val environment = coldRestartEnvironment()
        val productionFailure = runCatching {
            cleanupColdRestartProductionState(environment)
        }.exceptionOrNull()
        val isolatedRootCleared = deleteColdRestartExpectedMediaRoot(environment.context)
        if (productionFailure != null) throw productionFailure
        restartRequire(isolatedRootCleared, COLD_RESTART_EXPECTED_MEDIA_CLEANUP_FAILED)
    }

    private fun cleanupColdRestartProductionState(environment: ColdRestartEnvironment) {
        val preferences = environment.context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        when (coldRestartCheckpointState(environment.context)) {
            null -> return
            COLD_RESTART_STATE_PREPARING -> {
                val recovery = readColdRestartRecovery(environment.context)
                cleanupColdRestartRecovery(environment, recovery)
                restartRequire(
                    preferences.edit().clear().commit(),
                    COLD_RESTART_CHECKPOINT_CLEAR_FAILED,
                )
                return
            }
            COLD_RESTART_STATE_PREPARED,
            COLD_RESTART_STATE_VERIFIED,
            -> Unit
            else -> throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID)
        }
        val evidence = readColdRestartEvidence(environment.context)
            ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID)
        val assignment = readColdRestartAssignment(environment.controller)
        if (assignment != null) {
            assertColdRestartAssignment(evidence, assignment)
            val resetResult = runBlocking {
                withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                    environment.controller.reset(
                        scope = SYNTHETIC_CONVERSATION_SCOPE,
                        expectedRevision = evidence.revision,
                    )
                }
            }
            restartRequire(
                resetResult == WallpaperApplyControllerResult.Success,
                COLD_RESTART_RESET_FAILED,
            )
            restartRequire(
                readColdRestartAssignment(environment.controller) == null,
                COLD_RESTART_RESET_DID_NOT_CLEAR,
            )
        }
        deleteColdRestartPendingFile(environment.context, evidence.pendingFileName)
        restartRequire(
            coldRestartManagedFileNames(environment.context) == evidence.baselineFiles,
            COLD_RESTART_MANAGED_FILES_NOT_RESTORED,
        )
        restartRequire(
            environment.context.contentResolver.persistedUriPermissions.size ==
                evidence.baselineGrantCount,
            COLD_RESTART_GRANT_COUNT_CHANGED,
        )
        restartRequire(preferences.edit().clear().commit(), COLD_RESTART_CHECKPOINT_CLEAR_FAILED)
    }

    private fun coldRestartEnvironment(): ColdRestartEnvironment {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as? AuroraSmsApplication
            ?: throw AssertionError(COLD_RESTART_APPLICATION_MISSING)
        val status = runBlocking {
            withTimeoutOrNull(COLD_RESTART_TIMEOUT_MILLIS) {
                application.container.stateStorageStatus.first { current ->
                    current != StateStorageStatus.Opening
                }
            }
        }
        restartRequire(status == StateStorageStatus.Ready, COLD_RESTART_STATE_NOT_READY)
        return ColdRestartEnvironment(context, application.container.wallpaperController)
    }

    private fun readColdRestartAssignment(controller: WallpaperController): AppWallpaperAssignment? =
        runBlocking {
            withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                controller.observe(SYNTHETIC_CONVERSATION_SCOPE).first { observation ->
                    observation.ready
                }
            }
        }.assignment

    private fun assertColdRestartAssignment(
        evidence: ColdRestartEvidence,
        actual: AppWallpaperAssignment,
    ) {
        restartRequire(
            actual == AppWallpaperAssignment(
                scope = SYNTHETIC_CONVERSATION_SCOPE,
                mediaId = evidence.mediaId,
                dimPermill = evidence.dimPermill,
                focalXPermill = evidence.focalXPermill,
                focalYPermill = evidence.focalYPermill,
                revision = evidence.revision,
            ),
            COLD_RESTART_ASSIGNMENT_MISMATCH,
        )
    }

    private fun assertColdRestartManagedFiles(
        context: Context,
        baselineFiles: Set<String>,
        mediaId: String,
    ) {
        val expectedFile = wallpaperDerivativeFileName(mediaId)
            ?: throw AssertionError(COLD_RESTART_MANAGED_FILE_INVALID)
        restartRequire(expectedFile !in baselineFiles, COLD_RESTART_MANAGED_FILE_NOT_NEW)
        restartRequire(
            coldRestartManagedFileNames(context) == baselineFiles + expectedFile,
            COLD_RESTART_MANAGED_FILES_MISMATCH,
        )
        val classification = classifyManagedWallpaperFileName(expectedFile)
        restartRequire(
            classification is ManagedWallpaperFileClassification.Final &&
                classification.mediaId == mediaId,
            COLD_RESTART_MANAGED_FILE_INVALID,
        )
    }

    private fun assertColdRestartWallpaperLoad(
        controller: WallpaperController,
        assignment: AppWallpaperAssignment,
    ) {
        val loaded = runBlocking {
            withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                controller.loadFirstAvailable(listOf(assignment))
            }
        } ?: throw AssertionError(COLD_RESTART_MANAGED_FILE_UNAVAILABLE)
        try {
            restartRequire(loaded.assignment == assignment, COLD_RESTART_ASSIGNMENT_MISMATCH)
            val bitmap = loaded.image.asAndroidBitmap()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            restartRequire(
                coldRestartColorChannelMatches(pixel shr 16, COLD_RESTART_WALLPAPER_COLOR_ARGB shr 16) &&
                    coldRestartColorChannelMatches(pixel shr 8, COLD_RESTART_WALLPAPER_COLOR_ARGB shr 8) &&
                    coldRestartColorChannelMatches(pixel, COLD_RESTART_WALLPAPER_COLOR_ARGB),
                COLD_RESTART_MANAGED_FILE_PIXELS_MISMATCH,
            )
        } finally {
            loaded.release()
        }
    }

    private fun coldRestartColorChannelMatches(actual: Int, expected: Int): Boolean =
        kotlin.math.abs((actual and 0xff) - (expected and 0xff)) <= COLD_RESTART_COLOR_TOLERANCE

    private fun coldRestartManagedFileNames(context: Context): Set<String> {
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        if (!directory.exists()) return emptySet()
        restartRequire(directory.isDirectory, COLD_RESTART_MANAGED_DIRECTORY_INVALID)
        return directory.list()?.toSet()
            ?: throw AssertionError(COLD_RESTART_MANAGED_DIRECTORY_UNAVAILABLE)
    }

    private fun coldRestartCheckpointState(context: Context): String? {
        val preferences = context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val version = preferences.getInt(COLD_RESTART_KEY_VERSION, 0)
        if (version == 0) {
            restartRequire(preferences.all.isEmpty(), COLD_RESTART_CHECKPOINT_INVALID)
            return null
        }
        restartRequire(version == COLD_RESTART_VERSION, COLD_RESTART_CHECKPOINT_INVALID)
        return preferences.getString(COLD_RESTART_KEY_STATE, null)
            ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID)
    }

    private fun readColdRestartRecovery(context: Context): ColdRestartRecovery {
        restartRequire(
            coldRestartCheckpointState(context) == COLD_RESTART_STATE_PREPARING,
            COLD_RESTART_CHECKPOINT_INVALID,
        )
        val preferences = context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val recovery = ColdRestartRecovery(
            baselineFiles = preferences.getStringSet(COLD_RESTART_KEY_BASELINE_FILES, null)
                ?.toSet()
                ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID),
            baselineGrantCount = preferences.getInt(COLD_RESTART_KEY_BASELINE_GRANTS, -1),
            expectedMediaId = preferences.getString(COLD_RESTART_KEY_EXPECTED_MEDIA_ID, null)
                ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID),
            preparedPid = preferences.getInt(COLD_RESTART_KEY_PREPARED_PID, -1),
            preparedStartUptimeMillis = preferences.getLong(
                COLD_RESTART_KEY_PREPARED_START_UPTIME,
                -1L,
            ),
        )
        restartRequire(
            recovery.baselineGrantCount >= 0 &&
                runCatching {
                    AppearanceWallpaperMediaId.fromPrivateStorageToken(recovery.expectedMediaId)
                }.isSuccess &&
                recovery.preparedPid > 0 &&
                recovery.preparedStartUptimeMillis >= 0L,
            COLD_RESTART_CHECKPOINT_INVALID,
        )
        return recovery
    }

    private fun writeColdRestartRecovery(
        preferences: android.content.SharedPreferences,
        recovery: ColdRestartRecovery,
    ): Boolean = preferences.edit()
        .clear()
        .putInt(COLD_RESTART_KEY_VERSION, COLD_RESTART_VERSION)
        .putString(COLD_RESTART_KEY_STATE, COLD_RESTART_STATE_PREPARING)
        .putStringSet(COLD_RESTART_KEY_BASELINE_FILES, recovery.baselineFiles)
        .putInt(COLD_RESTART_KEY_BASELINE_GRANTS, recovery.baselineGrantCount)
        .putString(COLD_RESTART_KEY_EXPECTED_MEDIA_ID, recovery.expectedMediaId)
        .putInt(COLD_RESTART_KEY_PREPARED_PID, recovery.preparedPid)
        .putLong(COLD_RESTART_KEY_PREPARED_START_UPTIME, recovery.preparedStartUptimeMillis)
        .commit()

    private fun cleanupColdRestartRecovery(
        environment: ColdRestartEnvironment,
        recovery: ColdRestartRecovery,
        expectedAssignment: AppWallpaperAssignment? = null,
    ) {
        val assignment = readColdRestartAssignment(environment.controller)
        if (assignment != null) {
            if (expectedAssignment != null) {
                restartRequire(
                    assignment == expectedAssignment,
                    COLD_RESTART_RECOVERY_ASSIGNMENT_MISMATCH,
                )
            } else {
                restartRequire(
                    assignment.scope == SYNTHETIC_CONVERSATION_SCOPE &&
                        assignment.mediaId == recovery.expectedMediaId &&
                        assignment.dimPermill == COLD_RESTART_DIM_PERMILL &&
                        assignment.focalXPermill == COLD_RESTART_FOCAL_X_PERMILL &&
                        assignment.focalYPermill == COLD_RESTART_FOCAL_Y_PERMILL,
                    COLD_RESTART_RECOVERY_ASSIGNMENT_MISMATCH,
                )
            }
            val resetResult = runBlocking {
                withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                    environment.controller.reset(
                        SYNTHETIC_CONVERSATION_SCOPE,
                        assignment.revision,
                    )
                }
            }
            restartRequire(
                resetResult == WallpaperApplyControllerResult.Success,
                COLD_RESTART_RESET_FAILED,
            )
        }
        val reconciled = runBlocking {
            withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                environment.controller.reconcileManagedFiles()
            }
        }
        restartRequire(reconciled, COLD_RESTART_RECOVERY_RECONCILE_FAILED)
        deleteColdRestartPendingFile(environment.context, COLD_RESTART_PENDING_FILE_NAME)
        restartRequire(
            readColdRestartAssignment(environment.controller) == null,
            COLD_RESTART_RESET_DID_NOT_CLEAR,
        )
        restartRequire(
            coldRestartManagedFileNames(environment.context) == recovery.baselineFiles,
            COLD_RESTART_MANAGED_FILES_NOT_RESTORED,
        )
        restartRequire(
            environment.context.contentResolver.persistedUriPermissions.size ==
                recovery.baselineGrantCount,
            COLD_RESTART_GRANT_COUNT_CHANGED,
        )
    }

    private fun createColdRestartPendingFile(context: Context): String {
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        restartRequire(directory.isDirectory, COLD_RESTART_MANAGED_DIRECTORY_INVALID)
        val fileName = COLD_RESTART_PENDING_FILE_NAME
        restartRequire(
            classifyManagedWallpaperFileName(fileName) is ManagedWallpaperFileClassification.Pending,
            COLD_RESTART_PENDING_FILE_INVALID,
        )
        val file = File(directory, fileName)
        restartRequire(!file.exists(), COLD_RESTART_PENDING_FILE_ALREADY_EXISTS)
        var completed = false
        try {
            FileOutputStream(file).use { output ->
                output.write(COLD_RESTART_PENDING_BYTES)
                output.fd.sync()
            }
            restartRequire(
                Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
                COLD_RESTART_PENDING_FILE_WRITE_FAILED,
            )
            completed = true
            return fileName
        } finally {
            if (!completed) file.delete()
        }
    }

    private fun deleteColdRestartPendingFile(context: Context, fileName: String) {
        restartRequire(
            fileName == COLD_RESTART_PENDING_FILE_NAME &&
                classifyManagedWallpaperFileName(fileName) is
                ManagedWallpaperFileClassification.Pending,
            COLD_RESTART_PENDING_FILE_INVALID,
        )
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        val file = File(directory, fileName)
        if (!file.exists()) return
        restartRequire(
            Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            COLD_RESTART_PENDING_FILE_INVALID,
        )
        restartRequire(file.delete(), COLD_RESTART_PENDING_FILE_DELETE_FAILED)
    }

    private fun readColdRestartEvidence(context: Context): ColdRestartEvidence? {
        val checkpointState = coldRestartCheckpointState(context) ?: return null
        restartRequire(
            checkpointState == COLD_RESTART_STATE_PREPARED ||
                checkpointState == COLD_RESTART_STATE_VERIFIED,
            COLD_RESTART_CHECKPOINT_INVALID,
        )
        val preferences = context.getSharedPreferences(
            COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val mediaId = preferences.getString(COLD_RESTART_KEY_MEDIA_ID, null)
            ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID)
        val baselineFiles = preferences.getStringSet(COLD_RESTART_KEY_BASELINE_FILES, null)
            ?.toSet()
            ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID)
        val evidence = ColdRestartEvidence(
            mediaId = mediaId,
            revision = preferences.getLong(COLD_RESTART_KEY_REVISION, -1L),
            dimPermill = preferences.getInt(COLD_RESTART_KEY_DIM, -1),
            focalXPermill = preferences.getInt(COLD_RESTART_KEY_FOCAL_X, -1),
            focalYPermill = preferences.getInt(COLD_RESTART_KEY_FOCAL_Y, -1),
            baselineFiles = baselineFiles,
            baselineGrantCount = preferences.getInt(COLD_RESTART_KEY_BASELINE_GRANTS, -1),
            pendingFileName = preferences.getString(COLD_RESTART_KEY_PENDING_FILE, null)
                ?: throw AssertionError(COLD_RESTART_CHECKPOINT_INVALID),
            preparedPid = preferences.getInt(COLD_RESTART_KEY_PREPARED_PID, -1),
            preparedStartUptimeMillis = preferences.getLong(
                COLD_RESTART_KEY_PREPARED_START_UPTIME,
                -1L,
            ),
            verifiedPid = preferences.getInt(COLD_RESTART_KEY_VERIFIED_PID, -1)
                .takeIf { it > 0 },
            verifiedStartUptimeMillis = preferences.getLong(
                COLD_RESTART_KEY_VERIFIED_START_UPTIME,
                -1L,
            ).takeIf { it >= 0L },
        )
        restartRequire(
            runCatching { AppearanceWallpaperMediaId.fromPrivateStorageToken(evidence.mediaId) }
                .isSuccess &&
                evidence.revision > 0L &&
                evidence.dimPermill in 0..1_000 &&
                evidence.focalXPermill in 0..1_000 &&
                evidence.focalYPermill in 0..1_000 &&
                evidence.baselineGrantCount >= 0 &&
                evidence.preparedPid > 0 &&
                evidence.preparedStartUptimeMillis >= 0L &&
                evidence.pendingFileName == COLD_RESTART_PENDING_FILE_NAME &&
                classifyManagedWallpaperFileName(evidence.pendingFileName) is
                    ManagedWallpaperFileClassification.Pending &&
                ((evidence.verifiedPid == null) ==
                    (evidence.verifiedStartUptimeMillis == null)) &&
                (checkpointState == COLD_RESTART_STATE_VERIFIED) ==
                    (evidence.verifiedPid != null),
            COLD_RESTART_CHECKPOINT_INVALID,
        )
        return evidence
    }

    private fun writeColdRestartEvidence(
        preferences: android.content.SharedPreferences,
        evidence: ColdRestartEvidence,
    ): Boolean = preferences.edit()
        .clear()
        .putInt(COLD_RESTART_KEY_VERSION, COLD_RESTART_VERSION)
        .putString(
            COLD_RESTART_KEY_STATE,
            if (evidence.verifiedPid == null) {
                COLD_RESTART_STATE_PREPARED
            } else {
                COLD_RESTART_STATE_VERIFIED
            },
        )
        .putString(COLD_RESTART_KEY_MEDIA_ID, evidence.mediaId)
        .putLong(COLD_RESTART_KEY_REVISION, evidence.revision)
        .putInt(COLD_RESTART_KEY_DIM, evidence.dimPermill)
        .putInt(COLD_RESTART_KEY_FOCAL_X, evidence.focalXPermill)
        .putInt(COLD_RESTART_KEY_FOCAL_Y, evidence.focalYPermill)
        .putStringSet(COLD_RESTART_KEY_BASELINE_FILES, evidence.baselineFiles)
        .putInt(COLD_RESTART_KEY_BASELINE_GRANTS, evidence.baselineGrantCount)
        .putString(COLD_RESTART_KEY_PENDING_FILE, evidence.pendingFileName)
        .putInt(COLD_RESTART_KEY_PREPARED_PID, evidence.preparedPid)
        .putLong(COLD_RESTART_KEY_PREPARED_START_UPTIME, evidence.preparedStartUptimeMillis)
        .putInt(COLD_RESTART_KEY_VERIFIED_PID, evidence.verifiedPid ?: -1)
        .putLong(
            COLD_RESTART_KEY_VERIFIED_START_UPTIME,
            evidence.verifiedStartUptimeMillis ?: -1L,
        )
        .commit()

    private fun bestEffortRollbackColdRestartPrepare(
        environment: ColdRestartEnvironment,
        recovery: ColdRestartRecovery,
        pendingFileName: String?,
        expectedAssignment: AppWallpaperAssignment?,
    ): Boolean = try {
        pendingFileName?.let { pending ->
            restartRequire(
                pending == COLD_RESTART_PENDING_FILE_NAME,
                COLD_RESTART_PENDING_FILE_INVALID,
            )
        }
        cleanupColdRestartRecovery(
            environment = environment,
            recovery = recovery,
            expectedAssignment = expectedAssignment,
        )
        true
    } catch (_: Throwable) {
        // The pre-Apply recovery journal remains for the host runner's exact cleanup phase.
        false
    }

    private fun restartRequire(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun deriveColdRestartExpectedMediaId(context: Context): String {
        restartRequire(
            deleteColdRestartExpectedMediaRoot(context),
            COLD_RESTART_EXPECTED_MEDIA_CLEANUP_FAILED,
        )
        val root = coldRestartExpectedMediaRoot(context)
        restartRequire(
            runCatching { Files.createDirectory(root.toPath()) }.isSuccess,
            COLD_RESTART_EXPECTED_MEDIA_DIRECTORY_FAILED,
        )
        val store = ManagedWallpaperStore(
            context = ColdRestartIsolatedStoreContext(context, root),
            decodeGate = BoundedMediaDecodeGate(permits = 1),
        )
        try {
            val imported = runBlocking {
                withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                    store.import(COLD_RESTART_SOURCE_URI, emptySet())
                }
            } as? WallpaperImportResult.Ready
                ?: throw AssertionError(COLD_RESTART_EXPECTED_MEDIA_DERIVATION_FAILED)
            restartRequire(imported.created, COLD_RESTART_EXPECTED_MEDIA_DERIVATION_FAILED)
            restartRequire(
                runCatching {
                    AppearanceWallpaperMediaId.fromPrivateStorageToken(imported.mediaId)
                }.isSuccess,
                COLD_RESTART_EXPECTED_MEDIA_DERIVATION_FAILED,
            )
            return imported.mediaId
        } finally {
            val reconciled = runCatching {
                runBlocking {
                    withTimeout(COLD_RESTART_TIMEOUT_MILLIS) {
                        store.reconcile(emptySet())
                    }
                }
            }.getOrNull()
            val isolatedRootCleared = deleteColdRestartExpectedMediaRoot(context)
            restartRequire(
                reconciled == ManagedWallpaperReconcileResult.COMPLETE &&
                    isolatedRootCleared,
                COLD_RESTART_EXPECTED_MEDIA_CLEANUP_FAILED,
            )
        }
    }

    private fun coldRestartExpectedMediaRoot(context: Context): File =
        File(context.cacheDir, COLD_RESTART_EXPECTED_MEDIA_DIRECTORY)

    private fun deleteColdRestartExpectedMediaRoot(context: Context): Boolean {
        val root = coldRestartExpectedMediaRoot(context)
        val path = root.toPath()
        if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun openSyntheticThread() {
        waitForTag(INBOX_SCREEN_TEST_TAG)
        compose.onNodeWithTag(org.aurorasms.feature.conversations.INBOX_SEARCH_ACTION_TEST_TAG)
            .performClick()
        waitForTag(SEARCH_SCREEN_TEST_TAG)
        compose.onNodeWithTag(SEARCH_FIELD_TEST_TAG).performTextReplacement(SYNTHETIC_QUERY)
        waitForTag(SEARCH_HIT_TEST_TAG)
        compose.onNodeWithTag(SEARCH_HIT_TEST_TAG).performClick()
        waitForTag(THREAD_SCREEN_TEST_TAG)
        waitForTag(THREAD_LIST_TEST_TAG)
        waitForTag(THREAD_MORE_ACTION_TEST_TAG)
    }

    private fun openConversationWallpaperEditor() {
        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).performClick()
        waitForTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
            .performClick()
        waitForTag(SCOPED_WALLPAPER_DIALOG_TEST_TAG)
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(SCOPED_WALLPAPER_LOADING_TEST_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForWallpaperPixels(colorArgb: Int, dimPermill: Int) {
        val retained = (1_000 - dimPermill) / 1_000f
        val expectedRed = ((colorArgb shr 16) and 0xff) / 255f * retained
        val expectedGreen = ((colorArgb shr 8) and 0xff) / 255f * retained
        val expectedBlue = (colorArgb and 0xff) / 255f * retained
        compose.waitUntil(TIMEOUT_MILLIS) {
            val pixels = compose.onNodeWithTag(THREAD_LIST_TEST_TAG).captureToImage().toPixelMap()
            var matches = 0
            var y = 0
            while (y < pixels.height && matches < MINIMUM_WALLPAPER_PIXEL_SAMPLES) {
                var x = 0
                while (x < pixels.width && matches < MINIMUM_WALLPAPER_PIXEL_SAMPLES) {
                    val actual = pixels[x, y]
                    if (
                        kotlin.math.abs(actual.red - expectedRed) <= WALLPAPER_PIXEL_TOLERANCE &&
                        kotlin.math.abs(actual.green - expectedGreen) <= WALLPAPER_PIXEL_TOLERANCE &&
                        kotlin.math.abs(actual.blue - expectedBlue) <= WALLPAPER_PIXEL_TOLERANCE
                    ) {
                        matches += 1
                    }
                    x += WALLPAPER_PIXEL_SAMPLE_STRIDE
                }
                y += WALLPAPER_PIXEL_SAMPLE_STRIDE
            }
            matches >= MINIMUM_WALLPAPER_PIXEL_SAMPLES
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

    private fun waitForWallpaperDialogToClose() {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(SCOPED_WALLPAPER_DIALOG_TEST_TAG).fetchSemanticsNodes().isEmpty()
        }
    }
}

private data class ColdRestartEnvironment(
    val context: Context,
    val controller: WallpaperController,
) {
    override fun toString(): String = "ColdRestartEnvironment(REDACTED)"
}

private data class ColdRestartRecovery(
    val baselineFiles: Set<String>,
    val baselineGrantCount: Int,
    val expectedMediaId: String,
    val preparedPid: Int,
    val preparedStartUptimeMillis: Long,
) {
    override fun toString(): String = "ColdRestartRecovery(REDACTED)"
}

private class ColdRestartIsolatedStoreContext(
    base: Context,
    private val isolatedNoBackupRoot: File,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getNoBackupFilesDir(): File = isolatedNoBackupRoot

    override fun toString(): String = "ColdRestartIsolatedStoreContext(REDACTED)"
}

private data class ColdRestartEvidence(
    val mediaId: String,
    val revision: Long,
    val dimPermill: Int,
    val focalXPermill: Int,
    val focalYPermill: Int,
    val baselineFiles: Set<String>,
    val baselineGrantCount: Int,
    val pendingFileName: String,
    val preparedPid: Int,
    val preparedStartUptimeMillis: Long,
    val verifiedPid: Int?,
    val verifiedStartUptimeMillis: Long?,
) {
    override fun toString(): String = "ColdRestartEvidence(REDACTED)"
}

private class SyntheticFixture(
    wallpaperControllerOverride: WallpaperController? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val conversations = SyntheticConversationRepository()
    val index = SyntheticMessageIndex()
    val timeline = RejectingTimelineRepository()
    val appearance = InMemoryAppearanceRepository()
    val wallpapers = InMemoryWallpaperRepository()
    val wallpaperStore = SyntheticWallpaperMediaStore()
    private val wallpaperController = wallpaperControllerOverride
        ?: WallpaperController(wallpapers, wallpaperStore)
    private val services = SyntheticRootServices(
        scope = scope,
        conversations = conversations,
        index = index,
        timeline = timeline,
        wallpaperController = wallpaperController,
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
    override val wallpaperController: WallpaperController,
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

private class InMemoryWallpaperRepository : AppearanceWallpaperRepository {
    private val lock = Any()
    private val assignmentFlows = HashMap<AppearanceScope, MutableStateFlow<AppearanceWallpaperAssignment?>>()
    private var nextRevision = 1L

    override fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?> = synchronized(lock) {
        assignmentFlows.getOrPut(scope) { MutableStateFlow(null) }
    }

    fun assignmentFor(scope: AppearanceScope): AppearanceWallpaperAssignment? = synchronized(lock) {
        assignmentFlows[scope]?.value
    }

    fun seed(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
    ): AppearanceWallpaperAssignment = synchronized(lock) {
        val assignment = newAssignment(scope, mediaId, dimPermill, focalXPermill, focalYPermill)
        assignmentFlows.getOrPut(scope) { MutableStateFlow(null) }.value = assignment
        assignment
    }

    override suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> = synchronized(lock) {
        val flow = assignmentFlows.getOrPut(scope) { MutableStateFlow(null) }
        val current = flow.value
        if (!revisionMatches(current, expectedRevision)) return AppearanceRepositoryResult.StaleWrite
        val assignment = newAssignment(scope, mediaId, dimPermill, focalXPermill, focalYPermill)
        flow.value = assignment
        AppearanceRepositoryResult.Success(
            AppearanceWallpaperMutation(
                assignment = assignment,
                mediaIdNowUnreferenced = current?.mediaId?.takeIf { old ->
                    old != mediaId && !isReferenced(old)
                },
            ),
        )
    }

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> = synchronized(lock) {
        val flow = assignmentFlows.getOrPut(scope) { MutableStateFlow(null) }
        val current = flow.value
        if (!revisionMatches(current, expectedRevision)) return AppearanceRepositoryResult.StaleWrite
        flow.value = null
        AppearanceRepositoryResult.Success(
            AppearanceWallpaperMutation(
                assignment = null,
                mediaIdNowUnreferenced = current?.mediaId?.takeIf { old -> !isReferenced(old) },
            ),
        )
    }

    override suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> = synchronized(lock) {
        val current = assignmentFlows[scope]?.value
        if (!revisionMatches(current, expectedRevision)) return AppearanceRepositoryResult.StaleWrite
        AppearanceRepositoryResult.Success(
            assignmentFlows
                .filterKeys { it != scope }
                .values
                .mapNotNullTo(linkedSetOf()) { it.value?.mediaId }
                .apply { add(mediaId) },
        )
    }

    override suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        synchronized(lock) {
            AppearanceRepositoryResult.Success(
                assignmentFlows.values.mapNotNullTo(linkedSetOf()) { it.value?.mediaId },
            )
        }

    private fun newAssignment(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
    ): AppearanceWallpaperAssignment = AppearanceWallpaperAssignment(
        scope = scope,
        mediaKind = AppearanceWallpaperMediaKind.STATIC_RASTER_V1,
        mediaId = mediaId,
        dimPermill = dimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
        revision = AppearanceWallpaperRevision(nextRevision++),
    )

    private fun revisionMatches(
        current: AppearanceWallpaperAssignment?,
        expected: AppearanceWallpaperRevision?,
    ): Boolean = current?.revision == expected && (current == null) == (expected == null)

    private fun isReferenced(mediaId: AppearanceWallpaperMediaId): Boolean =
        assignmentFlows.values.any { it.value?.mediaId == mediaId }
}

private class SyntheticWallpaperMediaStore : WallpaperMediaStore {
    private val loads = Collections.synchronizedList(mutableListOf<SyntheticWallpaperLoad>())

    override suspend fun inspect(source: Uri): WallpaperInspectionResult =
        WallpaperInspectionResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun import(
        source: Uri,
        referencedMediaIds: Set<String>,
        onCandidateCreated: (WallpaperImportResult.Ready) -> Unit,
    ): WallpaperImportResult = WallpaperImportResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun load(mediaId: String, preview: Boolean): WallpaperLoadResult {
        loads += SyntheticWallpaperLoad(mediaId = mediaId, preview = preview)
        val color = when (mediaId) {
            GLOBAL_WALLPAPER_MEDIA_ID.toPrivateStorageToken() -> GLOBAL_WALLPAPER_COLOR_ARGB
            CONVERSATION_WALLPAPER_MEDIA_ID.toPrivateStorageToken() -> CONVERSATION_WALLPAPER_COLOR_ARGB
            REPLACEMENT_CONVERSATION_WALLPAPER_MEDIA_ID.toPrivateStorageToken() ->
                REPLACEMENT_CONVERSATION_WALLPAPER_COLOR_ARGB
            else -> return WallpaperLoadResult.Unavailable
        }
        val native = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return WallpaperLoadResult.Ready(
            mediaId = mediaId,
            image = native.asImageBitmap(),
            width = native.width,
            height = native.height,
        )
    }

    override suspend fun deleteIfUnreferenced(
        mediaId: String,
        referencedMediaIds: Set<String>,
    ): Boolean = mediaId !in referencedMediaIds

    override suspend fun reconcile(referencedMediaIds: Set<String>): ManagedWallpaperReconcileResult =
        ManagedWallpaperReconcileResult.COMPLETE

    override suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult = DurableWallpaperQuotaResult.WITHIN_LIMIT

    fun loadCount(mediaId: AppearanceWallpaperMediaId, preview: Boolean): Int = synchronized(loads) {
        loads.count { load ->
            load.mediaId == mediaId.toPrivateStorageToken() && load.preview == preview
        }
    }
}

private data class SyntheticWallpaperLoad(
    val mediaId: String,
    val preview: Boolean,
)

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
private val GLOBAL_THREAD_SCOPE = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
private val GLOBAL_WALLPAPER_MEDIA_ID = AppearanceWallpaperMediaId.fromPrivateStorageToken(
    "sha256-v1:${"1".repeat(64)}",
)
private val CONVERSATION_WALLPAPER_MEDIA_ID = AppearanceWallpaperMediaId.fromPrivateStorageToken(
    "sha256-v1:${"2".repeat(64)}",
)
private val REPLACEMENT_CONVERSATION_WALLPAPER_MEDIA_ID = AppearanceWallpaperMediaId.fromPrivateStorageToken(
    "sha256-v1:${"3".repeat(64)}",
)
private val GLOBAL_WALLPAPER_COLOR_ARGB = 0xff2457d6.toInt()
private val CONVERSATION_WALLPAPER_COLOR_ARGB = 0xffb43b63.toInt()
private val REPLACEMENT_CONVERSATION_WALLPAPER_COLOR_ARGB = 0xff3a8f5b.toInt()
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
private const val UPDATED_FOCAL_X = 270
private const val UPDATED_FOCAL_Y = 830
private const val UPDATED_DIM = 720
private const val WALLPAPER_PIXEL_TOLERANCE = 0.035f
private const val WALLPAPER_PIXEL_SAMPLE_STRIDE = 8
private const val MINIMUM_WALLPAPER_PIXEL_SAMPLES = 24
private const val COLD_RESTART_GATE_ARGUMENT = "auroraEmulatorWallpaperColdRestart"
private const val COLD_RESTART_PHASE_ARGUMENT = "auroraEmulatorWallpaperColdRestartPhase"
private const val COLD_RESTART_PHASE_PREPARE = "prepare"
private const val COLD_RESTART_PHASE_VERIFY = "verify"
private const val COLD_RESTART_PHASE_CLEANUP = "cleanup"
private const val COLD_RESTART_PREFERENCES = "aurora_wallpaper_cold_restart_evidence"
private const val COLD_RESTART_VERSION = 2
private const val COLD_RESTART_KEY_VERSION = "version"
private const val COLD_RESTART_KEY_STATE = "state"
private const val COLD_RESTART_STATE_PREPARING = "preparing"
private const val COLD_RESTART_STATE_PREPARED = "prepared"
private const val COLD_RESTART_STATE_VERIFIED = "verified"
private const val COLD_RESTART_KEY_MEDIA_ID = "media_id"
private const val COLD_RESTART_KEY_REVISION = "revision"
private const val COLD_RESTART_KEY_DIM = "dim"
private const val COLD_RESTART_KEY_FOCAL_X = "focal_x"
private const val COLD_RESTART_KEY_FOCAL_Y = "focal_y"
private const val COLD_RESTART_KEY_BASELINE_FILES = "baseline_files"
private const val COLD_RESTART_KEY_BASELINE_GRANTS = "baseline_grants"
private const val COLD_RESTART_KEY_EXPECTED_MEDIA_ID = "expected_media_id"
private const val COLD_RESTART_KEY_PENDING_FILE = "pending_file"
private const val COLD_RESTART_KEY_PREPARED_PID = "prepared_pid"
private const val COLD_RESTART_KEY_PREPARED_START_UPTIME = "prepared_start_uptime"
private const val COLD_RESTART_KEY_VERIFIED_PID = "verified_pid"
private const val COLD_RESTART_KEY_VERIFIED_START_UPTIME = "verified_start_uptime"
private const val COLD_RESTART_DIM_PERMILL = 470
private const val COLD_RESTART_FOCAL_X_PERMILL = 230
private const val COLD_RESTART_FOCAL_Y_PERMILL = 770
private const val COLD_RESTART_COLOR_TOLERANCE = 20
private const val COLD_RESTART_TIMEOUT_MILLIS = 30_000L
private const val COLD_RESTART_PENDING_FILE_NAME =
    ".pending-00000000-0000-0000-0000-000000000002"
private const val COLD_RESTART_EXPECTED_MEDIA_DIRECTORY =
    "aurora-wallpaper-cold-restart-expected"
private val COLD_RESTART_SOURCE_URI =
    Uri.parse("content://org.aurorasms.app.wallpaper.testprovider/cold-restart.png")
private val COLD_RESTART_WALLPAPER_COLOR_ARGB = 0xff2457d6.toInt()
private val COLD_RESTART_PENDING_BYTES = byteArrayOf(0x41)
private const val COLD_RESTART_GATE_REQUIRED = "cold restart wallpaper gate was not enabled"
private const val COLD_RESTART_EMULATOR_REQUIRED = "cold restart wallpaper evidence requires an emulator"
private const val COLD_RESTART_API_REQUIRED = "cold restart wallpaper evidence requires API 36"
private const val COLD_RESTART_PHASE_INVALID = "cold restart wallpaper phase was invalid"
private const val COLD_RESTART_APPLICATION_MISSING = "AuroraSMS application was unavailable"
private const val COLD_RESTART_STATE_NOT_READY = "AuroraSMS state storage did not become ready"
private const val COLD_RESTART_STALE_CHECKPOINT = "cold restart wallpaper checkpoint already exists"
private const val COLD_RESTART_CONVERSATION_ASSIGNMENT_NOT_EMPTY =
    "cold restart conversation wallpaper baseline was not empty"
private const val COLD_RESTART_APPLY_FAILED = "cold restart wallpaper Apply failed"
private const val COLD_RESTART_ASSIGNMENT_MISSING = "cold restart wallpaper assignment was missing"
private const val COLD_RESTART_ASSIGNMENT_MISMATCH = "cold restart wallpaper assignment changed"
private const val COLD_RESTART_GRANT_COUNT_CHANGED = "cold restart persisted URI grant count changed"
private const val COLD_RESTART_CHECKPOINT_WRITE_FAILED =
    "cold restart wallpaper checkpoint could not be written"
private const val COLD_RESTART_CHECKPOINT_MISSING = "cold restart wallpaper checkpoint was missing"
private const val COLD_RESTART_CHECKPOINT_INVALID = "cold restart wallpaper checkpoint was invalid"
private const val COLD_RESTART_CHECKPOINT_CLEAR_FAILED =
    "cold restart wallpaper checkpoint could not be cleared"
private const val COLD_RESTART_EXPECTED_MEDIA_DIRECTORY_FAILED =
    "cold restart expected media directory could not be created"
private const val COLD_RESTART_EXPECTED_MEDIA_DERIVATION_FAILED =
    "cold restart expected media identity could not be derived"
private const val COLD_RESTART_EXPECTED_MEDIA_CLEANUP_FAILED =
    "cold restart expected media directory could not be cleared"
private const val COLD_RESTART_PROCESS_NOT_RESTARTED =
    "cold restart verification reused the preparation process"
private const val COLD_RESTART_RESET_FAILED = "cold restart wallpaper Reset failed"
private const val COLD_RESTART_RESET_DID_NOT_CLEAR = "cold restart wallpaper Reset did not clear"
private const val COLD_RESTART_MANAGED_DIRECTORY_INVALID =
    "cold restart managed wallpaper directory was invalid"
private const val COLD_RESTART_MANAGED_DIRECTORY_UNAVAILABLE =
    "cold restart managed wallpaper directory was unavailable"
private const val COLD_RESTART_MANAGED_FILE_INVALID =
    "cold restart managed wallpaper file was invalid"
private const val COLD_RESTART_MANAGED_FILE_NOT_NEW =
    "cold restart managed wallpaper file was not test-owned"
private const val COLD_RESTART_MANAGED_FILES_MISMATCH =
    "cold restart managed wallpaper file set changed"
private const val COLD_RESTART_MANAGED_FILES_NOT_RESTORED =
    "cold restart managed wallpaper baseline was not restored"
private const val COLD_RESTART_MANAGED_FILE_UNAVAILABLE =
    "cold restart managed wallpaper could not be loaded"
private const val COLD_RESTART_MANAGED_FILE_PIXELS_MISMATCH =
    "cold restart managed wallpaper pixels were unexpected"
private const val COLD_RESTART_PENDING_FILE_INVALID =
    "cold restart pending wallpaper fixture was invalid"
private const val COLD_RESTART_PENDING_FILE_WRITE_FAILED =
    "cold restart pending wallpaper fixture could not be written"
private const val COLD_RESTART_PENDING_FILE_ALREADY_EXISTS =
    "cold restart pending wallpaper fixture already existed"
private const val COLD_RESTART_PENDING_FILE_DELETE_FAILED =
    "cold restart pending wallpaper fixture could not be deleted"
private const val COLD_RESTART_PENDING_FILE_SURVIVED =
    "startup reconciliation did not remove the cold restart pending fixture"
private const val COLD_RESTART_RECOVERY_ASSIGNMENT_MISMATCH =
    "cold restart recovery refused an unrelated assignment"
private const val COLD_RESTART_RECOVERY_RECONCILE_FAILED =
    "cold restart recovery could not reconcile managed files"
private const val COLD_RESTART_SYNTHETIC_WALLPAPER_USED =
    "cold restart root renderer used the synthetic wallpaper repository"
private const val TIMEOUT_MILLIS = 10_000L
