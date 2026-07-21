// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.model.ParticipantAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMessageUiModelHostTest {
    @Test
    fun newMessageControlsKeepEveryThreadOnlyOrSendingActionOff() {
        val controls = MessageComposerControls.NEW_MESSAGE_DRAFT

        assertFalse(controls.showAttachments)
        assertFalse(controls.showSubject)
        assertFalse(controls.showVoiceMemo)
        assertFalse(controls.showScheduling)
        assertFalse(controls.allowUndoAndRecovery)
        assertFalse(controls.sendEnabled)
    }

    @Test
    fun recipientPresentationDoesNotLeakAddressOrLabelThroughToString() {
        val address = "+12025550177"
        val label = "Synthetic Secret Recipient"
        val item = NewMessageRecipientUiItem(
            address = ParticipantAddress(address),
            displayLabel = label,
        )

        assertFalse(item.toString().contains(address))
        assertFalse(item.toString().contains(label))
        assertTrue(item.toString().contains("REDACTED"))
    }
}
