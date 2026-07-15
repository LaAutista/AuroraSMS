-- SPDX-License-Identifier: GPL-3.0-or-later
-- Room schema JSON does not represent triggers. StateDatabaseFactory installs
-- these deterministic version-4 invariants on both create and open. The
-- explicit migrations install the singleton triggers when their tables appear.

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
