// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAuroraRestoreProviderTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Before
    fun setUp() {
        RestoreTestProvider.reset()
        journalDirectory().deleteRecursively()
    }

    @After
    fun tearDown() {
        RestoreTestProvider.reset()
        journalDirectory().deleteRecursively()
    }

    @Test
    fun accessGatesRunBeforeAnySyntheticProviderCall() {
        val roleDenied = provider(roleHeld = false, permissionGranted = true)
        assertEquals(AuroraRestoreProviderResult.RoleRequired, roleDenied.preflight())
        assertEquals(0, RestoreTestProvider.queryCount())
        assertEquals(0, RestoreTestProvider.mutationCount())

        val permissionDenied = provider(roleHeld = true, permissionGranted = false)
        assertEquals(AuroraRestoreProviderResult.PermissionDenied, permissionDenied.preflight())
        assertEquals(0, RestoreTestProvider.queryCount())
        assertEquals(0, RestoreTestProvider.mutationCount())
    }

    @Test
    fun coordinatorRestoresExactSmsMmsAndStreamedPartThenSkipsExactReplay() {
        val binary = ByteArray(196_608) { index -> (index % 251).toByte() }
        val sms = smsRecord()
        val unsafeSms = sms.copy(
            archiveMessageId = 3,
            box = AuroraBackupMessageBox.OUTBOX,
            body = "synthetic historical outbox",
        )
        val mms = mmsRecord()
        val plaintext = plaintext(
            sequenceOf(
                AuroraBackupMessageCodec.smsEntry(sms),
                AuroraBackupMessageCodec.mmsEntry(mms),
                AuroraBackupMessageCodec.mmsPartEntry(textPart()),
                AuroraBackupMessageCodec.mmsPartEntry(binaryPart(binary)),
                AuroraBackupMessageCodec.smsEntry(unsafeSms),
            ),
        )
        val archive = AuroraBackupArchive()
        val journal = journal()
        val coordinator = AuroraRestoreCoordinator(archive, journal, provider())

        val first = coordinator.restore { ByteArrayInputStream(plaintext) }
        assertTrue(
            "unexpected first restore result: $first; queries=${RestoreTestProvider.queryCount()}; " +
                "mutations=${RestoreTestProvider.mutationCount()}; sms=${RestoreTestProvider.smsCount()}; " +
                "mms=${RestoreTestProvider.mmsCount()}; parts=${RestoreTestProvider.partCount()}",
            first is AuroraRestoreResult.Success,
        )
        val firstSuccess = first as AuroraRestoreResult.Success
        assertEquals(3L, firstSuccess.summary.importedMessages)
        assertEquals(0L, firstSuccess.summary.skippedDuplicates)
        assertEquals(2, RestoreTestProvider.smsCount())
        assertEquals(1, RestoreTestProvider.mmsCount())
        assertEquals(2, RestoreTestProvider.partCount())
        assertEquals(AuroraBackupMessageBox.INBOX.code, RestoreTestProvider.smsValue(1, "type"))
        assertEquals(sms.body, RestoreTestProvider.smsValue(1, "body"))
        assertEquals(AuroraBackupMessageBox.SENT.code, RestoreTestProvider.mmsValue(2, "msg_box"))
        assertEquals(mms.transactionId, RestoreTestProvider.mmsValue(2, "tr_id"))
        assertEquals(AuroraBackupMessageBox.FAILED.code, RestoreTestProvider.smsValue(3, "type"))
        assertEquals(42L, RestoreTestProvider.smsValue(3, "thread_id"))
        assertTrue(binary.contentEquals(RestoreTestProvider.binaryPayloads().single()))
        assertEquals(AuroraRestoreJournalRecoveryResult.None, journal.recoverySnapshot())

        val mutationsBeforeReplay = RestoreTestProvider.mutationCount()
        val second = coordinator.restore { ByteArrayInputStream(plaintext) }
        assertTrue("unexpected replay restore result: $second", second is AuroraRestoreResult.Success)
        val secondSuccess = second as AuroraRestoreResult.Success
        assertEquals(0L, secondSuccess.summary.importedMessages)
        assertEquals(3L, secondSuccess.summary.skippedDuplicates)
        assertEquals(mutationsBeforeReplay, RestoreTestProvider.mutationCount())
        assertEquals(2, RestoreTestProvider.smsCount())
        assertEquals(1, RestoreTestProvider.mmsCount())
    }

    @Test
    fun changedPreparedSmsConflictsButBothCrashWindowsRollbackExactly() {
        val provider = provider()
        val record = smsRecord()
        val session = AuroraRestoreSession(TEST_SESSION)
        val placeholder = AuroraRestorePlaceholder.smsAddress(session, record.archiveMessageId)
        val providerId = (provider.insertSmsPlaceholder(record, placeholder)
            as AuroraRestoreProviderResult.Success).value
        val expected = AuroraRestoreCanonicalDigest.sms(record, includeHistoricalBox = false)
        val prepared = (provider.prepareSms(providerId, placeholder, record, expected)
            as AuroraRestoreProviderResult.Success).value
        val ownership = AuroraRestoreOwnership(
            archiveMessageId = record.archiveMessageId,
            providerKind = AuroraRestoreProviderKind.SMS,
            providerRowId = providerId,
            targetBox = record.box,
            preparedDigest = prepared.value,
        )
        RestoreTestProvider.mutateSms(providerId, "body", "foreign change")
        assertEquals(
            AuroraRestoreProviderResult.OwnershipConflict,
            provider.commitHistoricalBox(ownership, AuroraBackupMessageBox.INBOX),
        )
        assertEquals(
            AuroraRestoreProviderResult.Success(AuroraRestoreRollbackOutcome.OWNERSHIP_CONFLICT),
            provider.rollback(session, ownership),
        )
        assertEquals(1, RestoreTestProvider.smsCount())

        RestoreTestProvider.reset()
        provider.insertSmsPlaceholder(record, placeholder)
        assertEquals(
            AuroraRestoreProviderResult.Success(AuroraRestoreRollbackOutcome.REMOVED),
            provider.rollback(
                session,
                ownership.copy(providerRowId = null, preparedDigest = null),
            ),
        )
        assertEquals(0, RestoreTestProvider.smsCount())

        val expectedProviderId = (provider.insertSmsPlaceholder(record, placeholder)
            as AuroraRestoreProviderResult.Success).value
        val expectedDigest = (provider.prepareSms(expectedProviderId, placeholder, record, expected)
            as AuroraRestoreProviderResult.Success).value
        assertEquals(
            AuroraRestoreProviderResult.Success(AuroraRestoreRollbackOutcome.REMOVED),
            provider.rollback(
                session,
                ownership.copy(
                    providerRowId = expectedProviderId,
                    preparedDigest = expectedDigest.value,
                ),
            ),
        )
        assertEquals(0, RestoreTestProvider.smsCount())

        RestoreTestProvider.reset()
        val mms = mmsRecord()
        val mmsPlaceholder = AuroraRestorePlaceholder.mmsTransactionId(
            session,
            mms.archiveMessageId,
        )
        provider.insertMmsPlaceholder(mms, mmsPlaceholder)
        assertEquals(
            AuroraRestoreProviderResult.Success(AuroraRestoreRollbackOutcome.REMOVED),
            provider.rollback(
                session,
                AuroraRestoreOwnership(
                    archiveMessageId = mms.archiveMessageId,
                    providerKind = AuroraRestoreProviderKind.MMS,
                    providerRowId = null,
                    targetBox = mms.box,
                ),
            ),
        )
        assertEquals(0, RestoreTestProvider.mmsCount())
    }

    @Test
    fun providerNormalizationIsDetectedAndConditionallyCleanedWithoutStrandingMms() {
        val record = mmsRecord().copy(archiveMessageId = 1)
        val plaintext = plaintext(
            sequenceOf(
                AuroraBackupMessageCodec.mmsEntry(record),
                AuroraBackupMessageCodec.mmsPartEntry(
                    textPart().copy(parentArchiveMessageId = 1),
                ),
            ),
        )
        RestoreTestProvider.normalizeNextMmsSubject()
        val journal = journal()
        val coordinator = AuroraRestoreCoordinator(AuroraBackupArchive(), journal, provider())

        assertEquals(
            AuroraRestoreResult.Failed(
                reason = AuroraRestoreFailure.OWNERSHIP_CONFLICT,
                rollbackComplete = true,
            ),
            coordinator.restore { ByteArrayInputStream(plaintext) },
        )
        assertEquals(0, RestoreTestProvider.mmsCount())
        assertEquals(0, RestoreTestProvider.partCount())
        assertEquals(AuroraRestoreJournalRecoveryResult.None, journal.recoverySnapshot())
    }

    @Test
    fun laterCommitConflictRollsBackAlreadyCommittedMmsAndPreparedSms() {
        val mms = mmsRecord().copy(archiveMessageId = 1)
        val sms = smsRecord().copy(archiveMessageId = 2, box = AuroraBackupMessageBox.SENT)
        val plaintext = plaintext(
            sequenceOf(
                AuroraBackupMessageCodec.mmsEntry(mms),
                AuroraBackupMessageCodec.mmsPartEntry(
                    textPart().copy(parentArchiveMessageId = 1),
                ),
                AuroraBackupMessageCodec.smsEntry(sms),
            ),
        )
        RestoreTestProvider.rejectNextSmsBoxCommit()
        val journal = journal()
        val coordinator = AuroraRestoreCoordinator(AuroraBackupArchive(), journal, provider())

        assertEquals(
            AuroraRestoreResult.Failed(
                reason = AuroraRestoreFailure.OWNERSHIP_CONFLICT,
                rollbackComplete = true,
            ),
            coordinator.restore { ByteArrayInputStream(plaintext) },
        )
        assertEquals(0, RestoreTestProvider.smsCount())
        assertEquals(0, RestoreTestProvider.mmsCount())
        assertEquals(0, RestoreTestProvider.partCount())
        assertEquals(AuroraRestoreJournalRecoveryResult.None, journal.recoverySnapshot())
    }

    private fun provider(
        roleHeld: Boolean = true,
        permissionGranted: Boolean = true,
    ): AndroidAuroraRestoreProvider {
        val base = Uri.parse("content://${RestoreTestProvider.AUTHORITY}")
        return AndroidAuroraRestoreProvider(
            resolver = context.contentResolver,
            uris = AndroidAuroraRestoreProvider.RestoreProviderUris(
                sms = base.buildUpon().appendPath("sms").build(),
                mms = base.buildUpon().appendPath("mms").build(),
                mmsPartBase = base.buildUpon().appendPath("part").build(),
            ),
            packageName = context.packageName,
            roleHeld = { roleHeld },
            readPermissionGranted = { permissionGranted },
            threadIdForAddresses = { addresses -> if (addresses.isEmpty()) 0L else 42L },
        )
    }

    private fun journal(): AuroraRestoreJournal = AuroraRestoreJournal(
        directory = journalDirectory().apply { check(mkdirs()) },
        nowMillis = { 42L },
        newSession = { TEST_SESSION },
    )

    private fun journalDirectory(): File = File(context.noBackupFilesDir, "restore-provider-test")

    private fun plaintext(entries: Sequence<AuroraBackupEntry>): ByteArray {
        val archive = AuroraBackupArchive()
        val passphrase = "synthetic restore provider secret".toCharArray()
        val encrypted = ByteArrayOutputStream()
        assertTrue(archive.writeEncrypted(entries, passphrase, encrypted) is AuroraBackupWriteResult.Success)
        val plaintext = ByteArrayOutputStream()
        assertTrue(
            archive.decryptToPending(
                ByteArrayInputStream(encrypted.toByteArray()),
                passphrase,
                plaintext,
            ) is AuroraBackupDecryptResult.Success,
        )
        return plaintext.toByteArray()
    }

    private fun smsRecord() = AuroraBackupSmsRecord(
        archiveMessageId = 1,
        box = AuroraBackupMessageBox.INBOX,
        address = "+15550000000",
        body = "synthetic SMS restore",
        timestampMillis = 1_700_000_000_000L,
        sentTimestampMillis = 1_699_999_999_000L,
        read = true,
        seen = true,
        locked = false,
        status = -1,
        errorCode = 0,
        protocol = 0,
        replyPathPresent = 0,
        subject = null,
        serviceCenter = "+15559999999",
        subscriptionId = 1,
    )

    private fun mmsRecord() = AuroraBackupMmsRecord(
        archiveMessageId = 2,
        box = AuroraBackupMessageBox.SENT,
        timestampMillis = 1_700_000_000_000L,
        sentTimestampMillis = 1_699_999_999_000L,
        read = true,
        seen = true,
        locked = false,
        subscriptionId = 1,
        messageType = 128,
        version = 18,
        priority = 129,
        status = 128,
        responseStatus = 128,
        retrieveStatus = 128,
        readReport = 128,
        deliveryReport = 128,
        reportAllowed = 128,
        messageSizeBytes = 196_700L,
        expiryMillis = 86_400_000L,
        deliveryTimeMillis = 0L,
        subject = "synthetic MMS restore",
        subjectCharset = 106,
        contentType = "application/vnd.wap.multipart.related",
        contentLocation = "synthetic-location",
        messageClass = "personal",
        transactionId = "synthetic-transaction",
        addresses = listOf(
            AuroraBackupMmsAddress(137, "+15550000000", 106),
            AuroraBackupMmsAddress(151, "insert-address-token", 106),
        ),
    )

    private fun textPart() = AuroraBackupMmsPartRecord(
        parentArchiveMessageId = 2,
        sequence = 0,
        contentType = "text/plain",
        charset = 106,
        name = null,
        contentDisposition = null,
        filename = null,
        contentId = "<text>",
        contentLocation = null,
        payload = AuroraBackupMmsPartPayload.Text("synthetic MMS body"),
    )

    private fun binaryPart(bytes: ByteArray) = AuroraBackupMmsPartRecord(
        parentArchiveMessageId = 2,
        sequence = 1,
        contentType = "image/jpeg",
        charset = null,
        name = "aurora.jpg",
        contentDisposition = "attachment",
        filename = "aurora.jpg",
        contentId = "<image>",
        contentLocation = "aurora.jpg",
        payload = AuroraBackupMmsPartPayload.Binary { output -> output.write(bytes) },
    )

    private companion object {
        const val TEST_SESSION = "00000000-0000-4000-8000-000000000004"
    }
}
