// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingVoiceMemoTest {
    @Test
    fun acceptedMemoDefensivelyCopiesBytesAndRedactsDiagnostics() {
        val source = byteArrayOf(11, 22, 33)
        val result = OutgoingVoiceMemo.create(source, durationMillis = 1_500L)
        val memo = (result as OutgoingVoiceMemo.CreationResult.Valid).memo

        source[0] = 99
        val firstCopy = memo.copyBytes()
        firstCopy[1] = 88

        assertArrayEquals(byteArrayOf(11, 22, 33), memo.copyBytes())
        assertEquals(3, memo.size)
        assertTrue(memo.toString().contains("content=REDACTED"))
        assertFalse(memo.toString().contains("11"))
    }

    @Test
    fun memoBoundsRejectEmptyOversizeAndInvalidDuration() {
        assertEquals(
            OutgoingVoiceMemo.CreationResult.Reason.EMPTY,
            (OutgoingVoiceMemo.create(byteArrayOf(), 1L) as OutgoingVoiceMemo.CreationResult.Rejected).reason,
        )
        assertEquals(
            OutgoingVoiceMemo.CreationResult.Reason.TOO_LARGE,
            (
                OutgoingVoiceMemo.create(
                    ByteArray(OutgoingVoiceMemo.MAX_BYTES + 1),
                    1L,
                ) as OutgoingVoiceMemo.CreationResult.Rejected
                ).reason,
        )
        assertEquals(
            OutgoingVoiceMemo.CreationResult.Reason.INVALID_DURATION,
            (OutgoingVoiceMemo.create(byteArrayOf(1), 0L) as OutgoingVoiceMemo.CreationResult.Rejected).reason,
        )
        assertEquals(
            OutgoingVoiceMemo.CreationResult.Reason.INVALID_DURATION,
            (
                OutgoingVoiceMemo.create(
                    byteArrayOf(1),
                    OutgoingVoiceMemo.MAX_DURATION_MILLIS + 1L,
                ) as OutgoingVoiceMemo.CreationResult.Rejected
                ).reason,
        )
    }

    @Test
    fun equalityUsesContentAndDurationWithoutLeakingPayloadText() {
        val first = validMemo(byteArrayOf(1, 2, 3), 2_000L)
        val same = validMemo(byteArrayOf(1, 2, 3), 2_000L)
        val differentDuration = validMemo(byteArrayOf(1, 2, 3), 2_001L)
        val payload = OutgoingMmsPayload.VoiceMemo(
            text = "private synthetic body",
            subject = "private synthetic subject",
            memo = first,
        )

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, differentDuration)
        assertFalse(payload.toString().contains("private synthetic body"))
        assertFalse(payload.toString().contains("private synthetic subject"))
        assertTrue(payload.toString().contains("textLength=22"))
        assertTrue(payload.toString().contains("hasSubject=true"))
    }

    @Test
    fun legacyHighLevelPayloadAlsoRedactsText() {
        val payload = OutgoingMmsPayload.RequiresEncoding(
            text = "private legacy body",
            subject = "private legacy subject",
            attachmentCount = 0,
        )

        assertFalse(payload.toString().contains("private legacy body"))
        assertFalse(payload.toString().contains("private legacy subject"))
        assertTrue(payload.toString().contains("REDACTED"))
    }

    private fun validMemo(bytes: ByteArray, durationMillis: Long): OutgoingVoiceMemo =
        (OutgoingVoiceMemo.create(bytes, durationMillis) as OutgoingVoiceMemo.CreationResult.Valid).memo
}
