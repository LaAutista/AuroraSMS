// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.telephony.DefaultSmsRoleState

class FakeRoleState(
    var available: Boolean = true,
    var held: Boolean = true,
) : DefaultSmsRoleState {
    override fun isRoleAvailable(): Boolean = available

    override fun isRoleHeld(): Boolean = held
}
