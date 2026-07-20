// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreStateTest {
    @Test
    fun backgroundInvalidatesOnlyPreMutationRestoreWork() {
        assertTrue(
            BackupRestoreUiState.Working(BackupOperation.SELECT_RESTORE)
                .requiresBackgroundCancellation(),
        )
        assertTrue(
            BackupRestoreUiState.Working(BackupOperation.AUTHENTICATE_RESTORE)
                .requiresBackgroundCancellation(),
        )
        assertFalse(
            BackupRestoreUiState.Working(BackupOperation.RESTORE)
                .requiresBackgroundCancellation(),
        )
        assertFalse(
            BackupRestoreUiState.Working(BackupOperation.EXPORT)
                .requiresBackgroundCancellation(),
        )
        assertFalse(BackupRestoreUiState.Idle.requiresBackgroundCancellation())
    }
}
