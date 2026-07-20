// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuroraRestoreCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun duplicatesAreSkippedAndUnsafeBoxesCommitOnlyAsFailedHistory() {
        val fixture = fixture(
            sequenceOf(
                AuroraBackupMessageCodec.smsEntry(sms(1, AuroraBackupMessageBox.INBOX)),
                AuroraBackupMessageCodec.mmsEntry(mms(2, AuroraBackupMessageBox.SENT)),
                AuroraBackupMessageCodec.mmsPartEntry(binaryPart(2)),
                AuroraBackupMessageCodec.smsEntry(sms(3, AuroraBackupMessageBox.OUTBOX)),
            ),
        )
        fixture.provider.duplicateSmsIds += 1L

        val result = fixture.coordinator.restore(fixture::openPlaintext)

        val success = result as AuroraRestoreResult.Success
        assertEquals(2L, success.summary.importedMessages)
        assertEquals(1L, success.summary.skippedDuplicates)
        assertEquals(listOf(2L, 3L), fixture.provider.insertedArchiveIds)
        assertEquals(4, fixture.provider.binaryBytes)
        assertEquals(
            listOf(AuroraBackupMessageBox.SENT, AuroraBackupMessageBox.FAILED),
            fixture.provider.committedBoxes,
        )
        assertTrue(fixture.provider.rollbackOwnership.isEmpty())
        assertEquals(AuroraRestoreJournalRecoveryResult.None, fixture.journal.recoverySnapshot())
    }

    @Test
    fun partFailureRollsBackEveryJournaledProviderOwner() {
        val fixture = fixture(
            sequenceOf(
                AuroraBackupMessageCodec.mmsEntry(mms(1, AuroraBackupMessageBox.INBOX)),
                AuroraBackupMessageCodec.mmsPartEntry(binaryPart(1)),
            ),
        )
        fixture.provider.failPartInsert = true

        assertEquals(
            AuroraRestoreResult.Failed(AuroraRestoreFailure.PROVIDER_FAILED, rollbackComplete = true),
            fixture.coordinator.restore(fixture::openPlaintext),
        )
        assertEquals(1, fixture.provider.rollbackOwnership.size)
        assertEquals(1L, fixture.provider.rollbackOwnership.single().archiveMessageId)
        assertEquals(AuroraRestoreJournalRecoveryResult.None, fixture.journal.recoverySnapshot())
        assertTrue(fixture.provider.committedBoxes.isEmpty())
    }

    @Test
    fun commitConflictStillAttemptsRollbackForEveryOwnedRow() {
        val fixture = fixture(
            sequenceOf(
                AuroraBackupMessageCodec.smsEntry(sms(1, AuroraBackupMessageBox.INBOX)),
                AuroraBackupMessageCodec.smsEntry(sms(2, AuroraBackupMessageBox.SENT)),
            ),
        )
        fixture.provider.failCommitArchiveId = 2L

        assertEquals(
            AuroraRestoreResult.Failed(AuroraRestoreFailure.OWNERSHIP_CONFLICT, rollbackComplete = true),
            fixture.coordinator.restore(fixture::openPlaintext),
        )
        assertEquals(listOf(1L, 2L), fixture.provider.rollbackOwnership.map { it.archiveMessageId })
        assertEquals(AuroraRestoreJournalRecoveryResult.None, fixture.journal.recoverySnapshot())
    }

    @Test
    fun providerPrepareMismatchRollsBackByTheDurableExpectedDigest() {
        val fixture = fixture(
            sequenceOf(AuroraBackupMessageCodec.smsEntry(sms(1, AuroraBackupMessageBox.INBOX))),
        )
        fixture.provider.returnWrongSmsPreparedDigest = true

        assertEquals(
            AuroraRestoreResult.Failed(AuroraRestoreFailure.OWNERSHIP_CONFLICT, rollbackComplete = true),
            fixture.coordinator.restore(fixture::openPlaintext),
        )
        val ownership = fixture.provider.rollbackOwnership.single()
        assertEquals(1L, ownership.archiveMessageId)
        assertTrue(ownership.preparedDigest != null)
        assertTrue(fixture.provider.committedBoxes.isEmpty())
        assertEquals(AuroraRestoreJournalRecoveryResult.None, fixture.journal.recoverySnapshot())
    }

    @Test
    fun startupRecoveryFindsTheDeterministicPreIdPlaceholder() {
        val fixture = fixture(emptySequence())
        val session = (fixture.journal.begin() as AuroraRestoreJournalBeginResult.Success).session
        assertTrue(
            fixture.journal.reserve(
                session,
                archiveMessageId = 1,
                providerKind = AuroraRestoreProviderKind.SMS,
                targetBox = AuroraBackupMessageBox.INBOX,
            ),
        )

        assertEquals(null, fixture.coordinator.recover())
        val recovered = fixture.provider.rollbackOwnership.single()
        assertEquals(null, recovered.providerRowId)
        assertEquals(
            AuroraRestorePlaceholder.smsAddress(session, 1),
            fixture.provider.rolledBackPlaceholder,
        )
        assertEquals(AuroraRestoreJournalRecoveryResult.None, fixture.journal.recoverySnapshot())
    }

    private fun fixture(entries: Sequence<AuroraBackupEntry>): Fixture {
        val archive = AuroraBackupArchive()
        val passphrase = "coordinator test secret".toCharArray()
        val encrypted = ByteArrayOutputStream()
        assertTrue(
            archive.writeEncrypted(entries, passphrase, encrypted) is AuroraBackupWriteResult.Success,
        )
        val plaintext = ByteArrayOutputStream()
        assertTrue(
            archive.decryptToPending(
                ByteArrayInputStream(encrypted.toByteArray()),
                passphrase,
                plaintext,
            ) is AuroraBackupDecryptResult.Success,
        )
        val directory = temporaryFolder.newFolder()
        val journal = AuroraRestoreJournal(
            directory,
            nowMillis = { 10L },
            newSession = { TEST_SESSION },
        )
        val provider = FakeRestoreProvider()
        return Fixture(
            plaintext = plaintext.toByteArray(),
            journal = journal,
            provider = provider,
            coordinator = AuroraRestoreCoordinator(archive, journal, provider),
        )
    }

    private data class Fixture(
        val plaintext: ByteArray,
        val journal: AuroraRestoreJournal,
        val provider: FakeRestoreProvider,
        val coordinator: AuroraRestoreCoordinator,
    ) {
        fun openPlaintext() = ByteArrayInputStream(plaintext)
    }

    private companion object {
        const val TEST_SESSION = "00000000-0000-4000-8000-000000000003"
    }
}

private class FakeRestoreProvider : AuroraRestoreProvider {
    val duplicateSmsIds = mutableSetOf<Long>()
    val insertedArchiveIds = mutableListOf<Long>()
    val committedBoxes = mutableListOf<AuroraBackupMessageBox>()
    val rollbackOwnership = mutableListOf<AuroraRestoreOwnership>()
    var binaryBytes = 0
    var failPartInsert = false
    var returnWrongSmsPreparedDigest = false
    var failCommitArchiveId: Long? = null
    var rolledBackPlaceholder: String? = null
    private var nextProviderId = 100L
    private val mmsDigests = mutableMapOf<Long, AuroraRestoreMmsDigestAccumulator>()

    override fun preflight(): AuroraRestoreProviderResult<Unit> = success(Unit)

    override fun isExactDuplicateSms(record: AuroraBackupSmsRecord): AuroraRestoreProviderResult<Boolean> =
        success(record.archiveMessageId in duplicateSmsIds)

    override fun beginMmsDuplicateCheck(
        record: AuroraBackupMmsRecord,
    ): AuroraRestoreProviderResult<AuroraMmsDuplicateCheck> = success(
        object : AuroraMmsDuplicateCheck {
            override fun acceptPart(
                record: AuroraBackupDecodedMmsPart,
                payload: AuroraBackupDecodedPartPayload,
            ): AuroraRestoreProviderResult<Unit> {
                if (payload is AuroraBackupDecodedPartPayload.Binary) payload.discard()
                return success(Unit)
            }

            override fun finish(): AuroraRestoreProviderResult<Boolean> = success(false)
        },
    )

    override fun insertSmsPlaceholder(
        record: AuroraBackupSmsRecord,
        placeholderAddress: String,
    ): AuroraRestoreProviderResult<Long> {
        check(placeholderAddress.contains(TEST_MARKER))
        insertedArchiveIds += record.archiveMessageId
        return success(nextProviderId++)
    }

    override fun prepareSms(
        providerRowId: Long,
        placeholderAddress: String,
        record: AuroraBackupSmsRecord,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest> = success(
        if (returnWrongSmsPreparedDigest) {
            AuroraRestorePreparedDigest("f".repeat(64))
        } else {
            AuroraRestoreCanonicalDigest.sms(record, includeHistoricalBox = false)
        },
    )

    override fun insertMmsPlaceholder(
        record: AuroraBackupMmsRecord,
        placeholderTransactionId: String,
    ): AuroraRestoreProviderResult<Long> {
        check(placeholderTransactionId.startsWith("ar1-"))
        insertedArchiveIds += record.archiveMessageId
        val providerId = nextProviderId++
        mmsDigests[providerId] = AuroraRestoreCanonicalDigest.beginMms(
            record,
            includeHistoricalBox = false,
        )
        return success(providerId)
    }

    override fun insertMmsPart(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupDecodedMmsPart,
        payload: AuroraBackupDecodedPartPayload,
    ): AuroraRestoreProviderResult<AuroraRestoreMmsPartDigest> {
        if (failPartInsert) return AuroraRestoreProviderResult.Unavailable("synthetic part failure")
        val output = if (payload is AuroraBackupDecodedPartPayload.Binary) ByteArrayOutputStream() else null
        val partDigest = AuroraRestoreCanonicalDigest.mmsPart(record, payload, output)
        binaryBytes += output?.size() ?: 0
        mmsDigests.getValue(providerRowId).accept(partDigest)
        return success(partDigest)
    }

    override fun prepareMms(
        providerRowId: Long,
        placeholderTransactionId: String,
        record: AuroraBackupMmsRecord,
    ): AuroraRestoreProviderResult<AuroraRestorePreparedDigest> = success(
        mmsDigests.getValue(providerRowId).finish(),
    )

    override fun commitHistoricalBox(
        ownership: AuroraRestoreOwnership,
        safeTargetBox: AuroraBackupMessageBox,
    ): AuroraRestoreProviderResult<Unit> {
        if (ownership.archiveMessageId == failCommitArchiveId) {
            return AuroraRestoreProviderResult.OwnershipConflict
        }
        committedBoxes += safeTargetBox
        return success(Unit)
    }

    override fun rollback(
        session: AuroraRestoreSession,
        ownership: AuroraRestoreOwnership,
    ): AuroraRestoreProviderResult<AuroraRestoreRollbackOutcome> {
        rollbackOwnership += ownership
        if (ownership.providerRowId == null) {
            rolledBackPlaceholder = when (ownership.providerKind) {
                AuroraRestoreProviderKind.SMS -> {
                    AuroraRestorePlaceholder.smsAddress(session, ownership.archiveMessageId)
                }
                AuroraRestoreProviderKind.MMS -> {
                    AuroraRestorePlaceholder.mmsTransactionId(session, ownership.archiveMessageId)
                }
            }
        }
        return success(AuroraRestoreRollbackOutcome.REMOVED)
    }

    private companion object {
        const val TEST_MARKER = "aurora.restore"
    }
}

private fun sms(id: Long, box: AuroraBackupMessageBox): AuroraBackupSmsRecord = AuroraBackupSmsRecord(
    archiveMessageId = id,
    box = box,
    address = "+15550000000",
    body = "synthetic $id",
    timestampMillis = id,
    sentTimestampMillis = null,
    read = false,
    seen = false,
    locked = false,
    status = null,
    errorCode = null,
    protocol = null,
    replyPathPresent = null,
    subject = null,
    serviceCenter = null,
    subscriptionId = null,
)

private fun mms(id: Long, box: AuroraBackupMessageBox): AuroraBackupMmsRecord = AuroraBackupMmsRecord(
    archiveMessageId = id,
    box = box,
    timestampMillis = id,
    sentTimestampMillis = null,
    read = false,
    seen = false,
    locked = false,
    subscriptionId = null,
    messageType = 132,
    version = 18,
    priority = null,
    status = null,
    responseStatus = null,
    retrieveStatus = null,
    readReport = null,
    deliveryReport = null,
    reportAllowed = null,
    messageSizeBytes = 4,
    expiryMillis = null,
    deliveryTimeMillis = null,
    subject = null,
    subjectCharset = null,
    contentType = "application/vnd.wap.multipart.related",
    contentLocation = null,
    messageClass = null,
    transactionId = null,
    addresses = listOf(AuroraBackupMmsAddress(137, "+15550000000", 106)),
)

private fun binaryPart(parent: Long): AuroraBackupMmsPartRecord = AuroraBackupMmsPartRecord(
    parentArchiveMessageId = parent,
    sequence = 0,
    contentType = "application/octet-stream",
    charset = null,
    name = "part.bin",
    contentDisposition = "attachment",
    filename = "part.bin",
    contentId = null,
    contentLocation = null,
    payload = AuroraBackupMmsPartPayload.Binary { it.write(byteArrayOf(1, 2, 3, 4)) },
)

private fun <T> success(value: T): AuroraRestoreProviderResult.Success<T> =
    AuroraRestoreProviderResult.Success(value)
