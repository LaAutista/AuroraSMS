<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0012: durable short send delay and Undo

- Status: accepted on 2026-07-19
- Scope: Phase 5E verified one-person, one-unit Thread SMS

## Context

Undo Send must prevent submission rather than claim that a carrier submission
can be recalled. A process-local timer alone loses ownership when Android kills
the process, while blindly dispatching an old delayed operation after reboot or
a clock change can surprise the user. The existing composer already has an
exact durable draft and a crash-safe sender; the delay layer must not create a
second message-content store or weaken that sender.

## Decision

The user may select immediate sending, or a 1, 3, 5, or 10 second delay. Zero
seconds remains the default. A delayed action first freezes the exact durable
draft and atomically creates one bounded Room operation binding only its local
ID, purpose-separated participant digest, provider Thread, draft ID/revision,
selected subscription, due instant, phase, clock anchors, and timestamps. The
delay table stores no body, subject, address, contact name, or SIM label.

The normal wake-up is a process-local coroutine. A private explicit immutable
alarm containing only the operation ID is a process-death recovery wake-up. An
exact alarm is used when available; an inexact alarm remains safe because the
window is a user-experience delay, not a deadline promise. Failure to arm leaves
the operation and frozen draft in visible review state.

Only `PENDING` can atomically become `DISPATCHING`. Undo deletes `PENDING` or
`REVIEW_REQUIRED`, cancels both wake-ups, and unfreezes the same draft. Undo is
never offered after `DISPATCHING`, because transport ownership may already have
transferred. Duplicate alarms lose the compare-and-set race and cannot send a
second time.

At dispatch, Aurora rechecks wall-versus-elapsed clock continuity, maximum
lateness, verified one-person identity, default-SMS role, exact remembered
active SMS-capable subscription, exact draft revision, and one-unit eligibility
before entering the existing durable sender. A continuous restart may dispatch
up to 30 seconds late. Reboot, clock discontinuity, greater lateness, lost role,
removed SIM, stale draft, arming failure, or interrupted handoff pauses for
review and never automatically retries.

After `DISPATCHING`, reconciliation trusts only the exact matching durable
composer operation. A missing draft means the existing sender completed and the
delay owner is removed. A mismatched/missing handoff with a retained draft
becomes review-required. Ambiguous exceptions after durable dispatch ownership
are treated as started and reconciled; they are never converted into a retry.

## Consequences

- The UI truthfully offers Undo only before submission.
- Process death cannot silently drop or duplicate the short-delay owner.
- The exact draft remains the only durable message-content authority.
- The state database advances to schema 9 with physical count, shape, and
  lifecycle triggers.
- No new permission, dependency, exported component, network path, worker, or
  background retry is introduced.
- Automated tests use synthetic content and do not send a carrier SMS.

## Rejected alternatives

- Snackbar-only/process-local Undo: loses deterministic ownership on process
  death.
- Deleting a carrier/provider row after submission: not a reliable recall and
  would misrepresent delivery state.
- Storing body or recipient in the delay row/alarm: duplicates sensitive state
  and expands exposure.
- Automatic dispatch after reboot or a large clock jump: violates user intent.
- Reusing scheduled-message rows: conflates a short UX grace period with a
  user-selected future schedule and weakens both lifecycle contracts.
