// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionRecoveryActionTest {
    @Test
    fun unavailableOrGrantedPermissionNeedsNoRecovery() {
        assertEquals(
            NotificationPermissionRecoveryAction.NONE,
            action(required = false, granted = false, requestedBefore = false, rationale = false),
        )
        assertEquals(
            NotificationPermissionRecoveryAction.NONE,
            action(required = true, granted = true, requestedBefore = true, rationale = false),
        )
    }

    @Test
    fun firstRequestDismissedDialogAndRationaleRetryUseRuntimePermissionDialog() {
        assertEquals(
            NotificationPermissionRecoveryAction.REQUEST_PERMISSION,
            action(required = true, granted = false, requestedBefore = false, rationale = false),
        )
        assertEquals(
            NotificationPermissionRecoveryAction.REQUEST_PERMISSION,
            action(required = true, granted = false, requestedBefore = true, rationale = true),
        )
    }

    @Test
    fun previouslyRequestedPermanentDenialOpensSettings() {
        assertEquals(
            NotificationPermissionRecoveryAction.OPEN_SETTINGS,
            action(required = true, granted = false, requestedBefore = true, rationale = false),
        )
    }

    @Test
    fun onlyExplicitGrantOrDenialRecordsACompletedUserDecision() {
        val permission = "android.permission.POST_NOTIFICATIONS"

        assertEquals(
            false,
            notificationPermissionResultRecordsDecision(emptyMap(), permission),
        )
        assertEquals(
            true,
            notificationPermissionResultRecordsDecision(mapOf(permission to false), permission),
        )
        assertEquals(
            true,
            notificationPermissionResultRecordsDecision(mapOf(permission to true), permission),
        )
    }

    private fun action(
        required: Boolean,
        granted: Boolean,
        requestedBefore: Boolean,
        rationale: Boolean,
    ): NotificationPermissionRecoveryAction = notificationPermissionRecoveryAction(
        notificationPermissionRequired = required,
        notificationPermissionGranted = granted,
        requestedBefore = requestedBefore,
        shouldShowRationale = rationale,
    )
}
