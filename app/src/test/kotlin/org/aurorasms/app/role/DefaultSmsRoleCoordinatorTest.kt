// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.role

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmsRoleCoordinatorTest {
    @Test
    fun missingTelephonyIsUnsupported() {
        val platform = FakePlatform(snapshot(telephony = false))
        val coordinator = DefaultSmsRoleCoordinator(platform)

        val state = coordinator.requestRole()

        assertEquals(
            RoleOnboardingState.Unsupported(UnsupportedReason.TELEPHONY_MISSING),
            state,
        )
        assertEquals(0, platform.requests)
    }

    @Test
    fun requestStartsOnlyWhenAvailable() {
        val platform = FakePlatform(snapshot())
        val coordinator = DefaultSmsRoleCoordinator(platform)

        val state = coordinator.requestRole()

        assertEquals(RoleOnboardingState.Requesting, state)
        assertEquals(1, platform.requests)
    }

    @Test
    fun cancellationReturnsToUsableState() {
        val platform = FakePlatform(snapshot())
        val coordinator = DefaultSmsRoleCoordinator(platform)
        coordinator.requestRole()

        val state = coordinator.onRoleRequestResult()

        assertEquals(RoleOnboardingState.Cancelled, state)
    }

    @Test
    fun heldRoleIsRecheckedAfterResult() {
        val platform = FakePlatform(snapshot())
        val coordinator = DefaultSmsRoleCoordinator(platform)
        coordinator.requestRole()
        platform.current = snapshot(held = true)

        val state = coordinator.onRoleRequestResult()

        assertEquals(RoleOnboardingState.Held, state)
    }

    private class FakePlatform(
        var current: SmsRoleSnapshot,
    ) : SmsRolePlatform {
        var requests = 0

        override fun snapshot(): SmsRoleSnapshot = current

        override fun requestRole(): RoleRequestStart {
            requests += 1
            return when {
                current.roleHeld -> RoleRequestStart.ALREADY_HELD
                current.roleAvailable -> RoleRequestStart.STARTED
                else -> RoleRequestStart.UNAVAILABLE
            }
        }
    }

    private fun snapshot(
        telephony: Boolean = true,
        messaging: Boolean = true,
        available: Boolean = true,
        held: Boolean = false,
    ) = SmsRoleSnapshot(
        telephonyCapable = telephony,
        messagingCapable = messaging,
        subscriptionCapable = true,
        roleAvailable = available,
        roleHeld = held,
    )
}
