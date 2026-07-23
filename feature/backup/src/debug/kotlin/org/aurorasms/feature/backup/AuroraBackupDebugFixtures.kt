// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

/** Debug-only opaque restore token for cross-module UI instrumentation. */
fun createAuroraBackupStagingSessionForTesting(value: String): AuroraBackupStagingSession =
    AuroraBackupStagingSession(value)
