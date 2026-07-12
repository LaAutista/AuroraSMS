// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.role

data class SmsRoleSnapshot(
    val telephonyCapable: Boolean,
    val messagingCapable: Boolean,
    val subscriptionCapable: Boolean,
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
)

enum class RoleRequestStart {
    STARTED,
    ALREADY_HELD,
    UNAVAILABLE,
}

interface SmsRolePlatform {
    fun snapshot(): SmsRoleSnapshot

    fun requestRole(): RoleRequestStart
}
