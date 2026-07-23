// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import org.aurorasms.app.AuroraComposeTestActivity
import org.aurorasms.app.AuroraSmsTheme
import org.aurorasms.feature.backup.AuroraBackupFailure
import org.aurorasms.feature.backup.AuroraBackupStagingSession
import org.aurorasms.feature.backup.AuroraBackupSummary
import org.aurorasms.feature.backup.AuroraBackupStartupRecoveryResult
import org.aurorasms.feature.backup.createAuroraBackupStagingSessionForTesting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackupRestoreContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<AuroraComposeTestActivity>()

    @Test
    fun idleOffersExplicitExportAndRestoreWithoutConfirmationAuthority() {
        compose.setContent {
            AuroraSmsTheme {
                Content(state = BackupRestoreUiState.Idle)
            }
        }

        compose.onNodeWithTag(BACKUP_RESTORE_SCREEN_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_CREATE_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RESTORE_CHOOSE_TEST_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RESTORE_CONFIRM_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun startupRecoveryMustFinishSafelyBeforeAnyFileActionIsExposed() {
        val recovery = mutableStateOf<AuroraBackupStartupRecoveryResult?>(null)
        compose.setContent {
            AuroraSmsTheme {
                BackupRestoreContent(
                    state = BackupRestoreUiState.Idle,
                    startupRecovery = recovery.value,
                    passphrase = "",
                    confirmation = "",
                    onPassphraseChanged = {},
                    onConfirmationChanged = {},
                    onBeginExport = {},
                    onChooseExportDestination = {},
                    onBeginRestore = {},
                    onAuthenticateRestore = {},
                    onConfirmRestore = {},
                    onCancel = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Checking restore safety…").assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_CREATE_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(RESTORE_CHOOSE_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle {
            recovery.value = AuroraBackupStartupRecoveryResult(
                restoreFailure = null,
                stagingCleanupSucceeded = false,
            )
        }
        compose.onNodeWithText("Backup & restore paused").assertIsDisplayed()
        compose.onNodeWithTag(BACKUP_CREATE_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(RESTORE_CHOOSE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun selectedAndFailedAuthenticationStillExposeNoRestoreConfirmation() {
        compose.setContent {
            AuroraSmsTheme {
                Content(
                    state = BackupRestoreUiState.RestorePassphrase(
                        session = session(),
                        encryptedBytes = 4_096L,
                        failure = AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION,
                    ),
                )
            }
        }

        compose.onNodeWithTag(RESTORE_PASSPHRASE_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RESTORE_AUTHENTICATE_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(RESTORE_CONFIRM_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText(
            "The passphrase is wrong or the encrypted file is damaged. Nothing was restored.",
        ).assertIsDisplayed()
    }

    @Test
    fun authenticatedSummaryIsTheOnlyStateWithExplicitRestoreConfirmation() {
        var confirmations = 0
        compose.setContent {
            AuroraSmsTheme {
                BackupRestoreContent(
                    state = BackupRestoreUiState.Review(session(), summary()),
                    startupRecovery = successfulStartupRecovery(),
                    passphrase = "",
                    confirmation = "",
                    onPassphraseChanged = {},
                    onConfirmationChanged = {},
                    onBeginExport = {},
                    onChooseExportDestination = {},
                    onBeginRestore = {},
                    onAuthenticateRestore = {},
                    onConfirmRestore = { confirmations += 1 },
                    onCancel = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("SMS messages: 3").assertIsDisplayed()
        compose.onNodeWithText("MMS messages: 2").assertIsDisplayed()
        compose.onNodeWithText("MMS parts: 4").assertIsDisplayed()
        compose.onNodeWithTag(RESTORE_CONFIRM_TEST_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun incompleteDestinationAndRollbackWarningsAreVisible() {
        val state = mutableStateOf<BackupRestoreUiState>(
            BackupRestoreUiState.ExportFailed(
                reason = org.aurorasms.feature.backup.AuroraBackupExportFailure.DOCUMENT_FAILURE,
                incompleteDestinationMayRemain = true,
            ),
        )
        compose.setContent {
            AuroraSmsTheme {
                Content(state = state.value)
            }
        }
        compose.onNodeWithText(
            "The document provider would not remove its incomplete destination. " +
                "Delete that file manually before retrying.",
        ).assertIsDisplayed()

        compose.runOnIdle {
            state.value = BackupRestoreUiState.RestoreFailed(
                reason = org.aurorasms.feature.backup.AuroraRestoreFailure.ROLLBACK_INCOMPLETE,
                rollbackComplete = false,
            )
        }
        compose.onNodeWithText(
            "AuroraSMS could not prove complete rollback. " +
                "Do not retry until the recovery warning clears.",
        ).assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Content(state: BackupRestoreUiState) {
        BackupRestoreContent(
            state = state,
            startupRecovery = successfulStartupRecovery(),
            passphrase = "",
            confirmation = "",
            onPassphraseChanged = {},
            onConfirmationChanged = {},
            onBeginExport = {},
            onChooseExportDestination = {},
            onBeginRestore = {},
            onAuthenticateRestore = {},
            onConfirmRestore = {},
            onCancel = {},
            onBack = {},
        )
    }

    private fun session() = createAuroraBackupStagingSessionForTesting(
        "00000000-0000-4000-8000-000000000005",
    )

    private fun summary() = AuroraBackupSummary(
        entryCount = 9L,
        smsCount = 3L,
        mmsCount = 2L,
        mmsPartCount = 4L,
        plaintextContentBytes = 8_192L,
    )

    private fun successfulStartupRecovery() = AuroraBackupStartupRecoveryResult(
        restoreFailure = null,
        stagingCleanupSucceeded = true,
    )
}
