// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner-gated mutation contract for the real Android SMS provider.
 *
 * This test never invokes SmsManager. It inserts one uniquely marked synthetic
 * row, exercises only provider state transitions, and removes every row bearing
 * that exact marker by provider ID in a finally block.
 */
@RunWith(AndroidJUnit4::class)
class OutgoingSmsProviderStagingContractTest {
    @Test
    fun exactOutgoingRowStagesFailedAndArmsOnlyOnce() = runBlocking {
        requireExplicitOwnerGate()

        val app = ApplicationProvider.getApplicationContext<AuroraSmsApplication>()
        requireRoleAndPermissions(app)
        val subscription = requireSmsCapableSubscription(app)
        val identity = TestRowIdentity(
            recipient = ParticipantAddress(SYNTHETIC_RECIPIENT),
            body = BODY_PREFIX + UUID.randomUUID(),
            timestampMillis = System.currentTimeMillis().coerceAtLeast(0L),
            subscriptionId = subscription.id.value,
            creator = app.packageName,
        )
        assertEquals(emptyList<Long>(), matchingProviderIds(app, identity))

        val provider = app.container.smsProviderDataSource
        var primaryFailure: Throwable? = null
        try {
            val inserted = provider.insertOutgoing(
                OutgoingSmsRecord(
                    recipient = identity.recipient,
                    body = identity.body,
                    timestampMillis = identity.timestampMillis,
                    subscriptionId = subscription.id,
                ),
            )
            assertTrue(inserted is ProviderAccessResult.Success)
            val stored = (inserted as ProviderAccessResult.Success).value
            assertEquals(ProviderKind.SMS, stored.providerId.kind)
            assertEquals(
                listOf(stored.providerId.value),
                matchingProviderIds(app, identity),
            )
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_FAILED,
                expectedStatus = Telephony.Sms.STATUS_FAILED,
                expectedErrorCode = STAGING_ERROR_CODE,
            )

            val wrongKind = provider.armOutgoing(
                ProviderMessageId(ProviderKind.MMS, stored.providerId.value),
            )
            assertTrue(wrongKind is ProviderAccessResult.InvalidInput)
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_FAILED,
                expectedStatus = Telephony.Sms.STATUS_FAILED,
                expectedErrorCode = STAGING_ERROR_CODE,
            )

            assertTrue(provider.armOutgoing(stored.providerId) is ProviderAccessResult.Success)
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                expectedStatus = Telephony.Sms.STATUS_PENDING,
                expectedErrorCode = CLEARED_ERROR_CODE,
            )

            assertTrue(provider.armOutgoing(stored.providerId) is ProviderAccessResult.Unavailable)
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                expectedStatus = Telephony.Sms.STATUS_PENDING,
                expectedErrorCode = CLEARED_ERROR_CODE,
            )

            assertEquals(
                ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT),
                provider.rollbackOutgoing(
                    stored.providerId,
                    ConversationId(stored.conversationId.value + 1L),
                ),
            )
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                expectedStatus = Telephony.Sms.STATUS_PENDING,
                expectedErrorCode = CLEARED_ERROR_CODE,
            )

            assertEquals(
                ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED),
                provider.rollbackOutgoing(stored.providerId, stored.conversationId),
            )
            assertEquals(
                ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED),
                provider.rollbackOutgoing(stored.providerId, stored.conversationId),
            )
            assertTrue(provider.armOutgoing(stored.providerId) is ProviderAccessResult.Unavailable)
            assertExactRow(
                context = app,
                identity = identity,
                providerId = stored.providerId,
                expectedThreadId = stored.conversationId.value,
                expectedType = Telephony.Sms.MESSAGE_TYPE_FAILED,
                expectedStatus = Telephony.Sms.STATUS_FAILED,
                expectedErrorCode = CLEARED_ERROR_CODE,
            )
            assertEquals(
                1,
                app.contentResolver.delete(
                    ContentUris.withAppendedId(
                        Telephony.Sms.CONTENT_URI,
                        stored.providerId.value,
                    ),
                    null,
                    null,
                ),
            )
            assertEquals(
                ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.ROW_ABSENT),
                provider.rollbackOutgoing(stored.providerId, stored.conversationId),
            )
            assertEquals(emptyList<Long>(), matchingProviderIds(app, identity))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                cleanupExactRows(app, identity)
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun requireExplicitOwnerGate() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(OWNER_GATE_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(OWNER_GATE_REQUIRED, enabled)
    }

    private fun requireRoleAndPermissions(app: AuroraSmsApplication) {
        assumeTrue(ROLE_REQUIRED, app.container.defaultSmsRoleState.isRoleHeld())
        REQUIRED_PERMISSIONS.forEach { permission ->
            assumeTrue(
                PERMISSIONS_REQUIRED,
                app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    private suspend fun requireSmsCapableSubscription(
        app: AuroraSmsApplication,
    ): ActiveSubscription {
        val subscriptions = app.container.subscriptionRepository.activeSubscriptions()
        val active = (subscriptions as? SubscriptionSnapshot.Available)
            ?.subscriptions
            ?.singleOrNull { it.smsCapable }
        assumeTrue(ACTIVE_SUBSCRIPTION_REQUIRED, active != null)
        return requireNotNull(active)
    }

    private fun assertExactRow(
        context: Context,
        identity: TestRowIdentity,
        providerId: ProviderMessageId,
        expectedThreadId: Long,
        expectedType: Int,
        expectedStatus: Int,
        expectedErrorCode: Int,
    ) {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId.value)
        val cursor = context.contentResolver.query(
            uri,
            ROW_PROJECTION,
            null,
            null,
            null,
        )
        assertTrue(cursor != null)
        cursor!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(providerId.value, it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID)))
            assertEquals(expectedThreadId, it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)))
            assertEquals(identity.recipient.value, it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)))
            assertEquals(identity.body, it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)))
            assertEquals(identity.timestampMillis, it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))
            assertEquals(expectedType, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE)))
            assertEquals(expectedStatus, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.STATUS)))
            assertEquals(expectedErrorCode, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.ERROR_CODE)))
            assertEquals(1, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)))
            assertEquals(1, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.SEEN)))
            assertEquals(identity.subscriptionId, it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)))
            assertEquals(identity.creator, it.getString(it.getColumnIndexOrThrow(Telephony.Sms.CREATOR)))
            assertTrue(!it.moveToNext())
        }
    }

    private fun matchingProviderIds(
        context: Context,
        identity: TestRowIdentity,
    ): List<Long> {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND " +
                "${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.SUBSCRIPTION_ID} = ? AND " +
                "${Telephony.Sms.CREATOR} = ?",
            arrayOf(
                identity.recipient.value,
                identity.body,
                identity.timestampMillis.toString(),
                identity.subscriptionId.toString(),
                identity.creator,
            ),
            "${BaseColumns._ID} ASC",
        ) ?: throw AssertionError(PROVIDER_QUERY_REQUIRED)
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(BaseColumns._ID)
            buildList {
                while (it.moveToNext()) add(it.getLong(idIndex))
            }
        }
    }

    private fun cleanupExactRows(context: Context, identity: TestRowIdentity) {
        matchingProviderIds(context, identity).forEach { providerId ->
            val deleted = context.contentResolver.delete(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId),
                null,
                null,
            )
            if (deleted != 1) throw AssertionError(EXACT_CLEANUP_REQUIRED)
        }
        if (matchingProviderIds(context, identity).isNotEmpty()) {
            throw AssertionError(EXACT_CLEANUP_REQUIRED)
        }
    }

    private data class TestRowIdentity(
        val recipient: ParticipantAddress,
        val body: String,
        val timestampMillis: Long,
        val subscriptionId: Int,
        val creator: String,
    )

    private companion object {
        const val OWNER_GATE_ARGUMENT = "auroraSmsProviderStagingContract"
        const val SYNTHETIC_RECIPIENT = "+12025550199"
        const val BODY_PREFIX = "AuroraSMS provider staging contract "
        const val STAGING_ERROR_CODE = Int.MIN_VALUE
        const val CLEARED_ERROR_CODE = 0
        const val OWNER_GATE_REQUIRED = "Explicit SMS provider staging gate is required"
        const val ROLE_REQUIRED = "Default SMS role is required"
        const val PERMISSIONS_REQUIRED = "SMS and phone permissions are required"
        const val ACTIVE_SUBSCRIPTION_REQUIRED = "Exactly one SMS-capable subscription is required"
        const val PROVIDER_QUERY_REQUIRED = "SMS provider query must be available"
        const val EXACT_CLEANUP_REQUIRED = "Exact synthetic SMS cleanup must succeed"
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )
        val ROW_PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.ERROR_CODE,
            Telephony.Sms.READ,
            Telephony.Sms.SEEN,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.CREATOR,
        )
    }
}
