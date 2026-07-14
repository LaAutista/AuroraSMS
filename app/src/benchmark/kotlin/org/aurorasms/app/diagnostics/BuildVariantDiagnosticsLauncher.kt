// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import android.app.Application
import androidx.compose.runtime.Composable

class BuildVariantDiagnosticsLauncher(
    @Suppress("UNUSED_PARAMETER") application: Application,
) : DiagnosticsLauncher {
    override val available: Boolean = false

    @Composable
    override fun Content(onClose: () -> Unit) = Unit
}
