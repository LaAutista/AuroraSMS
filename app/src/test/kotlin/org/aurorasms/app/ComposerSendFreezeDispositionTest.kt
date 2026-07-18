// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.FrozenDraftSnapshot
import org.aurorasms.app.message.ThreadSmsSendAttempt
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendFreezeDispositionTest {
    @Test
    fun localRefusalBeforeCoordinatorReopensEditing() {
        assertTrue(shouldUnfreezeComposerAsRefused(coordinatorEntered = false, attempt = null))
    }

    @Test
    fun explicitCoordinatorRefusalReopensEditing() {
        assertTrue(
            shouldUnfreezeComposerAsRefused(
                coordinatorEntered = true,
                attempt = ThreadSmsSendAttempt.REFUSED,
            ),
        )
    }

    @Test
    fun exceptionalCoordinatorExitRemainsFrozenForRecovery() {
        assertFalse(shouldUnfreezeComposerAsRefused(coordinatorEntered = true, attempt = null))
        assertFalse(
            shouldUnfreezeComposerAsRefused(
                coordinatorEntered = true,
                attempt = ThreadSmsSendAttempt.STARTED,
            ),
        )
    }

    @Test
    fun frozenSnapshotUpgradesBaseFreeSavedStateToExactRoomBase() {
        val content = DraftEditorContent(body = "exact frozen body", subject = null)
        val token = FrozenDraftSnapshot(
            content = content,
            draftId = DraftId(17L),
            revision = DraftRevision(29L),
        ).toExactRestorationToken()

        assertEquals(content, token.content)
        assertEquals(DraftId(17L), token.expectedDraftId)
        assertEquals(DraftRevision(29L), token.expectedRevision)
    }
}
