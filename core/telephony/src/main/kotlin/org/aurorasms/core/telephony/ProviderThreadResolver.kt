// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.ProviderThreadId

/**
 * Allocates or locates one provider thread and verifies its exact participant set.
 *
 * Callers must durably fence provider resolution before invoking this boundary. A thrown
 * exception or cancellation never proves that allocator entry did not occur and must be treated
 * as [ProviderThreadResolution.MutationOutcomeUnknown]. Only an explicit typed preflight outcome
 * below states that no allocator call was made.
 */
interface ProviderThreadResolver {
    suspend fun resolveExact(recipients: RecipientSet): ProviderThreadResolution
}

/** Redacted outcomes for the provider-thread mutation and readback boundary. */
sealed interface ProviderThreadResolution {
    data class Verified(
        val providerThreadId: ProviderThreadId,
        val participantCount: Int,
    ) : ProviderThreadResolution {
        init {
            require(participantCount in 1..RecipientSet.MAX_RECIPIENTS) {
                "Verified provider participant count is outside the reviewed bound"
            }
        }

        override fun toString(): String =
            "ProviderThreadResolution.Verified(participantCount=$participantCount, " +
                "providerThreadId=REDACTED)"
    }

    /** The SMS role is available but is not currently held. No allocator call was made. */
    data object RoleRequired : ProviderThreadResolution

    /** READ_SMS is absent. No allocator call was made. */
    data object PermissionDenied : ProviderThreadResolution

    /** The platform cannot provide this operation. No allocator call was made. */
    data object PlatformUnavailable : ProviderThreadResolution

    /** A thread was returned, but its bounded participant readback was not an exact match. */
    data object ExactParticipantsUnverified : ProviderThreadResolution

    /** Allocator entry occurred, so an exception or authority loss cannot prove no mutation. */
    data object MutationOutcomeUnknown : ProviderThreadResolution
}

/** Safe default for builds that deliberately do not expose provider-thread resolution. */
data object UnavailableProviderThreadResolver : ProviderThreadResolver {
    override suspend fun resolveExact(recipients: RecipientSet): ProviderThreadResolution =
        ProviderThreadResolution.PlatformUnavailable
}
