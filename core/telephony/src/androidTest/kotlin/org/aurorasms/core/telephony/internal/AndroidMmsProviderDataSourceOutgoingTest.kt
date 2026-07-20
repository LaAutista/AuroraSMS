// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.OutgoingVoiceMemo
import org.aurorasms.core.telephony.OutgoingVoiceMemoProviderRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.RecipientSet
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class AndroidMmsProviderDataSourceOutgoingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: InMemoryMmsProvider
    private lateinit var dataSource: AndroidMmsProviderDataSource

    @Before
    fun setUp() {
        provider = InMemoryMmsProvider().also { it.attachInfo(context, null) }
        dataSource = AndroidMmsProviderDataSource(
            context = context,
            roleState = HeldRole,
            ioDispatcher = Dispatchers.Unconfined,
            resolver = ContentResolver.wrap(provider),
        )
    }

    @After
    fun tearDown() {
        provider.close()
    }

    @Test
    fun partsArePersistedFirstThenAttachedToOneOwnedFailedRow() = runBlocking {
        val record = record(operation = 101L, transaction = "provider-test-101")

        val stored = dataSource.insertOutgoingVoiceMemo(record).successValue()

        assertEquals(ProviderMessageId(ProviderKind.MMS, 41L), stored.providerId)
        assertEquals(ConversationId(73L), stored.conversationId)
        assertEquals(0, provider.dummyPartCount(record.operationId.value))
        assertEquals(3, provider.partCount(41L))
        assertEquals(1, provider.addressCount(41L))
        assertArrayEquals(MEMO_BYTES, provider.audioBytes(41L))
        assertEquals(context.packageName, provider.messageValue(41L, Telephony.Mms.CREATOR))
        assertEquals(73L, provider.messageValue(41L, Telephony.Mms.THREAD_ID))
        assertEquals("provider-test-101", provider.messageValue(41L, Telephony.Mms.TRANSACTION_ID))
        assertEquals(2, provider.messageValue(41L, Telephony.Mms.SUBSCRIPTION_ID))
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, provider.messageBox(41L))

        assertEquals(
            OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            dataSource.updateOutgoingStatus(
                stored.providerId,
                ConversationId(74L),
                OutgoingMmsProviderStatus.OUTBOX,
            ).successValue(),
        )
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, provider.messageBox(41L))
        assertEquals(
            OutgoingMmsStatusUpdateOutcome.APPLIED,
            dataSource.updateOutgoingStatus(
                stored.providerId,
                stored.conversationId,
                OutgoingMmsProviderStatus.OUTBOX,
            ).successValue(),
        )
        assertEquals(Telephony.Mms.MESSAGE_BOX_OUTBOX, provider.messageBox(41L))
        assertEquals(
            OutgoingMmsStatusUpdateOutcome.APPLIED,
            dataSource.updateOutgoingStatus(
                stored.providerId,
                stored.conversationId,
                OutgoingMmsProviderStatus.SENT,
            ).successValue(),
        )
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, provider.messageBox(41L))
    }

    @Test
    fun incompleteAddressWriteDeletesTheRowAndEveryTemporaryPart() = runBlocking {
        provider.failAddressInsert = true
        val record = record(operation = 103L, transaction = "provider-test-103")

        val result = dataSource.insertOutgoingVoiceMemo(record)

        assertTrue(result is ProviderAccessResult.Unavailable)
        assertEquals(0, provider.messageCount())
        assertEquals(0, provider.totalPartCount())
        assertEquals(0, provider.totalAddressCount())
    }

    @Test
    fun rollbackDeletesOnlyTheExactFailedPreparationAndRefusesOutbox() = runBlocking {
        val first = record(operation = 105L, transaction = "provider-test-105")
        val firstStored = dataSource.insertOutgoingVoiceMemo(first).successValue()

        assertEquals(
            OutgoingMmsStatusUpdateOutcome.APPLIED,
            dataSource.rollbackOutgoingPreparation(
                first.operationId,
                firstStored.conversationId,
                first.transactionId,
            ).successValue(),
        )
        assertEquals(0, provider.messageCount())
        assertEquals(0, provider.totalPartCount())
        assertEquals(0, provider.totalAddressCount())

        val second = record(operation = 107L, transaction = "provider-test-107")
        val secondStored = dataSource.insertOutgoingVoiceMemo(second).successValue()
        assertEquals(
            OutgoingMmsStatusUpdateOutcome.APPLIED,
            dataSource.updateOutgoingStatus(
                secondStored.providerId,
                secondStored.conversationId,
                OutgoingMmsProviderStatus.OUTBOX,
            ).successValue(),
        )

        assertEquals(
            OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            dataSource.rollbackOutgoingPreparation(
                second.operationId,
                secondStored.conversationId,
                second.transactionId,
            ).successValue(),
        )
        assertEquals(1, provider.messageCount())
        assertEquals(Telephony.Mms.MESSAGE_BOX_OUTBOX, provider.messageBox(secondStored.providerId.value))
    }

    private fun record(operation: Long, transaction: String): OutgoingVoiceMemoProviderRecord =
        OutgoingVoiceMemoProviderRecord(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, operation),
            providerThreadId = ProviderThreadId(73L),
            recipients = (
                RecipientSet.parse(listOf("+15551230000")) as RecipientSet.CreationResult.Valid
                ).recipients,
            text = "Synthetic signature",
            subject = "Synthetic voice memo",
            memo = (
                OutgoingVoiceMemo.create(MEMO_BYTES, 4_321L) as
                    OutgoingVoiceMemo.CreationResult.Valid
                ).memo,
            encodedSize = 539,
            transactionId = transaction,
            timestampMillis = 1_720_000_000_000L,
            subscriptionId = AuroraSubscriptionId(2),
        )

    private fun <T> ProviderAccessResult<T>.successValue(): T =
        (this as ProviderAccessResult.Success<T>).value

    private object HeldRole : DefaultSmsRoleState {
        override fun isRoleAvailable(): Boolean = true
        override fun isRoleHeld(): Boolean = true
    }

    private companion object {
        val MEMO_BYTES = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte())
    }
}

internal class InMemoryMmsProvider : ContentProvider() {
    private data class Part(
        val id: Long,
        var ownerId: Long,
        val values: ContentValues,
        val file: File,
    )

    private data class Address(
        val id: Long,
        val ownerId: Long,
        val values: ContentValues,
    )

    private val messages = linkedMapOf<Long, ContentValues>()
    private val parts = linkedMapOf<Long, Part>()
    private val addresses = linkedMapOf<Long, Address>()
    private var nextMessageId = 41L
    private var nextPartId = 61L
    private var nextAddressId = 81L
    var failAddressInsert: Boolean = false

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val copy = ContentValues(values ?: ContentValues())
        val path = uri.pathSegments
        return when {
            path.isEmpty() -> {
                val id = nextMessageId++
                messages[id] = copy
                Uri.parse("content://mms/$id")
            }
            path.size == 2 && path[1] == "part" -> {
                val ownerId = path[0].toLongOrNull() ?: return null
                val id = nextPartId++
                val file = File.createTempFile("aurora-mms-part-$id-", ".bin", context!!.cacheDir)
                parts[id] = Part(id, ownerId, copy, file)
                Uri.parse("content://mms/part/$id")
            }
            path.size == 2 && path[1] == "addr" -> {
                if (failAddressInsert) return null
                val ownerId = path[0].toLongOrNull() ?: return null
                val id = nextAddressId++
                addresses[id] = Address(id, ownerId, copy)
                Uri.parse("content://mms/$ownerId/addr/$id")
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.map(String::toString)?.toTypedArray() ?: emptyArray()
        val cursor = MatrixCursor(columns)
        val path = uri.pathSegments
        when {
            path.isEmpty() -> messages.forEach { (id, values) ->
                if (matchesPreparation(values, selectionArgs)) cursor.addValues(columns, id, values)
            }
            path.size == 1 -> {
                val id = path[0].toLongOrNull()
                id?.let { messages[it] }?.let { cursor.addValues(columns, id, it) }
            }
            path.size == 2 && path[1] == "part" -> {
                val ownerId = path[0].toLongOrNull()
                parts.values.filter { it.ownerId == ownerId }.forEach { part ->
                    cursor.addValues(columns, part.id, part.values)
                }
            }
            path.size == 2 && path[1] == "addr" -> {
                val ownerId = path[0].toLongOrNull()
                addresses.values
                    .filter { address ->
                        address.ownerId == ownerId &&
                            (selectionArgs.isNullOrEmpty() ||
                                address.values.getAsString("type") == selectionArgs[0])
                    }
                    .forEach { address -> cursor.addValues(columns, address.id, address.values) }
            }
        }
        return cursor
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val path = uri.pathSegments
        if (path.size == 2 && path[1] == "part") {
            val ownerId = path[0].toLongOrNull() ?: return 0
            val newOwnerId = values?.getAsLong("mid") ?: return 0
            val selected = parts.values.filter { it.ownerId == ownerId }
            selected.forEach { it.ownerId = newOwnerId }
            return selected.size
        }
        if (path.size != 1) return 0
        val id = path[0].toLongOrNull() ?: return 0
        val row = messages[id] ?: return 0
        val args = selectionArgs ?: return 0
        if (args.size < 3) return 0
        if (row.getAsString(Telephony.Mms.CREATOR) != args[0]) return 0
        if (row.getAsString(Telephony.Mms.THREAD_ID) != args[1]) return 0
        val allowedBoxes = args.drop(2).toSet()
        if (row.getAsString(Telephony.Mms.MESSAGE_BOX) !in allowedBoxes) return 0
        row.putAll(values ?: ContentValues())
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val path = uri.pathSegments
        if (path.size == 2 && path[1] == "part") {
            val ownerId = path[0].toLongOrNull() ?: return 0
            val selected = parts.values.filter { it.ownerId == ownerId }
            selected.forEach(::deletePart)
            return selected.size
        }
        if (path.size != 1) return 0
        val id = path[0].toLongOrNull() ?: return 0
        val row = messages[id] ?: return 0
        if (selectionArgs != null && !matchesExactFailedPreparation(row, selectionArgs)) return 0
        messages.remove(id)
        parts.values.filter { it.ownerId == id }.forEach(::deletePart)
        addresses.entries.removeAll { it.value.ownerId == id }
        return 1
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val path = uri.pathSegments
        if (path.size != 2 || path[0] != "part") throw FileNotFoundException(uri.toString())
        val id = path[1].toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val file = parts[id]?.file ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE,
        )
    }

    fun messageCount(): Int = messages.size
    fun totalPartCount(): Int = parts.size
    fun totalAddressCount(): Int = addresses.size
    fun dummyPartCount(operationId: Long): Int =
        parts.values.count { it.ownerId == dummyMmsPartOwnerId(operationId) }
    fun partCount(messageId: Long): Int = parts.values.count { it.ownerId == messageId }
    fun addressCount(messageId: Long): Int = addresses.values.count { it.ownerId == messageId }
    fun messageBox(messageId: Long): Int = messages.getValue(messageId).getAsInteger(Telephony.Mms.MESSAGE_BOX)
    fun messageValue(messageId: Long, key: String): Any? = messages.getValue(messageId).get(key)
    fun audioBytes(messageId: Long): ByteArray =
        parts.values.single {
            it.ownerId == messageId && it.values.getAsString(Telephony.Mms.Part.CONTENT_TYPE) == "audio/mp4"
        }.file.readBytes()

    fun close() {
        parts.values.map(Part::file).forEach(File::delete)
        parts.clear()
        addresses.clear()
        messages.clear()
    }

    private fun matchesPreparation(values: ContentValues, args: Array<out String>?): Boolean =
        args == null ||
            (
                args.size >= 3 &&
                    values.getAsString(Telephony.Mms.CREATOR) == args[0] &&
                    values.getAsString(Telephony.Mms.THREAD_ID) == args[1] &&
                    values.getAsString(Telephony.Mms.TRANSACTION_ID) == args[2]
                )

    private fun matchesExactFailedPreparation(values: ContentValues, args: Array<out String>): Boolean =
        args.size >= 4 &&
            values.getAsString(Telephony.Mms.CREATOR) == args[0] &&
            values.getAsString(Telephony.Mms.THREAD_ID) == args[1] &&
            values.getAsString(Telephony.Mms.TRANSACTION_ID) == args[2] &&
            values.getAsString(Telephony.Mms.MESSAGE_BOX) == args[3]

    private fun deletePart(part: Part) {
        parts.remove(part.id)
        part.file.delete()
    }

    private fun MatrixCursor.addValues(columns: Array<String>, id: Long, values: ContentValues) {
        addRow(columns.map { column -> if (column == BaseColumns._ID) id else values.get(column) })
    }

}
