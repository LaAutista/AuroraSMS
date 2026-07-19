-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. Schema 9 adds the bounded,
-- content-free short send-delay lifecycle; the factory reinstalls prior triggers.

CREATE TRIGGER IF NOT EXISTS send_delay_operations_enforce_limit_insert
BEFORE INSERT ON send_delay_operations
WHEN (SELECT COUNT(*) FROM send_delay_operations) >= 128
BEGIN
    SELECT RAISE(ABORT, 'send-delay operation limit reached');
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
    OR NEW.due_timestamp_ms != OLD.due_timestamp_ms
    OR NEW.armed_wall_timestamp_ms != OLD.armed_wall_timestamp_ms
    OR NEW.armed_elapsed_realtime_ms != OLD.armed_elapsed_realtime_ms
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR NOT (
        (OLD.phase_code = 'pending_v1' AND NEW.phase_code = 'dispatching_v1'
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
