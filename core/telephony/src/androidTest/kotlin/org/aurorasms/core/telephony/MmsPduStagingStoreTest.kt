// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.internal.MmsPduDirection
import org.aurorasms.core.telephony.internal.MmsPduStagingStore
import org.aurorasms.core.telephony.internal.MmsStagingResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduStagingStoreTest {
    @Test
    fun sendAndDownloadOperationsUseUniqueDirectionalUris() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MmsPduStagingStore(context)
        val pdu = (EncodedMmsPdu.create(byteArrayOf(1, 2, 3)) as EncodedMmsPdu.CreationResult.Valid).pdu
        val send = store.stageSend(operation(1), pdu).ready()
        val secondSend = store.stageSend(operation(1), pdu).ready()
        val download = store.createDownloadTarget(operation(2)).ready()

        assertNotEquals(send.uri, secondSend.uri)
        assertNotEquals(send.uri, download.uri)
        assertEqualsDirection(MmsPduDirection.SEND_SOURCE, send.direction)
        assertEqualsDirection(MmsPduDirection.DOWNLOAD_TARGET, download.direction)
        assertTrue(store.cleanup(send.uri, send.direction))
        assertTrue(store.cleanup(secondSend.uri, secondSend.direction))
        assertTrue(store.cleanup(download.uri, download.direction))
    }

    @Test
    fun stagedSendRetainsDefensivePduCopyAndCleanupIsIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MmsPduStagingStore(context)
        val source = byteArrayOf(4, 5, 6)
        val pdu = (EncodedMmsPdu.create(source) as EncodedMmsPdu.CreationResult.Valid).pdu
        source.fill(0)
        val staged = store.stageSend(operation(3), pdu).ready()

        val bytes = context.contentResolver.openInputStream(staged.uri)!!.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes)
        assertTrue(store.cleanup(staged.uri, staged.direction))
        assertTrue(store.cleanup(staged.uri, staged.direction))
    }

    @Test
    fun completedDownloadIsReadOnlyThroughTheBoundedCopyBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MmsPduStagingStore(context)
        val staged = store.createDownloadTarget(operation(4)).ready()
        context.contentResolver.openOutputStream(staged.uri, "w")!!.use {
            it.write(byteArrayOf(9, 10, 11))
        }

        val result = store.readCompletedDownload(staged) as EncodedMmsPdu.CreationResult.Valid

        assertArrayEquals(byteArrayOf(9, 10, 11), result.pdu.copyBytes())
        assertTrue(store.cleanup(staged.uri, staged.direction))
    }

    @Test
    fun journalRecoveryReconstructsOnlyTheExactConfinedExistingDownloadFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MmsPduStagingStore(context)
        val operation = operation(5)
        val staged = store.createDownloadTarget(operation).ready()

        val recovered = requireNotNull(store.recoverDownloadTarget(operation, staged.fileName))

        assertEquals(staged, recovered)
        assertNull(store.recoverDownloadTarget(operation, "../${staged.fileName}"))
        assertNull(store.recoverDownloadTarget(operation, "11111111-1111-4111-8111-111111111111.pdu"))
        assertTrue(store.cleanup(staged.uri, staged.direction))
        assertNull(store.recoverDownloadTarget(operation, staged.fileName))
    }

    private fun operation(value: Long) = MessageId(ProviderKind.PENDING_OPERATION, value)

    private fun MmsStagingResult.ready() = (this as MmsStagingResult.Ready).staged

    private fun assertEqualsDirection(expected: MmsPduDirection, actual: MmsPduDirection) {
        assertTrue("Expected $expected but was $actual", expected == actual)
    }
}
