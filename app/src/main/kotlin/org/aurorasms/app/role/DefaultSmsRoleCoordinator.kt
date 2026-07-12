// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.role

class DefaultSmsRoleCoordinator(
    private val platform: SmsRolePlatform,
) {
    var state: RoleOnboardingState = stateFrom(platform.snapshot())
        private set

    fun refresh(): RoleOnboardingState {
        state = stateFrom(platform.snapshot())
        return state
    }

    fun requestRole(): RoleOnboardingState {
        val current = stateFrom(platform.snapshot())
        if (current != RoleOnboardingState.ReadyToRequest) {
            state = current
            return state
        }
        state = when (platform.requestRole()) {
            RoleRequestStart.STARTED -> RoleOnboardingState.Requesting
            RoleRequestStart.ALREADY_HELD -> RoleOnboardingState.Held
            RoleRequestStart.UNAVAILABLE -> stateFrom(platform.snapshot())
        }
        return state
    }

    fun onRoleRequestResult(): RoleOnboardingState {
        val snapshot = platform.snapshot()
        state = if (snapshot.roleHeld) {
            RoleOnboardingState.Held
        } else {
            when (val refreshed = stateFrom(snapshot)) {
                RoleOnboardingState.ReadyToRequest -> RoleOnboardingState.Cancelled
                else -> refreshed
            }
        }
        return state
    }

    private fun stateFrom(snapshot: SmsRoleSnapshot): RoleOnboardingState = when {
        !snapshot.telephonyCapable ->
            RoleOnboardingState.Unsupported(UnsupportedReason.TELEPHONY_MISSING)
        !snapshot.messagingCapable ->
            RoleOnboardingState.Unsupported(UnsupportedReason.MESSAGING_MISSING)
        !snapshot.roleAvailable ->
            RoleOnboardingState.Unsupported(UnsupportedReason.ROLE_UNAVAILABLE)
        snapshot.roleHeld -> RoleOnboardingState.Held
        else -> RoleOnboardingState.ReadyToRequest
    }
}
