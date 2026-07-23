-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. Schema 12 adds the bounded,
-- content-free spam classification and sender-block decision table. The state
-- factory reinstalls these and every prior trigger on create and open.

CREATE TRIGGER IF NOT EXISTS spam_safety_decisions_enforce_limit_insert
BEFORE INSERT ON spam_safety_decisions
WHEN (SELECT COUNT(*) FROM spam_safety_decisions) >= 256
BEGIN
    SELECT RAISE(ABORT, 'spam-safety decision limit reached');
END;

CREATE TRIGGER IF NOT EXISTS spam_safety_decisions_enforce_integrity_insert
BEFORE INSERT ON spam_safety_decisions
WHEN length(NEW.participant_set_key) != 74
    OR substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:'
    OR substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*'
    OR NEW.provider_thread_id <= 0
    OR NEW.classification_code NOT IN ('neutral_v1', 'spam_v1', 'not_spam_v1')
    OR NEW.blocked NOT IN (0, 1)
    OR (NEW.single_sender_key IS NOT NULL AND (
        length(NEW.single_sender_key) != 74
        OR substr(NEW.single_sender_key, 1, 10) != 'sha256-v1:'
        OR substr(NEW.single_sender_key, 11) GLOB '*[^0-9a-f]*'))
    OR (NEW.blocked = 1 AND NEW.single_sender_key IS NULL)
    OR (NEW.classification_code = 'neutral_v1' AND NEW.blocked = 0)
    OR NEW.revision != 1
    OR NEW.updated_timestamp_ms < 0
BEGIN
    SELECT RAISE(ABORT, 'invalid spam-safety decision');
END;

CREATE TRIGGER IF NOT EXISTS spam_safety_decisions_enforce_integrity_update
BEFORE UPDATE ON spam_safety_decisions
WHEN NEW.participant_set_key != OLD.participant_set_key
    OR NEW.provider_thread_id <= 0
    OR NEW.classification_code NOT IN ('neutral_v1', 'spam_v1', 'not_spam_v1')
    OR NEW.blocked NOT IN (0, 1)
    OR (NEW.single_sender_key IS NOT NULL AND (
        length(NEW.single_sender_key) != 74
        OR substr(NEW.single_sender_key, 1, 10) != 'sha256-v1:'
        OR substr(NEW.single_sender_key, 11) GLOB '*[^0-9a-f]*'))
    OR (NEW.blocked = 1 AND NEW.single_sender_key IS NULL)
    OR (NEW.classification_code = 'neutral_v1' AND NEW.blocked = 0)
    OR NEW.revision != OLD.revision + 1
    OR NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms
BEGIN
    SELECT RAISE(ABORT, 'invalid spam-safety transition');
END;
