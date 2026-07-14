// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.telephony.internal.RawMmsAddress
import org.aurorasms.core.telephony.internal.RawMmsPart
import org.aurorasms.core.telephony.internal.projectAddressMetadata
import org.aurorasms.core.telephony.internal.projectPartMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProjectionPolicyTest {
    @Test
    fun malformedRawRowsAdvanceCursorWithoutFalselyExhaustingHistory() {
        val request = ProviderPageRequest(limit = 2)
        val page = buildProviderPageFromRaw(
            request = request,
            rawRows = listOf(
                RawRow(timestampMillis = 30, id = 5, value = null),
                RawRow(timestampMillis = 20, id = 4, value = null),
                RawRow(timestampMillis = 10, id = 3, value = "accepted"),
            ),
            cursorFor = RawRow::cursor,
            project = RawRow::value,
        )

        assertEquals(listOf("accepted"), page.items)
        assertFalse(page.exhausted)
        assertEquals(ProviderPageCursor(timestampMillis = 10, providerRowId = 3), page.next)

        val older = buildProviderPageFromRaw(
            request = request.copy(before = page.next),
            rawRows = listOf(RawRow(timestampMillis = 5, id = 2, value = "older")),
            cursorFor = RawRow::cursor,
            project = RawRow::value,
        )
        assertEquals(listOf("older"), older.items)
        assertTrue(older.exhausted)
        assertNull(older.next)
    }

    @Test
    fun fullLogicalPageDoesNotConsumeRawLookahead() {
        val page = buildProviderPageFromRaw(
            request = ProviderPageRequest(limit = 2),
            rawRows = listOf(
                RawRow(timestampMillis = 30, id = 5, value = "first"),
                RawRow(timestampMillis = 20, id = 4, value = "second"),
                RawRow(timestampMillis = 10, id = 3, value = "lookahead"),
            ),
            cursorFor = RawRow::cursor,
            project = RawRow::value,
        )

        assertEquals(listOf("first", "second"), page.items)
        assertEquals(ProviderPageCursor(timestampMillis = 20, providerRowId = 4), page.next)
        assertFalse(page.exhausted)
    }

    @Test
    fun repeatedOrOutOfOrderRawCursorIsRejectedInsteadOfLooping() {
        val request = ProviderPageRequest(
            limit = 2,
            before = ProviderPageCursor(timestampMillis = 20, providerRowId = 4),
        )

        assertThrows(IllegalArgumentException::class.java) {
            buildProviderPageFromRaw(
                request = request,
                rawRows = listOf(RawRow(timestampMillis = 20, id = 4, value = "repeat")),
                cursorFor = RawRow::cursor,
                project = RawRow::value,
            )
        }
    }

    @Test
    fun mmsPartProjectionReadsOnlyBoundedTextAndAttachmentMetadata() {
        val projected = projectPartMetadata(
            rawRows = listOf(
                RawMmsPart("text/plain", "first", null, null, null),
                RawMmsPart("application/smil", "not searchable", null, null, null),
                RawMmsPart("text/plain; charset=utf-8", "second", null, null, null),
                RawMmsPart("image/jpeg", null, "aurora.jpg", null, null),
            ),
            totalBytes = 4_096,
        )

        assertEquals("first\nsecond", projected.body)
        assertEquals(1, projected.attachments.attachmentCount)
        assertEquals(4_096L, projected.attachments.totalBytes)
        assertEquals("image/jpeg", projected.attachments.contentTypes.single().mimeType)
        assertFalse(projected.attachments.metadataTruncated)
        assertFalse(projected.toString().contains("aurora.jpg"))
    }

    @Test
    fun mmsAttachmentMetadataOverflowIsExplicitAndBounded() {
        val projected = projectPartMetadata(
            rawRows = List(101) { index ->
                RawMmsPart("image/png", null, "image-$index.png", null, null)
            },
            totalBytes = null,
        )

        assertEquals(25, projected.attachments.attachmentCount)
        assertEquals(25, projected.attachments.contentTypes.size)
        assertTrue(projected.attachments.metadataTruncated)
    }

    @Test
    fun mmsAddressProjectionRetainsValidRowsButMarksMalformedIdentityIncomplete() {
        val projected = projectAddressMetadata(
            listOf(
                RawMmsAddress("+15550000001", MMS_FROM_ADDRESS_TYPE),
                RawMmsAddress("malformed\u0000address", MMS_TO_ADDRESS_TYPE),
                RawMmsAddress("insert-address-token", MMS_TO_ADDRESS_TYPE),
            ),
        )

        assertEquals("+15550000001", projected.sender?.value)
        assertEquals(listOf("+15550000001"), projected.participants.map { it.value })
        assertTrue(projected.truncated)
        assertFalse(projected.toString().contains("+15550000001"))
    }

    @Test
    fun mmsInsertAddressPlaceholderDoesNotPoisonACompleteActualSet() {
        val projected = projectAddressMetadata(
            listOf(
                RawMmsAddress("insert-address-token", MMS_FROM_ADDRESS_TYPE),
                RawMmsAddress("+15550000002", MMS_TO_ADDRESS_TYPE),
            ),
        )

        assertNull(projected.sender)
        assertEquals(listOf("+15550000002"), projected.participants.map { it.value })
        assertFalse(projected.truncated)
    }

    @Test
    fun mmsAddressProjectionMarksAnEmptyActualSetIncomplete() {
        val projected = projectAddressMetadata(
            listOf(RawMmsAddress("insert-address-token", MMS_FROM_ADDRESS_TYPE)),
        )

        assertTrue(projected.participants.isEmpty())
        assertTrue(projected.truncated)
    }

    private data class RawRow(
        val timestampMillis: Long,
        val id: Long,
        val value: String?,
    ) {
        fun cursor(): ProviderPageCursor = ProviderPageCursor(timestampMillis, id)
    }

    private companion object {
        const val MMS_FROM_ADDRESS_TYPE = 137
        const val MMS_TO_ADDRESS_TYPE = 151
    }
}
