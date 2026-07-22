// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.FirstContactOperationId
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.telephony.RecipientSet

/**
 * Exact, content-free input for the headless first-contact ownership checkpoint.
 *
 * The durable draft remains the sole body, subject, and attachment authority. A caller must obtain
 * [draftId], [expectedDraftRevision], [transport], and [frozenSignature] from one frozen
 * [participantDraftIdentity] writer; external review data and an unacknowledged editor snapshot
 * are not eligible inputs.
 */
internal data class FirstContactOwnershipCommand(
    val recipients: RecipientSet,
    val participantDraftIdentity: DraftIdentity.ParticipantSet,
    val draftId: DraftId,
    val expectedDraftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val transport: MessageTransportKind,
    val frozenSignature: MessageSignature? = null,
) {
    override fun toString(): String = "FirstContactOwnershipCommand(REDACTED)"
}

/** Role and provider-read permission check performed immediately before each authority boundary. */
internal fun interface FirstContactAuthorityPreflightGate {
    fun evaluate(): FirstContactAuthorityPreflight
}

internal enum class FirstContactAuthorityPreflight {
    READY,
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    PLATFORM_UNAVAILABLE,
}

internal enum class FirstContactOwnershipDenialReason {
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    SUBSCRIPTION_PERMISSION_DENIED,
    SUBSCRIPTION_UNAVAILABLE,
    SUBSCRIPTION_AMBIGUOUS,
    PLATFORM_UNAVAILABLE,
}

internal enum class FirstContactOwnershipConflictReason {
    ACTIVE_OPERATION,
    STALE_DRAFT,
    INELIGIBLE_DRAFT,
    INVALID_PHASE,
    THREAD_BINDING,
    TARGET_DRAFT,
    EXACT_PARTICIPANTS_UNVERIFIED,
}

internal enum class FirstContactOwnershipFailureReason {
    STORAGE_UNAVAILABLE,
    CORRUPT_STATE,
    INVALID_TIMESTAMP,
}

/**
 * Result of the synthetic ownership flow. None of these outcomes mean a message was submitted.
 */
internal sealed interface FirstContactOwnershipResult {
    data class HandoffReserved(
        val operationId: FirstContactOperationId,
        val providerThreadId: ProviderThreadId,
        val draftId: DraftId,
        val boundDraftRevision: DraftRevision,
    ) : FirstContactOwnershipResult {
        override fun toString(): String = "FirstContactOwnershipResult.HandoffReserved(REDACTED)"
    }

    data class Denied(
        val reason: FirstContactOwnershipDenialReason,
        val operationId: FirstContactOperationId? = null,
    ) : FirstContactOwnershipResult {
        override fun toString(): String =
            "FirstContactOwnershipResult.Denied(reason=$reason, hasOperation=${operationId != null}, REDACTED)"
    }

    data class Conflict(
        val reason: FirstContactOwnershipConflictReason,
        val operationId: FirstContactOperationId? = null,
    ) : FirstContactOwnershipResult {
        override fun toString(): String =
            "FirstContactOwnershipResult.Conflict(reason=$reason, " +
                "hasOperation=${operationId != null}, REDACTED)"
    }

    data class Failure(
        val reason: FirstContactOwnershipFailureReason,
        val operationId: FirstContactOperationId? = null,
    ) : FirstContactOwnershipResult {
        override fun toString(): String =
            "FirstContactOwnershipResult.Failure(reason=$reason, " +
                "hasOperation=${operationId != null}, REDACTED)"
    }

    /** Provider allocation may have started; recovery must never invoke it again. */
    data class ResolutionUnknown(
        val operationId: FirstContactOperationId,
    ) : FirstContactOwnershipResult {
        override fun toString(): String = "FirstContactOwnershipResult.ResolutionUnknown(REDACTED)"
    }

    /**
     * A typed cancellation result. [resolutionUnknown] is true after the durable provider
     * resolution fence was entered; in that case the operation is fenced from automatic retry.
     */
    data class Cancelled(
        val operationId: FirstContactOperationId?,
        val resolutionUnknown: Boolean,
    ) : FirstContactOwnershipResult {
        override fun toString(): String =
            "FirstContactOwnershipResult.Cancelled(hasOperation=${operationId != null}, " +
                "resolutionUnknown=$resolutionUnknown, REDACTED)"
    }
}

internal interface FirstContactOwnershipController {
    suspend fun reserveAndBind(command: FirstContactOwnershipCommand): FirstContactOwnershipResult
}

internal object UnavailableFirstContactOwnershipController : FirstContactOwnershipController {
    override suspend fun reserveAndBind(
        command: FirstContactOwnershipCommand,
    ): FirstContactOwnershipResult = FirstContactOwnershipResult.Failure(
        FirstContactOwnershipFailureReason.STORAGE_UNAVAILABLE,
    )
}
