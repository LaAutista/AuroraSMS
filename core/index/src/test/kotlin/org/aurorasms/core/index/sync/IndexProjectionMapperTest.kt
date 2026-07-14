// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexProjectionMapperTest {
    @Test
    fun smsProjectionMarksMissingSenderIdentityIncomplete() {
        val missing = IndexProjectionMapper.projectionFromSms(
            message = sms(sender = null),
            generationId = 1L,
        )
        val present = IndexProjectionMapper.projectionFromSms(
            message = sms(sender = ParticipantAddress("+15550000001")),
            generationId = 1L,
        )

        assertTrue(missing.participantAddresses.isEmpty())
        assertTrue(missing.participantsTruncated)
        assertEquals(listOf("+15550000001"), present.participantAddresses)
        assertFalse(present.participantsTruncated)
        assertTrue(IndexedProviderProjection.fromMessageOnly(present.message).participantsTruncated)
    }

    @Test
    fun mmsProjectionMarksEmptyOrExplicitlyIncompleteIdentityIncomplete() {
        val empty = IndexProjectionMapper.projectionFromMms(
            message = mms(participants = emptyList(), participantsTruncated = false),
            generationId = 1L,
        )
        val mixed = IndexProjectionMapper.projectionFromMms(
            message = mms(
                participants = listOf(ParticipantAddress("+15550000002")),
                participantsTruncated = true,
            ),
            generationId = 1L,
        )

        assertTrue(empty.participantAddresses.isEmpty())
        assertTrue(empty.participantsTruncated)
        assertEquals(listOf("+15550000002"), mixed.participantAddresses)
        assertTrue(mixed.participantsTruncated)
    }
}

private fun sms(sender: ParticipantAddress?): SmsProviderMessage = SmsProviderMessage(
    id = ProviderMessageId(ProviderKind.SMS, 1L),
    providerThreadId = ProviderThreadId(1L),
    sender = sender,
    body = "test",
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    rawStatus = null,
    rawErrorCode = null,
    timestampMillis = 1L,
    sentTimestampMillis = null,
    subscriptionId = null,
    read = true,
    seen = true,
    locked = false,
    syncFingerprint = fingerprint(1),
)

private fun mms(
    participants: List<ParticipantAddress>,
    participantsTruncated: Boolean,
): MmsProviderMessage = MmsProviderMessage(
    id = ProviderMessageId(ProviderKind.MMS, 2L),
    providerThreadId = ProviderThreadId(1L),
    sender = null,
    participants = participants,
    participantsTruncated = participantsTruncated,
    body = null,
    subject = null,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    rawStatus = null,
    rawResponseStatus = null,
    rawRetrieveStatus = null,
    timestampMillis = 1L,
    sentTimestampMillis = null,
    subscriptionId = null,
    attachments = MmsAttachmentSummary.EMPTY,
    read = true,
    seen = true,
    locked = false,
    syncFingerprint = fingerprint(2),
)

private fun fingerprint(seed: Int): MessageSyncFingerprint =
    MessageSyncFingerprint.fromSha256(
        ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { index -> (seed + index).toByte() },
    )
