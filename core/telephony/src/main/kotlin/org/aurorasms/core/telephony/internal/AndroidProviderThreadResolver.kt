// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ParticipantAddressEquivalenceKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ProviderThreadResolution
import org.aurorasms.core.telephony.ProviderThreadResolver
import org.aurorasms.core.telephony.RecipientSet

/**
 * Dormant Android provider-thread boundary. The app graph must not wire this before the durable
 * first-contact owner has recorded RESOLUTION_STARTED.
 */
class AndroidProviderThreadResolver internal constructor(
    private val roleState: DefaultSmsRoleState,
    private val permissionChecker: ProviderThreadPermissionChecker,
    private val allocator: ProviderThreadAllocator,
    private val participantQuery: ProviderThreadParticipantQuery,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderThreadResolver {
    constructor(
        context: Context,
        roleState: DefaultSmsRoleState = AndroidDefaultSmsRoleState(context.applicationContext),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        roleState = roleState,
        permissionChecker = AndroidProviderThreadPermissionChecker(context.applicationContext),
        allocator = AndroidProviderThreadAllocator(context.applicationContext),
        participantQuery = AndroidProviderThreadParticipantQuery(context.applicationContext.contentResolver),
        ioDispatcher = ioDispatcher,
    )

    override suspend fun resolveExact(recipients: RecipientSet): ProviderThreadResolution {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val callerJob = callerContext[Job]
        val preflight = withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            providerThreadPreflight(roleState, permissionChecker)
        }
        if (preflight != null) return preflight

        // This is the last cancellation point at which no provider mutation has been entered.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            withContext(ioDispatcher) {
                resolveAfterEntry(recipients, callerJob)
            }
        }
    }

    private suspend fun resolveAfterEntry(
        recipients: RecipientSet,
        callerJob: Job?,
    ): ProviderThreadResolution {
        // Dispatching to IO may race cancellation. Preserve the no-mutation cancellation outcome
        // until the exact line immediately before allocator entry.
        callerJob?.ensureActive()
        val queryCancellationSignal = CancellationSignal()
        val callerCancellationObserved = AtomicBoolean(false)
        val cancellationWatcher = callerJob?.let { observedJob ->
            CoroutineScope(observedJob + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (observedJob.isCancelled) {
                        callerCancellationObserved.set(true)
                        queryCancellationSignal.cancel()
                    }
                }
            }
        }
        return try {
            callerJob?.ensureActive()
            val rawThreadId = try {
                allocator.getOrCreateThreadId(
                    recipients.addresses.mapTo(linkedSetOf(), ParticipantAddress::value),
                )
            } catch (_: Exception) {
                return ProviderThreadResolution.MutationOutcomeUnknown
            }
            val threadId = rawThreadId
                .takeIf { it > 0L }
                ?.let(::ProviderThreadId)
                ?: return ProviderThreadResolution.MutationOutcomeUnknown

            if (callerCancellationObserved.get() || callerJob?.isActive == false) {
                return ProviderThreadResolution.MutationOutcomeUnknown
            }
            if (postEntryAuthorityStillHeld() != true) {
                return ProviderThreadResolution.MutationOutcomeUnknown
            }

            val snapshot = try {
                participantQuery.query(threadId, queryCancellationSignal)
            } catch (failure: Exception) {
                return if (
                    callerCancellationObserved.get() ||
                    callerJob?.isActive == false ||
                    failure.isCancellationFailure()
                ) {
                    ProviderThreadResolution.MutationOutcomeUnknown
                } else if (postEntryAuthorityStillHeld() == true) {
                    ProviderThreadResolution.ExactParticipantsUnverified
                } else {
                    ProviderThreadResolution.MutationOutcomeUnknown
                }
            }

            if (callerCancellationObserved.get() || callerJob?.isActive == false) {
                return ProviderThreadResolution.MutationOutcomeUnknown
            }
            // A readback is authoritative only while the same role and permission still hold.
            if (postEntryAuthorityStillHeld() != true) {
                return ProviderThreadResolution.MutationOutcomeUnknown
            }
            if (snapshot == null) return ProviderThreadResolution.ExactParticipantsUnverified

            if (snapshot.providerThreadId == threadId && providerParticipantsExactlyMatch(recipients, snapshot)) {
                ProviderThreadResolution.Verified(
                    providerThreadId = threadId,
                    participantCount = recipients.size,
                )
            } else {
                ProviderThreadResolution.ExactParticipantsUnverified
            }
        } finally {
            cancellationWatcher?.cancel()
        }
    }

    private fun postEntryAuthorityStillHeld(): Boolean? = try {
        roleState.isRoleAvailable() &&
            roleState.isRoleHeld() &&
            permissionChecker.hasReadSmsPermission()
    } catch (_: RuntimeException) {
        null
    }
}

private fun Exception.isCancellationFailure(): Boolean =
    this is CancellationException || this is OperationCanceledException

private fun providerThreadPreflight(
    roleState: DefaultSmsRoleState,
    permissionChecker: ProviderThreadPermissionChecker,
): ProviderThreadResolution? = try {
    when {
        !roleState.isRoleAvailable() -> ProviderThreadResolution.PlatformUnavailable
        !roleState.isRoleHeld() -> ProviderThreadResolution.RoleRequired
        !permissionChecker.hasReadSmsPermission() -> ProviderThreadResolution.PermissionDenied
        else -> null
    }
} catch (_: SecurityException) {
    ProviderThreadResolution.PermissionDenied
} catch (_: RuntimeException) {
    ProviderThreadResolution.PlatformUnavailable
}

internal fun interface ProviderThreadPermissionChecker {
    fun hasReadSmsPermission(): Boolean
}

internal fun interface ProviderThreadAllocator {
    fun getOrCreateThreadId(recipients: Set<String>): Long
}

internal fun interface ProviderThreadParticipantQuery {
    suspend fun query(
        threadId: ProviderThreadId,
        cancellationSignal: CancellationSignal,
    ): ProviderThreadParticipantSnapshot?
}

internal data class ProviderThreadParticipantSnapshot(
    val providerThreadId: ProviderThreadId,
    val addresses: List<String>,
) {
    override fun toString(): String =
        "ProviderThreadParticipantSnapshot(addressCount=${addresses.size}, providerThreadId=REDACTED)"
}

private class AndroidProviderThreadPermissionChecker(
    private val appContext: Context,
) : ProviderThreadPermissionChecker {
    override fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
}

private class AndroidProviderThreadAllocator(
    private val appContext: Context,
) : ProviderThreadAllocator {
    override fun getOrCreateThreadId(recipients: Set<String>): Long =
        Telephony.Threads.getOrCreateThreadId(appContext, recipients)
}

internal fun providerParticipantsExactlyMatch(
    recipients: RecipientSet,
    snapshot: ProviderThreadParticipantSnapshot,
): Boolean {
    if (snapshot.addresses.size != recipients.size) return false
    if (snapshot.addresses.size !in 1..RecipientSet.MAX_RECIPIENTS) return false

    val requestedKeys = recipients.addresses.mapTo(linkedSetOf()) { address ->
        ParticipantAddressEquivalenceKey.from(address) ?: return false
    }
    val providerKeys = linkedSetOf<ParticipantAddressEquivalenceKey>()
    snapshot.addresses.forEach { rawAddress ->
        val address = try {
            ParticipantAddress(rawAddress)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val key = ParticipantAddressEquivalenceKey.from(address) ?: return false
        if (!providerKeys.add(key)) return false
    }
    return requestedKeys == providerKeys
}

internal fun interface ProviderThreadCursorQuery {
    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal,
    ): Cursor?
}

internal class AndroidProviderThreadParticipantQuery internal constructor(
    private val cursorQuery: ProviderThreadCursorQuery,
) : ProviderThreadParticipantQuery {
    constructor(resolver: ContentResolver) : this(ResolverProviderThreadCursorQuery(resolver))

    override suspend fun query(
        threadId: ProviderThreadId,
        cancellationSignal: CancellationSignal,
    ): ProviderThreadParticipantSnapshot? {
        val canonicalIds = queryCanonicalIds(threadId, cancellationSignal) ?: return null
        val addresses = queryCanonicalAddresses(canonicalIds, cancellationSignal) ?: return null
        return ProviderThreadParticipantSnapshot(threadId, addresses)
    }

    private fun queryCanonicalIds(
        threadId: ProviderThreadId,
        cancellationSignal: CancellationSignal,
    ): List<Long>? {
        val uri = Telephony.Threads.CONTENT_URI
            .buildUpon()
            .appendPath(threadId.value.toString())
            .appendPath(THREAD_RECIPIENTS_PATH)
            .build()
        return cursorQuery.query(
            uri = uri,
            projection = THREAD_RECIPIENT_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            cancellationSignal = cancellationSignal,
        )?.use { cursor -> readCanonicalIds(cursor, threadId) }
    }

    private fun queryCanonicalAddresses(
        canonicalIds: List<Long>,
        cancellationSignal: CancellationSignal,
    ): List<String>? {
        if (canonicalIds.isEmpty() || canonicalIds.size > RecipientSet.MAX_RECIPIENTS) return null
        val placeholders = List(canonicalIds.size) { "?" }.joinToString(",")
        val uri = Telephony.MmsSms.CONTENT_URI
            .buildUpon()
            .appendPath(CANONICAL_ADDRESSES_PATH)
            .build()
        return cursorQuery.query(
            uri = uri,
            projection = CANONICAL_ADDRESS_PROJECTION,
            selection = "${BaseColumns._ID} IN ($placeholders)",
            selectionArgs = canonicalIds.map(Long::toString).toTypedArray(),
            sortOrder = "${BaseColumns._ID} ASC",
            cancellationSignal = cancellationSignal,
        )?.use { cursor -> readCanonicalAddresses(cursor, canonicalIds) }
    }
}

private class ResolverProviderThreadCursorQuery(
    private val resolver: ContentResolver,
) : ProviderThreadCursorQuery {
    override fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal,
    ): Cursor? = resolver.query(
        uri,
        projection,
        selection,
        selectionArgs,
        sortOrder,
        cancellationSignal,
    )
}

private fun readCanonicalIds(cursor: Cursor, threadId: ProviderThreadId): List<Long>? {
    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
    val recipientIdsIndex = cursor.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS)
    if (!cursor.moveToFirst()) return null
    if (cursor.getLong(idIndex) != threadId.value || cursor.isNull(recipientIdsIndex)) return null
    val recipientIds = parseCanonicalIds(cursor.getString(recipientIdsIndex)) ?: return null
    if (cursor.moveToNext()) return null
    return recipientIds
}

internal fun parseCanonicalIds(rawIds: String): List<Long>? {
    if (rawIds.isEmpty() || rawIds != rawIds.trim()) return null
    val tokens = rawIds.split(' ')
    if (tokens.any(String::isEmpty) || tokens.size > RecipientSet.MAX_RECIPIENTS) return null
    val ids = tokens.map { token -> token.toLongOrNull()?.takeIf { it > 0L } ?: return null }
    return ids.takeIf { parsed -> parsed.zipWithNext().all { (first, second) -> first < second } }
}

private fun readCanonicalAddresses(cursor: Cursor, expectedIds: List<Long>): List<String>? {
    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
    val addressIndex = cursor.getColumnIndexOrThrow(Telephony.CanonicalAddressesColumns.ADDRESS)
    val expected = expectedIds.toSet()
    val addressesById = linkedMapOf<Long, String>()
    while (cursor.moveToNext()) {
        if (addressesById.size >= expectedIds.size) return null
        val id = cursor.getLong(idIndex)
        if (id !in expected || cursor.isNull(addressIndex)) return null
        if (addressesById.put(id, cursor.getString(addressIndex)) != null) return null
    }
    if (addressesById.keys != expected) return null
    return expectedIds.map { id -> addressesById.getValue(id) }
}

private val THREAD_RECIPIENT_PROJECTION = arrayOf(
    BaseColumns._ID,
    Telephony.Threads.RECIPIENT_IDS,
)

private val CANONICAL_ADDRESS_PROJECTION = arrayOf(
    BaseColumns._ID,
    Telephony.CanonicalAddressesColumns.ADDRESS,
)

private const val THREAD_RECIPIENTS_PATH: String = "recipients"
private const val CANONICAL_ADDRESSES_PATH: String = "canonical-addresses"
