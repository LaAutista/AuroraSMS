-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory continues
-- to install every unchanged version-5 invariant recorded in 5-triggers.sql.
-- Schema 6 adds these deterministic acknowledged-callback receipt invariants;
-- migration 5-to-6 installs them when the owning table appears.

CREATE TRIGGER IF NOT EXISTS acknowledged_composer_sms_receipts_enforce_limit_insert
BEFORE INSERT ON acknowledged_composer_sms_receipts
WHEN (SELECT COUNT(*) FROM acknowledged_composer_sms_receipts) >= 128
BEGIN
    SELECT RAISE(ABORT, 'acknowledged composer SMS receipt limit reached');
END;

CREATE TRIGGER IF NOT EXISTS acknowledged_composer_sms_receipts_enforce_integrity_insert
BEFORE INSERT ON acknowledged_composer_sms_receipts
WHEN NEW.local_operation_id <= 0
    OR NEW.provider_message_id <= 0
    OR NEW.provider_conversation_id <= 0
    OR NEW.unit_count < 1
    OR NEW.unit_count > 1
    OR NEW.callback_proof_code != 'awaiting_callback_v1'
    OR NEW.acknowledged_timestamp_ms < 0
    OR NEW.updated_timestamp_ms != NEW.acknowledged_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid acknowledged composer SMS receipt');
END;

CREATE TRIGGER IF NOT EXISTS acknowledged_composer_sms_receipts_enforce_integrity_update
BEFORE UPDATE ON acknowledged_composer_sms_receipts
WHEN NEW.local_operation_id != OLD.local_operation_id
    OR NEW.provider_message_id != OLD.provider_message_id
    OR NEW.provider_conversation_id != OLD.provider_conversation_id
    OR NEW.unit_count != OLD.unit_count
    OR NEW.acknowledged_timestamp_ms != OLD.acknowledged_timestamp_ms
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
    OR OLD.callback_proof_code != 'awaiting_callback_v1'
    OR NEW.callback_proof_code NOT IN ('sent_v1', 'failed_v1')
BEGIN
    SELECT RAISE(ABORT, 'invalid acknowledged composer SMS transition');
END;
