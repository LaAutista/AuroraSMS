// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aurorasms.app.appearance.AppearanceController
import org.aurorasms.core.model.ConversationId

/**
 * Debug-only owner for deterministic [AuroraSmsRoot] instrumentation. It never falls back to the
 * production container when a test harness has not been installed.
 */
class AuroraSmsRootTestActivity : ComponentActivity() {
    internal lateinit var harness: AuroraSmsRootTestHarness
        private set

    private var moveFocusToTestSinkAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installedHarness = AuroraSmsRootTestHarnessRegistry.installedOrNull()
        if (installedHarness == null) {
            finish()
            return
        }
        harness = installedHarness
        setContent {
            val focusRequester = remember { FocusRequester() }
            DisposableEffect(focusRequester) {
                val moveFocus: () -> Unit = { focusRequester.requestFocus() }
                moveFocusToTestSinkAction = moveFocus
                onDispose {
                    if (moveFocusToTestSinkAction === moveFocus) {
                        moveFocusToTestSinkAction = null
                    }
                }
            }
            val appearance by harness.appearanceController.state.collectAsStateWithLifecycle()
            Box {
                AuroraSmsTheme(profile = appearance.activeProfile) {
                    AuroraSmsRoot(
                        services = harness.services,
                        appearance = appearance,
                        appearanceController = harness.appearanceController,
                        pendingConversationId = harness.pendingConversationId,
                        diagnosticsAvailable = harness.diagnosticsAvailable,
                        contactsPermissionGranted = harness.contactsPermissionGranted,
                        onOpenDiagnostics = harness::recordDiagnosticsOpen,
                        onRequestContactsPermission = harness::recordContactsPermissionRequest,
                        onPendingConversationConsumed = harness::consumePendingConversation,
                        onOpenedConversationChanged = harness::recordOpenedConversation,
                        onInboxReady = harness::recordInboxReady,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .focusable(),
                )
            }
        }
    }

    internal fun moveFocusToTestSink() {
        checkNotNull(moveFocusToTestSinkAction) { "Compose test focus sink is not installed" }
            .invoke()
    }
}

/** In-process state required to render the real root through test-installed services. */
internal class AuroraSmsRootTestHarness(
    val services: AuroraSmsRootServices,
    val appearanceController: AppearanceController,
    pendingConversationId: ConversationId? = null,
    val diagnosticsAvailable: Boolean = false,
    val contactsPermissionGranted: Boolean = true,
    private val onClear: () -> Unit = {},
) {
    var pendingConversationId by mutableStateOf(pendingConversationId)
        private set

    @Volatile
    var openedConversationId: ConversationId? = null
        private set

    @Volatile
    var diagnosticsOpenCount: Int = 0
        private set

    @Volatile
    var contactsPermissionRequestCount: Int = 0
        private set

    @Volatile
    var pendingConversationConsumedCount: Int = 0
        private set

    @Volatile
    var inboxReadyCount: Int = 0
        private set

    fun openConversation(conversationId: ConversationId) {
        pendingConversationId = conversationId
    }

    internal fun recordDiagnosticsOpen() {
        diagnosticsOpenCount += 1
    }

    internal fun recordContactsPermissionRequest() {
        contactsPermissionRequestCount += 1
    }

    internal fun consumePendingConversation(conversationId: ConversationId) {
        if (pendingConversationId == conversationId) {
            pendingConversationId = null
            pendingConversationConsumedCount += 1
        }
    }

    internal fun recordOpenedConversation(conversationId: ConversationId?) {
        openedConversationId = conversationId
    }

    internal fun recordInboxReady() {
        inboxReadyCount += 1
    }

    internal fun clear() {
        onClear()
    }
}

/** Process-local installation point retained across Activity recreation and explicit test cleanup. */
internal object AuroraSmsRootTestHarnessRegistry {
    private val lock = Any()

    @Volatile
    private var installedHarness: AuroraSmsRootTestHarness? = null

    fun install(harness: AuroraSmsRootTestHarness) {
        synchronized(lock) {
            check(installedHarness == null) { "An AuroraSmsRoot test harness is already installed" }
            installedHarness = harness
        }
    }

    fun installedOrNull(): AuroraSmsRootTestHarness? = installedHarness

    fun clear() {
        val removed = synchronized(lock) {
            installedHarness.also { installedHarness = null }
        }
        removed?.clear()
    }
}
