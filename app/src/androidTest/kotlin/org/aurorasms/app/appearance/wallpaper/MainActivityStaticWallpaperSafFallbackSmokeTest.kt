// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import org.aurorasms.app.StateStorageStatus
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_CANCEL_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_WALLPAPER_TEST_TAG
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.feature.conversations.CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCREEN_TEST_TAG
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner-invoked API 26 AOSP smoke for the AndroidX Photo Picker contract's SAF fallback.
 *
 * The cancellation method selects no document and never traverses DocumentsUI content. The
 * separately gated, emulator-only selection method opens only one exact test-APK SAF root and one
 * exact synthetic document. Unmatched visible titles are neither logged nor acted on, and no
 * shared-storage content is opened. Both methods use the real MainActivity editor and production
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
            failFixed(FIXED_INPUT_COMMAND_FAILED)
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
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val TEST_PACKAGE = "org.aurorasms.app.test"
        const val DOCUMENTS_UI_PACKAGE = "com.android.documentsui"
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
        const val DOCUMENTS_UI_ROOT_VIEWPORT_TIMEOUT_MILLIS = 2_000L
        const val DOCUMENTS_UI_ROOT_SCROLL_SETTLE_MILLIS = 350L
        const val INITIAL_FOCUS_STABLE_MILLIS = 750L
        const val MAXIMUM_DOCUMENTS_UI_DISMISS_ATTEMPTS = 3
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

        val SYNTHETIC_CONTROL_URI: android.net.Uri = android.net.Uri.parse(
            "content://org.aurorasms.app.wallpaper.testprovider/control",
        )

        val GLOBAL_THREAD_SCOPE: AppearanceScope.Screen =
            AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)

        const val GATE_REQUIRED = "emulator wallpaper SAF cancellation gate was not enabled"
        const val SELECTION_GATE_REQUIRED = "emulator wallpaper SAF selection gate was not enabled"
        const val EMULATOR_REQUIRED = "wallpaper SAF smoke requires an emulator"
        const val API_26_REQUIRED = "wallpaper SAF cancellation requires API 26"
        const val API_26_SELECTION_REQUIRED = "wallpaper SAF selection requires API 26"
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
        const val UNEXPECTED_FAILURE = "emulator SAF fallback cancellation smoke failed"
        const val UNEXPECTED_SELECTION_FAILURE = "emulator SAF fallback selection smoke failed"
    }
}
