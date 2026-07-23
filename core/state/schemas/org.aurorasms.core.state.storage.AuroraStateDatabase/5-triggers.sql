-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory installs
-- these deterministic version-5 invariants on both create and open. Explicit
-- migrations install each trigger when its owning table appears.

CREATE TRIGGER IF NOT EXISTS drafts_require_exactly_one_identity_insert
BEFORE INSERT ON drafts
WHEN ((NEW.provider_thread_id IS NULL) = (NEW.participant_set_key IS NULL))
    OR (NEW.provider_thread_id IS NOT NULL AND NEW.provider_thread_id <= 0)
    OR (
        NEW.participant_set_key IS NOT NULL
        AND (
            length(NEW.participant_set_key) = 0
            OR length(NEW.participant_set_key) > 32099
        )
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid draft identity');
END;

CREATE TRIGGER IF NOT EXISTS drafts_require_exactly_one_identity_update
BEFORE UPDATE OF provider_thread_id, participant_set_key ON drafts
WHEN ((NEW.provider_thread_id IS NULL) = (NEW.participant_set_key IS NULL))
    OR (NEW.provider_thread_id IS NOT NULL AND NEW.provider_thread_id <= 0)
    OR (
        NEW.participant_set_key IS NOT NULL
        AND (
            length(NEW.participant_set_key) = 0
            OR length(NEW.participant_set_key) > 32099
        )
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid draft identity');
END;

CREATE TRIGGER IF NOT EXISTS appearance_selection_require_singleton_insert
BEFORE INSERT ON appearance_selection
WHEN NEW.singleton_id != 1
BEGIN
    SELECT RAISE(ABORT, 'invalid appearance selection singleton');
END;

CREATE TRIGGER IF NOT EXISTS appearance_selection_require_singleton_update
BEFORE UPDATE OF singleton_id ON appearance_selection
WHEN NEW.singleton_id != 1
BEGIN
    SELECT RAISE(ABORT, 'invalid appearance selection singleton');
END;

CREATE TRIGGER IF NOT EXISTS appearance_override_revision_sequence_require_singleton_insert
BEFORE INSERT ON appearance_override_revision_sequence
WHEN NEW.singleton_id != 1 OR NEW.last_allocated_revision != 0
BEGIN
    SELECT RAISE(ABORT, 'invalid appearance override sequence singleton');
END;

CREATE TRIGGER IF NOT EXISTS appearance_override_revision_sequence_require_singleton_update
BEFORE UPDATE ON appearance_override_revision_sequence
WHEN NEW.singleton_id != 1
    OR NEW.last_allocated_revision != OLD.last_allocated_revision + 1
BEGIN
    SELECT RAISE(ABORT, 'invalid appearance override sequence advance');
END;

CREATE TRIGGER IF NOT EXISTS appearance_override_revision_sequence_reject_delete
BEFORE DELETE ON appearance_override_revision_sequence
BEGIN
    SELECT RAISE(ABORT, 'appearance override sequence cannot be deleted');
END;

CREATE TRIGGER IF NOT EXISTS composer_sms_operations_enforce_limit_insert
BEFORE INSERT ON composer_sms_operations
WHEN (SELECT COUNT(*) FROM composer_sms_operations) >= 128
BEGIN
    SELECT RAISE(ABORT, 'composer SMS operation limit reached');
END;

CREATE TRIGGER IF NOT EXISTS composer_sms_operations_enforce_integrity_insert
BEFORE INSERT ON composer_sms_operations
WHEN NEW.provider_thread_id <= 0
    OR NEW.draft_id <= 0
    OR NEW.draft_revision_ms < 0
    OR NEW.subscription_id < 0
    OR NEW.phase_code != 'reserved_v1'
    OR NEW.provider_message_id IS NOT NULL
    OR NEW.provider_conversation_id IS NOT NULL
    OR NEW.unit_count IS NOT NULL
    OR NEW.created_timestamp_ms < 0
    OR NEW.updated_timestamp_ms != NEW.created_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid composer SMS reservation');
END;

CREATE TRIGGER IF NOT EXISTS composer_sms_operations_enforce_integrity_update
BEFORE UPDATE ON composer_sms_operations
WHEN NEW.local_operation_id != OLD.local_operation_id
    OR NEW.provider_thread_id != OLD.provider_thread_id
    OR NEW.draft_id != OLD.draft_id
    OR NEW.draft_revision_ms != OLD.draft_revision_ms
    OR NEW.subscription_id != OLD.subscription_id
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR (NEW.provider_message_id IS NULL) != (NEW.provider_conversation_id IS NULL)
    OR (NEW.provider_message_id IS NULL) != (NEW.unit_count IS NULL)
    OR (NEW.provider_message_id IS NOT NULL AND NEW.provider_message_id <= 0)
    OR (NEW.provider_conversation_id IS NOT NULL AND NEW.provider_conversation_id <= 0)
    OR (NEW.unit_count IS NOT NULL AND (NEW.unit_count < 1 OR NEW.unit_count > 1))
    OR NOT (
        (
            OLD.phase_code = 'reserved_v1'
            AND NEW.phase_code = 'prepared_v1'
            AND OLD.provider_message_id IS NULL
            AND NEW.provider_message_id IS NOT NULL
        )
        OR (
            OLD.phase_code = 'reserved_v1'
            AND NEW.phase_code = 'known_unsent_v1'
            AND NEW.provider_message_id IS NULL
        )
        OR (
            OLD.phase_code = 'prepared_v1'
            AND NEW.phase_code IN ('submitting_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count
        )
        OR (
            OLD.phase_code = 'submitting_v1'
            AND NEW.phase_code IN (
                'platform_accepted_v1',
                'sent_callback_succeeded_v1',
                'submission_unknown_v1',
                'known_unsent_v1'
            )
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count
        )
        OR (
            OLD.phase_code = 'platform_accepted_v1'
            AND NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count
        )
        OR (
            OLD.phase_code = 'submission_unknown_v1'
            AND NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count
        )
        OR (
            OLD.phase_code = 'platform_accepted_v1'
            AND NEW.phase_code = 'submission_unknown_v1'
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count
        )
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid composer SMS transition');
END;
