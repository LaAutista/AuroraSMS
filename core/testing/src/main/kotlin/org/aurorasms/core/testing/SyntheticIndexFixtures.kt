// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.MmsAttachmentType
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderMessage

/**
 * Repeatable, synthetic-only provider projections for index tests and benchmarks.
 *
 * [FIXED_SEED] is an ASCII-derived `AURORA2` marker, not random or user data.
 * Changing it changes the benchmark corpus and therefore requires new baseline
 * evidence. A fixture stores only its shape and seed. Rows are constructed by
 * [SyntheticIndexFixture.recordAt], [SyntheticIndexFixture.window], or the lazy
 * [SyntheticIndexFixture.records] sequence; asking for the million-row shape
 * does not allocate a million objects.
 *
 * No reference application, device, contact, message, media, or carrier value
 * is an input. MMS fixtures contain bounded metadata only and never media bytes.
 */
object SyntheticIndexFixtures {
    const val FIXED_SEED: Long = 0x4155524f524132L
    const val FIXED_NEWEST_TIMESTAMP_MILLIS: Long = 1_735_689_600_000L
    const val MAX_WINDOW_SIZE: Int = 500
    const val LONG_BODY_CHARACTERS: Int = 4_096
    const val COMMON_SEARCH_TOKEN: String = "synthetic"

    const val GSM_BODY: String = "Synthetic GSM check-in at fixture station 42."
    const val UNICODE_BODY: String = "Synthetic Zażółć gęślą jaźń fixture."
    const val EMOJI_BODY: String = "Synthetic launch fixture 🚀🌌✨."
    const val COMBINING_BODY: String = "Synthetic Cafe\u0301 and A\u030a fixture."
    const val RTL_BODY: String = "رسالة اختبارية خيالية — synthetic fixture."

    val declaredShapes: List<SyntheticIndexShape> = SyntheticIndexShape.entries

    fun fixture(
        shape: SyntheticIndexShape,
        seed: Long = FIXED_SEED,
    ): SyntheticIndexFixture = SyntheticIndexFixture(shape = shape, seed = seed)
}

/** Shapes required by the Phase 2 scale and distribution gates. */
enum class SyntheticIndexShape(
    val messageCount: Int,
    val expectedThreadCount: Int,
) {
    EMPTY(messageCount = 0, expectedThreadCount = 0),
    SINGLE_MESSAGE(messageCount = 1, expectedThreadCount = 1),
    MESSAGES_10_THOUSAND(messageCount = 10_000, expectedThreadCount = 500),
    MESSAGES_100_THOUSAND(messageCount = 100_000, expectedThreadCount = 5_000),
    MESSAGES_500_THOUSAND(messageCount = 500_000, expectedThreadCount = 25_000),
    MESSAGES_1_MILLION(messageCount = 1_000_000, expectedThreadCount = 50_000),
    SINGLE_THREAD_250_THOUSAND(messageCount = 250_000, expectedThreadCount = 1),
    SHALLOW_20_THOUSAND_THREADS(messageCount = 20_000, expectedThreadCount = 20_000),
}

/**
 * A deterministic virtual corpus. It intentionally has no backing collection.
 *
 * `deletedFromProvider` records are tombstone scenarios: consumers may first
 * index their provider projection and then omit them from a verification pass.
 */
class SyntheticIndexFixture internal constructor(
    val shape: SyntheticIndexShape,
    val seed: Long,
) {
    val size: Int
        get() = shape.messageCount

    fun records(): Sequence<SyntheticIndexRecord> =
        (0 until size).asSequence().map(::recordAt)

    fun window(
        startIndex: Int,
        limit: Int = SyntheticIndexFixtures.MAX_WINDOW_SIZE,
    ): List<SyntheticIndexRecord> {
        require(startIndex in 0..size) { "Synthetic fixture start is out of bounds" }
        require(limit in 1..SyntheticIndexFixtures.MAX_WINDOW_SIZE) {
            "Synthetic fixture window must be in 1..${SyntheticIndexFixtures.MAX_WINDOW_SIZE}"
        }
        val endIndex = minOf(size.toLong(), startIndex.toLong() + limit).toInt()
        return (startIndex until endIndex).map(::recordAt)
    }

    fun recordAt(index: Int): SyntheticIndexRecord {
        require(index in 0 until size) { "Synthetic fixture index is out of bounds" }
        val providerKind = if (index % 2 == 0) ProviderKind.SMS else ProviderKind.MMS
        val providerId = ProviderMessageId(
            kind = providerKind,
            value = ((size + 1L) / 2L) - index.toLong() / 2L,
        )
        val providerThreadId = ProviderThreadId(threadIdAt(index))
        val timestampMillis = SyntheticIndexFixtures.FIXED_NEWEST_TIMESTAMP_MILLIS -
            (index.toLong() / SAME_MILLISECOND_GROUP_SIZE) * TIMESTAMP_STEP_MILLIS
        val direction = if ((index / 4) % 2 == 0) {
            MessageDirection.INCOMING
        } else {
            MessageDirection.OUTGOING
        }
        val sender = senderAt(index)
        val body = bodyAt(index)
        val deletedFromProvider = index % DELETED_MARKER_INTERVAL == DELETED_MARKER_OFFSET
        val resolvedContactName = if (index % CONTACT_INTERVAL == 0 && sender != null) {
            "Synthetic Fixture Contact ${index % CONTACT_VARIANTS + 1}"
        } else {
            null
        }
        val subscriptionId = AuroraSubscriptionId((index / 2) % 2)
        val box = boxFor(index = index, direction = direction)
        val status = statusFor(box)
        val read = direction == MessageDirection.OUTGOING || index % 3 == 0
        val seen = direction == MessageDirection.OUTGOING || index % 5 == 0
        val locked = index % LOCKED_INTERVAL == 0

        return when (providerKind) {
            ProviderKind.SMS -> {
                val rawStatus = rawStatusFor(status)
                val rawErrorCode = if (status == MessageStatus.FAILED) 42 else null
                val sentTimestampMillis = sentTimestampFor(timestampMillis)
                SyntheticIndexRecord.Sms(
                    providerMessage = SmsProviderMessage(
                        id = providerId,
                        providerThreadId = providerThreadId,
                        sender = sender,
                        body = body,
                        direction = direction,
                        box = box,
                        status = status,
                        rawStatus = rawStatus,
                        rawErrorCode = rawErrorCode,
                        timestampMillis = timestampMillis,
                        sentTimestampMillis = sentTimestampMillis,
                        subscriptionId = subscriptionId,
                        read = read,
                        seen = seen,
                        locked = locked,
                        syncFingerprint = fingerprintFor(
                            index,
                            providerId.kind.name,
                            providerId.value,
                            providerThreadId.value,
                            sender?.value,
                            body,
                            direction.name,
                            box.toStorageCode(),
                            status.toStorageCode(),
                            rawStatus,
                            rawErrorCode,
                            timestampMillis,
                            sentTimestampMillis,
                            subscriptionId.value,
                            read,
                            seen,
                            locked,
                        ),
                    ),
                    resolvedContactName = resolvedContactName,
                    deletedFromProvider = deletedFromProvider,
                )
            }

            ProviderKind.MMS -> {
                val participants = participantsFor(sender)
                val participantsTruncated = index % TRUNCATED_PARTICIPANT_INTERVAL == 0
                val mmsBody = if (index % NULL_MMS_BODY_INTERVAL == NULL_MMS_BODY_OFFSET) null else body
                val subject = subjectAt(index)
                val rawStatus = rawStatusFor(status)
                val rawResponseStatus = if (status == MessageStatus.FAILED) 224 else null
                val sentTimestampMillis = sentTimestampFor(timestampMillis)
                val attachments = attachmentsAt(index)
                SyntheticIndexRecord.Mms(
                    providerMessage = MmsProviderMessage(
                        id = providerId,
                        providerThreadId = providerThreadId,
                        sender = sender,
                        participants = participants,
                        participantsTruncated = participantsTruncated,
                        body = mmsBody,
                        subject = subject,
                        direction = direction,
                        box = box,
                        status = status,
                        rawStatus = rawStatus,
                        rawResponseStatus = rawResponseStatus,
                        rawRetrieveStatus = null,
                        timestampMillis = timestampMillis,
                        sentTimestampMillis = sentTimestampMillis,
                        subscriptionId = subscriptionId,
                        attachments = attachments,
                        read = read,
                        seen = seen,
                        locked = locked,
                        syncFingerprint = fingerprintFor(
                            index,
                            providerId.kind.name,
                            providerId.value,
                            providerThreadId.value,
                            sender?.value,
                            participants.joinToString(
                                separator = "\u0000",
                                transform = ParticipantAddress::value,
                            ),
                            participantsTruncated,
                            mmsBody,
                            subject,
                            direction.name,
                            box.toStorageCode(),
                            status.toStorageCode(),
                            rawStatus,
                            rawResponseStatus,
                            timestampMillis,
                            sentTimestampMillis,
                            subscriptionId.value,
                            attachments.attachmentCount,
                            attachments.totalBytes,
                            attachments.metadataTruncated,
                            attachments.contentTypes.joinToString(separator = "\u0000") { type ->
                                "${type.mimeType}\u0000${type.displayName}"
                            },
                            read,
                            seen,
                            locked,
                        ),
                    ),
                    resolvedContactName = resolvedContactName,
                    deletedFromProvider = deletedFromProvider,
                )
            }

            else -> error("Synthetic index fixtures only project SMS and MMS")
        }
    }

    override fun toString(): String =
        "SyntheticIndexFixture(shape=$shape, size=$size, content=REDACTED)"

    private fun threadIdAt(index: Int): Long {
        val threadCount = shape.expectedThreadCount
        check(threadCount > 0)
        val seedOffset = Math.floorMod(seed, threadCount.toLong()).toInt()
        val threadOrdinal = when (shape) {
            SyntheticIndexShape.SINGLE_THREAD_250_THOUSAND -> 0
            else -> (index + seedOffset) % threadCount
        }
        return FIRST_PROVIDER_THREAD_ID + threadOrdinal
    }

    private fun senderAt(index: Int): ParticipantAddress? =
        if (index % NULL_SENDER_INTERVAL == NULL_SENDER_OFFSET) {
            null
        } else {
            SYNTHETIC_ADDRESSES[Math.floorMod(mixedValue(index), SYNTHETIC_ADDRESSES.size.toLong()).toInt()]
        }

    private fun bodyAt(index: Int): String {
        val token = (mixedValue(index) and Long.MAX_VALUE).toString(radix = 36)
        val suffix = " Record $token."
        return when (Math.floorMod(index.toLong() + seed, BODY_VARIANT_COUNT.toLong()).toInt()) {
            0 -> SyntheticIndexFixtures.GSM_BODY + suffix
            1 -> SyntheticIndexFixtures.UNICODE_BODY + suffix
            2 -> SyntheticIndexFixtures.EMOJI_BODY + suffix
            3 -> SyntheticIndexFixtures.COMBINING_BODY + suffix
            4 -> SyntheticIndexFixtures.RTL_BODY + suffix
            5 -> longBody(suffix)
            6 -> "Synthetic fixture common-token alpha beta.$suffix"
            else -> "Synthetic fixture punctuation-safe plus+minus operator-like OR.$suffix"
        }
    }

    private fun longBody(suffix: String): String {
        val prefix = "Synthetic long-body fixture.$suffix "
        return prefix + "x".repeat(SyntheticIndexFixtures.LONG_BODY_CHARACTERS - prefix.length)
    }

    private fun subjectAt(index: Int): String? =
        if (index % NULL_SUBJECT_INTERVAL == NULL_SUBJECT_OFFSET) {
            null
        } else {
            "Synthetic fixture subject ${(mixedValue(index) and Long.MAX_VALUE).toString(36)}"
        }

    private fun attachmentsAt(index: Int): MmsAttachmentSummary =
        if (index % HEAVY_ATTACHMENT_INTERVAL == HEAVY_ATTACHMENT_OFFSET) {
            HEAVY_ATTACHMENTS
        } else if (index % 5 == 0) {
            ONE_TEXT_ATTACHMENT
        } else {
            MmsAttachmentSummary.EMPTY
        }

    private fun fingerprintFor(index: Int, vararg projectionValues: Any?): MessageSyncFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: Any?) {
            val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
        }
        update(seed)
        update(index)
        projectionValues.forEach(::update)
        return MessageSyncFingerprint.fromSha256(digest.digest())
    }

    private fun mixedValue(index: Int): Long {
        var mixed = seed + GOLDEN_GAMMA * (index.toLong() + 1L)
        mixed = (mixed xor (mixed ushr 30)) * MIX_MULTIPLIER_ONE
        mixed = (mixed xor (mixed ushr 27)) * MIX_MULTIPLIER_TWO
        return mixed xor (mixed ushr 31)
    }

    private companion object {
        const val FIRST_PROVIDER_THREAD_ID: Long = 100_001L
        const val SAME_MILLISECOND_GROUP_SIZE: Long = 4L
        const val TIMESTAMP_STEP_MILLIS: Long = 1_000L
        const val BODY_VARIANT_COUNT: Int = 8
        const val CONTACT_INTERVAL: Int = 3
        const val CONTACT_VARIANTS: Int = 7
        const val NULL_SENDER_INTERVAL: Int = 17
        const val NULL_SENDER_OFFSET: Int = 4
        const val DELETED_MARKER_INTERVAL: Int = 29
        const val DELETED_MARKER_OFFSET: Int = 13
        const val NULL_SUBJECT_INTERVAL: Int = 7
        const val NULL_SUBJECT_OFFSET: Int = 1
        const val HEAVY_ATTACHMENT_INTERVAL: Int = 31
        const val HEAVY_ATTACHMENT_OFFSET: Int = 3
        const val NULL_MMS_BODY_INTERVAL: Int = 37
        const val NULL_MMS_BODY_OFFSET: Int = 7
        const val LOCKED_INTERVAL: Int = 41
        const val TRUNCATED_PARTICIPANT_INTERVAL: Int = 47

        const val GOLDEN_GAMMA: Long = -7_046_029_254_386_353_131L
        const val MIX_MULTIPLIER_ONE: Long = -4_658_895_280_553_007_687L
        const val MIX_MULTIPLIER_TWO: Long = -7_723_592_293_110_705_685L

        val SYNTHETIC_ADDRESSES: List<ParticipantAddress> = listOf(
            ParticipantAddress("+12025550111"),
            ParticipantAddress("+12025550112"),
            ParticipantAddress("+12025550113"),
            ParticipantAddress("relay@fixtures.example.invalid"),
        )
        val FALLBACK_PARTICIPANT = ParticipantAddress("+12025550114")
        val GROUP_PARTICIPANT = ParticipantAddress("group@fixtures.example.invalid")

        val ONE_TEXT_ATTACHMENT = MmsAttachmentSummary(
            attachmentCount = 1,
            totalBytes = 512L,
            contentTypes = listOf(MmsAttachmentType("text/plain", "synthetic-note.txt")),
            metadataTruncated = false,
        )
        val HEAVY_ATTACHMENTS = MmsAttachmentSummary(
            attachmentCount = MmsAttachmentSummary.MAX_ATTACHMENT_COUNT,
            totalBytes = 25L * 1_024L * 1_024L,
            contentTypes = List(MmsAttachmentSummary.MAX_ATTACHMENT_COUNT) { attachmentIndex ->
                MmsAttachmentType(
                    mimeType = when (attachmentIndex % 3) {
                        0 -> "image/png"
                        1 -> "audio/ogg"
                        else -> "application/pdf"
                    },
                    displayName = "synthetic-attachment-${attachmentIndex + 1}",
                )
            },
            metadataTruncated = true,
        )

        fun participantsFor(sender: ParticipantAddress?): List<ParticipantAddress> =
            listOf(sender ?: FALLBACK_PARTICIPANT, GROUP_PARTICIPANT).distinct()

        fun sentTimestampFor(timestampMillis: Long): Long = timestampMillis - 250L

        fun boxFor(index: Int, direction: MessageDirection): MessageBox = when {
            index % 43 == 9 -> MessageBox.FAILED
            index % 19 == 8 -> MessageBox.OUTBOX
            direction == MessageDirection.INCOMING -> MessageBox.INBOX
            else -> MessageBox.SENT
        }

        fun statusFor(box: MessageBox): MessageStatus = when (box) {
            MessageBox.FAILED -> MessageStatus.FAILED
            MessageBox.OUTBOX, MessageBox.QUEUED -> MessageStatus.PENDING
            MessageBox.SENT -> MessageStatus.COMPLETE
            MessageBox.INBOX, MessageBox.DRAFT -> MessageStatus.NONE
            MessageBox.UNKNOWN -> MessageStatus.UNKNOWN
        }

        fun rawStatusFor(status: MessageStatus): Int? = when (status) {
            MessageStatus.NONE -> null
            MessageStatus.PENDING -> 32
            MessageStatus.COMPLETE -> 0
            MessageStatus.FAILED -> 64
            MessageStatus.UNKNOWN -> -1
        }
    }
}

/** One generated provider projection plus synthetic reconciliation metadata. */
sealed interface SyntheticIndexRecord {
    val providerId: ProviderMessageId
    val providerThreadId: ProviderThreadId
    val sender: ParticipantAddress?
    val body: String?
    val subject: String?
    val timestampMillis: Long
    val subscriptionId: AuroraSubscriptionId?
    val resolvedContactName: String?
    val deletedFromProvider: Boolean

    @ConsistentCopyVisibility
    data class Sms internal constructor(
        val providerMessage: SmsProviderMessage,
        override val resolvedContactName: String?,
        override val deletedFromProvider: Boolean,
    ) : SyntheticIndexRecord {
        override val providerId: ProviderMessageId
            get() = providerMessage.id
        override val providerThreadId: ProviderThreadId
            get() = providerMessage.providerThreadId
        override val sender: ParticipantAddress?
            get() = providerMessage.sender
        override val body: String
            get() = providerMessage.body
        override val subject: String? = null
        override val timestampMillis: Long
            get() = providerMessage.timestampMillis
        override val subscriptionId: AuroraSubscriptionId?
            get() = providerMessage.subscriptionId

        override fun toString(): String =
            "SyntheticIndexRecord.Sms(content=REDACTED, " +
                "hasResolvedContact=${resolvedContactName != null}, " +
                "deletedFromProvider=$deletedFromProvider)"
    }

    @ConsistentCopyVisibility
    data class Mms internal constructor(
        val providerMessage: MmsProviderMessage,
        override val resolvedContactName: String?,
        override val deletedFromProvider: Boolean,
    ) : SyntheticIndexRecord {
        override val providerId: ProviderMessageId
            get() = providerMessage.id
        override val providerThreadId: ProviderThreadId
            get() = providerMessage.providerThreadId
        override val sender: ParticipantAddress?
            get() = providerMessage.sender
        override val body: String?
            get() = providerMessage.body
        override val subject: String?
            get() = providerMessage.subject
        override val timestampMillis: Long
            get() = providerMessage.timestampMillis
        override val subscriptionId: AuroraSubscriptionId?
            get() = providerMessage.subscriptionId

        override fun toString(): String =
            "SyntheticIndexRecord.Mms(content=REDACTED, " +
                "hasResolvedContact=${resolvedContactName != null}, " +
                "deletedFromProvider=$deletedFromProvider)"
    }
}
