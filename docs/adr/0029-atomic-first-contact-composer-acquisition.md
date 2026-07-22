<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0029: atomic first-contact composer acquisition

- Status: Accepted for the synthetic N2C state checkpoint
- Date: 2026-07-22

## Context

ADR 0028 ends with a content-free `FirstContactOperation` in
`HANDOFF_RESERVED` and the same draft rekeyed to its verified provider thread.
That checkpoint is not permission to send. The mature composer journal must own
the exact draft before provider-message staging or transport can begin.

Creating a composer row and retiring first-contact ownership in separate
transactions would permit process death to leave two owners or no owner. An
ordinary existing-thread reservation must also be unable to bypass a pending
first-contact owner merely because it knows the new provider thread ID.

## Decision

The existing composer reservation is the only downstream owner. Its request may
carry an exact first-contact handoff authority: operation ID, operation revision,
the participant-set fingerprint derived from the current validated recipients,
and evidence derived from the exact attachments being submitted. Ordinary
reservations remain unchanged, but they fail closed if a
first-contact owner exists for the requested draft or provider thread and no
matching authority was supplied.

When that authority is present, `RoomComposerSmsOperationRepository.reserve`
runs one transaction that:

1. re-reads the exact `HANDOFF_RESERVED` operation;
2. matches its participant fingerprint, provider thread, draft ID and handoff
   revision, subscription, transport, and nullable frozen signature;
3. re-reads the provider-thread draft and current ordered attachments, then
   recomputes and matches the stored attachment evidence;
4. rejects any existing composer, scheduled, send-delay, permanent-deletion, or
   mismatched first-contact owner;
5. inserts the ordinary composer `RESERVED` row using the existing journal
   format and authoritative draft content; and
6. deletes only the exact source `HANDOFF_RESERVED` row after that matching
   composer row exists.

Any failed comparison or compare-and-set rolls back the entire transaction. A
repository transfer therefore commits either the first-contact owner or the
composer owner, never both and never neither.

State schema 16 changes no table or column. It replaces only the physical
first-contact delete trigger. `KNOWN_UNSENT` remains directly releasable. A
`HANDOFF_RESERVED` delete is permitted only while the database contains an
unbound composer `RESERVED` row matching the source thread, draft, handoff
revision, subscription, transport, and nullable signature. The trigger prevents
ownerless deletion; the repository transaction separately prevents a committed
dual-owner state.

No extra downstream phase, origin column, callback registry, transport, or
retry journal is added. If the transaction result is lost, the existing
composer recovery owns the committed `RESERVED` row and classifies it as
`KNOWN_UNSENT` without transport. If the transaction did not commit,
`HANDOFF_RESERVED` remains the sole owner.

This checkpoint does not expose New chat Send, install the Android thread
resolver in the production route graph, or bypass the existing conversation
sender. A later N2C slice may add one first-contact authority branch before
composer reservation; all post-reservation staging, callbacks, transport, and
recovery remain the mature composer path.

## Verification

This state checkpoint tests exact transfer, process reopen, missing or stale
authority, participant and attachment-evidence mismatch, attachment-only drift,
a conflicting thread owner, and rollback. Migration coverage proves schema-15
`HANDOFF_RESERVED` data survives while the upgraded trigger rejects a naked or
mismatched delete and permits the exact paired composer transfer. Before UI
activation, the synthetic N2C matrix must also exercise every remaining
immutable-field mismatch, the composer cap, and fault-injected post-insert
rollback.

## Consequences

First-contact ownership can cross into the existing composer journal without a
second send implementation or an ambiguous crash window. Explicit
`KNOWN_UNSENT` first-contact retry/release, first-contact sender admission,
multi-SIM review, UI activation, and physical provider/carrier acceptance remain
separate work. AuroraSMS is not gold.
