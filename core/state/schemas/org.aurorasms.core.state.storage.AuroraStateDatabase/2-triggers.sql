-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory installs
-- these deterministic version-2 invariants on both create and open. The
-- explicit 1-to-2 migration also installs the appearance singleton triggers.

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
