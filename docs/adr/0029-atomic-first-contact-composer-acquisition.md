<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0029: atomic first-contact composer acquisition

- Status: Accepted for the synthetic N2C durable-authority checkpoint
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

The synthetic sender-admission checkpoint adds one alternate authority source
to `ThreadSmsSendCommand`. It accepts the exact provider thread, validated
`RecipientSet`, and `ComposerSmsFirstContactAuthority` without fabricating a
`VerifiedConversationIdentity` for a new empty thread. Before reservation, the
existing coordinator requires one SMS recipient, empty attachment evidence,
the exact semantic participant fingerprint, the selected active SMS-capable
subscription, and the SMS role. It does not query the conversation index or a
conversation subscription preference for this exact first-contact branch.

The coordinator passes the authority into the same composer reservation above,
then uses the existing provider staging, callbacks, transport, classification,
and recovery unchanged. MMS remains refused until its authority evidence comes
from the exact outgoing attachment bytes.

The durable-authority checkpoint returns the exact
`ComposerSmsFirstContactAuthority` from each persisted `HANDOFF_RESERVED`
result, including after coordinator recreation, without resolving the provider
thread again. This result is still transient input: the composer reservation
must transactionally reread and compare the durable operation.

This checkpoint still does not expose New chat Send, construct a production
`FirstContactSmsAdmission`, or install the Android thread resolver in the
production route graph.

## Verification

The state checkpoint tests exact transfer, process reopen, every immutable
authority/request mismatch, attachment-only drift, a conflicting thread owner,
composer-cap ownership preservation, and fault-injected post-insert rollback.
Migration coverage proves schema-15 `HANDOFF_RESERVED` data survives while the
upgraded trigger rejects a naked or mismatched delete and permits the exact
paired composer transfer. The rollback test uses a test-only SQLite
`RAISE(IGNORE)` delete trigger; production has no fault hook.

Sender tests prove exact SMS authority skips the conversation index and enters
the existing sender once, participant/MMS mismatch stops before reservation,
ambiguous subscription and stale durable authority produce zero transport,
duplicate admission cannot submit twice, and an ambiguous committed
reservation uses existing non-sending recovery.

Ownership tests prove fresh and recreated handoff results expose the same exact
persisted operation revision, participant fingerprint, and attachment evidence
without a second provider-resolution call; mismatched commands expose no
handoff authority and result logging remains redacted.

## Consequences

First-contact ownership can cross into the existing composer journal without a
second send implementation or an ambiguous crash window. Explicit
`KNOWN_UNSENT` first-contact retry/release, multi-SIM review, UI activation, and
physical provider/carrier acceptance remain separate work. AuroraSMS is not
gold.
