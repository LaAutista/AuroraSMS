// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.DraftWriteStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.telephony.ContactDiscoveryResult
import org.aurorasms.core.telephony.DiscoveredContact
import org.aurorasms.feature.conversations.NewMessageContactDiscoveryUiState
import org.aurorasms.feature.conversations.NewMessageDraftStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NewMessageRouteStateTest {
    @Test
    fun `contact discovery stays closed until explicitly opened and denial preserves fallback`() {
        assertEquals(
            NewMessageContactDiscoveryUiState.Closed,
            resolveNewMessageContactDiscoveryUiState(
                open = false,
                query = "synthetic",
                contactsPermissionGranted = true,
                load = NewMessageContactDiscoveryLoad.Loading("synthetic"),
                committedRecipients = emptySet(),
            ),
        )
        assertEquals(
            NewMessageContactDiscoveryUiState.Unavailable(""),
            resolveNewMessageContactDiscoveryUiState(
                open = true,
                query = "",
                contactsPermissionGranted = false,
                load = NewMessageContactDiscoveryLoad.Idle,
                committedRecipients = emptySet(),
            ),
        )
    }

    @Test
    fun `resolved contacts map bounded labels and preserve truncation`() {
        val address = ParticipantAddress("+12025550101")
        val state = resolveNewMessageContactDiscoveryUiState(
            open = true,
            query = "Syn",
            contactsPermissionGranted = true,
            load = NewMessageContactDiscoveryLoad.Resolved(
                query = "Syn",
                result = ContactDiscoveryResult.Available(
                    contacts = listOf(
                        DiscoveredContact(
                            address = address,
                            displayName = "Synthetic Contact",
                            photoUri = null,
                        ),
                    ),
                    truncated = true,
                ),
            ),
            committedRecipients = emptySet(),
        )

        val results = state as NewMessageContactDiscoveryUiState.Results
        assertEquals(address, results.items.single().address)
        assertEquals("Synthetic Contact", results.items.single().displayLabel)
        assertEquals(true, results.truncated)
    }

    @Test
    fun `canonically equivalent selected result is filtered and remains truthfully truncated`() {
        val selected = ParticipantAddress("+12025550102")
        val committed = ParticipantAddress("+1 (202) 555-0102")
        val state = resolveNewMessageContactDiscoveryUiState(
            open = true,
            query = "selected",
            contactsPermissionGranted = true,
            load = NewMessageContactDiscoveryLoad.Resolved(
                query = "selected",
                result = ContactDiscoveryResult.Available(
                    contacts = listOf(DiscoveredContact(selected, "Selected", null)),
                    truncated = true,
                ),
            ),
            committedRecipients = setOf(committed),
        )

        assertEquals(
            NewMessageContactDiscoveryUiState.Empty(query = "selected", truncated = true),
            state,
        )
    }

    @Test
    fun `provider permission loss overrides a stale granted snapshot`() {
        assertEquals(
            NewMessageContactDiscoveryUiState.Unavailable(""),
            resolveNewMessageContactDiscoveryUiState(
                open = true,
                query = "",
                contactsPermissionGranted = true,
                load = NewMessageContactDiscoveryLoad.Resolved(
                    query = "",
                    result = ContactDiscoveryResult.PermissionDenied,
                ),
                committedRecipients = emptySet(),
            ),
        )
    }

    @Test
    fun `stale granted provider denial requires one explicit recovery retry`() {
        val denied = NewMessageContactDiscoveryLoad.Resolved(
            query = "synthetic",
            result = ContactDiscoveryResult.PermissionDenied,
        )

        assertEquals(
            NewMessageContactDiscoveryRetryAction.RECOVER_PERMISSION_AND_RETRY,
            newMessageContactDiscoveryRetryAction(
                contactsPermissionGranted = true,
                load = denied,
            ),
        )
        assertEquals(
            NewMessageContactDiscoveryRetryAction.RETRY_QUERY,
            newMessageContactDiscoveryRetryAction(
                contactsPermissionGranted = true,
                load = NewMessageContactDiscoveryLoad.Resolved(
                    query = "synthetic",
                    result = ContactDiscoveryResult.Unavailable,
                ),
            ),
        )
    }

    @Test
    fun `obsolete contact result cannot publish for a newer query`() {
        assertEquals(
            NewMessageContactDiscoveryUiState.Loading("new query"),
            resolveNewMessageContactDiscoveryUiState(
                open = true,
                query = "new query",
                contactsPermissionGranted = true,
                load = NewMessageContactDiscoveryLoad.Resolved(
                    query = "old query",
                    result = ContactDiscoveryResult.Available(emptyList(), truncated = false),
                ),
                committedRecipients = emptySet(),
            ),
        )
    }

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
