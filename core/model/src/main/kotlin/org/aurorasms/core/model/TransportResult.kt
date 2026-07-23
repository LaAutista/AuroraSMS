// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A transport result that never represents carrier submission as delivery. */
sealed interface TransportResult {
    val operationId: MessageId
    val transport: MessageTransportKind

    /** Explicit callback ownership; UNMARKED also covers pre-upgrade PendingIntents. */
    val operationOrigin: OperationOrigin
        get() = OperationOrigin.UNMARKED

    data class Submitted(
        override val operationId: MessageId,
        override val transport: MessageTransportKind,
        val unitCount: Int,
        val providerMessageId: ProviderMessageId? = null,
        val providerConversationId: ConversationId? = null,
        override val operationOrigin: OperationOrigin = OperationOrigin.UNMARKED,
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
        val providerConversationId: ConversationId? = null,
        override val operationOrigin: OperationOrigin = OperationOrigin.UNMARKED,
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
        val providerConversationId: ConversationId? = null,
        override val operationOrigin: OperationOrigin = OperationOrigin.UNMARKED,
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
        override val operationOrigin: OperationOrigin = OperationOrigin.UNMARKED,
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
        val providerConversationId: ConversationId? = null,
        val stage: FailureStage = FailureStage.SUBMISSION,
        override val operationOrigin: OperationOrigin = OperationOrigin.UNMARKED,
    ) : TransportResult {
        init {
            require(unitIndex >= 0 && unitCount > 0 && unitIndex < unitCount) {
                "Transport unit position is invalid"
            }
            require(stage != FailureStage.SUBMISSION_UNKNOWN || !retryable) {
                "An uncertain submission must never invite an automatic retry"
            }
        }
    }

    /** Identifies which transport boundary reported a failure. */
    enum class FailureStage {
        /** The platform transport call failed before AuroraSMS received a callback. */
        SUBMISSION,

        /** The irreversible platform call began but its acceptance could not be proven. */
        SUBMISSION_UNKNOWN,

        /** The platform sent callback reported that an outbound unit was not sent. */
        SENT_CALLBACK,

        /** The platform delivery callback reported that an outbound unit was not delivered. */
        DELIVERY_CALLBACK,

        /** The platform MMS download callback or its completed payload processing failed. */
        DOWNLOAD_CALLBACK,
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

    enum class OperationOrigin {
        /** Ordinary operations and callbacks created by builds without an ownership marker. */
        UNMARKED,

        /** A notification inline-reply operation created with the durable ownership protocol. */
        INLINE_REPLY,

        /** An existing-thread composer operation owned by Aurora's durable state database. */
        COMPOSER,
    }
}
