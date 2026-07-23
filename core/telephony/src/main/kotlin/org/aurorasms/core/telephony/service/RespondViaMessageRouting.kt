// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.service

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership

/** One typed transport decision; no group request can be represented as SMS. */
internal sealed interface RespondViaMessageSubmission {
    data class Sms(val request: SmsSendRequest) : RespondViaMessageSubmission
    data class Mms(val request: MmsSendRequest) : RespondViaMessageSubmission
}

internal fun respondViaMessageSubmission(
    operationId: MessageId,
    recipients: RecipientSet,
    body: String,
    subscriptionId: AuroraSubscriptionId,
): RespondViaMessageSubmission = when (recipients.requiredTransport()) {
    MessageTransportKind.SMS -> RespondViaMessageSubmission.Sms(
        SmsSendRequest(
            operationId = operationId,
            recipients = recipients,
            body = body,
            subscriptionId = subscriptionId,
        ),
    )
    MessageTransportKind.MMS -> RespondViaMessageSubmission.Mms(
        MmsSendRequest(
            operationId = operationId,
            recipients = recipients,
            payload = OutgoingMmsPayload.RequiresEncoding(
                text = body,
                subject = null,
                attachmentCount = 0,
            ),
            subscriptionId = subscriptionId,
        ),
    )
}

/** Submits exactly once. An MMS failure is returned and is never retried as SMS. */
internal suspend fun MessageTransport.submitRespondViaMessage(
    submission: RespondViaMessageSubmission,
): TransportResult = when (submission) {
    is RespondViaMessageSubmission.Sms -> sendSms(
        submission.request,
        ownership = SmsSubmissionOwnership.TransportOwned,
    )
    is RespondViaMessageSubmission.Mms -> sendMms(submission.request)
}
