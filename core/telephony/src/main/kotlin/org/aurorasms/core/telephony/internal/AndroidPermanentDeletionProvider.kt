// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.PermanentDeletionProvider
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderDeletionCommitOutcome
import org.aurorasms.core.telephony.ProviderMessageDeletionTarget
import org.aurorasms.core.telephony.ProviderThreadDeletionSnapshot
import org.aurorasms.core.telephony.SmsProviderDataSource

/** Guarded permanent mutations against Android's authoritative SMS/MMS provider. */
class AndroidPermanentDeletionProvider(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val sms: SmsProviderDataSource,
    private val mms: MmsProviderDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PermanentDeletionProvider {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun inspectMessage(
        providerMessageId: ProviderMessageId,
    ): ProviderAccessResult<ProviderMessageDeletionTarget?> = when (providerMessageId.kind) {
        ProviderKind.SMS -> when (val result = sms.readExact(providerMessageId)) {
            is ProviderAccessResult.Success -> ProviderAccessResult.Success(
                result.value?.let { message ->
                    ProviderMessageDeletionTarget(
                        providerMessageId = message.id,
                        providerThreadId = message.providerThreadId,
                        syncFingerprint = message.syncFingerprint,
                    )
                },
            )
            else -> result.forwardFailure()
        }
        ProviderKind.MMS -> when (val result = mms.readExact(providerMessageId)) {
            is ProviderAccessResult.Success -> ProviderAccessResult.Success(
                result.value?.let { message ->
                    ProviderMessageDeletionTarget(
                        providerMessageId = message.id,
                        providerThreadId = message.providerThreadId,
                        syncFingerprint = message.syncFingerprint,
                    )
                },
            )
            else -> result.forwardFailure()
        }
        ProviderKind.DRAFT,
        ProviderKind.SCHEDULED,
        ProviderKind.PENDING_OPERATION,
        -> ProviderAccessResult.InvalidInput("provider message kind")
    }

    override suspend fun inspectThread(
        providerThreadId: ProviderThreadId,
    ): ProviderAccessResult<ProviderThreadDeletionSnapshot> = withProviderAccess(
        "inspect deletion thread",
    ) {
        val smsSnapshot = inspectKind(providerThreadId, ProviderKind.SMS)
            ?: return@withProviderAccess ProviderAccessResult.Unavailable("inspect deletion SMS")
        val mmsSnapshot = inspectKind(providerThreadId, ProviderKind.MMS)
            ?: return@withProviderAccess ProviderAccessResult.Unavailable("inspect deletion MMS")
        ProviderAccessResult.Success(
            ProviderThreadDeletionSnapshot(
                providerThreadId = providerThreadId,
                smsCount = smsSnapshot.count,
                latestSmsId = smsSnapshot.latestId,
                mmsCount = mmsSnapshot.count,
                latestMmsId = mmsSnapshot.latestId,
            ),
        )
    }

    override suspend fun deleteMessage(
        expected: ProviderMessageDeletionTarget,
    ): ProviderAccessResult<ProviderDeletionCommitOutcome> = withProviderAccess(
        "delete exact message",
    ) {
        val current = when (val result = inspectMessage(expected.providerMessageId)) {
            is ProviderAccessResult.Success -> result.value
            else -> return@withProviderAccess result.forwardFailure()
        }
        if (current == null) {
            return@withProviderAccess ProviderAccessResult.Success(
                ProviderDeletionCommitOutcome.ALREADY_ABSENT,
            )
        }
        if (current != expected) {
            return@withProviderAccess ProviderAccessResult.Success(
                ProviderDeletionCommitOutcome.TARGET_CHANGED,
            )
        }
        val base = when (expected.providerMessageId.kind) {
            ProviderKind.SMS -> Telephony.Sms.CONTENT_URI
            ProviderKind.MMS -> Telephony.Mms.CONTENT_URI
            ProviderKind.DRAFT,
            ProviderKind.SCHEDULED,
            ProviderKind.PENDING_OPERATION,
            -> return@withProviderAccess ProviderAccessResult.InvalidInput(
                "provider message kind",
            )
        }
        val uri = ContentUris.withAppendedId(base, expected.providerMessageId.value)
        val deleted = resolver.delete(
            uri,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(expected.providerThreadId.value.toString()),
        )
        when {
            deleted == 1 -> ProviderAccessResult.Success(ProviderDeletionCommitOutcome.DELETED)
            deleted < 0 || deleted > 1 -> ProviderAccessResult.Unavailable("delete exact message")
            else -> classifyZeroMessageDelete(expected)
        }
    }

    override suspend fun deleteThread(
        expected: ProviderThreadDeletionSnapshot,
    ): ProviderAccessResult<ProviderDeletionCommitOutcome> = withProviderAccess(
        "delete exact thread",
    ) {
        val current = when (val result = inspectThread(expected.providerThreadId)) {
            is ProviderAccessResult.Success -> result.value
            else -> return@withProviderAccess result.forwardFailure()
        }
        if (current.messageCount == 0L) {
            return@withProviderAccess ProviderAccessResult.Success(
                ProviderDeletionCommitOutcome.ALREADY_ABSENT,
            )
        }
        if (current != expected) {
            return@withProviderAccess ProviderAccessResult.Success(
                ProviderDeletionCommitOutcome.TARGET_CHANGED,
            )
        }
        val uri = Telephony.MmsSms.CONTENT_CONVERSATIONS_URI.buildUpon()
            .appendPath(expected.providerThreadId.value.toString())
            .build()
        val deleted = resolver.delete(uri, null, null)
        when {
            deleted > 0 -> ProviderAccessResult.Success(ProviderDeletionCommitOutcome.DELETED)
            deleted < 0 -> ProviderAccessResult.Unavailable("delete exact thread")
            else -> when (val after = inspectThread(expected.providerThreadId)) {
                is ProviderAccessResult.Success -> ProviderAccessResult.Success(
                    if (after.value.messageCount == 0L) {
                        ProviderDeletionCommitOutcome.ALREADY_ABSENT
                    } else {
                        ProviderDeletionCommitOutcome.TARGET_CHANGED
                    },
                )
                else -> after.forwardFailure()
            }
        }
    }

    private suspend fun classifyZeroMessageDelete(
        expected: ProviderMessageDeletionTarget,
    ): ProviderAccessResult<ProviderDeletionCommitOutcome> =
        when (val after = inspectMessage(expected.providerMessageId)) {
            is ProviderAccessResult.Success -> ProviderAccessResult.Success(
                if (after.value == null) {
                    ProviderDeletionCommitOutcome.ALREADY_ABSENT
                } else {
                    ProviderDeletionCommitOutcome.TARGET_CHANGED
                },
            )
            else -> after.forwardFailure()
        }

    private fun inspectKind(
        providerThreadId: ProviderThreadId,
        kind: ProviderKind,
    ): KindSnapshot? {
        val uri = when (kind) {
            ProviderKind.SMS -> Telephony.Sms.CONTENT_URI
            ProviderKind.MMS -> Telephony.Mms.CONTENT_URI
            ProviderKind.DRAFT,
            ProviderKind.SCHEDULED,
            ProviderKind.PENDING_OPERATION,
            -> return null
        }
        return resolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(providerThreadId.value.toString()),
            "${BaseColumns._ID} DESC",
        )?.use { cursor ->
            val count = cursor.count.toLong()
            val latest = if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                    .takeIf { it > 0L }
                    ?.let { ProviderMessageId(kind, it) }
            } else {
                null
            }
            if ((count == 0L) != (latest == null)) null else KindSnapshot(count, latest)
        }
    }

    private suspend fun <T> withProviderAccess(
        operation: String,
        block: suspend () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) return@withContext ProviderAccessResult.RoleRequired
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ProviderAccessResult.PermissionDenied
        }
        try {
            block()
        } catch (_: SecurityException) {
            ProviderAccessResult.PermissionDenied
        } catch (_: IllegalArgumentException) {
            ProviderAccessResult.InvalidInput(operation)
        } catch (_: RuntimeException) {
            ProviderAccessResult.Unavailable(operation)
        }
    }
}

private data class KindSnapshot(val count: Long, val latestId: ProviderMessageId?)

@Suppress("UNCHECKED_CAST")
private fun <T> ProviderAccessResult<*>.forwardFailure(): ProviderAccessResult<T> = when (this) {
    ProviderAccessResult.RoleRequired -> ProviderAccessResult.RoleRequired
    ProviderAccessResult.PermissionDenied -> ProviderAccessResult.PermissionDenied
    is ProviderAccessResult.Unsupported -> this
    is ProviderAccessResult.Unavailable -> this
    is ProviderAccessResult.InvalidInput -> this
    is ProviderAccessResult.Success<*> -> error("A success cannot be forwarded as a failure")
}
