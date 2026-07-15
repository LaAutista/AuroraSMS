// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.MainActivity
import org.aurorasms.app.appearance.SCOPED_APPEARANCE_CANCEL_TEST_TAG
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
 * Owner-invoked physical smoke for the real static-wallpaper Photo Picker journey.
 *
 * The test creates and addresses only its own unique MediaStore album. It never enumerates media
 * text, reads a user thumbnail, opens a conversation, reads message/contact content, or invokes a
 * carrier action. All application navigation uses exported Compose resource tags; system-picker
 * navigation uses fixed MediaProvider resource IDs plus the exact synthetic album name.
 * Invoke it through scripts/run-physical-wallpaper-picker-smoke.sh so instrumentation cleanup
 * removes only the test package and preserves the installed SMS app, its data, role, and grants.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MainActivityStaticWallpaperPhysicalSmokeTest {
    @Test
    fun realGlobalThreadPickerCancelBackApplyAndResetRestoreBaseline() {
        requireExplicitPhysicalGate()
        assumeTrue(PHOTO_PICKER_API_REQUIRED, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        var automation: UiAutomation? = null
        var originalServiceFlags: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var fixture: SyntheticMediaFixture? = null
        var controller: WallpaperController? = null
        var baseline: DurableBaseline? = null
        var testMayHaveApplied = false

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
            waitForStableInitialInboxOrAssume(activeAutomation).recycleSafely()

            val initialAssignment = readReadyAssignment(activeController)
            assumeTrue(PREEXISTING_ASSIGNMENT_SKIP, initialAssignment == null)
            val durableBaseline = DurableBaseline(
                managedFiles = managedFileNames(context),
                persistedGrantCount = persistedGrantCount(context.contentResolver),
            )
            baseline = durableBaseline

            val createdFixture = insertSyntheticFixture(context.contentResolver)
            fixture = createdFixture

            // A completed preview followed by Cancel must not cross a persistence boundary.
            openGlobalWallpaperEditor(activeAutomation)
            chooseSyntheticFixture(activeAutomation, createdFixture.albumName)
            assertBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)

            // The same completed preview followed by in-dialog Back also remains transient.
            openGlobalWallpaperEditor(activeAutomation)
            chooseSyntheticFixture(activeAutomation, createdFixture.albumName)
            assertBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_BACK_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)

            // Apply is the sole commit boundary.
            openGlobalWallpaperEditor(activeAutomation)
            chooseSyntheticFixture(activeAutomation, createdFixture.albumName)
            assertBaselineUnchanged(activeController, context, durableBaseline)
            testMayHaveApplied = true
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_APPLY_TEST_TAG)
            val applied = awaitAssignment(activeController, assigned = true)
                ?: failFixed(APPLIED_ASSIGNMENT_REQUIRED)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            assertExactlyOneNewManagedFinal(context, durableBaseline, applied)
            assertGrantCountUnchanged(context.contentResolver, durableBaseline)

            // Return through Appearance, re-enter the wallpaper editor, and reset the commit.
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
            waitForEnabledAppTag(activeAutomation, SCOPED_WALLPAPER_RESET_TEST_TAG).recycleSafely()
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_RESET_TEST_TAG)
            awaitAssignment(activeController, assigned = false)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)
            testMayHaveApplied = false
            clickAppTag(activeAutomation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)
            deleteFixture(createdFixture)
            fixture = null
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (fixed: FixedSmokeFailure) {
            throw fixed
        } catch (_: Throwable) {
            failFixed(UNEXPECTED_PHYSICAL_SMOKE_FAILURE)
        } finally {
            try {
                scenario?.close()
            } catch (_: Throwable) {
                // Best effort: cleanup below still verifies current durable state directly.
            }
            val cleanupController = controller
            if (testMayHaveApplied && cleanupController != null && baseline != null) {
                bestEffortResetTestAssignment(cleanupController)
            }
            fixture?.let { created -> bestEffortDeleteFixture(created) }
            val activeAutomation = automation
            val flagsToRestore = originalServiceFlags
            if (activeAutomation != null && flagsToRestore != null) {
                try {
                    activeAutomation.serviceInfo = activeAutomation.serviceInfo.apply {
                        flags = flagsToRestore
                    }
                } catch (_: Throwable) {
                    // Best effort: never replace the fixed primary failure with platform text.
                }
            }
        }
    }

    private fun requireExplicitPhysicalGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(PHYSICAL_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(PHYSICAL_GATE_REQUIRED, enabled)
    }

    private fun insertSyntheticFixture(resolver: ContentResolver): SyntheticMediaFixture {
        val albumName = "AuroraSMS-${UUID.randomUUID().toString().take(12)}"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, SYNTHETIC_DISPLAY_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, SYNTHETIC_MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$albumName",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val inserted = resolver.insert(collection, values)
            ?: failFixed(FIXTURE_INSERT_FAILED)
        try {
            val source = resolver.openInputStream(SYNTHETIC_SOURCE_URI)
                ?: failFixed(FIXTURE_COPY_FAILED)
            source.use { input ->
                val output = resolver.openOutputStream(inserted, "w")
                    ?: failFixed(FIXTURE_COPY_FAILED)
                output.use { destination -> input.copyTo(destination, FIXTURE_COPY_BUFFER_BYTES) }
            }
            val published = resolver.update(
                inserted,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) failFixed(FIXTURE_PUBLISH_FAILED)
            resolver.notifyChange(inserted, null)
            return SyntheticMediaFixture(resolver, inserted, albumName)
        } catch (fixed: FixedSmokeFailure) {
            try {
                resolver.delete(inserted, null, null)
            } catch (_: Throwable) {
                // The outer fixed failure remains authoritative.
            }
            throw fixed
        } catch (_: Throwable) {
            try {
                resolver.delete(inserted, null, null)
            } catch (_: Throwable) {
                // The fixed copy failure remains authoritative.
            }
            failFixed(FIXTURE_COPY_FAILED)
        }
    }

    private fun openGlobalWallpaperEditor(automation: UiAutomation) {
        clickAppTag(automation, INBOX_MORE_ACTION_TEST_TAG)
        clickAppTag(automation, CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG)
        waitForAppTag(automation, SCOPED_APPEARANCE_DIALOG_TEST_TAG).recycleSafely()
        clickAppTag(automation, SCOPED_APPEARANCE_WALLPAPER_TEST_TAG)
        waitForAppTag(automation, SCOPED_WALLPAPER_DIALOG_TEST_TAG).recycleSafely()
        waitForEnabledAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG).recycleSafely()
    }

    private fun chooseSyntheticFixture(
        automation: UiAutomation,
        albumName: String,
    ) {
        clickAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG)
        waitForPackage(automation, PHOTO_PICKER_PACKAGE)
        clickSecondPickerTab(automation)
        clickExactSyntheticAlbum(automation, albumName)
        clickSoleSyntheticThumbnail(automation)
        waitForPackage(automation, TARGET_PACKAGE)
        waitForEnabledAppTag(automation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
    }

    private fun clickSecondPickerTab(automation: UiAutomation) {
        val tabLayout = waitForPickerResource(automation, PICKER_TAB_LAYOUT_RESOURCE)
        val clickableTabs = tabLayout.useNode(::visibleClickableDescendants)
        if (clickableTabs.size != EXPECTED_PICKER_TAB_COUNT) {
            clickableTabs.recycleAll()
            failFixed(PICKER_TAB_STRUCTURE_UNEXPECTED)
        }
        val selected = clickableTabs[ALBUMS_TAB_INDEX]
        val clicked = try {
            selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            clickableTabs.recycleAll()
        }
        if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
        SystemClock.sleep(PICKER_SETTLE_MILLIS)
    }

    private fun clickExactSyntheticAlbum(
        automation: UiAutomation,
        albumName: String,
    ) {
        repeat(MAXIMUM_PICKER_SCROLLS) {
            findExactAlbumNode(automation, albumName)?.let { album ->
                val clicked = album.useNode(::clickNodeOrAncestor)
                if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
                return
            }
            val recycler = waitForPickerResource(automation, PICKER_RECYCLER_RESOURCE)
            val scrolled = recycler.useNode { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (!scrolled) failFixed(SYNTHETIC_ALBUM_NOT_AVAILABLE)
            SystemClock.sleep(PICKER_SETTLE_MILLIS)
        }
        failFixed(SYNTHETIC_ALBUM_NOT_AVAILABLE)
    }

    private fun findExactAlbumNode(
        automation: UiAutomation,
        albumName: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != PHOTO_PICKER_PACKAGE) return null
            val matches = root.findAccessibilityNodeInfosByText(albumName)
            var accepted: AccessibilityNodeInfo? = null
            matches.forEach { node ->
                val exactSyntheticTarget = accepted == null &&
                    node.isVisibleToUser &&
                    node.viewIdResourceName == PICKER_ALBUM_NAME_RESOURCE &&
                    node.text?.toString() == albumName
                if (exactSyntheticTarget) accepted = node else node.recycleSafely()
            }
            accepted
        } finally {
            root.recycleSafely()
        }
    }

    private fun clickSoleSyntheticThumbnail(automation: UiAutomation) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val thumbnails = findVisiblePickerResources(automation, PICKER_THUMBNAIL_RESOURCE)
            if (thumbnails.size == 1) {
                val thumbnail = thumbnails.single()
                val clicked = thumbnail.useNode(::clickNodeOrAncestor)
                if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
                return
            }
            thumbnails.recycleAll()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(SOLE_SYNTHETIC_THUMBNAIL_REQUIRED)
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
            findResourceInActiveWindow(automation, TARGET_PACKAGE, tag)?.let { node ->
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
    ): AccessibilityNodeInfo = waitForResource(automation, TARGET_PACKAGE, tag, APP_TAG_NOT_AVAILABLE)

    private fun waitForPickerResource(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo = waitForResource(
        automation,
        PHOTO_PICKER_PACKAGE,
        resource,
        PICKER_RESOURCE_NOT_AVAILABLE,
    )

    private fun waitForResource(
        automation: UiAutomation,
        packageName: String,
        resource: String,
        failure: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findResourceInActiveWindow(automation, packageName, resource)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(failure)
    }

    private fun findResourceInActiveWindow(
        automation: UiAutomation,
        packageName: String,
        resource: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != packageName) return null
            val nodes = root.findAccessibilityNodeInfosByViewId(resource)
            var accepted: AccessibilityNodeInfo? = null
            nodes.forEach { node ->
                if (accepted == null && node.isVisibleToUser) accepted = node else node.recycleSafely()
            }
            accepted
        } finally {
            root.recycleSafely()
        }
    }

    private fun findVisiblePickerResources(
        automation: UiAutomation,
        resource: String,
    ): MutableList<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return mutableListOf()
        return try {
            if (root.packageName?.toString() != PHOTO_PICKER_PACKAGE) return mutableListOf()
            root.findAccessibilityNodeInfosByViewId(resource)
                .filterTo(mutableListOf()) { node ->
                    val visible = node.isVisibleToUser
                    if (!visible) node.recycleSafely()
                    visible
                }
        } finally {
            root.recycleSafely()
        }
    }

    private fun visibleClickableDescendants(root: AccessibilityNodeInfo): MutableList<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val accepted = mutableListOf<AccessibilityNodeInfo>()
        for (index in 0 until root.childCount) root.getChild(index)?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.isClickable) {
                accepted += node
            } else {
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
                node.recycleSafely()
            }
        }
        pending.recycleAll()
        return accepted
    }

    private fun clickNodeOrAncestor(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        while (node != null) {
            if (node.isClickable) {
                val clicked = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } finally {
                    if (node !== start) node.recycleSafely()
                }
                return clicked
            }
            val parent = node.parent
            if (node !== start) node.recycleSafely()
            node = parent
        }
        return false
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

    private fun waitForStableInitialInboxOrAssume(
        automation: UiAutomation,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var stableWindowId: Int? = null
        var stableSince = 0L
        do {
            val focusedWindowId = focusedTargetApplicationWindowId(automation)
            val inbox = if (focusedWindowId != null) {
                findResourceInActiveWindow(automation, TARGET_PACKAGE, INBOX_SCREEN_TEST_TAG)
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

        assumeTrue(
            DEVICE_FOREGROUND_REQUIRED,
            focusedTargetApplicationWindowId(automation) != null,
        )
        failFixed(APP_TAG_NOT_AVAILABLE)
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

    private fun waitForAppTagToDisappear(
        automation: UiAutomation,
        tag: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val found = findResourceInActiveWindow(automation, TARGET_PACKAGE, tag)
            if (found == null) return
            found.recycleSafely()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(APP_TAG_REMAINED_AVAILABLE)
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

    private fun assertBaselineUnchanged(
        controller: WallpaperController,
        context: Context,
        baseline: DurableBaseline,
    ) {
        if (readReadyAssignment(controller) != null) failFixed(ASSIGNMENT_CHANGED_BEFORE_APPLY)
        if (managedFileNames(context) != baseline.managedFiles) {
            failFixed(MANAGED_FILES_CHANGED_BEFORE_APPLY)
        }
        assertGrantCountUnchanged(context.contentResolver, baseline)
    }

    private fun assertExactlyOneNewManagedFinal(
        context: Context,
        baseline: DurableBaseline,
        assignment: AppWallpaperAssignment,
    ) {
        val current = managedFileNames(context)
        if (!current.containsAll(baseline.managedFiles)) failFixed(BASELINE_MANAGED_FILE_REMOVED)
        val added = current - baseline.managedFiles
        if (added.size != 1) failFixed(EXACTLY_ONE_NEW_MANAGED_FILE_REQUIRED)
        val classification = classifyManagedWallpaperFileName(added.single())
        if (
            classification !is ManagedWallpaperFileClassification.Final ||
            classification.mediaId != assignment.mediaId
        ) {
            failFixed(NEW_MANAGED_FILE_NOT_CONFORMING)
        }
    }

    private fun assertGrantCountUnchanged(
        resolver: ContentResolver,
        baseline: DurableBaseline,
    ) {
        if (persistedGrantCount(resolver) != baseline.persistedGrantCount) {
            failFixed(PERSISTED_GRANT_COUNT_CHANGED)
        }
    }

    private fun managedFileNames(context: Context): Set<String> {
        val directory = File(context.noBackupFilesDir, "appearance/wallpapers")
        if (!directory.exists()) return emptySet()
        if (!directory.isDirectory) failFixed(MANAGED_DIRECTORY_INVALID)
        return directory.list()?.toSet() ?: failFixed(MANAGED_DIRECTORY_UNAVAILABLE)
    }

    private fun persistedGrantCount(resolver: ContentResolver): Int =
        resolver.persistedUriPermissions.size

    private fun bestEffortResetTestAssignment(controller: WallpaperController) {
        try {
            // Join any in-flight Apply through the controller's mutation mutex. A null expected
            // revision succeeds only while the scope is still empty; a stale result means the
            // just-finished Apply committed and the fresh revision-qualified reset below owns it.
            runBlocking {
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    controller.reset(GLOBAL_THREAD_SCOPE, expectedRevision = null)
                }
            }
            val current = readReadyAssignment(controller) ?: return
            runBlocking {
                withTimeout(STATE_TIMEOUT_MILLIS) {
                    controller.reset(GLOBAL_THREAD_SCOPE, current.revision)
                }
            }
            awaitAssignment(controller, assigned = false)
        } catch (_: Throwable) {
            // Test started with no global assignment, so only its own possible commit is targeted.
        }
    }

    private fun bestEffortDeleteFixture(fixture: SyntheticMediaFixture) {
        try {
            fixture.resolver.delete(fixture.uri, null, null)
        } catch (_: Throwable) {
            // Do not replace the fixed primary result with provider-specific text.
        }
    }

    private fun deleteFixture(fixture: SyntheticMediaFixture) {
        val deleted = try {
            fixture.resolver.delete(fixture.uri, null, null)
        } catch (_: Throwable) {
            failFixed(FIXTURE_DELETE_FAILED)
        }
        if (deleted != 1) failFixed(FIXTURE_DELETE_FAILED)
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

    private fun MutableList<AccessibilityNodeInfo>.recycleAll() {
        forEach { node -> node.recycleSafely() }
        clear()
    }

    private fun ArrayDeque<AccessibilityNodeInfo>.recycleAll() {
        while (isNotEmpty()) removeFirst().recycleSafely()
    }

    private fun failFixed(message: String): Nothing = throw FixedSmokeFailure(message)

    private class FixedSmokeFailure(message: String) : AssertionError(message)

    private class DurableBaseline(
        val managedFiles: Set<String>,
        val persistedGrantCount: Int,
    ) {
        override fun toString(): String = "DurableBaseline(REDACTED)"
    }

    private class SyntheticMediaFixture(
        val resolver: ContentResolver,
        val uri: Uri,
        val albumName: String,
    ) {
        override fun toString(): String = "SyntheticMediaFixture(REDACTED)"
    }

    private companion object {
        const val PHYSICAL_GATE_ARGUMENT = "auroraPhysicalWallpaperPickerSmoke"
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val PHOTO_PICKER_PACKAGE = "com.android.providers.media.module"
        const val PICKER_TAB_LAYOUT_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/tab_layout"
        const val PICKER_RECYCLER_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/picker_tab_recyclerview"
        const val PICKER_ALBUM_NAME_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/album_name"
        const val PICKER_THUMBNAIL_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/icon_thumbnail"
        const val SYNTHETIC_DISPLAY_NAME = "aurora-wallpaper-smoke.png"
        const val SYNTHETIC_MIME_TYPE = "image/png"
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 75L
        const val INITIAL_FOCUS_STABLE_MILLIS = 750L
        const val PICKER_SETTLE_MILLIS = 250L
        const val FIXTURE_COPY_BUFFER_BYTES = 8 * 1024
        const val EXPECTED_PICKER_TAB_COUNT = 2
        const val ALBUMS_TAB_INDEX = 1
        const val MAXIMUM_PICKER_SCROLLS = 32

        val SYNTHETIC_SOURCE_URI: Uri =
            Uri.parse("content://org.aurorasms.app.wallpaper.testprovider/valid.png")
        val GLOBAL_THREAD_SCOPE: AppearanceScope.Screen =
            AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)

        const val PHYSICAL_GATE_REQUIRED = "physical wallpaper picker smoke gate was not enabled"
        const val PHOTO_PICKER_API_REQUIRED = "physical wallpaper picker smoke requires API 33 or newer"
        const val PREEXISTING_ASSIGNMENT_SKIP = "global wallpaper already exists; physical smoke skipped"
        const val DEVICE_FOREGROUND_REQUIRED = "physical smoke requires an available focused device"
        const val APPLICATION_REQUIRED = "AuroraSMS application was not available"
        const val REAL_ACTIVITY_REQUIRED = "real MainActivity was not launched"
        const val INBOX_ROUTE_REQUIRED = "physical smoke did not start on Inbox"
        const val APP_TAG_NOT_AVAILABLE = "required content-free app resource tag was not available"
        const val ENABLED_APP_TAG_NOT_AVAILABLE = "required app resource tag did not become enabled"
        const val APP_TAG_REMAINED_AVAILABLE = "dismissed app resource tag remained available"
        const val APP_TAG_CLICK_FAILED = "content-free app resource-tag click failed"
        const val EXPECTED_PACKAGE_NOT_FOCUSED = "expected fixed package did not receive focus"
        const val PICKER_RESOURCE_NOT_AVAILABLE = "required fixed Photo Picker resource was unavailable"
        const val PICKER_RESOURCE_CLICK_FAILED = "fixed Photo Picker resource click failed"
        const val PICKER_TAB_STRUCTURE_UNEXPECTED = "fixed Photo Picker tab structure was unexpected"
        const val SYNTHETIC_ALBUM_NOT_AVAILABLE = "exact synthetic Photo Picker album was unavailable"
        const val SOLE_SYNTHETIC_THUMBNAIL_REQUIRED = "sole synthetic Photo Picker thumbnail was unavailable"
        const val FIXTURE_INSERT_FAILED = "synthetic MediaStore fixture could not be inserted"
        const val FIXTURE_COPY_FAILED = "synthetic MediaStore fixture could not be copied"
        const val FIXTURE_PUBLISH_FAILED = "synthetic MediaStore fixture could not be published"
        const val FIXTURE_DELETE_FAILED = "synthetic MediaStore fixture could not be deleted"
        const val ASSIGNMENT_CHANGED_BEFORE_APPLY = "global assignment changed before Apply"
        const val MANAGED_FILES_CHANGED_BEFORE_APPLY = "managed files changed before Apply"
        const val PERSISTED_GRANT_COUNT_CHANGED = "persisted URI grant count changed"
        const val APPLIED_ASSIGNMENT_REQUIRED = "Apply did not create one global assignment"
        const val BASELINE_MANAGED_FILE_REMOVED = "Apply removed a baseline managed file"
        const val EXACTLY_ONE_NEW_MANAGED_FILE_REQUIRED = "Apply did not create exactly one managed file"
        const val NEW_MANAGED_FILE_NOT_CONFORMING = "Apply created a nonconforming managed file"
        const val MANAGED_DIRECTORY_INVALID = "managed wallpaper directory was invalid"
        const val MANAGED_DIRECTORY_UNAVAILABLE = "managed wallpaper directory was unavailable"
        const val UNEXPECTED_PHYSICAL_SMOKE_FAILURE = "physical wallpaper picker smoke failed"
    }
}
