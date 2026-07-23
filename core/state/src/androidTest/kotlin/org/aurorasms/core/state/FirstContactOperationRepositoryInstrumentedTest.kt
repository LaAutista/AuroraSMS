// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomComposerSmsOperationRepository
import org.aurorasms.core.state.storage.RoomDraftAttachmentRepository
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.RoomFirstContactOperationRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstContactOperationRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun reserveUsesExactDraftRevisionAndPersistsOnlySemanticAndAttachmentEvidence() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+1 (555) 000-0100"))
            val attachment = attachment(1, 2, 3)
            val draft = createDraft(database, participants, body = "Synthetic", attachments = listOf(attachment))
            val repository = RoomFirstContactOperationRepository(database)

            val operation = repository.reserve(
                request(draft, participants, MessageTransportKind.MMS),
            ).requireSuccess()

            assertEquals(operation, repository.readByDraft(draft.id).requireSuccess())
            val sqlite = database.openHelper.writableDatabase
            val columns = sqlite.query("PRAGMA table_info(`first_contact_operations`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
            }
            assertFalse(columns.contains("body"))
            assertFalse(columns.contains("subject"))
            assertFalse(columns.contains("recipient"))
            sqlite.query(
                "SELECT participant_set_key,attachment_set_evidence " +
                    "FROM first_contact_operations WHERE first_contact_id=?",
                arrayOf(operation.id.value),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNotEquals(participants.single().value, cursor.getString(0))
                assertEquals(operation.participantSetKey.toStorageValue(), cursor.getString(0))
                assertEquals(operation.attachmentSetEvidence.toStorageValue(), cursor.getString(1))
            }

            val equivalentParticipants = listOf(ParticipantAddress("+15550000100"))
            val equivalentDraft = createDraft(
                database,
                equivalentParticipants,
                body = "Second",
            )
            assertEquals(
                FirstContactOperationResult.Conflict,
                repository.reserve(
                    request(equivalentDraft, equivalentParticipants, MessageTransportKind.SMS),
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun resolutionTransitionsBindOnceAndOnlyPreEntryProofCanReleaseKnownUnsent() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000101"))
            val draft = createDraft(database, participants, body = "Synthetic")
            val repository = RoomFirstContactOperationRepository(database)
            val reserved = repository.reserve(
                request(draft, participants, MessageTransportKind.SMS),
            ).requireSuccess()

            assertEquals(
                FirstContactOperationResult.PhaseMismatch,
                repository.bindThread(
                    reserved.id,
                    reserved.revision,
                    ProviderThreadId(91L),
                    2_001L,
                ),
            )
            val started = repository.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess()
            assertEquals(participants, started.participants)
            assertEquals(
                FirstContactOperationResult.PhaseMismatch,
                repository.markKnownUnsent(
                    started.operation.id,
                    started.operation.revision,
                    FirstContactKnownUnsentProof.PROVIDER_AUTHORITY_NOT_ENTERED,
                    2_002L,
                ),
            )
            val bound = repository.bindThread(
                started.operation.id,
                started.operation.revision,
                ProviderThreadId(91L),
                2_003L,
            ).requireSuccess()
            assertEquals(FirstContactOperationPhase.THREAD_BOUND, bound.phase)
            assertEquals(
                FirstContactOperationResult.PhaseMismatch,
                repository.bindThread(
                    bound.id,
                    bound.revision,
                    ProviderThreadId(92L),
                    2_004L,
                ),
            )
            assertEquals(
                FirstContactOperationResult.PhaseMismatch,
                repository.markKnownUnsent(
                    bound.id,
                    bound.revision,
                    FirstContactKnownUnsentProof.PROVIDER_AUTHORITY_NOT_ENTERED,
                    2_004L,
                ),
            )

            val secondParticipants = listOf(ParticipantAddress("+15550000102"))
            val secondDraft = createDraft(database, secondParticipants, body = "Second")
            val second = repository.reserve(
                request(secondDraft, secondParticipants, MessageTransportKind.SMS, created = 3_000L),
            ).requireSuccess()
            val knownUnsent = repository.markKnownUnsent(
                second.id,
                second.revision,
                FirstContactKnownUnsentProof.PROVIDER_AUTHORITY_NOT_ENTERED,
                3_001L,
            ).requireSuccess()
            repository.release(knownUnsent.id, knownUnsent.revision).requireSuccess()
            assertEquals(FirstContactOperationResult.NotFound, repository.read(knownUnsent.id))
            assertEquals(
                secondDraft,
                RoomDraftRepository(database).read(secondDraft.id).requireSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun providerThreadBindingIsUniqueAndConflictLeavesBothOperationsUnchanged() = runBlocking {
        val database = openDatabase()
        try {
            val firstParticipants = listOf(ParticipantAddress("+15550000801"))
            val secondParticipants = listOf(ParticipantAddress("+15550000802"))
            val firstDraft = createDraft(database, firstParticipants, body = "First synthetic")
            val secondDraft = createDraft(database, secondParticipants, body = "Second synthetic")
            val repository = RoomFirstContactOperationRepository(database)
            val firstReserved = repository.reserve(
                request(firstDraft, firstParticipants, MessageTransportKind.SMS),
            ).requireSuccess()
            val secondReserved = repository.reserve(
                request(
                    secondDraft,
                    secondParticipants,
                    MessageTransportKind.SMS,
                    created = 3_000L,
                ),
            ).requireSuccess()
            val firstStarted = repository.markResolutionStarted(
                firstReserved.id,
                firstReserved.revision,
                2_001L,
            ).requireSuccess().operation
            val secondStarted = repository.markResolutionStarted(
                secondReserved.id,
                secondReserved.revision,
                3_001L,
            ).requireSuccess().operation
            val providerThreadId = ProviderThreadId(801L)
            val firstBound = repository.bindThread(
                firstStarted.id,
                firstStarted.revision,
                providerThreadId,
                2_002L,
            ).requireSuccess()

            assertEquals(
                FirstContactOperationResult.Conflict,
                repository.bindThread(
                    secondStarted.id,
                    secondStarted.revision,
                    providerThreadId,
                    3_002L,
                ),
            )
            assertEquals(firstBound, repository.read(firstBound.id).requireSuccess())
            assertEquals(secondStarted, repository.read(secondStarted.id).requireSuccess())
            assertEquals(
                1L,
                database.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM first_contact_operations WHERE provider_thread_id=?",
                    arrayOf(providerThreadId.value),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun bridgeRekeysSameDraftAndPreservesContentAttachmentsAndDurableHandoffOwner() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("person@example.invalid"))
            val attachment = attachment(7, 8, 9)
            val draft = createDraft(
                database,
                participants,
                body = "Synthetic MMS",
                subject = "Subject",
                attachments = listOf(attachment),
            )
            val repository = RoomFirstContactOperationRepository(database)
            val reserved = repository.reserve(
                request(draft, participants, MessageTransportKind.MMS),
            ).requireSuccess()
            val started = repository.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = repository.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(501L),
                2_002L,
            ).requireSuccess()

            val bridge = repository.bridgeToProviderThread(
                bound.id,
                bound.revision,
                2_003L,
            ).requireSuccess()

            assertEquals(FirstContactOperationPhase.HANDOFF_RESERVED, bridge.operation.phase)
            assertEquals(draft.id, bridge.providerDraft.id)
            assertEquals(DraftIdentity.ProviderThread(ProviderThreadId(501L)), bridge.providerDraft.identity)
            assertEquals(draft.body, bridge.providerDraft.body)
            assertEquals(draft.subject, bridge.providerDraft.subject)
            assertEquals(draft.createdTimestampMillis, bridge.providerDraft.createdTimestampMillis)
            assertTrue(bridge.providerDraft.revision.updatedTimestampMillis > draft.revision.updatedTimestampMillis)
            assertEquals(listOf(attachment), bridge.attachments)
            assertEquals(participants, bridge.participants)
            assertEquals(bridge.operation, repository.read(bound.id).requireSuccess())
            assertEquals(
                FirstContactOperationResult.PhaseMismatch,
                repository.release(bridge.operation.id, bridge.operation.revision),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun composerReservationAtomicallyConsumesOnlyTheExactHandoffOwner() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000501"))
            val draft = createDraft(database, participants, body = "Synthetic transfer")
            val signature = checkNotNull(MessageSignature.fromUserInput("Synthetic signature"))
            val firstContacts = RoomFirstContactOperationRepository(database)
            val reserved = firstContacts.reserve(
                request(draft, participants, MessageTransportKind.SMS).copy(
                    frozenSignature = signature,
                ),
            ).requireSuccess()
            val started = firstContacts.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = firstContacts.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(501L),
                2_002L,
            ).requireSuccess()
            val handoff = firstContacts.bridgeToProviderThread(
                bound.id,
                bound.revision,
                2_003L,
            ).requireSuccess()

            val composer = RoomComposerSmsOperationRepository(database).reserve(
                ComposerSmsReservationRequest(
                    providerThreadId = ProviderThreadId(501L),
                    draftId = draft.id,
                    expectedDraftRevision = handoff.providerDraft.revision,
                    subscriptionId = AuroraSubscriptionId(0),
                    createdTimestampMillis = 3_000L,
                    frozenSignature = signature,
                    firstContactAuthority = ComposerSmsFirstContactAuthority(
                        operationId = handoff.operation.id,
                        expectedRevision = handoff.operation.revision,
                        participantSetKey = handoff.operation.participantSetKey,
                        attachmentSetEvidence =
                            FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
                    ),
                ),
            ).requireComposerSuccess()

            assertEquals("Synthetic transfer", composer.authoritativeBody)
            assertEquals(ComposerSmsOperationPhase.RESERVED, composer.operation.phase)
            assertEquals(FirstContactOperationResult.NotFound, firstContacts.read(handoff.operation.id))
            assertEquals(
                listOf(composer.operation),
                RoomComposerSmsOperationRepository(database)
                    .recoverySnapshot()
                    .requireComposerSuccess(),
            )
            assertEquals(
                handoff.providerDraft,
                RoomDraftRepository(database).read(draft.id).requireSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun composerReservationRejectsMissingStaleOrAttachmentDriftedHandoffAuthority() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("handoff-drift@example.invalid"))
            val original = attachment(1, 2, 3)
            val replacement = attachment(4, 5, 6)
            val draft = createDraft(
                database,
                participants,
                body = "Synthetic MMS transfer",
                attachments = listOf(original),
            )
            val firstContacts = RoomFirstContactOperationRepository(database)
            val reserved = firstContacts.reserve(
                request(draft, participants, MessageTransportKind.MMS),
            ).requireSuccess()
            val started = firstContacts.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = firstContacts.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(502L),
                2_002L,
            ).requireSuccess()
            val handoff = firstContacts.bridgeToProviderThread(
                bound.id,
                bound.revision,
                2_003L,
            ).requireSuccess()
            val operations = RoomComposerSmsOperationRepository(database)
            val base = ComposerSmsReservationRequest(
                providerThreadId = ProviderThreadId(502L),
                draftId = draft.id,
                expectedDraftRevision = handoff.providerDraft.revision,
                subscriptionId = AuroraSubscriptionId(0),
                createdTimestampMillis = 3_000L,
                transport = MessageTransportKind.MMS,
                hasAttachments = true,
            )

            assertEquals(ComposerSmsOperationResult.Conflict, operations.reserve(base))
            assertEquals(
                ComposerSmsOperationResult.Conflict,
                operations.reserve(
                    base.copy(
                        firstContactAuthority = ComposerSmsFirstContactAuthority(
                            operationId = handoff.operation.id,
                            expectedRevision = handoff.operation.revision,
                            participantSetKey = FirstContactParticipantSetKey.fromParticipants(
                                listOf(ParticipantAddress("different@example.invalid")),
                            ),
                            attachmentSetEvidence =
                                FirstContactAttachmentSetEvidence.fromAttachments(listOf(original)),
                        ),
                    ),
                ),
            )
            assertEquals(
                ComposerSmsOperationResult.StaleWrite,
                operations.reserve(
                    base.copy(
                        firstContactAuthority = ComposerSmsFirstContactAuthority(
                            operationId = handoff.operation.id,
                            expectedRevision = FirstContactOperationRevision(2_002L),
                            participantSetKey = handoff.operation.participantSetKey,
                            attachmentSetEvidence =
                                FirstContactAttachmentSetEvidence.fromAttachments(listOf(original)),
                        ),
                    ),
                ),
            )
            assertEquals(
                ComposerSmsOperationResult.Conflict,
                operations.reserve(
                    base.copy(
                        firstContactAuthority = ComposerSmsFirstContactAuthority(
                            operationId = handoff.operation.id,
                            expectedRevision = handoff.operation.revision,
                            participantSetKey = handoff.operation.participantSetKey,
                            attachmentSetEvidence =
                                FirstContactAttachmentSetEvidence.fromAttachments(
                                    listOf(replacement),
                                ),
                        ),
                    ),
                ),
            )
            RoomDraftAttachmentRepository(database).replace(
                draft.id,
                handoff.providerDraft.revision,
                listOf(replacement),
            ).requireSuccess()
            assertEquals(
                ComposerSmsOperationResult.StaleWrite,
                operations.reserve(
                    base.copy(
                        firstContactAuthority = ComposerSmsFirstContactAuthority(
                            operationId = handoff.operation.id,
                            expectedRevision = handoff.operation.revision,
                            participantSetKey = handoff.operation.participantSetKey,
                            attachmentSetEvidence =
                                FirstContactAttachmentSetEvidence.fromAttachments(listOf(original)),
                        ),
                    ),
                ),
            )
            assertEquals(handoff.operation, firstContacts.read(handoff.operation.id).requireSuccess())
            assertEquals(emptyList<ComposerSmsOperation>(), operations.recoverySnapshot().requireComposerSuccess())
        } finally {
            database.close()
        }
    }

    @Test
    fun composerReservationRejectsRemainingImmutableHandoffMismatches() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000504"))
            val signature = checkNotNull(MessageSignature.fromUserInput("Synthetic signature"))
            val handoff = createHandoff(
                database = database,
                participants = participants,
                body = "Synthetic mismatch matrix",
                providerThreadId = ProviderThreadId(504L),
                transport = MessageTransportKind.MMS,
                attachments = listOf(attachment(5, 0, 4)),
                frozenSignature = signature,
            )
            val base = handoff.composerRequest()
            val authority = checkNotNull(base.firstContactAuthority)
            val cases = listOf(
                Triple(
                    "operation ID",
                    ComposerSmsOperationResult.StaleWrite,
                    base.copy(
                        firstContactAuthority = authority.copy(
                            operationId = FirstContactOperationId(
                                handoff.operation.id.value + 1_000L,
                            ),
                        ),
                    ),
                ),
                Triple(
                    "provider thread",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(providerThreadId = ProviderThreadId(1_504L)),
                ),
                Triple(
                    "draft ID",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(draftId = DraftId(handoff.providerDraft.id.value + 1_000L)),
                ),
                Triple(
                    "handoff draft revision",
                    ComposerSmsOperationResult.StaleWrite,
                    base.copy(
                        expectedDraftRevision = DraftRevision(
                            handoff.providerDraft.revision.updatedTimestampMillis + 1L,
                        ),
                    ),
                ),
                Triple(
                    "subscription",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(subscriptionId = AuroraSubscriptionId(1)),
                ),
                Triple(
                    "transport",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(
                        transport = MessageTransportKind.SMS,
                        hasAttachments = false,
                    ),
                ),
                Triple(
                    "missing signature",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(frozenSignature = null),
                ),
                Triple(
                    "different signature",
                    ComposerSmsOperationResult.Conflict,
                    base.copy(
                        frozenSignature = checkNotNull(
                            MessageSignature.fromUserInput("Different signature"),
                        ),
                    ),
                ),
                Triple(
                    "attachment presence",
                    ComposerSmsOperationResult.StaleWrite,
                    base.copy(hasAttachments = false),
                ),
            )
            val firstContacts = RoomFirstContactOperationRepository(database)
            val composer = RoomComposerSmsOperationRepository(database)

            cases.forEach { (name, expected, candidate) ->
                assertEquals(name, expected, composer.reserve(candidate))
                assertEquals(
                    name,
                    handoff.operation,
                    firstContacts.read(handoff.operation.id).requireSuccess(),
                )
                assertEquals(
                    name,
                    emptyList<ComposerSmsOperation>(),
                    composer.recoverySnapshot().requireComposerSuccess(),
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun composerReservationRollsBackWhenThreadWorkAppearsAfterHandoff() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000503"))
            val draft = createDraft(database, participants, body = "Synthetic conflict")
            val firstContacts = RoomFirstContactOperationRepository(database)
            val reserved = firstContacts.reserve(
                request(draft, participants, MessageTransportKind.SMS),
            ).requireSuccess()
            val started = firstContacts.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = firstContacts.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(503L),
                2_002L,
            ).requireSuccess()
            val handoff = firstContacts.bridgeToProviderThread(
                bound.id,
                bound.revision,
                2_003L,
            ).requireSuccess()
            val delayKey = SendDelayParticipantSetKey.fromParticipants(participants)
                .toStorageValue()
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO send_delay_operations(participant_set_key,provider_thread_id," +
                    "draft_id,draft_revision_ms,subscription_id,due_timestamp_ms,phase_code," +
                    "review_reason_code,armed_wall_timestamp_ms,armed_elapsed_realtime_ms," +
                    "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                    "VALUES('$delayKey',503,${draft.id.value}," +
                    "${handoff.providerDraft.revision.updatedTimestampMillis},0,4000," +
                    "'pending_v1',NULL,3000,1000,3000,3000,NULL)",
            )
            val composer = RoomComposerSmsOperationRepository(database)

            assertEquals(
                ComposerSmsOperationResult.Conflict,
                composer.reserve(
                    ComposerSmsReservationRequest(
                        providerThreadId = ProviderThreadId(503L),
                        draftId = draft.id,
                        expectedDraftRevision = handoff.providerDraft.revision,
                        subscriptionId = AuroraSubscriptionId(0),
                        createdTimestampMillis = 5_000L,
                        firstContactAuthority = ComposerSmsFirstContactAuthority(
                            operationId = handoff.operation.id,
                            expectedRevision = handoff.operation.revision,
                            participantSetKey = handoff.operation.participantSetKey,
                            attachmentSetEvidence =
                                FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
                        ),
                    ),
                ),
            )
            assertEquals(
                handoff.operation,
                firstContacts.read(handoff.operation.id).requireSuccess(),
            )
            assertEquals(
                emptyList<ComposerSmsOperation>(),
                composer.recoverySnapshot().requireComposerSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun composerCapPreservesFirstContactHandoffOwner() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000505"))
            val handoff = createHandoff(
                database = database,
                participants = participants,
                body = "Synthetic cap",
                providerThreadId = ProviderThreadId(505L),
            )
            val sqlite = database.openHelper.writableDatabase
            repeat(MAXIMUM_COMPOSER_SMS_OPERATIONS) { index ->
                val ordinal = index.toLong() + 1L
                val timestamp = 30_000L + ordinal
                sqlite.execSQL(
                    "INSERT INTO composer_sms_operations(provider_thread_id,draft_id," +
                        "draft_revision_ms,subscription_id,transport_code,phase_code," +
                        "provider_message_id,provider_conversation_id,unit_count," +
                        "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                        "VALUES(?,?,1,0,'sms_v1','reserved_v1',NULL,NULL,NULL,?,?,NULL)",
                    arrayOf<Any?>(
                        10_000L + ordinal,
                        20_000L + ordinal,
                        timestamp,
                        timestamp,
                    ),
                )
            }

            assertEquals(
                ComposerSmsOperationResult.LimitExceeded,
                RoomComposerSmsOperationRepository(database).reserve(handoff.composerRequest()),
            )
            assertEquals(
                handoff.operation,
                RoomFirstContactOperationRepository(database)
                    .read(handoff.operation.id)
                    .requireSuccess(),
            )
            assertEquals(
                handoff.providerDraft,
                RoomDraftRepository(database)
                    .read(handoff.providerDraft.id)
                    .requireSuccess(),
            )
            sqlite.query("SELECT COUNT(*) FROM composer_sms_operations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(MAXIMUM_COMPOSER_SMS_OPERATIONS, cursor.getInt(0))
            }
            sqlite.query(
                "SELECT COUNT(*) FROM composer_sms_operations WHERE provider_thread_id=?",
                arrayOf(checkNotNull(handoff.operation.providerThreadId).value),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun composerReservationRollsBackWhenPostInsertHandoffDeleteIsIgnored() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000506"))
            val handoff = createHandoff(
                database = database,
                participants = participants,
                body = "Synthetic rollback",
                providerThreadId = ProviderThreadId(506L),
            )
            val sqlite = database.openHelper.writableDatabase
            val triggerName = "test_ignore_exact_first_contact_handoff_delete"
            sqlite.execSQL(
                "CREATE TRIGGER $triggerName BEFORE DELETE ON first_contact_operations " +
                    "WHEN OLD.first_contact_id=${handoff.operation.id.value} " +
                    "AND OLD.phase_code='handoff_reserved_v1' " +
                    "AND EXISTS(SELECT 1 FROM composer_sms_operations " +
                    "WHERE provider_thread_id=OLD.provider_thread_id " +
                    "AND draft_id=OLD.draft_id " +
                    "AND draft_revision_ms=OLD.handoff_draft_revision_ms " +
                    "AND subscription_id=OLD.subscription_id " +
                    "AND transport_code=OLD.transport_code " +
                    "AND phase_code='reserved_v1' " +
                    "AND provider_message_id IS NULL " +
                    "AND provider_conversation_id IS NULL " +
                    "AND signature_text IS OLD.signature_text) " +
                    "BEGIN SELECT RAISE(IGNORE); END",
            )
            val result = try {
                RoomComposerSmsOperationRepository(database).reserve(handoff.composerRequest())
            } finally {
                sqlite.execSQL("DROP TRIGGER IF EXISTS $triggerName")
            }

            assertEquals(ComposerSmsOperationResult.StaleWrite, result)
            assertEquals(
                handoff.operation,
                RoomFirstContactOperationRepository(database)
                    .read(handoff.operation.id)
                    .requireSuccess(),
            )
            assertEquals(
                emptyList<ComposerSmsOperation>(),
                RoomComposerSmsOperationRepository(database)
                    .recoverySnapshot()
                    .requireComposerSuccess(),
            )
            assertEquals(
                handoff.providerDraft,
                RoomDraftRepository(database)
                    .read(handoff.providerDraft.id)
                    .requireSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun sameRevisionAttachmentReplacementMakesBridgeStaleWithoutRekeyingAnything() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000103"))
            val original = attachment(1, 1, 1)
            val replacement = attachment(2, 2, 2)
            val draft = createDraft(
                database,
                participants,
                body = "Synthetic MMS",
                attachments = listOf(original),
            )
            val repository = RoomFirstContactOperationRepository(database)
            val reserved = repository.reserve(
                request(draft, participants, MessageTransportKind.MMS),
            ).requireSuccess()
            val started = repository.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = repository.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(502L),
                2_002L,
            ).requireSuccess()
            RoomDraftAttachmentRepository(database).replace(
                draft.id,
                draft.revision,
                listOf(replacement),
            ).requireSuccess()

            assertEquals(
                FirstContactOperationResult.StaleWrite,
                repository.bridgeToProviderThread(bound.id, bound.revision, 2_003L),
            )
            assertEquals(bound, repository.read(bound.id).requireSuccess())
            assertEquals(draft, RoomDraftRepository(database).read(draft.id).requireSuccess())
            assertEquals(
                listOf(replacement),
                RoomDraftAttachmentRepository(database).read(draft.id).requireSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun providerThreadDraftSiblingMakesBridgeConflictAndPreservesBothExactOwners() = runBlocking {
        val database = openDatabase()
        try {
            val participants = listOf(ParticipantAddress("+15550000104"))
            val sourceAttachment = attachment(4, 4, 4)
            val siblingAttachment = attachment(5, 5, 5)
            val source = createDraft(
                database,
                participants,
                body = "First-contact source",
                attachments = listOf(sourceAttachment),
            )
            val sibling = RoomDraftRepository(database).create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(ProviderThreadId(504L)),
                    body = "Existing provider-thread sibling",
                    subject = null,
                    createdTimestampMillis = 1_100L,
                    updatedTimestampMillis = 1_100L,
                ),
            ).requireSuccess()
            RoomDraftAttachmentRepository(database).replace(
                sibling.id,
                sibling.revision,
                listOf(siblingAttachment),
            ).requireSuccess()
            val repository = RoomFirstContactOperationRepository(database)
            val reserved = repository.reserve(
                request(source, participants, MessageTransportKind.MMS),
            ).requireSuccess()
            val started = repository.markResolutionStarted(
                reserved.id,
                reserved.revision,
                2_001L,
            ).requireSuccess().operation
            val bound = repository.bindThread(
                started.id,
                started.revision,
                ProviderThreadId(504L),
                2_002L,
            ).requireSuccess()

            assertEquals(
                FirstContactOperationResult.Conflict,
                repository.bridgeToProviderThread(bound.id, bound.revision, 2_003L),
            )
            assertEquals(bound, repository.read(bound.id).requireSuccess())
            assertEquals(source, RoomDraftRepository(database).read(source.id).requireSuccess())
            assertEquals(sibling, RoomDraftRepository(database).read(sibling.id).requireSuccess())
            assertEquals(
                listOf(sourceAttachment),
                RoomDraftAttachmentRepository(database).read(source.id).requireSuccess(),
            )
            assertEquals(
                listOf(siblingAttachment),
                RoomDraftAttachmentRepository(database).read(sibling.id).requireSuccess(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun bridgeRejectsComposerScheduledAndSendDelayOwnersEvenWithoutTargetDraftSibling() = runBlocking {
        val database = openDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO composer_sms_operations(provider_thread_id,draft_id," +
                    "draft_revision_ms,subscription_id,transport_code,phase_code," +
                    "provider_message_id,provider_conversation_id,unit_count," +
                    "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                    "VALUES(601,9001,1,0,'sms_v1','reserved_v1',NULL,NULL,NULL,10,10,NULL)",
            )
            val scheduledKey = ScheduledSmsParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("+15550000602")),
            ).toStorageValue()
            sqlite.execSQL(
                "INSERT INTO scheduled_sms_operations(participant_set_key,provider_thread_id," +
                    "draft_id,draft_revision_ms,subscription_id,due_timestamp_ms,phase_code," +
                    "precision_code,review_reason_code,armed_wall_timestamp_ms," +
                    "armed_elapsed_realtime_ms,created_timestamp_ms,updated_timestamp_ms," +
                    "signature_text) VALUES('$scheduledKey',602,9002,1,0,2000,'pending_v1'," +
                    "'inexact_v1',NULL,1000,1,1000,1000,NULL)",
            )
            val delayKey = SendDelayParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("+15550000603")),
            ).toStorageValue()
            sqlite.execSQL(
                "INSERT INTO send_delay_operations(participant_set_key,provider_thread_id," +
                    "draft_id,draft_revision_ms,subscription_id,due_timestamp_ms,phase_code," +
                    "review_reason_code,armed_wall_timestamp_ms,armed_elapsed_realtime_ms," +
                    "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                    "VALUES('$delayKey',603,9003,1,0,2000,'pending_v1',NULL,1000,1,1000,1000,NULL)",
            )

            listOf(601L, 602L, 603L).forEachIndexed { index, thread ->
                val participants = listOf(ParticipantAddress("+1555000070$index"))
                val draft = createDraft(database, participants, body = "Synthetic $index")
                val repository = RoomFirstContactOperationRepository(database)
                val reserved = repository.reserve(
                    request(
                        draft,
                        participants,
                        MessageTransportKind.SMS,
                        created = 3_000L + index * 10L,
                    ),
                ).requireSuccess()
                val started = repository.markResolutionStarted(
                    reserved.id,
                    reserved.revision,
                    3_001L + index * 10L,
                ).requireSuccess().operation
                val bound = repository.bindThread(
                    started.id,
                    started.revision,
                    ProviderThreadId(thread),
                    3_002L + index * 10L,
                ).requireSuccess()
                assertEquals(
                    FirstContactOperationResult.Conflict,
                    repository.bridgeToProviderThread(
                        bound.id,
                        bound.revision,
                        3_003L + index * 10L,
                    ),
                )
                assertEquals(bound, repository.read(bound.id).requireSuccess())
                assertEquals(
                    DraftIdentity.ParticipantSet(DraftParticipantSetKey.fromParticipants(participants)),
                    RoomDraftRepository(database).read(draft.id).requireSuccess().identity,
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun physicalInsertCapRejectsTheOneHundredTwentyNinthOwner() {
        val database = openDatabase()
        try {
            val sqlite = database.openHelper.writableDatabase
            val emptyEvidence = FirstContactAttachmentSetEvidence.fromAttachments(emptyList())
                .toStorageValue()
            repeat(MAXIMUM_FIRST_CONTACT_OPERATIONS) { index ->
                val key = FirstContactParticipantSetKey.fromParticipants(
                    listOf(ParticipantAddress("cap-$index@example.invalid")),
                ).toStorageValue()
                sqlite.execSQL(
                    "INSERT INTO first_contact_operations(participant_set_key,draft_id," +
                        "source_draft_revision_ms,attachment_set_evidence,subscription_id," +
                        "transport_code,phase_code,provider_thread_id,handoff_draft_revision_ms," +
                        "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                        "VALUES('$key',${index + 1},1,'$emptyEvidence',0,'sms_v1'," +
                        "'reserved_v1',NULL,NULL,1,1,NULL)",
                )
            }
            val overflowKey = FirstContactParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("cap-overflow@example.invalid")),
            ).toStorageValue()
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO first_contact_operations(participant_set_key,draft_id," +
                        "source_draft_revision_ms,attachment_set_evidence,subscription_id," +
                        "transport_code,phase_code,provider_thread_id,handoff_draft_revision_ms," +
                        "created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                        "VALUES('$overflowKey',9999,1,'$emptyEvidence',0,'sms_v1'," +
                        "'reserved_v1',NULL,NULL,1,1,NULL)",
                )
            }
        } finally {
            database.close()
        }
    }

    private suspend fun createDraft(
        database: org.aurorasms.core.state.storage.AuroraStateDatabase,
        participants: List<ParticipantAddress>,
        body: String?,
        subject: String? = null,
        attachments: List<DraftAttachment> = emptyList(),
    ): Draft {
        val draft = RoomDraftRepository(database).create(
            NewDraft(
                identity = DraftIdentity.ParticipantSet(
                    DraftParticipantSetKey.fromParticipants(participants),
                ),
                body = body,
                subject = subject,
                createdTimestampMillis = 1_000L,
                updatedTimestampMillis = 1_000L,
            ),
        ).requireSuccess()
        if (attachments.isNotEmpty()) {
            RoomDraftAttachmentRepository(database).replace(
                draft.id,
                draft.revision,
                attachments,
            ).requireSuccess()
        }
        return draft
    }

    private fun request(
        draft: Draft,
        participants: List<ParticipantAddress>,
        transport: MessageTransportKind,
        created: Long = 2_000L,
    ) = FirstContactReservationRequest(
        participants = participants,
        draftId = draft.id,
        expectedDraftRevision = draft.revision,
        subscriptionId = AuroraSubscriptionId(0),
        transport = transport,
        createdTimestampMillis = created,
    )

    private suspend fun createHandoff(
        database: org.aurorasms.core.state.storage.AuroraStateDatabase,
        participants: List<ParticipantAddress>,
        body: String,
        providerThreadId: ProviderThreadId,
        transport: MessageTransportKind = MessageTransportKind.SMS,
        attachments: List<DraftAttachment> = emptyList(),
        frozenSignature: MessageSignature? = null,
    ): FirstContactBridgeSnapshot {
        val draft = createDraft(
            database = database,
            participants = participants,
            body = body,
            attachments = attachments,
        )
        val repository = RoomFirstContactOperationRepository(database)
        val reserved = repository.reserve(
            request(draft, participants, transport).copy(frozenSignature = frozenSignature),
        ).requireSuccess()
        val started = repository.markResolutionStarted(
            reserved.id,
            reserved.revision,
            2_001L,
        ).requireSuccess().operation
        val bound = repository.bindThread(
            started.id,
            started.revision,
            providerThreadId,
            2_002L,
        ).requireSuccess()
        return repository.bridgeToProviderThread(
            bound.id,
            bound.revision,
            2_003L,
        ).requireSuccess()
    }

    private fun FirstContactBridgeSnapshot.composerRequest() =
        ComposerSmsReservationRequest(
            providerThreadId = checkNotNull(operation.providerThreadId),
            draftId = providerDraft.id,
            expectedDraftRevision = providerDraft.revision,
            subscriptionId = operation.subscriptionId,
            createdTimestampMillis = 3_000L,
            frozenSignature = operation.frozenSignature,
            transport = operation.transport,
            hasAttachments = attachments.isNotEmpty(),
            firstContactAuthority = ComposerSmsFirstContactAuthority(
                operationId = operation.id,
                expectedRevision = operation.revision,
                participantSetKey = operation.participantSetKey,
                attachmentSetEvidence = operation.attachmentSetEvidence,
            ),
        )

    private fun attachment(vararg bytes: Int): DraftAttachment =
        (DraftAttachment.create("image/jpeg", bytes.map(Int::toByte).toByteArray()) as
            DraftAttachment.CreationResult.Valid).attachment

    private fun openDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database
}

private fun <T> FirstContactOperationResult<T>.requireSuccess(): T =
    (this as FirstContactOperationResult.Success<T>).value

private fun <T> DraftRepositoryResult<T>.requireSuccess(): T =
    (this as DraftRepositoryResult.Success<T>).value

private fun <T> ComposerSmsOperationResult<T>.requireComposerSuccess(): T =
    (this as ComposerSmsOperationResult.Success<T>).value
