// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import org.aurorasms.app.MainActivity
import org.aurorasms.feature.conversations.INBOX_MORE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG
import org.aurorasms.feature.conversations.INBOX_SCREEN_TEST_TAG
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner-invoked physical smoke for the real app route.
 *
 * This test deliberately reads only accessibility package names and Compose test tags exposed as
 * view IDs. It never reads node text/content descriptions, emits a hierarchy, captures a screen,
 * opens a conversation, edits an assignment, or invokes a carrier action.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityScopedAppearancePhysicalSmokeTest {
    @Test
    fun realInboxScopedModalReceivesWindowFocusAndCancelPreservesInbox() {
        requireExplicitPhysicalGate()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val originalServiceFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)

        try {
            var initialActivity: MainActivity? = null
            scenario.onActivity { activity ->
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) {
                    failFixed(INBOX_ROUTE_REQUIRED)
                }
                initialActivity = activity
            }

            val inboxWindowId = waitForTag(automation, INBOX_SCREEN_TEST_TAG).useNode { node ->
                node.windowId
            }
            assertFocusedApplicationWindow(automation, inboxWindowId)

            clickTag(automation, INBOX_MORE_ACTION_TEST_TAG)
            clickTag(automation, INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG)

            val dialogWindowId = waitForTag(
                automation,
                SCOPED_APPEARANCE_DIALOG_TEST_TAG,
            ).useNode { node -> node.windowId }
            if (dialogWindowId == inboxWindowId) {
                failFixed(DIALOG_WINDOW_REQUIRED)
            }
            assertFocusedApplicationWindow(automation, dialogWindowId)

            clickTag(automation, SCOPED_APPEARANCE_CANCEL_TEST_TAG)
            waitForTagToDisappear(automation, SCOPED_APPEARANCE_DIALOG_TEST_TAG)

            val restoredInboxWindowId = waitForTag(
                automation,
                INBOX_SCREEN_TEST_TAG,
            ).useNode { node -> node.windowId }
            if (restoredInboxWindowId != inboxWindowId) {
                failFixed(INBOX_WINDOW_CHANGED)
            }
            assertFocusedApplicationWindow(automation, restoredInboxWindowId)

            scenario.onActivity { activity ->
                if (activity !== initialActivity) {
                    failFixed(ACTIVITY_RECREATED)
                }
                if (activity.componentName.className != MainActivity::class.java.name) {
                    failFixed(REAL_ACTIVITY_REQUIRED)
                }
                if (activity.openedConversationId != null) {
                    failFixed(INBOX_ROUTE_CHANGED)
                }
            }
        } finally {
            scenario.close()
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = originalServiceFlags
            }
        }
    }

    private fun requireExplicitPhysicalGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(PHYSICAL_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(PHYSICAL_GATE_REQUIRED, enabled)
    }

    private fun clickTag(
        automation: UiAutomation,
        tag: String,
    ) {
        val clicked = waitForTag(automation, tag).useNode { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!clicked) failFixed(TAG_CLICK_FAILED)
    }

    private fun waitForTag(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            findTagInActiveWindow(automation, tag)?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(TAG_NOT_AVAILABLE)
    }

    private fun waitForTagToDisappear(
        automation: UiAutomation,
        tag: String,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val found = findTagInActiveWindow(automation, tag)
            if (found == null) return
            found.recycleSafely()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(TAG_REMAINED_AVAILABLE)
    }

    private fun findTagInActiveWindow(
        automation: UiAutomation,
        tag: String,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
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

    private fun assertFocusedApplicationWindow(
        automation: UiAutomation,
        expectedWindowId: Int,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        var windowWasAvailable = false
        var rootWasAvailable = false
        do {
            val windows = automation.windows
            try {
                val expected = windows.firstOrNull { it.id == expectedWindowId }
                if (expected != null) {
                    windowWasAvailable = true
                    val root = expected.root
                    if (root != null) {
                        rootWasAvailable = true
                        val packageMatches = try {
                            root.packageName?.toString() == TARGET_PACKAGE
                        } finally {
                            root.recycleSafely()
                        }
                        if (
                            packageMatches &&
                            expected.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            expected.isActive &&
                            expected.isFocused
                        ) {
                            return
                        }
                    }
                }
            } finally {
                windows.forEach { window -> window.recycleSafely() }
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)

        when {
            !windowWasAvailable -> failFixed(EXPECTED_WINDOW_NOT_AVAILABLE)
            !rootWasAvailable -> failFixed(EXPECTED_WINDOW_ROOT_NOT_AVAILABLE)
            else -> failFixed(EXPECTED_WINDOW_NOT_FOCUSED)
        }
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

    private fun failFixed(message: String): Nothing = throw AssertionError(message)

    private companion object {
        const val PHYSICAL_GATE_ARGUMENT = "auroraPhysicalScopedAppearanceSmoke"
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 50L

        const val PHYSICAL_GATE_REQUIRED = "physical smoke gate was not explicitly enabled"
        const val REAL_ACTIVITY_REQUIRED = "real MainActivity was not launched"
        const val INBOX_ROUTE_REQUIRED = "physical smoke did not start on Inbox"
        const val INBOX_ROUTE_CHANGED = "physical smoke left the Inbox route"
        const val TAG_NOT_AVAILABLE = "required content-free resource tag was not available"
        const val TAG_REMAINED_AVAILABLE = "dismissed content-free resource tag remained available"
        const val TAG_CLICK_FAILED = "content-free resource-tag click failed"
        const val DIALOG_WINDOW_REQUIRED = "scoped modal did not create a distinct window"
        const val EXPECTED_WINDOW_NOT_AVAILABLE = "expected application window was not available"
        const val EXPECTED_WINDOW_ROOT_NOT_AVAILABLE = "expected application window root was absent"
        const val EXPECTED_WINDOW_NOT_FOCUSED = "expected application window was not active and focused"
        const val INBOX_WINDOW_CHANGED = "Inbox application window changed during modal cancellation"
        const val ACTIVITY_RECREATED = "MainActivity was recreated during modal cancellation"
    }
}
