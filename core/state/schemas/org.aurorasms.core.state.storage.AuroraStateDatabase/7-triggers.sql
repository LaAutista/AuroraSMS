-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory continues
-- to install every invariant recorded for schemas 1 through 6. Schema 7 adds
-- these content-free, purpose-separated conversation SIM preference invariants;
-- migration 6-to-7 installs them when the owning table appears.

CREATE TRIGGER IF NOT EXISTS conversation_subscription_preferences_enforce_integrity_insert
BEFORE INSERT ON conversation_subscription_preferences
WHEN length(NEW.participant_set_key) != 74
    OR substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:'
    OR substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*'
    OR NEW.provider_thread_id <= 0
    OR NEW.subscription_id < 0
    OR NEW.revision != 1
    OR NEW.updated_timestamp_ms < 0
BEGIN
    SELECT RAISE(ABORT, 'invalid conversation subscription preference');
END;

CREATE TRIGGER IF NOT EXISTS conversation_subscription_preferences_enforce_integrity_update
BEFORE UPDATE ON conversation_subscription_preferences
WHEN NEW.participant_set_key != OLD.participant_set_key
    OR NEW.provider_thread_id <= 0
    OR NEW.subscription_id < 0
    OR NEW.revision != OLD.revision + 1
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid conversation subscription transition');
END;
