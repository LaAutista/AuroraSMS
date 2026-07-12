-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory installs
-- these deterministic version-1 invariants on both create and open.

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
