// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.MainActivity
import org.aurorasms.app.StateStorageStatus
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_DIALOG_TEST_TAG
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_WALLPAPER_TEST_TAG
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
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
 * The test selects no document and never traverses DocumentsUI content. It first proves that the
 * contract resolves to ACTION_OPEN_DOCUMENT with image-only MIME filtering on this exact device,
 * then cancels the focused AOSP DocumentsUI activity with accessibility Back. The real
 * MainActivity editor must return usable without changing its exact assignment,
 * managed-filename, or persisted-grant baseline.
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

    private fun clickAppTag(
        automation: UiAutomation,
        tag: String,
    ) {
        val clicked = waitForAppTag(automation, tag).useNode { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!clicked) failFixed(APP_TAG_CLICK_FAILED)
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
        recycle()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.recycleSafely() {
        recycle()
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
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val DOCUMENTS_UI_PACKAGE = "com.android.documentsui"
        const val IMAGE_MIME_TYPE = "image/*"
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 75L
        const val INITIAL_FOCUS_STABLE_MILLIS = 750L
        const val MAXIMUM_DOCUMENTS_UI_DISMISS_ATTEMPTS = 3

        val GLOBAL_THREAD_SCOPE: AppearanceScope.Screen =
            AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)

        const val GATE_REQUIRED = "emulator wallpaper SAF cancellation gate was not enabled"
        const val EMULATOR_REQUIRED = "wallpaper SAF cancellation requires an emulator"
        const val API_26_REQUIRED = "wallpaper SAF cancellation requires API 26"
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
        const val FINAL_BASELINE_UNAVAILABLE = "final SAF cancellation baseline could not be verified"
        const val UNEXPECTED_FAILURE = "emulator SAF fallback cancellation smoke failed"
    }
}
