// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuroraBackupCryptoCompatibilityTest {
    @Test
    fun platformCryptoStreamsAndAuthenticatesVersionOneArchive() {
        val archive = AuroraBackupArchive()
        val passphrase = "api compatibility secret".toCharArray()
        val encrypted = ByteArrayOutputStream()
        val written = archive.writeEncrypted(
            sequenceOf(
                AuroraBackupEntry(AuroraBackupEntryType.SMS) { output ->
                    DataOutputStream(output).use {
                        it.writeByte(1)
                        it.writeUTF("synthetic message")
                    }
                },
            ),
            passphrase,
            encrypted,
        )
        assertTrue(written is AuroraBackupWriteResult.Success)

        val pending = ByteArrayOutputStream()
        assertTrue(
            archive.decryptToPending(
                ByteArrayInputStream(encrypted.toByteArray()),
                passphrase,
                pending,
            ) is AuroraBackupDecryptResult.Success,
        )
        val validated = archive.validatePlaintext(ByteArrayInputStream(pending.toByteArray()))
        assertEquals(1L, (validated as AuroraBackupValidationResult.Success).summary.smsCount)
    }

    @Test
    fun syntheticTelephonySourceStreamsSmsMmsTextAndBinaryParts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = Uri.parse("content://${BackupTestProvider.AUTHORITY}")
        val source = AndroidTelephonyBackupSource(
            resolver = context.contentResolver,
            uris = AndroidTelephonyBackupSource.ProviderUris(
                sms = base.buildUpon().appendPath("sms").build(),
                mms = base.buildUpon().appendPath("mms").build(),
                mmsPartBase = base.buildUpon().appendPath("part").build(),
            ),
            roleHeld = { true },
            readPermissionGranted = { true },
        )
        val ready = source.open() as AuroraBackupSourceOpenResult.Ready
        val archive = AuroraBackupArchive()
        val passphrase = "synthetic provider secret".toCharArray()
        val encrypted = ByteArrayOutputStream()
        val written = archive.writeEncrypted(ready.entries, passphrase, encrypted)
        assertEquals(1L, (written as AuroraBackupWriteResult.Success).summary.smsCount)
        assertEquals(1L, written.summary.mmsCount)
        assertEquals(2L, written.summary.mmsPartCount)

        val plaintext = ByteArrayOutputStream()
        assertTrue(
            archive.decryptToPending(
                ByteArrayInputStream(encrypted.toByteArray()),
                passphrase,
                plaintext,
            ) is AuroraBackupDecryptResult.Success,
        )
        assertEquals(
            AuroraBackupValidationResult.Success(written.summary),
            archive.validateMessagePlaintext(ByteArrayInputStream(plaintext.toByteArray())),
        )
        var visitedSms = 0
        var visitedMms = 0
        var visitedParts = 0
        var binaryBytes = 0L
        assertEquals(
            AuroraBackupValidationResult.Success(written.summary),
            archive.visitMessagePlaintext(
                ByteArrayInputStream(plaintext.toByteArray()),
                object : AuroraBackupMessageVisitor {
                    override fun onSms(record: AuroraBackupSmsRecord) {
                        visitedSms += 1
                    }

                    override fun onMms(record: AuroraBackupMmsRecord) {
                        visitedMms += 1
                    }

                    override fun onMmsPart(
                        record: AuroraBackupDecodedMmsPart,
                        payload: AuroraBackupDecodedPartPayload,
                    ) {
                        visitedParts += 1
                        if (payload is AuroraBackupDecodedPartPayload.Binary) {
                            binaryBytes += payload.discard()
                        }
                    }
                },
            ),
        )
        assertEquals(1, visitedSms)
        assertEquals(1, visitedMms)
        assertEquals(2, visitedParts)
        assertEquals(196_608L, binaryBytes)
    }

    @Test
    fun sourceAccessChecksFailBeforeAnyProviderQuery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidTelephonyBackupSource(
            resolver = context.contentResolver,
            uris = AndroidTelephonyBackupSource.ProviderUris.platform(),
            roleHeld = { false },
            readPermissionGranted = { true },
        )
        assertEquals(AuroraBackupSourceOpenResult.RoleRequired, source.open())
    }

    @Test
    fun restoreJournalPersistsCrashOrderingInNoBackupStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "restore-journal-instrumentation").apply {
            deleteRecursively()
            check(mkdirs())
        }
        try {
            val sessionValue = "00000000-0000-4000-8000-000000000002"
            val journal = AuroraRestoreJournal(
                directory = directory,
                nowMillis = { 42L },
                newSession = { sessionValue },
            )
            val session = (journal.begin() as AuroraRestoreJournalBeginResult.Success).session
            assertTrue(journal.reserve(session, 1, AuroraRestoreProviderKind.SMS))
            assertTrue(journal.recordInserted(session, 1, AuroraRestoreProviderKind.SMS, 77))

            val recovered = AuroraRestoreJournal(directory)
            assertEquals(
                AuroraRestoreJournalRecoveryResult.Active(session, 42L, 1L, false),
                recovered.recoverySnapshot(),
            )
            assertTrue(recovered.clear(session))
        } finally {
            directory.deleteRecursively()
        }
    }
}
