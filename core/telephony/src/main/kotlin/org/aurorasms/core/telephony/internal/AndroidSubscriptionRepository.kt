// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot

class AndroidSubscriptionRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SubscriptionRepository {
    private val appContext = context.applicationContext

    override suspend fun activeSubscriptions(): SubscriptionSnapshot = withContext(ioDispatcher) {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return@withContext SubscriptionSnapshot.FeatureUnavailable
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
        ) {
            return@withContext SubscriptionSnapshot.FeatureUnavailable
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext SubscriptionSnapshot.PermissionDenied
        }

        try {
            val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
                ?: return@withContext SubscriptionSnapshot.PlatformUnavailable
            val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
                ?: return@withContext SubscriptionSnapshot.PlatformUnavailable
            val subscriptions = subscriptionManager.activeSubscriptionInfoList
                .orEmpty()
                .asSequence()
                .filter { it.subscriptionId >= 0 && it.simSlotIndex >= 0 }
                .map { info ->
                    val id = AuroraSubscriptionId(info.subscriptionId)
                    val label = info.displayName
                        ?.toString()
                        ?.trim()
                        ?.take(ActiveSubscription.MAX_DISPLAY_LABEL_CHARACTERS)
                        .orEmpty()
                    ActiveSubscription(
                        id = id,
                        slotIndex = info.simSlotIndex,
                        displayLabel = label,
                        smsCapable = if (Build.VERSION.SDK_INT >= 35) {
                            telephonyManager.isDeviceSmsCapable &&
                                info.serviceCapabilities.contains(SubscriptionManager.SERVICE_CAPABILITY_SMS)
                        } else {
                            @Suppress("DEPRECATION")
                            telephonyManager.createForSubscriptionId(id.value).isSmsCapable
                        },
                    )
                }
                .sortedWith(compareBy(ActiveSubscription::slotIndex, { it.id.value }))
                .toList()
            SubscriptionSnapshot.Available(subscriptions)
        } catch (_: SecurityException) {
            SubscriptionSnapshot.PermissionDenied
        } catch (_: RuntimeException) {
            SubscriptionSnapshot.PlatformUnavailable
        }
    }
}
