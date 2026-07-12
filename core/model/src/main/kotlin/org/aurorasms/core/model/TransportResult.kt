// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A transport result that never represents carrier submission as delivery. */
sealed interface TransportResult {
    val operationId: MessageId
    val transport: MessageTransportKind

    data class Submitted(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val unitCount: Int,
        val providerMessageId: ProviderMessageId? = null,
    ) : TransportResult {
        init {
            require(unitCount > 0) { "A submitted operation must contain at least one unit" }
        }
    }

    data class Sent(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val platformResultCode: Int,
        val unitIndex: Int = 0,
        val unitCount: Int = 1,
        val providerMessageId: ProviderMessageId? = null,
    ) : TransportResult {
        init {
            require(unitIndex >= 0 && unitCount > 0 && unitIndex < unitCount) {
                "Transport unit position is invalid"
            }
        }
    }

    data class Delivered(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val platformResultCode: Int,
        val unitIndex: Int = 0,
        val unitCount: Int = 1,
        val providerMessageId: ProviderMessageId? = null,
    ) : TransportResult {
        init {
            require(unitIndex >= 0 && unitCount > 0 && unitIndex < unitCount) {
                "Transport unit position is invalid"
            }
        }
    }

    data class Downloaded(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val platformResultCode: Int,
        val byteCount: Int,
    ) : TransportResult {
        init {
            require(transport == MessageTransportKind.MMS) { "Only MMS has a downloaded PDU" }
            require(byteCount > 0) { "A downloaded PDU cannot be empty" }
        }
    }

    data class Rejected(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val reason: FailureReason,
    ) : TransportResult

    data class Failed(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val reason: FailureReason,
        val retryable: Boolean,
        val platformResultCode: Int? = null,
        val unitIndex: Int = 0,
        val unitCount: Int = 1,
        val providerMessageId: ProviderMessageId? = null,
    ) : TransportResult {
        init {
            require(unitIndex >= 0 && unitCount > 0 && unitIndex < unitCount) {
                "Transport unit position is invalid"
            }
        }
    }

    enum class FailureReason {
        ROLE_NOT_HELD,
        PERMISSION_DENIED,
        FEATURE_UNAVAILABLE,
        SUBSCRIPTION_UNAVAILABLE,
        INVALID_RECIPIENT,
        EMPTY_CONTENT,
        PAYLOAD_TOO_LARGE,
        CODEC_UNAVAILABLE,
        PROVIDER_UNAVAILABLE,
        PLATFORM_REJECTED,
        CANCELLED,
        INTERNAL_ERROR,
    }
}
