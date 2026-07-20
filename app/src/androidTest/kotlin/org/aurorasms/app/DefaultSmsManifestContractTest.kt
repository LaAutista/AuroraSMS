// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultSmsManifestContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager
    private val packageName = context.packageName
    private val packageInfo: PackageInfo = packageInfo()

    @Test
    fun manifestHasNoGeneralNetworkOrBackupCapability() {
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()

        assertFalse(Manifest.permission.INTERNET in requested)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in requested)
        assertEquals(
            0,
            requireNotNull(packageInfo.applicationInfo).flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test
    fun corePermissionsAndTelephonyFeatureAreDeclared() {
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()
        val required = setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
        )

        assertTrue(requested.containsAll(required))
        assertTrue(
            packageInfo.reqFeatures.orEmpty().any { feature ->
                feature.name == PackageManager.FEATURE_TELEPHONY &&
                    feature.flags and FeatureInfo.FLAG_REQUIRED != 0
            },
        )
    }

    @Test
    fun composeActivityResolvesAllFourMessagingSchemes() {
        setOf("sms", "smsto", "mms", "mmsto").forEach { scheme ->
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("$scheme:+12025550199"))
                .setPackage(packageName)

            val matches = queryActivities(intent)

            assertTrue(
                "$scheme must resolve to the AuroraSMS compose activity",
                matches.any { it.activityInfo.name.endsWith(".compose.ComposeMessageActivity") },
            )
        }
    }

    @Test
    fun deliveryReceiversHaveOfficialGuards() {
        val smsReceiver = requireNotNull(
            packageInfo.receivers.orEmpty().singleOrNull {
                it.name.endsWith(".receiver.SmsDeliverReceiver")
            },
        )
        val mmsReceiver = requireNotNull(
            packageInfo.receivers.orEmpty().singleOrNull {
                it.name.endsWith(".receiver.MmsWapPushReceiver")
            },
        )

        assertTrue(smsReceiver.exported)
        assertEquals(Manifest.permission.BROADCAST_SMS, smsReceiver.permission)
        assertTrue(mmsReceiver.exported)
        assertEquals(Manifest.permission.BROADCAST_WAP_PUSH, mmsReceiver.permission)

        assertTrue(
            queryReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).setPackage(packageName))
                .any { it.activityInfo.name == smsReceiver.name },
        )
        assertTrue(
            queryReceivers(
                Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
                    .setType("application/vnd.wap.mms-message")
                    .setPackage(packageName),
            ).any { it.activityInfo.name == mmsReceiver.name },
        )
    }

    @Test
    fun roleChangeReceiverIsExportedForProtectedPlatformBroadcast() {
        val roleChangeReceiver = requireNotNull(
            packageInfo.receivers.orEmpty().singleOrNull {
                it.name.endsWith(".receiver.DefaultSmsRoleChangedReceiver")
            },
        )

        assertTrue(roleChangeReceiver.exported)
        assertNull(roleChangeReceiver.permission)
        assertTrue(
            queryReceivers(
                Intent(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED)
                    .setPackage(packageName),
            ).any { it.activityInfo.name == roleChangeReceiver.name },
        )
    }

    @Test
    fun respondViaMessageServiceHasOfficialGuardAndSchemes() {
        val service = requireNotNull(
            packageInfo.services.orEmpty().singleOrNull {
                it.name.endsWith(".service.RespondViaMessageService")
            },
        )
        assertTrue(service.exported)
        assertEquals(Manifest.permission.SEND_RESPOND_VIA_MESSAGE, service.permission)

        setOf("sms", "smsto", "mms", "mmsto").forEach { scheme ->
            val intent = Intent(TelephonyManagerActions.RESPOND_VIA_MESSAGE)
                .setData(Uri.parse("$scheme:+12025550199"))
                .setPackage(packageName)
            assertTrue(
                queryServices(intent).any { it.serviceInfo.name == service.name },
            )
        }
    }

    @Test
    fun privateCallbacksAndMmsProviderAreNotExported() {
        val privateReceiverSuffixes = setOf(
            ".receiver.SmsSentReceiver",
            ".receiver.SmsDeliveredReceiver",
            ".receiver.MmsSendResultReceiver",
            ".receiver.MmsDownloadResultReceiver",
            ".InlineReplyReceiver",
            ".receiver.ScheduledSmsAlarmReceiver",
            ".receiver.ScheduledSmsRecoveryReceiver",
            ".receiver.SendDelayAlarmReceiver",
            ".receiver.PermanentDeletionAlarmReceiver",
            ".receiver.NotificationReminderAlarmReceiver",
        )
        privateReceiverSuffixes.forEach { suffix ->
            val receiver = requireNotNull(
                packageInfo.receivers.orEmpty().singleOrNull { it.name.endsWith(suffix) },
            )
            assertFalse("$suffix must not be exported", receiver.exported)
        }

        val provider = requireNotNull(
            packageInfo.providers.orEmpty().singleOrNull {
                it.name.endsWith(".internal.MmsPduFileProvider")
            },
        )
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals("$packageName.mms-pdu", provider.authority)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo = packageManager.getPackageInfo(
        packageName,
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_CONFIGURATIONS,
    )

    @Suppress("DEPRECATION")
    private fun queryActivities(intent: Intent) =
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

    @Suppress("DEPRECATION")
    private fun queryReceivers(intent: Intent) =
        packageManager.queryBroadcastReceivers(intent, 0)

    @Suppress("DEPRECATION")
    private fun queryServices(intent: Intent) =
        packageManager.queryIntentServices(intent, 0)
}

private object TelephonyManagerActions {
    const val RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
}
