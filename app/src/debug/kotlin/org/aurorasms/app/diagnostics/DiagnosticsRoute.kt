// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DiagnosticsRoute(
    application: Application,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val diagnostics: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.Factory(application),
    )
    DiagnosticsScreen(
        snapshot = diagnostics.snapshot,
        onRefresh = diagnostics::refresh,
        onClose = onClose,
    )
}
