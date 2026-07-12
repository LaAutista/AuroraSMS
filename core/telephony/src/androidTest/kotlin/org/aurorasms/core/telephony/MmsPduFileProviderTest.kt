// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.internal.MmsPduFileProvider
import org.aurorasms.core.telephony.internal.MmsPduStagingStore
import org.aurorasms.core.telephony.internal.MmsStagingResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MmsPduFileProviderTest {
    @Test
    fun providerIsNonExportedAndEnforcesOppositeDirections() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val providerInfo = context.packageManager.getProviderInfo(
            ComponentName(context, MmsPduFileProvider::class.java),
            0,
        )
        assertFalse(providerInfo.exported)
        val store = MmsPduStagingStore(context)
        val pdu = (EncodedMmsPdu.create(byteArrayOf(7, 8)) as EncodedMmsPdu.CreationResult.Valid).pdu
        val send = (store.stageSend(operation(10), pdu) as MmsStagingResult.Ready).staged
        val download = (store.createDownloadTarget(operation(11)) as MmsStagingResult.Ready).staged

        context.contentResolver.openFileDescriptor(send.uri, "r")!!.close()
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(send.uri, "w")
        }
        context.contentResolver.openFileDescriptor(download.uri, "w")!!.close()
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(download.uri, "r")
        }
        val traversal = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.mms-pdu")
            .encodedPath("/mms_send/../00000000-0000-0000-0000-000000000000.pdu")
            .build()
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(traversal, "r")
        }

        store.cleanup(send.uri, send.direction)
        store.cleanup(download.uri, download.direction)
    }

    private fun operation(value: Long) = MessageId(ProviderKind.PENDING_OPERATION, value)
}
