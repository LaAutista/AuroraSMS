// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

internal enum class ContactsPermissionRecoveryAction {
    NONE,
    REQUEST_PERMISSION,
    OPEN_SETTINGS,
}

/**
 * Chooses the next explicit recovery action after the user asks for contact access.
 *
 * Contact access is optional and never belongs to messaging onboarding. A prior denial with no
 * rationale means Android will no longer show a useful runtime dialog, so the only truthful
 * recovery path is application settings.
 */
internal fun contactsPermissionRecoveryAction(
    contactsPermissionGranted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): ContactsPermissionRecoveryAction = when {
    contactsPermissionGranted -> ContactsPermissionRecoveryAction.NONE
    !requestedBefore || shouldShowRationale ->
        ContactsPermissionRecoveryAction.REQUEST_PERMISSION
    else -> ContactsPermissionRecoveryAction.OPEN_SETTINGS
}

/** A dismissed multi-permission dialog returns no entry and is not a durable decision. */
internal fun contactsPermissionResultRecordsDecision(
    results: Map<String, Boolean>,
    permission: String,
): Boolean = results.containsKey(permission)

internal const val CONTACTS_PERMISSION_PREFERENCES =
    "aurora_contacts_permission_state"
internal const val CONTACTS_PERMISSION_REQUESTED_BEFORE = "requested_before"
