// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aurorasms.app.R
import org.aurorasms.app.IndexStorageStatus
import org.aurorasms.app.StateStorageStatus
import org.aurorasms.core.model.TransportResult

@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (snapshot.loading) {
                Text(stringResource(R.string.diagnostics_loading))
            } else {
                DiagnosticRow(
                    stringResource(R.string.diagnostics_role_label),
                    booleanText(snapshot.roleHeld),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_telephony_label),
                    booleanText(snapshot.telephonyFeature),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_messaging_label),
                    booleanText(snapshot.messagingFeature),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_subscription_feature_label),
                    booleanText(snapshot.subscriptionFeature),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_permissions_label),
                    "${snapshot.grantedPermissions}/${snapshot.ledgeredPermissions}",
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_active_subscriptions_label),
                    snapshot.activeSubscriptions?.toString()
                        ?: stringResource(R.string.diagnostics_unavailable),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_sms_rows_label),
                    snapshot.smsRows?.toString() ?: stringResource(R.string.diagnostics_unavailable),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_mms_rows_label),
                    snapshot.mmsRows?.toString() ?: stringResource(R.string.diagnostics_unavailable),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_index_state_label),
                    snapshot.indexState?.name?.asDiagnosticText()
                        ?: stringResource(R.string.diagnostics_unavailable),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_indexed_rows_label),
                    snapshot.indexedRows?.toString()
                        ?: stringResource(R.string.diagnostics_unavailable),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_index_verified_label),
                    booleanText(snapshot.indexVerifiedComplete),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_index_pending_label),
                    booleanText(snapshot.indexPendingChanges),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_index_failure_label),
                    snapshot.indexFailure?.name?.asDiagnosticText()
                        ?: stringResource(R.string.diagnostics_none),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_index_storage_label),
                    indexStorageText(snapshot.indexStorageStatus),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_state_storage_label),
                    stateStorageText(snapshot.stateStorageStatus),
                )
                DiagnosticRow(
                    stringResource(R.string.diagnostics_last_transport_label),
                    transportResultText(snapshot.lastTransportResult),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onRefresh) {
                    Text(stringResource(R.string.diagnostics_refresh))
                }
                OutlinedButton(onClick = onClose) {
                    Text(stringResource(R.string.diagnostics_close))
                }
            }
        }
    }
}

@Composable
private fun stateStorageText(status: StateStorageStatus): String = when (status) {
    StateStorageStatus.Opening -> stringResource(R.string.diagnostics_state_opening)
    StateStorageStatus.Ready -> stringResource(R.string.diagnostics_state_ready)
    is StateStorageStatus.Failed -> status.reason.name.asDiagnosticText()
}

@Composable
private fun indexStorageText(status: IndexStorageStatus): String = when (status) {
    IndexStorageStatus.Opening -> stringResource(R.string.diagnostics_state_opening)
    is IndexStorageStatus.Ready -> stringResource(
        if (status.recovered) {
            R.string.diagnostics_index_ready_recovered
        } else {
            R.string.diagnostics_state_ready
        },
    )
    is IndexStorageStatus.Failed -> status.reason.name.asDiagnosticText()
}

private fun String.asDiagnosticText(): String = lowercase(Locale.ROOT).replace('_', ' ')

@Composable
private fun booleanText(value: Boolean): String = stringResource(
    if (value) R.string.diagnostics_yes else R.string.diagnostics_no,
)

@Composable
private fun transportResultText(result: TransportResult?): String = stringResource(
    when (result) {
        null -> R.string.diagnostics_transport_none
        is TransportResult.Submitted -> R.string.diagnostics_transport_submitted
        is TransportResult.Sent -> R.string.diagnostics_transport_sent
        is TransportResult.Delivered -> R.string.diagnostics_transport_delivered
        is TransportResult.Downloaded -> R.string.diagnostics_transport_downloaded
        is TransportResult.Rejected -> R.string.diagnostics_transport_rejected
        is TransportResult.Failed -> R.string.diagnostics_transport_failed
    },
)

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
