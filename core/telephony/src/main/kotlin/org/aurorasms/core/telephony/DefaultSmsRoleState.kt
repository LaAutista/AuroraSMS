// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

interface DefaultSmsRoleState {
    fun isRoleAvailable(): Boolean

    fun isRoleHeld(): Boolean
}
