// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.MmsAttachmentType
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MAXIMUM_VISIBLE_MMS_IMAGE_PARTS
import org.aurorasms.core.telephony.MmsAttachmentContent
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentDescriptor
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.MmsStaticImageList
import org.aurorasms.core.telephony.SUPPORTED_STATIC_MMS_IMAGE_MIME_TYPES

class AndroidMmsAttachmentRepository(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MmsAttachmentRepository {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun listStaticImages(
        providerMessageId: ProviderMessageId,
    ): MmsAttachmentListResult = withContext(ioDispatcher) {
        if (providerMessageId.kind != ProviderKind.MMS) {
            return@withContext MmsAttachmentListResult.InvalidMessageKind
        }
        when (accessState()) {
            AccessState.ROLE_REQUIRED -> return@withContext MmsAttachmentListResult.RoleRequired
            AccessState.PERMISSION_DENIED -> return@withContext MmsAttachmentListResult.PermissionDenied
            AccessState.GRANTED -> Unit
        }

        try {
            val rawRows = queryParts(
                providerMessageId = providerMessageId,
                selection = null,
                selectionArgs = null,
                limit = MAXIMUM_INSPECTED_ATTACHMENT_PARTS + 1,
            )?.use(::readRawParts) ?: return@withContext MmsAttachmentListResult.Unavailable
            MmsAttachmentListResult.Success(projectStaticImageParts(providerMessageId, rawRows))
        } catch (_: SecurityException) {
            MmsAttachmentListResult.PermissionDenied
        } catch (_: RuntimeException) {
            MmsAttachmentListResult.Unavailable
        }
    }

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> = withContext(ioDispatcher) {
        when (accessState()) {
            AccessState.ROLE_REQUIRED -> return@withContext MmsAttachmentReadResult.RoleRequired
            AccessState.PERMISSION_DENIED -> return@withContext MmsAttachmentReadResult.PermissionDenied
            AccessState.GRANTED -> Unit
        }
        val descriptor = try {
            queryParts(
                providerMessageId = id.providerMessageId,
                selection = "${BaseColumns._ID} = ?",
                selectionArgs = arrayOf(id.providerPartId.toString()),
                limit = 2,
            )?.use { cursor ->
                val rows = readRawParts(cursor)
                if (rows.size != 1) null else rows.single().toDescriptor(id.providerMessageId)
            }
        } catch (_: SecurityException) {
            return@withContext MmsAttachmentReadResult.PermissionDenied
        } catch (_: RuntimeException) {
            return@withContext MmsAttachmentReadResult.Unavailable
        } ?: return@withContext MmsAttachmentReadResult.NotFound

        if (descriptor.type.mimeType !in SUPPORTED_STATIC_MMS_IMAGE_MIME_TYPES) {
            return@withContext MmsAttachmentReadResult.UnsupportedType
        }
        val partUri = MMS_PART_CONTENT_URI.buildUpon()
            .appendPath(id.providerPartId.toString())
            .build()
        val asset = try {
            resolver.openAssetFileDescriptor(partUri, "r")
        } catch (_: FileNotFoundException) {
            return@withContext MmsAttachmentReadResult.NotFound
        } catch (_: SecurityException) {
            return@withContext MmsAttachmentReadResult.PermissionDenied
        } catch (_: RuntimeException) {
            return@withContext MmsAttachmentReadResult.Unavailable
        } ?: return@withContext MmsAttachmentReadResult.NotFound

        try {
            asset.use { descriptorFile ->
                descriptorFile.createInputStream().use { stream ->
                    MmsAttachmentReadResult.Success(
                        reader.read(
                            MmsAttachmentContent(
                                descriptor = descriptor,
                                encodedLengthBytes = descriptorFile.length.takeIf { it >= 0L },
                                stream = stream,
                            ),
                        ),
                    )
                }
            }
        } catch (_: FileNotFoundException) {
            MmsAttachmentReadResult.NotFound
        } catch (_: SecurityException) {
            MmsAttachmentReadResult.PermissionDenied
        }
    }

    private fun accessState(): AccessState = when {
        !roleState.isRoleHeld() -> AccessState.ROLE_REQUIRED
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED -> AccessState.PERMISSION_DENIED
        else -> AccessState.GRANTED
    }

    private fun queryParts(
        providerMessageId: ProviderMessageId,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
    ): Cursor? {
        val uri = Telephony.Mms.CONTENT_URI.buildUpon()
            .appendPath(providerMessageId.value.toString())
            .appendPath(MMS_PART_PATH)
            .build()
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.FILENAME,
            Telephony.Mms.Part.NAME,
            Telephony.Mms.Part.CONTENT_LOCATION,
        )
        val queryArgs = Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return try {
            resolver.query(uri, projection, queryArgs, null)
        } catch (_: IllegalArgumentException) {
            resolver.query(uri, projection, selection, selectionArgs, "${BaseColumns._ID} ASC")
        }
    }

    private fun readRawParts(cursor: Cursor): List<RawAttachmentPart> {
        val rows = ArrayList<RawAttachmentPart>(MAXIMUM_INSPECTED_ATTACHMENT_PARTS + 1)
        val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
        val contentTypeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
        val filenameIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.FILENAME)
        val nameIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.NAME)
        val locationIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_LOCATION)
        while (rows.size <= MAXIMUM_INSPECTED_ATTACHMENT_PARTS && cursor.moveToNext()) {
            rows += RawAttachmentPart(
                partId = cursor.getLong(idIndex),
                contentType = cursor.getString(contentTypeIndex),
                filename = cursor.getString(filenameIndex),
                name = cursor.getString(nameIndex),
                contentLocation = cursor.getString(locationIndex),
            )
        }
        return rows
    }

    private enum class AccessState {
        GRANTED,
        ROLE_REQUIRED,
        PERMISSION_DENIED,
    }

    private companion object {
        val MMS_PART_CONTENT_URI: Uri = "content://mms/part".toUri()
        const val MMS_PART_PATH: String = "part"
        const val MAXIMUM_INSPECTED_ATTACHMENT_PARTS: Int = 100
    }
}

internal data class RawAttachmentPart(
    val partId: Long,
    val contentType: String?,
    val filename: String?,
    val name: String?,
    val contentLocation: String?,
)

internal fun projectStaticImageParts(
    providerMessageId: ProviderMessageId,
    rawRows: List<RawAttachmentPart>,
): MmsStaticImageList {
    val projected = LinkedHashMap<Long, MmsAttachmentDescriptor>()
    var truncated = rawRows.size > 100
    rawRows.take(100).forEach { raw ->
        val descriptor = raw.toDescriptor(providerMessageId) ?: return@forEach
        if (descriptor.type.mimeType !in SUPPORTED_STATIC_MMS_IMAGE_MIME_TYPES) return@forEach
        if (projected.size < MAXIMUM_VISIBLE_MMS_IMAGE_PARTS) {
            projected.putIfAbsent(raw.partId, descriptor)
        } else {
            truncated = true
        }
    }
    return MmsStaticImageList(projected.values.toList(), truncated)
}

private fun RawAttachmentPart.toDescriptor(
    providerMessageId: ProviderMessageId,
): MmsAttachmentDescriptor? {
    if (partId <= 0L) return null
    val normalizedMimeType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val displayName = sequenceOf(filename, name, contentLocation)
        .mapNotNull { candidate ->
            candidate
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty() &&
                        it.length <= MmsAttachmentType.MAX_DISPLAY_NAME_CHARACTERS &&
                        it.none(Char::isISOControl) &&
                        '/' !in it &&
                        '\\' !in it
                }
        }
        .firstOrNull()
    return try {
        MmsAttachmentDescriptor(
            id = MmsAttachmentId(providerMessageId, partId),
            type = MmsAttachmentType(normalizedMimeType, displayName),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
