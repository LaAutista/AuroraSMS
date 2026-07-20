-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. Schema 11 adds one nullable,
-- bounded frozen signature to each durable send owner. Migration 10->11 drops
-- and reinstalls these six changed integrity triggers; the factory reinstalls
-- them and every unchanged prior trigger on create and open.

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
    OR (NEW.signature_text IS NOT NULL AND
        (length(NEW.signature_text) < 1 OR length(NEW.signature_text) > 160))
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
    OR NEW.signature_text IS NOT OLD.signature_text
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR (NEW.provider_message_id IS NULL) != (NEW.provider_conversation_id IS NULL)
    OR (NEW.provider_message_id IS NULL) != (NEW.unit_count IS NULL)
    OR (NEW.provider_message_id IS NOT NULL AND NEW.provider_message_id <= 0)
    OR (NEW.provider_conversation_id IS NOT NULL AND NEW.provider_conversation_id <= 0)
    OR (NEW.unit_count IS NOT NULL AND (NEW.unit_count < 1 OR NEW.unit_count > 1))
    OR NOT (
        (OLD.phase_code = 'reserved_v1'
            AND NEW.phase_code = 'prepared_v1'
            AND OLD.provider_message_id IS NULL
            AND NEW.provider_message_id IS NOT NULL)
        OR (OLD.phase_code = 'reserved_v1'
            AND NEW.phase_code = 'known_unsent_v1'
            AND NEW.provider_message_id IS NULL)
        OR (OLD.phase_code = 'prepared_v1'
            AND NEW.phase_code IN ('submitting_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count)
        OR (OLD.phase_code = 'submitting_v1'
            AND NEW.phase_code IN (
                'platform_accepted_v1',
                'sent_callback_succeeded_v1',
                'submission_unknown_v1',
                'known_unsent_v1'
            )
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count)
        OR (OLD.phase_code = 'platform_accepted_v1'
            AND NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count)
        OR (OLD.phase_code = 'submission_unknown_v1'
            AND NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1')
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count)
        OR (OLD.phase_code = 'platform_accepted_v1'
            AND NEW.phase_code = 'submission_unknown_v1'
            AND NEW.provider_message_id = OLD.provider_message_id
            AND NEW.provider_conversation_id = OLD.provider_conversation_id
            AND NEW.unit_count = OLD.unit_count)
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid composer SMS transition');
END;

CREATE TRIGGER IF NOT EXISTS scheduled_sms_operations_enforce_integrity_insert
BEFORE INSERT ON scheduled_sms_operations
WHEN length(NEW.participant_set_key) != 74
    OR substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:'
    OR substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*'
    OR NEW.provider_thread_id <= 0
    OR NEW.draft_id <= 0
    OR NEW.draft_revision_ms < 0
    OR NEW.subscription_id < 0
    OR NEW.due_timestamp_ms <= NEW.created_timestamp_ms
    OR NEW.phase_code != 'pending_v1'
    OR NEW.precision_code != 'inexact_v1'
    OR NEW.review_reason_code IS NOT NULL
    OR NEW.armed_wall_timestamp_ms < 0
    OR (NEW.signature_text IS NOT NULL AND
        (length(NEW.signature_text) < 1 OR length(NEW.signature_text) > 160))
    OR NEW.armed_elapsed_realtime_ms < 0
    OR NEW.created_timestamp_ms < 0
    OR NEW.updated_timestamp_ms != NEW.created_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid scheduled SMS operation');
END;

CREATE TRIGGER IF NOT EXISTS scheduled_sms_operations_enforce_integrity_update
BEFORE UPDATE ON scheduled_sms_operations
WHEN NEW.schedule_id != OLD.schedule_id
    OR NEW.participant_set_key != OLD.participant_set_key
    OR NEW.provider_thread_id != OLD.provider_thread_id
    OR NEW.draft_id != OLD.draft_id
    OR NEW.draft_revision_ms != OLD.draft_revision_ms
    OR NEW.subscription_id != OLD.subscription_id
    OR NEW.signature_text IS NOT OLD.signature_text
    OR NEW.due_timestamp_ms != OLD.due_timestamp_ms
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR NEW.precision_code NOT IN ('exact_v1','inexact_v1')
    OR NEW.armed_wall_timestamp_ms < 0
    OR NEW.armed_elapsed_realtime_ms < 0
    OR (NEW.phase_code = 'pending_v1' AND NEW.review_reason_code IS NOT NULL)
    OR (NEW.phase_code = 'dispatching_v1' AND
        (OLD.phase_code != 'pending_v1' OR NEW.review_reason_code IS NOT NULL))
    OR (NEW.phase_code = 'review_required_v1' AND
        (OLD.phase_code NOT IN ('pending_v1','dispatching_v1') OR
            NEW.review_reason_code IS NULL))
    OR NEW.phase_code NOT IN ('pending_v1','dispatching_v1','review_required_v1')
BEGIN
    SELECT RAISE(ABORT, 'invalid scheduled SMS transition');
END;

CREATE TRIGGER IF NOT EXISTS send_delay_operations_enforce_integrity_insert
BEFORE INSERT ON send_delay_operations
WHEN length(NEW.participant_set_key) != 74
    OR substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:'
    OR substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*'
    OR NEW.provider_thread_id <= 0
    OR NEW.draft_id <= 0
    OR NEW.draft_revision_ms < 0
    OR NEW.subscription_id < 0
    OR NEW.due_timestamp_ms - NEW.created_timestamp_ms < 1000
    OR NEW.due_timestamp_ms - NEW.created_timestamp_ms > 10000
    OR NEW.phase_code != 'pending_v1'
    OR NEW.review_reason_code IS NOT NULL
    OR (NEW.signature_text IS NOT NULL AND
        (length(NEW.signature_text) < 1 OR length(NEW.signature_text) > 160))
    OR NEW.armed_wall_timestamp_ms != NEW.created_timestamp_ms
    OR NEW.armed_elapsed_realtime_ms < 0
    OR NEW.created_timestamp_ms < 0
    OR NEW.updated_timestamp_ms != NEW.created_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid send-delay reservation');
END;

CREATE TRIGGER IF NOT EXISTS send_delay_operations_enforce_integrity_update
BEFORE UPDATE ON send_delay_operations
WHEN NEW.send_delay_id != OLD.send_delay_id
    OR NEW.participant_set_key != OLD.participant_set_key
    OR NEW.provider_thread_id != OLD.provider_thread_id
    OR NEW.draft_id != OLD.draft_id
    OR NEW.draft_revision_ms != OLD.draft_revision_ms
    OR NEW.subscription_id != OLD.subscription_id
    OR NEW.signature_text IS NOT OLD.signature_text
    OR NEW.due_timestamp_ms != OLD.due_timestamp_ms
    OR NEW.armed_wall_timestamp_ms != OLD.armed_wall_timestamp_ms
    OR NEW.armed_elapsed_realtime_ms != OLD.armed_elapsed_realtime_ms
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR NOT (
        (OLD.phase_code = 'pending_v1'
            AND NEW.phase_code = 'dispatching_v1'
            AND NEW.review_reason_code IS NULL)
        OR (OLD.phase_code IN ('pending_v1', 'dispatching_v1')
            AND NEW.phase_code = 'review_required_v1'
            AND NEW.review_reason_code IN (
                'clock_changed_v1',
                'missed_after_restart_v1',
                'precondition_failed_v1',
                'arming_failed_v1',
                'interrupted_before_reservation_v1'
            ))
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid send-delay transition');
END;
