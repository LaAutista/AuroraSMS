// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsPermissionRecoveryActionTest {
    @Test
    fun grantedPermissionNeedsNoRecovery() {
        assertEquals(
            ContactsPermissionRecoveryAction.NONE,
            action(granted = true, requestedBefore = true, rationale = false),
        )
    }

    @Test
    fun firstRequestAndRationaleRetryUseRuntimeDialog() {
        assertEquals(
            ContactsPermissionRecoveryAction.REQUEST_PERMISSION,
            action(granted = false, requestedBefore = false, rationale = false),
        )
        assertEquals(
            ContactsPermissionRecoveryAction.REQUEST_PERMISSION,
            action(granted = false, requestedBefore = true, rationale = true),
        )
    }

    @Test
    fun priorPermanentDenialUsesApplicationSettings() {
        assertEquals(
            ContactsPermissionRecoveryAction.OPEN_SETTINGS,
            action(granted = false, requestedBefore = true, rationale = false),
        )
    }

    @Test
    fun dismissedDialogDoesNotRecordACompletedDecision() {
        val permission = "android.permission.READ_CONTACTS"

        assertEquals(false, contactsPermissionResultRecordsDecision(emptyMap(), permission))
        assertEquals(
            true,
            contactsPermissionResultRecordsDecision(mapOf(permission to false), permission),
        )
        assertEquals(
            true,
            contactsPermissionResultRecordsDecision(mapOf(permission to true), permission),
        )
    }

    private fun action(
        granted: Boolean,
        requestedBefore: Boolean,
        rationale: Boolean,
    ): ContactsPermissionRecoveryAction = contactsPermissionRecoveryAction(
        contactsPermissionGranted = granted,
        requestedBefore = requestedBefore,
        shouldShowRationale = rationale,
    )
}
