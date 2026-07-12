// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import android.app.Application
import androidx.compose.runtime.Composable

class BuildVariantDiagnosticsLauncher(
    private val application: Application,
) : DiagnosticsLauncher {
    override val available: Boolean = true

    @Composable
    override fun Content(onClose: () -> Unit) {
        DiagnosticsRoute(application = application, onClose = onClose)
    }
}
