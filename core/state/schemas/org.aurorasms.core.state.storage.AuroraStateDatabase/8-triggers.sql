-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. Schema 8 adds content-free
-- scheduled-SMS lifecycle constraints; the factory reinstalls all prior triggers.

CREATE TRIGGER IF NOT EXISTS scheduled_sms_operations_enforce_limit_insert
BEFORE INSERT ON scheduled_sms_operations
WHEN (SELECT COUNT(*) FROM scheduled_sms_operations) >= 128
BEGIN
    SELECT RAISE(ABORT, 'scheduled SMS operation limit exceeded');
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
        (OLD.phase_code NOT IN ('pending_v1','dispatching_v1') OR NEW.review_reason_code IS NULL))
    OR NEW.phase_code NOT IN ('pending_v1','dispatching_v1','review_required_v1')
BEGIN
    SELECT RAISE(ABORT, 'invalid scheduled SMS transition');
END;
