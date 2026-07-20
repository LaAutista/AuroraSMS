// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
}
