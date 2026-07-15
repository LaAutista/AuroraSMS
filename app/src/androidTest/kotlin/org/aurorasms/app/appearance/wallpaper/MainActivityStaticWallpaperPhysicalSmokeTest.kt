// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
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
 * The test creates one uniquely named synthetic MediaStore download. It never
 * enumerates media text, reads or selects a user thumbnail, opens a conversation, reads
 * message/contact content, or invokes a carrier action. All application navigation uses exported
 * Compose resource tags; system-picker navigation uses fixed MediaProvider resources plus the
 * localized Downloads label and an exact synthetic description reproduced from the installed
 * picker's resource and date-formatting contract.
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

            val createdFixture = insertSyntheticFixture(context)
            fixture = createdFixture

            // A completed preview followed by Cancel must not cross a persistence boundary.
            openGlobalWallpaperEditor(activeAutomation)
            chooseSyntheticFixture(activeAutomation, createdFixture)
            assertBaselineUnchanged(activeController, context, durableBaseline)
            clickAppTag(activeAutomation, SCOPED_WALLPAPER_CANCEL_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_WALLPAPER_DIALOG_TEST_TAG)
            waitForAppTagToDisappear(activeAutomation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)
            waitForAppTag(activeAutomation, INBOX_SCREEN_TEST_TAG).recycleSafely()
            assertBaselineUnchanged(activeController, context, durableBaseline)

            // The same completed preview followed by in-dialog Back also remains transient.
            openGlobalWallpaperEditor(activeAutomation)
            chooseSyntheticFixture(activeAutomation, createdFixture)
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
            chooseSyntheticFixture(activeAutomation, createdFixture)
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
            scrollToAndClickAppTag(activeAutomation, SCOPED_WALLPAPER_RESET_TEST_TAG)
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

    private fun insertSyntheticFixture(context: Context): SyntheticMediaFixture {
        val resolver = context.contentResolver
        val fixtureId = UUID.randomUUID()
        val displayName = "aurora-wallpaper-smoke-${fixtureId.toString().take(12)}.png"
        val syntheticDateTaken = SYNTHETIC_DATE_BASE_MILLIS +
            Math.floorMod(fixtureId.leastSignificantBits, SYNTHETIC_DATE_SPAN_SECONDS) * 1_000L
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, SYNTHETIC_MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
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
            writeSyntheticExif(resolver, inserted, syntheticDateTaken)
            val published = resolver.update(
                inserted,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) failFixed(FIXTURE_PUBLISH_FAILED)
            resolver.notifyChange(inserted, null)
            val publishedDateTaken = readBackPublishedFixture(
                resolver = resolver,
                uri = inserted,
                expectedDateTaken = syntheticDateTaken,
            )
            val pickerTargets = resolvePickerTargets(context, publishedDateTaken)
            return SyntheticMediaFixture(
                resolver = resolver,
                uri = inserted,
                albumName = pickerTargets.downloadsAlbumName,
                contentDescription = pickerTargets.syntheticContentDescription,
            )
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

    private fun writeSyntheticExif(
        resolver: ContentResolver,
        uri: Uri,
        dateTaken: Long,
    ) {
        val dateTime = SYNTHETIC_EXIF_FORMATTER.format(Instant.ofEpochMilli(dateTaken))
        val written = try {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME, dateTime)
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
                    setAttribute(ExifInterface.TAG_OFFSET_TIME, SYNTHETIC_EXIF_OFFSET)
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, SYNTHETIC_EXIF_OFFSET)
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, SYNTHETIC_EXIF_OFFSET)
                    saveAttributes()
                }
                true
            } == true
        } catch (_: Throwable) {
            false
        }
        if (!written) failFixed(FIXTURE_EXIF_WRITE_FAILED)
    }

    private fun readBackPublishedFixture(
        resolver: ContentResolver,
        uri: Uri,
        expectedDateTaken: Long,
    ): Long {
        val timeoutAt = SystemClock.uptimeMillis() + FIXTURE_READBACK_TIMEOUT_MILLIS
        do {
            val verifiedDate = try {
                resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.MIME_TYPE,
                        MediaStore.Images.Media.IS_PENDING,
                        MediaStore.Images.Media.IS_DOWNLOAD,
                        MediaStore.Images.Media.SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.count != 1 || !cursor.moveToFirst()) return@use null
                    val dateTaken = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN),
                    )
                    val mimeType = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE),
                    )
                    val pending = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING),
                    )
                    val download = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_DOWNLOAD),
                    )
                    val size = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE),
                    )
                    dateTaken.takeIf {
                        it == expectedDateTaken &&
                            mimeType == SYNTHETIC_MIME_TYPE &&
                            pending == 0 &&
                            download == 1 &&
                            size > 0L
                    }
                }
            } catch (_: Throwable) {
                null
            }
            if (verifiedDate != null) return verifiedDate
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(FIXTURE_READBACK_FAILED)
    }

    private fun resolvePickerTargets(
        context: Context,
        publishedDateTaken: Long,
    ): PickerTargets {
        val pickerContext = try {
            context.createPackageContext(PHOTO_PICKER_PACKAGE, 0)
        } catch (_: Throwable) {
            failFixed(PICKER_RESOURCE_SCHEMA_UNAVAILABLE)
        }
        val resources = pickerContext.resources
        fun requiredString(name: String): Int = resources.getIdentifier(
            name,
            "string",
            PHOTO_PICKER_PACKAGE,
        ).takeIf { identifier -> identifier != 0 }
            ?: failFixed(PICKER_RESOURCE_SCHEMA_UNAVAILABLE)

        val formatter = DateFormat.getInstanceForSkeleton(
            PICKER_CONTENT_DESCRIPTION_DATE_SKELETON,
            Locale.getDefault(),
        ).apply {
            setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
        }
        val dateDescription = formatter.format(publishedDateTaken)
        val photoDescription = resources.getString(requiredString(PICKER_PHOTO_STRING_NAME))
        return PickerTargets(
            downloadsAlbumName = resources.getString(
                requiredString(PICKER_DOWNLOADS_STRING_NAME),
            ),
            syntheticContentDescription = resources.getString(
                requiredString(PICKER_ITEM_CONTENT_DESCRIPTION_STRING_NAME),
                photoDescription,
                dateDescription,
            ),
        )
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
        fixture: SyntheticMediaFixture,
    ) {
        clickAppTag(automation, SCOPED_WALLPAPER_PICK_TEST_TAG)
        waitForPackage(automation, PHOTO_PICKER_PACKAGE)
        clickSecondPickerTab(automation)
        clickExactPickerAlbum(automation, fixture.albumName)
        clickExactSyntheticThumbnail(automation, fixture.contentDescription)
        waitForPackage(automation, TARGET_PACKAGE)
        waitForEnabledAppTag(automation, SCOPED_WALLPAPER_APPLY_TEST_TAG).recycleSafely()
    }

    private fun clickSecondPickerTab(automation: UiAutomation) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val tabLayout = findPickerResourceInActiveWindow(
                automation,
                PICKER_TAB_LAYOUT_RESOURCE,
            )
            if (tabLayout != null) {
                val collection = tabLayout.collectionInfo
                val tabItems = tabLayout.useNode(::visibleCollectionItems)
                val albumsTab = tabItems.singleOrNull { node ->
                    val item = node.collectionItemInfo
                    item?.rowIndex == PICKER_TAB_ROW_INDEX &&
                        item.columnIndex == ALBUMS_TAB_INDEX
                }
                val exactStructure = collection?.rowCount == EXPECTED_PICKER_TAB_ROW_COUNT &&
                    collection.columnCount == EXPECTED_PICKER_TAB_COUNT &&
                    tabItems.size == EXPECTED_PICKER_TAB_COUNT &&
                    tabItems.mapNotNull { node -> node.collectionItemInfo?.columnIndex }.toSet() ==
                    EXPECTED_PICKER_TAB_COLUMNS
                if (exactStructure && albumsTab != null) {
                    val clicked = try {
                        clickNodeOrAncestor(albumsTab)
                    } finally {
                        tabItems.recycleAll()
                    }
                    if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
                    SystemClock.sleep(PICKER_SETTLE_MILLIS)
                    return
                }
                tabItems.recycleAll()
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(PICKER_TAB_STRUCTURE_UNEXPECTED)
    }

    private fun clickExactPickerAlbum(
        automation: UiAutomation,
        albumName: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var completedScrolls = 0
        do {
            findExactAlbumNode(automation, albumName)?.let { album ->
                val clicked = album.useNode(::clickExactAlbumNode)
                if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
                return
            }
            val recycler = findPickerResourceInActiveWindow(
                automation,
                PICKER_RECYCLER_RESOURCE,
            )
            if (recycler != null && completedScrolls < MAXIMUM_PICKER_SCROLLS) {
                val scrolled = recycler.useNode { node ->
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                if (scrolled) completedScrolls += 1
            }
            SystemClock.sleep(PICKER_SETTLE_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(PICKER_DOWNLOADS_ALBUM_NOT_AVAILABLE)
    }

    private fun findExactAlbumNode(
        automation: UiAutomation,
        albumName: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != PHOTO_PICKER_PACKAGE) return null
            val activeWindowId = root.windowId
            val matches = root.findAccessibilityNodeInfosByText(albumName)
            val accepted = mutableListOf<AccessibilityNodeInfo>()
            matches.forEach { node ->
                val exactSyntheticTarget = node.packageName?.toString() == PHOTO_PICKER_PACKAGE &&
                    node.windowId == activeWindowId &&
                    node.isVisibleToUser &&
                    node.isEnabled &&
                    node.className?.toString() == PICKER_ALBUM_NAME_CLASS_NAME &&
                    node.viewIdResourceName == PICKER_ALBUM_NAME_RESOURCE &&
                    node.text?.toString() == albumName
                if (exactSyntheticTarget) accepted += node else node.recycleSafely()
            }
            if (accepted.size == 1) accepted.removeFirst() else {
                accepted.recycleAll()
                null
            }
        } finally {
            root.recycleSafely()
        }
    }

    private fun clickExactAlbumNode(albumNameNode: AccessibilityNodeInfo): Boolean {
        val parent = albumNameNode.parent ?: return false
        return try {
            parent.packageName?.toString() == PHOTO_PICKER_PACKAGE &&
                parent.windowId == albumNameNode.windowId &&
                parent.className?.toString() == PICKER_ALBUM_ROOT_CLASS_NAME &&
                parent.isVisibleToUser &&
                parent.isEnabled &&
                parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            parent.recycleSafely()
        }
    }

    private fun clickExactSyntheticThumbnail(
        automation: UiAutomation,
        contentDescription: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findExactSyntheticThumbnail(automation, contentDescription)?.let { thumbnail ->
                val clicked = thumbnail.useNode { node ->
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!clicked) failFixed(PICKER_RESOURCE_CLICK_FAILED)
                return
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EXACT_SYNTHETIC_THUMBNAIL_REQUIRED)
    }

    private fun findExactSyntheticThumbnail(
        automation: UiAutomation,
        contentDescription: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != PHOTO_PICKER_PACKAGE) return null
            val activeWindowId = root.windowId
            val matches = root.findAccessibilityNodeInfosByText(contentDescription)
            val accepted = mutableListOf<AccessibilityNodeInfo>()
            matches.forEach { node ->
                val exactSyntheticTarget = node.packageName?.toString() == PHOTO_PICKER_PACKAGE &&
                    node.windowId == activeWindowId &&
                    node.isVisibleToUser &&
                    node.isEnabled &&
                    node.isClickable &&
                    node.isFocusable &&
                    node.className?.toString() == PICKER_ITEM_ROOT_CLASS_NAME &&
                    node.contentDescription?.toString() == contentDescription
                if (exactSyntheticTarget) accepted += node else node.recycleSafely()
            }
            if (accepted.size == 1) accepted.removeFirst() else {
                accepted.recycleAll()
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

    private fun scrollToAndClickAppTag(
        automation: UiAutomation,
        tag: String,
    ) {
        var observedVisible = false
        var observedEnabled = false
        repeat(MAXIMUM_APP_DIALOG_SCROLLS) {
            val clicked = findAppTagInActiveWindow(automation, tag)?.useNode { node ->
                try {
                    node.refresh()
                } catch (_: Throwable) {
                    // The fresh active-window lookup remains authoritative for this attempt.
                }
                observedVisible = observedVisible || node.isVisibleToUser
                observedEnabled = observedEnabled || node.isEnabled
                node.isVisibleToUser &&
                    node.isEnabled &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } == true
            if (clicked) return
            if (!swipeWallpaperDialogUp(automation)) failFixed(APP_DIALOG_SWIPE_FAILED)
            SystemClock.sleep(APP_DIALOG_SCROLL_SETTLE_MILLIS)
        }
        if (!observedVisible) failFixed(APP_TAG_DID_NOT_BECOME_VISIBLE)
        if (!observedEnabled) failFixed(ENABLED_APP_TAG_NOT_AVAILABLE)
        failFixed(APP_TAG_CLICK_FAILED)
    }

    private fun swipeWallpaperDialogUp(automation: UiAutomation): Boolean {
        val dialogBounds = waitForAppTag(
            automation,
            SCOPED_WALLPAPER_DIALOG_TEST_TAG,
        ).useNode { node ->
            Rect().also { result -> node.getBoundsInScreen(result) }
        }
        val previewBounds = waitForAppTag(
            automation,
            SCOPED_WALLPAPER_PREVIEW_TEST_TAG,
        ).useNode { node ->
            Rect().also { result -> node.getBoundsInScreen(result) }
        }
        if (
            dialogBounds.width() <= 0 ||
            dialogBounds.height() <= 0 ||
            previewBounds.width() <= 0 ||
            previewBounds.height() <= 0
        ) {
            return false
        }

        // Begin on the non-interactive preview so sliders cannot consume or reinterpret the
        // synthetic vertical gesture. Ending near the dialog top provides enough distance to
        // expose Reset in one bounded swipe without relying on fixed screen coordinates.
        val x = previewBounds.centerX()
        val startY = previewBounds.bottom - (previewBounds.height() / 8)
        val endY = dialogBounds.top + (dialogBounds.height() / 10)
        if (startY <= endY) return false

        return try {
            val descriptor = automation.executeShellCommand(
                "input touchscreen swipe $x $startY $x $endY $APP_DIALOG_SWIPE_DURATION_MILLIS",
            )
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(SHELL_COMMAND_OUTPUT_BUFFER_BYTES)
                while (input.read(buffer) >= 0) {
                    // The fixed input command has no expected output; drain it to await completion.
                }
            }
            true
        } catch (_: Throwable) {
            false
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

    private fun findPickerResourceInActiveWindow(
        automation: UiAutomation,
        resource: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != PHOTO_PICKER_PACKAGE) return null
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

    private fun visibleCollectionItems(root: AccessibilityNodeInfo): MutableList<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val accepted = mutableListOf<AccessibilityNodeInfo>()
        for (index in 0 until root.childCount) root.getChild(index)?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.collectionItemInfo != null) {
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
            val found = findAppTagInActiveWindow(automation, tag)
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
        val contentDescription: String,
    ) {
        override fun toString(): String = "SyntheticMediaFixture(REDACTED)"
    }

    private class PickerTargets(
        val downloadsAlbumName: String,
        val syntheticContentDescription: String,
    ) {
        override fun toString(): String = "PickerTargets(REDACTED)"
    }

    private companion object {
        const val PHYSICAL_GATE_ARGUMENT = "auroraPhysicalWallpaperPickerSmoke"
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val PHOTO_PICKER_PACKAGE = "com.android.providers.media.module"
        const val PICKER_TAB_LAYOUT_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/tab_layout"
        const val PICKER_RECYCLER_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/picker_tab_recyclerview"
        const val PICKER_ALBUM_NAME_RESOURCE = "$PHOTO_PICKER_PACKAGE:id/album_name"
        const val PICKER_DOWNLOADS_STRING_NAME = "picker_category_downloads"
        const val PICKER_PHOTO_STRING_NAME = "picker_photo"
        const val PICKER_ITEM_CONTENT_DESCRIPTION_STRING_NAME = "picker_item_content_desc"
        const val PICKER_CONTENT_DESCRIPTION_DATE_SKELETON = "MMMdyhmmss"
        const val PICKER_ALBUM_NAME_CLASS_NAME = "android.widget.TextView"
        const val PICKER_ALBUM_ROOT_CLASS_NAME = "android.widget.LinearLayout"
        const val PICKER_ITEM_ROOT_CLASS_NAME = "android.widget.FrameLayout"
        const val SYNTHETIC_MIME_TYPE = "image/png"
        const val SYNTHETIC_DATE_BASE_MILLIS = 7_258_118_400_000L
        const val SYNTHETIC_DATE_SPAN_SECONDS = 6_311_347_200L
        const val SYNTHETIC_EXIF_OFFSET = "+00:00"
        const val SYNTHETIC_EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss"
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val FIXTURE_READBACK_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 75L
        const val INITIAL_FOCUS_STABLE_MILLIS = 750L
        const val PICKER_SETTLE_MILLIS = 250L
        const val FIXTURE_COPY_BUFFER_BYTES = 8 * 1024
        const val EXPECTED_PICKER_TAB_ROW_COUNT = 1
        const val PICKER_TAB_ROW_INDEX = 0
        const val EXPECTED_PICKER_TAB_COUNT = 2
        const val ALBUMS_TAB_INDEX = 1
        const val MAXIMUM_PICKER_SCROLLS = 32
        const val MAXIMUM_APP_DIALOG_SCROLLS = 4
        const val APP_DIALOG_SWIPE_DURATION_MILLIS = 300L
        const val APP_DIALOG_SCROLL_SETTLE_MILLIS = 250L
        const val SHELL_COMMAND_OUTPUT_BUFFER_BYTES = 128

        val EXPECTED_PICKER_TAB_COLUMNS: Set<Int> = setOf(0, ALBUMS_TAB_INDEX)

        val SYNTHETIC_EXIF_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern(SYNTHETIC_EXIF_PATTERN, Locale.US)
            .withZone(ZoneOffset.UTC)

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
        const val APP_TAG_DID_NOT_BECOME_VISIBLE =
            "required app resource tag did not become visible"
        const val APP_DIALOG_SWIPE_FAILED = "content-free app dialog swipe failed"
        const val APP_TAG_CLICK_FAILED = "content-free app resource-tag click failed"
        const val EXPECTED_PACKAGE_NOT_FOCUSED = "expected fixed package did not receive focus"
        const val PICKER_RESOURCE_CLICK_FAILED = "fixed Photo Picker resource click failed"
        const val PICKER_TAB_STRUCTURE_UNEXPECTED = "fixed Photo Picker tab structure was unexpected"
        const val PICKER_DOWNLOADS_ALBUM_NOT_AVAILABLE =
            "fixed Photo Picker Downloads album was unavailable"
        const val EXACT_SYNTHETIC_THUMBNAIL_REQUIRED =
            "exact synthetic Photo Picker thumbnail was unavailable"
        const val PICKER_RESOURCE_SCHEMA_UNAVAILABLE =
            "required fixed Photo Picker string resources were unavailable"
        const val FIXTURE_INSERT_FAILED = "synthetic MediaStore fixture could not be inserted"
        const val FIXTURE_COPY_FAILED = "synthetic MediaStore fixture could not be copied"
        const val FIXTURE_EXIF_WRITE_FAILED = "synthetic MediaStore fixture EXIF write failed"
        const val FIXTURE_PUBLISH_FAILED = "synthetic MediaStore fixture could not be published"
        const val FIXTURE_READBACK_FAILED = "synthetic MediaStore fixture read-back was not exact"
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
