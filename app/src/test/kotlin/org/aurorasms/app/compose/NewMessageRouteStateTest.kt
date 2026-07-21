// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.DraftWriteStatus
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.feature.conversations.NewMessageDraftStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NewMessageRouteStateTest {
    @Test
    fun `settled recipient route without a durable draft reports ready not saved`() {
        assertEquals(
            NewMessageDraftStatus.READY,
            settledDraft(body = null, draftId = null, revision = null)
                .toNewMessageDraftStatus(hasIdentity = true),
        )
    }

    @Test
    fun `settled route with a durable draft reports saved`() {
        assertEquals(
            NewMessageDraftStatus.SAVED,
            settledDraft(
                body = "durable text",
                draftId = DraftId(2L),
                revision = DraftRevision(3L),
            ).toNewMessageDraftStatus(hasIdentity = true),
        )
    }

    @Test
    fun `review-only prefill is revalidated against a newly durable conflicting draft`() {
        val resolution = resolveExternalPrefill(
            current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
            prefill = "external review text",
            draftStatus = settledDraft(
                body = "concurrent durable winner",
                draftId = DraftId(4L),
                revision = DraftRevision(8L),
            ),
        )

        assertEquals(ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT, resolution)
    }

    @Test
    fun `review-only prefill remains non-durable while Room has no draft`() {
        val resolution = resolveExternalPrefill(
            current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
            prefill = "external review text",
            draftStatus = settledDraft(body = null, draftId = null, revision = null),
        )

        assertEquals(ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL, resolution)
    }

    @Test
    fun `external prefill stays locked while its draft read or write is unsettled`() {
        val saving = settledDraft(
            body = "external review text",
            draftId = null,
            revision = null,
        ).copy(saving = true)

        assertEquals(
            ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
            resolveExternalPrefill(
                current = ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
                prefill = "external review text",
                draftStatus = saving,
            ),
        )
        assertEquals(
            ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
            resolveExternalPrefill(
                current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
                prefill = "external review text",
                draftStatus = DraftWriteStatus.Loading,
            ),
        )
        assertEquals(
            ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
            resolveExternalPrefill(
                current = ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
                prefill = "external review text",
                draftStatus = saving,
            ),
        )
    }

    @Test
    fun `review-only external prefill remains visible when a recreated draft read fails`() {
        val failedLatest = DraftEditorContent(body = null, subject = "preserved subject")

        assertEquals(
            DraftEditorContent(
                body = "external review text",
                subject = "preserved subject",
            ),
            resolveFailedDisplayContent(
                externalPrefillResolution =
                    ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
                externalPrefill = "external review text",
                latest = failedLatest,
            ),
        )
    }

    @Test
    fun `settled equivalent restoration clears a prior storage failure`() {
        val failed = DraftEditorContent(body = "retry this edit", subject = null)

        assertEquals(
            true,
            shouldClearRecoveredFailure(
                failedContent = failed,
                active = settledDraft(
                    body = "retry this edit",
                    draftId = DraftId(9L),
                    revision = DraftRevision(10L),
                ),
                externalPrefillResolution = ExternalPrefillResolution.NOT_APPLICABLE,
            ),
        )
    }

    @Test
    fun `different durable winner keeps a local conflict display-only`() {
        assertEquals(
            false,
            shouldClearRecoveredFailure(
                failedContent = DraftEditorContent("losing local edit", null),
                active = settledDraft(
                    body = "durable winner",
                    draftId = DraftId(11L),
                    revision = DraftRevision(12L),
                ),
                externalPrefillResolution = ExternalPrefillResolution.NOT_APPLICABLE,
            ),
        )
    }

    @Test
    fun `recovered external read clears failure after durable winner revalidation`() {
        assertEquals(
            true,
            shouldClearRecoveredFailure(
                failedContent = DraftEditorContent("external review text", null),
                active = settledDraft(
                    body = "durable winner",
                    draftId = DraftId(13L),
                    revision = DraftRevision(14L),
                ),
                externalPrefillResolution = ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT,
            ),
        )
    }

    @Test
    fun `unsettled restoration cannot clear a prior failure`() {
        val failed = DraftEditorContent(body = "retry this edit", subject = null)

        assertEquals(
            false,
            shouldClearRecoveredFailure(
                failedContent = failed,
                active = settledDraft(
                    body = "retry this edit",
                    draftId = DraftId(15L),
                    revision = DraftRevision(16L),
                ).copy(saving = true),
                externalPrefillResolution = ExternalPrefillResolution.NOT_APPLICABLE,
            ),
        )
    }

    private fun settledDraft(
        body: String?,
        draftId: DraftId?,
        revision: DraftRevision?,
    ): DraftWriteStatus.Active = DraftWriteStatus.Active(
        latest = DraftEditorContent(body = body, subject = null),
        acknowledgedDraftId = draftId,
        acknowledgedRevision = revision,
        saving = false,
        frozenForSend = false,
        initialized = true,
    )
}
