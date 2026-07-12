// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.role

enum class UnsupportedReason {
    TELEPHONY_MISSING,
    MESSAGING_MISSING,
    ROLE_UNAVAILABLE,
}

sealed interface RoleOnboardingState {
    data class Unsupported(val reason: UnsupportedReason) : RoleOnboardingState

    data object ReadyToRequest : RoleOnboardingState

    data object Requesting : RoleOnboardingState

    data object Held : RoleOnboardingState

    data object Cancelled : RoleOnboardingState
}
