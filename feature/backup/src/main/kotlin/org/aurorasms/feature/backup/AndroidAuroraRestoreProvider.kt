// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import java.io.IOException

/** Default-role-only Telephony restore boundary. It never invokes a transport API. */
class AndroidAuroraRestoreProvider internal constructor(
    private val resolver: ContentResolver,
    private val uris: RestoreProviderUris,
    private val packageName: String,
    private val roleHeld: () -> Boolean,
    private val readPermissionGranted: () -> Boolean,
    private val threadIdForAddresses: (Set<String>) -> Long?,
) : AuroraRestoreProvider {
    constructor(context: Context) : this(
        resolver = context.applicationContext.contentResolver,
        uris = RestoreProviderUris.platform(),
        packageName = context.applicationContext.packageName,
        roleHeld = {
            Telephony.Sms.getDefaultSmsPackage(context.applicationContext) ==
                context.applicationContext.packageName
        },
        readPermissionGranted = {
            context.applicationContext.checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        },
        threadIdForAddresses = { addresses ->
            when (addresses.size) {
                0 -> 0L
                1 -> Telephony.Threads.getOrCreateThreadId(
                    context.applicationContext,
                    addresses.single(),
                )
                else -> Telephony.Threads.getOrCreateThreadId(context.applicationContext, addresses)
            }
        },
    )

    override fun preflight(): AuroraRestoreProviderResult<Unit> = accessFailure()
        ?: success(Unit)

    override fun isExactDuplicateSms(
        record: AuroraBackupSmsRecord,
    ): AuroraRestoreProviderResult<Boolean> = withAccess("scan duplicate SMS") {
        val restoredBox = record.box.safeRestoredBox()
        val candidates = queryRows(
            uri = uris.sms,
            projection = SMS_PROJECTION,
            selection = "$SMS_DATE = ? AND $SMS_TYPE = ?",
            selectionArgs = arrayOf(record.timestampMillis.toString(), restoredBox.code.toString()),
            sortOrder = "$ID_COLUMN ASC",
            limit = MAX_MMS_DUPLICATE_CANDIDATES + 1,
            map = Cursor::toSmsRestoreRow,
        ) ?: return@withAccess unavailable("scan duplicate SMS")
        if (candidates.size > MAX_MMS_DUPLICATE_CANDIDATES) {
            return@withAccess unavailable("bound duplicate SMS candidates")
        }
        val expected = AuroraRestoreCanonicalDigest.sms(
            record.copy(box = restoredBox),
            includeHistoricalBox = true,
        )
        val candidate = candidates.firstOrNull { candidate ->
            AuroraRestoreCanonicalDigest.sms(
                candidate.toRecord(record.archiveMessageId),
                includeHistoricalBox = true,
            ) == expected
        } ?: return@withAccess success(false)
        val verifiedRows = queryRows(
            uri = uris.exactSms(candidate.providerId),
            projection = SMS_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = null,
            limit = 2,
            map = Cursor::toSmsRestoreRow,
        ) ?: return@withAccess unavailable("recheck duplicate SMS")
        val verified = verifiedRows.singleOrNull() ?: return@withAccess success(false)
        success(
            AuroraRestoreCanonicalDigest.sms(
                verified.toRecord(record.archiveMessageId),
                includeHistoricalBox = true,
            ) == expected,
        )
    }

    override fun beginMmsDuplicateCheck(
        record: AuroraBackupMmsRecord,
    ): AuroraRestoreProviderResult<AuroraMmsDuplicateCheck> = withAccess("scan duplicate MMS") {
        val timestampSeconds = record.timestampMillis.toMmsSeconds()
        val restoredRecord = record.copy(box = record.box.safeRestoredBox())
        val candidates = queryRows(
            uri = uris.mms,
            projection = MMS_PROJECTION,
            selection = "$MMS_DATE = ? AND $MMS_BOX = ?",
            selectionArgs = arrayOf(timestampSeconds.toString(), restoredRecord.box.code.toString()),
            sortOrder = "$ID_COLUMN ASC",
            limit = MAX_DUPLICATE_CANDIDATES + 1,
            map = Cursor::toMmsRestoreRow,
        ) ?: return@withAccess unavailable("scan duplicate MMS")
        if (candidates.size > MAX_DUPLICATE_CANDIDATES) {
            return@withAccess unavailable("bound duplicate MMS candidates")
        }
        val candidateDigests = ArrayList<Pair<Long, AuroraRestorePreparedDigest>>(candidates.size)
        for (candidate in candidates) {
            candidateDigests += candidate.providerId to (
                computeMmsDigest(candidate, includeHistoricalBox = true)
                ?: return@withAccess unavailable("fingerprint duplicate MMS candidate")
                )
        }
        val archiveDigest = AuroraRestoreCanonicalDigest.beginMms(
            restoredRecord,
            includeHistoricalBox = true,
        )
        success(
            object : AuroraMmsDuplicateCheck {
                private var complete = false

                override fun acceptPart(
                    record: AuroraBackupDecodedMmsPart,
                    payload: AuroraBackupDecodedPartPayload,
                ): AuroraRestoreProviderResult<Unit> = withAccess("fingerprint backup MMS part") {
                    if (complete) return@withAccess unavailable("reuse duplicate MMS check")
                    archiveDigest.accept(AuroraRestoreCanonicalDigest.mmsPart(record, payload))
                    success(Unit)
                }

                override fun finish(): AuroraRestoreProviderResult<Boolean> =
                    withAccess("finish duplicate MMS check") {
                        if (complete) return@withAccess unavailable("reuse duplicate MMS check")
                        complete = true
                        val expected = archiveDigest.finish()
                        val matchingIds = candidateDigests.asSequence()
                            .filter { (_, digest) -> digest == expected }
                            .map(Pair<Long, AuroraRestorePreparedDigest>::first)
                            .toList()
                        for (providerId in matchingIds) {
                            val rows = queryRows(
                                uri = uris.exactMms(providerId),
                                projection = MMS_PROJECTION,
                                selection = null,
                                selectionArgs = null,
                                sortOrder = null,
                                limit = 2,
                                map = Cursor::toMmsRestoreRow,
                            ) ?: return@withAccess unavailable("recheck duplicate MMS")
                            val current = rows.singleOrNull() ?: continue
                            val currentDigest = computeMmsDigest(
                                current,
                                includeHistoricalBox = true,
                            ) ?: return@withAccess unavailable("recheck duplicate MMS content")
                            if (currentDigest == expected) return@withAccess success(true)
                        }
                        success(false)
                    }
            },
        )
    }

    override fun insertSmsPlaceholder(
        record: AuroraBackupSmsRecord,
        placeholderAddress: String,
    ): AuroraRestoreProviderResult<Long> = withAccess("insert restore SMS placeholder") {
        if (!placeholderAddress.matches(SMS_PLACEHOLDER_PATTERN)) {
            return@withAccess unavailable("validate restore SMS placeholder")
        }
        val inserted = resolver.insert(
            uris.sms,
            ContentValues().apply {
                put(SMS_THREAD_ID, 0L)
                put(SMS_ADDRESS, placeholderAddress)
                putNull(SMS_BODY)
                put(SMS_DATE, 0L)
                putNull(SMS_DATE_SENT)
                put(SMS_TYPE, AuroraBackupMessageBox.FAILED.code)
                put(SMS_STATUS, Telephony.Sms.STATUS_FAILED)
                put(SMS_ERROR_CODE, 0)
                put(SMS_READ, 1)
                put(SMS_SEEN, 1)
                put(SMS_LOCKED, 0)
                putNull(SMS_PROTOCOL)
                putNull(SMS_REPLY_PATH)
                putNull(SMS_SUBJECT)
                putNull(SMS_SERVICE_CENTER)
                putNull(SMS_SUBSCRIPTION_ID)
                put(SMS_CREATOR, packageName)
            },
        ) ?: return@withAccess unavailable("insert restore SMS placeholder")
        val providerId = runCatching { ContentUris.parseId(inserted) }.getOrNull()
            ?.takeIf { it > 0L }
            ?: return@withAccess unavailable("read restore SMS placeholder ID")
        val row = readExactSms(providerId)
        if (row == null || !row.isPlaceholder(packageName, placeholderAddress)) {
            runCatching { resolver.delete(uris.exactSms(providerId), null, null) }
            return@withAccess unavailable("verify restore SMS placeholder")
        }
        success(providerId)
    }

    override fun prepareSms(
        providerRowId: Long,
        placeholderAddress: String,
        record: AuroraBackupSmsRecord,
        expectedDigest: AuroraRestorePreparedDigest,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest> =
        withAccess("prepare restore SMS") {
            if (providerRowId <= 0L || !placeholderAddress.matches(SMS_PLACEHOLDER_PATTERN)) {
                return@withAccess unavailable("validate restore SMS identity")
            }
            val current = readExactSms(providerRowId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (!current.isPlaceholder(packageName, placeholderAddress)) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val threadId = resolveThreadId(
                record.address?.trim()?.takeIf(String::isNotEmpty)?.let(::setOf).orEmpty(),
            )
            val updated = resolver.update(
                uris.exactSms(providerRowId),
                record.toPreparedSmsValues(packageName, threadId),
                current.exactSelection().sql,
                current.exactSelection().arguments,
            )
            if (updated != 1) return@withAccess updateFailure(updated, "prepare restore SMS")
            val prepared = readExactSms(providerRowId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (prepared.creator != packageName || prepared.boxCode != AuroraBackupMessageBox.FAILED.code) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val actualDigest = AuroraRestoreCanonicalDigest.sms(
                prepared.toRecord(record.archiveMessageId),
                includeHistoricalBox = false,
            )
            if (actualDigest != expectedDigest) {
                return@withAccess preparedMismatch(deleteExactSms(prepared))
            }
            success(actualDigest)
        }

    override fun insertMmsPlaceholder(
        record: AuroraBackupMmsRecord,
        placeholderTransactionId: String,
    ): AuroraRestoreProviderResult<Long> = withAccess("insert restore MMS placeholder") {
        record.requireProviderMmsTimes()
        if (!placeholderTransactionId.matches(MMS_PLACEHOLDER_PATTERN)) {
            return@withAccess unavailable("validate restore MMS placeholder")
        }
        val inserted = resolver.insert(
            uris.mms,
            ContentValues().apply {
                put(MMS_THREAD_ID, 0L)
                put(MMS_BOX, AuroraBackupMessageBox.FAILED.code)
                put(MMS_DATE, 0L)
                putNull(MMS_DATE_SENT)
                put(MMS_READ, 1)
                put(MMS_SEEN, 1)
                put(MMS_LOCKED, 0)
                putNull(MMS_SUBSCRIPTION_ID)
                putNull(MMS_MESSAGE_TYPE)
                putNull(MMS_VERSION)
                putNull(MMS_PRIORITY)
                putNull(MMS_STATUS)
                putNull(MMS_RESPONSE_STATUS)
                putNull(MMS_RETRIEVE_STATUS)
                putNull(MMS_READ_REPORT)
                putNull(MMS_DELIVERY_REPORT)
                putNull(MMS_REPORT_ALLOWED)
                putNull(MMS_MESSAGE_SIZE)
                putNull(MMS_EXPIRY)
                putNull(MMS_DELIVERY_TIME)
                putNull(MMS_SUBJECT)
                putNull(MMS_SUBJECT_CHARSET)
                putNull(MMS_CONTENT_TYPE)
                putNull(MMS_CONTENT_LOCATION)
                putNull(MMS_MESSAGE_CLASS)
                put(MMS_TRANSACTION_ID, placeholderTransactionId)
                put(MMS_CREATOR, packageName)
            },
        ) ?: return@withAccess unavailable("insert restore MMS placeholder")
        val providerId = runCatching { ContentUris.parseId(inserted) }.getOrNull()
            ?.takeIf { it > 0L }
            ?: return@withAccess unavailable("read restore MMS placeholder ID")
        val row = readExactMms(providerId)
        if (row == null || !row.isPlaceholder(packageName, placeholderTransactionId)) {
            runCatching { resolver.delete(uris.exactMms(providerId), null, null) }
            return@withAccess unavailable("verify restore MMS placeholder")
        }
        success(providerId)
    }

    override fun insertMmsPart(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    ): AuroraRestoreProviderResult<AuroraRestoreMmsPartDigest> =
        withAccess("insert restore MMS part") {
            val parent = readExactMms(providerRowId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (!parent.isPlaceholder(packageName, placeholderTransactionId)) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val values = record.toMmsPartValues().apply {
                if (payload is AuroraBackupDecodedPartPayload.Text) put(MMS_PART_TEXT, payload.value)
            }
            val inserted = resolver.insert(uris.mmsPart(providerRowId), values)
                ?: return@withAccess unavailable("insert restore MMS part")
            val partId = runCatching { ContentUris.parseId(inserted) }.getOrNull()
                ?.takeIf { it > 0L }
                ?: return@withAccess unavailable("read restore MMS part ID")
            val expected = when (payload) {
                is AuroraBackupDecodedPartPayload.Binary -> {
                    resolver.openOutputStream(inserted, "w")?.use { output ->
                        AuroraRestoreCanonicalDigest.mmsPart(record, payload, output)
                    } ?: return@withAccess unavailable("open restore MMS part")
                }
                else -> AuroraRestoreCanonicalDigest.mmsPart(record, payload)
            }
            val stored = readExactMmsPart(partId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (stored.parentProviderId != providerRowId) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val actual = stored.digest(providerRowId, resolver, uris)
                ?: return@withAccess unavailable("verify restore MMS part")
            if (actual != expected) return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            success(expected)
        }

    override fun prepareMms(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupMmsRecord,
        expectedDigest: AuroraRestorePreparedDigest,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest> =
        withAccess("prepare restore MMS") {
            record.requireProviderMmsTimes()
            val current = readExactMms(providerRowId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (!current.isPlaceholder(packageName, placeholderTransactionId)) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            for (address in record.addresses) {
                val inserted = resolver.insert(
                    uris.mmsAddress(providerRowId),
                    ContentValues().apply {
                        put(MMS_ADDRESS_TYPE, address.type)
                        put(MMS_ADDRESS, address.address)
                        putNullable(MMS_ADDRESS_CHARSET, address.charset)
                    },
                ) ?: return@withAccess unavailable("insert restore MMS address")
                if (runCatching { ContentUris.parseId(inserted) }.getOrNull()?.let { it > 0L } != true) {
                    return@withAccess unavailable("read restore MMS address ID")
                }
            }
            if (readMmsAddresses(providerRowId) != record.addresses) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val participants = record.addresses.asSequence()
                .map(AuroraBackupMmsAddress::address)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { it.equals(MMS_INSERT_ADDRESS_TOKEN, ignoreCase = true) }
                .toSet()
            val threadId = resolveThreadId(participants)
            val selection = current.exactSelection()
            val updated = resolver.update(
                uris.exactMms(providerRowId),
                record.toPreparedMmsValues(packageName, threadId),
                selection.sql,
                selection.arguments,
            )
            if (updated != 1) return@withAccess updateFailure(updated, "prepare restore MMS")
            val prepared = readExactMms(providerRowId)
                ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            if (prepared.creator != packageName || prepared.boxCode != AuroraBackupMessageBox.FAILED.code) {
                return@withAccess AuroraRestoreProviderResult.OwnershipConflict
            }
            val digest = computeMmsDigest(prepared, includeHistoricalBox = false)
                ?: return@withAccess unavailable("verify prepared restore MMS")
            if (digest != expectedDigest) {
                return@withAccess preparedMismatch(deleteExactMms(prepared))
            }
            success(digest)
        }

    override fun commitHistoricalBox(
        ownership: AuroraRestoreOwnership,
        safeTargetBox: AuroraBackupMessageBox,
    ): AuroraRestoreProviderResult<Unit> = withAccess("commit restored message box") {
        val providerId = ownership.providerRowId
            ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
        val expectedDigest = ownership.preparedDigest
            ?.let(::AuroraRestorePreparedDigest)
            ?: return@withAccess AuroraRestoreProviderResult.OwnershipConflict
        if (
            safeTargetBox !in SAFE_RESTORED_BOXES ||
            safeTargetBox != ownership.targetBox.safeRestoredBox()
        ) {
            return@withAccess AuroraRestoreProviderResult.OwnershipConflict
        }
        when (ownership.providerKind) {
            AuroraRestoreProviderKind.SMS -> commitSms(providerId, expectedDigest, safeTargetBox)
            AuroraRestoreProviderKind.MMS -> commitMms(providerId, expectedDigest, safeTargetBox)
        }
    }

    override fun rollback(
        session: AuroraRestoreSession,
        ownership: AuroraRestoreOwnership,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> =
        withAccess("rollback restored message") {
            when (ownership.providerKind) {
                AuroraRestoreProviderKind.SMS -> rollbackSms(session, ownership)
                AuroraRestoreProviderKind.MMS -> rollbackMms(session, ownership)
            }
        }

    private fun commitMms(
        providerId: Long,
        expectedDigest: AuroraRestorePreparedDigest,
        safeTargetBox: AuroraBackupMessageBox,
    ): AuroraRestoreProviderResult<Unit> {
        val current = readExactMms(providerId)
            ?: return AuroraRestoreProviderResult.OwnershipConflict
        if (
            current.creator != packageName ||
            current.boxCode != AuroraBackupMessageBox.FAILED.code ||
            computeMmsDigest(current, includeHistoricalBox = false) != expectedDigest
        ) {
            return AuroraRestoreProviderResult.OwnershipConflict
        }
        val selection = current.exactSelection()
        val updated = resolver.update(
            uris.exactMms(providerId),
            ContentValues().apply { put(MMS_BOX, safeTargetBox.code) },
            selection.sql,
            selection.arguments,
        )
        if (updated != 1) return updateFailure(updated, "commit restored MMS box")
        val committed = readExactMms(providerId)
            ?: return AuroraRestoreProviderResult.OwnershipConflict
        return if (
            committed.creator == packageName &&
            committed.boxCode == safeTargetBox.code &&
            computeMmsDigest(committed, includeHistoricalBox = false) == expectedDigest
        ) {
            success(Unit)
        } else {
            AuroraRestoreProviderResult.OwnershipConflict
        }
    }

    private fun commitSms(
        providerId: Long,
        expectedDigest: AuroraRestorePreparedDigest,
        safeTargetBox: AuroraBackupMessageBox,
    ): AuroraRestoreProviderResult<Unit> {
        val current = readExactSms(providerId)
            ?: return AuroraRestoreProviderResult.OwnershipConflict
        if (
            current.creator != packageName ||
            current.boxCode != AuroraBackupMessageBox.FAILED.code ||
            current.preparedDigest() != expectedDigest
        ) {
            return AuroraRestoreProviderResult.OwnershipConflict
        }
        val selection = current.exactSelection()
        val updated = resolver.update(
            uris.exactSms(providerId),
            ContentValues().apply { put(SMS_TYPE, safeTargetBox.code) },
            selection.sql,
            selection.arguments,
        )
        if (updated != 1) return updateFailure(updated, "commit restored SMS box")
        val committed = readExactSms(providerId)
            ?: return AuroraRestoreProviderResult.OwnershipConflict
        return if (
            committed.creator == packageName &&
            committed.boxCode == safeTargetBox.code &&
            committed.preparedDigest() == expectedDigest
        ) {
            success(Unit)
        } else {
            AuroraRestoreProviderResult.OwnershipConflict
        }
    }

    private fun rollbackSms(
        session: AuroraRestoreSession,
        ownership: AuroraRestoreOwnership,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val placeholder = AuroraRestorePlaceholder.smsAddress(session, ownership.archiveMessageId)
        val providerId = ownership.providerRowId ?: return rollbackSmsBeforeId(placeholder)
        val current = readExactSms(providerId)
            ?: return success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
        if (current.creator != packageName) {
            return success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
        val placeholderOwned = current.isPlaceholder(packageName, placeholder)
        val digestOwned = ownership.preparedDigest?.let { digest ->
            current.boxCode in ownership.allowedRollbackBoxes() &&
                current.preparedDigest().value == digest
        } == true
        if (!placeholderOwned && !digestOwned) {
            return success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
        return deleteExactSms(current)
    }

    private fun rollbackSmsBeforeId(
        placeholder: String,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val candidates = queryRows(
            uri = uris.sms,
            projection = SMS_PROJECTION,
            selection = "$SMS_ADDRESS = ? AND $SMS_CREATOR = ? AND $SMS_TYPE = ?",
            selectionArgs = arrayOf(
                placeholder,
                packageName,
                AuroraBackupMessageBox.FAILED.code.toString(),
            ),
            sortOrder = "$ID_COLUMN ASC",
            limit = 2,
            map = Cursor::toSmsRestoreRow,
        ) ?: return unavailable("find restore SMS placeholder")
        return when (candidates.size) {
            0 -> success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
            1 -> {
                val candidate = candidates.single()
                if (candidate.isPlaceholder(packageName, placeholder)) {
                    deleteExactSms(candidate)
                } else {
                    success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
                }
            }
            else -> success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
    }

    private fun deleteExactSms(
        row: SmsRestoreRow,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val selection = row.exactSelection()
        return when (
            val deleted = resolver.delete(
                uris.exactSms(row.providerId),
                selection.sql,
                selection.arguments,
            )
        ) {
            1 -> success(AuroraRestoreRollbackOutcome.REMOVED)
            0 -> if (readExactSms(row.providerId) == null) {
                success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
            } else {
                success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
            }
            else -> unavailable("delete exact restored SMS ($deleted)")
        }
    }

    private fun rollbackMms(
        session: AuroraRestoreSession,
        ownership: AuroraRestoreOwnership,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val placeholder = AuroraRestorePlaceholder.mmsTransactionId(session, ownership.archiveMessageId)
        val providerId = ownership.providerRowId ?: return rollbackMmsBeforeId(placeholder)
        val current = readExactMms(providerId)
            ?: return success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
        if (current.creator != packageName) {
            return success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
        val placeholderOwned = current.isPlaceholder(packageName, placeholder)
        val digestOwned = ownership.preparedDigest?.let { digest ->
            current.boxCode in ownership.allowedRollbackBoxes() &&
                computeMmsDigest(current, includeHistoricalBox = false)?.value == digest
        } == true
        if (!placeholderOwned && !digestOwned) {
            return success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
        return deleteExactMms(current)
    }

    private fun rollbackMmsBeforeId(
        placeholder: String,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val candidates = queryRows(
            uri = uris.mms,
            projection = MMS_PROJECTION,
            selection = "$MMS_TRANSACTION_ID = ? AND $MMS_CREATOR = ? AND $MMS_BOX = ?",
            selectionArgs = arrayOf(
                placeholder,
                packageName,
                AuroraBackupMessageBox.FAILED.code.toString(),
            ),
            sortOrder = "$ID_COLUMN ASC",
            limit = 2,
            map = Cursor::toMmsRestoreRow,
        ) ?: return unavailable("find restore MMS placeholder")
        return when (candidates.size) {
            0 -> success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
            1 -> {
                val candidate = candidates.single()
                if (candidate.isPlaceholder(packageName, placeholder)) {
                    deleteExactMms(candidate)
                } else {
                    success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
                }
            }
            else -> success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
        }
    }

    private fun deleteExactMms(
        row: MmsRestoreRow,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        val selection = row.exactSelection()
        return when (
            val deleted = resolver.delete(
                uris.exactMms(row.providerId),
                selection.sql,
                selection.arguments,
            )
        ) {
            1 -> success(AuroraRestoreRollbackOutcome.REMOVED)
            0 -> if (readExactMms(row.providerId) == null) {
                success(AuroraRestoreRollbackOutcome.ALREADY_ABSENT)
            } else {
                success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT)
            }
            else -> unavailable("delete exact restored MMS ($deleted)")
        }
    }

    private fun computeMmsDigest(
        row: MmsRestoreRow,
        includeHistoricalBox: Boolean,
    ): AuroraRestorePreparedDigest? {
        val addresses = readMmsAddresses(row.providerId) ?: return null
        val parts = readMmsParts(row.providerId) ?: return null
        val accumulator = AuroraRestoreCanonicalDigest.beginMms(
            row.toRecord(archiveMessageId = 1, addresses = addresses),
            includeHistoricalBox,
        )
        for (part in parts) {
            if (part.parentProviderId != row.providerId) return null
            accumulator.accept(part.digest(row.providerId, resolver, uris) ?: return null)
        }
        return accumulator.finish()
    }

    private fun readExactMms(providerId: Long): MmsRestoreRow? = queryRows(
        uri = uris.exactMms(providerId),
        projection = MMS_PROJECTION,
        selection = null,
        selectionArgs = null,
        sortOrder = null,
        limit = 2,
        map = Cursor::toMmsRestoreRow,
    )?.singleOrNull()

    private fun readMmsAddresses(providerId: Long): List<AuroraBackupMmsAddress>? {
        val rows = queryRows(
            uri = uris.mmsAddress(providerId),
            projection = MMS_ADDRESS_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = "$ID_COLUMN ASC",
            limit = MAX_MMS_ADDRESSES + 1,
            map = Cursor::toMmsRestoreAddress,
        ) ?: return null
        return rows.takeIf { it.size <= MAX_MMS_ADDRESSES }
    }

    private fun readMmsParts(providerId: Long): List<MmsRestorePartRow>? {
        val rows = queryRows(
            uri = uris.mmsPart(providerId),
            projection = MMS_PART_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = "$ID_COLUMN ASC",
            limit = MAX_MMS_PARTS + 1,
            map = Cursor::toMmsRestorePartRow,
        ) ?: return null
        return rows.takeIf { it.size <= MAX_MMS_PARTS }
    }

    private fun readExactMmsPart(providerPartId: Long): MmsRestorePartRow? = queryRows(
        uri = uris.exactMmsPart(providerPartId),
        projection = MMS_PART_PROJECTION,
        selection = null,
        selectionArgs = null,
        sortOrder = null,
        limit = 2,
        map = Cursor::toMmsRestorePartRow,
    )?.singleOrNull()

    private fun readExactSms(providerId: Long): SmsRestoreRow? = queryRows(
        uri = uris.exactSms(providerId),
        projection = SMS_PROJECTION,
        selection = null,
        selectionArgs = null,
        sortOrder = null,
        limit = 2,
        map = Cursor::toSmsRestoreRow,
    )?.singleOrNull()

    private fun resolveThreadId(addresses: Set<String>): Long {
        val resolved = threadIdForAddresses(addresses)
            ?: throw IllegalArgumentException("restore thread unavailable")
        require(resolved >= 0L) { "restore thread invalid" }
        return resolved
    }

    private fun accessFailure(): AuroraRestoreProviderResult<Nothing>? = when {
        !roleHeld() -> AuroraRestoreProviderResult.RoleRequired
        !readPermissionGranted() -> AuroraRestoreProviderResult.PermissionDenied
        else -> null
    }

    private inline fun <T> withAccess(
        operation: String,
        block: () -> AuroraRestoreProviderResult<T>,
    ): AuroraRestoreProviderResult<T> {
        accessFailure()?.let { return it }
        return try {
            block()
        } catch (_: SecurityException) {
            AuroraRestoreProviderResult.PermissionDenied
        } catch (_: IllegalArgumentException) {
            unavailable(operation)
        } catch (_: IOException) {
            unavailable(operation)
        } catch (_: RuntimeException) {
            unavailable(operation)
        }
    }

    private fun <T> queryRows(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        limit: Int,
        map: (Cursor) -> T,
    ): List<T>? {
        val cursor = try {
            resolver.query(
                uri,
                projection,
                Bundle().apply {
                    selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                    selectionArgs?.let {
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
                    }
                    sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                },
                null,
            )
        } catch (_: IllegalArgumentException) {
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } ?: return null
        return cursor.use {
            buildList {
                while (size < limit && it.moveToNext()) add(map(it))
            }
        }
    }

    internal data class RestoreProviderUris(
        val sms: Uri,
        val mms: Uri,
        val mmsPartBase: Uri,
    ) {
        fun exactSms(providerId: Long): Uri = ContentUris.withAppendedId(sms, providerId)
        fun exactMms(providerId: Long): Uri = ContentUris.withAppendedId(mms, providerId)
        fun mmsAddress(providerId: Long): Uri = exactMms(providerId).buildUpon()
            .appendPath(MMS_ADDRESS_PATH)
            .build()
        fun mmsPart(providerId: Long): Uri = exactMms(providerId).buildUpon()
            .appendPath(MMS_PART_PATH)
            .build()
        fun exactMmsPart(providerPartId: Long): Uri = ContentUris.withAppendedId(
            mmsPartBase,
            providerPartId,
        )

        companion object {
            fun platform(): RestoreProviderUris = RestoreProviderUris(
                sms = Telephony.Sms.CONTENT_URI,
                mms = Telephony.Mms.CONTENT_URI,
                mmsPartBase = Uri.parse("content://mms/part"),
            )
        }
    }

    private companion object {
        const val ID_COLUMN = BaseColumns._ID
        const val SMS_TYPE = "type"
        const val SMS_THREAD_ID = "thread_id"
        const val SMS_ADDRESS = "address"
        const val SMS_BODY = "body"
        const val SMS_DATE = "date"
        const val SMS_DATE_SENT = "date_sent"
        const val SMS_READ = "read"
        const val SMS_SEEN = "seen"
        const val SMS_LOCKED = "locked"
        const val SMS_STATUS = "status"
        const val SMS_ERROR_CODE = "error_code"
        const val SMS_PROTOCOL = "protocol"
        const val SMS_REPLY_PATH = "reply_path_present"
        const val SMS_SUBJECT = "subject"
        const val SMS_SERVICE_CENTER = "service_center"
        const val SMS_SUBSCRIPTION_ID = "sub_id"
        const val SMS_CREATOR = "creator"
        const val MMS_BOX = "msg_box"
        const val MMS_THREAD_ID = "thread_id"
        const val MMS_DATE = "date"
        const val MMS_DATE_SENT = "date_sent"
        const val MMS_READ = "read"
        const val MMS_SEEN = "seen"
        const val MMS_LOCKED = "locked"
        const val MMS_SUBSCRIPTION_ID = "sub_id"
        const val MMS_MESSAGE_TYPE = "m_type"
        const val MMS_VERSION = "v"
        const val MMS_PRIORITY = "pri"
        const val MMS_STATUS = "st"
        const val MMS_RESPONSE_STATUS = "resp_st"
        const val MMS_RETRIEVE_STATUS = "retr_st"
        const val MMS_READ_REPORT = "rr"
        const val MMS_DELIVERY_REPORT = "d_rpt"
        const val MMS_REPORT_ALLOWED = "rpt_a"
        const val MMS_MESSAGE_SIZE = "m_size"
        const val MMS_EXPIRY = "exp"
        const val MMS_DELIVERY_TIME = "d_tm"
        const val MMS_SUBJECT = "sub"
        const val MMS_SUBJECT_CHARSET = "sub_cs"
        const val MMS_CONTENT_TYPE = "ct_t"
        const val MMS_CONTENT_LOCATION = "ct_l"
        const val MMS_MESSAGE_CLASS = "m_cls"
        const val MMS_TRANSACTION_ID = "tr_id"
        const val MMS_CREATOR = "creator"
        const val MMS_ADDRESS_PATH = "addr"
        const val MMS_PART_PATH = "part"
        const val MMS_ADDRESS_TYPE = "type"
        const val MMS_ADDRESS = "address"
        const val MMS_ADDRESS_CHARSET = "charset"
        const val MMS_PART_SEQUENCE = "seq"
        const val MMS_PART_PARENT = "mid"
        const val MMS_PART_CONTENT_TYPE = "ct"
        const val MMS_PART_CHARSET = "chset"
        const val MMS_PART_NAME = "name"
        const val MMS_PART_DISPOSITION = "cd"
        const val MMS_PART_FILENAME = "fn"
        const val MMS_PART_CONTENT_ID = "cid"
        const val MMS_PART_CONTENT_LOCATION = "cl"
        const val MMS_PART_TEXT = "text"
        const val MMS_PART_DATA = "_data"
        const val MAX_DUPLICATE_CANDIDATES = 200
        const val MAX_MMS_DUPLICATE_CANDIDATES = 8
        const val MAX_MMS_ADDRESSES = AuroraBackupMessageCodec.MAX_MMS_ADDRESSES
        const val MAX_MMS_PARTS = 1_000
        const val MMS_INSERT_ADDRESS_TOKEN = "insert-address-token"

        val SMS_PLACEHOLDER_PATTERN = Regex(
            "aurora\\.restore\\.[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-" +
                "[89ab][a-f0-9]{3}-[a-f0-9]{12}\\.[1-9][0-9]{0,6}",
        )
        val MMS_PLACEHOLDER_PATTERN = Regex("ar1-[a-f0-9]{32}-[1-9a-f][a-f0-9]{0,5}")
        val SAFE_RESTORED_BOXES = setOf(
            AuroraBackupMessageBox.INBOX,
            AuroraBackupMessageBox.SENT,
            AuroraBackupMessageBox.FAILED,
        )
        val SMS_PROJECTION = arrayOf(
            ID_COLUMN,
            SMS_THREAD_ID,
            SMS_TYPE,
            SMS_ADDRESS,
            SMS_BODY,
            SMS_DATE,
            SMS_DATE_SENT,
            SMS_READ,
            SMS_SEEN,
            SMS_LOCKED,
            SMS_STATUS,
            SMS_ERROR_CODE,
            SMS_PROTOCOL,
            SMS_REPLY_PATH,
            SMS_SUBJECT,
            SMS_SERVICE_CENTER,
            SMS_SUBSCRIPTION_ID,
            SMS_CREATOR,
        )
        val MMS_PROJECTION = arrayOf(
            ID_COLUMN,
            MMS_THREAD_ID,
            MMS_BOX,
            MMS_DATE,
            MMS_DATE_SENT,
            MMS_READ,
            MMS_SEEN,
            MMS_LOCKED,
            MMS_SUBSCRIPTION_ID,
            MMS_MESSAGE_TYPE,
            MMS_VERSION,
            MMS_PRIORITY,
            MMS_STATUS,
            MMS_RESPONSE_STATUS,
            MMS_RETRIEVE_STATUS,
            MMS_READ_REPORT,
            MMS_DELIVERY_REPORT,
            MMS_REPORT_ALLOWED,
            MMS_MESSAGE_SIZE,
            MMS_EXPIRY,
            MMS_DELIVERY_TIME,
            MMS_SUBJECT,
            MMS_SUBJECT_CHARSET,
            MMS_CONTENT_TYPE,
            MMS_CONTENT_LOCATION,
            MMS_MESSAGE_CLASS,
            MMS_TRANSACTION_ID,
            MMS_CREATOR,
        )
        val MMS_ADDRESS_PROJECTION = arrayOf(
            ID_COLUMN,
            MMS_ADDRESS_TYPE,
            MMS_ADDRESS,
            MMS_ADDRESS_CHARSET,
        )
        val MMS_PART_PROJECTION = arrayOf(
            ID_COLUMN,
            MMS_PART_PARENT,
            MMS_PART_SEQUENCE,
            MMS_PART_CONTENT_TYPE,
            MMS_PART_CHARSET,
            MMS_PART_NAME,
            MMS_PART_DISPOSITION,
            MMS_PART_FILENAME,
            MMS_PART_CONTENT_ID,
            MMS_PART_CONTENT_LOCATION,
            MMS_PART_TEXT,
            MMS_PART_DATA,
        )
    }
}

private data class SmsRestoreRow(
    val providerId: Long,
    val threadId: Long,
    val boxCode: Int,
    val address: String?,
    val body: String?,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val status: Int?,
    val errorCode: Int?,
    val protocol: Int?,
    val replyPathPresent: Int?,
    val subject: String?,
    val serviceCenter: String?,
    val subscriptionId: Int?,
    val creator: String?,
) {
    fun toRecord(archiveMessageId: Long): AuroraBackupSmsRecord = AuroraBackupSmsRecord(
        archiveMessageId = archiveMessageId,
        box = AuroraBackupMessageBox.decode(boxCode)
            ?: throw IllegalArgumentException("SMS box"),
        address = address,
        body = body,
        timestampMillis = timestampMillis,
        sentTimestampMillis = sentTimestampMillis,
        read = read,
        seen = seen,
        locked = locked,
        status = status,
        errorCode = errorCode,
        protocol = protocol,
        replyPathPresent = replyPathPresent,
        subject = subject,
        serviceCenter = serviceCenter,
        subscriptionId = subscriptionId,
    )

    fun preparedDigest(): AuroraRestorePreparedDigest = AuroraRestoreCanonicalDigest.sms(
        toRecord(archiveMessageId = 1),
        includeHistoricalBox = false,
    )

    fun isPlaceholder(expectedCreator: String, placeholder: String): Boolean =
        creator == expectedCreator &&
            address == placeholder &&
            boxCode == AuroraBackupMessageBox.FAILED.code &&
            body == null &&
            timestampMillis == 0L &&
            sentTimestampMillis == null &&
            read &&
            seen &&
            !locked &&
            status == Telephony.Sms.STATUS_FAILED &&
            errorCode == 0 &&
            protocol == null &&
            replyPathPresent == null &&
            subject == null &&
            serviceCenter == null &&
            subscriptionId == null

    fun exactSelection(): SqlSelection = SqlSelectionBuilder().apply {
        equal("_id", providerId)
        equal("thread_id", threadId)
        equal("type", boxCode)
        equal("address", address)
        equal("body", body)
        equal("date", timestampMillis)
        equal("date_sent", sentTimestampMillis)
        equal("read", read.toProviderInt())
        equal("seen", seen.toProviderInt())
        equal("locked", locked.toProviderInt())
        equal("status", status)
        equal("error_code", errorCode)
        equal("protocol", protocol)
        equal("reply_path_present", replyPathPresent)
        equal("subject", subject)
        equal("service_center", serviceCenter)
        equal("sub_id", subscriptionId)
        equal("creator", creator)
    }.build()
}

private fun Cursor.toSmsRestoreRow(): SmsRestoreRow = SmsRestoreRow(
    providerId = requiredLong("_id"),
    threadId = requiredLong("thread_id"),
    boxCode = requiredInt("type"),
    address = nullableString("address"),
    body = nullableString("body"),
    timestampMillis = requiredLong("date"),
    sentTimestampMillis = nullableLong("date_sent"),
    read = requiredInt("read") != 0,
    seen = requiredInt("seen") != 0,
    locked = requiredInt("locked") != 0,
    status = nullableInt("status"),
    errorCode = nullableInt("error_code"),
    protocol = nullableInt("protocol"),
    replyPathPresent = nullableInt("reply_path_present"),
    subject = nullableString("subject"),
    serviceCenter = nullableString("service_center"),
    subscriptionId = nullableInt("sub_id"),
    creator = nullableString("creator"),
)

private fun AuroraBackupSmsRecord.toPreparedSmsValues(
    creator: String,
    threadId: Long,
): ContentValues =
    ContentValues().apply {
        put("thread_id", threadId)
        putNullable("address", address)
        putNullable("body", body)
        put("date", timestampMillis)
        putNullable("date_sent", sentTimestampMillis)
        put("type", AuroraBackupMessageBox.FAILED.code)
        put("read", read.toProviderInt())
        put("seen", seen.toProviderInt())
        put("locked", locked.toProviderInt())
        putNullable("status", status)
        putNullable("error_code", errorCode)
        putNullable("protocol", protocol)
        putNullable("reply_path_present", replyPathPresent)
        putNullable("subject", subject)
        putNullable("service_center", serviceCenter)
        putNullable("sub_id", subscriptionId)
        put("creator", creator)
    }

private data class MmsRestoreRow(
    val providerId: Long,
    val threadId: Long,
    val boxCode: Int,
    val timestampSeconds: Long,
    val sentTimestampSeconds: Long?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val subscriptionId: Int?,
    val messageType: Int?,
    val version: Int?,
    val priority: Int?,
    val status: Int?,
    val responseStatus: Int?,
    val retrieveStatus: Int?,
    val readReport: Int?,
    val deliveryReport: Int?,
    val reportAllowed: Int?,
    val messageSizeBytes: Long?,
    val expirySeconds: Long?,
    val deliveryTimeSeconds: Long?,
    val subject: String?,
    val subjectCharset: Int?,
    val contentType: String?,
    val contentLocation: String?,
    val messageClass: String?,
    val transactionId: String?,
    val creator: String?,
) {
    fun toRecord(
        archiveMessageId: Long,
        addresses: List<AuroraBackupMmsAddress>,
    ): AuroraBackupMmsRecord = AuroraBackupMmsRecord(
        archiveMessageId = archiveMessageId,
        box = AuroraBackupMessageBox.decode(boxCode)
            ?: throw IllegalArgumentException("MMS box"),
        timestampMillis = timestampSeconds.toMmsMillis(),
        sentTimestampMillis = sentTimestampSeconds?.toMmsMillis(),
        read = read,
        seen = seen,
        locked = locked,
        subscriptionId = subscriptionId,
        messageType = messageType,
        version = version,
        priority = priority,
        status = status,
        responseStatus = responseStatus,
        retrieveStatus = retrieveStatus,
        readReport = readReport,
        deliveryReport = deliveryReport,
        reportAllowed = reportAllowed,
        messageSizeBytes = messageSizeBytes,
        expiryMillis = expirySeconds?.toMmsMillis(),
        deliveryTimeMillis = deliveryTimeSeconds?.toMmsMillis(),
        subject = subject,
        subjectCharset = subjectCharset,
        contentType = contentType,
        contentLocation = contentLocation,
        messageClass = messageClass,
        transactionId = transactionId,
        addresses = addresses,
    )

    fun isPlaceholder(expectedCreator: String, placeholder: String): Boolean =
        creator == expectedCreator &&
            transactionId == placeholder &&
            boxCode == AuroraBackupMessageBox.FAILED.code &&
            timestampSeconds == 0L &&
            sentTimestampSeconds == null &&
            read &&
            seen &&
            !locked &&
            subscriptionId == null &&
            messageType == null &&
            version == null &&
            priority == null &&
            status == null &&
            responseStatus == null &&
            retrieveStatus == null &&
            readReport == null &&
            deliveryReport == null &&
            reportAllowed == null &&
            messageSizeBytes == null &&
            expirySeconds == null &&
            deliveryTimeSeconds == null &&
            subject == null &&
            subjectCharset == null &&
            contentType == null &&
            contentLocation == null &&
            messageClass == null

    fun exactSelection(): SqlSelection = SqlSelectionBuilder().apply {
        equal("_id", providerId)
        equal("thread_id", threadId)
        equal("msg_box", boxCode)
        equal("date", timestampSeconds)
        equal("date_sent", sentTimestampSeconds)
        equal("read", read.toProviderInt())
        equal("seen", seen.toProviderInt())
        equal("locked", locked.toProviderInt())
        equal("sub_id", subscriptionId)
        equal("m_type", messageType)
        equal("v", version)
        equal("pri", priority)
        equal("st", status)
        equal("resp_st", responseStatus)
        equal("retr_st", retrieveStatus)
        equal("rr", readReport)
        equal("d_rpt", deliveryReport)
        equal("rpt_a", reportAllowed)
        equal("m_size", messageSizeBytes)
        equal("exp", expirySeconds)
        equal("d_tm", deliveryTimeSeconds)
        equal("sub", subject)
        equal("sub_cs", subjectCharset)
        equal("ct_t", contentType)
        equal("ct_l", contentLocation)
        equal("m_cls", messageClass)
        equal("tr_id", transactionId)
        equal("creator", creator)
    }.build()
}

private data class MmsRestorePartRow(
    val providerPartId: Long,
    val parentProviderId: Long,
    val sequence: Int,
    val contentType: String?,
    val charset: Int?,
    val name: String?,
    val contentDisposition: String?,
    val filename: String?,
    val contentId: String?,
    val contentLocation: String?,
    val text: String?,
    val dataReference: String?,
) {
    fun digest(
        parentArchiveMessageId: Long,
        resolver: ContentResolver,
        uris: AndroidAuroraRestoreProvider.RestoreProviderUris,
    ): AuroraRestoreMmsPartDigest? {
        val type = contentType ?: return null
        val record = runCatching {
            AuroraBackupDecodedMmsPart(
                parentArchiveMessageId = parentArchiveMessageId,
                sequence = sequence,
                contentType = type,
                charset = charset,
                name = name,
                contentDisposition = contentDisposition,
                filename = filename,
                contentId = contentId,
                contentLocation = contentLocation,
            )
        }.getOrNull() ?: return null
        return if (dataReference != null) {
            resolver.openInputStream(uris.exactMmsPart(providerPartId))?.use { source ->
                AuroraRestoreCanonicalDigest.mmsPart(
                    record,
                    AuroraBackupDecodedPartPayload.Binary(source),
                )
            }
        } else {
            AuroraRestoreCanonicalDigest.mmsPart(
                record,
                text?.let(AuroraBackupDecodedPartPayload::Text)
                    ?: AuroraBackupDecodedPartPayload.Empty,
            )
        }
    }
}

private fun Cursor.toMmsRestoreRow(): MmsRestoreRow = MmsRestoreRow(
    providerId = requiredLong("_id"),
    threadId = requiredLong("thread_id"),
    boxCode = requiredInt("msg_box"),
    timestampSeconds = requiredLong("date"),
    sentTimestampSeconds = nullableLong("date_sent"),
    read = requiredInt("read") != 0,
    seen = requiredInt("seen") != 0,
    locked = requiredInt("locked") != 0,
    subscriptionId = nullableInt("sub_id"),
    messageType = nullableInt("m_type"),
    version = nullableInt("v"),
    priority = nullableInt("pri"),
    status = nullableInt("st"),
    responseStatus = nullableInt("resp_st"),
    retrieveStatus = nullableInt("retr_st"),
    readReport = nullableInt("rr"),
    deliveryReport = nullableInt("d_rpt"),
    reportAllowed = nullableInt("rpt_a"),
    messageSizeBytes = nullableLong("m_size"),
    expirySeconds = nullableLong("exp"),
    deliveryTimeSeconds = nullableLong("d_tm"),
    subject = nullableString("sub"),
    subjectCharset = nullableInt("sub_cs"),
    contentType = nullableString("ct_t"),
    contentLocation = nullableString("ct_l"),
    messageClass = nullableString("m_cls"),
    transactionId = nullableString("tr_id"),
    creator = nullableString("creator"),
)

private fun Cursor.toMmsRestoreAddress(): AuroraBackupMmsAddress = AuroraBackupMmsAddress(
    type = requiredInt("type"),
    address = nullableString("address") ?: throw IllegalArgumentException("MMS address"),
    charset = nullableInt("charset"),
)

private fun Cursor.toMmsRestorePartRow(): MmsRestorePartRow = MmsRestorePartRow(
    providerPartId = requiredLong("_id"),
    parentProviderId = requiredLong("mid"),
    sequence = requiredInt("seq"),
    contentType = nullableString("ct"),
    charset = nullableInt("chset"),
    name = nullableString("name"),
    contentDisposition = nullableString("cd"),
    filename = nullableString("fn"),
    contentId = nullableString("cid"),
    contentLocation = nullableString("cl"),
    text = nullableString("text"),
    dataReference = nullableString("_data"),
)

private fun AuroraBackupMmsRecord.toPreparedMmsValues(
    creator: String,
    threadId: Long,
): ContentValues =
    ContentValues().apply {
        put("thread_id", threadId)
        put("msg_box", AuroraBackupMessageBox.FAILED.code)
        put("date", timestampMillis.toMmsSeconds())
        putNullable("date_sent", sentTimestampMillis?.toMmsSeconds())
        put("read", read.toProviderInt())
        put("seen", seen.toProviderInt())
        put("locked", locked.toProviderInt())
        putNullable("sub_id", subscriptionId)
        putNullable("m_type", messageType)
        putNullable("v", version)
        putNullable("pri", priority)
        putNullable("st", status)
        putNullable("resp_st", responseStatus)
        putNullable("retr_st", retrieveStatus)
        putNullable("rr", readReport)
        putNullable("d_rpt", deliveryReport)
        putNullable("rpt_a", reportAllowed)
        putNullable("m_size", messageSizeBytes)
        putNullable("exp", expiryMillis?.toMmsSeconds())
        putNullable("d_tm", deliveryTimeMillis?.toMmsSeconds())
        putNullable("sub", subject)
        putNullable("sub_cs", subjectCharset)
        putNullable("ct_t", contentType)
        putNullable("ct_l", contentLocation)
        putNullable("m_cls", messageClass)
        putNullable("tr_id", transactionId)
        put("creator", creator)
    }

private fun AuroraBackupDecodedMmsPart.toMmsPartValues(): ContentValues = ContentValues().apply {
    put("seq", sequence)
    put("ct", contentType)
    putNullable("chset", charset)
    putNullable("name", name)
    putNullable("cd", contentDisposition)
    putNullable("fn", filename)
    putNullable("cid", contentId)
    putNullable("cl", contentLocation)
}

private fun AuroraBackupMmsRecord.requireProviderMmsTimes() {
    timestampMillis.toMmsSeconds()
    sentTimestampMillis?.toMmsSeconds()
    expiryMillis?.toMmsSeconds()
    deliveryTimeMillis?.toMmsSeconds()
}

private fun Long.toMmsSeconds(): Long {
    require(this >= 0L && this % 1_000L == 0L) { "MMS timestamp precision" }
    return this / 1_000L
}

private fun Long.toMmsMillis(): Long {
    require(this >= 0L && this <= Long.MAX_VALUE / 1_000L) { "MMS timestamp range" }
    return this * 1_000L
}

private fun AuroraRestoreOwnership.allowedRollbackBoxes(): Set<Int> = setOf(
    AuroraBackupMessageBox.FAILED.code,
    when (targetBox) {
        AuroraBackupMessageBox.INBOX -> AuroraBackupMessageBox.INBOX.code
        AuroraBackupMessageBox.SENT -> AuroraBackupMessageBox.SENT.code
        else -> AuroraBackupMessageBox.FAILED.code
    },
)

private data class SqlSelection(
    val sql: String,
    val arguments: Array<String>,
)

private class SqlSelectionBuilder {
    private val clauses = mutableListOf<String>()
    private val arguments = mutableListOf<String>()

    fun equal(column: String, value: Any?) {
        if (value == null) {
            clauses += "$column IS NULL"
        } else {
            clauses += "$column = ?"
            arguments += value.toString()
        }
    }

    fun build(): SqlSelection = SqlSelection(
        sql = clauses.joinToString(" AND "),
        arguments = arguments.toTypedArray(),
    )
}

private fun ContentValues.putNullable(column: String, value: String?) {
    if (value == null) putNull(column) else put(column, value)
}

private fun ContentValues.putNullable(column: String, value: Int?) {
    if (value == null) putNull(column) else put(column, value)
}

private fun ContentValues.putNullable(column: String, value: Long?) {
    if (value == null) putNull(column) else put(column, value)
}

private fun Boolean.toProviderInt(): Int = if (this) 1 else 0

private fun Cursor.requiredLong(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.requiredInt(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun Cursor.nullableLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.nullableInt(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.nullableString(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun <T> success(value: T): AuroraRestoreProviderResult<T> =
    AuroraRestoreProviderResult.Success(value)

private fun <T> unavailable(operation: String): AuroraRestoreProviderResult<T> =
    AuroraRestoreProviderResult.Unavailable(operation)

private fun <T> updateFailure(
    updated: Int,
    operation: String,
): AuroraRestoreProviderResult<T> = if (updated == 0) {
    AuroraRestoreProviderResult.OwnershipConflict
} else {
    unavailable(operation)
}

private fun <T> preparedMismatch(
    cleanup: AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome>,
): AuroraRestoreProviderResult<T> = when (cleanup) {
    is AuroraRestoreProviderResult.Success -> AuroraRestoreProviderResult.OwnershipConflict
    is AuroraRestoreProviderResult.Unavailable -> cleanup
    AuroraRestoreProviderResult.OwnershipConflict -> AuroraRestoreProviderResult.OwnershipConflict
    AuroraRestoreProviderResult.PermissionDenied -> AuroraRestoreProviderResult.PermissionDenied
    AuroraRestoreProviderResult.RoleRequired -> AuroraRestoreProviderResult.RoleRequired
}
