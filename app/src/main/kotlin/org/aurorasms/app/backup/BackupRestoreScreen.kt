// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurorasms.app.R
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
import org.aurorasms.feature.backup.AuroraBackupArchive
import org.aurorasms.feature.backup.AuroraBackupDocumentController
import org.aurorasms.feature.backup.AuroraBackupExportFailure
import org.aurorasms.feature.backup.AuroraBackupExportResult
import org.aurorasms.feature.backup.AuroraBackupFailure
import org.aurorasms.feature.backup.AuroraBackupStagingSession
import org.aurorasms.feature.backup.AuroraBackupStartupRecoveryResult
import org.aurorasms.feature.backup.AuroraBackupSummary
import org.aurorasms.feature.backup.AuroraRestoreConfirmationResult
import org.aurorasms.feature.backup.AuroraRestoreFailure
import org.aurorasms.feature.backup.AuroraRestoreReviewResult
import org.aurorasms.feature.backup.AuroraRestoreSelectionFailure
import org.aurorasms.feature.backup.AuroraRestoreSelectionResult
import org.aurorasms.feature.backup.AuroraRestoreSummary

@Composable
internal fun BackupRestoreScreen(
    controller: AuroraBackupDocumentController,
    startupRecovery: AuroraBackupStartupRecoveryResult?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val disposalCleanupScope = remember(controller) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf<BackupRestoreUiState>(BackupRestoreUiState.Idle) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingExportSecret by remember { mutableStateOf<CharArray?>(null) }
    var operationEpoch by remember { mutableLongStateOf(0L) }

    fun clearPassphrases() {
        passphrase = ""
        confirmation = ""
        pendingExportSecret?.let { Arrays.fill(it, '\u0000') }
        pendingExportSecret = null
    }

    fun cancelRestoreAndReturnIdle() {
        operationEpoch += 1L
        clearPassphrases()
        state = BackupRestoreUiState.Idle
        scope.launch(Dispatchers.IO) { controller.cancelRestore() }
    }

    fun leaveScreen() {
        operationEpoch += 1L
        clearPassphrases()
        state = BackupRestoreUiState.Working(BackupOperation.CLEANUP)
        scope.launch {
            withContext(Dispatchers.IO + NonCancellable) { controller.cancelRestore() }
            onBack()
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AURORA_BACKUP_MIME_TYPE),
    ) { destination: Uri? ->
        val secret = pendingExportSecret
        pendingExportSecret = null
        if (destination == null || secret == null) {
            secret?.let { Arrays.fill(it, '\u0000') }
            state = BackupRestoreUiState.Idle
        } else {
            val epoch = operationEpoch + 1L
            operationEpoch = epoch
            state = BackupRestoreUiState.Working(BackupOperation.EXPORT)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        controller.export(destination, secret)
                    } finally {
                        Arrays.fill(secret, '\u0000')
                    }
                }
                if (epoch != operationEpoch) return@launch
                state = when (result) {
                    is AuroraBackupExportResult.Success ->
                        BackupRestoreUiState.ExportComplete(result.summary)
                    is AuroraBackupExportResult.Failed -> BackupRestoreUiState.ExportFailed(
                        result.reason,
                        incompleteDestinationMayRemain = !result.incompleteDestinationRemoved,
                    )
                }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source: Uri? ->
        if (source == null) {
            state = BackupRestoreUiState.Idle
        } else {
            val epoch = operationEpoch + 1L
            operationEpoch = epoch
            state = BackupRestoreUiState.Working(BackupOperation.SELECT_RESTORE)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    controller.selectRestoreSource(source)
                }
                if (epoch != operationEpoch) return@launch
                state = when (result) {
                    is AuroraRestoreSelectionResult.Success ->
                        BackupRestoreUiState.RestorePassphrase(
                            session = result.session,
                            encryptedBytes = result.encryptedBytes,
                        )
                    is AuroraRestoreSelectionResult.Failed ->
                        BackupRestoreUiState.SelectionFailed(result.reason)
                }
            }
        }
    }

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && state.requiresBackgroundCancellation()) {
                cancelRestoreAndReturnIdle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clearPassphrases()
            disposalCleanupScope.launch {
                try {
                    controller.cancelRestore()
                } finally {
                    disposalCleanupScope.cancel()
                }
            }
        }
    }
    BackHandler(onBack = ::leaveScreen)

    BackupRestoreContent(
        state = state,
        startupRecovery = startupRecovery,
        passphrase = passphrase,
        confirmation = confirmation,
        onPassphraseChanged = { value ->
            if (value.length <= AuroraBackupArchive.MAXIMUM_PASSPHRASE_CHARACTERS) {
                passphrase = value
            }
        },
        onConfirmationChanged = { value ->
            if (value.length <= AuroraBackupArchive.MAXIMUM_PASSPHRASE_CHARACTERS) {
                confirmation = value
            }
        },
        onBeginExport = {
            clearPassphrases()
            state = BackupRestoreUiState.ExportPassphrase
        },
        onChooseExportDestination = {
            val secret = passphrase.toCharArray()
            if (!AuroraBackupArchive.passphraseMeetsPolicy(secret) || passphrase != confirmation) {
                Arrays.fill(secret, '\u0000')
                state = BackupRestoreUiState.ExportPassphraseInvalid
            } else {
                clearPassphrases()
                pendingExportSecret = secret
                createDocument.launch(defaultBackupFileName())
            }
        },
        onBeginRestore = {
            clearPassphrases()
            state = BackupRestoreUiState.Idle
            openDocument.launch(arrayOf(AURORA_BACKUP_MIME_TYPE, OCTET_STREAM_MIME_TYPE))
        },
        onAuthenticateRestore = { session ->
            val encryptedBytes = (state as? BackupRestoreUiState.RestorePassphrase)
                ?.encryptedBytes ?: 0L
            val secret = passphrase.toCharArray()
            passphrase = ""
            val epoch = operationEpoch + 1L
            operationEpoch = epoch
            state = BackupRestoreUiState.Working(BackupOperation.AUTHENTICATE_RESTORE)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        controller.authenticateRestore(session, secret)
                    } finally {
                        Arrays.fill(secret, '\u0000')
                    }
                }
                if (epoch != operationEpoch) return@launch
                state = when (result) {
                    is AuroraRestoreReviewResult.Success -> BackupRestoreUiState.Review(
                        result.session,
                        result.summary,
                    )
                    is AuroraRestoreReviewResult.Failed -> BackupRestoreUiState.RestorePassphrase(
                        session = session,
                        encryptedBytes = encryptedBytes,
                        failure = result.reason,
                    )
                    AuroraRestoreReviewResult.NoActiveSelection ->
                        BackupRestoreUiState.SelectionFailed(
                            AuroraRestoreSelectionFailure.PRIVATE_STORAGE_FAILURE,
                        )
                }
            }
        },
        onConfirmRestore = { session ->
            val epoch = operationEpoch + 1L
            operationEpoch = epoch
            state = BackupRestoreUiState.Working(BackupOperation.RESTORE)
            scope.launch {
                val result = withContext(Dispatchers.IO) { controller.confirmRestore(session) }
                if (epoch != operationEpoch) return@launch
                state = when (result) {
                    is AuroraRestoreConfirmationResult.Success ->
                        BackupRestoreUiState.RestoreComplete(result.summary)
                    is AuroraRestoreConfirmationResult.Failed -> BackupRestoreUiState.RestoreFailed(
                        result.reason,
                        result.rollbackComplete,
                    )
                    AuroraRestoreConfirmationResult.NoValidatedArchive ->
                        BackupRestoreUiState.SelectionFailed(
                            AuroraRestoreSelectionFailure.PRIVATE_STORAGE_FAILURE,
                        )
                }
            }
        },
        onCancel = ::cancelRestoreAndReturnIdle,
        onBack = ::leaveScreen,
    )
}

@Composable
internal fun BackupRestoreContent(
    state: BackupRestoreUiState,
    startupRecovery: AuroraBackupStartupRecoveryResult?,
    passphrase: String,
    confirmation: String,
    onPassphraseChanged: (String) -> Unit,
    onConfirmationChanged: (String) -> Unit,
    onBeginExport: () -> Unit,
    onChooseExportDestination: () -> Unit,
    onBeginRestore: () -> Unit,
    onAuthenticateRestore: (AuroraBackupStagingSession) -> Unit,
    onConfirmRestore: (AuroraBackupStagingSession) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val visuals = LocalAuroraVisualTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(BACKUP_RESTORE_SCREEN_TEST_TAG),
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuroraIconAction(
                        glyph = AuroraGlyph.BACK,
                        contentDescription = stringResource(R.string.backup_restore_back),
                        onClick = onBack,
                        modifier = Modifier.testTag(BACKUP_RESTORE_BACK_TEST_TAG),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.backup_restore_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_subtitle),
                            color = visuals.lilacSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (
                startupRecovery?.restoreFailure != null ||
                startupRecovery?.stagingCleanupSucceeded == false
            ) {
                item {
                    NoticeCard(
                        text = stringResource(R.string.backup_restore_recovery_warning),
                        warning = true,
                    )
                }
            }
            item {
                when {
                    startupRecovery == null -> WorkingContent(BackupOperation.STARTUP_RECOVERY)
                    startupRecovery.restoreFailure != null ||
                        !startupRecovery.stagingCleanupSucceeded -> RecoveryBlockedContent()
                    else -> when (state) {
                        BackupRestoreUiState.Idle -> IdleContent(onBeginExport, onBeginRestore)
                        BackupRestoreUiState.ExportPassphrase,
                        BackupRestoreUiState.ExportPassphraseInvalid,
                        -> ExportPassphraseContent(
                            passphrase = passphrase,
                            confirmation = confirmation,
                            invalid = state == BackupRestoreUiState.ExportPassphraseInvalid,
                            onPassphraseChanged = onPassphraseChanged,
                            onConfirmationChanged = onConfirmationChanged,
                            onChooseDestination = onChooseExportDestination,
                            onCancel = onCancel,
                        )
                        is BackupRestoreUiState.RestorePassphrase -> RestorePassphraseContent(
                            state = state,
                            passphrase = passphrase,
                            onPassphraseChanged = onPassphraseChanged,
                            onAuthenticate = { onAuthenticateRestore(state.session) },
                            onCancel = onCancel,
                        )
                        is BackupRestoreUiState.Review -> RestoreReviewContent(
                            state = state,
                            onConfirm = { onConfirmRestore(state.session) },
                            onCancel = onCancel,
                        )
                        is BackupRestoreUiState.Working -> WorkingContent(state.operation)
                        is BackupRestoreUiState.ExportComplete -> ExportCompleteContent(
                            state.summary,
                            onCancel,
                        )
                        is BackupRestoreUiState.RestoreComplete -> RestoreCompleteContent(
                            state.summary,
                            onCancel,
                        )
                        is BackupRestoreUiState.ExportFailed -> FailureContent(
                            message = exportFailureMessage(state.reason),
                            extraWarning = if (state.incompleteDestinationMayRemain) {
                                stringResource(R.string.backup_restore_incomplete_file_warning)
                            } else {
                                null
                            },
                            onDone = onCancel,
                        )
                        is BackupRestoreUiState.SelectionFailed -> FailureContent(
                            message = selectionFailureMessage(state.reason),
                            extraWarning = null,
                            onDone = onCancel,
                        )
                        is BackupRestoreUiState.RestoreFailed -> FailureContent(
                            message = restoreFailureMessage(state.reason),
                            extraWarning = if (!state.rollbackComplete) {
                                stringResource(R.string.backup_restore_rollback_warning)
                            } else {
                                null
                            },
                            onDone = onCancel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryBlockedContent() {
    WorkflowCard(title = stringResource(R.string.backup_restore_paused_title)) {
        Text(stringResource(R.string.backup_restore_paused_explanation))
    }
}

@Composable
private fun IdleContent(onBeginExport: () -> Unit, onBeginRestore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ActionCard(
            title = stringResource(R.string.backup_create_title),
            explanation = stringResource(R.string.backup_create_explanation),
            button = stringResource(R.string.backup_create_action),
            buttonTag = BACKUP_CREATE_TEST_TAG,
            onClick = onBeginExport,
        )
        ActionCard(
            title = stringResource(R.string.restore_title),
            explanation = stringResource(R.string.restore_explanation),
            button = stringResource(R.string.restore_choose_action),
            buttonTag = RESTORE_CHOOSE_TEST_TAG,
            onClick = onBeginRestore,
        )
        NoticeCard(stringResource(R.string.backup_restore_exclusions), warning = false)
    }
}

@Composable
private fun ExportPassphraseContent(
    passphrase: String,
    confirmation: String,
    invalid: Boolean,
    onPassphraseChanged: (String) -> Unit,
    onConfirmationChanged: (String) -> Unit,
    onChooseDestination: () -> Unit,
    onCancel: () -> Unit,
) {
    WorkflowCard(title = stringResource(R.string.backup_passphrase_title)) {
        Text(stringResource(R.string.backup_passphrase_warning))
        SecretField(
            value = passphrase,
            label = stringResource(R.string.backup_passphrase),
            tag = BACKUP_PASSPHRASE_TEST_TAG,
            onValueChanged = onPassphraseChanged,
        )
        SecretField(
            value = confirmation,
            label = stringResource(R.string.backup_passphrase_confirm),
            tag = BACKUP_PASSPHRASE_CONFIRM_TEST_TAG,
            onValueChanged = onConfirmationChanged,
        )
        if (invalid) {
            Text(
                text = stringResource(R.string.backup_passphrase_invalid),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onChooseDestination,
                modifier = Modifier.testTag(BACKUP_CHOOSE_DESTINATION_TEST_TAG),
            ) {
                Text(stringResource(R.string.backup_choose_destination))
            }
        }
    }
}

@Composable
private fun RestorePassphraseContent(
    state: BackupRestoreUiState.RestorePassphrase,
    passphrase: String,
    onPassphraseChanged: (String) -> Unit,
    onAuthenticate: () -> Unit,
    onCancel: () -> Unit,
) {
    WorkflowCard(title = stringResource(R.string.restore_passphrase_title)) {
        if (state.encryptedBytes > 0L) {
            Text(stringResource(R.string.restore_selected_size, formatBytes(state.encryptedBytes)))
        }
        Text(stringResource(R.string.restore_passphrase_explanation))
        SecretField(
            value = passphrase,
            label = stringResource(R.string.backup_passphrase),
            tag = RESTORE_PASSPHRASE_TEST_TAG,
            onValueChanged = onPassphraseChanged,
        )
        state.failure?.let { failure ->
            Text(
                text = authenticationFailureMessage(failure),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onAuthenticate,
                modifier = Modifier.testTag(RESTORE_AUTHENTICATE_TEST_TAG),
            ) {
                Text(stringResource(R.string.restore_review_action))
            }
        }
    }
}

@Composable
private fun RestoreReviewContent(
    state: BackupRestoreUiState.Review,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    WorkflowCard(title = stringResource(R.string.restore_review_title)) {
        SummaryLines(state.summary)
        NoticeCard(stringResource(R.string.restore_review_duplicate_note), warning = false)
        NoticeCard(stringResource(R.string.restore_review_send_safety), warning = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag(RESTORE_CONFIRM_TEST_TAG),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.restore_confirm_action,
                        (state.summary.smsCount + state.summary.mmsCount).toInt(),
                        state.summary.smsCount + state.summary.mmsCount,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WorkingContent(operation: BackupOperation) {
    WorkflowCard(title = stringResource(operation.titleResource)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.backup_restore_keep_open))
    }
}

@Composable
private fun ExportCompleteContent(summary: AuroraBackupSummary, onDone: () -> Unit) {
    WorkflowCard(title = stringResource(R.string.backup_complete_title)) {
        SummaryLines(summary)
        Text(stringResource(R.string.backup_complete_explanation))
        Button(onClick = onDone) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun RestoreCompleteContent(summary: AuroraRestoreSummary, onDone: () -> Unit) {
    WorkflowCard(title = stringResource(R.string.restore_complete_title)) {
        Text(stringResource(R.string.restore_imported_count, summary.importedMessages))
        Text(stringResource(R.string.restore_skipped_count, summary.skippedDuplicates))
        Button(onClick = onDone) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun FailureContent(message: String, extraWarning: String?, onDone: () -> Unit) {
    WorkflowCard(title = stringResource(R.string.backup_restore_failed_title)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        extraWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onDone) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun SummaryLines(summary: AuroraBackupSummary) {
    Text(stringResource(R.string.restore_summary_sms, summary.smsCount))
    Text(stringResource(R.string.restore_summary_mms, summary.mmsCount))
    Text(stringResource(R.string.restore_summary_parts, summary.mmsPartCount))
    Text(
        stringResource(
            R.string.restore_summary_content_size,
            formatBytes(summary.plaintextContentBytes),
        ),
    )
}

@Composable
private fun ActionCard(
    title: String,
    explanation: String,
    button: String,
    buttonTag: String,
    onClick: () -> Unit,
) {
    WorkflowCard(title) {
        Text(explanation)
        Button(onClick = onClick, modifier = Modifier.testTag(buttonTag)) { Text(button) }
    }
}

@Composable
private fun WorkflowCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val visuals = LocalAuroraVisualTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = visuals.elevatedSurface.copy(alpha = BACKUP_SURFACE_ALPHA),
        border = BorderStroke(1.dp, visuals.violet.copy(alpha = BACKUP_BORDER_ALPHA)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun NoticeCard(text: String, warning: Boolean) {
    val color = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = color,
    ) {
        Text(text = text, modifier = Modifier.padding(14.dp), color = contentColor)
    }
}

@Composable
private fun SecretField(
    value: String,
    label: String,
    tag: String,
    onValueChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Composable
private fun authenticationFailureMessage(failure: AuroraBackupFailure): String = when (failure) {
    AuroraBackupFailure.PASSPHRASE_POLICY -> stringResource(R.string.backup_passphrase_invalid)
    AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION ->
        stringResource(R.string.restore_authentication_or_corruption)
    AuroraBackupFailure.UNSUPPORTED_VERSION -> stringResource(R.string.restore_unsupported_version)
    AuroraBackupFailure.LIMIT_EXCEEDED -> stringResource(R.string.restore_limit_exceeded)
    AuroraBackupFailure.INVALID_ARCHIVE -> stringResource(R.string.restore_invalid_archive)
    AuroraBackupFailure.CRYPTO_UNAVAILABLE,
    AuroraBackupFailure.IO_FAILURE,
    AuroraBackupFailure.SOURCE_FAILURE,
    -> stringResource(R.string.restore_unavailable)
}

@Composable
private fun exportFailureMessage(failure: AuroraBackupExportFailure): String = when (failure) {
    AuroraBackupExportFailure.INVALID_DESTINATION -> stringResource(R.string.backup_invalid_destination)
    AuroraBackupExportFailure.ROLE_REQUIRED -> stringResource(R.string.backup_role_required)
    AuroraBackupExportFailure.PERMISSION_DENIED -> stringResource(R.string.backup_permission_required)
    AuroraBackupExportFailure.PROVIDER_UNAVAILABLE -> stringResource(R.string.backup_provider_unavailable)
    AuroraBackupExportFailure.PASSPHRASE_POLICY -> stringResource(R.string.backup_passphrase_invalid)
    AuroraBackupExportFailure.LIMIT_EXCEEDED -> stringResource(R.string.backup_limit_exceeded)
    AuroraBackupExportFailure.CRYPTO_UNAVAILABLE -> stringResource(R.string.backup_crypto_unavailable)
    AuroraBackupExportFailure.SOURCE_FAILURE -> stringResource(R.string.backup_source_failed)
    AuroraBackupExportFailure.DOCUMENT_FAILURE -> stringResource(R.string.backup_document_failed)
}

@Composable
private fun selectionFailureMessage(failure: AuroraRestoreSelectionFailure): String = when (failure) {
    AuroraRestoreSelectionFailure.INVALID_SOURCE -> stringResource(R.string.restore_invalid_source)
    AuroraRestoreSelectionFailure.DOCUMENT_UNAVAILABLE -> stringResource(R.string.restore_document_unavailable)
    AuroraRestoreSelectionFailure.SOURCE_FAILURE -> stringResource(R.string.restore_source_failed)
    AuroraRestoreSelectionFailure.LIMIT_EXCEEDED -> stringResource(R.string.restore_limit_exceeded)
    AuroraRestoreSelectionFailure.PRIVATE_STORAGE_FAILURE ->
        stringResource(R.string.restore_private_storage_failed)
}

@Composable
private fun restoreFailureMessage(failure: AuroraRestoreFailure): String = when (failure) {
    AuroraRestoreFailure.ROLE_REQUIRED -> stringResource(R.string.backup_role_required)
    AuroraRestoreFailure.PERMISSION_DENIED -> stringResource(R.string.backup_permission_required)
    AuroraRestoreFailure.RECOVERY_REQUIRED -> stringResource(R.string.backup_restore_recovery_warning)
    AuroraRestoreFailure.INVALID_ARCHIVE -> stringResource(R.string.restore_invalid_archive)
    AuroraRestoreFailure.DUPLICATE_SCAN_FAILED -> stringResource(R.string.restore_duplicate_scan_failed)
    AuroraRestoreFailure.JOURNAL_FAILED -> stringResource(R.string.restore_journal_failed)
    AuroraRestoreFailure.PROVIDER_FAILED -> stringResource(R.string.restore_provider_failed)
    AuroraRestoreFailure.OWNERSHIP_CONFLICT -> stringResource(R.string.restore_ownership_conflict)
    AuroraRestoreFailure.ROLLBACK_INCOMPLETE -> stringResource(R.string.restore_rollback_incomplete)
    AuroraRestoreFailure.SOURCE_FAILED -> stringResource(R.string.restore_source_failed)
}

internal sealed interface BackupRestoreUiState {
    data object Idle : BackupRestoreUiState
    data object ExportPassphrase : BackupRestoreUiState
    data object ExportPassphraseInvalid : BackupRestoreUiState
    data class RestorePassphrase(
        val session: AuroraBackupStagingSession,
        val encryptedBytes: Long,
        val failure: AuroraBackupFailure? = null,
    ) : BackupRestoreUiState
    data class Review(
        val session: AuroraBackupStagingSession,
        val summary: AuroraBackupSummary,
    ) : BackupRestoreUiState
    data class Working(val operation: BackupOperation) : BackupRestoreUiState
    data class ExportComplete(val summary: AuroraBackupSummary) : BackupRestoreUiState
    data class RestoreComplete(val summary: AuroraRestoreSummary) : BackupRestoreUiState
    data class ExportFailed(
        val reason: AuroraBackupExportFailure,
        val incompleteDestinationMayRemain: Boolean,
    ) : BackupRestoreUiState
    data class SelectionFailed(val reason: AuroraRestoreSelectionFailure) : BackupRestoreUiState
    data class RestoreFailed(
        val reason: AuroraRestoreFailure,
        val rollbackComplete: Boolean,
    ) : BackupRestoreUiState
}

internal fun BackupRestoreUiState.requiresBackgroundCancellation(): Boolean = when (this) {
    is BackupRestoreUiState.RestorePassphrase,
    is BackupRestoreUiState.Review,
    -> true
    is BackupRestoreUiState.Working -> operation == BackupOperation.SELECT_RESTORE ||
        operation == BackupOperation.AUTHENTICATE_RESTORE
    BackupRestoreUiState.Idle,
    BackupRestoreUiState.ExportPassphrase,
    BackupRestoreUiState.ExportPassphraseInvalid,
    is BackupRestoreUiState.ExportComplete,
    is BackupRestoreUiState.RestoreComplete,
    is BackupRestoreUiState.ExportFailed,
    is BackupRestoreUiState.SelectionFailed,
    is BackupRestoreUiState.RestoreFailed,
    -> false
}

internal enum class BackupOperation(val titleResource: Int) {
    STARTUP_RECOVERY(R.string.backup_restore_checking),
    EXPORT(R.string.backup_working),
    SELECT_RESTORE(R.string.restore_copying),
    AUTHENTICATE_RESTORE(R.string.restore_authenticating),
    RESTORE(R.string.restore_working),
    CLEANUP(R.string.backup_restore_cleaning),
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KiB"
    bytes < 1_024L * 1_024L * 1_024L -> "${bytes / (1_024L * 1_024L)} MiB"
    else -> "${bytes / (1_024L * 1_024L * 1_024L)} GiB"
}

private fun defaultBackupFileName(): String =
    "AuroraSMS-${LocalDateTime.now().format(BACKUP_FILE_TIMESTAMP)}.aurorabk"

internal const val BACKUP_RESTORE_SCREEN_TEST_TAG = "aurora-backup-restore-screen"
internal const val BACKUP_RESTORE_BACK_TEST_TAG = "aurora-backup-restore-back"
internal const val BACKUP_CREATE_TEST_TAG = "aurora-backup-create"
internal const val RESTORE_CHOOSE_TEST_TAG = "aurora-restore-choose"
internal const val BACKUP_PASSPHRASE_TEST_TAG = "aurora-backup-passphrase"
internal const val BACKUP_PASSPHRASE_CONFIRM_TEST_TAG = "aurora-backup-passphrase-confirm"
internal const val BACKUP_CHOOSE_DESTINATION_TEST_TAG = "aurora-backup-choose-destination"
internal const val RESTORE_PASSPHRASE_TEST_TAG = "aurora-restore-passphrase"
internal const val RESTORE_AUTHENTICATE_TEST_TAG = "aurora-restore-authenticate"
internal const val RESTORE_CONFIRM_TEST_TAG = "aurora-restore-confirm"
private const val AURORA_BACKUP_MIME_TYPE = "application/vnd.aurorasms.backup"
private const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"
private const val BACKUP_SURFACE_ALPHA = 0.95f
private const val BACKUP_BORDER_ALPHA = 0.66f
private val BACKUP_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
