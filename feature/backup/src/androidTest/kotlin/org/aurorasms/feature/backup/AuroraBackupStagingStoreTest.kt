// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuroraBackupStagingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val passphrase = "correct horse aurora".toCharArray()

    @Test
    fun encryptedSelectionPromotesOnlyAfterAuthenticationAndFullValidation() {
        withStore("success") { directory, store ->
            val encrypted = validMessageArchive(passphrase)
            val staged = store.stageEncrypted(ByteArrayInputStream(encrypted))
                as AuroraBackupStageResult.Success
            assertEquals(encrypted.size.toLong(), staged.encryptedBytes)
            assertEquals(listOf("encrypted.staged"), suffixes(directory))

            val authenticated = store.authenticate(staged.session, passphrase)
                as AuroraBackupAuthenticateResult.Success
            assertEquals(1L, authenticated.summary.smsCount)
            assertEquals(0L, authenticated.summary.mmsCount)
            assertEquals(listOf("plaintext.validated"), suffixes(directory))

            authenticated.archive.open().use { input ->
                assertEquals(
                    AuroraBackupValidationResult.Success(authenticated.summary),
                    AuroraBackupArchive().validateMessagePlaintext(input),
                )
            }
            val validated = directory.listFiles()!!.single()
            val stat = Os.lstat(validated.absolutePath)
            assertTrue(OsConstants.S_ISREG(stat.st_mode))
            assertEquals(1L, stat.st_nlink)
            assertEquals(OWNER_READ_WRITE_MODE, stat.st_mode and PERMISSION_MASK)
            assertTrue(validated.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))

            assertTrue(store.cleanup(staged.session))
            assertTrue(directory.listFiles()!!.isEmpty())
            assertThrows(IOException::class.java) { authenticated.archive.open() }
        }
    }

    @Test
    fun wrongPassphraseAndTamperingUseOneFailureAndLeaveNoPlaintext() {
        withStore("authentication") { directory, store ->
            val encrypted = validMessageArchive(passphrase)
            val first = store.stageEncrypted(ByteArrayInputStream(encrypted))
                as AuroraBackupStageResult.Success
            assertEquals(
                AuroraBackupAuthenticateResult.Failed(
                    AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION,
                ),
                store.authenticate(first.session, "incorrect secret value".toCharArray()),
            )
            assertEquals(listOf("encrypted.staged"), suffixes(directory))
            assertTrue(
                store.authenticate(first.session, passphrase) is
                    AuroraBackupAuthenticateResult.Success,
            )
            assertTrue(store.cleanup(first.session))

            val tampered = encrypted.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
            val second = store.stageEncrypted(ByteArrayInputStream(tampered))
                as AuroraBackupStageResult.Success
            assertEquals(
                AuroraBackupAuthenticateResult.Failed(
                    AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION,
                ),
                store.authenticate(second.session, passphrase),
            )
            assertEquals(listOf("encrypted.staged"), suffixes(directory))
            assertTrue(store.cleanup(second.session))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun authenticatedButInvalidMessageSchemaNeverBecomesValidated() {
        withStore("invalid-schema") { directory, store ->
            val archive = AuroraBackupArchive()
            val encrypted = ByteArrayOutputStream()
            assertTrue(
                archive.writeEncrypted(
                    sequenceOf(
                        AuroraBackupEntry(AuroraBackupEntryType.SMS) { output ->
                            output.write(AuroraBackupMessageCodec.SCHEMA_VERSION)
                            output.write(byteArrayOf(4, 3, 2, 1))
                        },
                    ),
                    passphrase,
                    encrypted,
                ) is AuroraBackupWriteResult.Success,
            )
            val staged = store.stageEncrypted(ByteArrayInputStream(encrypted.toByteArray()))
                as AuroraBackupStageResult.Success
            assertEquals(
                AuroraBackupAuthenticateResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE),
                store.authenticate(staged.session, passphrase),
            )
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun sourceFailureAndConfiguredLimitDeleteIncompleteCopy() {
        withStore("source-failure") { directory, store ->
            val failing = object : InputStream() {
                override fun read(): Int = throw IOException("synthetic source failure")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("synthetic source failure")
            }
            assertEquals(
                AuroraBackupStageResult.Failed(AuroraBackupStageFailure.SOURCE_FAILURE),
                store.stageEncrypted(failing),
            )
            assertTrue(directory.listFiles()!!.isEmpty())
        }

        withStore("size-limit", maximumEncryptedBytes = 32L) { directory, store ->
            assertEquals(
                AuroraBackupStageResult.Failed(AuroraBackupStageFailure.LIMIT_EXCEEDED),
                store.stageEncrypted(ByteArrayInputStream(ByteArray(33))),
            )
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun startupReconciliationUnlinksOwnedSymlinkWithoutFollowingIt() {
        withStore("startup-symlink") { directory, store ->
            assertTrue(store.reconcileStartup())
            val target = File(directory.parentFile, "staging-symlink-target").apply {
                writeText("do not delete")
            }
            try {
                val managedLink = File(
                    directory,
                    "aurora_restore_$SESSION.encrypted.staged",
                )
                Files.createSymbolicLink(managedLink.toPath(), target.toPath())
                assertTrue(store.reconcileStartup())
                assertFalse(Files.exists(managedLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                assertTrue(target.exists())
                assertEquals("do not delete", target.readText())
            } finally {
                target.delete()
            }
        }
    }

    @Test
    fun validatedCapabilityRejectsPathReplacementAndCleanupDoesNotFollowIt() {
        withStore("validated-replacement") { directory, store ->
            val staged = store.stageEncrypted(ByteArrayInputStream(validMessageArchive(passphrase)))
                as AuroraBackupStageResult.Success
            val authenticated = store.authenticate(staged.session, passphrase)
                as AuroraBackupAuthenticateResult.Success
            val validated = directory.listFiles()!!.single()
            assertTrue(validated.delete())
            val target = File(directory.parentFile, "validated-replacement-target").apply {
                writeText("foreign")
            }
            try {
                Files.createSymbolicLink(validated.toPath(), target.toPath())
                assertThrows(IOException::class.java) { authenticated.archive.open() }
                assertTrue(store.cleanup(staged.session))
                assertTrue(target.exists())
                assertEquals("foreign", target.readText())
            } finally {
                target.delete()
            }
        }
    }

    private fun validMessageArchive(secret: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        val written = AuroraBackupArchive().writeEncrypted(
            sequenceOf(
                AuroraBackupMessageCodec.smsEntry(
                    AuroraBackupSmsRecord(
                        archiveMessageId = 1L,
                        box = AuroraBackupMessageBox.INBOX,
                        address = "+15550000000",
                        body = "synthetic staging fixture",
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
                ),
            ),
            secret,
            output,
        )
        assertTrue(written is AuroraBackupWriteResult.Success)
        return output.toByteArray()
    }

    private fun suffixes(directory: File): List<String> = directory.listFiles()!!
        .map { it.name.substringAfter("$SESSION.") }
        .sorted()

    private fun withStore(
        name: String,
        maximumEncryptedBytes: Long = AuroraBackupArchive.MAX_ENCRYPTED_ARCHIVE_BYTES,
        block: (File, AuroraBackupStagingStore) -> Unit,
    ) {
        val directory = File(context.noBackupFilesDir, "restore-staging-test-$name")
        directory.deleteRecursively()
        val store = AuroraBackupStagingStore(
            directory = directory,
            newSession = { SESSION },
            maximumEncryptedBytes = maximumEncryptedBytes,
        )
        try {
            block(directory, store)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION = "00000000-0000-4000-8000-000000000003"
        const val OWNER_READ_WRITE_MODE = 384
        const val PERMISSION_MASK = 511
    }
}
