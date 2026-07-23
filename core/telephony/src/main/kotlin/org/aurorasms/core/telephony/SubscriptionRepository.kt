// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId

data class ActiveSubscription(
    val id: AuroraSubscriptionId,
    val slotIndex: Int,
    val displayLabel: String,
    val smsCapable: Boolean,
) {
    init {
        require(slotIndex >= 0) { "Active subscriptions need a valid slot index" }
        require(displayLabel.length <= MAX_DISPLAY_LABEL_CHARACTERS) { "Subscription label is too long" }
    }

    override fun toString(): String =
        "ActiveSubscription(slotIndex=$slotIndex, smsCapable=$smsCapable, displayLabel=REDACTED)"

    companion object {
        const val MAX_DISPLAY_LABEL_CHARACTERS: Int = 100
    }
}

sealed interface SubscriptionSnapshot {
    data class Available(val subscriptions: List<ActiveSubscription>) : SubscriptionSnapshot
    data object PermissionDenied : SubscriptionSnapshot
    data object FeatureUnavailable : SubscriptionSnapshot
    data object PlatformUnavailable : SubscriptionSnapshot
}

interface SubscriptionRepository {
    suspend fun activeSubscriptions(): SubscriptionSnapshot

    suspend fun findActive(id: AuroraSubscriptionId): ActiveSubscription? =
        (activeSubscriptions() as? SubscriptionSnapshot.Available)
            ?.subscriptions
            ?.firstOrNull { it.id == id }
}

/** Typed, fail-closed validation of one explicitly selected SMS subscription. */
sealed interface ActiveSmsSubscriptionValidation {
    data class Valid(val subscription: ActiveSubscription) : ActiveSmsSubscriptionValidation
    data object Inactive : ActiveSmsSubscriptionValidation
    data object Ambiguous : ActiveSmsSubscriptionValidation
    data object NotSmsCapable : ActiveSmsSubscriptionValidation
    data object PermissionDenied : ActiveSmsSubscriptionValidation
    data object FeatureUnavailable : ActiveSmsSubscriptionValidation
    data object PlatformUnavailable : ActiveSmsSubscriptionValidation
}

/**
 * Re-enumerates subscriptions and validates the exact selected ID without default-SIM fallback.
 * Enumeration failures retain their original typed meaning.
 */
suspend fun SubscriptionRepository.validateActiveSmsSubscription(
    id: AuroraSubscriptionId,
): ActiveSmsSubscriptionValidation = when (val snapshot = activeSubscriptions()) {
    is SubscriptionSnapshot.Available -> {
        val matches = snapshot.subscriptions.filter { it.id == id }
        val subscription = matches.singleOrNull()
        when {
            matches.isEmpty() -> ActiveSmsSubscriptionValidation.Inactive
            subscription == null -> ActiveSmsSubscriptionValidation.Ambiguous
            !subscription.smsCapable -> ActiveSmsSubscriptionValidation.NotSmsCapable
            else -> ActiveSmsSubscriptionValidation.Valid(subscription)
        }
    }
    SubscriptionSnapshot.PermissionDenied -> ActiveSmsSubscriptionValidation.PermissionDenied
    SubscriptionSnapshot.FeatureUnavailable -> ActiveSmsSubscriptionValidation.FeatureUnavailable
    SubscriptionSnapshot.PlatformUnavailable -> ActiveSmsSubscriptionValidation.PlatformUnavailable
}
