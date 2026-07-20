// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuroraRestoreJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun journalRecoversInsertedAndPreInsertOwnershipWithoutContent() {
        val directory = temporaryFolder.newFolder("journal")
        val journal = newJournal(directory)
        val session = (journal.begin() as AuroraRestoreJournalBeginResult.Success).session
        assertTrue(journal.reserve(session, 1, AuroraRestoreProviderKind.SMS, AuroraBackupMessageBox.INBOX))
        assertTrue(journal.recordInserted(session, 1, AuroraRestoreProviderKind.SMS, 101))
        assertTrue(journal.reserve(session, 2, AuroraRestoreProviderKind.MMS, AuroraBackupMessageBox.SENT))

        val recoveredJournal = newJournal(directory)
        assertEquals(
            AuroraRestoreJournalRecoveryResult.Active(
                session = session,
                createdTimestampMillis = 1234L,
                messageCount = 2L,
                hasPreInsertReservation = true,
            ),
            recoveredJournal.recoverySnapshot(),
        )
        val ownership = mutableListOf<AuroraRestoreOwnership>()
        assertTrue(recoveredJournal.forEachOwnership(session, ownership::add))
        assertEquals(
            listOf(
                AuroraRestoreOwnership(1, AuroraRestoreProviderKind.SMS, 101, AuroraBackupMessageBox.INBOX),
                AuroraRestoreOwnership(2, AuroraRestoreProviderKind.MMS, null, AuroraBackupMessageBox.SENT),
            ),
            ownership,
        )

        val storedText = File(directory, "aurora_restore_journal_v1.log").readText()
        assertFalse(storedText.contains("message body"))
        assertFalse(storedText.contains("+1555"))
        assertFalse(storedText.contains("subject"))
    }

    @Test
    fun completionRequiresPairedInsertAndExactSessionBeforeClear() {
        val directory = temporaryFolder.newFolder("complete")
        val journal = newJournal(directory)
        val session = (journal.begin() as AuroraRestoreJournalBeginResult.Success).session
        assertTrue(journal.reserve(session, 1, AuroraRestoreProviderKind.MMS, AuroraBackupMessageBox.OUTBOX))
        assertFalse(journal.markComplete(session))
        assertFalse(journal.recordInserted(session, 1, AuroraRestoreProviderKind.SMS, 9))
        assertTrue(journal.recordInserted(session, 1, AuroraRestoreProviderKind.MMS, 9))
        assertTrue(journal.markComplete(session))

        val recovered = newJournal(directory)
        assertEquals(
            AuroraRestoreJournalRecoveryResult.Complete(session, 1234L, 1L),
            recovered.recoverySnapshot(),
        )
        val foreign = AuroraRestoreSession("11111111-1111-4111-8111-111111111111")
        assertFalse(recovered.clear(foreign))
        assertTrue(recovered.clear(session))
        assertEquals(AuroraRestoreJournalRecoveryResult.None, recovered.recoverySnapshot())
    }

    @Test
    fun corruptionFailsClosedAndCannotBeClearedAsTrustedRecovery() {
        val directory = temporaryFolder.newFolder("corrupt")
        val journal = newJournal(directory)
        val session = (journal.begin() as AuroraRestoreJournalBeginResult.Success).session
        assertTrue(journal.reserve(session, 1, AuroraRestoreProviderKind.SMS, AuroraBackupMessageBox.INBOX))
        val file = File(directory, "aurora_restore_journal_v1.log")
        RandomAccessFile(file, "rw").use { random ->
            random.seek(file.length() - 3L)
            val original = random.read()
            random.seek(file.length() - 3L)
            random.write(if (original == '0'.code) '1'.code else '0'.code)
            random.fd.sync()
        }

        val recovered = newJournal(directory)
        assertEquals(AuroraRestoreJournalRecoveryResult.Corrupt, recovered.recoverySnapshot())
        assertFalse(recovered.clear(session))
        assertEquals(AuroraRestoreJournalBeginResult.ExistingRecoveryRequired, recovered.begin())
    }

    @Test
    fun placeholdersAreOpaqueBoundedAndDeterministic() {
        val session = AuroraRestoreSession(TEST_SESSION)
        val sms = AuroraRestorePlaceholder.smsAddress(session, 2_000_000)
        val mms = AuroraRestorePlaceholder.mmsTransactionId(session, 2_000_000)
        assertEquals(sms, AuroraRestorePlaceholder.smsAddress(session, 2_000_000))
        assertEquals(mms, AuroraRestorePlaceholder.mmsTransactionId(session, 2_000_000))
        assertTrue(sms.length < AuroraBackupMessageCodec.MAX_ADDRESS_BYTES)
        assertTrue(mms.length <= 64)
        assertTrue(mms.matches(Regex("[A-Za-z0-9._-]+")))
    }

    private fun newJournal(directory: File): AuroraRestoreJournal = AuroraRestoreJournal(
        directory = directory,
        nowMillis = { 1234L },
        newSession = { TEST_SESSION },
    )

    private companion object {
        const val TEST_SESSION = "00000000-0000-4000-8000-000000000001"
    }
}
