// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.aurorasms.app.diagnostics.BuildVariantDiagnosticsLauncher
import org.aurorasms.app.diagnostics.DiagnosticsLauncher
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.app.role.AndroidSmsRolePlatform
import org.aurorasms.app.role.DefaultSmsRoleCoordinator
import org.aurorasms.app.role.RoleOnboardingState
import org.aurorasms.app.role.UnsupportedReason
import org.aurorasms.core.model.ConversationId

class MainActivity : ComponentActivity() {
    private lateinit var roleCoordinator: DefaultSmsRoleCoordinator
    private lateinit var diagnosticsLauncher: DiagnosticsLauncher
    private var roleState by mutableStateOf<RoleOnboardingState>(RoleOnboardingState.ReadyToRequest)
    private var permissionState by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var contactsPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(Build.VERSION.SDK_INT < 33)
    private var lastReportedMessagingEligibility: Boolean? = null
    private var lastReportedContactsPermission: Boolean? = null
    private var fullyDrawnReported = false
    private var showDiagnostics by mutableStateOf(false)
    private var pendingConversationId by mutableStateOf<ConversationId?>(null)
    internal var openedConversationId by mutableStateOf<ConversationId?>(null)
        private set

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleState = roleCoordinator.onRoleRequestResult()
        refreshPermissionState()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshPermissionState()
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsPermissionGranted = granted || isPermissionGranted(Manifest.permission.READ_CONTACTS)
        relayContactsPermissionIfChanged()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleCoordinator = DefaultSmsRoleCoordinator(
            AndroidSmsRolePlatform(this) { intent -> roleRequestLauncher.launch(intent) },
        )
        diagnosticsLauncher = BuildVariantDiagnosticsLauncher(application)
        roleState = roleCoordinator.refresh()
        refreshPermissionState()
        val initialConversation = initialConversationId(
            action = intent.action,
            rawConversationId = intent.getLongExtra(
                AppNotificationIntentFactory.EXTRA_CONVERSATION_ID,
                INVALID_CONVERSATION_ID,
            ).takeIf { it != INVALID_CONVERSATION_ID },
            savedConversationId = savedInstanceState
                ?.takeIf { it.containsKey(STATE_OPEN_CONVERSATION) }
                ?.getLong(STATE_OPEN_CONVERSATION),
        )
        openedConversationId = initialConversation
        pendingConversationId = initialConversation

        setContent {
            AuroraSmsTheme {
                val savedBranches = rememberSaveableStateHolder()
                val messagingEligible = roleState == RoleOnboardingState.Held &&
                    permissionState[Manifest.permission.READ_SMS] == true
                if (showDiagnostics && diagnosticsLauncher.available) {
                    diagnosticsLauncher.Content { showDiagnostics = false }
                } else if (messagingEligible) {
                    savedBranches.SaveableStateProvider(ROOT_STATE_KEY) {
                        AuroraSmsRoot(
                            container = (application as AuroraSmsApplication).container,
                            pendingConversationId = pendingConversationId,
                            diagnosticsAvailable = diagnosticsLauncher.available,
                            contactsPermissionGranted = contactsPermissionGranted,
                            onOpenDiagnostics = { showDiagnostics = true },
                            onRequestContactsPermission = {
                                if (roleCoordinator.refresh() == RoleOnboardingState.Held) {
                                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                } else {
                                    roleState = roleCoordinator.state
                                }
                            },
                            onPendingConversationConsumed = {
                                pendingConversationId = null
                                setIntent(
                                    Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
                                )
                            },
                            onOpenedConversationChanged = { openedConversationId = it },
                            onInboxReady = {
                                if (!fullyDrawnReported) {
                                    fullyDrawnReported = true
                                    reportFullyDrawn()
                                }
                            },
                        )
                    }
                } else {
                    OnboardingScreen(
                        roleState = roleState,
                        grantedPermissions = permissionState.values.count { it },
                        requiredPermissions = permissionState.size,
                        notificationPermissionRequired = Build.VERSION.SDK_INT >= 33,
                        notificationPermissionGranted = notificationPermissionGranted,
                        diagnosticsAvailable = diagnosticsLauncher.available,
                        onRequestRole = { roleState = roleCoordinator.requestRole() },
                        onRequestPermissions = {
                            if (roleCoordinator.refresh() == RoleOnboardingState.Held) {
                                permissionLauncher.launch(requiredMessagingPermissions().toTypedArray())
                            } else {
                                roleState = roleCoordinator.state
                            }
                        },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                roleCoordinator.refresh() == RoleOnboardingState.Held
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenDiagnostics = { showDiagnostics = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDiagnostics = false
        val conversation = notificationConversationId(
            action = intent.action,
            rawConversationId = intent.getLongExtra(
                AppNotificationIntentFactory.EXTRA_CONVERSATION_ID,
                INVALID_CONVERSATION_ID,
            ).takeIf { it != INVALID_CONVERSATION_ID },
        )
        openedConversationId = conversation
        pendingConversationId = conversation
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(
            STATE_OPEN_CONVERSATION,
            openedConversationId?.value ?: INVALID_CONVERSATION_ID,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::roleCoordinator.isInitialized) {
            roleState = roleCoordinator.refresh()
            refreshPermissionState()
        }
    }

    private fun refreshPermissionState() {
        permissionState = requiredMessagingPermissions().associateWith(::isPermissionGranted)
        contactsPermissionGranted = isPermissionGranted(Manifest.permission.READ_CONTACTS)
        notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        relayMessagingEligibilityIfChanged()
        relayContactsPermissionIfChanged()
    }

    private fun relayMessagingEligibilityIfChanged() {
        if (!::roleCoordinator.isInitialized) return
        val eligible = roleState == RoleOnboardingState.Held && isPermissionGranted(Manifest.permission.READ_SMS)
        if (lastReportedMessagingEligibility == eligible) return
        lastReportedMessagingEligibility = eligible
        (application as? AuroraSmsApplication)?.container?.onMessagingEligibilityChanged()
    }

    private fun relayContactsPermissionIfChanged() {
        val granted = contactsPermissionGranted
        if (lastReportedContactsPermission == granted) return
        lastReportedContactsPermission = granted
        (application as? AuroraSmsApplication)?.container?.onContactsPermissionChanged()
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requiredMessagingPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.RECEIVE_MMS)
        add(Manifest.permission.RECEIVE_WAP_PUSH)
        add(Manifest.permission.READ_PHONE_STATE)
    }

    private companion object {
        const val ROOT_STATE_KEY = "aurora.root"
        const val STATE_OPEN_CONVERSATION = "aurora.state.OPEN_CONVERSATION"
        const val INVALID_CONVERSATION_ID = -1L
    }
}

@Composable
fun AuroraSmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF78D6C6),
            secondary = Color(0xFF9CB8FF),
            background = Color(0xFF101419),
            surface = Color(0xFF171C22),
        ),
        content = content,
    )
}

@Composable
private fun OnboardingScreen(
    roleState: RoleOnboardingState,
    grantedPermissions: Int,
    requiredPermissions: Int,
    notificationPermissionRequired: Boolean,
    notificationPermissionGranted: Boolean,
    diagnosticsAvailable: Boolean,
    onRequestRole: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = roleStateDescription(roleState),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))

            if (roleState is RoleOnboardingState.ReadyToRequest ||
                roleState is RoleOnboardingState.Cancelled
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestRole,
                ) {
                    Text(stringResource(R.string.request_sms_role))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (roleState is RoleOnboardingState.Held) {
                Text(
                    text = stringResource(
                        R.string.permission_summary,
                        grantedPermissions,
                        requiredPermissions,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                if (grantedPermissions < requiredPermissions) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRequestPermissions,
                    ) {
                        Text(stringResource(R.string.grant_messaging_permissions))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (notificationPermissionRequired && !notificationPermissionGranted) {
                    Text(stringResource(R.string.notification_permission_explanation))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRequestNotificationPermission,
                    ) {
                        Text(stringResource(R.string.enable_notifications))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (diagnosticsAvailable) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenDiagnostics,
                ) {
                    Text(stringResource(R.string.open_diagnostics))
                }
            }
        }
    }
}

internal fun notificationConversationId(
    action: String?,
    rawConversationId: Long?,
): ConversationId? = rawConversationId
    ?.takeIf { action == AppNotificationIntentFactory.ACTION_OPEN_CONVERSATION && it > 0L }
    ?.let(::ConversationId)

internal fun initialConversationId(
    action: String?,
    rawConversationId: Long?,
    savedConversationId: Long?,
): ConversationId? = notificationConversationId(action, rawConversationId)
    ?: savedConversationId?.takeIf { it > 0L }?.let(::ConversationId)

@Composable
private fun roleStateDescription(state: RoleOnboardingState): String = when (state) {
    RoleOnboardingState.ReadyToRequest -> stringResource(R.string.role_ready)
    RoleOnboardingState.Requesting -> stringResource(R.string.role_requesting)
    RoleOnboardingState.Held -> stringResource(R.string.role_held)
    RoleOnboardingState.Cancelled -> stringResource(R.string.role_cancelled)
    is RoleOnboardingState.Unsupported -> when (state.reason) {
        UnsupportedReason.TELEPHONY_MISSING -> stringResource(R.string.telephony_missing)
        UnsupportedReason.MESSAGING_MISSING -> stringResource(R.string.messaging_missing)
        UnsupportedReason.ROLE_UNAVAILABLE -> stringResource(R.string.role_unavailable)
    }
}
