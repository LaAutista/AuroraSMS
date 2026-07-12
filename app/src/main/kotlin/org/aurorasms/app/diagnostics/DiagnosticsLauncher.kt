// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import androidx.compose.runtime.Composable

interface DiagnosticsLauncher {
    val available: Boolean

    @Composable
    fun Content(onClose: () -> Unit)
}
