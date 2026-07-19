# ADR 0013: Durable permanent provider deletion with a short Undo boundary

- Status: accepted
- Date: 2026-07-19
- Scope: Phase 5F exact message and whole-Thread deletion

## Context

AuroraSMS needs permanent deletion without inventing a recycle bin or implying
that provider data can be recovered after Android accepts a delete. A process
can die between confirmation, provider mutation, local draft cleanup, and index
refresh. Provider row IDs alone are insufficient authority for replaying a
destructive action.

## Decision

AuroraSMS exposes two destructive paths:

- one SMS/MMS row, selected by its provider kind, row ID, Thread ID, and the
  exact projection fingerprint already held by the private index; and
- one provider Thread, selected by its Thread ID plus SMS/MMS row counts and
  latest row IDs captured at confirmation.

Message deletion requires an explicit confirmation. Whole-Thread deletion uses
two distinct confirmations and states that locked messages and the current
unsent draft are included. Both paths first create one bounded, content-free
Room operation with a fixed five-second `PENDING` window. A process-local timer
is the normal wake-up; a private alarm containing only the local deletion ID is
the process-death fallback. `Undo` removes only `PENDING` or safe review state.

At the boundary, AuroraSMS revalidates role, local conflicts, exact draft
revision for a Thread, clock continuity, lateness, and provider identity before
moving durably to `COMMITTING`. The provider target is re-read immediately
before mutation. A changed target pauses for review without deleting. Whole-
Thread provider deletion uses Android's combined conversation URI so SMS and
MMS are deleted as one provider operation.

`COMMITTING` recovery never replays a delete. It only inspects the exact target:

- absent means the requested provider deletion completed, so Aurora atomically
  clears only the snapshotted draft revision and removes the operation;
- unchanged becomes `REVIEW_REQUIRED` and is safely cancelled when a later
  inspection still proves the item exists; and
- changed or unavailable remains reviewable without mutation.

After a successful provider delete, the operation is removed and the UI leaves
the Thread or reloads the message window. No UI claims restoration. There is no
recycle-bin table, preference, worker, route, or screen.

## Physical constraints

Room schema 10 stores only provider identifiers, counts, fingerprints, exact
draft identity, timestamps, and lifecycle codes. SQLite triggers enforce a
128-row maximum, fixed five-second window, valid target shape, immutable
bindings, monotonic revisions, and the approved state transitions. Message
body, subject, address, contact label, attachment URI, and SIM label are never
stored in deletion operations or alarm intents.

## Consequences

- Undo is truthful only before provider commit ownership.
- Incoming/outgoing provider changes during the window cause a safe review,
  not deletion of a changed target.
- A newer draft is preserved even after the provider Thread is deleted.
- Clock changes, reboot lateness, lost role, storage conflict, and alarm failure
  stop before provider mutation.
- Automated acceptance uses synthetic provider state only. It does not delete
  the user's live messages or close physical/OEM lifecycle gates.
