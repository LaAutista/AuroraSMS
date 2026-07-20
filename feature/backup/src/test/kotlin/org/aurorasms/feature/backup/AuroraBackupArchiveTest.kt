// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraBackupArchiveTest {
    private val archive = AuroraBackupArchive(DeterministicSecureRandom())
    private val passphrase = "correct horse aurora".toCharArray()

    @Test
    fun encryptedRoundTripAuthenticatesAndValidatesStreamingRecords() {
        val encrypted = ByteArrayOutputStream()
        val written = archive.writeEncrypted(
            entries = sequenceOf(
                entry(AuroraBackupEntryType.SMS, 17),
                entry(AuroraBackupEntryType.MMS, 33),
                entry(AuroraBackupEntryType.MMS_PART, 131_073),
            ),
            passphrase = passphrase,
            destination = encrypted,
        )
        val expected = AuroraBackupSummary(
            entryCount = 3,
            smsCount = 1,
            mmsCount = 1,
            mmsPartCount = 1,
            plaintextContentBytes = 131_123,
        )
        assertEquals(AuroraBackupWriteResult.Success(expected), written)
        assertTrue(encrypted.size() > 0)

        val pending = ByteArrayOutputStream()
        val decrypted = archive.decryptToPending(
            ByteArrayInputStream(encrypted.toByteArray()),
            passphrase,
            pending,
        )
        assertEquals(AuroraBackupDecryptResult.Success(pending.size().toLong()), decrypted)
        assertEquals(
            AuroraBackupValidationResult.Success(expected),
            archive.validatePlaintext(ByteArrayInputStream(pending.toByteArray())),
        )
    }

    @Test
    fun wrongPassphraseAndCiphertextTamperingShareOneFailure() {
        val encrypted = validEncryptedArchive()
        val wrongPending = ByteArrayOutputStream()
        assertEquals(
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION),
            archive.decryptToPending(
                ByteArrayInputStream(encrypted),
                "incorrect password".toCharArray(),
                wrongPending,
            ),
        )

        val tampered = encrypted.copyOf().also { it[it.lastIndex - 5] = (it[it.lastIndex - 5].toInt() xor 1).toByte() }
        assertEquals(
            AuroraBackupDecryptResult.Failed(AuroraBackupFailure.AUTHENTICATION_OR_CORRUPTION),
            archive.decryptToPending(ByteArrayInputStream(tampered), passphrase, ByteArrayOutputStream()),
        )
    }

    @Test
    fun invalidPassphraseDoesNotTouchDestination() {
        val destination = ByteArrayOutputStream().apply { write(byteArrayOf(9, 8, 7)) }
        assertEquals(
            AuroraBackupWriteResult.Failed(AuroraBackupFailure.PASSPHRASE_POLICY),
            archive.writeEncrypted(emptySequence(), "too short".toCharArray(), destination),
        )
        assertArrayEquals(byteArrayOf(9, 8, 7), destination.toByteArray())
    }

    @Test
    fun sourceMustStartEveryRecordWithKnownSchema() {
        val result = archive.writeEncrypted(
            sequenceOf(AuroraBackupEntry(AuroraBackupEntryType.SMS) { it.write(2) }),
            passphrase,
            ByteArrayOutputStream(),
        )
        assertEquals(AuroraBackupWriteResult.Failed(AuroraBackupFailure.SOURCE_FAILURE), result)
    }

    @Test
    fun plaintextValidationRejectsTruncationAndPathMutation() {
        val pending = decrypt(validEncryptedArchive())
        assertEquals(
            AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE),
            archive.validatePlaintext(ByteArrayInputStream(pending.copyOf(pending.size - 1))),
        )
        val expectedPath = AuroraBackupArchive.pathFor(1, AuroraBackupEntryType.SMS)
            .toByteArray(Charsets.UTF_8)
        val pathOffset = pending.indexOfSlice(expectedPath)
        assertTrue(pathOffset >= 0)
        val mutated = pending.copyOf().also { it[pathOffset] = '.'.code.toByte() }
        assertEquals(
            AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE),
            archive.validatePlaintext(ByteArrayInputStream(mutated)),
        )
    }

    @Test
    fun passphrasePolicyIsExplicitAndBounded() {
        assertTrue(AuroraBackupArchive.passphraseMeetsPolicy(passphrase))
        assertEquals(false, AuroraBackupArchive.passphraseMeetsPolicy("            ".toCharArray()))
        assertEquals(false, AuroraBackupArchive.passphraseMeetsPolicy("short chars".toCharArray()))
        assertEquals(false, AuroraBackupArchive.passphraseMeetsPolicy("valid length\n".toCharArray()))
    }

    private fun validEncryptedArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        assertTrue(
            archive.writeEncrypted(
                sequenceOf(entry(AuroraBackupEntryType.SMS, 17)),
                passphrase,
                output,
            ) is AuroraBackupWriteResult.Success,
        )
        return output.toByteArray()
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        assertTrue(
            archive.decryptToPending(ByteArrayInputStream(encrypted), passphrase, output) is
                AuroraBackupDecryptResult.Success,
        )
        return output.toByteArray()
    }

    private fun entry(type: AuroraBackupEntryType, size: Int): AuroraBackupEntry =
        AuroraBackupEntry(type) { output ->
            DataOutputStream(output).use { content ->
                content.writeByte(1)
                content.write(ByteArray(size - 1) { (it % 251).toByte() })
            }
        }

    private fun ByteArray.indexOfSlice(target: ByteArray): Int {
        for (offset in 0..size - target.size) {
            if (target.indices.all { this[offset + it] == target[it] }) return offset
        }
        return -1
    }
}

private class DeterministicSecureRandom : SecureRandom() {
    private var next = 1

    override fun nextBytes(bytes: ByteArray) {
        bytes.indices.forEach { index -> bytes[index] = (next++ and 0xff).toByte() }
    }
}
