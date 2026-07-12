// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.BaseColumns
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.IndexStorageStatus
import org.aurorasms.app.StateStorageStatus
import org.aurorasms.core.index.IndexFailureCode
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.model.TransportResult

data class DiagnosticsSnapshot(
    val loading: Boolean,
    val roleHeld: Boolean,
    val telephonyFeature: Boolean,
    val messagingFeature: Boolean,
    val subscriptionFeature: Boolean,
    val grantedPermissions: Int,
    val ledgeredPermissions: Int,
    val activeSubscriptions: Int?,
    val smsRows: Int?,
    val mmsRows: Int?,
    val indexState: IndexRunState?,
    val indexedRows: Long?,
    val indexVerifiedComplete: Boolean,
    val indexPendingChanges: Boolean,
    val indexFailure: IndexFailureCode?,
    val indexStorageStatus: IndexStorageStatus,
    val stateStorageStatus: StateStorageStatus,
    val lastTransportResult: TransportResult?,
) {
    companion object {
        val Loading = DiagnosticsSnapshot(
            loading = true,
            roleHeld = false,
            telephonyFeature = false,
            messagingFeature = false,
            subscriptionFeature = false,
            grantedPermissions = 0,
            ledgeredPermissions = 0,
            activeSubscriptions = null,
            smsRows = null,
            mmsRows = null,
            indexState = null,
            indexedRows = null,
            indexVerifiedComplete = false,
            indexPendingChanges = false,
            indexFailure = null,
            indexStorageStatus = IndexStorageStatus.Opening,
            stateStorageStatus = StateStorageStatus.Opening,
            lastTransportResult = null,
        )
    }
}

class DiagnosticsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    var snapshot by mutableStateOf(DiagnosticsSnapshot.Loading)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            snapshot = withContext(Dispatchers.IO) { readSnapshot() }
        }
    }

    private suspend fun readSnapshot(): DiagnosticsSnapshot {
        val context = getApplication<Application>()
        val container = (context as? AuroraSmsApplication)?.container
        val indexCoverage = try {
            container?.messageIndex?.coverage()
        } catch (_: RuntimeException) {
            null
        }
        val packageManager = context.packageManager
        val permissions = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.RECEIVE_MMS)
            add(Manifest.permission.RECEIVE_WAP_PUSH)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val readSmsGranted = isGranted(context, Manifest.permission.READ_SMS)
        return DiagnosticsSnapshot(
            loading = false,
            roleHeld = isRoleHeld(context),
            telephonyFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            messagingFeature = Build.VERSION.SDK_INT < 33 ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING),
            subscriptionFeature = Build.VERSION.SDK_INT < 33 ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION),
            grantedPermissions = permissions.count { isGranted(context, it) },
            ledgeredPermissions = permissions.size,
            activeSubscriptions = activeSubscriptionCount(context),
            smsRows = if (readSmsGranted) providerCount(context, Telephony.Sms.CONTENT_URI) else null,
            mmsRows = if (readSmsGranted) providerCount(context, Telephony.Mms.CONTENT_URI) else null,
            indexState = indexCoverage?.state,
            indexedRows = indexCoverage?.indexedMessageCount,
            indexVerifiedComplete = indexCoverage?.verifiedComplete == true,
            indexPendingChanges = indexCoverage?.pendingChanges == true,
            indexFailure = indexCoverage?.failureCode,
            indexStorageStatus = container?.indexStorageStatus?.value ?: IndexStorageStatus.Opening,
            stateStorageStatus = container?.stateStorageStatus?.value ?: StateStorageStatus.Opening,
            lastTransportResult = container?.lastTransportResult?.value,
        )
    }

    private fun isRoleHeld(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    @SuppressLint("MissingPermission")
    private fun activeSubscriptionCount(context: Context): Int? {
        if (!isGranted(context, Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.size
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun providerCount(context: Context, uri: android.net.Uri): Int? = try {
        context.contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { cursor -> cursor.count }
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DiagnosticsViewModel(application) as T
    }
}
