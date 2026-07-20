// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aurorasms.app.diagnostics.BuildVariantDiagnosticsLauncher
import org.aurorasms.app.diagnostics.DiagnosticsLauncher
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.app.role.AndroidSmsRolePlatform
import org.aurorasms.app.role.DefaultSmsRoleCoordinator
import org.aurorasms.app.role.RoleOnboardingState
import org.aurorasms.app.role.UnsupportedReason
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.model.ConversationId

class MainActivity : ComponentActivity() {
    private lateinit var roleCoordinator: DefaultSmsRoleCoordinator
    private lateinit var diagnosticsLauncher: DiagnosticsLauncher
    private var roleState by mutableStateOf<RoleOnboardingState>(RoleOnboardingState.ReadyToRequest)
    private var permissionState by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var contactsPermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(Build.VERSION.SDK_INT < 33)
    private var notificationPermissionRequestedBefore by mutableStateOf(false)
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
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val decisionRecorded = if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionResultRecordsDecision(
                results = results,
                permission = Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            false
        }
        // The multiple-permission contract preserves the distinction that matters here:
        // an explicit choice contains true/false, while dismissing the dialog returns no entry.
        if (decisionRecorded) {
            notificationPermissionRequestedBefore = true
            notificationPermissionPreferences().edit {
                putBoolean(NOTIFICATION_PERMISSION_REQUESTED_BEFORE, true)
            }
        }
        refreshPermissionState()
    }

    override fun onStart() {
        super.onStart()
        (application as AuroraSmsApplication).container.onMessagingActivityStarted()
    }

    override fun onStop() {
        (application as AuroraSmsApplication).container.onMessagingActivityStopped()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleCoordinator = DefaultSmsRoleCoordinator(
            AndroidSmsRolePlatform(this) { intent -> roleRequestLauncher.launch(intent) },
        )
        diagnosticsLauncher = BuildVariantDiagnosticsLauncher(application)
        roleState = roleCoordinator.refresh()
        notificationPermissionRequestedBefore = notificationPermissionPreferences()
            .getBoolean(NOTIFICATION_PERMISSION_REQUESTED_BEFORE, false)
        refreshPermissionState()
        val rawConversationId = intent.getLongExtra(
            AppNotificationIntentFactory.EXTRA_CONVERSATION_ID,
            INVALID_CONVERSATION_ID,
        ).takeIf { it != INVALID_CONVERSATION_ID }
        val initialRouting = initialConversationRouting(
            action = intent.action,
            rawConversationId = rawConversationId,
            savedConversationId = savedInstanceState
                ?.takeIf { it.containsKey(STATE_OPEN_CONVERSATION) }
                ?.getLong(STATE_OPEN_CONVERSATION),
        )
        openedConversationId = initialRouting.openedConversationId
        pendingConversationId = initialRouting.pendingConversationId
        val appContainer = (application as AuroraSmsApplication).container

        setContent {
            val appearance by appContainer.appearanceController.state.collectAsStateWithLifecycle()
            AuroraSmsTheme(profile = appearance.activeProfile) {
                val savedBranches = rememberSaveableStateHolder()
                val messagingEligible = roleState == RoleOnboardingState.Held &&
                    permissionState[Manifest.permission.READ_SMS] == true
                if (showDiagnostics && diagnosticsLauncher.available) {
                    diagnosticsLauncher.Content { showDiagnostics = false }
                } else if (messagingEligible) {
                    savedBranches.SaveableStateProvider(ROOT_STATE_KEY) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                // Consumed here so nested root screens do not double their own
                                // safeDrawingPadding while this sibling notice stays unobscured.
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        ) {
                            NotificationPermissionNoticeHost(
                                notificationPermissionRequired = Build.VERSION.SDK_INT >= 33,
                                notificationPermissionGranted = notificationPermissionGranted,
                                onRecoverNotificationPermission = ::recoverNotificationPermission,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                AuroraSmsRoot(
                                    container = appContainer,
                                    appearance = appearance,
                                    appearanceController = appContainer.appearanceController,
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
                                    onPendingConversationConsumed = { consumedConversationId ->
                                        if (pendingConversationId == consumedConversationId) {
                                            pendingConversationId = null
                                            setIntent(
                                                Intent(this@MainActivity, MainActivity::class.java)
                                                    .setAction(Intent.ACTION_MAIN),
                                            )
                                        }
                                    },
                                    onOpenedConversationChanged = { conversationId ->
                                        appContainer.onConversationOpened(conversationId)
                                        if (pendingConversationId == null) {
                                            openedConversationId = conversationId
                                        }
                                    },
                                    onInboxReady = {
                                        if (!fullyDrawnReported) {
                                            fullyDrawnReported = true
                                            reportFullyDrawn()
                                        }
                                    },
                                )
                            }
                        }
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
                                recoverNotificationPermission()
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

    private fun recoverNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        when (
            notificationPermissionRecoveryAction(
                notificationPermissionRequired = true,
                notificationPermissionGranted = granted,
                requestedBefore = notificationPermissionRequestedBefore,
                shouldShowRationale = shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
        ) {
            NotificationPermissionRecoveryAction.NONE -> {
                notificationPermissionGranted = true
            }

            NotificationPermissionRecoveryAction.REQUEST_PERMISSION -> {
                notificationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                )
            }

            NotificationPermissionRecoveryAction.OPEN_SETTINGS -> openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(appNotificationSettingsIntent(packageName))
        } catch (_: ActivityNotFoundException) {
            openApplicationDetailsSettings()
        } catch (_: SecurityException) {
            openApplicationDetailsSettings()
        }
    }

    private fun notificationPermissionPreferences() = getSharedPreferences(
        NOTIFICATION_PERMISSION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun openApplicationDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The notice remains visible and messaging remains usable.
        } catch (_: SecurityException) {
            // The notice remains visible and messaging remains usable.
        }
    }

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
        const val NOTIFICATION_PERMISSION_PREFERENCES = "aurora_notification_permission_state"
        const val NOTIFICATION_PERMISSION_REQUESTED_BEFORE = "requested_before"
    }
}

@Composable
fun AuroraSmsTheme(
    profile: AuroraMaterialProfile = AuroraMaterialProfile.Default,
    content: @Composable () -> Unit,
) {
    AuroraMaterialTheme(profile = profile, content = content)
}

@Composable
internal fun NotificationPermissionNoticeHost(
    notificationPermissionRequired: Boolean,
    notificationPermissionGranted: Boolean,
    onRecoverNotificationPermission: () -> Unit,
) {
    if (notificationPermissionRequired && !notificationPermissionGranted) {
        NotificationPermissionNotice(onRecoverNotificationPermission)
    }
}

@Composable
private fun NotificationPermissionNotice(
    onRecoverNotificationPermission: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTagsAsResourceId = true }
            .testTag(NOTIFICATION_PERMISSION_NOTICE_TEST_TAG),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.notification_permission_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedButton(
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag(NOTIFICATION_PERMISSION_CTA_TEST_TAG),
                onClick = onRecoverNotificationPermission,
            ) {
                Text(stringResource(R.string.enable_notifications))
            }
        }
    }
}

internal enum class NotificationPermissionRecoveryAction {
    NONE,
    REQUEST_PERMISSION,
    OPEN_SETTINGS,
}

internal fun notificationPermissionRecoveryAction(
    notificationPermissionRequired: Boolean,
    notificationPermissionGranted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionRecoveryAction = when {
    !notificationPermissionRequired || notificationPermissionGranted ->
        NotificationPermissionRecoveryAction.NONE
    !requestedBefore || shouldShowRationale ->
        NotificationPermissionRecoveryAction.REQUEST_PERMISSION
    else -> NotificationPermissionRecoveryAction.OPEN_SETTINGS
}

internal fun notificationPermissionResultRecordsDecision(
    results: Map<String, Boolean>,
    permission: String,
): Boolean = results.containsKey(permission)

internal fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

internal const val NOTIFICATION_PERMISSION_NOTICE_TEST_TAG =
    "aurora-notification-permission-notice"
internal const val NOTIFICATION_PERMISSION_CTA_TEST_TAG =
    "aurora-notification-permission-cta"

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

internal class InitialConversationRouting(
    val openedConversationId: ConversationId?,
    val pendingConversationId: ConversationId?,
) {
    override fun toString(): String =
        "InitialConversationRouting(hasOpened=${openedConversationId != null}, " +
            "hasPending=${pendingConversationId != null}, REDACTED)"
}

internal fun initialConversationRouting(
    action: String?,
    rawConversationId: Long?,
    savedConversationId: Long?,
): InitialConversationRouting {
    val notificationConversation = notificationConversationId(action, rawConversationId)
    return InitialConversationRouting(
        openedConversationId = initialConversationId(
            action = action,
            rawConversationId = rawConversationId,
            savedConversationId = savedConversationId,
        ),
        pendingConversationId = notificationConversation,
    )
}

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
