// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.flowOf
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
import org.aurorasms.app.compose.ComposeMessageActivity
import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.drafts.SerializedDraftWriterLease
import org.aurorasms.app.drafts.SerializedDraftWriterPool
import org.aurorasms.app.message.FirstContactOwnershipCommand
import org.aurorasms.app.message.FirstContactOwnershipController
import org.aurorasms.app.message.FirstContactOwnershipFailureReason
import org.aurorasms.app.message.FirstContactOwnershipResult
import org.aurorasms.app.message.ThreadSmsRecoveryResult
import org.aurorasms.app.message.ThreadSmsSendAttempt
import org.aurorasms.app.message.ThreadSmsSendCommand
import org.aurorasms.app.message.ThreadSmsSendController
import org.aurorasms.app.message.ThreadSmsSendObservation
import org.aurorasms.app.message.ThreadSmsSendPhase
import org.aurorasms.app.message.UnavailableFirstContactOwnershipController
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.aurorasms.app.settings.SETTINGS_BACKUP_RESTORE_TEST_TAG
import org.aurorasms.app.settings.SETTINGS_BACK_TEST_TAG
import org.aurorasms.app.settings.SETTINGS_SCREEN_TEST_TAG
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
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
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
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftAttachmentRepository
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewAppearanceProfile
import org.aurorasms.core.state.NewDraft
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.ContactDiscovery
import org.aurorasms.core.telephony.ContactDiscoveryResult
import org.aurorasms.core.telephony.DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT
import org.aurorasms.core.telephony.DiscoveredContact
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.telephony.UnavailableContactDiscovery
import org.aurorasms.feature.conversations.AttachmentPreviewResult
import org.aurorasms.feature.conversations.BoundedPreviewLoader
import org.aurorasms.feature.conversations.COMPOSER_SEND_TEST_TAG
import org.aurorasms.feature.conversations.COMPOSER_SCHEDULE_TEST_TAG
import org.aurorasms.feature.conversations.COMPOSER_TEST_TAG
import org.aurorasms.feature.conversations.COMPOSER_ATTACHMENT_TEST_TAG_PREFIX
import org.aurorasms.feature.conversations.CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_LIST_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCREEN_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SETTINGS_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.MESSAGE_BUBBLE_TEST_TAG
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
import org.junit.Assert.assertTrue
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
    fun inboxSettingsRouteExposesBackupRestoreAndReturnsToInbox() {
        val fixture = SyntheticFixture()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) { fixture.conversations.inboxLoadCount.get() == 1 }

            compose.onNodeWithTag(INBOX_MORE_ACTION_TEST_TAG).performClick()
            compose.onNodeWithTag(INBOX_SETTINGS_ACTION_TEST_TAG).performClick()
            waitForTag(SETTINGS_SCREEN_TEST_TAG)
            compose.onNodeWithTag(SETTINGS_BACKUP_RESTORE_TEST_TAG)
                .performScrollTo()
                .assertIsDisplayed()

            compose.onNodeWithTag(SETTINGS_BACK_TEST_TAG).performClick()
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(INBOX_SCREEN_TEST_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun eligibleOnePersonComposerFreezesOneExactSendAcrossUnknownRecreationAndAcknowledgement() {
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            threadSummary = syntheticThreadSummary(
                participants = SYNTHETIC_SEND_PARTICIPANTS,
                latestSubscriptionId = SYNTHETIC_SEND_SUBSCRIPTION.id,
            ),
            verifiedIdentity = SYNTHETIC_SEND_VERIFIED_IDENTITY,
            subscriptionRepository = FixedSubscriptionRepository(SYNTHETIC_SEND_SUBSCRIPTION),
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            openSyntheticThread()
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement(SYNTHETIC_SEND_DRAFT)
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_SEND_DRAFT)
            waitForDisplayedText("Draft saved locally · 1 SMS")
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
                .assertIsEnabled()
                .performClick()

            compose.waitUntil(TIMEOUT_MILLIS) { sendController.sendCount == 1 }
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            waitForDisplayedText("Submitting safely…")

            val command = sendController.commandsSnapshot().single()
            val storedDraft = checkNotNull(
                fixture.drafts.snapshot(DraftIdentity.ProviderThread(SYNTHETIC_THREAD_ID)),
            )
            assertEquals(SYNTHETIC_SEND_VERIFIED_IDENTITY, command.identity)
            assertEquals(SYNTHETIC_SEND_SUBSCRIPTION.id, command.subscriptionId)
            assertEquals(storedDraft.id, command.draftId)
            assertEquals(storedDraft.revision, command.draftRevision)
            assertEquals(SYNTHETIC_SEND_DRAFT, storedDraft.body)

            sendController.releaseAsSubmissionUnknown()
            waitForDisplayedText("Send status unknown. Check the conversation before trying again.")
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsEnabled()
            assertEquals(1, sendController.sendCount)

            scenario.recreate()

            waitForTag(THREAD_SCREEN_TEST_TAG)
            waitForTag(THREAD_LIST_TEST_TAG)
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .assertTextContains(SYNTHETIC_SEND_DRAFT)
                .assertIsNotEnabled()
            waitForDisplayedText("Send status unknown. Check the conversation before trying again.")
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
                .assertIsEnabled()
                .performClick()
            waitForDisplayedText("Send status unknown")
            waitForDisplayedText(
                "Android may have accepted this message. Check the conversation before keeping " +
                    "this text as a draft; sending it again could create a duplicate.",
            )
            compose.onNodeWithText("Keep as draft").performClick()

            compose.waitUntil(TIMEOUT_MILLIS) { sendController.acknowledgementCount == 1 }
            waitForDisplayedText("Draft saved locally · 1 SMS")
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .assertTextContains(SYNTHETIC_SEND_DRAFT)
                .assertIsEnabled()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsEnabled()
            assertEquals(1, sendController.sendCount)
            assertEquals(
                SYNTHETIC_SEND_DRAFT,
                fixture.drafts.snapshot(DraftIdentity.ProviderThread(SYNTHETIC_THREAD_ID))?.body,
            )
        }
    }

    @Test
    fun groupThreadRoutesOneDurablyOwnedMmsWithoutSmsFanout() {
        val groupParticipants = SYNTHETIC_VERIFIED_PARTICIPANTS.take(3)
        val groupIdentity = SYNTHETIC_SEND_VERIFIED_IDENTITY.copy(participants = groupParticipants)
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            threadSummary = syntheticThreadSummary(
                participants = groupParticipants,
                latestSubscriptionId = SYNTHETIC_SEND_SUBSCRIPTION.id,
            ),
            verifiedIdentity = groupIdentity,
            subscriptionRepository = FixedSubscriptionRepository(SYNTHETIC_SEND_SUBSCRIPTION),
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openSyntheticThread()
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement("Synthetic group text")
            waitForDisplayedText("Draft saved locally · MMS")
            compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
                .assertIsEnabled()
                .performClick()

            compose.waitUntil(TIMEOUT_MILLIS) { sendController.sendCount == 1 }
            val command = sendController.commandsSnapshot().single()
            assertEquals(groupIdentity, command.identity)
            assertEquals(SYNTHETIC_SEND_SUBSCRIPTION.id, command.subscriptionId)
            assertEquals(MessageTransportKind.MMS, command.transport)
            assertTrue(command.attachments.isEmpty())

            sendController.releaseAsSubmissionUnknown()
            waitForDisplayedText("Send status unknown. Check the conversation before trying again.")
            assertEquals(1, sendController.sendCount)
        }
    }

    @Test
    fun durableImageAttachmentRestoresAcrossActivityRecreationAndRoutesOneMms() = runBlocking {
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            threadSummary = syntheticThreadSummary(
                participants = SYNTHETIC_SEND_PARTICIPANTS,
                latestSubscriptionId = SYNTHETIC_SEND_SUBSCRIPTION.id,
            ),
            verifiedIdentity = SYNTHETIC_SEND_VERIFIED_IDENTITY,
            subscriptionRepository = FixedSubscriptionRepository(SYNTHETIC_SEND_SUBSCRIPTION),
            threadSmsSendController = sendController,
        )
        val draft = fixture.drafts.create(
            NewDraft(
                identity = DraftIdentity.ProviderThread(SYNTHETIC_THREAD_ID),
                body = "Synthetic restored image caption",
                subject = null,
                createdTimestampMillis = 29_000L,
                updatedTimestampMillis = 29_000L,
            ),
        ).successValue()
        val durableAttachment = validDraftAttachment(
            DraftAttachment.IMAGE_PNG,
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
        )
        fixture.draftAttachments
            .replace(draft.id, draft.revision, listOf(durableAttachment))
            .successValue()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            openSyntheticThread()
            waitForTag("$COMPOSER_ATTACHMENT_TEST_TAG_PREFIX-0")
            waitForDisplayedText("Draft saved locally · MMS")
            compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).assertIsNotEnabled()

            scenario.recreate()

            waitForTag(THREAD_SCREEN_TEST_TAG)
            waitForTag("$COMPOSER_ATTACHMENT_TEST_TAG_PREFIX-0")
            waitForDisplayedText("Draft saved locally · MMS")
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsEnabled().performClick()

            compose.waitUntil(TIMEOUT_MILLIS) { sendController.sendCount == 1 }
            val command = sendController.commandsSnapshot().single()
            assertEquals(MessageTransportKind.MMS, command.transport)
            assertEquals(SYNTHETIC_SEND_VERIFIED_IDENTITY, command.identity)
            assertEquals(1, command.attachments.size)
            assertEquals(
                validOutgoingAttachment(
                    OutgoingMmsAttachment.IMAGE_PNG,
                    durableAttachment.copyBytes(),
                ),
                command.attachments.single(),
            )

            sendController.releaseAsSubmissionUnknown()
            waitForDisplayedText("Send status unknown. Check the conversation before trying again.")
            assertEquals(1, sendController.sendCount)
        }
    }

    @Test
    fun durableImageAttachmentSurvivesHostForceStopAndColdProcessMmsRoute() {
        when (requireExplicitAttachmentColdRestartPhase()) {
            ATTACHMENT_COLD_RESTART_PHASE_PREPARE -> prepareAttachmentColdRestart()
            ATTACHMENT_COLD_RESTART_PHASE_VERIFY -> verifyAttachmentColdRestart()
            ATTACHMENT_COLD_RESTART_PHASE_CLEANUP -> cleanupAttachmentColdRestart()
            else -> throw AssertionError(ATTACHMENT_COLD_RESTART_PHASE_INVALID)
        }
    }

    @Test
    fun unavailableDraftAttachmentStorageBlocksSendWithoutTransportAttempt() = runBlocking {
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            threadSummary = syntheticThreadSummary(
                participants = SYNTHETIC_SEND_PARTICIPANTS,
                latestSubscriptionId = SYNTHETIC_SEND_SUBSCRIPTION.id,
            ),
            verifiedIdentity = SYNTHETIC_SEND_VERIFIED_IDENTITY,
            subscriptionRepository = FixedSubscriptionRepository(SYNTHETIC_SEND_SUBSCRIPTION),
            threadSmsSendController = sendController,
            draftAttachmentRepositoryOverride = UnavailableDraftAttachmentRepository,
        )
        fixture.drafts.create(
            NewDraft(
                identity = DraftIdentity.ProviderThread(SYNTHETIC_THREAD_ID),
                body = "Synthetic protected image caption",
                subject = null,
                createdTimestampMillis = 30_000L,
                updatedTimestampMillis = 30_000L,
            ),
        ).successValue()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openSyntheticThread()
            waitForDisplayedText(
                "Saved image attachments are unavailable. " +
                    "Sending is disabled to protect your draft.",
            )
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, sendController.sendCount)
        }
    }

    @Test
    fun externalActionSendToComposeRemainsReviewOnlyWithSendDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as AuroraSmsApplication
        val recipient = ParticipantAddress("+15550100001")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )
        clearDraft(application.container.draftRepository, identity)
        val intent = Intent(context, ComposeMessageActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:${recipient.value}")
            putExtra("sms_body", SYNTHETIC_EXTERNAL_COMPOSE_BODY)
        }

        try {
            ActivityScenario.launch<ComposeMessageActivity>(intent).use {
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithText(recipient.value).assertIsDisplayed()
                compose.onNodeWithText(SYNTHETIC_EXTERNAL_COMPOSE_BODY).assertIsDisplayed()
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
                waitForDisplayedText(
                    context.getString(
                        org.aurorasms.feature.conversations.R.string.new_message_external_review_only,
                    ),
                )
                assertNull(readDraft(application.container.draftRepository, identity))
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_EDITED_EXTERNAL_COMPOSE_BODY)
                waitForDraftBody(
                    repository = application.container.draftRepository,
                    identity = identity,
                    body = SYNTHETIC_EDITED_EXTERNAL_COMPOSE_BODY,
                )
            }
            assertEquals(
                SYNTHETIC_EDITED_EXTERNAL_COMPOSE_BODY,
                readDraft(application.container.draftRepository, identity)?.body,
            )
        } finally {
            clearDraft(application.container.draftRepository, identity)
        }
    }

    @Test
    fun newChatInternalExternalAndRecreationNeverInvokeFirstContactOwnership() {
        val firstContact = RecordingFirstContactOwnershipController()
        val fixture = SyntheticFixture(firstContactOwnershipController = firstContact)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        assertFirstContactOwnershipIsAbsentFromNewMessageRuntimeGraph()

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, firstContact.invocationCount)

            scenario.recreate()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, firstContact.invocationCount)
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val externalIntent = Intent(context, ComposeMessageActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:+15550100041")
            putExtra("sms_body", "Synthetic dormant first-contact review")
        }
        ActivityScenario.launch<ComposeMessageActivity>(externalIntent).use { scenario ->
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, firstContact.invocationCount)

            scenario.recreate()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, firstContact.invocationCount)
        }
    }

    @Test
    fun newExternalIntentReplacesTheWholeReviewedRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as AuroraSmsApplication
        val firstRecipient = ParticipantAddress("+15550100011")
        val secondRecipient = ParticipantAddress("+15550100012")
        val firstIdentity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(firstRecipient)),
        )
        val secondIdentity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(secondRecipient)),
        )
        clearDraft(application.container.draftRepository, firstIdentity)
        clearDraft(application.container.draftRepository, secondIdentity)
        val firstIntent = Intent(context, ComposeMessageActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:${firstRecipient.value}")
            putExtra("sms_body", SYNTHETIC_FIRST_REUSED_EXTERNAL_BODY)
        }
        try {
            ActivityScenario.launch<ComposeMessageActivity>(firstIntent).use { scenario ->
                val firstActivityIdentity = AtomicInteger()
                scenario.onActivity { activity ->
                    firstActivityIdentity.set(System.identityHashCode(activity))
                }
                waitForDisplayedText(SYNTHETIC_FIRST_REUSED_EXTERNAL_BODY)
                assertNull(readDraft(application.container.draftRepository, firstIdentity))

                scenario.onActivity { activity ->
                    val replacementIntent = Intent(activity.intent).apply {
                        action = Intent.ACTION_SENDTO
                        data = Uri.parse("smsto:${secondRecipient.value}")
                        putExtra("sms_body", SYNTHETIC_SECOND_REUSED_EXTERNAL_BODY)
                    }
                    ComposeMessageActivity::class.java
                        .getDeclaredMethod("onNewIntent", Intent::class.java)
                        .apply { isAccessible = true }
                        .invoke(activity, replacementIntent)
                }
                // ActivityScenario matches recreated instances against its original Intent.
                // Keep that harness-only copy aligned with the Intent now owned by the Activity.
                firstIntent.data = Uri.parse("smsto:${secondRecipient.value}")

                waitForDisplayedText(SYNTHETIC_SECOND_REUSED_EXTERNAL_BODY)
                compose.onNodeWithText(secondRecipient.value).assertIsDisplayed()
                compose.onNodeWithText(SYNTHETIC_FIRST_REUSED_EXTERNAL_BODY).assertDoesNotExist()
                compose.onNodeWithText(firstRecipient.value).assertDoesNotExist()
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
                scenario.onActivity { activity ->
                    assertEquals(
                        firstActivityIdentity.get(),
                        System.identityHashCode(activity),
                    )
                }
                assertNull(readDraft(application.container.draftRepository, secondIdentity))

                compose.onNodeWithTag(COMPOSER_TEST_TAG).performTextReplacement("")
                compose.waitUntil(TIMEOUT_MILLIS) {
                    readDraft(application.container.draftRepository, secondIdentity)?.body == null
                }
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(
                            org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                        ).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(SYNTHETIC_UNCOMMITTED_REUSED_RECIPIENT)

                scenario.recreate()
                compose.onNodeWithText(secondRecipient.value).assertIsDisplayed()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).assertTextContains(SYNTHETIC_UNCOMMITTED_REUSED_RECIPIENT)
                compose.onNodeWithText(SYNTHETIC_FIRST_REUSED_EXTERNAL_BODY).assertDoesNotExist()
                compose.onNodeWithText(firstRecipient.value).assertDoesNotExist()
            }
        } finally {
            clearDraft(application.container.draftRepository, firstIdentity)
            clearDraft(application.container.draftRepository, secondIdentity)
        }
    }

    @Test
    fun externalSendToStartsWithDiscoveryClosedAndNewIntentDisposesBlankPanel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as AuroraSmsApplication
        val firstRecipient = ParticipantAddress("+15550100034")
        val secondRecipient = ParticipantAddress("+15550100035")
        val firstIdentity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(firstRecipient)),
        )
        val secondIdentity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(secondRecipient)),
        )
        clearDraft(application.container.draftRepository, firstIdentity)
        clearDraft(application.container.draftRepository, secondIdentity)
        val permissionBefore = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
        val firstIntent = Intent(context, ComposeMessageActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("smsto:${firstRecipient.value}")
        }

        var launchedScenario: ActivityScenario<ComposeMessageActivity>? = null
        try {
            val scenario = ActivityScenario.launch<ComposeMessageActivity>(firstIntent)
            launchedScenario = scenario
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithText(firstRecipient.value).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            ).assertDoesNotExist()
            compose.waitUntil(TIMEOUT_MILLIS) {
                runCatching {
                    compose.onNodeWithTag(
                        org.aurorasms.feature.conversations
                            .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                    ).assertIsEnabled()
                }.isSuccess
            }

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            assertEquals(
                permissionBefore,
                context.checkSelfPermission(Manifest.permission.READ_CONTACTS),
            )

            scenario.onActivity { activity ->
                val replacement = Intent(activity.intent).apply {
                    action = Intent.ACTION_SENDTO
                    data = Uri.parse("smsto:${secondRecipient.value}")
                    removeExtra("sms_body")
                    removeExtra(Intent.EXTRA_TEXT)
                }
                ComposeMessageActivity::class.java
                    .getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, replacement)
            }

            waitForDisplayedText(secondRecipient.value)
            compose.onNodeWithText(firstRecipient.value).assertDoesNotExist()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            ).assertDoesNotExist()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            assertEquals(
                permissionBefore,
                context.checkSelfPermission(Manifest.permission.READ_CONTACTS),
            )
            assertNull(readDraft(application.container.draftRepository, firstIdentity))
            assertNull(readDraft(application.container.draftRepository, secondIdentity))
        } finally {
            launchedScenario?.let { scenario ->
                runCatching {
                    scenario.onActivity { activity -> activity.finishAndRemoveTask() }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            clearDraft(application.container.draftRepository, firstIdentity)
            clearDraft(application.container.draftRepository, secondIdentity)
        }
    }

    @Test
    fun newChatContactSelectionUsesParticipantDraftPathWithoutTransportAttempt() {
        val recipient = ParticipantAddress("+15550100031")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )
        val discovery = RecordingContactDiscovery { query, resultLimit ->
            assertEquals(DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT, resultLimit)
            when (query) {
                SYNTHETIC_CONTACT_QUERY -> ContactDiscoveryResult.Available(
                    contacts = listOf(
                        DiscoveredContact(
                            address = recipient,
                            displayName = "Synthetic Ada",
                            photoUri = null,
                        ),
                    ),
                    truncated = false,
                )
                SYNTHETIC_REVOKED_CONTACT_QUERY -> ContactDiscoveryResult.PermissionDenied
                else -> error("Unexpected synthetic contact query")
            }
        }
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            contactDiscovery = discovery,
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openNewChat()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).assertIsEnabled().performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            assertEquals(0, discovery.requestCount)

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).performTextReplacement(SYNTHETIC_CONTACT_QUERY)
            compose.waitUntil(TIMEOUT_MILLIS) { discovery.requestCount == 1 }
            waitForTag(
                "${org.aurorasms.feature.conversations.NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX}-0",
            )
            compose.onNodeWithText("Synthetic Ada").assertIsDisplayed()
            compose.onNodeWithText(recipient.value).assertIsDisplayed()
            compose.onNodeWithTag(
                "${org.aurorasms.feature.conversations.NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX}-0",
            ).performClick()

            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
                ).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Synthetic Ada").assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            compose.waitUntil(TIMEOUT_MILLIS) {
                runCatching { compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).assertIsEnabled().performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).performTextReplacement(SYNTHETIC_REVOKED_CONTACT_QUERY)
            compose.waitUntil(TIMEOUT_MILLIS) { discovery.requestCount == 2 }
            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
                ).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Synthetic Ada").assertDoesNotExist()
            compose.onNodeWithText(recipient.value).assertIsDisplayed()

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).assertIsNotEnabled()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG,
            ).assertIsEnabled()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).assertIsNotEnabled()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG,
            ).assertIsEnabled()
            assertEquals(2, discovery.requestCount)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()

            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement(SYNTHETIC_CONTACT_DRAFT_BODY)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.drafts.snapshot(identity)?.body == SYNTHETIC_CONTACT_DRAFT_BODY
            }

            assertEquals(
                listOf(
                    SyntheticContactDiscoveryRequest(
                        query = SYNTHETIC_CONTACT_QUERY,
                        resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
                    ),
                    SyntheticContactDiscoveryRequest(
                        query = SYNTHETIC_REVOKED_CONTACT_QUERY,
                        resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
                    ),
                ),
                discovery.requestsSnapshot(),
            )
            assertEquals(1, fixture.drafts.snapshotCount())
            assertEquals(0, sendController.sendCount)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun deniedContactsPermissionNeverQueriesAndManualRecipientDraftingStillWorks() {
        val recipient = ParticipantAddress("+15550100032")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )
        val discovery = RecordingContactDiscovery { _, _ ->
            throw AssertionError("Permission-denied New Chat must not query contact discovery")
        }
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            contactDiscovery = discovery,
            contactsPermissionGranted = false,
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openNewChat()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).assertIsNotEnabled()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG,
            ).assertIsEnabled().performClick()
            assertEquals(1, fixture.harness.contactsPermissionRequestCount)
            assertEquals(0, discovery.requestCount)

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement(recipient.value)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.waitUntil(TIMEOUT_MILLIS) {
                runCatching { compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement(SYNTHETIC_MANUAL_CONTACT_DRAFT_BODY)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.drafts.snapshot(identity)?.body == SYNTHETIC_MANUAL_CONTACT_DRAFT_BODY
            }

            assertEquals(0, discovery.requestCount)
            assertEquals(0, sendController.sendCount)
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun newChatContactQueryAndResultsAreNotReplayedAfterActivityRecreation() {
        val recipient = ParticipantAddress("+15550100033")
        val discovery = RecordingContactDiscovery { _, _ ->
            ContactDiscoveryResult.Available(
                contacts = listOf(
                    DiscoveredContact(
                        address = recipient,
                        displayName = "Synthetic Grace",
                        photoUri = null,
                    ),
                ),
                truncated = false,
            )
        }
        val fixture = SyntheticFixture(contactDiscovery = discovery)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            openNewChat()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
            ).performTextReplacement(SYNTHETIC_CONTACT_QUERY)
            compose.waitUntil(TIMEOUT_MILLIS) { discovery.requestCount == 1 }
            waitForTag(
                "${org.aurorasms.feature.conversations.NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX}-0",
            )

            scenario.recreate()

            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            ).assertDoesNotExist()
            compose.onNodeWithText("Synthetic Grace").assertDoesNotExist()
            assertEquals(1, discovery.requestCount)

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(
                org.aurorasms.feature.conversations
                    .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
            )
            compose.onNodeWithText("Synthetic Grace").assertDoesNotExist()
            compose.waitForIdle()
            assertEquals(1, discovery.requestCount)
        }
    }

    @Test
    fun stoppingNewChatCancelsInFlightContactDiscoveryAndClearsItsPanel() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ContactDiscoveryResult>()
        val discovery = RecordingContactDiscovery { _, _ ->
            started.complete(Unit)
            try {
                release.await()
            } catch (failure: CancellationException) {
                cancelled.complete(Unit)
                throw failure
            }
        }
        val fixture = SyntheticFixture(contactDiscovery = discovery)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
                openNewChat()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                ).performClick()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
                ).performTextReplacement(SYNTHETIC_CONTACT_QUERY)
                compose.waitUntil(TIMEOUT_MILLIS) { started.isCompleted }
                assertEquals(1, discovery.requestCount)

                scenario.moveToState(Lifecycle.State.CREATED)
                runBlocking { withTimeout(TIMEOUT_MILLIS) { cancelled.await() } }

                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
                ).assertDoesNotExist()
                assertEquals(1, discovery.requestCount)
            }
        } finally {
            release.complete(ContactDiscoveryResult.Unavailable)
        }
    }

    @Test
    fun replacingNewChatContactQueryCancelsInFlightDiscoveryAndPublishesOnlyNewestResult() {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<ContactDiscoveryResult>()
        val newestRecipient = ParticipantAddress("+15550100036")
        val discovery = RecordingContactDiscovery { query, resultLimit ->
            assertEquals(DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT, resultLimit)
            when (query) {
                SYNTHETIC_REPLACED_CONTACT_QUERY -> {
                    firstStarted.complete(Unit)
                    try {
                        releaseFirst.await()
                    } catch (failure: CancellationException) {
                        firstCancelled.complete(Unit)
                        throw failure
                    }
                }
                SYNTHETIC_REPLACEMENT_CONTACT_QUERY -> ContactDiscoveryResult.Available(
                    contacts = listOf(
                        DiscoveredContact(
                            address = newestRecipient,
                            displayName = "Synthetic newest contact",
                            photoUri = null,
                        ),
                    ),
                    truncated = false,
                )
                else -> error("Unexpected synthetic replacement query")
            }
        }
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            contactDiscovery = discovery,
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
                openNewChat()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                ).performClick()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
                ).performTextReplacement(SYNTHETIC_REPLACED_CONTACT_QUERY)
                compose.waitUntil(TIMEOUT_MILLIS) { firstStarted.isCompleted }

                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
                ).performTextReplacement(SYNTHETIC_REPLACEMENT_CONTACT_QUERY)
                compose.waitUntil(TIMEOUT_MILLIS) { firstCancelled.isCompleted }
                compose.waitUntil(TIMEOUT_MILLIS) { discovery.requestCount == 2 }
                waitForTag(
                    "${org.aurorasms.feature.conversations.NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX}-0",
                )

                compose.onNodeWithText("Synthetic newest contact").assertIsDisplayed()
                compose.onNodeWithText(newestRecipient.value).assertIsDisplayed()
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
                assertEquals(
                    listOf(
                        SyntheticContactDiscoveryRequest(
                            query = SYNTHETIC_REPLACED_CONTACT_QUERY,
                            resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
                        ),
                        SyntheticContactDiscoveryRequest(
                            query = SYNTHETIC_REPLACEMENT_CONTACT_QUERY,
                            resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
                        ),
                    ),
                    discovery.requestsSnapshot(),
                )
                assertEquals(0, sendController.sendCount)
            }
        } finally {
            releaseFirst.complete(ContactDiscoveryResult.Unavailable)
        }
    }

    @Test
    fun closingNewChatContactPanelCancelsInFlightDiscoveryAndClearsTransientQuery() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ContactDiscoveryResult>()
        val discovery = RecordingContactDiscovery { query, resultLimit ->
            assertEquals(SYNTHETIC_PANEL_CLOSE_CONTACT_QUERY, query)
            assertEquals(DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT, resultLimit)
            started.complete(Unit)
            try {
                release.await()
            } catch (failure: CancellationException) {
                cancelled.complete(Unit)
                throw failure
            }
        }
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            contactDiscovery = discovery,
            threadSmsSendController = sendController,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
                openNewChat()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                ).performClick()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG,
                ).performTextReplacement(SYNTHETIC_PANEL_CLOSE_CONTACT_QUERY)
                compose.waitUntil(TIMEOUT_MILLIS) { started.isCompleted }

                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                ).performScrollTo().performClick()
                compose.waitUntil(TIMEOUT_MILLIS) { cancelled.isCompleted }
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
                ).assertDoesNotExist()

                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG,
                ).performScrollTo().performClick()
                waitForTag(
                    org.aurorasms.feature.conversations
                        .NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG,
                )
                compose.onNodeWithText(SYNTHETIC_PANEL_CLOSE_CONTACT_QUERY).assertDoesNotExist()
                assertEquals(1, discovery.requestCount)
                assertEquals(0, sendController.sendCount)
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            }
        } finally {
            release.complete(ContactDiscoveryResult.Unavailable)
        }
    }

    @Test
    fun newChatDraftSurvivesRecreationAndReloadsForTheSameParticipantSet() {
        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(threadSmsSendController = sendController)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val recipient = ParticipantAddress("+15550100002")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement(recipient.value)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.waitUntil(TIMEOUT_MILLIS) {
                runCatching {
                    compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                }.isSuccess
            }
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement(SYNTHETIC_NEW_CHAT_DRAFT_BODY)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.drafts.snapshot(identity)?.body == SYNTHETIC_NEW_CHAT_DRAFT_BODY
            }

            scenario.recreate()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            waitForDisplayedText(SYNTHETIC_NEW_CHAT_DRAFT_BODY)
            compose.onNodeWithText(recipient.value).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_BACK_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement(recipient.value)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            waitForDisplayedText(SYNTHETIC_NEW_CHAT_DRAFT_BODY)
            assertEquals(SYNTHETIC_NEW_CHAT_DRAFT_BODY, fixture.drafts.snapshot(identity)?.body)
            assertEquals(0, sendController.sendCount)
        }
    }

    @Test
    fun newChatRecreationPreservesAnEditStillWaitingForItsFirstAcknowledgement() {
        val gatedDrafts = GatedCreateDraftRepository()
        val fixture = SyntheticFixture(draftRepositoryOverride = gatedDrafts)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val recipient = ParticipantAddress("+15550100014")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
                waitForTag(INBOX_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
                ).performClick()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(recipient.value)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
                ).performClick()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_IN_FLIGHT_NEW_CHAT_DRAFT_BODY)
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.createStarted.isCompleted }
                assertNull(gatedDrafts.snapshot(identity))

                scenario.recreate()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                gatedDrafts.allowCreate.complete(Unit)

                compose.waitUntil(TIMEOUT_MILLIS) {
                    gatedDrafts.snapshot(identity)?.body == SYNTHETIC_IN_FLIGHT_NEW_CHAT_DRAFT_BODY
                }
                waitForDisplayedText(SYNTHETIC_IN_FLIGHT_NEW_CHAT_DRAFT_BODY)
                compose.onNodeWithText(recipient.value).assertIsDisplayed()
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
            }
        } finally {
            gatedDrafts.allowCreate.complete(Unit)
        }
    }

    @Test
    fun newChatRecreationKeepsTheNewestEditAcrossAnIntermediateAcknowledgement() {
        val gatedDrafts = GatedCreateDraftRepository()
        val fixture = SyntheticFixture(draftRepositoryOverride = gatedDrafts)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val recipient = ParticipantAddress("+15550100015")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
                waitForTag(INBOX_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
                ).performClick()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(recipient.value)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
                ).performClick()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_INTERMEDIATE_NEW_CHAT_DRAFT_BODY)
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.createStarted.isCompleted }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY)

                scenario.recreate()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY)
                gatedDrafts.allowCreate.complete(Unit)
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.updateStarted.isCompleted }
                assertEquals(
                    SYNTHETIC_INTERMEDIATE_NEW_CHAT_DRAFT_BODY,
                    gatedDrafts.snapshot(identity)?.body,
                )
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY)

                gatedDrafts.allowUpdate.complete(Unit)
                compose.waitUntil(TIMEOUT_MILLIS) {
                    gatedDrafts.snapshot(identity)?.body == SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY)
            }
        } finally {
            gatedDrafts.allowCreate.complete(Unit)
            gatedDrafts.allowUpdate.complete(Unit)
        }
    }

    @Test
    fun newChatKeepsRecipientsLockedUntilAnEmptyEditIsDurable() {
        val gatedDrafts = GatedCreateDraftRepository()
        val fixture = SyntheticFixture(draftRepositoryOverride = gatedDrafts)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val recipient = ParticipantAddress("+15550100016")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
                waitForTag(INBOX_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
                ).performClick()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(recipient.value)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
                ).performClick()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_CLEAR_GATED_NEW_CHAT_DRAFT_BODY)
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.createStarted.isCompleted }
                gatedDrafts.allowCreate.complete(Unit)
                compose.waitUntil(TIMEOUT_MILLIS) {
                    gatedDrafts.snapshot(identity)?.body == SYNTHETIC_CLEAR_GATED_NEW_CHAT_DRAFT_BODY
                }

                compose.onNodeWithTag(COMPOSER_TEST_TAG).performTextReplacement("")
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.updateStarted.isCompleted }
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).assertIsNotEnabled()
                compose.onNodeWithTag(
                    "${org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX}-0",
                ).assertIsNotEnabled()

                gatedDrafts.allowUpdate.complete(Unit)
                compose.waitUntil(TIMEOUT_MILLIS) {
                    gatedDrafts.snapshot(identity)?.body == null
                }
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(
                            org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                        ).assertIsEnabled()
                    }.isSuccess
                }
            }
        } finally {
            gatedDrafts.allowCreate.complete(Unit)
            gatedDrafts.allowUpdate.complete(Unit)
        }
    }

    @Test
    fun newChatConflictKeepsLocalTextDisplayOnlyAcrossRecreation() = runBlocking {
        val gatedDrafts = GatedCreateDraftRepository()
        val fixture = SyntheticFixture(draftRepositoryOverride = gatedDrafts)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recipient = ParticipantAddress("+15550100017")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )
        val durableWinnerBody = "Synthetic concurrent durable winner"

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
                waitForTag(INBOX_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
                ).performClick()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(recipient.value)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
                ).performClick()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_CONFLICTED_LOCAL_NEW_CHAT_BODY)
                compose.waitUntil(TIMEOUT_MILLIS) { gatedDrafts.createStarted.isCompleted }

                assertTrue(
                    gatedDrafts.createConcurrent(
                        NewDraft(
                            identity = identity,
                            body = durableWinnerBody,
                            subject = null,
                            createdTimestampMillis = 60_000L,
                            updatedTimestampMillis = 60_000L,
                        ),
                    ) is DraftRepositoryResult.Success,
                )
                gatedDrafts.allowCreate.complete(Unit)
                waitForDisplayedText(
                    context.getString(org.aurorasms.feature.conversations.R.string.draft_failed),
                )
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_CONFLICTED_LOCAL_NEW_CHAT_BODY)
                    .assertIsNotEnabled()
                assertEquals(durableWinnerBody, gatedDrafts.snapshot(identity)?.body)

                scenario.recreate()
                waitForDisplayedText(
                    context.getString(org.aurorasms.feature.conversations.R.string.draft_failed),
                )
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_CONFLICTED_LOCAL_NEW_CHAT_BODY)
                    .assertIsNotEnabled()
                assertEquals(durableWinnerBody, gatedDrafts.snapshot(identity)?.body)
            }
        } finally {
            gatedDrafts.allowCreate.complete(Unit)
            gatedDrafts.allowUpdate.complete(Unit)
        }
    }

    @Test
    fun newChatStorageFailureRecoversTheExactEditAfterRecreation() {
        val recoveringDrafts = GatedCreateDraftRepository(failFirstCreate = true)
        val fixture = SyntheticFixture(draftRepositoryOverride = recoveringDrafts)
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recipient = ParticipantAddress("+15550100018")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )

        try {
            ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use { scenario ->
                waitForTag(INBOX_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
                ).performClick()
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                ).performTextReplacement(recipient.value)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
                ).performClick()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    runCatching {
                        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    }.isSuccess
                }
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .performTextReplacement(SYNTHETIC_RECOVERED_NEW_CHAT_DRAFT_BODY)
                compose.waitUntil(TIMEOUT_MILLIS) { recoveringDrafts.createStarted.isCompleted }
                recoveringDrafts.allowCreate.complete(Unit)
                waitForDisplayedText(
                    context.getString(org.aurorasms.feature.conversations.R.string.draft_failed),
                )
                compose.onNodeWithTag(COMPOSER_TEST_TAG)
                    .assertTextContains(SYNTHETIC_RECOVERED_NEW_CHAT_DRAFT_BODY)
                    .assertIsNotEnabled()
                compose.waitForIdle()

                scenario.recreate()
                compose.waitUntil(TIMEOUT_MILLIS) {
                    recoveringDrafts.snapshot(identity)?.body ==
                        SYNTHETIC_RECOVERED_NEW_CHAT_DRAFT_BODY
                }
                waitForDisplayedText(SYNTHETIC_RECOVERED_NEW_CHAT_DRAFT_BODY)
                compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                compose.onNodeWithText(
                    context.getString(org.aurorasms.feature.conversations.R.string.draft_failed),
                ).assertDoesNotExist()
            }
        } finally {
            recoveringDrafts.allowCreate.complete(Unit)
            recoveringDrafts.allowUpdate.complete(Unit)
        }
    }

    @Test
    fun newChatValidatesAndCanonicalizesManualRecipientSetsBeforeDrafting() {
        val fixture = SyntheticFixture()
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = ParticipantAddress("+1 (555) 010-0013")
        val second = ParticipantAddress("synthetic@example.invalid")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(first, second)),
        )

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            waitForTag(INBOX_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
            ).performClick()
            waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).assertIsNotEnabled()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement("+")
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_SUPPORT_TEST_TAG,
            ).assertTextContains(
                context.getString(
                    org.aurorasms.feature.conversations.R.string.new_message_recipient_invalid,
                ),
            )
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
            assertEquals(0, fixture.drafts.snapshotCount())

            val tooMany = (0..100).joinToString(",") { index -> "recipient-$index" }
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement(tooMany)
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_SUPPORT_TEST_TAG,
            ).assertTextContains(
                context.getString(
                    org.aurorasms.feature.conversations.R.string.new_message_recipient_too_many,
                ),
            )
            assertEquals(0, fixture.drafts.snapshotCount())

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement("${first.value},${second.value}")
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                "${org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX}-0",
            ).assertIsDisplayed()
            compose.onNodeWithTag(
                "${org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX}-1",
            ).assertIsDisplayed()
            compose.waitUntil(TIMEOUT_MILLIS) {
                runCatching {
                    compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
                    compose.onNodeWithTag(
                        org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
                    ).assertIsEnabled()
                }.isSuccess
            }

            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).performTextReplacement("+15550100013")
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG,
            ).performClick()
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_SUPPORT_TEST_TAG,
            ).assertTextContains(
                context.getString(
                    org.aurorasms.feature.conversations.R.string.new_message_recipient_duplicate,
                ),
            )

            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .performTextReplacement(SYNTHETIC_MULTI_RECIPIENT_DRAFT_BODY)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.drafts.snapshot(identity)?.body == SYNTHETIC_MULTI_RECIPIENT_DRAFT_BODY
            }
            assertEquals(1, fixture.drafts.snapshotCount())
            compose.onNodeWithTag(
                org.aurorasms.feature.conversations.NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG,
            ).assertIsNotEnabled()
        }
    }

    @Test
    fun externalMmsPrefillCannotOverwriteAnExistingParticipantDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as AuroraSmsApplication
        val recipient = ParticipantAddress("+15550100003")
        val identity = DraftIdentity.ParticipantSet(
            DraftParticipantSetKey.fromParticipants(listOf(recipient)),
        )
        clearDraft(application.container.draftRepository, identity)
        val existing = runBlocking {
            application.container.draftRepository.create(
                NewDraft(
                    identity = identity,
                    body = SYNTHETIC_EXISTING_NEW_CHAT_DRAFT,
                    subject = SYNTHETIC_EXISTING_NEW_CHAT_SUBJECT,
                    createdTimestampMillis = 40_000L,
                    updatedTimestampMillis = 40_000L,
                ),
            )
        }
        assertTrue(existing is DraftRepositoryResult.Success)
        val existingDraft = existing.successValue()
        val intent = Intent(context, ComposeMessageActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.parse("mmsto:${recipient.value}")
            putExtra("sms_body", SYNTHETIC_CONFLICTING_EXTERNAL_BODY)
        }

        try {
            ActivityScenario.launch<ComposeMessageActivity>(intent).use {
                waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
                waitForDisplayedText(SYNTHETIC_EXISTING_NEW_CHAT_DRAFT)
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_EXTERNAL_CONFLICT_TEST_TAG,
                ).assertIsDisplayed()
                compose.onNodeWithTag(
                    org.aurorasms.feature.conversations.NEW_MESSAGE_EXPLICIT_MMS_TEST_TAG,
                ).assertIsDisplayed()
                compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
                assertEquals(existingDraft, readDraft(application.container.draftRepository, identity))
            }
            val afterClose = readDraft(application.container.draftRepository, identity)
            assertEquals(existingDraft, afterClose)
            assertTrue(afterClose?.body?.contains(SYNTHETIC_CONFLICTING_EXTERNAL_BODY) == false)
        } finally {
            clearDraft(application.container.draftRepository, identity)
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
            onView(isRoot()).perform(closeSoftKeyboard())

            waitForTag(THREAD_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) { fixture.index.anchorLoadCount.get() == 1 }
            waitForTag(THREAD_LIST_TEST_TAG)
            waitForTag(THREAD_MORE_ACTION_TEST_TAG)
            waitForDisplayedText(SYNTHETIC_EXACT_ANCHOR)
            scrollThreadRowIntoView("Synthetic anchor row 20", THREAD_ANCHOR_INDEX)
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
            waitForDisplayedThreadRow("Synthetic anchor row 20")
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
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            waitForDisplayedThreadRow("Synthetic anchor row 20")
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
            waitForDisplayedThreadRow("Synthetic anchor row 20")
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
            waitForDisplayedThreadRow("Synthetic anchor row 20")
            compose.onNodeWithTag(COMPOSER_TEST_TAG).assertTextContains(SYNTHETIC_DRAFT)
            scrollThreadRowIntoView("Synthetic anchor row 10", THREAD_REENTRY_STALE_INDEX)
            compose.onNodeWithTag(COMPOSER_TEST_TAG).performClick().assertIsFocused()
            moveFocusOffComposer(scenario)
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            waitForTag(SEARCH_SCREEN_TEST_TAG)
            compose.onNodeWithTag(SEARCH_FIELD_TEST_TAG).assertTextContains(SYNTHETIC_QUERY)
            compose.onNodeWithTag(THEME_STUDIO_SCREEN_TEST_TAG).assertDoesNotExist()
            val anchorLoadsBeforeReentry = fixture.index.anchorLoadCount.get()
            waitForTag(SEARCH_HIT_TEST_TAG)
            compose.onNodeWithTag(SEARCH_HIT_TEST_TAG).performClick()
            onView(isRoot()).perform(closeSoftKeyboard())
            waitForTag(THREAD_SCREEN_TEST_TAG)
            compose.waitUntil(TIMEOUT_MILLIS) {
                fixture.index.anchorLoadCount.get() == anchorLoadsBeforeReentry + 1
            }
            waitForTag(THREAD_LIST_TEST_TAG)
            waitForDisplayedText(SYNTHETIC_EXACT_ANCHOR)
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
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertIsDisplayed()
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

            scrollThreadRowIntoView("Synthetic anchor row 20", THREAD_ANCHOR_INDEX)
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
            waitForDisplayedThreadRow("Synthetic anchor row 20")

            compose.onNodeWithTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG).performClick()
            waitForDialogToClose()
            waitForWallpaperPixels(CONVERSATION_WALLPAPER_COLOR_ARGB, UPDATED_DIM)
            val conversationLoadsBeforeRecreation = fixture.wallpaperStore.loadCount(
                CONVERSATION_WALLPAPER_MEDIA_ID,
                preview = false,
            )
            val anchorLoadsBeforeRecreation = fixture.index.anchorLoadCount.get()
            compose.onNodeWithTag(COMPOSER_TEST_TAG).performClick().assertIsFocused()
            moveFocusOffComposer(scenario)
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
            waitForDisplayedThreadRow("Synthetic anchor row 20")
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
            compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).assertDoesNotExist()
        }
    }

    private fun requireExplicitAttachmentColdRestartPhase(): String {
        val arguments = InstrumentationRegistry.getArguments()
        val enabled = arguments.getString(ATTACHMENT_COLD_RESTART_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(ATTACHMENT_COLD_RESTART_GATE_REQUIRED, enabled)
        assumeTrue(
            ATTACHMENT_COLD_RESTART_EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
        assumeTrue(
            ATTACHMENT_COLD_RESTART_API_REQUIRED,
            Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA,
        )
        return arguments.getString(ATTACHMENT_COLD_RESTART_PHASE_ARGUMENT).orEmpty().also { phase ->
            restartRequire(
                phase == ATTACHMENT_COLD_RESTART_PHASE_PREPARE ||
                    phase == ATTACHMENT_COLD_RESTART_PHASE_VERIFY ||
                    phase == ATTACHMENT_COLD_RESTART_PHASE_CLEANUP,
                ATTACHMENT_COLD_RESTART_PHASE_INVALID,
            )
        }
    }

    private fun prepareAttachmentColdRestart() = runBlocking {
        val environment = attachmentColdRestartEnvironment()
        val preferences = environment.context.getSharedPreferences(
            ATTACHMENT_COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        restartRequire(
            preferences.all.isEmpty(),
            ATTACHMENT_COLD_RESTART_STALE_CHECKPOINT,
        )
        restartRequire(
            environment.drafts.read(ATTACHMENT_COLD_RESTART_IDENTITY) ==
                DraftRepositoryResult.NotFound,
            ATTACHMENT_COLD_RESTART_DRAFT_NOT_EMPTY,
        )
        val preparedPid = Process.myPid()
        val preparedStartUptimeMillis = Process.getStartUptimeMillis()
        restartRequire(
            preferences.edit()
                .putInt(ATTACHMENT_COLD_RESTART_KEY_VERSION, ATTACHMENT_COLD_RESTART_VERSION)
                .putString(
                    ATTACHMENT_COLD_RESTART_KEY_STATE,
                    ATTACHMENT_COLD_RESTART_STATE_PREPARING,
                )
                .putInt(ATTACHMENT_COLD_RESTART_KEY_PREPARED_PID, preparedPid)
                .putLong(
                    ATTACHMENT_COLD_RESTART_KEY_PREPARED_START_UPTIME,
                    preparedStartUptimeMillis,
                )
                .commit(),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )

        val draft = environment.drafts.create(
            NewDraft(
                identity = ATTACHMENT_COLD_RESTART_IDENTITY,
                body = ATTACHMENT_COLD_RESTART_BODY,
                subject = ATTACHMENT_COLD_RESTART_SUBJECT,
                createdTimestampMillis = ATTACHMENT_COLD_RESTART_TIMESTAMP_MILLIS,
                updatedTimestampMillis = ATTACHMENT_COLD_RESTART_TIMESTAMP_MILLIS,
            ),
        ).successValue()
        val attachment = attachmentColdRestartFixture()
        restartRequire(
            environment.attachments.replace(
                draft.id,
                draft.revision,
                listOf(attachment),
            ) == DraftRepositoryResult.Success(listOf(attachment)),
            ATTACHMENT_COLD_RESTART_ATTACHMENT_WRITE_FAILED,
        )
        restartRequire(
            environment.drafts.read(draft.id) == DraftRepositoryResult.Success(draft),
            ATTACHMENT_COLD_RESTART_DRAFT_MISMATCH,
        )
        restartRequire(
            environment.attachments.read(draft.id) ==
                DraftRepositoryResult.Success(listOf(attachment)),
            ATTACHMENT_COLD_RESTART_ATTACHMENT_MISMATCH,
        )
        restartRequire(
            writeAttachmentColdRestartEvidence(
                preferences,
                AttachmentColdRestartEvidence(
                    draftId = draft.id,
                    initialRevision = draft.revision,
                    preparedPid = preparedPid,
                    preparedStartUptimeMillis = preparedStartUptimeMillis,
                    verifiedPid = null,
                    verifiedStartUptimeMillis = null,
                ),
            ),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
    }

    private fun verifyAttachmentColdRestart() {
        val environment = attachmentColdRestartEnvironment()
        val preferences = environment.context.getSharedPreferences(
            ATTACHMENT_COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val evidence = readAttachmentColdRestartEvidence(preferences)
        val currentPid = Process.myPid()
        val currentStartUptimeMillis = Process.getStartUptimeMillis()
        restartRequire(
            currentPid != evidence.preparedPid &&
                currentStartUptimeMillis > evidence.preparedStartUptimeMillis,
            ATTACHMENT_COLD_RESTART_PROCESS_NOT_RESTARTED,
        )
        val attachment = attachmentColdRestartFixture()
        val storedDraft = runBlocking {
            environment.drafts.read(evidence.draftId).successValue()
        }
        restartRequire(
            storedDraft.identity == ATTACHMENT_COLD_RESTART_IDENTITY &&
                storedDraft.body == ATTACHMENT_COLD_RESTART_BODY &&
                storedDraft.subject == ATTACHMENT_COLD_RESTART_SUBJECT &&
                storedDraft.revision == evidence.initialRevision,
            ATTACHMENT_COLD_RESTART_DRAFT_MISMATCH,
        )
        restartRequire(
            runBlocking { environment.attachments.read(evidence.draftId) } ==
                DraftRepositoryResult.Success(listOf(attachment)),
            ATTACHMENT_COLD_RESTART_ATTACHMENT_MISMATCH,
        )

        val sendController = RecordingUnknownThreadSmsSendController()
        val fixture = SyntheticFixture(
            threadSummary = syntheticThreadSummary(
                participants = SYNTHETIC_SEND_PARTICIPANTS,
                latestSubscriptionId = SYNTHETIC_SEND_SUBSCRIPTION.id,
            ),
            verifiedIdentity = SYNTHETIC_SEND_VERIFIED_IDENTITY,
            subscriptionRepository = FixedSubscriptionRepository(SYNTHETIC_SEND_SUBSCRIPTION),
            threadSmsSendController = sendController,
            draftRepositoryOverride = environment.drafts,
            draftAttachmentRepositoryOverride = environment.attachments,
        )
        AuroraSmsRootTestHarnessRegistry.install(fixture.harness)

        ActivityScenario.launch(AuroraSmsRootTestActivity::class.java).use {
            openSyntheticThread()
            compose.onNodeWithTag(COMPOSER_TEST_TAG)
                .assertTextContains(ATTACHMENT_COLD_RESTART_BODY)
            waitForTag("$COMPOSER_ATTACHMENT_TEST_TAG_PREFIX-0")
            waitForDisplayedText("Draft saved locally · MMS")
            compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsEnabled().performClick()

            compose.waitUntil(TIMEOUT_MILLIS) { sendController.sendCount == 1 }
            val command = sendController.commandsSnapshot().single()
            restartRequire(
                command.draftId == evidence.draftId &&
                    command.identity == SYNTHETIC_SEND_VERIFIED_IDENTITY &&
                    command.subscriptionId == SYNTHETIC_SEND_SUBSCRIPTION.id &&
                    command.transport == MessageTransportKind.MMS &&
                    command.attachments == listOf(
                        validOutgoingAttachment(
                            OutgoingMmsAttachment.IMAGE_PNG,
                            attachment.copyBytes(),
                        ),
                    ),
                ATTACHMENT_COLD_RESTART_ROUTE_MISMATCH,
            )
            sendController.releaseAsSubmissionUnknown()
            waitForDisplayedText("Send status unknown. Check the conversation before trying again.")
            restartRequire(sendController.sendCount == 1, ATTACHMENT_COLD_RESTART_ROUTE_MISMATCH)
        }

        restartRequire(
            runBlocking { environment.attachments.read(evidence.draftId) } ==
                DraftRepositoryResult.Success(listOf(attachment)),
            ATTACHMENT_COLD_RESTART_ATTACHMENT_MISMATCH,
        )
        restartRequire(
            writeAttachmentColdRestartEvidence(
                preferences,
                evidence.copy(
                    verifiedPid = currentPid,
                    verifiedStartUptimeMillis = currentStartUptimeMillis,
                ),
            ),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_WRITE_FAILED,
        )
    }

    private fun cleanupAttachmentColdRestart() = runBlocking {
        val environment = attachmentColdRestartEnvironment()
        val preferences = environment.context.getSharedPreferences(
            ATTACHMENT_COLD_RESTART_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val state = preferences.getString(ATTACHMENT_COLD_RESTART_KEY_STATE, null)
        if (state == null) {
            restartRequire(
                preferences.all.isEmpty(),
                ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID,
            )
            return@runBlocking
        }
        restartRequire(
            preferences.getInt(ATTACHMENT_COLD_RESTART_KEY_VERSION, 0) ==
                ATTACHMENT_COLD_RESTART_VERSION,
            ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID,
        )
        if (state == ATTACHMENT_COLD_RESTART_STATE_VERIFIED) {
            val evidence = readAttachmentColdRestartEvidence(preferences)
            restartRequire(
                evidence.verifiedPid != null &&
                    Process.myPid() != evidence.verifiedPid &&
                    Process.getStartUptimeMillis() >
                    checkNotNull(evidence.verifiedStartUptimeMillis),
                ATTACHMENT_COLD_RESTART_CLEANUP_PROCESS_NOT_RESTARTED,
            )
        } else {
            restartRequire(
                state == ATTACHMENT_COLD_RESTART_STATE_PREPARING ||
                    state == ATTACHMENT_COLD_RESTART_STATE_PREPARED,
                ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID,
            )
        }

        val expectedDraftId = preferences.getLong(ATTACHMENT_COLD_RESTART_KEY_DRAFT_ID, -1L)
            .takeIf { it > 0L }
            ?.let(::DraftId)
        when (val draftResult = environment.drafts.read(ATTACHMENT_COLD_RESTART_IDENTITY)) {
            is DraftRepositoryResult.Success -> {
                restartRequire(
                    expectedDraftId == null || draftResult.value.id == expectedDraftId,
                    ATTACHMENT_COLD_RESTART_DRAFT_MISMATCH,
                )
                environment.drafts.delete(draftResult.value.id).successValue()
                restartRequire(
                    environment.attachments.read(draftResult.value.id) ==
                        DraftRepositoryResult.NotFound,
                    ATTACHMENT_COLD_RESTART_ATTACHMENT_NOT_REMOVED,
                )
            }
            DraftRepositoryResult.NotFound -> Unit
            else -> throw AssertionError(ATTACHMENT_COLD_RESTART_CLEANUP_FAILED)
        }
        restartRequire(
            environment.drafts.read(ATTACHMENT_COLD_RESTART_IDENTITY) ==
                DraftRepositoryResult.NotFound,
            ATTACHMENT_COLD_RESTART_DRAFT_NOT_REMOVED,
        )
        restartRequire(
            preferences.edit().clear().commit(),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_CLEAR_FAILED,
        )
    }

    private fun attachmentColdRestartEnvironment(): AttachmentColdRestartEnvironment {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as? AuroraSmsApplication
            ?: throw AssertionError(ATTACHMENT_COLD_RESTART_APPLICATION_MISSING)
        val status = runBlocking {
            withTimeoutOrNull(ATTACHMENT_COLD_RESTART_TIMEOUT_MILLIS) {
                application.container.stateStorageStatus.first { current ->
                    current != StateStorageStatus.Opening
                }
            }
        }
        restartRequire(
            status == StateStorageStatus.Ready,
            ATTACHMENT_COLD_RESTART_STATE_NOT_READY,
        )
        return AttachmentColdRestartEnvironment(
            context = context,
            drafts = application.container.draftRepository,
            attachments = application.container.draftAttachmentRepository,
        )
    }

    private fun readAttachmentColdRestartEvidence(
        preferences: android.content.SharedPreferences,
    ): AttachmentColdRestartEvidence {
        val state = preferences.getString(ATTACHMENT_COLD_RESTART_KEY_STATE, null)
        restartRequire(
            preferences.getInt(ATTACHMENT_COLD_RESTART_KEY_VERSION, 0) ==
                ATTACHMENT_COLD_RESTART_VERSION &&
                (state == ATTACHMENT_COLD_RESTART_STATE_PREPARED ||
                    state == ATTACHMENT_COLD_RESTART_STATE_VERIFIED),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID,
        )
        val evidence = AttachmentColdRestartEvidence(
            draftId = DraftId(
                preferences.getLong(ATTACHMENT_COLD_RESTART_KEY_DRAFT_ID, -1L),
            ),
            initialRevision = DraftRevision(
                preferences.getLong(ATTACHMENT_COLD_RESTART_KEY_INITIAL_REVISION, -1L),
            ),
            preparedPid = preferences.getInt(ATTACHMENT_COLD_RESTART_KEY_PREPARED_PID, -1),
            preparedStartUptimeMillis = preferences.getLong(
                ATTACHMENT_COLD_RESTART_KEY_PREPARED_START_UPTIME,
                -1L,
            ),
            verifiedPid = preferences.getInt(ATTACHMENT_COLD_RESTART_KEY_VERIFIED_PID, -1)
                .takeIf { it > 0 },
            verifiedStartUptimeMillis = preferences.getLong(
                ATTACHMENT_COLD_RESTART_KEY_VERIFIED_START_UPTIME,
                -1L,
            ).takeIf { it >= 0L },
        )
        restartRequire(
            evidence.preparedPid > 0 &&
                evidence.preparedStartUptimeMillis >= 0L &&
                (state == ATTACHMENT_COLD_RESTART_STATE_VERIFIED) ==
                (evidence.verifiedPid != null && evidence.verifiedStartUptimeMillis != null),
            ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID,
        )
        return evidence
    }

    private fun writeAttachmentColdRestartEvidence(
        preferences: android.content.SharedPreferences,
        evidence: AttachmentColdRestartEvidence,
    ): Boolean = preferences.edit()
        .clear()
        .putInt(ATTACHMENT_COLD_RESTART_KEY_VERSION, ATTACHMENT_COLD_RESTART_VERSION)
        .putString(
            ATTACHMENT_COLD_RESTART_KEY_STATE,
            if (evidence.verifiedPid == null) {
                ATTACHMENT_COLD_RESTART_STATE_PREPARED
            } else {
                ATTACHMENT_COLD_RESTART_STATE_VERIFIED
            },
        )
        .putLong(ATTACHMENT_COLD_RESTART_KEY_DRAFT_ID, evidence.draftId.value)
        .putLong(
            ATTACHMENT_COLD_RESTART_KEY_INITIAL_REVISION,
            evidence.initialRevision.updatedTimestampMillis,
        )
        .putInt(ATTACHMENT_COLD_RESTART_KEY_PREPARED_PID, evidence.preparedPid)
        .putLong(
            ATTACHMENT_COLD_RESTART_KEY_PREPARED_START_UPTIME,
            evidence.preparedStartUptimeMillis,
        )
        .putInt(ATTACHMENT_COLD_RESTART_KEY_VERIFIED_PID, evidence.verifiedPid ?: -1)
        .putLong(
            ATTACHMENT_COLD_RESTART_KEY_VERIFIED_START_UPTIME,
            evidence.verifiedStartUptimeMillis ?: -1L,
        )
        .commit()

    private fun attachmentColdRestartFixture(): DraftAttachment = validDraftAttachment(
        DraftAttachment.IMAGE_PNG,
        ATTACHMENT_COLD_RESTART_PNG_BYTES,
    )

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
        onView(isRoot()).perform(closeSoftKeyboard())
        waitForTag(THREAD_SCREEN_TEST_TAG)
        waitForTag(THREAD_LIST_TEST_TAG)
        waitForTag(THREAD_MORE_ACTION_TEST_TAG)
        waitForDisplayedText(SYNTHETIC_EXACT_ANCHOR)
    }

    private fun openNewChat() {
        waitForTag(INBOX_SCREEN_TEST_TAG)
        compose.onNodeWithTag(
            org.aurorasms.feature.conversations.INBOX_NEW_CHAT_ACTION_TEST_TAG,
        ).performClick()
        waitForTag(org.aurorasms.feature.conversations.NEW_MESSAGE_SCREEN_TEST_TAG)
    }

    private fun clearDraft(
        repository: DraftRepository,
        identity: DraftIdentity,
    ) {
        runBlocking {
            when (val current = repository.read(identity)) {
                is DraftRepositoryResult.Success -> check(
                    repository.delete(current.value.id) is DraftRepositoryResult.Success,
                ) { "Synthetic draft cleanup could not delete its identity" }
                DraftRepositoryResult.NotFound -> Unit
                else -> throw AssertionError("Synthetic draft cleanup could not read its identity")
            }
        }
    }

    private fun readDraft(
        repository: DraftRepository,
        identity: DraftIdentity,
    ): Draft? = runBlocking {
        when (val current = repository.read(identity)) {
            is DraftRepositoryResult.Success -> current.value
            DraftRepositoryResult.NotFound -> null
            else -> throw AssertionError("Synthetic draft read failed")
        }
    }

    private fun waitForDraftBody(
        repository: DraftRepository,
        identity: DraftIdentity,
        body: String,
    ) {
        compose.waitUntil(TIMEOUT_MILLIS) {
            readDraft(repository, identity)?.body == body
        }
    }

    private fun scrollThreadRowIntoView(text: String, index: Int) {
        compose.onNodeWithTag(THREAD_LIST_TEST_TAG).performScrollToIndex(index)
        waitForDisplayedThreadRow(text)
    }

    private fun moveFocusOffComposer(scenario: ActivityScenario<AuroraSmsRootTestActivity>) {
        scenario.onActivity(AuroraSmsRootTestActivity::moveFocusToTestSink)
        onView(isRoot()).perform(closeSoftKeyboard())
        compose.waitUntil(TIMEOUT_MILLIS) {
            runCatching {
                compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotFocused()
            }.isSuccess
        }
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

    private fun waitForDisplayedText(text: String) {
        compose.waitUntil(TIMEOUT_MILLIS) {
            runCatching {
                compose.onNodeWithText(text).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForDisplayedThreadRow(text: String) {
        val rowMatcher = threadRowMatcher(text)
        compose.waitUntil(TIMEOUT_MILLIS) {
            runCatching {
                compose.onNode(rowMatcher, useUnmergedTree = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun threadRowMatcher(text: String) =
        hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and hasAnyDescendant(hasText(text))

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

    private fun assertFirstContactOwnershipIsAbsentFromNewMessageRuntimeGraph() {
        val controller = FirstContactOwnershipController::class.java
        val runtimeSurfaces = listOf(
            AuroraSmsRootServices::class.java,
            ComposeMessageActivity::class.java,
            Class.forName("org.aurorasms.app.compose.NewMessageRouteKt"),
        )

        runtimeSurfaces.forEach { surface ->
            assertTrue(
                "${surface.name} unexpectedly owns first-contact controller",
                surface.declaredFields.none { field ->
                    controller.isAssignableFrom(field.type)
                },
            )
            assertTrue(
                "${surface.name} unexpectedly exposes first-contact controller",
                surface.declaredMethods.none { method ->
                    controller.isAssignableFrom(method.returnType) ||
                        method.parameterTypes.any { type -> controller.isAssignableFrom(type) }
                },
            )
        }
    }
}

private data class ColdRestartEnvironment(
    val context: Context,
    val controller: WallpaperController,
) {
    override fun toString(): String = "ColdRestartEnvironment(REDACTED)"
}

private data class AttachmentColdRestartEnvironment(
    val context: Context,
    val drafts: DraftRepository,
    val attachments: DraftAttachmentRepository,
) {
    override fun toString(): String = "AttachmentColdRestartEnvironment(REDACTED)"
}

private data class AttachmentColdRestartEvidence(
    val draftId: DraftId,
    val initialRevision: DraftRevision,
    val preparedPid: Int,
    val preparedStartUptimeMillis: Long,
    val verifiedPid: Int?,
    val verifiedStartUptimeMillis: Long?,
) {
    override fun toString(): String = "AttachmentColdRestartEvidence(REDACTED)"
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
    threadSummary: ConversationSummary = syntheticThreadSummary(),
    verifiedIdentity: VerifiedConversationIdentity = SYNTHETIC_VERIFIED_IDENTITY,
    contactDiscovery: ContactDiscovery = UnavailableContactDiscovery,
    contactsPermissionGranted: Boolean = true,
    subscriptionRepository: SubscriptionRepository = SyntheticSubscriptions,
    threadSmsSendController: ThreadSmsSendController = SyntheticIdleThreadSmsSendController,
    firstContactOwnershipController: FirstContactOwnershipController =
        UnavailableFirstContactOwnershipController,
    segmentCounter: (String) -> Int? = { body -> body.takeIf(String::isNotBlank)?.let { 1 } },
    draftRepositoryOverride: DraftRepository? = null,
    draftAttachmentRepositoryOverride: DraftAttachmentRepository? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val conversations = SyntheticConversationRepository(threadSummary, verifiedIdentity)
    val index = SyntheticMessageIndex()
    val timeline = RejectingTimelineRepository()
    val appearance = InMemoryAppearanceRepository()
    val wallpapers = InMemoryWallpaperRepository()
    val wallpaperStore = SyntheticWallpaperMediaStore()
    val drafts = InMemoryDraftRepository()
    val draftAttachments = InMemoryDraftAttachmentRepository(drafts)
    private val rootDrafts = draftRepositoryOverride ?: drafts
    private val rootDraftAttachments = draftAttachmentRepositoryOverride ?: draftAttachments
    private val wallpaperController = wallpaperControllerOverride
        ?: WallpaperController(wallpapers, wallpaperStore)
    private val services = SyntheticRootServices(
        scope = scope,
        conversations = conversations,
        index = index,
        timeline = timeline,
        contactDiscovery = contactDiscovery,
        wallpaperController = wallpaperController,
        drafts = rootDrafts,
        draftAttachmentRepository = rootDraftAttachments,
        subscriptionRepository = subscriptionRepository,
        threadSmsSendController = threadSmsSendController,
        firstContactOwnershipController = firstContactOwnershipController,
        segmentCounter = segmentCounter,
    )
    private val controller = AppearanceController(appearance, scope) { 20_000L }
    val harness = AuroraSmsRootTestHarness(
        services = services,
        appearanceController = controller,
        contactsPermissionGranted = contactsPermissionGranted,
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
    override val contactDiscovery: ContactDiscovery,
    override val wallpaperController: WallpaperController,
    private val drafts: DraftRepository,
    override val draftAttachmentRepository: DraftAttachmentRepository,
    override val subscriptionRepository: SubscriptionRepository,
    override val threadSmsSendController: ThreadSmsSendController,
    override val firstContactOwnershipController: FirstContactOwnershipController,
    private val segmentCounter: (String) -> Int?,
) : AuroraSmsRootServices, AutoCloseable {
    private val clock = AtomicLong(30_000L)
    private val writerPool = SerializedDraftWriterPool(scope) { identity, restorationToken ->
        SerializedDraftWriter(
            repository = drafts,
            identity = identity,
            scope = scope,
            restorationToken = restorationToken,
            nowMillis = clock::incrementAndGet,
        )
    }

    override val conversationRepository: ConversationRepository = conversations
    override val threadTimelineRepository: ThreadTimelineRepository = timeline
    override val messageIndex: MessageIndex = index
    override val contactCache: ContactCache = SyntheticContactCache
    override val mmsAttachmentRepository: MmsAttachmentRepository = RejectingAttachments
    override val previewLoader: BoundedPreviewLoader = RejectingPreviewLoader
    override val conversationSubscriptionPreferenceRepository:
        ConversationSubscriptionPreferenceRepository =
        InMemoryConversationSubscriptionPreferenceRepository()

    override fun countSmsSegments(body: String): Int? = segmentCounter(body)

    override fun acquireDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
        participantRouteOwner: String?,
    ): SerializedDraftWriterLease = writerPool.acquire(
        identity,
        restorationToken,
        participantRouteOwner,
    )

    override fun close() {
        writerPool.close()
    }
}

private data class SyntheticContactDiscoveryRequest(
    val query: String,
    val resultLimit: Int,
)

private class RecordingContactDiscovery(
    private val response: suspend (query: String, resultLimit: Int) -> ContactDiscoveryResult,
) : ContactDiscovery {
    private val requests = Collections.synchronizedList(
        mutableListOf<SyntheticContactDiscoveryRequest>(),
    )

    val requestCount: Int
        get() = requests.size

    fun requestsSnapshot(): List<SyntheticContactDiscoveryRequest> = synchronized(requests) {
        requests.toList()
    }

    override suspend fun discover(
        query: String,
        resultLimit: Int,
    ): ContactDiscoveryResult {
        requests += SyntheticContactDiscoveryRequest(query, resultLimit)
        return response(query, resultLimit)
    }
}

private class InMemoryConversationSubscriptionPreferenceRepository :
    ConversationSubscriptionPreferenceRepository {
    private val lock = Any()
    private val stored = LinkedHashMap<Any, ConversationSubscriptionPreference>()

    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        synchronized(lock) {
            stored[scope.participantSetKey]
                ?.let { ConversationSubscriptionRepositoryResult.Success(it) }
                ?: ConversationSubscriptionRepositoryResult.NotFound
        }

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        synchronized(lock) {
            val existing = stored[scope.participantSetKey]
            if (
                (existing == null && expectedRevision != null) ||
                (existing != null && existing.revision != expectedRevision) ||
                updatedTimestampMillis <= (existing?.updatedTimestampMillis ?: -1L)
            ) {
                return@synchronized ConversationSubscriptionRepositoryResult.StaleWrite
            }
            val preference = ConversationSubscriptionPreference(
                scope = scope,
                subscriptionId = subscriptionId,
                revision = ConversationSubscriptionRevision((existing?.revision?.value ?: 0L) + 1L),
                updatedTimestampMillis = updatedTimestampMillis,
            )
            stored[scope.participantSetKey] = preference
            ConversationSubscriptionRepositoryResult.Success(preference)
        }
}

private object SyntheticIdleThreadSmsSendController : ThreadSmsSendController {
    override fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation> =
        flowOf(ThreadSmsSendObservation(ThreadSmsSendPhase.IDLE))

    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt =
        ThreadSmsSendAttempt.REFUSED

    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean = false

    override suspend fun recover(): ThreadSmsRecoveryResult = ThreadSmsRecoveryResult.READY

    override fun fence() = Unit

    override suspend fun handleTransportResult(result: TransportResult): Boolean = false
}

private class RecordingFirstContactOwnershipController : FirstContactOwnershipController {
    private val invocations = AtomicInteger()

    val invocationCount: Int
        get() = invocations.get()

    override suspend fun reserveAndBind(
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult {
        invocations.incrementAndGet()
        return FirstContactOwnershipResult.Failure(
            FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
        )
    }
}

private class RecordingUnknownThreadSmsSendController : ThreadSmsSendController {
    private val observation = MutableStateFlow(ThreadSmsSendObservation(ThreadSmsSendPhase.IDLE))
    private val commands = Collections.synchronizedList(mutableListOf<ThreadSmsSendCommand>())
    private val releaseSend = CompletableDeferred<Unit>()

    @Volatile
    var acknowledgementCount: Int = 0
        private set

    val sendCount: Int
        get() = commands.size

    fun commandsSnapshot(): List<ThreadSmsSendCommand> = synchronized(commands) { commands.toList() }

    fun releaseAsSubmissionUnknown() {
        check(releaseSend.complete(Unit)) { "Synthetic send was already released" }
    }

    override fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation> {
        check(providerThreadId == SYNTHETIC_THREAD_ID)
        return observation
    }

    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt {
        commands += command
        observation.value = ThreadSmsSendObservation(ThreadSmsSendPhase.SENDING)
        releaseSend.await()
        observation.value = ThreadSmsSendObservation(ThreadSmsSendPhase.SUBMISSION_UNKNOWN)
        return ThreadSmsSendAttempt.STARTED
    }

    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean {
        if (
            providerThreadId != SYNTHETIC_THREAD_ID ||
            observation.value.phase != ThreadSmsSendPhase.SUBMISSION_UNKNOWN
        ) {
            return false
        }
        acknowledgementCount += 1
        observation.value = ThreadSmsSendObservation(
            phase = ThreadSmsSendPhase.IDLE,
            unknownAcknowledgementEpoch = acknowledgementCount.toLong(),
        )
        return true
    }

    override suspend fun recover(): ThreadSmsRecoveryResult = ThreadSmsRecoveryResult.READY

    override fun fence() {
        observation.value = ThreadSmsSendObservation(ThreadSmsSendPhase.RECOVERY_PENDING)
    }

    override suspend fun handleTransportResult(result: TransportResult): Boolean = false
}

private class FixedSubscriptionRepository(
    private val subscription: ActiveSubscription,
) : SubscriptionRepository {
    override suspend fun activeSubscriptions(): SubscriptionSnapshot =
        SubscriptionSnapshot.Available(listOf(subscription))
}

private class SyntheticConversationRepository(
    private val thread: ConversationSummary = syntheticThreadSummary(),
    private val verifiedIdentity: VerifiedConversationIdentity = SYNTHETIC_VERIFIED_IDENTITY,
) : ConversationRepository {
    val inboxLoadCount = AtomicInteger()
    private val inbox = (1..30).map(::syntheticInboxSummary)
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
            verifiedIdentity = verifiedIdentity.takeIf { verifiedIdentityAvailable },
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

    fun snapshot(identity: DraftIdentity): Draft? = synchronized(lock) { drafts[identity] }

    fun snapshotCount(): Int = synchronized(lock) { drafts.size }

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

private class GatedCreateDraftRepository(
    private val failFirstCreate: Boolean = false,
) : DraftRepository {
    private val delegate = InMemoryDraftRepository()
    private val createAttempts = AtomicInteger()
    val createStarted = CompletableDeferred<Unit>()
    val allowCreate = CompletableDeferred<Unit>()
    val updateStarted = CompletableDeferred<Unit>()
    val allowUpdate = CompletableDeferred<Unit>()

    fun snapshot(identity: DraftIdentity): Draft? = delegate.snapshot(identity)

    suspend fun createConcurrent(draft: NewDraft): DraftRepositoryResult<Draft> =
        delegate.create(draft)

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> {
        createStarted.complete(Unit)
        allowCreate.await()
        if (failFirstCreate && createAttempts.incrementAndGet() == 1) {
            return DraftRepositoryResult.StorageFailure(DraftStorageOperation.CREATE)
        }
        return delegate.create(draft)
    }

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> = delegate.read(id)

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> =
        delegate.read(identity)

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> {
        updateStarted.complete(Unit)
        allowUpdate.await()
        return delegate.update(draft, expectedRevision)
    }

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> = delegate.delete(id)
}

private class InMemoryDraftAttachmentRepository(
    private val drafts: InMemoryDraftRepository,
) : DraftAttachmentRepository {
    private val lock = Any()
    private val stored = HashMap<DraftId, List<DraftAttachment>>()

    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> = when (drafts.read(draftId)) {
        is DraftRepositoryResult.Success -> DraftRepositoryResult.Success(
            synchronized(lock) { stored[draftId].orEmpty().toList() },
        )
        else -> {
            synchronized(lock) { stored.remove(draftId) }
            DraftRepositoryResult.NotFound
        }
    }

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> {
        if (!DraftAttachment.isValidSet(attachments)) return DraftRepositoryResult.CorruptData
        val draft = when (val result = drafts.read(draftId)) {
            is DraftRepositoryResult.Success -> result.value
            else -> return DraftRepositoryResult.NotFound
        }
        if (draft.revision != expectedRevision) return DraftRepositoryResult.StaleWrite
        val frozen = attachments.toList()
        synchronized(lock) { stored[draftId] = frozen }
        return DraftRepositoryResult.Success(frozen)
    }
}

private object UnavailableDraftAttachmentRepository : DraftAttachmentRepository {
    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> =
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.READ)

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> =
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)
}

private fun validDraftAttachment(contentType: String, bytes: ByteArray): DraftAttachment =
    (DraftAttachment.create(contentType, bytes) as DraftAttachment.CreationResult.Valid).attachment

private fun validOutgoingAttachment(contentType: String, bytes: ByteArray): OutgoingMmsAttachment =
    (OutgoingMmsAttachment.create(contentType, bytes) as
        OutgoingMmsAttachment.CreationResult.Valid).attachment

private fun <T> DraftRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is DraftRepositoryResult.Success<T>)
    return (this as DraftRepositoryResult.Success<T>).value
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

private fun syntheticThreadSummary(
    participants: List<ParticipantAddress> = SYNTHETIC_VERIFIED_PARTICIPANTS,
    latestSubscriptionId: AuroraSubscriptionId? = null,
): ConversationSummary = ConversationSummary(
    providerThreadId = SYNTHETIC_THREAD_ID,
    latestLocalRowId = EXACT_LOCAL_ROW,
    latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, EXACT_LOCAL_ROW),
    latestTimestampMillis = 40_000L,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.NONE,
    latestSubscriptionId = latestSubscriptionId,
    latestSenderAddress = participants.first(),
    latestSnippet = SYNTHETIC_EXACT_ANCHOR,
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = ANCHOR_WINDOW_SIZE.toLong(),
    indexedUnreadCount = 0L,
    participants = participants.take(8),
    indexedParticipantCount = participants.size,
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
private val SYNTHETIC_SEND_PARTICIPANTS = listOf(SYNTHETIC_PARTICIPANT)
private val SYNTHETIC_SEND_SUBSCRIPTION = ActiveSubscription(
    id = AuroraSubscriptionId(7),
    slotIndex = 0,
    displayLabel = "Synthetic active SIM",
    smsCapable = true,
)
private val SYNTHETIC_SEND_VERIFIED_IDENTITY = VerifiedConversationIdentity(
    providerThreadId = SYNTHETIC_THREAD_ID,
    generationId = checkNotNull(COMPLETE_COVERAGE.generationId),
    participants = SYNTHETIC_SEND_PARTICIPANTS,
)
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
private const val SYNTHETIC_SEND_DRAFT = "Synthetic one part send draft"
private const val SYNTHETIC_EXTERNAL_COMPOSE_BODY = "Synthetic external review body"
private const val SYNTHETIC_EDITED_EXTERNAL_COMPOSE_BODY = "Synthetic user-edited external review body"
private const val SYNTHETIC_FIRST_REUSED_EXTERNAL_BODY = "Synthetic first reused request"
private const val SYNTHETIC_SECOND_REUSED_EXTERNAL_BODY = "Synthetic second reused request"
private const val SYNTHETIC_UNCOMMITTED_REUSED_RECIPIENT = "+15550100999"
private const val SYNTHETIC_NEW_CHAT_DRAFT_BODY = "Synthetic durable New chat draft"
private const val SYNTHETIC_IN_FLIGHT_NEW_CHAT_DRAFT_BODY = "Synthetic in-flight New chat draft"
private const val SYNTHETIC_INTERMEDIATE_NEW_CHAT_DRAFT_BODY = "Synthetic intermediate New chat draft"
private const val SYNTHETIC_NEWEST_NEW_CHAT_DRAFT_BODY = "Synthetic newest New chat draft"
private const val SYNTHETIC_CLEAR_GATED_NEW_CHAT_DRAFT_BODY = "Synthetic clear-gated New chat draft"
private const val SYNTHETIC_CONFLICTED_LOCAL_NEW_CHAT_BODY = "Synthetic conflicted local New chat draft"
private const val SYNTHETIC_RECOVERED_NEW_CHAT_DRAFT_BODY = "Synthetic recovered New chat draft"
private const val SYNTHETIC_MULTI_RECIPIENT_DRAFT_BODY = "Synthetic group draft"
private const val SYNTHETIC_CONTACT_QUERY = "Synthetic contact lookup"
private const val SYNTHETIC_REVOKED_CONTACT_QUERY = "Synthetic revoked contact lookup"
private const val SYNTHETIC_REPLACED_CONTACT_QUERY = "Synthetic replaced contact lookup"
private const val SYNTHETIC_REPLACEMENT_CONTACT_QUERY = "Synthetic replacement contact lookup"
private const val SYNTHETIC_PANEL_CLOSE_CONTACT_QUERY = "Synthetic panel-close contact lookup"
private const val SYNTHETIC_CONTACT_DRAFT_BODY = "Synthetic discovered-contact draft"
private const val SYNTHETIC_MANUAL_CONTACT_DRAFT_BODY = "Synthetic manual-recipient draft"
private const val SYNTHETIC_EXISTING_NEW_CHAT_DRAFT = "Synthetic existing participant draft"
private const val SYNTHETIC_EXISTING_NEW_CHAT_SUBJECT = "Synthetic preserved subject"
private const val SYNTHETIC_CONFLICTING_EXTERNAL_BODY = "Synthetic conflicting external prefill"
private const val SYNTHETIC_EXACT_ANCHOR = "Synthetic exact anchor"
private const val UPDATED_FOCAL_X = 270
private const val UPDATED_FOCAL_Y = 830
private const val UPDATED_DIM = 720
private const val WALLPAPER_PIXEL_TOLERANCE = 0.035f
private const val WALLPAPER_PIXEL_SAMPLE_STRIDE = 8
private const val MINIMUM_WALLPAPER_PIXEL_SAMPLES = 24
private const val ATTACHMENT_COLD_RESTART_GATE_ARGUMENT =
    "auroraEmulatorDraftAttachmentColdRestart"
private const val ATTACHMENT_COLD_RESTART_PHASE_ARGUMENT =
    "auroraEmulatorDraftAttachmentColdRestartPhase"
private const val ATTACHMENT_COLD_RESTART_PHASE_PREPARE = "prepare"
private const val ATTACHMENT_COLD_RESTART_PHASE_VERIFY = "verify"
private const val ATTACHMENT_COLD_RESTART_PHASE_CLEANUP = "cleanup"
private const val ATTACHMENT_COLD_RESTART_PREFERENCES =
    "aurora_draft_attachment_cold_restart_evidence"
private const val ATTACHMENT_COLD_RESTART_VERSION = 1
private const val ATTACHMENT_COLD_RESTART_KEY_VERSION = "version"
private const val ATTACHMENT_COLD_RESTART_KEY_STATE = "state"
private const val ATTACHMENT_COLD_RESTART_STATE_PREPARING = "preparing"
private const val ATTACHMENT_COLD_RESTART_STATE_PREPARED = "prepared"
private const val ATTACHMENT_COLD_RESTART_STATE_VERIFIED = "verified"
private const val ATTACHMENT_COLD_RESTART_KEY_DRAFT_ID = "draft_id"
private const val ATTACHMENT_COLD_RESTART_KEY_INITIAL_REVISION = "initial_revision"
private const val ATTACHMENT_COLD_RESTART_KEY_PREPARED_PID = "prepared_pid"
private const val ATTACHMENT_COLD_RESTART_KEY_PREPARED_START_UPTIME =
    "prepared_start_uptime"
private const val ATTACHMENT_COLD_RESTART_KEY_VERIFIED_PID = "verified_pid"
private const val ATTACHMENT_COLD_RESTART_KEY_VERIFIED_START_UPTIME =
    "verified_start_uptime"
private const val ATTACHMENT_COLD_RESTART_BODY = "Synthetic cold-process image caption"
private const val ATTACHMENT_COLD_RESTART_SUBJECT = "Synthetic cold-process subject"
private const val ATTACHMENT_COLD_RESTART_TIMESTAMP_MILLIS = 29_000L
private const val ATTACHMENT_COLD_RESTART_TIMEOUT_MILLIS = 30_000L
private val ATTACHMENT_COLD_RESTART_IDENTITY = DraftIdentity.ProviderThread(SYNTHETIC_THREAD_ID)
private val ATTACHMENT_COLD_RESTART_PNG_BYTES = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a,
    0x41,
    0x55,
    0x52,
    0x4f,
    0x52,
    0x41,
)
private const val ATTACHMENT_COLD_RESTART_GATE_REQUIRED =
    "cold restart attachment gate was not enabled"
private const val ATTACHMENT_COLD_RESTART_EMULATOR_REQUIRED =
    "cold restart attachment evidence requires an emulator"
private const val ATTACHMENT_COLD_RESTART_API_REQUIRED =
    "cold restart attachment evidence requires API 36"
private const val ATTACHMENT_COLD_RESTART_PHASE_INVALID =
    "cold restart attachment phase was invalid"
private const val ATTACHMENT_COLD_RESTART_APPLICATION_MISSING =
    "AuroraSMS application was unavailable for cold restart attachment evidence"
private const val ATTACHMENT_COLD_RESTART_STATE_NOT_READY =
    "AuroraSMS state storage did not become ready for cold restart attachment evidence"
private const val ATTACHMENT_COLD_RESTART_STALE_CHECKPOINT =
    "cold restart attachment checkpoint already exists"
private const val ATTACHMENT_COLD_RESTART_DRAFT_NOT_EMPTY =
    "cold restart attachment draft identity was not empty"
private const val ATTACHMENT_COLD_RESTART_ATTACHMENT_WRITE_FAILED =
    "cold restart attachment could not be written"
private const val ATTACHMENT_COLD_RESTART_DRAFT_MISMATCH =
    "cold restart attachment draft changed"
private const val ATTACHMENT_COLD_RESTART_ATTACHMENT_MISMATCH =
    "cold restart attachment bytes changed"
private const val ATTACHMENT_COLD_RESTART_ROUTE_MISMATCH =
    "cold restart attachment did not route one exact MMS operation"
private const val ATTACHMENT_COLD_RESTART_PROCESS_NOT_RESTARTED =
    "cold restart attachment verification reused the preparation process"
private const val ATTACHMENT_COLD_RESTART_CLEANUP_PROCESS_NOT_RESTARTED =
    "cold restart attachment cleanup reused the verification process"
private const val ATTACHMENT_COLD_RESTART_CHECKPOINT_WRITE_FAILED =
    "cold restart attachment checkpoint could not be written"
private const val ATTACHMENT_COLD_RESTART_CHECKPOINT_INVALID =
    "cold restart attachment checkpoint was invalid"
private const val ATTACHMENT_COLD_RESTART_CHECKPOINT_CLEAR_FAILED =
    "cold restart attachment checkpoint could not be cleared"
private const val ATTACHMENT_COLD_RESTART_CLEANUP_FAILED =
    "cold restart attachment cleanup could not read its draft"
private const val ATTACHMENT_COLD_RESTART_DRAFT_NOT_REMOVED =
    "cold restart attachment draft was not removed"
private const val ATTACHMENT_COLD_RESTART_ATTACHMENT_NOT_REMOVED =
    "cold restart attachment cascade did not remove its bytes"
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
