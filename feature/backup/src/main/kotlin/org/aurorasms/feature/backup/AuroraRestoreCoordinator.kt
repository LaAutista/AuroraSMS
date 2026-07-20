// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.IOException
import java.io.InputStream

sealed interface AuroraRestoreProviderResult<out T> {
    data class Success<T>(val value: T) : AuroraRestoreProviderResult<T>
    data object RoleRequired : AuroraRestoreProviderResult<Nothing>
    data object PermissionDenied : AuroraRestoreProviderResult<Nothing>
    data class Unavailable(val operation: String) : AuroraRestoreProviderResult<Nothing>
    data object OwnershipConflict : AuroraRestoreProviderResult<Nothing>
}

enum class AuroraRestoreRollbackOutcome {
    REMOVED,
    ALREADY_ABSENT,
    OWNERSHIP_CONFLICT,
}

data class AuroraRestorePreparedDigest(val value: String) {
    init {
        require(value.matches(Regex("[a-f0-9]{64}")))
    }

    override fun toString(): String = "AuroraRestorePreparedDigest(REDACTED)"
}

interface AuroraMmsDuplicateCheck {
    fun acceptPart(
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    ): AuroraRestoreProviderResult<Unit>

    fun finish(): AuroraRestoreProviderResult<Boolean>
}

/** Platform boundary used only by an explicitly confirmed restore. */
interface AuroraRestoreProvider {
    fun preflight(): AuroraRestoreProviderResult<Unit>

    fun isExactDuplicateSms(record: AuroraBackupSmsRecord): AuroraRestoreProviderResult<Boolean>

    fun beginMmsDuplicateCheck(record: AuroraBackupMmsRecord):
        AuroraRestoreProviderResult<AuroraMmsDuplicateCheck>

    /** Inserts one known-unsent FAILED SMS with only the opaque placeholder identity. */
    fun insertSmsPlaceholder(
        record: AuroraBackupSmsRecord,
        placeholderAddress: String,
    ): AuroraRestoreProviderResult<Long>

    /** Writes final historical fields but deliberately leaves the provider box FAILED. */
    fun prepareSms(
        providerRowId: Long,
        placeholderAddress: String,
        record: AuroraBackupSmsRecord,
        expectedDigest: AuroraRestorePreparedDigest,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest>

    /** Inserts one known-unsent FAILED MMS parent with only the opaque transaction identity. */
    fun insertMmsPlaceholder(
        record: AuroraBackupMmsRecord,
        placeholderTransactionId: String,
    ): AuroraRestoreProviderResult<Long>

    fun insertMmsPart(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    ): AuroraRestoreProviderResult<AuroraRestoreMmsPartDigest>

    /** Writes final parent/address metadata while deliberately leaving the provider box FAILED. */
    fun prepareMms(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupMmsRecord,
        expectedDigest: AuroraRestorePreparedDigest,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest>

    /** The only operation allowed to expose a staged row in its safe historical box. */
    fun commitHistoricalBox(
        ownership: AuroraRestoreOwnership,
        safeTargetBox: AuroraBackupMessageBox,
    ): AuroraRestoreProviderResult<Unit>

    /** Removes only the exact app-created row or deterministic pre-ID placeholder. */
    fun rollback(
        session: AuroraRestoreSession,
        ownership: AuroraRestoreOwnership,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome>
}

data class AuroraRestoreSummary(
    val archive: AuroraBackupSummary,
    val importedMessages: Long,
    val skippedDuplicates: Long,
) {
    init {
        require(importedMessages >= 0L && skippedDuplicates >= 0L)
        require(importedMessages + skippedDuplicates == archive.smsCount + archive.mmsCount)
    }
}

enum class AuroraRestoreFailure {
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    RECOVERY_REQUIRED,
    INVALID_ARCHIVE,
    DUPLICATE_SCAN_FAILED,
    JOURNAL_FAILED,
    PROVIDER_FAILED,
    OWNERSHIP_CONFLICT,
    ROLLBACK_INCOMPLETE,
    SOURCE_FAILED,
}

sealed interface AuroraRestoreResult {
    data class Success(val summary: AuroraRestoreSummary) : AuroraRestoreResult
    data class Failed(
        val reason: AuroraRestoreFailure,
        val rollbackComplete: Boolean,
    ) : AuroraRestoreResult
}

/**
 * Three bounded passes over one credential-encrypted, already validated archive:
 * duplicate analysis, non-sendable staging, then journal-driven box commit.
 */
class AuroraRestoreCoordinator(
    private val archive: AuroraBackupArchive,
    private val journal: AuroraRestoreJournal,
    private val provider: AuroraRestoreProvider,
) {
    fun restore(openValidatedPlaintext: () -> InputStream): AuroraRestoreResult {
        provider.preflight().failureOrNull()?.let { return failure(it, rollbackComplete = true) }
        when (journal.recoverySnapshot()) {
            AuroraRestoreJournalRecoveryResult.None -> Unit
            is AuroraRestoreJournalRecoveryResult.Active,
            is AuroraRestoreJournalRecoveryResult.Complete,
            AuroraRestoreJournalRecoveryResult.Corrupt,
            -> return failure(AuroraRestoreFailure.RECOVERY_REQUIRED, rollbackComplete = false)
        }

        val validation = withArchive(openValidatedPlaintext) { archive.validateMessagePlaintext(it) }
            ?: return failure(AuroraRestoreFailure.SOURCE_FAILED, rollbackComplete = true)
        val archiveSummary = (validation as? AuroraBackupValidationResult.Success)?.summary
            ?: return failure(AuroraRestoreFailure.INVALID_ARCHIVE, rollbackComplete = true)
        val messageCount = archiveSummary.smsCount + archiveSummary.mmsCount
        if (messageCount > Int.MAX_VALUE - 1L) {
            return failure(AuroraRestoreFailure.INVALID_ARCHIVE, rollbackComplete = true)
        }
        val duplicates = BooleanArray(messageCount.toInt() + 1)
        val duplicateFailure = scanDuplicates(openValidatedPlaintext, duplicates)
        if (duplicateFailure != null) return failure(duplicateFailure, rollbackComplete = true)

        val session = when (val begin = journal.begin()) {
            is AuroraRestoreJournalBeginResult.Success -> begin.session
            AuroraRestoreJournalBeginResult.ExistingRecoveryRequired -> {
                return failure(AuroraRestoreFailure.RECOVERY_REQUIRED, rollbackComplete = false)
            }
            AuroraRestoreJournalBeginResult.StorageFailure -> {
                return failure(AuroraRestoreFailure.JOURNAL_FAILED, rollbackComplete = true)
            }
        }
        val stageFailure = stage(openValidatedPlaintext, session, duplicates)
        if (stageFailure != null) return failWithRollback(session, stageFailure)

        var commitFailure: AuroraRestoreFailure? = null
        val parsed = journal.forEachOwnership(session) { ownership ->
            if (
                commitFailure != null ||
                ownership.providerRowId == null ||
                ownership.preparedDigest == null
            ) {
                commitFailure = commitFailure ?: AuroraRestoreFailure.JOURNAL_FAILED
                return@forEachOwnership
            }
            val target = ownership.targetBox.safeRestoredBox()
            provider.commitHistoricalBox(ownership, target).failureOrNull()?.let { commitFailure = it }
        }
        if (!parsed || commitFailure != null) {
            return failWithRollback(
                session,
                commitFailure ?: AuroraRestoreFailure.JOURNAL_FAILED,
            )
        }
        if (!journal.markComplete(session)) {
            return failWithRollback(session, AuroraRestoreFailure.JOURNAL_FAILED)
        }
        // A durable COMPLETE record makes leftover cleanup safe after process death.
        journal.clear(session)
        val skipped = duplicates.count { it }.toLong()
        return AuroraRestoreResult.Success(
            AuroraRestoreSummary(
                archive = archiveSummary,
                importedMessages = messageCount - skipped,
                skippedDuplicates = skipped,
            ),
        )
    }

    /** Startup/error recovery. A COMPLETE journal needs cleanup only; ACTIVE needs rollback. */
    fun recover(): AuroraRestoreResult? = when (val recovery = journal.recoverySnapshot()) {
        AuroraRestoreJournalRecoveryResult.None -> null
        is AuroraRestoreJournalRecoveryResult.Complete -> {
            if (journal.clear(recovery.session)) null else failure(
                AuroraRestoreFailure.JOURNAL_FAILED,
                rollbackComplete = false,
            )
        }
        is AuroraRestoreJournalRecoveryResult.Active -> {
            val complete = rollback(recovery.session)
            if (complete) null else failure(AuroraRestoreFailure.ROLLBACK_INCOMPLETE, false)
        }
        AuroraRestoreJournalRecoveryResult.Corrupt -> failure(
            AuroraRestoreFailure.RECOVERY_REQUIRED,
            rollbackComplete = false,
        )
    }

    private fun scanDuplicates(
        openValidatedPlaintext: () -> InputStream,
        duplicates: BooleanArray,
    ): AuroraRestoreFailure? {
        var activeMmsId: Long? = null
        var activeCheck: AuroraMmsDuplicateCheck? = null
        var failure: AuroraRestoreFailure? = null

        fun finishMms() {
            val id = activeMmsId ?: return
            val check = activeCheck ?: run {
                failure = AuroraRestoreFailure.DUPLICATE_SCAN_FAILED
                return
            }
            when (val result = check.finish()) {
                is AuroraRestoreProviderResult.Success -> duplicates[id.toInt()] = result.value
                else -> failure = result.failureOrNull() ?: AuroraRestoreFailure.DUPLICATE_SCAN_FAILED
            }
            activeMmsId = null
            activeCheck = null
        }

        val visited = withArchive(openValidatedPlaintext) { source ->
            archive.visitMessagePlaintext(
                source,
                object : AuroraBackupMessageVisitor {
                    override fun onSms(record: AuroraBackupSmsRecord) {
                        finishMms()
                        if (failure != null) throw CoordinatorAbortException()
                        when (val result = provider.isExactDuplicateSms(record)) {
                            is AuroraRestoreProviderResult.Success -> {
                                duplicates[record.archiveMessageId.toInt()] = result.value
                            }
                            else -> {
                                failure = result.failureOrNull() ?: AuroraRestoreFailure.DUPLICATE_SCAN_FAILED
                                throw CoordinatorAbortException()
                            }
                        }
                    }

                    override fun onMms(record: AuroraBackupMmsRecord) {
                        finishMms()
                        if (failure != null) throw CoordinatorAbortException()
                        when (val result = provider.beginMmsDuplicateCheck(record)) {
                            is AuroraRestoreProviderResult.Success -> {
                                activeMmsId = record.archiveMessageId
                                activeCheck = result.value
                            }
                            else -> {
                                failure = result.failureOrNull() ?: AuroraRestoreFailure.DUPLICATE_SCAN_FAILED
                                throw CoordinatorAbortException()
                            }
                        }
                    }

                    override fun onMmsPart(
                        record: AuroraBackupDecodedMmsPart,
                        payload: AuroraBackupDecodedPartPayload,
                    ) {
                        val check = activeCheck ?: run {
                            failure = AuroraRestoreFailure.INVALID_ARCHIVE
                            throw CoordinatorAbortException()
                        }
                        when (val result = check.acceptPart(record, payload)) {
                            is AuroraRestoreProviderResult.Success -> Unit
                            else -> {
                                failure = result.failureOrNull() ?: AuroraRestoreFailure.DUPLICATE_SCAN_FAILED
                                throw CoordinatorAbortException()
                            }
                        }
                    }
                },
            )
        } ?: return failure ?: AuroraRestoreFailure.SOURCE_FAILED
        if (visited is AuroraBackupValidationResult.Success) finishMms()
        return failure ?: if (visited is AuroraBackupValidationResult.Success) {
            null
        } else {
            AuroraRestoreFailure.INVALID_ARCHIVE
        }
    }

    private fun stage(
        openValidatedPlaintext: () -> InputStream,
        session: AuroraRestoreSession,
        duplicates: BooleanArray,
    ): AuroraRestoreFailure? {
        var activeMms: StagedMms? = null
        var failure: AuroraRestoreFailure? = null

        fun finishMms() {
            val staged = activeMms ?: return
            if (!staged.duplicate) {
                val expected = staged.digest?.finish() ?: run {
                    failure = AuroraRestoreFailure.JOURNAL_FAILED
                    activeMms = null
                    return
                }
                if (!journal.recordExpected(
                        session,
                        staged.record.archiveMessageId,
                        AuroraRestoreProviderKind.MMS,
                        staged.providerRowId,
                        expected.value,
                    )
                ) {
                    failure = AuroraRestoreFailure.JOURNAL_FAILED
                } else {
                    val prepared = provider.prepareMms(
                        staged.providerRowId,
                        staged.placeholder,
                        staged.record,
                        expected,
                    ).valueOrNull { failure = it }
                    if (prepared != null && prepared != expected) {
                        failure = AuroraRestoreFailure.OWNERSHIP_CONFLICT
                    } else if (
                        prepared != null &&
                        !journal.recordPrepared(
                            session,
                            staged.record.archiveMessageId,
                            AuroraRestoreProviderKind.MMS,
                            staged.providerRowId,
                            prepared.value,
                        )
                    ) {
                        failure = AuroraRestoreFailure.JOURNAL_FAILED
                    }
                }
            }
            activeMms = null
        }

        val visited = withArchive(openValidatedPlaintext) { source ->
            archive.visitMessagePlaintext(
                source,
                object : AuroraBackupMessageVisitor {
                    override fun onSms(record: AuroraBackupSmsRecord) {
                        finishMms()
                        if (failure != null) throw CoordinatorAbortException()
                        if (duplicates[record.archiveMessageId.toInt()]) return
                        if (
                            !journal.reserve(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.SMS,
                                record.box,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                        val placeholder = AuroraRestorePlaceholder.smsAddress(session, record.archiveMessageId)
                        val providerId = provider.insertSmsPlaceholder(record, placeholder).valueOrAbort(::setFailure)
                        if (
                            !journal.recordInserted(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.SMS,
                                providerId,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                        val expected = AuroraRestoreCanonicalDigest.sms(record, includeHistoricalBox = false)
                        if (
                            !journal.recordExpected(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.SMS,
                                providerId,
                                expected.value,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                        val prepared = provider.prepareSms(providerId, placeholder, record, expected)
                            .valueOrAbort(::setFailure)
                        if (prepared != expected) {
                            failure = AuroraRestoreFailure.OWNERSHIP_CONFLICT
                            throw CoordinatorAbortException()
                        }
                        if (
                            !journal.recordPrepared(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.SMS,
                                providerId,
                                prepared.value,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                    }

                    override fun onMms(record: AuroraBackupMmsRecord) {
                        finishMms()
                        if (failure != null) throw CoordinatorAbortException()
                        if (duplicates[record.archiveMessageId.toInt()]) {
                            activeMms = StagedMms(record, true, 0L, "", null)
                            return
                        }
                        if (
                            !journal.reserve(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.MMS,
                                record.box,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                        val placeholder = AuroraRestorePlaceholder.mmsTransactionId(
                            session,
                            record.archiveMessageId,
                        )
                        val providerId = provider.insertMmsPlaceholder(record, placeholder)
                            .valueOrAbort(::setFailure)
                        if (
                            !journal.recordInserted(
                                session,
                                record.archiveMessageId,
                                AuroraRestoreProviderKind.MMS,
                                providerId,
                            )
                        ) {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                        activeMms = StagedMms(
                            record,
                            false,
                            providerId,
                            placeholder,
                            AuroraRestoreCanonicalDigest.beginMms(record, includeHistoricalBox = false),
                        )
                    }

                    override fun onMmsPart(
                        record: AuroraBackupDecodedMmsPart,
                        payload: AuroraBackupDecodedPartPayload,
                    ) {
                        val staged = activeMms ?: run {
                            failure = AuroraRestoreFailure.INVALID_ARCHIVE
                            throw CoordinatorAbortException()
                        }
                        if (staged.duplicate) {
                            if (payload is AuroraBackupDecodedPartPayload.Binary) payload.discard()
                            return
                        }
                        val partDigest = provider.insertMmsPart(
                            staged.providerRowId,
                            staged.placeholder,
                            record,
                            payload,
                        ).valueOrAbort(::setFailure)
                        staged.digest?.accept(partDigest) ?: run {
                            failure = AuroraRestoreFailure.JOURNAL_FAILED
                            throw CoordinatorAbortException()
                        }
                    }

                    private fun setFailure(reason: AuroraRestoreFailure) {
                        failure = reason
                    }
                },
            )
        } ?: return failure ?: AuroraRestoreFailure.SOURCE_FAILED
        if (visited is AuroraBackupValidationResult.Success) finishMms()
        return failure ?: if (visited is AuroraBackupValidationResult.Success) {
            null
        } else {
            AuroraRestoreFailure.INVALID_ARCHIVE
        }
    }

    private fun failWithRollback(
        session: AuroraRestoreSession,
        reason: AuroraRestoreFailure,
    ): AuroraRestoreResult = if (rollback(session)) {
        failure(reason, rollbackComplete = true)
    } else {
        failure(AuroraRestoreFailure.ROLLBACK_INCOMPLETE, rollbackComplete = false)
    }

    private fun rollback(session: AuroraRestoreSession): Boolean {
        var complete = true
        val parsed = journal.forEachOwnership(session) { ownership ->
            when (val result = provider.rollback(session, ownership)) {
                is AuroraRestoreProviderResult.Success -> {
                    if (result.value == AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT) complete = false
                }
                else -> complete = false
            }
        }
        if (!parsed || !complete) return false
        return journal.clear(session)
    }

    private inline fun <T> withArchive(
        openValidatedPlaintext: () -> InputStream,
        action: (InputStream) -> T,
    ): T? = try {
        openValidatedPlaintext().use(action)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private data class StagedMms(
        val record: AuroraBackupMmsRecord,
        val duplicate: Boolean,
        val providerRowId: Long,
        val placeholder: String,
        val digest: AuroraRestoreMmsDigestAccumulator?,
    )

    private companion object {
        fun failure(reason: AuroraRestoreFailure, rollbackComplete: Boolean): AuroraRestoreResult.Failed =
            AuroraRestoreResult.Failed(reason, rollbackComplete)
    }
}

internal fun AuroraBackupMessageBox.safeRestoredBox(): AuroraBackupMessageBox = when (this) {
    AuroraBackupMessageBox.INBOX -> AuroraBackupMessageBox.INBOX
    AuroraBackupMessageBox.SENT -> AuroraBackupMessageBox.SENT
    AuroraBackupMessageBox.DRAFT,
    AuroraBackupMessageBox.OUTBOX,
    AuroraBackupMessageBox.FAILED,
    AuroraBackupMessageBox.QUEUED,
    -> AuroraBackupMessageBox.FAILED
}

private fun AuroraRestoreProviderResult<*>.failureOrNull(): AuroraRestoreFailure? = when (this) {
    is AuroraRestoreProviderResult.Success -> null
    AuroraRestoreProviderResult.RoleRequired -> AuroraRestoreFailure.ROLE_REQUIRED
    AuroraRestoreProviderResult.PermissionDenied -> AuroraRestoreFailure.PERMISSION_DENIED
    is AuroraRestoreProviderResult.Unavailable -> AuroraRestoreFailure.PROVIDER_FAILED
    AuroraRestoreProviderResult.OwnershipConflict -> AuroraRestoreFailure.OWNERSHIP_CONFLICT
}

private fun <T> AuroraRestoreProviderResult<T>.valueOrAbort(
    recordFailure: (AuroraRestoreFailure) -> Unit,
): T = when (this) {
    is AuroraRestoreProviderResult.Success -> value
    else -> {
        recordFailure(failureOrNull() ?: AuroraRestoreFailure.PROVIDER_FAILED)
        throw CoordinatorAbortException()
    }
}

private fun <T> AuroraRestoreProviderResult<T>.valueOrNull(
    recordFailure: (AuroraRestoreFailure) -> Unit,
): T? = when (this) {
    is AuroraRestoreProviderResult.Success -> value
    else -> {
        recordFailure(failureOrNull() ?: AuroraRestoreFailure.PROVIDER_FAILED)
        null
    }
}

private class CoordinatorAbortException : RuntimeException()
