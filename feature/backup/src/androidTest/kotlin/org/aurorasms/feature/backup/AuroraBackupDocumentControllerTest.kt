// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuroraBackupDocumentControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val passphrase = "correct horse aurora".toCharArray()
    private val sourceUri = Uri.parse("content://synthetic.documents/source")
    private val destinationUri = Uri.parse("content://synthetic.documents/destination")

    @Test
    fun restoreCannotReachProviderBeforeAuthenticatedSummaryConfirmation() {
        withFixture { fixture ->
            fixture.documents.sourceBytes = fixture.validArchive()
            assertEquals(
                AuroraRestoreConfirmationResult.NoValidatedArchive,
                fixture.controller.confirmRestore(session()),
            )
            val selected = fixture.controller.selectRestoreSource(sourceUri)
                as AuroraRestoreSelectionResult.Success
            assertEquals(0, fixture.restoreCalls)
            assertEquals(
                AuroraRestoreConfirmationResult.NoValidatedArchive,
                fixture.controller.confirmRestore(selected.session),
            )

            val reviewed = fixture.controller.authenticateRestore(selected.session, passphrase)
                as AuroraRestoreReviewResult.Success
            assertEquals(1L, reviewed.summary.smsCount)
            assertEquals(0L, reviewed.summary.mmsCount)
            assertEquals(0, fixture.restoreCalls)

            val confirmed = fixture.controller.confirmRestore(selected.session)
                as AuroraRestoreConfirmationResult.Success
            assertEquals(1L, confirmed.summary.importedMessages)
            assertEquals(1, fixture.restoreCalls)
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
            assertEquals(
                AuroraRestoreConfirmationResult.NoValidatedArchive,
                fixture.controller.confirmRestore(selected.session),
            )
        }
    }

    @Test
    fun wrongPassphraseCanRetryButCannotConfirm() {
        withFixture { fixture ->
            fixture.documents.sourceBytes = fixture.validArchive()
            val selected = fixture.controller.selectRestoreSource(sourceUri)
                as AuroraRestoreSelectionResult.Success
            assertEquals(
                AuroraRestoreReviewResult.Failed(
                    AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION,
                ),
                fixture.controller.authenticateRestore(
                    selected.session,
                    "incorrect secret value".toCharArray(),
                ),
            )
            assertEquals(0, fixture.restoreCalls)
            assertEquals(
                AuroraRestoreConfirmationResult.NoValidatedArchive,
                fixture.controller.confirmRestore(selected.session),
            )
            assertTrue(
                fixture.controller.authenticateRestore(selected.session, passphrase) is
                    AuroraRestoreReviewResult.Success,
            )
            assertTrue(
                fixture.controller.confirmRestore(selected.session) is
                    AuroraRestoreConfirmationResult.Success,
            )
        }
    }

    @Test
    fun cancelDeletesStagedOrValidatedFileAndInvalidatesSession() {
        withFixture { fixture ->
            fixture.documents.sourceBytes = fixture.validArchive()
            val first = fixture.controller.selectRestoreSource(sourceUri)
                as AuroraRestoreSelectionResult.Success
            assertTrue(fixture.controller.cancelRestore())
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
            assertEquals(
                AuroraRestoreReviewResult.NoActiveSelection,
                fixture.controller.authenticateRestore(first.session, passphrase),
            )

            val second = fixture.controller.selectRestoreSource(sourceUri)
                as AuroraRestoreSelectionResult.Success
            assertTrue(
                fixture.controller.authenticateRestore(second.session, passphrase) is
                    AuroraRestoreReviewResult.Success,
            )
            assertTrue(fixture.controller.cancelRestore())
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
            assertEquals(
                AuroraRestoreConfirmationResult.NoValidatedArchive,
                fixture.controller.confirmRestore(second.session),
            )
        }
    }

    @Test
    fun exportStreamsEncryptedArchiveAndDeletesEveryFailedDestination() {
        withFixture { fixture ->
            val success = fixture.controller.export(destinationUri, passphrase)
                as AuroraBackupExportResult.Success
            assertEquals(1L, success.summary.smsCount)
            val encrypted = fixture.documents.destinationBytes
            assertTrue(encrypted.isNotEmpty())
            val plaintext = ByteArrayOutputStream()
            assertTrue(
                fixture.archive.decryptToPending(
                    ByteArrayInputStream(encrypted),
                    passphrase,
                    plaintext,
                ) is AuroraBackupDecryptResult.Success,
            )
            assertEquals(
                AuroraBackupValidationResult.Success(success.summary),
                fixture.archive.validateMessagePlaintext(
                    ByteArrayInputStream(plaintext.toByteArray()),
                ),
            )
            assertFalse(fixture.documents.deleted)

            fixture.sourceResult = AuroraBackupSourceOpenResult.RoleRequired
            fixture.documents.resetDestination()
            assertEquals(
                AuroraBackupExportResult.Failed(
                    AuroraBackupExportFailure.ROLE_REQUIRED,
                    incompleteDestinationRemoved = true,
                ),
                fixture.controller.export(destinationUri, passphrase),
            )
            assertTrue(fixture.documents.deleted)

            fixture.sourceResult = AuroraBackupSourceOpenResult.Ready(fixture.entries())
            fixture.documents.resetDestination()
            fixture.documents.failDestinationWrite = true
            fixture.documents.deleteResult = false
            assertEquals(
                AuroraBackupExportResult.Failed(
                    AuroraBackupExportFailure.DOCUMENT_FAILURE,
                    incompleteDestinationRemoved = false,
                ),
                fixture.controller.export(destinationUri, passphrase),
            )
            assertTrue(fixture.documents.deleted)
        }
    }

    @Test
    fun invalidUrisNeverOpenOrDeleteDocuments() {
        withFixture { fixture ->
            val invalid = Uri.parse("file:///tmp/not-accepted.aurorabk")
            assertEquals(
                AuroraRestoreSelectionResult.Failed(
                    AuroraRestoreSelectionFailure.INVALID_SOURCE,
                ),
                fixture.controller.selectRestoreSource(invalid),
            )
            assertEquals(
                AuroraBackupExportResult.Failed(
                    AuroraBackupExportFailure.INVALID_DESTINATION,
                    incompleteDestinationRemoved = false,
                ),
                fixture.controller.export(invalid, passphrase),
            )
            assertEquals(0, fixture.documents.sourceOpenCount)
            assertEquals(0, fixture.documents.destinationOpenCount)
            assertFalse(fixture.documents.deleted)
        }
    }

    @Test
    fun startupRecoversProviderBeforeDeletingCrashResidue() {
        withFixture { fixture ->
            assertTrue(fixture.staging.reconcileStartup())
            val residue = File(
                fixture.directory,
                "aurora_restore_$SESSION.plaintext.pending",
            ).apply { writeText("synthetic private residue") }
            fixture.recoverAction = {
                assertTrue(residue.exists())
                AuroraRestoreResult.Failed(
                    AuroraRestoreFailure.ROLLBACK_INCOMPLETE,
                    rollbackComplete = false,
                )
            }

            val recovered = fixture.controller.recoverStartup()
            assertEquals(
                AuroraRestoreResult.Failed(
                    AuroraRestoreFailure.ROLLBACK_INCOMPLETE,
                    rollbackComplete = false,
                ),
                recovered.restoreFailure,
            )
            assertTrue(recovered.stagingCleanupSucceeded)
            assertFalse(residue.exists())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = File(context.noBackupFilesDir, "backup-document-controller-test")
        directory.deleteRecursively()
        val fixture = Fixture(directory)
        try {
            block(fixture)
        } finally {
            fixture.controller.cancelRestore()
            directory.deleteRecursively()
        }
    }

    private inner class Fixture(val directory: File) {
        val archive = AuroraBackupArchive()
        val staging = AuroraBackupStagingStore(
            directory = directory,
            archive = archive,
            newSession = { SESSION },
        )
        val documents = FakeDocuments()
        var sourceResult: AuroraBackupSourceOpenResult = AuroraBackupSourceOpenResult.Ready(entries())
        var restoreCalls = 0
        var recoverAction: () -> AuroraRestoreResult? = { null }
        val controller = AuroraBackupDocumentController(
            documents = documents,
            openBackupSource = { sourceResult },
            archive = archive,
            staging = staging,
            restore = { openValidated ->
                restoreCalls += 1
                val validation = openValidated().use(archive::validateMessagePlaintext)
                    as AuroraBackupValidationResult.Success
                AuroraRestoreResult.Success(
                    AuroraRestoreSummary(
                        archive = validation.summary,
                        importedMessages = validation.summary.smsCount + validation.summary.mmsCount,
                        skippedDuplicates = 0L,
                    ),
                )
            },
            recoverRestore = { recoverAction() },
        )

        fun entries(): Sequence<AuroraBackupEntry> = sequenceOf(smsEntry())

        fun validArchive(): ByteArray {
            val output = ByteArrayOutputStream()
            assertTrue(
                archive.writeEncrypted(entries(), passphrase, output) is
                    AuroraBackupWriteResult.Success,
            )
            return output.toByteArray()
        }
    }

    private class FakeDocuments : AuroraBackupDocumentAccess {
        var sourceBytes: ByteArray = ByteArray(0)
        var destinationBytes: ByteArray = ByteArray(0)
            private set
        var sourceOpenCount = 0
        var destinationOpenCount = 0
        var failDestinationWrite = false
        var deleted = false
        var deleteResult = true

        override fun accepts(uri: Uri): Boolean = uri.scheme == "content"

        override fun openSource(uri: Uri): InputStream {
            sourceOpenCount += 1
            return ByteArrayInputStream(sourceBytes)
        }

        override fun openDestination(uri: Uri): OutputStream {
            destinationOpenCount += 1
            val collected = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (failDestinationWrite) throw IOException("synthetic destination failure")
                    collected.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (failDestinationWrite) throw IOException("synthetic destination failure")
                    collected.write(bytes, offset, length)
                }

                override fun close() {
                    destinationBytes = collected.toByteArray()
                }
            }
        }

        override fun delete(uri: Uri): Boolean {
            deleted = true
            destinationBytes = ByteArray(0)
            return deleteResult
        }

        fun resetDestination() {
            destinationBytes = ByteArray(0)
            deleted = false
            failDestinationWrite = false
            deleteResult = true
        }
    }

    private fun smsEntry(): AuroraBackupEntry = AuroraBackupMessageCodec.smsEntry(
        AuroraBackupSmsRecord(
            archiveMessageId = 1L,
            box = AuroraBackupMessageBox.INBOX,
            address = "+15550000000",
            body = "synthetic controller fixture",
            timestampMillis = 1_700_000_000_000L,
            sentTimestampMillis = null,
            read = true,
            seen = true,
            locked = false,
            status = null,
            errorCode = null,
            protocol = null,
            replyPathPresent = null,
            subject = null,
            serviceCenter = null,
            subscriptionId = 1,
        ),
    )

    private fun session(): AuroraBackupStagingSession = AuroraBackupStagingSession(SESSION)

    private companion object {
        const val SESSION = "00000000-0000-4000-8000-000000000004"
    }
}
