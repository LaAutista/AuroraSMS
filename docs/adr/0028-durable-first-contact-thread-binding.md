<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0028: durable first-contact thread binding

- Status: Accepted for the synthetic N2B checkpoint
- Date: 2026-07-22

## Context

AuroraSMS can retain a New chat draft under a bounded participant-set identity,
but the existing composer journal starts from an already authoritative
`ProviderThreadId`. Inventing a thread ID, resolving one before durable user
intent exists, or treating `Telephony.Threads.getOrCreateThreadId` as a
read-only lookup would leave a crash window in which provider mutation can be
repeated without an Aurora-owned record.

The existing draft participant key preserves exact validated address strings;
it deliberately does not claim equivalent phone-number formatting. New chat
also needs one active operation for semantically equivalent recipient sets,
without copying message content, contact names, queries, photos, or raw
addresses into a second durable record.

N2B is a synthetic ownership checkpoint. It must prove the state and authority
boundaries before any New chat Send control, live provider allocation, provider
message write, callback registration, or carrier submission is enabled.

## Decision

AuroraSMS adds a separate, content-free `FirstContactOperation` in state schema
15. It binds a bounded local operation ID to the exact participant-draft ID and
revision, a purpose-separated SHA-256 fingerprint of the semantic recipient
set, an explicitly selected active SMS-capable subscription, an optional
write-once provider thread, and monotonic operation timestamps. The table is
bounded to 128 rows. It stores no raw recipient, body, subject, attachment
content, query, contact label, or photo reference. As in the mature composer
journal, an optional bounded frozen signature is the sole user-authored text in
the operation; freezing it prevents a later preference change from altering the
accepted action, and it remains private, redacted, and excluded from exports.

Recipient equivalence is shared by `core:model` and `RecipientSet`: phone-like
values compare after removing supported presentation punctuation while
preserving a leading plus sign, email domains compare case-insensitively, and
other valid addresses remain exact. Type prefixes and sorted keys make the
first-contact fingerprint deterministic and prevent order or harmless phone
formatting from creating sibling reservations. The operation fingerprint uses
a first-contact-only domain separator and does not replace the exact draft
identity or revision check.

The persisted phases are fail-closed:

1. `RESERVED` records exact local intent before provider authority is entered.
2. `RESOLUTION_STARTED` is durably compare-and-set before an allocator may run.
3. `THREAD_BOUND` records one positive, exactly verified provider thread once.
4. `HANDOFF_RESERVED` records that the same draft was transactionally rekeyed
   to that provider thread at a strictly newer draft revision.
5. `RESOLUTION_UNKNOWN` stops recovery when provider allocation was entered but
   its result could not be classified.
6. `KNOWN_UNSENT` is a terminal statement available only from `RESERVED`: no
   provider-resolution or message-transport boundary was entered.

Transitions use exact operation revisions and reject backwards movement,
thread rebinding, subscription substitution, stale drafts, corrupt phase and
binding combinations, and duplicate semantic recipient ownership. Recovery
never repeats resolution from `RESOLUTION_STARTED` or `RESOLUTION_UNKNOWN`.
This checkpoint has no transition that submits a message or registers a
callback.

Attachment replacement currently uses the draft revision as its compare-and-
set base without advancing that revision. Reservation therefore records a
separate, first-contact-domain digest of the exact ordered attachment set,
including the empty set, and the bridge recomputes it. This is evidence only;
attachment bytes and types remain in the draft-owned attachment table.

The `THREAD_BOUND` to `HANDOFF_RESERVED` bridge is one Room transaction. It
re-reads the operation and exact source draft identity/revision, rejects an
existing provider-thread draft, changes the identity of the same draft ID,
strictly advances its revision, and records that bound revision on the
operation. Body, subject, creation time, and attachment rows remain owned by
the same draft. Any conflict leaves all owners unchanged. N2B does not yet
insert a composer operation: N2C must adjudicate the exact handoff to the
existing composer journal before exposing transport.

`ProviderThreadResolver` accepts only a validated bounded `RecipientSet` and
returns redacted typed outcomes. A successful result requires a positive
thread ID and an exact, bounded provider readback of its canonical recipients.
Role or `READ_SMS` denial before allocator entry performs no provider call.
Nevertheless, once Aurora has durably entered `RESOLUTION_STARTED`, every
non-verified resolver result and every thrown cancellation or exception is
classified as unknown; no caller or freely constructible proof can move that
checkpoint to retryable `KNOWN_UNSENT`. The Android implementation exists
behind injected allocator/readback seams but is deliberately not wired to New
chat or the production application graph in N2B.

Subscription validation retains the typed errors from active-subscription
enumeration. The exact selected ID must remain active and SMS-capable before
reservation and at every later authority boundary; Aurora never silently
substitutes the platform default SIM. Provider thread identity remains solely a
function of participants, not subscription choice.

The headless first-contact coordinator composes these contracts with role and
subscription revalidation. It has no dependency on `MessageTransport`, the
existing thread-send controller, provider-message staging, or callbacks. The
New chat UI and external compose-review flow do not invoke it; their Send
control remains disabled.

## Verification

Contract, Room migration/reopen, repository, resolver-fake, and coordinator
tests cover bounded storage, content-free rows, semantic sibling conflicts,
stale revisions, compare-and-set ordering, cancellation and authority loss,
subscription churn, one-time thread binding, draft/attachment preservation,
transactional sibling conflict, and zero transport. API 26 and API 36 runs use
synthetic fixtures only. No live provider thread, role mutation, permission
grant, SMS/MMS submission, callback, or carrier send participates in this
checkpoint.

## Consequences

AuroraSMS gains crash-auditable ownership from a participant draft through an
exact provider-thread binding without weakening the mature existing-thread send
journal. The additional schema and terminal unknown state are deliberate costs
of refusing to infer provider mutation outcomes. First-contact Send, explicit
multi-SIM UI, the composer-journal handoff, physical provider verification, and
carrier SMS/MMS acceptance remain N2C work. AuroraSMS is not gold.
