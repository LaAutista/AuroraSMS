// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.ResolvedContact

class AndroidContactResolver(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ContactResolver {
    private val appContext = context.applicationContext

    override suspend fun resolve(
        addresses: List<ParticipantAddress>,
    ): List<ResolvedContact> = withContext(ioDispatcher) {
        val fallback = addresses.map(ParticipantAddress::asAddressOnlyContact)
        if (addresses.isEmpty() || addresses.size > MAX_LOOKUPS_PER_CALL) {
            return@withContext fallback
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext fallback
        }

        try {
            addresses.map { address -> lookup(address) ?: address.asAddressOnlyContact() }
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and any individual
            // provider query. Return one consistent address-only snapshot.
            fallback
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun lookup(address: ParticipantAddress): ResolvedContact? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            address.value,
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
        )
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME),
            ).boundedDisplayValue(MAX_DISPLAY_NAME_CHARACTERS)
            val photo = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.PHOTO_URI),
            ).boundedDisplayValue(MAX_PHOTO_URI_CHARACTERS)
            ResolvedContact(address = address, displayName = name, photoUri = photo)
        }
    }

    companion object {
        const val MAX_LOOKUPS_PER_CALL: Int = 100
        private const val MAX_DISPLAY_NAME_CHARACTERS: Int = 1_000
        private const val MAX_PHOTO_URI_CHARACTERS: Int = 2_048
    }
}

private fun ParticipantAddress.asAddressOnlyContact(): ResolvedContact =
    ResolvedContact(address = this, displayName = null, photoUri = null)

private fun String?.boundedDisplayValue(maxLength: Int): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxLength && it.none(Char::isISOControl) }
