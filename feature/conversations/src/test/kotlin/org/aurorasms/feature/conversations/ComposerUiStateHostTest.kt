// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerUiStateHostTest {
    @Test
    fun `toString reports send metadata without revealing the draft body`() {
        val secretBody = "SYNTHETIC_SECRET_COMPOSER_BODY"
        val state = ComposerUiState(
            body = secretBody,
            saving = false,
            failed = false,
            sendState = ComposerSendState.UNAVAILABLE,
            unavailableReason = ComposerUnavailableReason.MULTIPART_UNAVAILABLE,
            segmentCount = 3,
        )

        val rendered = state.toString()

        assertFalse(rendered.contains(secretBody))
        assertTrue(rendered.contains("bodyLength=${secretBody.length}"))
        assertTrue(rendered.contains("sendState=UNAVAILABLE"))
        assertTrue(rendered.contains("unavailableReason=MULTIPART_UNAVAILABLE"))
        assertTrue(rendered.contains("segmentCount=3"))
        assertTrue(rendered.contains("scheduleState=None"))
        assertTrue(rendered.contains("REDACTED"))
    }
}
