// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.AuroraSmsTheme
import org.aurorasms.app.CONTACTS_PERMISSION_PREFERENCES
import org.aurorasms.app.CONTACTS_PERMISSION_REQUESTED_BEFORE
import org.aurorasms.app.ContactsPermissionRecoveryAction
import org.aurorasms.app.contactsPermissionRecoveryAction
import org.aurorasms.app.contactsPermissionResultRecordsDecision

class ComposeMessageActivity : ComponentActivity() {
    private val requestState = MutableStateFlow<ExternalComposeState>(
        ExternalComposeState.Loading(generation = 0L),
    )
    private var parseJob: Job? = null
    private var requestGeneration: Long = 0L
    private var contactsPermissionGranted by mutableStateOf(false)
    private var contactsPermissionRequestedBefore = false
    private var lastReportedContactsPermission: Boolean? = null

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (contactsPermissionResultRecordsDecision(results, Manifest.permission.READ_CONTACTS)) {
            contactsPermissionRequestedBefore = true
            contactsPermissionPreferences().edit {
                putBoolean(CONTACTS_PERMISSION_REQUESTED_BEFORE, true)
            }
        }
        refreshContactsPermissionState()
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
        val appContainer = (application as AuroraSmsApplication).container
        contactsPermissionRequestedBefore = contactsPermissionPreferences()
            .getBoolean(CONTACTS_PERMISSION_REQUESTED_BEFORE, false)
        refreshContactsPermissionState()
        val restoredGeneration = savedInstanceState
            ?.getLong(REQUEST_GENERATION_STATE_KEY)
            ?.takeIf { it > 0L }
        acceptIntent(
            intent = intent,
            generation = restoredGeneration ?: nextRequestGeneration(),
        )

        setContent {
            val appearance by appContainer.appearanceController.state.collectAsStateWithLifecycle()
            val externalRequest by requestState.collectAsStateWithLifecycle()
            AuroraSmsTheme(profile = appearance.activeProfile) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val request = externalRequest) {
                        is ExternalComposeState.Loading -> key(request.generation) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(EXTERNAL_COMPOSE_LOADING_TEST_TAG),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        is ExternalComposeState.Invalid -> key(request.generation) {
                            NewMessageRoute(
                                container = appContainer,
                                initialRequest = null,
                                invalidExternalRequest = true,
                                contactsPermissionGranted = contactsPermissionGranted,
                                onRequestContactsPermission = ::recoverContactsPermission,
                                onBack = ::finish,
                            )
                        }
                        is ExternalComposeState.Ready -> key(request.generation) {
                            NewMessageRoute(
                                container = appContainer,
                                initialRequest = request.request,
                                invalidExternalRequest = false,
                                contactsPermissionGranted = contactsPermissionGranted,
                                onRequestContactsPermission = ::recoverContactsPermission,
                                onBack = ::finish,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent, nextRequestGeneration())
    }

    override fun onResume() {
        super.onResume()
        refreshContactsPermissionState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(REQUEST_GENERATION_STATE_KEY, requestGeneration)
        super.onSaveInstanceState(outState)
    }

    private fun nextRequestGeneration(): Long = requestGeneration + 1L

    private fun acceptIntent(intent: Intent, generation: Long) {
        val sourceIntent = Intent(intent)
        requestGeneration = generation
        parseJob?.cancel()
        requestState.value = ExternalComposeState.Loading(generation)
        parseJob = lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                try {
                    ExternalComposeRequestParser.parse(
                        action = sourceIntent.action,
                        dataUri = sourceIntent.dataString,
                        smsBody = sourceIntent.getCharSequenceExtra("sms_body"),
                        extraText = sourceIntent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                    )?.let { ExternalComposeState.Ready(generation, it) }
                        ?: ExternalComposeState.Invalid(generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    ExternalComposeState.Invalid(generation)
                }
            }
            if (generation == requestGeneration) {
                requestState.value = parsed
            }
        }
    }

    private fun refreshContactsPermissionState() {
        contactsPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        val granted = contactsPermissionGranted
        if (lastReportedContactsPermission != granted) {
            lastReportedContactsPermission = granted
            (application as? AuroraSmsApplication)?.container?.onContactsPermissionChanged()
        }
    }

    private fun recoverContactsPermission() {
        refreshContactsPermissionState()
        when (
            contactsPermissionRecoveryAction(
                contactsPermissionGranted = contactsPermissionGranted,
                requestedBefore = contactsPermissionRequestedBefore,
                shouldShowRationale = shouldShowRequestPermissionRationale(
                    Manifest.permission.READ_CONTACTS,
                ),
            )
        ) {
            ContactsPermissionRecoveryAction.NONE -> contactsPermissionGranted = true
            ContactsPermissionRecoveryAction.REQUEST_PERMISSION ->
                contactsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            ContactsPermissionRecoveryAction.OPEN_SETTINGS -> openApplicationDetailsSettings()
        }
    }

    private fun contactsPermissionPreferences() = getSharedPreferences(
        CONTACTS_PERMISSION_PREFERENCES,
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
            // The unavailable panel remains visible and manual entry remains usable.
        } catch (_: SecurityException) {
            // The unavailable panel remains visible and manual entry remains usable.
        }
    }
}

private sealed interface ExternalComposeState {
    val generation: Long

    data class Loading(override val generation: Long) : ExternalComposeState

    data class Invalid(override val generation: Long) : ExternalComposeState

    data class Ready(
        override val generation: Long,
        val request: ComposeRequest,
    ) : ExternalComposeState {
        override fun toString(): String = "ExternalComposeState.Ready(REDACTED)"
    }
}

internal const val EXTERNAL_COMPOSE_LOADING_TEST_TAG = "aurora-external-compose-loading"
private const val REQUEST_GENERATION_STATE_KEY = "external_compose_request_generation"
