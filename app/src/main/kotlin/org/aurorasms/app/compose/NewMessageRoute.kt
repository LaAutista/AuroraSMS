// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.aurorasms.app.AppContainer
import org.aurorasms.app.AuroraSmsRootServices
import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.DraftWriteFailure
import org.aurorasms.app.drafts.DraftWriteStatus
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.drafts.SerializedDraftWriterLease
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.telephony.ContactDiscovery
import org.aurorasms.core.telephony.ContactDiscoveryResult
import org.aurorasms.core.telephony.DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.feature.conversations.MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS
import org.aurorasms.feature.conversations.NewMessageContactDiscoveryUiState
import org.aurorasms.feature.conversations.NewMessageContactResultUiItem
import org.aurorasms.feature.conversations.NewMessageDraftStatus
import org.aurorasms.feature.conversations.NewMessageRecipientError
import org.aurorasms.feature.conversations.NewMessageRecipientUiItem
import org.aurorasms.feature.conversations.NewMessageScreen

/** Internal New Chat route backed by the root's injected, testable draft services. */
@Composable
internal fun NewMessageRoute(
    services: AuroraSmsRootServices,
    draftWriterRouteOwner: String,
    contactsPermissionGranted: Boolean,
    onRequestContactsPermission: () -> Unit,
    onBack: () -> Unit,
) {
    NewMessageRouteContent(
        writerOwnerKey = services,
        acquireDraftWriter = services::acquireDraftWriter,
        contactDiscovery = services.contactDiscovery,
        stableDraftWriterRouteOwner = draftWriterRouteOwner,
        initialRequest = null,
        invalidExternalRequest = false,
        contactsPermissionGranted = contactsPermissionGranted,
        onRequestContactsPermission = onRequestContactsPermission,
        onBack = onBack,
    )
}

/** Shared external-compose route. Opening it is review-only and can never send. */
@Composable
internal fun NewMessageRoute(
    container: AppContainer,
    initialRequest: ComposeRequest?,
    invalidExternalRequest: Boolean,
    contactsPermissionGranted: Boolean,
    onRequestContactsPermission: () -> Unit,
    onBack: () -> Unit,
) {
    NewMessageRouteContent(
        writerOwnerKey = container,
        acquireDraftWriter = container::acquireDraftWriter,
        contactDiscovery = container.contactDiscovery,
        stableDraftWriterRouteOwner = null,
        initialRequest = initialRequest,
        invalidExternalRequest = invalidExternalRequest,
        contactsPermissionGranted = contactsPermissionGranted,
        onRequestContactsPermission = onRequestContactsPermission,
        onBack = onBack,
    )
}

@Composable
private fun NewMessageRouteContent(
    writerOwnerKey: Any,
    acquireDraftWriter: (DraftIdentity, DraftRestorationToken?, String?) -> SerializedDraftWriterLease,
    contactDiscovery: ContactDiscovery,
    stableDraftWriterRouteOwner: String?,
    initialRequest: ComposeRequest?,
    invalidExternalRequest: Boolean,
    contactsPermissionGranted: Boolean,
    onRequestContactsPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val initialRecipientValues = remember(initialRequest) {
        initialRequest?.recipients?.addresses?.map(ParticipantAddress::value).orEmpty()
    }
    var savedRecipients by rememberSaveable(stateSaver = SAVED_RECIPIENT_SELECTION_SAVER) {
        mutableStateOf(SavedRecipientSelection(initialRecipientValues))
    }
    var recipientInput by rememberSaveable { mutableStateOf("") }
    var recipientErrorName by rememberSaveable {
        mutableStateOf(
            NewMessageRecipientError.INVALID.name.takeIf { invalidExternalRequest },
        )
    }
    var ephemeralBody by rememberSaveable {
        mutableStateOf(initialRequest?.body.orEmpty())
    }
    var savedDraftRestoration by rememberSaveable(
        stateSaver = NEW_MESSAGE_DRAFT_RESTORATION_SAVER,
    ) {
        mutableStateOf(
            NewMessageSavedDraftRestoration(),
        )
    }
    var externalPrefillResolutionName by rememberSaveable {
        mutableStateOf(
            if (initialRequest?.body.isNullOrEmpty()) {
                ExternalPrefillResolution.NOT_APPLICABLE.name
            } else {
                ExternalPrefillResolution.AWAITING_DURABLE_DRAFT.name
            },
        )
    }
    var contactDiscoveryOpen by remember { mutableStateOf(false) }
    var contactDiscoveryQuery by remember { mutableStateOf("") }
    var contactDiscoveryRetryGeneration by remember { mutableStateOf(0L) }
    var contactDiscoveryPermissionRecoveryRequired by remember { mutableStateOf(false) }
    var transientContactLabels by remember {
        mutableStateOf<Map<ParticipantAddress, String>>(emptyMap())
    }
    var contactDiscoveryLoad by remember {
        mutableStateOf<NewMessageContactDiscoveryLoad>(NewMessageContactDiscoveryLoad.Idle)
    }
    val draftWriterRouteOwner = rememberSaveable {
        stableDraftWriterRouteOwner ?: UUID.randomUUID().toString()
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                contactDiscoveryOpen = false
                contactDiscoveryQuery = ""
                contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                contactDiscoveryPermissionRecoveryRequired = false
                transientContactLabels = emptyMap()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(contactsPermissionGranted) {
        if (!contactsPermissionGranted) {
            contactDiscoveryOpen = false
            contactDiscoveryQuery = ""
            contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
            contactDiscoveryPermissionRecoveryRequired = false
            transientContactLabels = emptyMap()
        }
    }

    val committedRecipients = remember(savedRecipients) {
        if (savedRecipients.values.isEmpty()) {
            null
        } else {
            when (val parsed = RecipientSet.parse(savedRecipients.values)) {
                is RecipientSet.CreationResult.Valid -> parsed.recipients
                is RecipientSet.CreationResult.Rejected -> null
            }
        }
    }
    val identity = remember(committedRecipients) {
        committedRecipients?.let { recipients ->
            DraftIdentity.ParticipantSet(
                DraftParticipantSetKey.fromParticipants(recipients.addresses),
            )
        }
    }
    val writerLease = remember(writerOwnerKey, identity) {
        identity?.let { draftIdentity ->
            acquireDraftWriter(
                draftIdentity,
                savedDraftRestoration.token,
                draftWriterRouteOwner,
            )
        }
    }
    DisposableEffect(writerLease) {
        onDispose { writerLease?.close() }
    }
    val writer = writerLease?.writer
    val draftStatus = observeDraftStatus(writer)
    val effectiveExternalPrefillResolution = resolveExternalPrefill(
        current = ExternalPrefillResolution.valueOf(externalPrefillResolutionName),
        prefill = initialRequest?.body.orEmpty(),
        draftStatus = draftStatus,
    )

    LaunchedEffect(writer, draftStatus, effectiveExternalPrefillResolution) {
        when (val status = draftStatus) {
            is DraftWriteStatus.Active -> {
                val failedContent = savedDraftRestoration.failedContent
                if (
                    failedContent == null ||
                    shouldClearRecoveredFailure(
                        failedContent = failedContent,
                        active = status,
                        externalPrefillResolution = effectiveExternalPrefillResolution,
                    )
                ) {
                    savedDraftRestoration = NewMessageSavedDraftRestoration(
                        token = status.toRestorationToken(),
                    )
                }
                if (effectiveExternalPrefillResolution.name != externalPrefillResolutionName) {
                    externalPrefillResolutionName = effectiveExternalPrefillResolution.name
                }
            }
            is DraftWriteStatus.Failed -> {
                // Failure text is display-only. Never pair it with a newer durable
                // revision and replay it after a participant conflict.
                val displayContent = resolveFailedDisplayContent(
                    externalPrefillResolution = effectiveExternalPrefillResolution,
                    externalPrefill = initialRequest?.body.orEmpty(),
                    latest = status.latest,
                )
                savedDraftRestoration = savedDraftRestoration.copy(
                    token = if (status.failure == DraftWriteFailure.STORAGE) {
                        DraftRestorationToken(
                            content = status.latest,
                            expectedDraftId = status.acknowledgedDraftId,
                            expectedRevision = status.acknowledgedRevision,
                        )
                    } else {
                        savedDraftRestoration.token
                    },
                    failedContent = displayContent,
                )
            }
            DraftWriteStatus.Loading,
            null,
            -> Unit
        }
    }

    val savedFailureContent = savedDraftRestoration.failedContent
    val visibleBody = if (savedFailureContent != null) {
        savedFailureContent.body.orEmpty()
    } else if (
        effectiveExternalPrefillResolution ==
        ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL ||
        (
            effectiveExternalPrefillResolution ==
                ExternalPrefillResolution.AWAITING_DURABLE_DRAFT &&
                draftStatus is DraftWriteStatus.Failed
            )
    ) {
        ephemeralBody
    } else {
        when (val status = draftStatus) {
            null -> ephemeralBody
            DraftWriteStatus.Loading -> ""
            is DraftWriteStatus.Active -> status.latest.body.orEmpty()
            is DraftWriteStatus.Failed -> status.latest.body.orEmpty()
        }
    }
    val preservedSubject = savedFailureContent?.subject ?: when (val status = draftStatus) {
        is DraftWriteStatus.Active -> status.latest.subject
        is DraftWriteStatus.Failed -> status.latest.subject
        DraftWriteStatus.Loading,
        null,
        -> savedDraftRestoration.token?.content?.subject
    }
    val currentRecipients = committedRecipients?.addresses.orEmpty()
    val recipientEditingEnabled = savedFailureContent == null && (writer == null || (
        effectiveExternalPrefillResolution !=
            ExternalPrefillResolution.AWAITING_DURABLE_DRAFT &&
            visibleBody.isEmpty() &&
            draftStatus is DraftWriteStatus.Active &&
            draftStatus.initialized &&
            !draftStatus.saving
        ))
    val bodyEditingEnabled = effectiveExternalPrefillResolution !=
        ExternalPrefillResolution.AWAITING_DURABLE_DRAFT &&
        savedFailureContent == null

    LaunchedEffect(
        contactDiscovery,
        contactDiscoveryOpen,
        contactDiscoveryQuery,
        contactsPermissionGranted,
        contactDiscoveryPermissionRecoveryRequired,
        contactDiscoveryRetryGeneration,
    ) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when {
                !contactDiscoveryOpen -> {
                    contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                }
                !contactsPermissionGranted || contactDiscoveryPermissionRecoveryRequired -> {
                    contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Resolved(
                        query = contactDiscoveryQuery,
                        result = ContactDiscoveryResult.PermissionDenied,
                    )
                }
                contactDiscoveryQuery.isBlank() -> {
                    contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                }
                else -> {
                    val requestedQuery = contactDiscoveryQuery
                    contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Loading(requestedQuery)
                    delay(CONTACT_DISCOVERY_DEBOUNCE_MILLIS)
                    val result = try {
                        contactDiscovery.discover(
                            query = requestedQuery,
                            resultLimit = DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        ContactDiscoveryResult.Unavailable
                    }
                    if (result == ContactDiscoveryResult.PermissionDenied) {
                        contactDiscoveryOpen = false
                        contactDiscoveryQuery = ""
                        contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                        contactDiscoveryPermissionRecoveryRequired = true
                        transientContactLabels = emptyMap()
                    } else {
                        contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Resolved(
                            query = requestedQuery,
                            result = result,
                        )
                    }
                }
            }
        }
    }

    val contactDiscoveryUiState = resolveNewMessageContactDiscoveryUiState(
        open = contactDiscoveryOpen,
        query = contactDiscoveryQuery,
        contactsPermissionGranted = contactsPermissionGranted,
        load = contactDiscoveryLoad,
        committedRecipients = currentRecipients.toSet(),
    )

    fun transitionRecipients(recipients: RecipientSet): Boolean {
        if (!recipientEditingEnabled) return false
        val nextValues = recipients.addresses.map(ParticipantAddress::value)
        if (nextValues == savedRecipients.values) {
            recipientErrorName = NewMessageRecipientError.DUPLICATE.name
            return false
        }
        ephemeralBody = visibleBody
        savedDraftRestoration = NewMessageSavedDraftRestoration(
            token = visibleBody.takeIf(String::isNotEmpty)?.let(::baseFreeRestorationToken),
        )
        savedRecipients = SavedRecipientSelection(nextValues)
        transientContactLabels = transientContactLabels.filterKeys { it in recipients.addresses }
        recipientInput = ""
        recipientErrorName = null
        externalPrefillResolutionName = ExternalPrefillResolution.NOT_APPLICABLE.name
        return true
    }

    NewMessageScreen(
        recipientInput = recipientInput,
        committedRecipients = currentRecipients.map { address ->
            NewMessageRecipientUiItem(
                address = address,
                displayLabel = transientContactLabels[address] ?: address.value,
            )
        },
        body = visibleBody,
        onBack = onBack,
        onRecipientInputChanged = { value ->
            if (recipientEditingEnabled) {
                recipientInput = value
                recipientErrorName = null
            }
        },
        onCommitRecipients = { raw ->
            if (recipientEditingEnabled) {
                when (val parsed = parseComposeRecipientList(raw, currentRecipients)) {
                    is RecipientSet.CreationResult.Valid -> transitionRecipients(parsed.recipients)
                    is RecipientSet.CreationResult.Rejected -> {
                        recipientErrorName = parsed.reason.toNewMessageError().name
                    }
                }
            }
        },
        onRemoveRecipient = { removed ->
            if (recipientEditingEnabled) {
                val remaining = currentRecipients.filterNot { it == removed }
                ephemeralBody = visibleBody
                savedDraftRestoration = NewMessageSavedDraftRestoration()
                savedRecipients = SavedRecipientSelection(remaining.map(ParticipantAddress::value))
                transientContactLabels = transientContactLabels.filterKeys { it != removed }
                recipientErrorName = null
                externalPrefillResolutionName = ExternalPrefillResolution.NOT_APPLICABLE.name
            }
        },
        onBodyChanged = { body ->
            if (bodyEditingEnabled) {
                if (writer == null) {
                    ephemeralBody = body
                } else {
                    val content = DraftEditorContent(
                        body = body.takeIf(String::isNotEmpty),
                        subject = preservedSubject,
                    )
                    val active = writer.status.value as? DraftWriteStatus.Active
                    val acceptedToken = if (
                        body.isEmpty() &&
                        active?.initialized == true &&
                        !active.saving &&
                        active.acknowledgedDraftId == null
                    ) {
                        ephemeralBody = ""
                        active.toRestorationToken(content)
                    } else {
                        writer.submitWithRestorationToken(content)
                    }
                    if (acceptedToken != null) {
                        externalPrefillResolutionName = ExternalPrefillResolution.NOT_APPLICABLE.name
                        savedDraftRestoration = NewMessageSavedDraftRestoration(
                            acceptedToken,
                        )
                    }
                }
            }
        },
        contactDiscoveryState = contactDiscoveryUiState,
        onOpenContactDiscovery = {
            if (recipientEditingEnabled) {
                contactDiscoveryOpen = true
            }
        },
        onCloseContactDiscovery = {
            contactDiscoveryOpen = false
            contactDiscoveryQuery = ""
            contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
        },
        onContactDiscoveryQueryChanged = { query ->
            if (
                recipientEditingEnabled &&
                query.length <= MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS &&
                query.none(Char::isISOControl)
            ) {
                contactDiscoveryQuery = query
            }
        },
        onRetryContactDiscovery = {
            when (
                newMessageContactDiscoveryRetryAction(
                    contactsPermissionGranted = contactsPermissionGranted,
                    load = contactDiscoveryLoad,
                )
            ) {
                NewMessageContactDiscoveryRetryAction.RETRY_QUERY -> {
                    contactDiscoveryRetryGeneration += 1L
                }
                NewMessageContactDiscoveryRetryAction.RECOVER_PERMISSION_AND_RETRY -> {
                    // Refreshing the Activity snapshot may close/clear this panel if the grant was
                    // actually revoked. If Android still reports a grant, clearing this local fence
                    // admits exactly one explicit retry and cannot spin automatically.
                    contactDiscoveryPermissionRecoveryRequired = false
                    contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                    contactDiscoveryRetryGeneration += 1L
                    onRequestContactsPermission()
                }
            }
        },
        onSelectDiscoveredContact = { selected ->
            if (recipientEditingEnabled) {
                when (
                    val parsed = parseComposeRecipientList(
                        raw = selected.value,
                        existing = currentRecipients,
                    )
                ) {
                    is RecipientSet.CreationResult.Valid -> {
                        if (transitionRecipients(parsed.recipients)) {
                            val discoveredLabel =
                                (contactDiscoveryLoad as? NewMessageContactDiscoveryLoad.Resolved)
                                    ?.result
                                    ?.let { it as? ContactDiscoveryResult.Available }
                                    ?.contacts
                                    ?.firstOrNull { it.address == selected }
                                    ?.displayNameOrAddress
                                    ?.takeIf {
                                        it.length <= MAXIMUM_TRANSIENT_CONTACT_LABEL_CHARACTERS
                                    }
                            if (discoveredLabel != null) {
                                transientContactLabels = transientContactLabels +
                                    (selected to discoveredLabel)
                            }
                            contactDiscoveryOpen = false
                            contactDiscoveryQuery = ""
                            contactDiscoveryLoad = NewMessageContactDiscoveryLoad.Idle
                            contactDiscoveryPermissionRecoveryRequired = false
                        }
                    }
                    is RecipientSet.CreationResult.Rejected -> {
                        recipientErrorName = parsed.reason.toNewMessageError().name
                    }
                }
            }
        },
        recipientError = recipientErrorName?.let(NewMessageRecipientError::valueOf),
        draftStatus = when {
            savedFailureContent != null || draftStatus is DraftWriteStatus.Failed ->
                NewMessageDraftStatus.FAILED
            effectiveExternalPrefillResolution ==
                ExternalPrefillResolution.AWAITING_DURABLE_DRAFT ->
                NewMessageDraftStatus.LOADING
            effectiveExternalPrefillResolution ==
                ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL ->
                NewMessageDraftStatus.REVIEW_ONLY
            else -> draftStatus.toNewMessageDraftStatus(identity != null)
        },
        recipientEditingEnabled = recipientEditingEnabled,
        bodyEditingEnabled = bodyEditingEnabled,
        externalPrefillConflict = effectiveExternalPrefillResolution ==
            ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT,
        explicitMmsRequested = initialRequest?.requestedTransport == MessageTransportKind.MMS,
    )
}

internal sealed interface NewMessageContactDiscoveryLoad {
    data object Idle : NewMessageContactDiscoveryLoad

    data class Loading(val query: String) : NewMessageContactDiscoveryLoad {
        override fun toString(): String = "NewMessageContactDiscoveryLoad.Loading(REDACTED)"
    }

    data class Resolved(
        val query: String,
        val result: ContactDiscoveryResult,
    ) : NewMessageContactDiscoveryLoad {
        override fun toString(): String = "NewMessageContactDiscoveryLoad.Resolved(REDACTED)"
    }
}

internal enum class NewMessageContactDiscoveryRetryAction {
    RETRY_QUERY,
    RECOVER_PERMISSION_AND_RETRY,
}

internal fun newMessageContactDiscoveryRetryAction(
    contactsPermissionGranted: Boolean,
    load: NewMessageContactDiscoveryLoad,
): NewMessageContactDiscoveryRetryAction =
    if (
        !contactsPermissionGranted ||
        (
            load is NewMessageContactDiscoveryLoad.Resolved &&
                load.result == ContactDiscoveryResult.PermissionDenied
            )
    ) {
        NewMessageContactDiscoveryRetryAction.RECOVER_PERMISSION_AND_RETRY
    } else {
        NewMessageContactDiscoveryRetryAction.RETRY_QUERY
    }

internal fun resolveNewMessageContactDiscoveryUiState(
    open: Boolean,
    query: String,
    contactsPermissionGranted: Boolean,
    load: NewMessageContactDiscoveryLoad,
    committedRecipients: Set<ParticipantAddress>,
): NewMessageContactDiscoveryUiState {
    if (!open) return NewMessageContactDiscoveryUiState.Closed
    if (!contactsPermissionGranted) return NewMessageContactDiscoveryUiState.Unavailable(query)
    if (
        load is NewMessageContactDiscoveryLoad.Resolved &&
        load.result == ContactDiscoveryResult.PermissionDenied
    ) {
        return NewMessageContactDiscoveryUiState.Unavailable(query)
    }
    if (query.isBlank()) return NewMessageContactDiscoveryUiState.Empty(query)

    return when (load) {
        NewMessageContactDiscoveryLoad.Idle -> NewMessageContactDiscoveryUiState.Loading(query)
        is NewMessageContactDiscoveryLoad.Loading ->
            NewMessageContactDiscoveryUiState.Loading(query)
        is NewMessageContactDiscoveryLoad.Resolved -> {
            if (load.query != query) return NewMessageContactDiscoveryUiState.Loading(query)
            when (val result = load.result) {
                is ContactDiscoveryResult.Available -> {
                    val items = result.contacts
                        .asSequence()
                        .filterNot { contact ->
                            isCanonicalRecipientAlreadyCommitted(
                                candidate = contact.address,
                                committedRecipients = committedRecipients,
                            )
                        }
                        .map { contact ->
                            NewMessageContactResultUiItem(
                                address = contact.address,
                                displayLabel = contact.displayNameOrAddress,
                            )
                        }
                        .toList()
                    if (items.isEmpty()) {
                        NewMessageContactDiscoveryUiState.Empty(
                            query = query,
                            truncated = result.truncated,
                        )
                    } else {
                        NewMessageContactDiscoveryUiState.Results(
                            query = query,
                            items = items,
                            truncated = result.truncated,
                        )
                    }
                }
                ContactDiscoveryResult.PermissionDenied ->
                    NewMessageContactDiscoveryUiState.Unavailable(query)
                ContactDiscoveryResult.InvalidRequest,
                ContactDiscoveryResult.Unavailable,
                -> NewMessageContactDiscoveryUiState.Error(query)
            }
        }
    }
}

private fun isCanonicalRecipientAlreadyCommitted(
    candidate: ParticipantAddress,
    committedRecipients: Set<ParticipantAddress>,
): Boolean {
    if (committedRecipients.isEmpty()) return false
    val combined = RecipientSet.from(committedRecipients + candidate)
        as? RecipientSet.CreationResult.Valid
        ?: return false
    return combined.recipients.size == committedRecipients.size
}

@Composable
private fun observeDraftStatus(writer: SerializedDraftWriter?): DraftWriteStatus? {
    if (writer == null) return null
    val status by writer.status.collectAsStateWithLifecycle()
    return status
}

internal fun DraftWriteStatus?.toNewMessageDraftStatus(
    hasIdentity: Boolean,
): NewMessageDraftStatus = when {
    !hasIdentity -> NewMessageDraftStatus.WAITING_FOR_RECIPIENT
    this == null || this is DraftWriteStatus.Loading -> NewMessageDraftStatus.LOADING
    this is DraftWriteStatus.Failed -> NewMessageDraftStatus.FAILED
    this is DraftWriteStatus.Active && !initialized -> NewMessageDraftStatus.LOADING
    this is DraftWriteStatus.Active && saving -> NewMessageDraftStatus.SAVING
    this is DraftWriteStatus.Active && acknowledgedDraftId == null -> NewMessageDraftStatus.READY
    else -> NewMessageDraftStatus.SAVED
}

private fun RecipientSet.RejectionReason.toNewMessageError(): NewMessageRecipientError = when (this) {
    RecipientSet.RejectionReason.EMPTY,
    RecipientSet.RejectionReason.BLANK,
    -> NewMessageRecipientError.EMPTY
    RecipientSet.RejectionReason.TOO_MANY -> NewMessageRecipientError.TOO_MANY
    RecipientSet.RejectionReason.TOO_LONG,
    RecipientSet.RejectionReason.CONTROL_CHARACTER,
    RecipientSet.RejectionReason.INVALID_ADDRESS,
    -> NewMessageRecipientError.INVALID
}

private fun baseFreeRestorationToken(body: String): DraftRestorationToken =
    DraftRestorationToken(
        content = DraftEditorContent(body = body, subject = null),
        expectedDraftId = null,
        expectedRevision = null,
    )

private fun DraftWriteStatus.Active.toRestorationToken(
    content: DraftEditorContent = latest,
): DraftRestorationToken =
    DraftRestorationToken(
        content = content,
        expectedDraftId = acknowledgedDraftId,
        expectedRevision = acknowledgedRevision,
    )

private data class SavedRecipientSelection(val values: List<String>) {
    init {
        require(values.size <= RecipientSet.MAX_RECIPIENTS) {
            "A saved recipient selection is too large"
        }
    }

    override fun toString(): String = "SavedRecipientSelection(size=${values.size}, REDACTED)"
}

private val SAVED_RECIPIENT_SELECTION_SAVER: Saver<SavedRecipientSelection, Bundle> = Saver(
    save = { selection ->
        Bundle().apply {
            putStringArrayList(SAVED_RECIPIENTS_KEY, ArrayList(selection.values))
        }
    },
    restore = { bundle ->
        val values = bundle.getStringArrayList(SAVED_RECIPIENTS_KEY).orEmpty()
        val valid = values.isEmpty() ||
            RecipientSet.parse(values) is RecipientSet.CreationResult.Valid
        SavedRecipientSelection(values.takeIf { valid }.orEmpty())
    },
)

private data class NewMessageSavedDraftRestoration(
    val token: DraftRestorationToken? = null,
    val failedContent: DraftEditorContent? = null,
) {
    override fun toString(): String =
        "NewMessageSavedDraftRestoration(hasToken=${token != null}, " +
            "hasFailure=${failedContent != null}, REDACTED)"
}

private val NEW_MESSAGE_DRAFT_RESTORATION_SAVER:
    Saver<NewMessageSavedDraftRestoration, Bundle> = Saver(
        save = { state ->
            Bundle().apply {
                val token = state.token
                putBoolean(SAVED_DRAFT_TOKEN_PRESENT_KEY, token != null)
                if (token != null) {
                    putString(SAVED_DRAFT_BODY_KEY, token.content.body)
                    putString(SAVED_DRAFT_SUBJECT_KEY, token.content.subject)
                    val hasBase = token.expectedDraftId != null
                    putBoolean(SAVED_DRAFT_BASE_PRESENT_KEY, hasBase)
                    if (hasBase) {
                        putLong(SAVED_DRAFT_ID_KEY, checkNotNull(token.expectedDraftId).value)
                        putLong(
                            SAVED_DRAFT_REVISION_KEY,
                            checkNotNull(token.expectedRevision).updatedTimestampMillis,
                        )
                    }
                }
                val failedContent = state.failedContent
                putBoolean(SAVED_DRAFT_FAILURE_PRESENT_KEY, failedContent != null)
                if (failedContent != null) {
                    putString(SAVED_DRAFT_FAILURE_BODY_KEY, failedContent.body)
                    putString(SAVED_DRAFT_FAILURE_SUBJECT_KEY, failedContent.subject)
                }
            }
        },
        restore = { bundle ->
            runCatching {
                val token = if (bundle.getBoolean(SAVED_DRAFT_TOKEN_PRESENT_KEY)) {
                    val hasBase = bundle.getBoolean(SAVED_DRAFT_BASE_PRESENT_KEY)
                    DraftRestorationToken(
                        content = DraftEditorContent(
                            body = bundle.getString(SAVED_DRAFT_BODY_KEY),
                            subject = bundle.getString(SAVED_DRAFT_SUBJECT_KEY),
                        ),
                        expectedDraftId = if (hasBase) {
                            DraftId(bundle.getLong(SAVED_DRAFT_ID_KEY))
                        } else {
                            null
                        },
                        expectedRevision = if (hasBase) {
                            DraftRevision(bundle.getLong(SAVED_DRAFT_REVISION_KEY))
                        } else {
                            null
                        },
                    )
                } else {
                    null
                }
                val failedContent = if (bundle.getBoolean(SAVED_DRAFT_FAILURE_PRESENT_KEY)) {
                    DraftEditorContent(
                        body = bundle.getString(SAVED_DRAFT_FAILURE_BODY_KEY),
                        subject = bundle.getString(SAVED_DRAFT_FAILURE_SUBJECT_KEY),
                    )
                } else {
                    null
                }
                NewMessageSavedDraftRestoration(token, failedContent)
            }.getOrDefault(NewMessageSavedDraftRestoration())
        },
    )

internal enum class ExternalPrefillResolution {
    NOT_APPLICABLE,
    AWAITING_DURABLE_DRAFT,
    REVIEWING_UNPERSISTED_PREFILL,
    APPLIED_OR_EQUIVALENT,
    PRESERVED_EXISTING_DRAFT,
}

internal fun resolveExternalPrefill(
    current: ExternalPrefillResolution,
    prefill: String,
    draftStatus: DraftWriteStatus?,
): ExternalPrefillResolution {
    if (
        current != ExternalPrefillResolution.AWAITING_DURABLE_DRAFT &&
        current != ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL
    ) {
        return current
    }
    val active = draftStatus as? DraftWriteStatus.Active
        ?: return ExternalPrefillResolution.AWAITING_DURABLE_DRAFT
    if (!active.initialized || active.saving) {
        return ExternalPrefillResolution.AWAITING_DURABLE_DRAFT
    }
    return when {
        active.acknowledgedDraftId == null ->
            ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL
        active.latest.body.orEmpty() != prefill ->
            ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT
        else -> ExternalPrefillResolution.APPLIED_OR_EQUIVALENT
    }
}

internal fun resolveFailedDisplayContent(
    externalPrefillResolution: ExternalPrefillResolution,
    externalPrefill: String,
    latest: DraftEditorContent,
): DraftEditorContent = if (
    externalPrefill.isNotEmpty() &&
    externalPrefillResolution in setOf(
        ExternalPrefillResolution.AWAITING_DURABLE_DRAFT,
        ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
    )
) {
    latest.copy(body = externalPrefill)
} else {
    latest
}

internal fun shouldClearRecoveredFailure(
    failedContent: DraftEditorContent,
    active: DraftWriteStatus.Active,
    externalPrefillResolution: ExternalPrefillResolution,
): Boolean {
    if (!active.initialized || active.saving) return false
    if (active.latest == failedContent) return true
    return externalPrefillResolution in setOf(
        ExternalPrefillResolution.REVIEWING_UNPERSISTED_PREFILL,
        ExternalPrefillResolution.APPLIED_OR_EQUIVALENT,
        ExternalPrefillResolution.PRESERVED_EXISTING_DRAFT,
    )
}

private const val SAVED_RECIPIENTS_KEY = "new_message_recipients"
private const val SAVED_DRAFT_TOKEN_PRESENT_KEY = "new_message_draft_token_present"
private const val SAVED_DRAFT_BODY_KEY = "new_message_draft_body"
private const val SAVED_DRAFT_SUBJECT_KEY = "new_message_draft_subject"
private const val SAVED_DRAFT_BASE_PRESENT_KEY = "new_message_draft_base_present"
private const val SAVED_DRAFT_ID_KEY = "new_message_draft_id"
private const val SAVED_DRAFT_REVISION_KEY = "new_message_draft_revision"
private const val SAVED_DRAFT_FAILURE_PRESENT_KEY = "new_message_draft_failure_present"
private const val SAVED_DRAFT_FAILURE_BODY_KEY = "new_message_draft_failure_body"
private const val SAVED_DRAFT_FAILURE_SUBJECT_KEY = "new_message_draft_failure_subject"
private const val CONTACT_DISCOVERY_DEBOUNCE_MILLIS = 250L
private const val MAXIMUM_TRANSIENT_CONTACT_LABEL_CHARACTERS = 320
