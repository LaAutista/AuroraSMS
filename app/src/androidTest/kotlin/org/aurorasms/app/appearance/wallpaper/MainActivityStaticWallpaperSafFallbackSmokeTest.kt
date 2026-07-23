// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.service.notification.StatusBarNotification
import android.system.Os
import android.system.OsConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.MainActivity
import org.aurorasms.app.R
import org.aurorasms.app.StateStorageStatus
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_CANCEL_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_WALLPAPER_TEST_TAG
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.notifications.NotificationChannels
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.notifications.NotificationPrivacy
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.feature.conversations.CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCREEN_TEST_TAG
import org.aurorasms.feature.conversations.THREAD_SCREEN_TEST_TAG
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner-invoked API 26 AOSP smoke for the AndroidX Photo Picker contract's SAF fallback.
 *
 * The cancellation method selects no document and never traverses DocumentsUI content. The
 * separately gated, emulator-only selection journeys open only one exact test-APK SAF root and one
 * exact synthetic document. Unmatched visible titles are neither logged nor acted on, and no
 * shared-storage content is opened. Every method uses the real MainActivity editor and production
 * AndroidX picker contract.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O, maxSdkVersion = Build.VERSION_CODES.O)
class MainActivityStaticWallpaperSafFallbackSmokeTest {
    @Test
    fun realGlobalThreadSafFallbackBackCancellationRestoresEditorAndBaseline() {
        requireExplicitGate()
        assumeTrue(API_26_REQUIRED, Build.VERSION.SDK_INT == Build.VERSION_CODES.O)

        var automation: UiAutomation? = null
        var originalServiceFlags: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: WallpaperController? = null
        var baseline: CancellationBaseline? = null

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activeAutomation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            automation = activeAutomation
            originalServiceFlags = activeAutomation.serviceInfo.flags
            activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context as? AuroraSmsApplication
                ?: failFixed(APPLICATION_REQUIRED)
            awaitStateStorageReady(application)
            assertExactSafFallbackContract(context)

            val activeController = application.container.wallpaperController
            controller = activeController
            val launchIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val activeScenario = ActivityScenario.launch<MainActivity>(launchIntent)
            scenario = activeScenario

            activeScenario.onActivity { activity ->
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) failFixed(INBOX_ROUTE_REQUIRED)
            }
            waitForStableInitialInbox(activeAutomation).recycleSafely()

            val durableBaseline = CancellationBaseline(
                assignment = readReadyAssignment(activeController),
                managedFiles = managedFileNames(context),
                persistedGrants = persistedGrantSnapshot(context.contentResolver),
            )
            baseline = durableBaseline

            openGlobalWallpaperEditor(activeAutomation)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_PICK_TEST_TAG)
            waitForPackage(activeAutomation, DOCUMENTS_UI_PACKAGE)
            if (!activeAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                failFixed(SAF_CANCEL_FAILED)
            }

            waitForPackage(activeAutomation, TARGET_PACKAGE)
            waitForAppTag(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG).recycleSafely()
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_PICK_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_ERROR_TEST_TAG)
            val applyEnabled = waitForAppTag(
                activeAutomation,
                SCOPED_WALLPAPER_APPLY_TEST_TAG,
            ).useNode { node -> node.isEnabled }
            if (applyEnabled) failFixed(APPLY_ENABLED_AFTER_CANCEL)
            assertBaselineUnchanged(activeController, context, durableBaseline)
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_FAILURE)
        } finally {
            bestEffortDismissFocusedDocumentsUi(automation)
            try {
                scenario?.close()
            } catch (_: Throwable) {
                // The journey is read-only and its exact durable baseline is asserted above.
            }
            var finalBaselineFailure: FixedSmokeFailure? = null
            val cleanupController = controller
            val cleanupBaseline = baseline
            if (cleanupController != null && cleanupBaseline != null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    assertBaselineUnchanged(cleanupController, context, cleanupBaseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalBaselineFailure = fixed
                } catch (_: Throwable) {
                    finalBaselineFailure = FixedSmokeFailure(FINAL_BASELINE_UNAVAILABLE)
                }
            }
            val activeAutomation = automation
            val flagsToRestore = originalServiceFlags
            if (activeAutomation != null && flagsToRestore != null) {
                try {
                    activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                        flags = flagsToRestore
                    }
                } catch (_: Throwable) {
                    // Never replace the fixed primary result with platform-specific text.
                }
            }
            finalBaselineFailure?.let { throw it }
        }
    }

    @Test
    fun realGlobalThreadSafFallbackSelectionLifecycleRestoresBaseline() {
        requireExplicitSelectionGate()
        assumeTrue(API_26_SELECTION_REQUIRED, Build.VERSION.SDK_INT == Build.VERSION_CODES.O)

        var automation: UiAutomation? = null
        var originalServiceFlags: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: WallpaperController? = null
        var baseline: SelectionBaseline? = null
        var appliedTestAssignment: AppWallpaperAssignment? = null

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activeAutomation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            automation = activeAutomation
            originalServiceFlags = activeAutomation.serviceInfo.flags
            activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context as? AuroraSmsApplication
                ?: failFixed(APPLICATION_REQUIRED)
            awaitStateStorageReady(application)
            assertExactSafFallbackContract(context)
            assertSyntheticDocumentsProviderRegistered(context)
            resetSyntheticDocumentState(context)
            assertExactSyntheticDocumentUri()

            val activeController = application.container.wallpaperController
            controller = activeController
            val launchIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val activeScenario = ActivityScenario.launch<MainActivity>(launchIntent)
            scenario = activeScenario

            activeScenario.onActivity { activity ->
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) failFixed(INBOX_ROUTE_REQUIRED)
            }
            waitForStableInitialInbox(activeAutomation).recycleSafely()

            val durableBaseline = SelectionBaseline(
                assignment = readReadyAssignment(activeController),
                managedFiles = managedFileLedger(context),
                persistedGrants = persistedGrantSnapshot(context.contentResolver),
                revisionSequence = readAppearanceRevisionSequence(context),
            )
            if (durableBaseline.assignment != null) failFixed(SELECTION_EMPTY_BASELINE_REQUIRED)
            baseline = durableBaseline
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // A real returned SAF result creates only a transient preview. Cancel owns no commit.
            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // The wallpaper Back control also discards the editor's selected source and preview.
            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_BACK_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()

            // Activity recreation may restore only bounded numeric editor state, never the URI.
            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            activeScenario.recreate()
            waitForPackage(activeAutomation, TARGET_PACKAGE)
            waitForAppTag(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG).recycleSafely()
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_PICK_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_ERROR_TEST_TAG)
            if (isAppTagEnabled(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)) {
                failFixed(SELECTION_SURVIVED_RECREATION)
            }
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()

            // Apply must reopen the source. A vanished source fails without consuming a revision.
            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            val beforeUnavailableApply = syntheticDocumentSnapshot(context)
            setSyntheticDocumentAvailable(context, available = false)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            scrollToAppTag(activeAutomation, SCOPED_WALLPAPER_ERROR_TEST_TAG).recycleSafely()
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
            val afterUnavailableApply = syntheticDocumentSnapshot(context)
            if (
                afterUnavailableApply.openAttempts <= beforeUnavailableApply.openAttempts ||
                afterUnavailableApply.successfulOpens != beforeUnavailableApply.successfulOpens ||
                afterUnavailableApply.lastDocumentId !=
                WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID
            ) {
                failFixed(SOURCE_LOSS_WAS_NOT_REVALIDATED)
            }
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // Restoring the exact source lets the same transient selection cross only Apply.
            setSyntheticDocumentAvailable(context, available = true)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)
            val applied = awaitAssignment(activeController, assigned = true)
                ?: failFixed(APPLIED_ASSIGNMENT_REQUIRED)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            if (readAppearanceRevisionSequence(context) != durableBaseline.revisionSequence + 1L) {
                failFixed(APPLY_REVISION_SEQUENCE_UNEXPECTED)
            }
            if (applied.revision != durableBaseline.revisionSequence + 1L) {
                failFixed(APPLIED_ASSIGNMENT_REVISION_UNEXPECTED)
            }
            assertExactlyOneNewManagedFinal(context, durableBaseline, applied)
            appliedTestAssignment = applied
            assertPersistedGrantsUnchanged(context, durableBaseline)
            val afterSuccessfulApply = syntheticDocumentSnapshot(context)
            if (
                afterSuccessfulApply.successfulOpens <= beforeUnavailableApply.successfulOpens ||
                afterSuccessfulApply.lastDocumentId !=
                WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID
            ) {
                failFixed(SUCCESSFUL_APPLY_DID_NOT_REOPEN_SOURCE)
            }

            // Managed decode/load is now independent of the temporary SAF source.
            setSyntheticDocumentAvailable(context, available = false)
            assertManagedWallpaperLoads(activeController, applied)

            // Reset is the second and final durable mutation; it removes only the test commit.
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
            scrollToAndClickAppTag(activeAutomation, SCOPED_WALLPAPER_RESET_TEST_TAG)
            awaitAssignment(activeController, assigned = false)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            if (readAppearanceRevisionSequence(context) != durableBaseline.revisionSequence + 1L) {
                failFixed(RESET_REVISION_SEQUENCE_UNEXPECTED)
            }
            assertSelectionBaselineRestored(activeController, context, durableBaseline)
            appliedTestAssignment = null
            setSyntheticDocumentAvailable(context, available = true)
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertSelectionBaselineRestored(activeController, context, durableBaseline)
            if (readAppearanceRevisionSequence(context) != durableBaseline.revisionSequence + 1L) {
                failFixed(STALE_APPLY_RESET_REVISION_UNEXPECTED)
            }

            instrumentation.sendStatus(
                SELECTION_SENTINEL_STATUS_CODE,
                Bundle().apply { putString(SELECTION_SENTINEL_KEY, SELECTION_SENTINEL_VALUE) },
            )
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_SELECTION_FAILURE)
        } finally {
            bestEffortDismissFocusedDocumentsUi(automation)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                setSyntheticDocumentAvailable(context, available = true)
            } catch (_: Throwable) {
                // The exact durable cleanup below remains authoritative.
            }
            try {
                scenario?.close()
            } catch (_: Throwable) {
                // Cleanup below verifies durable state directly.
            }
            val cleanupController = controller
            val cleanupAssignment = appliedTestAssignment
            if (cleanupController != null && cleanupAssignment != null) {
                bestEffortResetTestAssignment(cleanupController, cleanupAssignment)
            }
            var finalBaselineFailure: FixedSmokeFailure? = null
            val cleanupBaseline = baseline
            if (cleanupController != null && cleanupBaseline != null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    assertSelectionBaselineRestored(cleanupController, context, cleanupBaseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalBaselineFailure = fixed
                } catch (_: Throwable) {
                    finalBaselineFailure = FixedSmokeFailure(FINAL_SELECTION_BASELINE_UNAVAILABLE)
                }
            }
            val activeAutomation = automation
            val flagsToRestore = originalServiceFlags
            if (activeAutomation != null && flagsToRestore != null) {
                try {
                    activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                        flags = flagsToRestore
                    }
                } catch (_: Throwable) {
                    // Never replace the fixed primary result with platform-specific text.
                }
            }
            finalBaselineFailure?.let { throw it }
        }
    }

    @Test
    fun realGlobalThreadSafFallbackRouteLossAndStaleApplyPreserveAuthority() {
        requireExplicitStaleApplyGate()
        assumeTrue(API_26_STALE_APPLY_REQUIRED, Build.VERSION.SDK_INT == Build.VERSION_CODES.O)

        var automation: UiAutomation? = null
        var originalServiceFlags: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: WallpaperController? = null
        var baseline: SelectionBaseline? = null
        var concurrentTestAssignment: AppWallpaperAssignment? = null
        var concurrentApplySucceeded = false

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activeAutomation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            automation = activeAutomation
            originalServiceFlags = activeAutomation.serviceInfo.flags
            activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context as? AuroraSmsApplication
                ?: failFixed(APPLICATION_REQUIRED)
            awaitStateStorageReady(application)
            assertExactSafFallbackContract(context)
            assertSyntheticDocumentsProviderRegistered(context)
            resetSyntheticDocumentState(context)
            assertExactSyntheticDocumentUri()

            val activeController = application.container.wallpaperController
            controller = activeController
            val launchIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val activeScenario = ActivityScenario.launch<MainActivity>(launchIntent)
            scenario = activeScenario

            activeScenario.onActivity { activity ->
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) failFixed(INBOX_ROUTE_REQUIRED)
            }
            waitForStableInitialInbox(activeAutomation).recycleSafely()

            val durableBaseline = SelectionBaseline(
                assignment = readReadyAssignment(activeController),
                managedFiles = managedFileLedger(context),
                persistedGrants = persistedGrantSnapshot(context.contentResolver),
                revisionSequence = readAppearanceRevisionSequence(context),
            )
            if (durableBaseline.assignment != null) failFixed(STALE_APPLY_EMPTY_BASELINE_REQUIRED)
            baseline = durableBaseline
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // Replacing the editor route before Apply must discard only the transient selection.
            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            val beforeRouteReplacement = syntheticDocumentSnapshot(context)
            activeScenario.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(
                    activity,
                    Intent(context, MainActivity::class.java)
                        .setAction(AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION)
                        .putExtra(
                            AppNotificationIntentFactory.EXTRA_CONVERSATION_ID,
                            STALE_APPLY_SYNTHETIC_CONVERSATION_ID,
                        ),
                )
            }
            waitForPackage(activeAutomation, TARGET_PACKAGE)
            waitForAppTag(activeAutomation, THREAD_SCREEN_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            if (syntheticDocumentSnapshot(context) != beforeRouteReplacement) {
                failFixed(ROUTE_REPLACEMENT_REOPENED_SOURCE)
            }
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)
            if (!activeAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                failFixed(ROUTE_REPLACEMENT_BACK_FAILED)
            }
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // Reopening must start empty; otherwise the dismissed route leaked its transient URI.
            openGlobalWallpaperEditor(activeAutomation)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_ERROR_TEST_TAG)
            if (isAppTagEnabled(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)) {
                failFixed(ROUTE_REPLACEMENT_SELECTION_SURVIVED)
            }
            if (syntheticDocumentSnapshot(context) != beforeRouteReplacement) {
                failFixed(ROUTE_REPLACEMENT_REOPENED_SOURCE)
            }
            assertSelectionBaselineUnchanged(activeController, context, durableBaseline)

            // A newer authoritative assignment must win over the editor's captured empty revision.
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, durableBaseline)
            val concurrentResult = runBlocking {
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    activeController.apply(
                        scope = GLOBAL_THREAD_SCOPE,
                        source = STALE_APPLY_CONCURRENT_SOURCE_URI,
                        dimPermill = STALE_APPLY_CONCURRENT_DIM_PERMILL,
                        focalXPermill = STALE_APPLY_CONCURRENT_FOCAL_X_PERMILL,
                        focalYPermill = STALE_APPLY_CONCURRENT_FOCAL_Y_PERMILL,
                        expectedRevision = null,
                    )
                }
            }
            if (concurrentResult != WallpaperApplyControllerResult.Success) {
                failFixed(CONCURRENT_ASSIGNMENT_APPLY_FAILED)
            }
            concurrentApplySucceeded = true
            val concurrent = awaitAssignment(activeController, assigned = true)
                ?: failFixed(CONCURRENT_ASSIGNMENT_REQUIRED)
            concurrentTestAssignment = concurrent
            if (
                concurrent.revision != durableBaseline.revisionSequence + 1L ||
                concurrent.dimPermill != STALE_APPLY_CONCURRENT_DIM_PERMILL ||
                concurrent.focalXPermill != STALE_APPLY_CONCURRENT_FOCAL_X_PERMILL ||
                concurrent.focalYPermill != STALE_APPLY_CONCURRENT_FOCAL_Y_PERMILL
            ) {
                failFixed(CONCURRENT_ASSIGNMENT_INVALID)
            }
            if (readAppearanceRevisionSequence(context) != durableBaseline.revisionSequence + 1L) {
                failFixed(CONCURRENT_ASSIGNMENT_REVISION_UNEXPECTED)
            }
            assertExactlyOneNewManagedFinal(context, durableBaseline, concurrent)
            assertPersistedGrantsUnchanged(context, durableBaseline)
            assertManagedWallpaperAvailable(activeController, concurrent)

            val concurrentBaseline = SelectionBaseline(
                assignment = concurrent,
                managedFiles = managedFileLedger(context),
                persistedGrants = durableBaseline.persistedGrants,
                revisionSequence = durableBaseline.revisionSequence + 1L,
            )
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
            waitForAppTag(activeAutomation, SCOPED_WALLPAPER_PREVIEW_TEST_TAG).recycleSafely()
            val beforeStaleApply = syntheticDocumentSnapshot(context)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            val staleError = scrollToAppTag(
                activeAutomation,
                SCOPED_WALLPAPER_ERROR_TEST_TAG,
            ).useNode { node -> node.text?.toString() }
            if (staleError != context.getString(R.string.wallpaper_error_stale_assignment)) {
                failFixed(STALE_APPLY_ERROR_REQUIRED)
            }
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
            val afterStaleApply = syntheticDocumentSnapshot(context)
            if (
                afterStaleApply.openAttempts <= beforeStaleApply.openAttempts ||
                afterStaleApply.successfulOpens <= beforeStaleApply.successfulOpens ||
                afterStaleApply.lastDocumentId !=
                WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID
            ) {
                failFixed(STALE_APPLY_DID_NOT_REOPEN_SOURCE)
            }
            assertStaleWinnerUnchanged(activeController, context, concurrentBaseline)
            assertManagedWallpaperAvailable(activeController, concurrent)

            // Reopen against the winner's exact revision and reset only that controlled commit.
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_BACK_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            assertStaleWinnerUnchanged(activeController, context, concurrentBaseline)
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
            scrollToAndClickAppTag(activeAutomation, SCOPED_WALLPAPER_RESET_TEST_TAG)
            awaitAssignment(activeController, assigned = false)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            if (readAppearanceRevisionSequence(context) != durableBaseline.revisionSequence + 1L) {
                failFixed(STALE_APPLY_RESET_REVISION_UNEXPECTED)
            }
            assertSelectionBaselineRestored(activeController, context, durableBaseline)
            concurrentTestAssignment = null
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertSelectionBaselineRestored(activeController, context, durableBaseline)

            instrumentation.sendStatus(
                STALE_APPLY_SENTINEL_STATUS_CODE,
                Bundle().apply {
                    putString(STALE_APPLY_SENTINEL_KEY, STALE_APPLY_SENTINEL_VALUE)
                },
            )
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_STALE_APPLY_FAILURE)
        } finally {
            bestEffortDismissFocusedDocumentsUi(automation)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                setSyntheticDocumentAvailable(context, available = true)
            } catch (_: Throwable) {
                // The exact assignment cleanup below remains authoritative.
            }
            try {
                scenario?.close()
            } catch (_: Throwable) {
                // Cleanup below verifies durable state directly.
            }
            val cleanupController = controller
            val cleanupBaseline = baseline
            val cleanupAssignment = concurrentTestAssignment ?: if (
                concurrentApplySucceeded && cleanupController != null && cleanupBaseline != null
            ) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    recoverControlledConcurrentAssignment(
                        cleanupController,
                        context,
                        cleanupBaseline,
                    )
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            if (cleanupController != null && cleanupAssignment != null) {
                bestEffortResetTestAssignment(cleanupController, cleanupAssignment)
            }
            var finalBaselineFailure: FixedSmokeFailure? = null
            if (cleanupController != null && cleanupBaseline != null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    assertSelectionBaselineRestored(cleanupController, context, cleanupBaseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalBaselineFailure = fixed
                } catch (_: Throwable) {
                    finalBaselineFailure = FixedSmokeFailure(FINAL_STALE_APPLY_BASELINE_UNAVAILABLE)
                }
            }
            val activeAutomation = automation
            val flagsToRestore = originalServiceFlags
            if (activeAutomation != null && flagsToRestore != null) {
                try {
                    activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                        flags = flagsToRestore
                    }
                } catch (_: Throwable) {
                    // Never replace the fixed primary result with platform-specific text.
                }
            }
            finalBaselineFailure?.let { throw it }
        }
    }

    @Test
    fun realGlobalThreadSafFallbackNotificationPendingIntentRouteLossPreservesBaseline() {
        requireExplicitNotificationPendingIntentGate()
        assumeTrue(
            API_26_NOTIFICATION_PENDING_INTENT_REQUIRED,
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O,
        )

        var automation: UiAutomation? = null
        var originalServiceFlags: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var scenarioLaunchIntent: Intent? = null
        var controller: WallpaperController? = null
        var durableBaseline: SelectionBaseline? = null
        var notificationBaseline: Set<ActiveNotificationIdentity>? = null
        var channelBaseline: Set<NotificationChannelSnapshot>? = null
        var controlledNotificationPosted = false

        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activeAutomation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            automation = activeAutomation
            originalServiceFlags = activeAutomation.serviceInfo.flags
            activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context as? AuroraSmsApplication
                ?: failFixed(APPLICATION_REQUIRED)
            awaitStateStorageReady(application)
            assertExactSafFallbackContract(context)
            assertSyntheticDocumentsProviderRegistered(context)
            resetSyntheticDocumentState(context)
            assertExactSyntheticDocumentUri()

            try {
                NotificationChannels.ensureCreated(context)
            } catch (_: Throwable) {
                failFixed(NOTIFICATION_CHANNEL_BOOTSTRAP_FAILED)
            }
            val stableChannelBaseline = notificationChannelSnapshot(context)
            val stableChannelIds = stableChannelBaseline.mapTo(mutableSetOf()) { channel ->
                channel.id
            }
            if (!stableChannelIds.containsAll(EXPECTED_NOTIFICATION_CHANNEL_IDS)) {
                failFixed(NOTIFICATION_CHANNEL_PRECONDITION_REQUIRED)
            }
            channelBaseline = stableChannelBaseline
            val stableNotificationBaseline = activeNotificationSnapshot(context)
            if (
                stableNotificationBaseline.any { identity ->
                    identity.id == NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID ||
                        identity.tag == NOTIFICATION_PENDING_INTENT_TAG
                }
            ) {
                failFixed(NOTIFICATION_PENDING_INTENT_COLLISION)
            }
            notificationBaseline = stableNotificationBaseline

            val activeController = application.container.wallpaperController
            controller = activeController
            val activeNotifier = application.container.messageNotifier
            val launchIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            scenarioLaunchIntent = Intent(launchIntent)
            val activeScenario = ActivityScenario.launch<MainActivity>(launchIntent)
            scenario = activeScenario

            var activityIdentity = 0
            activeScenario.onActivity { activity ->
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) failFixed(INBOX_ROUTE_REQUIRED)
                activityIdentity = System.identityHashCode(activity)
            }
            waitForStableInitialInbox(activeAutomation).recycleSafely()

            val baseline = SelectionBaseline(
                assignment = readReadyAssignment(activeController),
                managedFiles = managedFileLedger(context),
                persistedGrants = persistedGrantSnapshot(context.contentResolver),
                revisionSequence = readAppearanceRevisionSequence(context),
            )
            if (baseline.assignment != null) {
                failFixed(NOTIFICATION_PENDING_INTENT_EMPTY_BASELINE_REQUIRED)
            }
            durableBaseline = baseline
            assertSelectionBaselineUnchanged(activeController, context, baseline)

            openGlobalWallpaperEditor(activeAutomation)
            chooseExactSyntheticDocument(activeAutomation, context)
            assertTransientSelection(activeAutomation, activeController, context, baseline)
            val beforeNotificationPost = syntheticDocumentSnapshot(context)

            val notificationReceivedAt = System.currentTimeMillis()
            val postResult = activeNotifier.notifyIncoming(
                message = IncomingMessageNotification(
                    messageId = MessageId(
                        ProviderKind.SMS,
                        NOTIFICATION_PENDING_INTENT_MESSAGE_ID,
                    ),
                    conversationId = ConversationId(
                        NOTIFICATION_PENDING_INTENT_CONVERSATION_ID,
                    ),
                    senderDisplayName = NOTIFICATION_PENDING_INTENT_SENDER,
                    senderPersonKey = NOTIFICATION_PENDING_INTENT_PERSON_KEY,
                    body = NOTIFICATION_PENDING_INTENT_BODY,
                    receivedAtEpochMillis = notificationReceivedAt,
                    canReply = false,
                ),
                config = NotificationConfig(privacy = NotificationPrivacy.SENDER_AND_BODY),
            )
            val posted = postResult as? NotificationPostResult.Posted
                ?: failFixed(NOTIFICATION_PENDING_INTENT_POST_FAILED)
            controlledNotificationPosted = true
            if (posted.notificationId != NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID) {
                failFixed(NOTIFICATION_PENDING_INTENT_ID_INVALID)
            }
            val statusBarNotification = awaitExactPostedNotification(
                context = context,
                baseline = stableNotificationBaseline,
                notificationId = posted.notificationId,
            )
            assertExactNotificationPendingIntent(
                context = context,
                statusBarNotification = statusBarNotification,
                expectedWhen = notificationReceivedAt,
            )
            assertNotificationChannelsUnchanged(context, stableChannelBaseline)
            if (syntheticDocumentSnapshot(context) != beforeNotificationPost) {
                failFixed(NOTIFICATION_PENDING_INTENT_REOPENED_SOURCE)
            }
            assertTransientSelection(activeAutomation, activeController, context, baseline)

            openExactAospNotificationShade(activeAutomation, context)
            waitForExactSystemUiNotificationBody(activeAutomation).useNode { body ->
                tapExactSystemUiNotificationBody(activeAutomation, body)
            }
            waitForPackage(activeAutomation, TARGET_PACKAGE)
            waitForAppTag(activeAutomation, THREAD_SCREEN_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForNotificationRouteConsumed(activeScenario, activityIdentity)
            if (syntheticDocumentSnapshot(context) != beforeNotificationPost) {
                failFixed(NOTIFICATION_PENDING_INTENT_REOPENED_SOURCE)
            }
            assertSelectionBaselineUnchanged(activeController, context, baseline)
            awaitActiveNotificationBaseline(context, stableNotificationBaseline)
            controlledNotificationPosted = false
            assertNotificationChannelsUnchanged(context, stableChannelBaseline)

            if (!activeAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                failFixed(NOTIFICATION_PENDING_INTENT_BACK_FAILED)
            }
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            openGlobalWallpaperEditor(activeAutomation)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_ERROR_TEST_TAG)
            if (isAppTagEnabled(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)) {
                failFixed(NOTIFICATION_PENDING_INTENT_SELECTION_SURVIVED)
            }
            if (syntheticDocumentSnapshot(context) != beforeNotificationPost) {
                failFixed(NOTIFICATION_PENDING_INTENT_REOPENED_SOURCE)
            }
            assertSelectionBaselineUnchanged(activeController, context, baseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_BACK_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertSelectionBaselineUnchanged(activeController, context, baseline)
            awaitActiveNotificationBaseline(context, stableNotificationBaseline)
            assertNotificationChannelsUnchanged(context, stableChannelBaseline)

            instrumentation.sendStatus(
                NOTIFICATION_PENDING_INTENT_SENTINEL_STATUS_CODE,
                Bundle().apply {
                    putString(
                        NOTIFICATION_PENDING_INTENT_SENTINEL_KEY,
                        NOTIFICATION_PENDING_INTENT_SENTINEL_VALUE,
                    )
                },
            )
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_NOTIFICATION_PENDING_INTENT_FAILURE)
        } finally {
            bestEffortDismissFocusedTransientSystemUi(automation)
            bestEffortDismissFocusedDocumentsUi(automation)
            if (controlledNotificationPosted) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    context.getSystemService(NotificationManager::class.java)?.cancel(
                        NOTIFICATION_PENDING_INTENT_TAG,
                        NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID,
                    )
                } catch (_: Throwable) {
                    // Exact notification-baseline verification below is authoritative.
                }
            }
            var finalNotificationFailure: FixedSmokeFailure? = null
            val stableNotificationBaseline = notificationBaseline
            if (stableNotificationBaseline != null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    awaitActiveNotificationBaseline(context, stableNotificationBaseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalNotificationFailure = fixed
                } catch (_: Throwable) {
                    finalNotificationFailure = FixedSmokeFailure(
                        FINAL_NOTIFICATION_PENDING_INTENT_BASELINE_UNAVAILABLE,
                    )
                }
            }
            val stableChannelBaseline = channelBaseline
            if (stableChannelBaseline != null && finalNotificationFailure == null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    assertNotificationChannelsUnchanged(context, stableChannelBaseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalNotificationFailure = fixed
                } catch (_: Throwable) {
                    finalNotificationFailure = FixedSmokeFailure(
                        FINAL_NOTIFICATION_PENDING_INTENT_CHANNEL_BASELINE_UNAVAILABLE,
                    )
                }
            }
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                setSyntheticDocumentAvailable(context, available = true)
            } catch (_: Throwable) {
                // Durable-state verification below remains authoritative.
            }
            var finalScenarioFailure: FixedSmokeFailure? = null
            val activeScenario = scenario
            if (activeScenario != null) {
                try {
                    // MainActivity replaces a consumed notification route with ACTION_MAIN.
                    // ActivityScenario tracks the launch intent's filter identity, so restore
                    // that identity before close even if routing failed partway through.
                    scenarioLaunchIntent?.let { launchIntent ->
                        activeScenario.onActivity { activity ->
                            activity.intent = Intent(launchIntent)
                        }
                    }
                } catch (_: Throwable) {
                    finalScenarioFailure = FixedSmokeFailure(
                        FINAL_NOTIFICATION_PENDING_INTENT_ACTIVITY_CLEANUP_UNAVAILABLE,
                    )
                }
                try {
                    activeScenario.close()
                } catch (_: Throwable) {
                    if (finalScenarioFailure == null) {
                        finalScenarioFailure = FixedSmokeFailure(
                            FINAL_NOTIFICATION_PENDING_INTENT_ACTIVITY_CLEANUP_UNAVAILABLE,
                        )
                    }
                }
            }
            var finalDurableFailure: FixedSmokeFailure? = null
            val cleanupController = controller
            val baseline = durableBaseline
            if (cleanupController != null && baseline != null) {
                try {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    assertSelectionBaselineUnchanged(cleanupController, context, baseline)
                } catch (fixed: FixedSmokeFailure) {
                    finalDurableFailure = fixed
                } catch (_: Throwable) {
                    finalDurableFailure = FixedSmokeFailure(
                        FINAL_NOTIFICATION_PENDING_INTENT_DURABLE_BASELINE_UNAVAILABLE,
                    )
                }
            }
            val activeAutomation = automation
            val flagsToRestore = originalServiceFlags
            if (activeAutomation != null && flagsToRestore != null) {
                try {
                    activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                        flags = flagsToRestore
                    }
                } catch (_: Throwable) {
                    // Never replace fixed cleanup evidence with platform-specific text.
                }
            }
            finalNotificationFailure?.let { throw it }
            finalScenarioFailure?.let { throw it }
            finalDurableFailure?.let { throw it }
        }
    }

    @Test
    fun exactSyntheticNotificationPendingIntentCleanupOnly() {
        requireExplicitNotificationCleanupGate()
        assumeTrue(
            API_26_NOTIFICATION_PENDING_INTENT_REQUIRED,
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O,
        )

        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
            val exactActive = activeNotifications(context).filter { notification ->
                notification.id == NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID &&
                    notification.tag == NOTIFICATION_PENDING_INTENT_TAG
            }
            if (exactActive.size > 1) {
                failFixed(EXACT_SYNTHETIC_NOTIFICATION_CLEANUP_SCOPE_INVALID)
            }
            exactActive.singleOrNull()?.let { notification ->
                assertControlledSyntheticNotificationFingerprint(
                    context = context,
                    statusBarNotification = notification,
                    expectedWhen = null,
                )
                manager.cancel(
                    NOTIFICATION_PENDING_INTENT_TAG,
                    NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID,
                )
            }
            awaitExactSyntheticNotificationAbsent(context)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                NOTIFICATION_CLEANUP_SENTINEL_STATUS_CODE,
                Bundle().apply {
                    putString(
                        NOTIFICATION_CLEANUP_SENTINEL_KEY,
                        NOTIFICATION_CLEANUP_SENTINEL_VALUE,
                    )
                },
            )
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_NOTIFICATION_CLEANUP_FAILURE)
        }
    }

    private fun requireExplicitGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(GATE_REQUIRED, enabled)
        assumeTrue(
            EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
    }

    private fun requireExplicitSelectionGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(SELECTION_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(SELECTION_GATE_REQUIRED, enabled)
        assumeTrue(
            EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
    }

    private fun requireExplicitStaleApplyGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(STALE_APPLY_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(STALE_APPLY_GATE_REQUIRED, enabled)
        assumeTrue(
            EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
    }

    private fun requireExplicitNotificationPendingIntentGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(NOTIFICATION_PENDING_INTENT_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(NOTIFICATION_PENDING_INTENT_GATE_REQUIRED, enabled)
        assumeTrue(
            EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
    }

    private fun requireExplicitNotificationCleanupGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(NOTIFICATION_CLEANUP_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(NOTIFICATION_CLEANUP_GATE_REQUIRED, enabled)
        assumeTrue(
            EMULATOR_REQUIRED,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
    }

    private fun notificationChannelSnapshot(
        context: Context,
    ): Set<NotificationChannelSnapshot> {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
        return try {
            manager.notificationChannels.mapTo(mutableSetOf()) { channel ->
                channel.toSnapshot()
            }
        } catch (_: Throwable) {
            failFixed(NOTIFICATION_CHANNEL_BASELINE_UNAVAILABLE)
        }
    }

    private fun NotificationChannel.toSnapshot(): NotificationChannelSnapshot =
        NotificationChannelSnapshot(
            id = id,
            name = name?.toString(),
            description = description,
            importance = importance,
            lockscreenVisibility = lockscreenVisibility,
            bypassDnd = canBypassDnd(),
            showBadge = canShowBadge(),
            showLights = shouldShowLights(),
            lightColor = lightColor,
            vibrate = shouldVibrate(),
            vibrationPattern = vibrationPattern?.toList(),
            sound = sound?.toString(),
            group = group,
            audioUsage = audioAttributes?.usage,
            audioContentType = audioAttributes?.contentType,
            audioFlags = audioAttributes?.flags,
        )

    private fun assertNotificationChannelsUnchanged(
        context: Context,
        baseline: Set<NotificationChannelSnapshot>,
    ) {
        if (notificationChannelSnapshot(context) != baseline) {
            failFixed(NOTIFICATION_CHANNEL_BASELINE_CHANGED)
        }
    }

    private fun activeNotificationSnapshot(context: Context): Set<ActiveNotificationIdentity> =
        activeNotifications(context).mapTo(mutableSetOf(), ::activeNotificationIdentity)

    private fun activeNotifications(context: Context): Array<StatusBarNotification> {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
        return try {
            manager.activeNotifications.also { notifications ->
                if (notifications.any { notification ->
                        notification.packageName != TARGET_PACKAGE
                    }
                ) {
                    failFixed(ACTIVE_NOTIFICATION_SCOPE_INVALID)
                }
            }
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(ACTIVE_NOTIFICATION_BASELINE_UNAVAILABLE)
        }
    }

    private fun activeNotificationIdentity(
        notification: StatusBarNotification,
    ): ActiveNotificationIdentity = ActiveNotificationIdentity(
        key = notification.key,
        packageName = notification.packageName,
        id = notification.id,
        tag = notification.tag,
        uid = notification.uid,
        postTime = notification.postTime,
    )

    private fun awaitExactPostedNotification(
        context: Context,
        baseline: Set<ActiveNotificationIdentity>,
        notificationId: Int,
    ): StatusBarNotification {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val active = activeNotifications(context)
            val added = active
                .filter { notification -> activeNotificationIdentity(notification) !in baseline }
            if (added.size == 1) {
                val notification = added.single()
                if (
                    notification.packageName == TARGET_PACKAGE &&
                    notification.id == notificationId &&
                    notification.tag == NOTIFICATION_PENDING_INTENT_TAG
                ) {
                    return notification
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EXACT_ACTIVE_NOTIFICATION_UNAVAILABLE)
    }

    private fun awaitActiveNotificationBaseline(
        context: Context,
        baseline: Set<ActiveNotificationIdentity>,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            if (activeNotificationSnapshot(context) == baseline) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(ACTIVE_NOTIFICATION_BASELINE_NOT_RESTORED)
    }

    private fun awaitExactSyntheticNotificationAbsent(context: Context) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val exactActive = activeNotifications(context).any { notification ->
                notification.id == NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID &&
                    notification.tag == NOTIFICATION_PENDING_INTENT_TAG
            }
            if (!exactActive) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EXACT_SYNTHETIC_NOTIFICATION_CLEANUP_FAILED)
    }

    private fun assertExactNotificationPendingIntent(
        context: Context,
        statusBarNotification: StatusBarNotification,
        expectedWhen: Long,
    ) = assertControlledSyntheticNotificationFingerprint(
        context = context,
        statusBarNotification = statusBarNotification,
        expectedWhen = expectedWhen,
    )

    private fun assertControlledSyntheticNotificationFingerprint(
        context: Context,
        statusBarNotification: StatusBarNotification,
        expectedWhen: Long?,
    ) {
        val notification = statusBarNotification.notification
        val contentIntent = notification.contentIntent
            ?: failFixed(NOTIFICATION_CONTENT_PENDING_INTENT_REQUIRED)
        val timestampMatches = expectedWhen?.let { expected ->
            notification.`when` == expected
        } ?: (notification.`when` > 0L)
        if (
            statusBarNotification.packageName != TARGET_PACKAGE ||
            statusBarNotification.uid != context.applicationInfo.uid ||
            statusBarNotification.id != NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID ||
            statusBarNotification.tag != NOTIFICATION_PENDING_INTENT_TAG ||
            !statusBarNotification.isClearable ||
            notification.channelId != NotificationChannels.MESSAGES ||
            notification.category != Notification.CATEGORY_MESSAGE ||
            notification.visibility != Notification.VISIBILITY_PRIVATE ||
            !timestampMatches ||
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() !=
            NOTIFICATION_PENDING_INTENT_SENDER ||
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() !=
            NOTIFICATION_PENDING_INTENT_BODY ||
            notification.flags and Notification.FLAG_AUTO_CANCEL == 0 ||
            notification.actions?.isNotEmpty() == true ||
            notification.publicVersion?.contentIntent != contentIntent ||
            contentIntent.creatorPackage != TARGET_PACKAGE ||
            contentIntent.creatorUid != context.applicationInfo.uid ||
            !contentIntent.isActivity
        ) {
            failFixed(NOTIFICATION_PENDING_INTENT_CONTRACT_INVALID)
        }
    }

    private fun openExactAospNotificationShade(
        automation: UiAutomation,
        context: Context,
    ) {
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        val endY = height * NOTIFICATION_SHADE_SWIPE_END_PERCENT / 100
        if (width <= 0 || height <= 0 || endY <= NOTIFICATION_SHADE_SWIPE_START_Y) {
            failFixed(NOTIFICATION_SHADE_OPEN_FAILED)
        }
        executeFixedShellCommand(
            automation = automation,
            command = "input touchscreen swipe ${width / 2} " +
                "$NOTIFICATION_SHADE_SWIPE_START_Y ${width / 2} $endY " +
                "$NOTIFICATION_SHADE_SWIPE_DURATION_MILLIS",
            failureMessage = NOTIFICATION_SHADE_OPEN_FAILED,
        )
        waitForExactExpandedSystemUiNotificationShade(automation, width, height)
    }

    private fun waitForExactExpandedSystemUiNotificationShade(
        automation: UiAutomation,
        width: Int,
        height: Int,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var stableSince = 0L
        do {
            val panel = findExactSystemUiResource(
                automation = automation,
                resource = SYSTEM_UI_NOTIFICATION_PANEL_RESOURCE,
            )
            val stack = findExactSystemUiResource(
                automation = automation,
                resource = SYSTEM_UI_NOTIFICATION_STACK_RESOURCE,
            )
            val exact = if (panel != null && stack != null) {
                val panelBounds = Rect().also(panel::getBoundsInScreen)
                val stackBounds = Rect().also(stack::getBoundsInScreen)
                panelBounds == Rect(0, 0, width, height) &&
                    stackBounds.left == 0 &&
                    stackBounds.top == 0 &&
                    stackBounds.right == width &&
                    stackBounds.bottom > height / 2 &&
                    stackBounds.bottom <= height
            } else {
                false
            }
            panel?.recycleSafely()
            stack?.recycleSafely()
            val now = SystemClock.uptimeMillis()
            if (exact) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= SYSTEM_UI_NODE_STABLE_MILLIS) return
            } else {
                stableSince = 0L
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(NOTIFICATION_SHADE_OPEN_FAILED)
    }

    private fun waitForExactSystemUiResource(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findExactSystemUiResource(automation, resource)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(NOTIFICATION_SHADE_OPEN_FAILED)
    }

    private fun findExactSystemUiResource(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo? {
        val windows = automation.windows
        val accepted = mutableListOf<AccessibilityNodeInfo>()
        return try {
            windows.forEach { window ->
                val root = window.root ?: return@forEach
                try {
                    if (root.packageName?.toString() != SYSTEM_UI_PACKAGE) return@forEach
                    val matches = root.findAccessibilityNodeInfosByViewId(resource).orEmpty()
                    matches.forEach { node ->
                        val exact = node.packageName?.toString() == SYSTEM_UI_PACKAGE &&
                            node.windowId == root.windowId &&
                            node.viewIdResourceName == resource &&
                            node.isVisibleToUser &&
                            node.isEnabled
                        if (exact) accepted += node else node.recycleSafely()
                    }
                } finally {
                    root.recycleSafely()
                }
            }
            if (accepted.size == 1) accepted.single() else {
                accepted.forEach { node -> node.recycleSafely() }
                null
            }
        } catch (throwable: Throwable) {
            accepted.forEach { node -> node.recycleSafely() }
            throw throwable
        } finally {
            windows.forEach { window -> window.recycleSafely() }
        }
    }

    private fun waitForExactSystemUiNotificationBody(
        automation: UiAutomation,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var stableBounds: Rect? = null
        var stableSince = 0L
        do {
            val node = findExactSystemUiNotificationBody(automation)
            if (node != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val now = SystemClock.uptimeMillis()
                if (bounds.width() > 0 && bounds.height() > 0 && bounds == stableBounds) {
                    if (now - stableSince >= SYSTEM_UI_NODE_STABLE_MILLIS) return node
                } else {
                    stableBounds = bounds
                    stableSince = now
                }
                node.recycleSafely()
            } else {
                stableBounds = null
                stableSince = 0L
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EXACT_SYSTEM_UI_NOTIFICATION_BODY_UNAVAILABLE)
    }

    private fun findExactSystemUiNotificationBody(
        automation: UiAutomation,
    ): AccessibilityNodeInfo? {
        val stack = findExactSystemUiResource(
            automation = automation,
            resource = SYSTEM_UI_NOTIFICATION_STACK_RESOURCE,
        ) ?: return null
        val accepted = mutableListOf<AccessibilityNodeInfo>()
        return try {
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (
                    visited >= MAXIMUM_SYSTEM_UI_NOTIFICATION_TREE_NODES ||
                    depth > MAXIMUM_SYSTEM_UI_NOTIFICATION_ANCESTOR_DEPTH
                ) {
                    return
                }
                visited += 1
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    val exact = child.packageName?.toString() == SYSTEM_UI_PACKAGE &&
                        child.windowId == stack.windowId &&
                        child.viewIdResourceName in SYSTEM_UI_NOTIFICATION_BODY_RESOURCES &&
                        child.text?.toString() == NOTIFICATION_PENDING_INTENT_BODY &&
                        child.isVisibleToUser &&
                        child.isEnabled &&
                        hasExactClickableSystemUiNotificationRow(child, stack)
                    if (exact) {
                        accepted += child
                    } else {
                        try {
                            visit(child, depth + 1)
                        } finally {
                            child.recycleSafely()
                        }
                    }
                }
            }
            visit(stack, 0)
            if (accepted.size == 1) accepted.single() else {
                accepted.forEach { node -> node.recycleSafely() }
                null
            }
        } catch (throwable: Throwable) {
            accepted.forEach { node -> node.recycleSafely() }
            throw throwable
        } finally {
            stack.recycleSafely()
        }
    }

    private fun hasExactClickableSystemUiNotificationRow(
        body: AccessibilityNodeInfo,
        stack: AccessibilityNodeInfo,
    ): Boolean {
        val bodyBounds = Rect().also(body::getBoundsInScreen)
        val stackBounds = Rect().also(stack::getBoundsInScreen)
        if (
            bodyBounds.width() <= 0 ||
            bodyBounds.height() <= 0 ||
            stackBounds.width() <= 0 ||
            stackBounds.height() <= 0 ||
            !stackBounds.contains(bodyBounds.centerX(), bodyBounds.centerY())
        ) {
            return false
        }

        var ancestor = try {
            body.parent
        } catch (_: Throwable) {
            null
        }
        var requiredResourceIndex = 0
        var clickableRowCount = 0
        repeat(MAXIMUM_SYSTEM_UI_NOTIFICATION_ANCESTOR_DEPTH) {
            val current = ancestor ?: return false
            var parent: AccessibilityNodeInfo? = null
            try {
                if (current.windowId != stack.windowId) return false
                val bounds = Rect().also(current::getBoundsInScreen)
                if (
                    current.packageName?.toString() != SYSTEM_UI_PACKAGE ||
                    !current.isVisibleToUser ||
                    !current.isEnabled
                ) {
                    return false
                }
                if (
                    requiredResourceIndex < SYSTEM_UI_NOTIFICATION_ROW_RESOURCE_PATH.size &&
                    current.viewIdResourceName ==
                    SYSTEM_UI_NOTIFICATION_ROW_RESOURCE_PATH[requiredResourceIndex]
                ) {
                    requiredResourceIndex += 1
                }
                if (current.isClickable) {
                    if (
                        requiredResourceIndex <
                        SYSTEM_UI_NOTIFICATION_ROW_RESOURCE_PATH.lastIndex ||
                        current.className?.toString() != SYSTEM_UI_NOTIFICATION_ROW_CLASS ||
                        !current.viewIdResourceName.isNullOrEmpty() ||
                        !current.isVisibleToUser ||
                        !current.isEnabled ||
                        !bounds.contains(bodyBounds.centerX(), bodyBounds.centerY())
                    ) {
                        return false
                    }
                    clickableRowCount += 1
                }
                if (
                    current.viewIdResourceName == SYSTEM_UI_NOTIFICATION_STACK_RESOURCE
                ) {
                    return requiredResourceIndex ==
                        SYSTEM_UI_NOTIFICATION_ROW_RESOURCE_PATH.size &&
                        clickableRowCount == 1 &&
                        current.isVisibleToUser &&
                        current.isEnabled &&
                        bounds.contains(bodyBounds.centerX(), bodyBounds.centerY())
                }
                parent = current.parent
            } catch (_: Throwable) {
                return false
            } finally {
                current.recycleSafely()
            }
            ancestor = parent
        }
        ancestor?.recycleSafely()
        return false
    }

    private fun tapExactSystemUiNotificationBody(
        automation: UiAutomation,
        node: AccessibilityNodeInfo,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val stack = findExactSystemUiResource(
            automation = automation,
            resource = SYSTEM_UI_NOTIFICATION_STACK_RESOURCE,
        ) ?: failFixed(SYSTEM_UI_NOTIFICATION_BODY_INVALID)
        val exactRow = try {
            hasExactClickableSystemUiNotificationRow(node, stack)
        } finally {
            stack.recycleSafely()
        }
        if (
            node.packageName?.toString() != SYSTEM_UI_PACKAGE ||
            node.viewIdResourceName !in SYSTEM_UI_NOTIFICATION_BODY_RESOURCES ||
            node.text?.toString() != NOTIFICATION_PENDING_INTENT_BODY ||
            !node.isVisibleToUser ||
            !node.isEnabled ||
            bounds.width() <= 0 ||
            bounds.height() <= 0 ||
            !exactRow
        ) {
            failFixed(SYSTEM_UI_NOTIFICATION_BODY_INVALID)
        }
        executeFixedInputCommand(
            automation,
            "input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}",
        )
    }

    private fun waitForNotificationRouteConsumed(
        scenario: ActivityScenario<MainActivity>,
        expectedActivityIdentity: Int,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            var consumed = false
            scenario.onActivity { activity ->
                consumed = System.identityHashCode(activity) == expectedActivityIdentity &&
                    activity.openedConversationId == ConversationId(
                        NOTIFICATION_PENDING_INTENT_CONVERSATION_ID,
                    ) &&
                    activity.intent.action == Intent.ACTION_MAIN
            }
            if (consumed) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(NOTIFICATION_PENDING_INTENT_ROUTE_NOT_CONSUMED)
    }

    private fun assertExactSafFallbackContract(context: Context) {
        val intent = PickVisualMedia().createIntent(
            context,
            PickVisualMediaRequest(PickVisualMedia.ImageOnly),
        )
        if (intent.action != Intent.ACTION_OPEN_DOCUMENT) failFixed(OPEN_DOCUMENT_ACTION_REQUIRED)
        if (intent.type != IMAGE_MIME_TYPE) failFixed(IMAGE_ONLY_MIME_REQUIRED)
        val resolvedPackage = intent.resolveActivity(context.packageManager)?.packageName
        if (resolvedPackage != DOCUMENTS_UI_PACKAGE) failFixed(AOSP_DOCUMENTS_UI_REQUIRED)
    }

    private fun assertSyntheticDocumentsProviderRegistered(context: Context) {
        val provider = context.packageManager.resolveContentProvider(
            WallpaperTestDocumentsProvider.AUTHORITY,
            0,
        ) ?: failFixed(SYNTHETIC_DOCUMENTS_PROVIDER_REQUIRED)
        if (
            provider.authority != WallpaperTestDocumentsProvider.AUTHORITY ||
            provider.packageName != TEST_PACKAGE ||
            provider.name != WallpaperTestDocumentsProvider::class.java.name ||
            !provider.exported ||
            !provider.grantUriPermissions ||
            provider.readPermission != android.Manifest.permission.MANAGE_DOCUMENTS ||
            provider.writePermission != android.Manifest.permission.MANAGE_DOCUMENTS
        ) {
            failFixed(SYNTHETIC_DOCUMENTS_PROVIDER_INVALID)
        }
    }

    private fun assertExactSyntheticDocumentUri() {
        val source = DocumentsContract.buildDocumentUri(
            WallpaperTestDocumentsProvider.AUTHORITY,
            WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID,
        )
        if (
            source.scheme != ContentResolver.SCHEME_CONTENT ||
            source.authority != WallpaperTestDocumentsProvider.AUTHORITY ||
            DocumentsContract.getDocumentId(source) !=
            WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID ||
            source.toString().toByteArray(StandardCharsets.UTF_8).size >
            MAXIMUM_TRANSIENT_URI_UTF8_BYTES
        ) {
            failFixed(SYNTHETIC_DOCUMENT_URI_INVALID)
        }
    }

    private fun resetSyntheticDocumentState(context: Context) {
        val snapshot = callSyntheticDocumentProvider(
            context,
            WallpaperTestDocumentsProvider.METHOD_RESET,
        ).toSyntheticDocumentSnapshot()
        if (
            !snapshot.available ||
            snapshot.openAttempts != 0 ||
            snapshot.successfulOpens != 0 ||
            snapshot.lastDocumentId != null
        ) {
            failFixed(SYNTHETIC_DOCUMENT_STATE_INVALID)
        }
    }

    private fun setSyntheticDocumentAvailable(
        context: Context,
        available: Boolean,
    ) {
        val extras = Bundle().apply {
            putBoolean(WallpaperTestDocumentsProvider.KEY_AVAILABLE, available)
        }
        val snapshot = callSyntheticDocumentProvider(
            context,
            WallpaperTestDocumentsProvider.METHOD_SET_AVAILABLE,
            extras,
        ).toSyntheticDocumentSnapshot()
        if (snapshot.available != available) failFixed(SYNTHETIC_DOCUMENT_STATE_INVALID)
    }

    private fun syntheticDocumentSnapshot(context: Context): SyntheticDocumentSnapshot =
        callSyntheticDocumentProvider(
            context,
            WallpaperTestDocumentsProvider.METHOD_SNAPSHOT,
        ).toSyntheticDocumentSnapshot()

    private fun callSyntheticDocumentProvider(
        context: Context,
        method: String,
        extras: Bundle? = null,
    ): Bundle = try {
        context.contentResolver.call(SYNTHETIC_CONTROL_URI, method, null, extras)
            ?: failFixed(SYNTHETIC_DOCUMENT_STATE_INVALID)
    } catch (fixed: FixedSmokeFailure) {
        throw fixed
    } catch (_: Throwable) {
        failFixed(SYNTHETIC_DOCUMENT_STATE_INVALID)
    }

    private fun Bundle.toSyntheticDocumentSnapshot(): SyntheticDocumentSnapshot =
        SyntheticDocumentSnapshot(
            available = getBoolean(WallpaperTestDocumentsProvider.KEY_AVAILABLE, false),
            openAttempts = getInt(WallpaperTestDocumentsProvider.KEY_OPEN_ATTEMPTS, -1),
            successfulOpens = getInt(
                WallpaperTestDocumentsProvider.KEY_SUCCESSFUL_OPENS,
                -1,
            ),
            lastDocumentId = getString(WallpaperTestDocumentsProvider.KEY_LAST_DOCUMENT_ID),
        ).also { snapshot ->
            if (
                snapshot.openAttempts < 0 ||
                snapshot.successfulOpens < 0 ||
                snapshot.successfulOpens > snapshot.openAttempts
            ) {
                failFixed(SYNTHETIC_DOCUMENT_STATE_INVALID)
            }
        }

    private fun awaitStateStorageReady(application: AuroraSmsApplication) {
        val status = runBlocking {
            withTimeoutOrNull(STATE_TIMEOUT_MILLIS) {
                application.container.stateStorageStatus.first { current ->
                    current != StateStorageStatus.Opening
                }
            }
        }
        if (status != StateStorageStatus.Ready) failFixed(STATE_STORAGE_NOT_READY)
    }

    private fun openGlobalWallpaperEditor(automation: UiAutomation) {
        clickAppTag(automation, INBOX_MORE_ACTION_TEST_TAG)
        clickAppTag(automation, CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG)
        waitForAppTag(automation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
        clickAppTag(automation, SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
        waitForAppTag(automation, SCOPED_WALLPAPER_DIALOG_TEST_TAG).recycleSafely()
        waitForEnabledAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG).recycleSafely()
    }

    private fun chooseExactSyntheticDocument(
        automation: UiAutomation,
        context: Context,
    ) {
        var unexpectedFailure = SAF_SELECTION_PICKER_CLICK_UNEXPECTED
        try {
            clickAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG)
            unexpectedFailure = SAF_SELECTION_DOCUMENTS_UI_FOCUS_UNEXPECTED
            waitForPackage(automation, DOCUMENTS_UI_PACKAGE)
            unexpectedFailure = SAF_SELECTION_ROOTS_NAVIGATION_UNEXPECTED
            openDocumentsUiRoots(automation)
            unexpectedFailure = SAF_SELECTION_ROOT_CLICK_UNEXPECTED
            clickExactDocumentsUiRoot(automation)
            unexpectedFailure = SAF_SELECTION_DOCUMENT_CLICK_UNEXPECTED
            waitForExactDocumentsUiTitle(
                automation = automation,
                expectedTitle = WallpaperTestDocumentsProvider.IMAGE_DISPLAY_NAME,
            ).useNode { title -> tapExactNodeCenter(automation, title) }
            unexpectedFailure = SAF_SELECTION_APP_RETURN_UNEXPECTED
            waitForPackage(automation, TARGET_PACKAGE)
            waitForEnabledAppTag(automation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
            waitForEnabledAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG).recycleSafely()
            waitForAppTagToDisappear(automation, SCOPED_WALLPAPER_LOADING_TEST_TAG)
            waitForAppTagToDisappear(automation, SCOPED_WALLPAPER_ERROR_TEST_TAG)
            waitForAppTag(automation, SCOPED_WALLPAPER_PREVIEW_TEST_TAG).recycleSafely()
            unexpectedFailure = SAF_SELECTION_PROVIDER_EVIDENCE_UNEXPECTED
            val snapshot = syntheticDocumentSnapshot(context)
            if (
                snapshot.openAttempts < 1 ||
                snapshot.successfulOpens < 1 ||
                snapshot.lastDocumentId != WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID
            ) {
                failFixed(SYNTHETIC_DOCUMENT_WAS_NOT_OPENED)
            }
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(unexpectedFailure)
        }
    }

    private fun openDocumentsUiRoots(automation: UiAutomation) {
        var unexpectedFailure = DOCUMENTS_UI_TOOLBAR_LOOKUP_UNEXPECTED
        try {
            val toolbar = waitForDocumentsUiResource(automation, DOCUMENTS_UI_TOOLBAR_RESOURCE)
            unexpectedFailure = DOCUMENTS_UI_NAVIGATION_CHILD_COUNT_UNEXPECTED
            val navigation = toolbar.useNode { node ->
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                val childCount = node.childCount
                for (index in 0 until childCount) {
                    unexpectedFailure = DOCUMENTS_UI_NAVIGATION_CHILD_LOOKUP_UNEXPECTED
                    node.getChild(index)?.let { child ->
                        unexpectedFailure = DOCUMENTS_UI_NAVIGATION_METADATA_UNEXPECTED
                        val exact = child.packageName?.toString() == DOCUMENTS_UI_PACKAGE &&
                            child.windowId == node.windowId &&
                            child.className?.toString() == DOCUMENTS_UI_NAVIGATION_CLASS &&
                            child.isVisibleToUser &&
                            child.isEnabled &&
                            child.isClickable
                        unexpectedFailure = DOCUMENTS_UI_NAVIGATION_COLLECTION_UNEXPECTED
                        if (exact) candidates += child else child.recycleSafely()
                    }
                }
                unexpectedFailure = DOCUMENTS_UI_NAVIGATION_CARDINALITY_UNEXPECTED
                if (candidates.size == 1) candidates[0] else {
                    candidates.forEach { candidate -> candidate.recycleSafely() }
                    failFixed(DOCUMENTS_UI_NAVIGATION_UNEXPECTED)
                }
            }
            unexpectedFailure = DOCUMENTS_UI_NAVIGATION_CLICK_UNEXPECTED
            val clicked = navigation.useNode { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) failFixed(DOCUMENTS_UI_NAVIGATION_FAILED)
            unexpectedFailure = DOCUMENTS_UI_ROOTS_LIST_LOOKUP_UNEXPECTED
            waitForDocumentsUiResource(automation, DOCUMENTS_UI_ROOTS_LIST_RESOURCE).recycleSafely()
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(unexpectedFailure)
        }
    }

    private fun clickExactDocumentsUiRoot(automation: UiAutomation) {
        repeat(MAXIMUM_DOCUMENTS_UI_ROOT_SCROLLS + 1) { viewport ->
            val title = waitForExactDocumentsUiTitleOrNull(
                automation = automation,
                expectedTitle = WallpaperTestDocumentsProvider.ROOT_TITLE,
                timeoutMillis = DOCUMENTS_UI_ROOT_VIEWPORT_TIMEOUT_MILLIS,
            )
            if (title != null) {
                title.useNode { node -> tapExactNodeCenter(automation, node) }
                return
            }
            if (viewport == MAXIMUM_DOCUMENTS_UI_ROOT_SCROLLS) {
                failFixed(EXACT_SYNTHETIC_DOCUMENTS_UI_TITLE_REQUIRED)
            }
            val scrolled = waitForDocumentsUiResource(
                automation,
                DOCUMENTS_UI_ROOTS_LIST_RESOURCE,
            ).useNode { roots ->
                roots.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (!scrolled) failFixed(DOCUMENTS_UI_ROOT_SCROLL_FAILED)
            SystemClock.sleep(DOCUMENTS_UI_ROOT_SCROLL_SETTLE_MILLIS)
        }
        failFixed(EXACT_SYNTHETIC_DOCUMENTS_UI_TITLE_REQUIRED)
    }

    private fun waitForExactDocumentsUiTitle(
        automation: UiAutomation,
        expectedTitle: String,
    ): AccessibilityNodeInfo = waitForExactDocumentsUiTitleOrNull(
        automation = automation,
        expectedTitle = expectedTitle,
        timeoutMillis = WAIT_TIMEOUT_MILLIS,
    ) ?: failFixed(EXACT_SYNTHETIC_DOCUMENTS_UI_TITLE_REQUIRED)

    private fun waitForExactDocumentsUiTitleOrNull(
        automation: UiAutomation,
        expectedTitle: String,
        timeoutMillis: Long,
    ): AccessibilityNodeInfo? {
        val timeoutAt = SystemClock.uptimeMillis() + timeoutMillis
        var stableBounds: Rect? = null
        var stableSince = 0L
        do {
            findExactDocumentsUiTitle(automation, expectedTitle)?.let { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                val now = SystemClock.uptimeMillis()
                if (bounds.width() > 0 && bounds.height() > 0 && bounds == stableBounds) {
                    if (now - stableSince >= DOCUMENTS_UI_NODE_STABLE_MILLIS) return node
                } else {
                    stableBounds = bounds
                    stableSince = now
                }
                node.recycleSafely()
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        return null
    }

    private fun findExactDocumentsUiTitle(
        automation: UiAutomation,
        expectedTitle: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != DOCUMENTS_UI_PACKAGE) return null
            val activeWindowId = root.windowId
            val matches = root.findAccessibilityNodeInfosByText(expectedTitle).orEmpty()
            val accepted = mutableListOf<AccessibilityNodeInfo>()
            matches.forEach { node ->
                val exact = node.packageName?.toString() == DOCUMENTS_UI_PACKAGE &&
                    node.windowId == activeWindowId &&
                    node.viewIdResourceName == ANDROID_TITLE_RESOURCE &&
                    node.className?.toString() == DOCUMENTS_UI_TITLE_CLASS &&
                    node.text?.toString() == expectedTitle &&
                    node.isVisibleToUser &&
                    node.isEnabled
                if (exact) accepted += node else node.recycleSafely()
            }
            if (accepted.size == 1) accepted[0] else {
                accepted.forEach { node -> node.recycleSafely() }
                null
            }
        } finally {
            root.recycleSafely()
        }
    }

    private fun tapExactNodeCenter(
        automation: UiAutomation,
        node: AccessibilityNodeInfo,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (
            !node.isVisibleToUser ||
            !node.isEnabled ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            failFixed(EXACT_SYNTHETIC_DOCUMENTS_UI_TITLE_REQUIRED)
        }
        executeFixedInputCommand(
            automation,
            "input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}",
        )
    }

    private fun waitForDocumentsUiResource(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findDocumentsUiResource(automation, resource)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(DOCUMENTS_UI_RESOURCE_REQUIRED)
    }

    private fun findDocumentsUiResource(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != DOCUMENTS_UI_PACKAGE) return null
            val matches = root.findAccessibilityNodeInfosByViewId(resource).orEmpty()
            val accepted = matches.filter { node ->
                node.packageName?.toString() == DOCUMENTS_UI_PACKAGE &&
                    node.windowId == root.windowId &&
                    node.isVisibleToUser &&
                    node.isEnabled
            }
            matches.filterNot(accepted::contains).forEach { node -> node.recycleSafely() }
            if (accepted.size == 1) accepted.single() else {
                accepted.forEach { node -> node.recycleSafely() }
                null
            }
        } finally {
            root.recycleSafely()
        }
    }

    private fun clickAppTag(
        automation: UiAutomation,
        tag: String,
    ) {
        val clicked = waitForAppTag(automation, tag).useNode { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!clicked) failFixed(APP_TAG_CLICK_FAILED)
    }

    private fun isAppTagEnabled(
        automation: UiAutomation,
        tag: String,
    ): Boolean = waitForAppTag(automation, tag).useNode { node -> node.isEnabled }

    private fun scrollToAndClickAppTag(
        automation: UiAutomation,
        tag: String,
    ) {
        repeat(MAXIMUM_APP_DIALOG_SCROLLS) {
            val clicked = findAppTagInActiveWindow(automation, tag)?.useNode { node ->
                node.isVisibleToUser &&
                    node.isEnabled &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } == true
            if (clicked) return
            if (!swipeWallpaperDialogUp(automation)) failFixed(APP_DIALOG_SWIPE_FAILED)
            SystemClock.sleep(APP_DIALOG_SCROLL_SETTLE_MILLIS)
        }
        failFixed(APP_TAG_CLICK_FAILED)
    }

    private fun scrollToAppTag(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo {
        repeat(MAXIMUM_APP_DIALOG_SCROLLS) {
            findAppTagInActiveWindow(automation, tag)?.let { node ->
                if (node.isVisibleToUser && node.isEnabled) return node
                node.recycleSafely()
            }
            if (!swipeWallpaperDialogUp(automation)) failFixed(APP_DIALOG_SWIPE_FAILED)
            SystemClock.sleep(APP_DIALOG_SCROLL_SETTLE_MILLIS)
        }
        failFixed(APP_TAG_NOT_AVAILABLE)
    }

    private fun swipeWallpaperDialogUp(automation: UiAutomation): Boolean {
        val dialogBounds = waitForAppTag(
            automation,
            SCOPED_WALLPAPER_DIALOG_TEST_TAG,
        ).useNode { node -> Rect().also(node::getBoundsInScreen) }
        val previewBounds = waitForAppTag(
            automation,
            SCOPED_WALLPAPER_PREVIEW_TEST_TAG,
        ).useNode { node -> Rect().also(node::getBoundsInScreen) }
        if (
            dialogBounds.width() <= 0 ||
            dialogBounds.height() <= 0 ||
            previewBounds.width() <= 0 ||
            previewBounds.height() <= 0
        ) {
            return false
        }
        val x = previewBounds.centerX()
        val startY = previewBounds.bottom - (previewBounds.height() / 8)
        val endY = dialogBounds.top + (dialogBounds.height() / 10)
        if (startY <= endY) return false
        return try {
            executeFixedInputCommand(
                automation,
                "input touchscreen swipe $x $startY $x $endY $APP_DIALOG_SWIPE_DURATION_MILLIS",
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun executeFixedInputCommand(
        automation: UiAutomation,
        command: String,
    ) = executeFixedShellCommand(
        automation = automation,
        command = command,
        failureMessage = FIXED_INPUT_COMMAND_FAILED,
    )

    private fun executeFixedShellCommand(
        automation: UiAutomation,
        command: String,
        failureMessage: String,
    ) {
        try {
            val descriptor = automation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(SHELL_COMMAND_OUTPUT_BUFFER_BYTES)
                while (input.read(buffer) >= 0) {
                    // Drain fixed input output to await command completion.
                }
            }
        } catch (_: Throwable) {
            failFixed(failureMessage)
        }
    }

    private fun waitForEnabledAppTag(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findAppTagInActiveWindow(automation, tag)?.let { node ->
                try {
                    node.refresh()
                } catch (_: Throwable) {
                    // The fresh active-window lookup remains authoritative for this poll.
                }
                if (node.isEnabled) return node
                node.recycleSafely()
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(ENABLED_APP_TAG_NOT_AVAILABLE)
    }

    private fun waitForAppTag(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findAppTagInActiveWindow(automation, tag)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(APP_TAG_NOT_AVAILABLE)
    }

    private fun waitForAppTagToDisappear(
        automation: UiAutomation,
        tag: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val found = findAppTagInActiveWindow(automation, tag)
            if (found == null) return
            found.recycleSafely()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(APP_TAG_REMAINED_AVAILABLE)
    }

    private fun findAppTagInActiveWindow(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != TARGET_PACKAGE) {
            root.recycleSafely()
            return null
        }
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val matches = node.packageName?.toString() == TARGET_PACKAGE &&
                node.viewIdResourceName == tag
            if (matches) {
                pending.recycleAll()
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
            node.recycleSafely()
        }
        return null
    }

    private fun waitForPackage(
        automation: UiAutomation,
        packageName: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val matches = try {
                    root.packageName?.toString() == packageName
                } finally {
                    root.recycleSafely()
                }
                if (matches) return
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EXPECTED_PACKAGE_NOT_FOCUSED)
    }

    private fun waitForStableInitialInbox(automation: UiAutomation): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var stableWindowId: Int? = null
        var stableSince = 0L
        do {
            val focusedWindowId = focusedTargetApplicationWindowId(automation)
            val inbox = if (focusedWindowId != null) {
                findAppTagInActiveWindow(automation, INBOX_SCREEN_TEST_TAG)
            } else {
                null
            }
            if (inbox != null && inbox.windowId == focusedWindowId) {
                val now = SystemClock.uptimeMillis()
                if (stableWindowId != focusedWindowId) {
                    stableWindowId = focusedWindowId
                    stableSince = now
                }
                if (now - stableSince >= INITIAL_FOCUS_STABLE_MILLIS) return inbox
            } else {
                stableWindowId = null
                stableSince = 0L
            }
            inbox?.recycleSafely()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(INBOX_NOT_AVAILABLE)
    }

    private fun focusedTargetApplicationWindowId(automation: UiAutomation): Int? {
        val windows = automation.windows
        try {
            windows.forEach { window ->
                if (
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive &&
                    window.isFocused
                ) {
                    val root = window.root ?: return@forEach
                    val targetPackage = try {
                        root.packageName?.toString() == TARGET_PACKAGE
                    } finally {
                        root.recycleSafely()
                    }
                    if (targetPackage) return window.id
                }
            }
            return null
        } finally {
            windows.forEach { window -> window.recycleSafely() }
        }
    }

    private fun bestEffortDismissFocusedDocumentsUi(automation: UiAutomation?) {
        if (automation == null) return
        try {
            repeat(MAXIMUM_DOCUMENTS_UI_DISMISS_ATTEMPTS) {
                val root = automation.rootInActiveWindow ?: return
                val documentsUiFocused = try {
                    root.packageName?.toString() == DOCUMENTS_UI_PACKAGE
                } finally {
                    root.recycleSafely()
                }
                if (!documentsUiFocused) return
                if (!automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) return
                SystemClock.sleep(POLL_INTERVAL_MILLIS)
            }
        } catch (_: Throwable) {
            // Inspect only the focused package and never replace the primary result.
        }
    }

    private fun bestEffortDismissFocusedTransientSystemUi(automation: UiAutomation?) {
        if (automation == null) return
        try {
            repeat(MAXIMUM_SYSTEM_UI_DISMISS_ATTEMPTS) {
                val root = automation.rootInActiveWindow ?: return
                val systemUiFocused = try {
                    root.packageName?.toString() == SYSTEM_UI_PACKAGE
                } finally {
                    root.recycleSafely()
                }
                if (!systemUiFocused) return
                if (!automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) return
                SystemClock.sleep(POLL_INTERVAL_MILLIS)
            }
        } catch (_: Throwable) {
            // Inspect only the focused package and never replace the primary result.
        }
    }

    private fun readReadyAssignment(controller: WallpaperController): AppWallpaperAssignment? =
        runBlocking {
            withTimeout(STATE_TIMEOUT_MILLIS) {
                controller.observe(GLOBAL_THREAD_SCOPE)
                    .first { observation -> observation.isReadyFor(GLOBAL_THREAD_SCOPE) }
                    .assignmentFor(GLOBAL_THREAD_SCOPE)
            }
        }

    private fun awaitAssignment(
        controller: WallpaperController,
        assigned: Boolean,
    ): AppWallpaperAssignment? = runBlocking {
        withTimeout(STATE_TIMEOUT_MILLIS) {
            controller.observe(GLOBAL_THREAD_SCOPE)
                .first { observation ->
                    observation.isReadyFor(GLOBAL_THREAD_SCOPE) &&
                        ((observation.assignmentFor(GLOBAL_THREAD_SCOPE) != null) == assigned)
                }
                .assignmentFor(GLOBAL_THREAD_SCOPE)
        }
    }

    private fun assertTransientSelection(
        automation: UiAutomation,
        controller: WallpaperController,
        context: Context,
        baseline: SelectionBaseline,
    ) {
        waitForEnabledAppTag(automation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
        waitForAppTag(automation, SCOPED_WALLPAPER_PREVIEW_TEST_TAG).recycleSafely()
        assertSelectionBaselineUnchanged(controller, context, baseline)
        val snapshot = syntheticDocumentSnapshot(context)
        if (
            snapshot.openAttempts < 1 ||
            snapshot.successfulOpens < 1 ||
            snapshot.lastDocumentId != WallpaperTestDocumentsProvider.IMAGE_DOCUMENT_ID
        ) {
            failFixed(SYNTHETIC_DOCUMENT_WAS_NOT_OPENED)
        }
    }

    private fun assertSelectionBaselineUnchanged(
        controller: WallpaperController,
        context: Context,
        baseline: SelectionBaseline,
    ) {
        if (readReadyAssignment(controller) != baseline.assignment) {
            failFixed(SELECTION_ASSIGNMENT_CHANGED_BEFORE_APPLY)
        }
        if (managedFileLedger(context) != baseline.managedFiles) {
            failFixed(SELECTION_MANAGED_FILES_CHANGED_BEFORE_APPLY)
        }
        assertPersistedGrantsUnchanged(context, baseline)
        if (readAppearanceRevisionSequence(context) != baseline.revisionSequence) {
            failFixed(SELECTION_REVISION_CHANGED_BEFORE_APPLY)
        }
    }

    private fun assertSelectionBaselineRestored(
        controller: WallpaperController,
        context: Context,
        baseline: SelectionBaseline,
    ) {
        if (readReadyAssignment(controller) != baseline.assignment) {
            failFixed(SELECTION_ASSIGNMENT_NOT_RESTORED)
        }
        if (managedFileLedger(context) != baseline.managedFiles) {
            failFixed(SELECTION_MANAGED_FILES_NOT_RESTORED)
        }
        assertPersistedGrantsUnchanged(context, baseline)
    }

    private fun assertStaleWinnerUnchanged(
        controller: WallpaperController,
        context: Context,
        winner: SelectionBaseline,
    ) {
        if (readReadyAssignment(controller) != winner.assignment) {
            failFixed(STALE_APPLY_REPLACED_WINNER)
        }
        if (managedFileLedger(context) != winner.managedFiles) {
            failFixed(STALE_APPLY_CHANGED_MANAGED_FILES)
        }
        if (readAppearanceRevisionSequence(context) != winner.revisionSequence) {
            failFixed(STALE_APPLY_CHANGED_REVISION)
        }
        assertPersistedGrantsUnchanged(context, winner)
    }

    private fun assertPersistedGrantsUnchanged(
        context: Context,
        baseline: SelectionBaseline,
    ) {
        if (persistedGrantSnapshot(context.contentResolver) != baseline.persistedGrants) {
            failFixed(PERSISTED_GRANTS_CHANGED)
        }
    }

    private fun assertExactlyOneNewManagedFinal(
        context: Context,
        baseline: SelectionBaseline,
        assignment: AppWallpaperAssignment,
    ) {
        val current = managedFileLedger(context)
        if (!current.containsAll(baseline.managedFiles)) failFixed(BASELINE_MANAGED_FILE_REMOVED)
        val added = current - baseline.managedFiles
        if (added.size != 1) failFixed(EXACTLY_ONE_NEW_MANAGED_FILE_REQUIRED)
        val classification = classifyManagedWallpaperFileName(added.single().name)
        if (
            classification !is ManagedWallpaperFileClassification.Final ||
            classification.mediaId != assignment.mediaId ||
            !assignment.mediaId.startsWith(WALLPAPER_MEDIA_ID_PREFIX)
        ) {
            failFixed(NEW_MANAGED_FILE_NOT_CONFORMING)
        }
    }

    private fun assertManagedWallpaperLoads(
        controller: WallpaperController,
        assignment: AppWallpaperAssignment,
    ) {
        val loaded = runBlocking {
            withTimeout(STATE_TIMEOUT_MILLIS) {
                controller.loadFirstAvailable(listOf(assignment))
            }
        } ?: failFixed(APPLIED_MANAGED_WALLPAPER_UNAVAILABLE)
        try {
            if (loaded.assignment != assignment) failFixed(APPLIED_MANAGED_WALLPAPER_UNAVAILABLE)
            val bitmap = loaded.image.asAndroidBitmap()
            if (
                bitmap.config != Bitmap.Config.ARGB_8888 ||
                bitmap.width != SYNTHETIC_IMAGE_WIDTH ||
                bitmap.height != SYNTHETIC_IMAGE_HEIGHT
            ) {
                failFixed(APPLIED_MANAGED_WALLPAPER_INVALID)
            }
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            if (
                !colorChannelMatches(pixel shr 16, SYNTHETIC_IMAGE_COLOR_ARGB shr 16) ||
                !colorChannelMatches(pixel shr 8, SYNTHETIC_IMAGE_COLOR_ARGB shr 8) ||
                !colorChannelMatches(pixel, SYNTHETIC_IMAGE_COLOR_ARGB)
            ) {
                failFixed(APPLIED_MANAGED_WALLPAPER_INVALID)
            }
        } finally {
            loaded.release()
        }
    }

    private fun assertManagedWallpaperAvailable(
        controller: WallpaperController,
        assignment: AppWallpaperAssignment,
    ) {
        val loaded = runBlocking {
            withTimeout(STATE_TIMEOUT_MILLIS) {
                controller.loadFirstAvailable(listOf(assignment))
            }
        } ?: failFixed(CONCURRENT_MANAGED_WALLPAPER_UNAVAILABLE)
        try {
            if (loaded.assignment != assignment) {
                failFixed(CONCURRENT_MANAGED_WALLPAPER_UNAVAILABLE)
            }
        } finally {
            loaded.release()
        }
    }

    private fun colorChannelMatches(actual: Int, expected: Int): Boolean =
        kotlin.math.abs((actual and 0xff) - (expected and 0xff)) <=
            SYNTHETIC_COLOR_TOLERANCE

    private fun bestEffortResetTestAssignment(
        controller: WallpaperController,
        expected: AppWallpaperAssignment,
    ) {
        try {
            val current = readReadyAssignment(controller)
            if (current != expected) return
            runBlocking {
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    controller.reset(GLOBAL_THREAD_SCOPE, expected.revision)
                }
            }
            awaitAssignment(controller, assigned = false)
        } catch (_: Throwable) {
            // The selection test starts empty, so only its own possible commit is targeted.
        }
    }

    private fun recoverControlledConcurrentAssignment(
        controller: WallpaperController,
        context: Context,
        baseline: SelectionBaseline,
    ): AppWallpaperAssignment? {
        val current = readReadyAssignment(controller) ?: return null
        if (
            current.scope != GLOBAL_THREAD_SCOPE ||
            current.revision != baseline.revisionSequence + 1L ||
            current.dimPermill != STALE_APPLY_CONCURRENT_DIM_PERMILL ||
            current.focalXPermill != STALE_APPLY_CONCURRENT_FOCAL_X_PERMILL ||
            current.focalYPermill != STALE_APPLY_CONCURRENT_FOCAL_Y_PERMILL
        ) {
            return null
        }
        val ledger = managedFileLedger(context)
        if (!ledger.containsAll(baseline.managedFiles)) return null
        val added = ledger - baseline.managedFiles
        if (added.size != 1) return null
        val classification = classifyManagedWallpaperFileName(added.single().name)
        return current.takeIf {
            classification is ManagedWallpaperFileClassification.Final &&
                classification.mediaId == current.mediaId
        }
    }

    private fun assertBaselineUnchanged(
        controller: WallpaperController,
        context: Context,
        baseline: CancellationBaseline,
    ) {
        if (readReadyAssignment(controller) != baseline.assignment) {
            failFixed(ASSIGNMENT_CHANGED_AFTER_CANCEL)
        }
        if (managedFileNames(context) != baseline.managedFiles) {
            failFixed(MANAGED_FILES_CHANGED_AFTER_CANCEL)
        }
        if (persistedGrantSnapshot(context.contentResolver) != baseline.persistedGrants) {
            failFixed(PERSISTED_GRANTS_CHANGED)
        }
    }

    private fun managedFileNames(context: Context): Set<String> {
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        if (!directory.exists()) return emptySet()
        if (!directory.isDirectory) failFixed(MANAGED_DIRECTORY_INVALID)
        return directory.list()?.toSet() ?: failFixed(MANAGED_DIRECTORY_UNAVAILABLE)
    }

    private fun managedFileLedger(context: Context): Set<ManagedFileLedgerEntry> {
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        if (!directory.exists()) return emptySet()
        if (!directory.isDirectory) failFixed(MANAGED_DIRECTORY_INVALID)
        val entries = directory.listFiles() ?: failFixed(MANAGED_DIRECTORY_UNAVAILABLE)
        return entries.mapTo(mutableSetOf()) { file -> managedFileLedgerEntry(file) }
    }

    private fun managedFileLedgerEntry(file: File): ManagedFileLedgerEntry {
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (_: Throwable) {
            failFixed(MANAGED_FILE_LEDGER_UNAVAILABLE)
        }
        val input = FileInputStream(descriptor)
        try {
            val before = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) {
                failFixed(MANAGED_FILE_LEDGER_UNAVAILABLE)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(MANAGED_FILE_DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            val after = Os.fstat(descriptor)
            if (
                before.st_dev != after.st_dev ||
                before.st_ino != after.st_ino ||
                before.st_nlink != after.st_nlink ||
                before.st_size != after.st_size ||
                before.st_mtime != after.st_mtime
            ) {
                failFixed(MANAGED_FILE_LEDGER_UNAVAILABLE)
            }
            return ManagedFileLedgerEntry(
                name = file.name,
                device = after.st_dev,
                inode = after.st_ino,
                linkCount = after.st_nlink,
                size = after.st_size,
                modifiedSeconds = after.st_mtime,
                sha256 = digest.digest().joinToString(separator = "") { byte ->
                    "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff)
                },
            )
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(MANAGED_FILE_LEDGER_UNAVAILABLE)
        } finally {
            try {
                input.close()
            } catch (_: Throwable) {
                // The fixed ledger result remains authoritative.
            }
        }
    }

    private fun readAppearanceRevisionSequence(context: Context): Long {
        val database = try {
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(StateDatabaseFactory.DATABASE_NAME).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        } catch (_: Throwable) {
            failFixed(REVISION_SEQUENCE_UNAVAILABLE)
        }
        try {
            database.rawQuery(
                "SELECT last_allocated_revision " +
                    "FROM appearance_override_revision_sequence WHERE singleton_id = 1",
                emptyArray(),
            ).use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst() || cursor.columnCount != 1) {
                    failFixed(REVISION_SEQUENCE_UNAVAILABLE)
                }
                return cursor.getLong(0).takeIf { it >= 0L }
                    ?: failFixed(REVISION_SEQUENCE_UNAVAILABLE)
            }
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(REVISION_SEQUENCE_UNAVAILABLE)
        } finally {
            database.close()
        }
    }

    private fun persistedGrantSnapshot(resolver: ContentResolver): Set<PersistedGrantIdentity> =
        resolver.persistedUriPermissions.mapTo(mutableSetOf()) { permission ->
            PersistedGrantIdentity(
                uri = permission.uri.toString(),
                read = permission.isReadPermission,
                write = permission.isWritePermission,
                persistedTime = permission.persistedTime,
            )
        }

    private inline fun <T> AccessibilityNodeInfo.useNode(
        block: (AccessibilityNodeInfo) -> T,
    ): T = try {
        block(this)
    } finally {
        recycleSafely()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleSafely() {
        try {
            recycle()
        } catch (_: RuntimeException) {
            // API 26 can report a stale/recycled accessibility snapshot during window changes.
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.recycleSafely() {
        try {
            recycle()
        } catch (_: RuntimeException) {
            // Recycling is cleanup only; a concurrently replaced API 26 window is already stale.
        }
    }

    private fun ArrayDeque<AccessibilityNodeInfo>.recycleAll() {
        while (isNotEmpty()) removeFirst().recycleSafely()
    }

    private fun failFixed(message: String): Nothing = throw FixedSmokeFailure(message)

    private class FixedSmokeFailure(message: String) : AssertionError(message)

    private class CancellationBaseline(
        val assignment: AppWallpaperAssignment?,
        val managedFiles: Set<String>,
        val persistedGrants: Set<PersistedGrantIdentity>,
    ) {
        override fun toString(): String = "CancellationBaseline(REDACTED)"
    }

    private class SelectionBaseline(
        val assignment: AppWallpaperAssignment?,
        val managedFiles: Set<ManagedFileLedgerEntry>,
        val persistedGrants: Set<PersistedGrantIdentity>,
        val revisionSequence: Long,
    ) {
        override fun toString(): String = "SelectionBaseline(REDACTED)"
    }

    private data class SyntheticDocumentSnapshot(
        val available: Boolean,
        val openAttempts: Int,
        val successfulOpens: Int,
        val lastDocumentId: String?,
    ) {
        override fun toString(): String = "SyntheticDocumentSnapshot(REDACTED)"
    }

    private data class ManagedFileLedgerEntry(
        val name: String,
        val device: Long,
        val inode: Long,
        val linkCount: Long,
        val size: Long,
        val modifiedSeconds: Long,
        val sha256: String,
    ) {
        override fun toString(): String = "ManagedFileLedgerEntry(REDACTED)"
    }

    private data class ActiveNotificationIdentity(
        val key: String,
        val packageName: String,
        val id: Int,
        val tag: String?,
        val uid: Int,
        val postTime: Long,
    ) {
        override fun toString(): String = "ActiveNotificationIdentity(REDACTED)"
    }

    private data class NotificationChannelSnapshot(
        val id: String,
        val name: String?,
        val description: String?,
        val importance: Int,
        val lockscreenVisibility: Int,
        val bypassDnd: Boolean,
        val showBadge: Boolean,
        val showLights: Boolean,
        val lightColor: Int,
        val vibrate: Boolean,
        val vibrationPattern: List<Long>?,
        val sound: String?,
        val group: String?,
        val audioUsage: Int?,
        val audioContentType: Int?,
        val audioFlags: Int?,
    ) {
        override fun toString(): String = "NotificationChannelSnapshot(REDACTED)"
    }

    private class PersistedGrantIdentity(
        private val uri: String,
        private val read: Boolean,
        private val write: Boolean,
        private val persistedTime: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            other is PersistedGrantIdentity &&
                uri == other.uri &&
                read == other.read &&
                write == other.write &&
                persistedTime == other.persistedTime

        override fun hashCode(): Int {
            var result = uri.hashCode()
            result = 31 * result + read.hashCode()
            result = 31 * result + write.hashCode()
            result = 31 * result + persistedTime.hashCode()
            return result
        }

        override fun toString(): String = "PersistedGrantIdentity(REDACTED)"
    }

    private companion object {
        const val GATE_ARGUMENT = "auroraEmulatorWallpaperSafCancellation"
        const val SELECTION_GATE_ARGUMENT = "auroraEmulatorWallpaperSafSelection"
        const val STALE_APPLY_GATE_ARGUMENT = "auroraEmulatorWallpaperSafStaleApply"
        const val NOTIFICATION_PENDING_INTENT_GATE_ARGUMENT =
            "auroraEmulatorWallpaperSafNotificationPendingIntent"
        const val NOTIFICATION_CLEANUP_GATE_ARGUMENT =
            "auroraEmulatorWallpaperSafNotificationCleanup"
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val TEST_PACKAGE = "org.aurorasms.app.test"
        const val DOCUMENTS_UI_PACKAGE = "com.android.documentsui"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SYSTEM_UI_NOTIFICATION_PANEL_RESOURCE =
            "$SYSTEM_UI_PACKAGE:id/notification_panel"
        const val SYSTEM_UI_NOTIFICATION_STACK_RESOURCE =
            "$SYSTEM_UI_PACKAGE:id/notification_stack_scroller"
        const val SYSTEM_UI_NOTIFICATION_EXPANDED_RESOURCE =
            "$SYSTEM_UI_PACKAGE:id/expanded"
        const val SYSTEM_UI_NOTIFICATION_MAIN_COLUMN_RESOURCE =
            "android:id/notification_main_column"
        const val SYSTEM_UI_NOTIFICATION_ACTION_MARGIN_RESOURCE =
            "android:id/notification_action_list_margin_target"
        const val SYSTEM_UI_NOTIFICATION_EVENT_CONTENT_RESOURCE =
            "android:id/status_bar_latest_event_content"
        const val SYSTEM_UI_NOTIFICATION_ROW_CLASS = "android.widget.FrameLayout"
        const val ANDROID_NOTIFICATION_TEXT_RESOURCE = "android:id/text"
        const val ANDROID_NOTIFICATION_BIG_TEXT_RESOURCE = "android:id/big_text"
        const val IMAGE_MIME_TYPE = "image/*"
        const val DOCUMENTS_UI_TOOLBAR_RESOURCE = "$DOCUMENTS_UI_PACKAGE:id/toolbar"
        const val DOCUMENTS_UI_ROOTS_LIST_RESOURCE = "$DOCUMENTS_UI_PACKAGE:id/roots_list"
        const val ANDROID_TITLE_RESOURCE = "android:id/title"
        const val DOCUMENTS_UI_NAVIGATION_CLASS = "android.widget.ImageButton"
        const val DOCUMENTS_UI_TITLE_CLASS = "android.widget.TextView"
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 75L
        const val DOCUMENTS_UI_NODE_STABLE_MILLIS = 350L
        const val SYSTEM_UI_NODE_STABLE_MILLIS = 350L
        const val NOTIFICATION_SHADE_SWIPE_START_Y = 1
        const val NOTIFICATION_SHADE_SWIPE_END_PERCENT = 75
        const val NOTIFICATION_SHADE_SWIPE_DURATION_MILLIS = 500
        const val DOCUMENTS_UI_ROOT_VIEWPORT_TIMEOUT_MILLIS = 2_000L
        const val DOCUMENTS_UI_ROOT_SCROLL_SETTLE_MILLIS = 350L
        const val INITIAL_FOCUS_STABLE_MILLIS = 750L
        const val MAXIMUM_DOCUMENTS_UI_DISMISS_ATTEMPTS = 3
        const val MAXIMUM_SYSTEM_UI_DISMISS_ATTEMPTS = 3
        const val MAXIMUM_SYSTEM_UI_NOTIFICATION_ANCESTOR_DEPTH = 16
        const val MAXIMUM_SYSTEM_UI_NOTIFICATION_TREE_NODES = 512
        const val MAXIMUM_DOCUMENTS_UI_ROOT_SCROLLS = 6
        const val MAXIMUM_TRANSIENT_URI_UTF8_BYTES = 4_096
        const val MAXIMUM_APP_DIALOG_SCROLLS = 4
        const val APP_DIALOG_SWIPE_DURATION_MILLIS = 300L
        const val APP_DIALOG_SCROLL_SETTLE_MILLIS = 250L
        const val SHELL_COMMAND_OUTPUT_BUFFER_BYTES = 128
        const val MANAGED_FILE_DIGEST_BUFFER_BYTES = 8 * 1_024
        const val SYNTHETIC_IMAGE_WIDTH = 40
        const val SYNTHETIC_IMAGE_HEIGHT = 20
        const val SYNTHETIC_IMAGE_COLOR_ARGB = 0xff2457d6.toInt()
        const val SYNTHETIC_COLOR_TOLERANCE = 8
        const val SELECTION_SENTINEL_STATUS_CODE = 42
        const val SELECTION_SENTINEL_KEY = "auroraSafSelectionResult"
        const val SELECTION_SENTINEL_VALUE = "pass"
        const val STALE_APPLY_SENTINEL_STATUS_CODE = 43
        const val STALE_APPLY_SENTINEL_KEY = "auroraSafStaleApplyResult"
        const val STALE_APPLY_SENTINEL_VALUE = "pass"
        const val NOTIFICATION_PENDING_INTENT_SENTINEL_STATUS_CODE = 44
        const val NOTIFICATION_PENDING_INTENT_SENTINEL_KEY =
            "auroraSafNotificationPendingIntentResult"
        const val NOTIFICATION_PENDING_INTENT_SENTINEL_VALUE = "pass"
        const val NOTIFICATION_CLEANUP_SENTINEL_STATUS_CODE = 45
        const val NOTIFICATION_CLEANUP_SENTINEL_KEY =
            "auroraSafNotificationCleanupResult"
        const val NOTIFICATION_CLEANUP_SENTINEL_VALUE = "pass"
        const val STALE_APPLY_SYNTHETIC_CONVERSATION_ID = 8_600_026L
        const val STALE_APPLY_CONCURRENT_DIM_PERMILL = 575
        const val STALE_APPLY_CONCURRENT_FOCAL_X_PERMILL = 250
        const val STALE_APPLY_CONCURRENT_FOCAL_Y_PERMILL = 750
        const val NOTIFICATION_PENDING_INTENT_CONVERSATION_ID = 8_600_027L
        const val NOTIFICATION_PENDING_INTENT_NOTIFICATION_ID = 8_600_027
        const val NOTIFICATION_PENDING_INTENT_MESSAGE_ID = 9_600_027L
        const val NOTIFICATION_PENDING_INTENT_TAG = "aurora-conversation:8600027"
        const val NOTIFICATION_PENDING_INTENT_SENDER = "AuroraSMS synthetic route sender"
        const val NOTIFICATION_PENDING_INTENT_PERSON_KEY = "aurora-synthetic-route-8600027"
        const val NOTIFICATION_PENDING_INTENT_BODY =
            "AuroraSMS synthetic notification route 8600027"

        val SYSTEM_UI_NOTIFICATION_BODY_RESOURCES = setOf(
            ANDROID_NOTIFICATION_TEXT_RESOURCE,
            ANDROID_NOTIFICATION_BIG_TEXT_RESOURCE,
        )

        val SYSTEM_UI_NOTIFICATION_ROW_RESOURCE_PATH = listOf(
            SYSTEM_UI_NOTIFICATION_MAIN_COLUMN_RESOURCE,
            SYSTEM_UI_NOTIFICATION_ACTION_MARGIN_RESOURCE,
            SYSTEM_UI_NOTIFICATION_EVENT_CONTENT_RESOURCE,
            SYSTEM_UI_NOTIFICATION_EXPANDED_RESOURCE,
            SYSTEM_UI_NOTIFICATION_STACK_RESOURCE,
        )

        val EXPECTED_NOTIFICATION_CHANNEL_IDS = setOf(
            NotificationChannels.MESSAGES,
            NotificationChannels.REPLY_FAILURES,
        )

        val SYNTHETIC_CONTROL_URI: android.net.Uri = android.net.Uri.parse(
            "content://org.aurorasms.app.wallpaper.testprovider/control",
        )

        val STALE_APPLY_CONCURRENT_SOURCE_URI: android.net.Uri = android.net.Uri.parse(
            "content://org.aurorasms.app.wallpaper.testprovider/valid.png",
        )

        val GLOBAL_THREAD_SCOPE: AppearanceScope.Screen =
            AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)

        const val GATE_REQUIRED = "emulator wallpaper SAF cancellation gate was not enabled"
        const val SELECTION_GATE_REQUIRED = "emulator wallpaper SAF selection gate was not enabled"
        const val STALE_APPLY_GATE_REQUIRED =
            "emulator wallpaper SAF stale-Apply gate was not enabled"
        const val NOTIFICATION_PENDING_INTENT_GATE_REQUIRED =
            "emulator wallpaper SAF notification PendingIntent gate was not enabled"
        const val NOTIFICATION_CLEANUP_GATE_REQUIRED =
            "emulator wallpaper SAF notification cleanup gate was not enabled"
        const val EMULATOR_REQUIRED = "wallpaper SAF smoke requires an emulator"
        const val API_26_REQUIRED = "wallpaper SAF cancellation requires API 26"
        const val API_26_SELECTION_REQUIRED = "wallpaper SAF selection requires API 26"
        const val API_26_STALE_APPLY_REQUIRED = "wallpaper SAF stale-Apply requires API 26"
        const val API_26_NOTIFICATION_PENDING_INTENT_REQUIRED =
            "wallpaper SAF notification PendingIntent requires API 26"
        const val APPLICATION_REQUIRED = "AuroraSMS application was not available"
        const val STATE_STORAGE_NOT_READY = "AuroraSMS state storage did not become ready"
        const val OPEN_DOCUMENT_ACTION_REQUIRED = "Photo Picker contract did not select ACTION_OPEN_DOCUMENT"
        const val IMAGE_ONLY_MIME_REQUIRED = "SAF fallback did not request image-only content"
        const val AOSP_DOCUMENTS_UI_REQUIRED = "SAF fallback did not resolve to AOSP DocumentsUI"
        const val REAL_ACTIVITY_REQUIRED = "real MainActivity was not launched"
        const val INBOX_ROUTE_REQUIRED = "SAF smoke did not start on Inbox"
        const val INBOX_NOT_AVAILABLE = "stable Inbox was not available for SAF smoke"
        const val APP_TAG_NOT_AVAILABLE = "required content-free app resource tag was not available"
        const val ENABLED_APP_TAG_NOT_AVAILABLE = "required app resource tag did not become enabled"
        const val APP_TAG_REMAINED_AVAILABLE = "dismissed app resource tag remained available"
        const val APP_TAG_CLICK_FAILED = "content-free app resource-tag click failed"
        const val EXPECTED_PACKAGE_NOT_FOCUSED = "expected fixed package did not receive focus"
        const val SAF_CANCEL_FAILED = "AOSP DocumentsUI Back cancellation failed"
        const val APPLY_ENABLED_AFTER_CANCEL = "Apply became enabled after SAF cancellation"
        const val ASSIGNMENT_CHANGED_AFTER_CANCEL = "global assignment changed after SAF cancellation"
        const val MANAGED_FILES_CHANGED_AFTER_CANCEL = "managed files changed after SAF cancellation"
        const val PERSISTED_GRANTS_CHANGED = "persisted URI grant identity changed"
        const val MANAGED_DIRECTORY_INVALID = "managed wallpaper directory was invalid"
        const val MANAGED_DIRECTORY_UNAVAILABLE = "managed wallpaper directory was unavailable"
        const val SYNTHETIC_DOCUMENTS_PROVIDER_REQUIRED =
            "synthetic SAF DocumentsProvider was not registered"
        const val SYNTHETIC_DOCUMENTS_PROVIDER_INVALID =
            "synthetic SAF DocumentsProvider contract was invalid"
        const val SYNTHETIC_DOCUMENT_URI_INVALID = "synthetic SAF document URI was invalid"
        const val SYNTHETIC_DOCUMENT_STATE_INVALID = "synthetic SAF document state was invalid"
        const val SYNTHETIC_DOCUMENT_WAS_NOT_OPENED =
            "production inspection did not open the exact synthetic SAF document"
        const val SAF_SELECTION_PICKER_CLICK_UNEXPECTED =
            "unexpected failure while opening the SAF selection contract"
        const val SAF_SELECTION_DOCUMENTS_UI_FOCUS_UNEXPECTED =
            "unexpected failure while awaiting AOSP DocumentsUI"
        const val SAF_SELECTION_ROOTS_NAVIGATION_UNEXPECTED =
            "unexpected failure while opening the exact synthetic SAF roots list"
        const val SAF_SELECTION_ROOT_CLICK_UNEXPECTED =
            "unexpected failure while selecting the exact synthetic SAF root"
        const val SAF_SELECTION_DOCUMENT_CLICK_UNEXPECTED =
            "unexpected failure while selecting the exact synthetic SAF document"
        const val SAF_SELECTION_APP_RETURN_UNEXPECTED =
            "unexpected failure while awaiting the returned SAF preview"
        const val SAF_SELECTION_PROVIDER_EVIDENCE_UNEXPECTED =
            "unexpected failure while verifying the exact synthetic SAF source"
        const val DOCUMENTS_UI_NAVIGATION_UNEXPECTED =
            "AOSP DocumentsUI navigation structure was unexpected"
        const val DOCUMENTS_UI_NAVIGATION_FAILED = "AOSP DocumentsUI roots navigation failed"
        const val DOCUMENTS_UI_TOOLBAR_LOOKUP_UNEXPECTED =
            "unexpected failure while locating the AOSP DocumentsUI toolbar"
        const val DOCUMENTS_UI_NAVIGATION_CHILD_COUNT_UNEXPECTED =
            "unexpected failure while reading the AOSP DocumentsUI toolbar structure"
        const val DOCUMENTS_UI_NAVIGATION_CHILD_LOOKUP_UNEXPECTED =
            "unexpected failure while reading an AOSP DocumentsUI toolbar child"
        const val DOCUMENTS_UI_NAVIGATION_METADATA_UNEXPECTED =
            "unexpected failure while validating the AOSP DocumentsUI roots control"
        const val DOCUMENTS_UI_NAVIGATION_COLLECTION_UNEXPECTED =
            "unexpected failure while collecting the AOSP DocumentsUI roots control"
        const val DOCUMENTS_UI_NAVIGATION_CARDINALITY_UNEXPECTED =
            "unexpected failure while checking the AOSP DocumentsUI roots control count"
        const val DOCUMENTS_UI_NAVIGATION_CLICK_UNEXPECTED =
            "unexpected failure while invoking the AOSP DocumentsUI roots control"
        const val DOCUMENTS_UI_ROOTS_LIST_LOOKUP_UNEXPECTED =
            "unexpected failure while awaiting the AOSP DocumentsUI roots list"
        const val DOCUMENTS_UI_RESOURCE_REQUIRED = "required AOSP DocumentsUI resource was unavailable"
        const val DOCUMENTS_UI_ROOT_SCROLL_FAILED =
            "AOSP DocumentsUI roots list could not advance to the exact synthetic root"
        const val EXACT_SYNTHETIC_DOCUMENTS_UI_TITLE_REQUIRED =
            "exact synthetic SAF root or document title was unavailable"
        const val FIXED_INPUT_COMMAND_FAILED = "fixed content-free input command failed"
        const val APP_DIALOG_SWIPE_FAILED = "content-free wallpaper dialog swipe failed"
        const val SELECTION_EMPTY_BASELINE_REQUIRED =
            "global wallpaper must be empty before the mutation smoke"
        const val SELECTION_SURVIVED_RECREATION =
            "transient SAF selection survived Activity recreation"
        const val SOURCE_LOSS_WAS_NOT_REVALIDATED =
            "Apply did not reopen and reject the unavailable SAF source"
        const val SUCCESSFUL_APPLY_DID_NOT_REOPEN_SOURCE =
            "successful Apply did not reopen the exact SAF source"
        const val STALE_APPLY_EMPTY_BASELINE_REQUIRED =
            "global wallpaper must be empty before the stale-Apply smoke"
        const val ROUTE_REPLACEMENT_REOPENED_SOURCE =
            "route replacement unexpectedly reopened the transient SAF source"
        const val ROUTE_REPLACEMENT_SELECTION_SURVIVED =
            "transient SAF selection survived route replacement"
        const val ROUTE_REPLACEMENT_BACK_FAILED =
            "replacement conversation route did not return to Inbox"
        const val CONCURRENT_ASSIGNMENT_APPLY_FAILED =
            "controlled concurrent wallpaper assignment could not be applied"
        const val CONCURRENT_ASSIGNMENT_REQUIRED =
            "controlled concurrent wallpaper assignment was unavailable"
        const val CONCURRENT_ASSIGNMENT_INVALID =
            "controlled concurrent wallpaper assignment was invalid"
        const val CONCURRENT_ASSIGNMENT_REVISION_UNEXPECTED =
            "controlled concurrent wallpaper consumed an unexpected revision"
        const val STALE_APPLY_ERROR_REQUIRED =
            "stale Apply did not surface the exact stale-assignment error"
        const val STALE_APPLY_DID_NOT_REOPEN_SOURCE =
            "stale Apply did not reopen the exact synthetic SAF source"
        const val STALE_APPLY_REPLACED_WINNER =
            "stale Apply replaced the newer authoritative assignment"
        const val STALE_APPLY_CHANGED_MANAGED_FILES =
            "stale Apply changed the newer assignment's managed-file ledger"
        const val STALE_APPLY_CHANGED_REVISION =
            "stale Apply consumed an additional appearance revision"
        const val CONCURRENT_MANAGED_WALLPAPER_UNAVAILABLE =
            "newer authoritative managed wallpaper became unavailable"
        const val STALE_APPLY_RESET_REVISION_UNEXPECTED =
            "reset after stale Apply consumed an unexpected revision"
        const val NOTIFICATION_MANAGER_UNAVAILABLE =
            "production notification manager was unavailable"
        const val NOTIFICATION_CHANNEL_BASELINE_UNAVAILABLE =
            "production notification channel baseline was unavailable"
        const val NOTIFICATION_CHANNEL_BOOTSTRAP_FAILED =
            "production notification channels could not be initialized"
        const val NOTIFICATION_CHANNEL_PRECONDITION_REQUIRED =
            "stable production notification channels were unavailable after initialization"
        const val NOTIFICATION_CHANNEL_BASELINE_CHANGED =
            "production notification channel baseline changed"
        const val ACTIVE_NOTIFICATION_SCOPE_INVALID =
            "active notification query escaped the AuroraSMS package"
        const val ACTIVE_NOTIFICATION_BASELINE_UNAVAILABLE =
            "AuroraSMS active notification baseline was unavailable"
        const val ACTIVE_NOTIFICATION_BASELINE_NOT_RESTORED =
            "AuroraSMS active notification baseline was not restored"
        const val EXACT_SYNTHETIC_NOTIFICATION_CLEANUP_FAILED =
            "exact synthetic AuroraSMS notification cleanup failed"
        const val EXACT_SYNTHETIC_NOTIFICATION_CLEANUP_SCOPE_INVALID =
            "active notification did not match the controlled synthetic cleanup fingerprint"
        const val NOTIFICATION_PENDING_INTENT_COLLISION =
            "reserved synthetic notification identity was already active"
        const val NOTIFICATION_PENDING_INTENT_EMPTY_BASELINE_REQUIRED =
            "global wallpaper must be empty before the notification PendingIntent smoke"
        const val NOTIFICATION_PENDING_INTENT_POST_FAILED =
            "production notifier did not post the synthetic notification"
        const val NOTIFICATION_PENDING_INTENT_ID_INVALID =
            "production notifier returned an unexpected notification ID"
        const val EXACT_ACTIVE_NOTIFICATION_UNAVAILABLE =
            "exact system-posted AuroraSMS notification was unavailable"
        const val NOTIFICATION_CONTENT_PENDING_INTENT_REQUIRED =
            "system-posted AuroraSMS notification had no content PendingIntent"
        const val NOTIFICATION_PENDING_INTENT_CONTRACT_INVALID =
            "system-posted AuroraSMS content PendingIntent contract was invalid"
        const val NOTIFICATION_PENDING_INTENT_REOPENED_SOURCE =
            "notification routing unexpectedly reopened the transient SAF source"
        const val NOTIFICATION_SHADE_OPEN_FAILED =
            "AOSP notification shade could not be opened"
        const val EXACT_SYSTEM_UI_NOTIFICATION_BODY_UNAVAILABLE =
            "exact synthetic AuroraSMS notification body was unavailable in SystemUI"
        const val SYSTEM_UI_NOTIFICATION_BODY_INVALID =
            "exact synthetic AuroraSMS SystemUI notification body was invalid"
        const val NOTIFICATION_PENDING_INTENT_ROUTE_NOT_CONSUMED =
            "system notification PendingIntent did not reach the exact warm Thread route"
        const val NOTIFICATION_PENDING_INTENT_BACK_FAILED =
            "notification Thread route did not return to Inbox"
        const val NOTIFICATION_PENDING_INTENT_SELECTION_SURVIVED =
            "transient SAF selection survived system notification routing"
        const val SELECTION_ASSIGNMENT_CHANGED_BEFORE_APPLY =
            "global assignment changed before successful Apply"
        const val SELECTION_MANAGED_FILES_CHANGED_BEFORE_APPLY =
            "managed file ledger changed before successful Apply"
        const val SELECTION_REVISION_CHANGED_BEFORE_APPLY =
            "appearance revision sequence changed before successful Apply"
        const val SELECTION_ASSIGNMENT_NOT_RESTORED =
            "global assignment was not restored after Reset"
        const val SELECTION_MANAGED_FILES_NOT_RESTORED =
            "managed file ledger was not restored after Reset"
        const val REVISION_SEQUENCE_UNAVAILABLE = "appearance revision sequence was unavailable"
        const val MANAGED_FILE_LEDGER_UNAVAILABLE = "managed file ledger was unavailable"
        const val BASELINE_MANAGED_FILE_REMOVED = "Apply removed a baseline managed file"
        const val EXACTLY_ONE_NEW_MANAGED_FILE_REQUIRED =
            "Apply did not create exactly one managed file"
        const val NEW_MANAGED_FILE_NOT_CONFORMING = "Apply created a nonconforming managed file"
        const val APPLIED_ASSIGNMENT_REQUIRED = "Apply did not create one global assignment"
        const val APPLY_REVISION_SEQUENCE_UNEXPECTED = "Apply consumed an unexpected revision"
        const val APPLIED_ASSIGNMENT_REVISION_UNEXPECTED =
            "applied assignment did not own the expected revision"
        const val RESET_REVISION_SEQUENCE_UNEXPECTED = "Reset consumed an unexpected revision"
        const val APPLIED_MANAGED_WALLPAPER_UNAVAILABLE =
            "managed wallpaper did not load independently of the SAF source"
        const val APPLIED_MANAGED_WALLPAPER_INVALID =
            "managed wallpaper pixels or bounds were invalid"
        const val FINAL_BASELINE_UNAVAILABLE = "final SAF cancellation baseline could not be verified"
        const val FINAL_SELECTION_BASELINE_UNAVAILABLE =
            "final SAF selection baseline could not be verified"
        const val FINAL_STALE_APPLY_BASELINE_UNAVAILABLE =
            "final SAF stale-Apply baseline could not be verified"
        const val FINAL_NOTIFICATION_PENDING_INTENT_BASELINE_UNAVAILABLE =
            "final notification baseline could not be verified"
        const val FINAL_NOTIFICATION_PENDING_INTENT_CHANNEL_BASELINE_UNAVAILABLE =
            "final notification channel baseline could not be verified"
        const val FINAL_NOTIFICATION_PENDING_INTENT_ACTIVITY_CLEANUP_UNAVAILABLE =
            "notification PendingIntent activity could not be closed"
        const val FINAL_NOTIFICATION_PENDING_INTENT_DURABLE_BASELINE_UNAVAILABLE =
            "final notification PendingIntent durable baseline could not be verified"
        const val UNEXPECTED_FAILURE = "emulator SAF fallback cancellation smoke failed"
        const val UNEXPECTED_SELECTION_FAILURE = "emulator SAF fallback selection smoke failed"
        const val UNEXPECTED_STALE_APPLY_FAILURE =
            "emulator SAF fallback route-loss/stale-Apply smoke failed"
        const val UNEXPECTED_NOTIFICATION_PENDING_INTENT_FAILURE =
            "emulator SAF fallback notification PendingIntent smoke failed"
        const val UNEXPECTED_NOTIFICATION_CLEANUP_FAILURE =
            "emulator exact synthetic notification cleanup failed"
    }
}
