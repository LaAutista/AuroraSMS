// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.model.ParticipantAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun contactDiscoveryPresentationIsBoundedUniqueAndRedacted() {
        val query = "Synthetic Secret Contact"
        val address = ParticipantAddress("+12025550178")
        val label = "Synthetic Private Label"
        val item = NewMessageContactResultUiItem(address, label)
        val states = listOf(
            NewMessageContactDiscoveryUiState.Empty(query, truncated = true),
            NewMessageContactDiscoveryUiState.Loading(query),
            NewMessageContactDiscoveryUiState.Unavailable(query),
            NewMessageContactDiscoveryUiState.Error(query),
            NewMessageContactDiscoveryUiState.Results(query, listOf(item), truncated = true),
        )

        assertFalse(item.toString().contains(address.value))
        assertFalse(item.toString().contains(label))
        states.forEach { state ->
            assertFalse(state.toString().contains(query))
            assertFalse(state.toString().contains(address.value))
            assertFalse(state.toString().contains(label))
            assertTrue(state.toString().contains("REDACTED"))
        }

        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Empty(
                "x".repeat(MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Results(query, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Results("", listOf(item))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Empty("", truncated = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Results(query, listOf(item, item))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewMessageContactDiscoveryUiState.Results(
                query = query,
                items = (0..MAXIMUM_NEW_MESSAGE_CONTACT_RESULTS).map { index ->
                    NewMessageContactResultUiItem(
                        address = ParticipantAddress("synthetic-$index"),
                    )
                },
            )
        }
    }
}
