// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.ContactDiscovery
import org.aurorasms.core.telephony.ContactDiscoveryResult
import org.aurorasms.core.telephony.DiscoveredContact
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_DISCOVERY_RESULTS
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS
import org.aurorasms.core.telephony.MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.normalizeContactDiscoveryQuery

class AndroidContactDiscovery internal constructor(
    private val permissionChecker: ContactDiscoveryPermissionChecker,
    private val providerQuery: ContactDiscoveryProviderQuery,
    private val ioDispatcher: CoroutineDispatcher,
) : ContactDiscovery {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        permissionChecker = AndroidContactDiscoveryPermissionChecker(context.applicationContext),
        providerQuery = AndroidContactDiscoveryProviderQuery(context.applicationContext.contentResolver),
        ioDispatcher = ioDispatcher,
    )

    override suspend fun discover(
        query: String,
        resultLimit: Int,
    ): ContactDiscoveryResult {
        val request = ValidatedContactDiscoveryRequest.create(query, resultLimit)
            ?: return ContactDiscoveryResult.InvalidRequest
        return withContext(ioDispatcher) {
            try {
                if (!permissionChecker.hasReadContactsPermission()) {
                    return@withContext ContactDiscoveryResult.PermissionDenied
                }
                val rawRows = providerQuery.query(request)
                    ?: return@withContext ContactDiscoveryResult.Unavailable
                projectContactDiscoveryRows(rawRows, request.resultLimit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                ContactDiscoveryResult.PermissionDenied
            } catch (_: RuntimeException) {
                ContactDiscoveryResult.Unavailable
            }
        }
    }
}

internal fun interface ContactDiscoveryPermissionChecker {
    fun hasReadContactsPermission(): Boolean
}

internal fun interface ContactDiscoveryProviderQuery {
    suspend fun query(request: ValidatedContactDiscoveryRequest): List<RawDiscoveredContactRow>?
}

internal class ValidatedContactDiscoveryRequest private constructor(
    val query: String,
    val resultLimit: Int,
) {
    val providerRowLimit: Int
        get() = resultLimit + 1

    override fun toString(): String =
        "ValidatedContactDiscoveryRequest(resultLimit=$resultLimit, query=REDACTED)"

    companion object {
        fun create(query: String, resultLimit: Int): ValidatedContactDiscoveryRequest? {
            val normalizedQuery = normalizeContactDiscoveryQuery(query, resultLimit) ?: return null
            return ValidatedContactDiscoveryRequest(normalizedQuery, resultLimit)
        }
    }
}

internal data class RawDiscoveredContactRow(
    val address: String?,
    val displayName: String?,
    val photoUri: String?,
) {
    override fun toString(): String = "RawDiscoveredContactRow(REDACTED)"
}

private class AndroidContactDiscoveryPermissionChecker(
    private val appContext: Context,
) : ContactDiscoveryPermissionChecker {
    override fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}

internal fun interface ContactDiscoveryCursorQuery {
    fun query(
        request: ValidatedContactDiscoveryRequest,
        cancellationSignal: CancellationSignal,
    ): Cursor?
}

internal class AndroidContactDiscoveryProviderQuery internal constructor(
    private val cursorQuery: ContactDiscoveryCursorQuery,
) : ContactDiscoveryProviderQuery {
    constructor(resolver: ContentResolver) : this(
        cursorQuery = ResolverContactDiscoveryCursorQuery(resolver),
    )

    override suspend fun query(
        request: ValidatedContactDiscoveryRequest,
    ): List<RawDiscoveredContactRow>? = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine

        try {
            val rows = cursorQuery.query(request, cancellationSignal)?.use { cursor ->
                readRows(cursor, request.providerRowLimit) { continuation.isActive }
            }
            if (continuation.isActive) continuation.resume(rows)
        } catch (failure: RuntimeException) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

    private fun readRows(
        cursor: Cursor,
        rowLimit: Int,
        isActive: () -> Boolean,
    ): List<RawDiscoveredContactRow> {
        val addressIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val displayNameIndex = cursor.getColumnIndex(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        )
        val photoUriIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
        val rows = ArrayList<RawDiscoveredContactRow>(rowLimit)
        while (rows.size < rowLimit && isActive() && cursor.moveToNext()) {
            rows += RawDiscoveredContactRow(
                address = cursor.getString(addressIndex),
                displayName = cursor.stringOrNull(displayNameIndex),
                photoUri = cursor.stringOrNull(photoUriIndex),
            )
        }
        return rows
    }
}

private fun Cursor.stringOrNull(columnIndex: Int): String? =
    columnIndex.takeIf { it >= 0 }?.let { getString(it) }

private class ResolverContactDiscoveryCursorQuery(
    private val resolver: ContentResolver,
) : ContactDiscoveryCursorQuery {
    override fun query(
        request: ValidatedContactDiscoveryRequest,
        cancellationSignal: CancellationSignal,
    ): Cursor? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(request.query)
            .appendQueryParameter(CONTACTS_LIMIT_QUERY_PARAMETER, request.providerRowLimit.toString())
            .build()
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, CONTACT_DISCOVERY_SORT_ORDER)
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.providerRowLimit)
        }
        return try {
            resolver.query(uri, CONTACT_DISCOVERY_PROJECTION, queryArgs, cancellationSignal)
        } catch (_: IllegalArgumentException) {
            // Some OEM providers reject structured query arguments. The Contacts URI still carries
            // the same limit, and the cursor reader independently enforces it.
            resolver.query(
                uri,
                CONTACT_DISCOVERY_PROJECTION,
                null,
                null,
                CONTACT_DISCOVERY_SORT_ORDER,
                cancellationSignal,
            )
        }
    }
}

internal fun projectContactDiscoveryRows(
    rawRows: List<RawDiscoveredContactRow>,
    resultLimit: Int,
): ContactDiscoveryResult.Available {
    require(resultLimit in 1..MAXIMUM_CONTACT_DISCOVERY_RESULTS) {
        "Contact discovery projection exceeds the reviewed bound"
    }
    val inspectedRows = rawRows.take(resultLimit + 1)
    val preferredByExactAddress = inspectedRows
        .asSequence()
        .mapNotNull(RawDiscoveredContactRow::toDiscoveredContact)
        .groupBy(DiscoveredContact::address)
        .mapValues { (_, candidates) -> candidates.minWith(CONTACT_DISCOVERY_CANDIDATE_ORDER) }
    val preferredCandidates = preferredByExactAddress.values.sortedWith(CONTACT_DISCOVERY_CANDIDATE_ORDER)
    val canonicalAddresses = when (val canonical = RecipientSet.from(preferredCandidates.map(DiscoveredContact::address))) {
        is RecipientSet.CreationResult.Valid -> canonical.recipients.addresses
        is RecipientSet.CreationResult.Rejected -> emptyList()
    }
    val ordered = canonicalAddresses
        .mapNotNull(preferredByExactAddress::get)
        .sortedWith(CONTACT_DISCOVERY_OUTPUT_ORDER)
    return ContactDiscoveryResult.Available(
        contacts = ordered.take(resultLimit),
        truncated = rawRows.size > resultLimit || ordered.size > resultLimit,
    )
}

private fun RawDiscoveredContactRow.toDiscoveredContact(): DiscoveredContact? {
    val normalizedAddress = address?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val participant = try {
        ParticipantAddress(normalizedAddress)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (RecipientSet.from(listOf(participant)) !is RecipientSet.CreationResult.Valid) return null
    return DiscoveredContact(
        address = participant,
        displayName = displayName.boundedContactMetadata(MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS),
        photoUri = photoUri.boundedContactMetadata(MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS),
    )
}

private fun String?.boundedContactMetadata(maxLength: Int): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxLength && it.none(Char::isISOControl) }

private val CONTACT_DISCOVERY_CANDIDATE_ORDER = compareBy<DiscoveredContact>(
    { it.displayName == null },
    { it.displayName?.lowercase(Locale.ROOT).orEmpty() },
    { it.displayName.orEmpty() },
    { it.photoUri == null },
    { it.photoUri.orEmpty() },
    { it.address.value.lowercase(Locale.ROOT) },
    { it.address.value },
)

private val CONTACT_DISCOVERY_OUTPUT_ORDER = compareBy<DiscoveredContact>(
    { it.displayName == null },
    { it.displayName?.lowercase(Locale.ROOT).orEmpty() },
    { it.displayName.orEmpty() },
    { it.address.value.lowercase(Locale.ROOT) },
    { it.address.value },
)

private val CONTACT_DISCOVERY_PROJECTION = arrayOf(
    ContactsContract.CommonDataKinds.Phone.NUMBER,
    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
)

private const val CONTACT_DISCOVERY_SORT_ORDER: String =
    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, " +
        "${ContactsContract.CommonDataKinds.Phone.NUMBER} COLLATE NOCASE ASC, " +
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} ASC, " +
        "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
private const val CONTACTS_LIMIT_QUERY_PARAMETER: String = "limit"
