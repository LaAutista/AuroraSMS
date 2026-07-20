// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.telephony.PhoneNumberUtils
import java.util.Locale
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.DecodedIncomingMmsPart
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord

internal fun BoundedMmsPdu.Retrieved.toIncomingProviderRecord(
    journal: IncomingMmsDownloadJournal.Record,
    ownAddress: ParticipantAddress?,
    addressesMatch: (ParticipantAddress, ParticipantAddress) -> Boolean = ::mmsAddressesMatch,
): IncomingMmsProjectionResult {
    if (transactionId != null && transactionId != journal.transactionId) {
        return IncomingMmsProjectionResult.Malformed
    }
    val exactSender = sender ?: return IncomingMmsProjectionResult.Malformed
    val threadParticipants = resolveIncomingMmsThreadParticipants(
        sender = exactSender,
        to = to,
        cc = cc,
        ownAddress = ownAddress,
        addressesMatch = addressesMatch,
    ) ?: return IncomingMmsProjectionResult.OwnAddressUnavailable
    val text = parts.asSequence()
        .filter { it.contentType == TEXT_PLAIN_MIME }
        .mapNotNull(BoundedMmsPart::decodedText)
        .filter(String::isNotEmpty)
        .joinToString("\n")
        .takeIf(String::isNotEmpty)
    return try {
        IncomingMmsProjectionResult.Ready(
            DecodedIncomingMmsRecord(
                operationId = journal.operationId,
                sender = exactSender,
                participants = threadParticipants,
                to = to,
                cc = cc,
                subject = subject,
                text = text,
                sentTimestampMillis = sentTimestampMillis,
                receivedTimestampMillis = journal.receivedTimestampMillis,
                subscriptionId = journal.subscriptionId,
                notificationTransactionId = journal.transactionId,
                messageId = messageId,
                parts = parts.map(BoundedMmsPart::toProviderPart),
            ),
        )
    } catch (_: IllegalArgumentException) {
        IncomingMmsProjectionResult.Malformed
    }
}

internal sealed interface IncomingMmsProjectionResult {
    data class Ready(val record: DecodedIncomingMmsRecord) : IncomingMmsProjectionResult
    data object Malformed : IncomingMmsProjectionResult
    data object OwnAddressUnavailable : IncomingMmsProjectionResult
}

internal fun resolveIncomingMmsThreadParticipants(
    sender: ParticipantAddress,
    to: List<ParticipantAddress>,
    cc: List<ParticipantAddress>,
    ownAddress: ParticipantAddress?,
    addressesMatch: (ParticipantAddress, ParticipantAddress) -> Boolean = ::mmsAddressesMatch,
): List<ParticipantAddress>? {
    val destinations = (to + cc)
        .distinctBy { it.value.lowercase(Locale.ROOT) }
        .filterNot { addressesMatch(it, sender) }
    val remoteDestinations = if (ownAddress == null) {
        if (destinations.size > 1) return null
        emptyList()
    } else {
        destinations.filterNot { addressesMatch(it, ownAddress) }
    }
    return (listOf(sender) + remoteDestinations)
        .distinctBy { it.value.lowercase(Locale.ROOT) }
        .takeIf { it.size <= org.aurorasms.core.telephony.MmsProviderMessage.MAX_MMS_PARTICIPANTS }
}

private fun BoundedMmsPart.toProviderPart(): DecodedIncomingMmsPart = DecodedIncomingMmsPart(
    contentType = contentType,
    charsetMibEnum = charsetMibEnum,
    name = name,
    filename = filename,
    contentLocation = contentLocation,
    contentId = contentId,
    contentDisposition = contentDisposition,
    decodedText = decodedText,
    bytes = copyBytes(),
)

@Suppress("DEPRECATION")
private fun mmsAddressesMatch(first: ParticipantAddress, second: ParticipantAddress): Boolean =
    if ('@' in first.value || '@' in second.value) {
        first.value.equals(second.value, ignoreCase = true)
    } else {
        PhoneNumberUtils.compare(first.value, second.value)
    }

private const val TEXT_PLAIN_MIME = "text/plain"
