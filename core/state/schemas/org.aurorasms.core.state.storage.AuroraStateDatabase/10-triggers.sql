-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. Schema 10 adds the bounded,
-- content-free permanent provider-deletion lifecycle; the factory reinstalls
-- all triggers from prior schemas.

CREATE TRIGGER IF NOT EXISTS permanent_deletion_enforce_limit_insert
BEFORE INSERT ON permanent_deletion_operations
WHEN (SELECT COUNT(*) FROM permanent_deletion_operations) >= 128
BEGIN
    SELECT RAISE(ABORT, 'permanent-deletion operation limit reached');
END;

CREATE TRIGGER IF NOT EXISTS permanent_deletion_enforce_integrity_insert
BEFORE INSERT ON permanent_deletion_operations
WHEN NEW.provider_thread_id <= 0
    OR NEW.created_timestamp_ms > 9223372036854770807
    OR NEW.due_timestamp_ms != NEW.created_timestamp_ms + 5000
    OR NEW.phase_code != 'pending_v1'
    OR NEW.review_reason_code IS NOT NULL
    OR NEW.armed_wall_timestamp_ms != NEW.created_timestamp_ms
    OR NEW.armed_elapsed_realtime_ms < 0
    OR NEW.created_timestamp_ms < 0
    OR NEW.updated_timestamp_ms != NEW.created_timestamp_ms
    OR NOT (
        (NEW.target_kind_code = 'message_v1'
            AND NEW.provider_kind IN (1, 2)
            AND NEW.provider_message_id > 0
            AND length(NEW.sync_fingerprint) = 64
            AND NEW.sync_fingerprint NOT GLOB '*[^0-9a-f]*'
            AND NEW.sms_count IS NULL
            AND NEW.latest_sms_id IS NULL
            AND NEW.mms_count IS NULL
            AND NEW.latest_mms_id IS NULL
            AND NEW.draft_id IS NULL
            AND NEW.draft_revision_ms IS NULL)
        OR (NEW.target_kind_code = 'thread_v1'
            AND NEW.provider_kind IS NULL
            AND NEW.provider_message_id IS NULL
            AND NEW.sync_fingerprint IS NULL
            AND NEW.sms_count >= 0
            AND NEW.mms_count >= 0
            AND NEW.sms_count + NEW.mms_count > 0
            AND ((NEW.sms_count = 0 AND NEW.latest_sms_id IS NULL)
                OR (NEW.sms_count > 0 AND NEW.latest_sms_id > 0))
            AND ((NEW.mms_count = 0 AND NEW.latest_mms_id IS NULL)
                OR (NEW.mms_count > 0 AND NEW.latest_mms_id > 0))
            AND ((NEW.draft_id IS NULL AND NEW.draft_revision_ms IS NULL)
                OR (NEW.draft_id > 0 AND NEW.draft_revision_ms >= 0)))
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid permanent-deletion reservation');
END;

CREATE TRIGGER IF NOT EXISTS permanent_deletion_enforce_integrity_update
BEFORE UPDATE ON permanent_deletion_operations
WHEN NEW.deletion_id != OLD.deletion_id
    OR NEW.target_kind_code != OLD.target_kind_code
    OR NEW.provider_thread_id != OLD.provider_thread_id
    OR NEW.provider_kind IS NOT OLD.provider_kind
    OR NEW.provider_message_id IS NOT OLD.provider_message_id
    OR NEW.sync_fingerprint IS NOT OLD.sync_fingerprint
    OR NEW.sms_count IS NOT OLD.sms_count
    OR NEW.latest_sms_id IS NOT OLD.latest_sms_id
    OR NEW.mms_count IS NOT OLD.mms_count
    OR NEW.latest_mms_id IS NOT OLD.latest_mms_id
    OR NEW.draft_id IS NOT OLD.draft_id
    OR NEW.draft_revision_ms IS NOT OLD.draft_revision_ms
    OR NEW.due_timestamp_ms != OLD.due_timestamp_ms
    OR NEW.armed_wall_timestamp_ms != OLD.armed_wall_timestamp_ms
    OR NEW.armed_elapsed_realtime_ms != OLD.armed_elapsed_realtime_ms
    OR NEW.created_timestamp_ms != OLD.created_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR NOT (
        (OLD.phase_code = 'pending_v1'
            AND NEW.phase_code = 'committing_v1'
            AND NEW.review_reason_code IS NULL)
        OR (OLD.phase_code IN ('pending_v1', 'committing_v1')
            AND NEW.phase_code = 'review_required_v1'
            AND NEW.review_reason_code IN (
                'clock_changed_v1',
                'missed_after_restart_v1',
                'target_changed_v1',
                'precondition_failed_v1',
                'arming_failed_v1',
                'interrupted_during_commit_v1'
            ))
    )
BEGIN
    SELECT RAISE(ABORT, 'invalid permanent-deletion transition');
END;
