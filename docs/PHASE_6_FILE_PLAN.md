<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Phase 6 feature and privacy plan

Status: active. Phase 5A through 5G and Phase 6A through 6E are implemented and
locally accepted. This
plan sequences the remaining user-facing work into reviewable slices; it does
not declare AuroraSMS complete or gold.

## Shared boundaries

- Android's SMS/MMS provider remains the message authority. Presentation
  features never rewrite provider bodies to add reactions, quotes, spam labels,
  signatures, or reminder state.
- All derived state is local, bounded, purpose-specific, excluded from OS/cloud
  backup, and content-minimized where possible.
- No Phase 6 feature adds `INTERNET`, analytics, accounts, remote reputation,
  remote media search, or silent deletion.
- Actions that record, export, import, send, hide, or block require a visible
  user decision and exact failure behavior.
- Every slice must pass host tests, API 26/API 36 emulator acceptance, privacy
  and permission gates, R8 release assembly, and a safe physical handoff when a
  device is available. Carrier actions require a separate destination-aware
  protocol.

## Slice order

### Phase 6A — conservative reaction fallback presentation — complete

Parse only exact, bounded, well-known English SMS fallback forms such as
`Liked “…”` and `Removed a like from “…”`. Render a local structured reaction
card only when the complete visible body is an unambiguous match. Truncated,
malformed, multiline, unknown, or oversized text remains the original raw SMS.
The stored/indexed/provider body is never changed.

### Phase 6B — selected-text copy and message details — complete

Provide explicit text selection and copy while keeping destructive long-press
actions accessible. Copy only the selected range, never the whole conversation,
sender metadata, hidden subject, or attachment path. Details remain local and
bounded.

### Phase 6C — local notification reminders — complete

Use a content-free durable reminder owner and private ID-only alarm. Revalidate
unread/provider state at fire time; cancellation, read state, reboot, clock
change, and role loss fail closed. Do not wake repeatedly or create an
unbounded alarm set.

### Phase 6D — global and per-conversation signatures — complete

Store bounded signature settings separately from drafts. Show exact segment or
MMS impact before send, freeze the resolved signature with the durable send
owner, and never silently turn a group into SMS or a one-part text into an
unacknowledged multipart submission.

Acceptance passed on 2026-07-19: 578 host tests, the complete 886-task offline
aggregate, 332 API 36 tests, and 335 API 26 tests all completed with zero
failures or errors. The exact debug APK was installed and hash-matched on the
Pixel 8 and API 36 emulator without changing either SMS role, launching the
app, reading live content, or submitting carrier traffic.

### Phase 6 history follow-up — role-resumable initial scan — complete

Physical content-free inspection found that repeated default-app switching had
left four partial generations and caused the newest incomplete generation to
expose fewer conversation projections than the rebuildable cache retained.
Role transitions now pause/resume a clean checkpoint without falsely marking a
provider mutation. Actual content-observer and external-provider signals remain
dirty. Inbox and Thread use a prominent committed-row progress notice that says
conversations and older messages are missing until verification finishes.

Acceptance passed on 2026-07-19: 579 host tests, the complete 886-task offline
aggregate, 333 API 36 tests, and 336 API 26 tests all completed with zero
failures or errors. The version-16 APK installed and hash-matched on the Pixel 8
and API 36 emulator without changing either role, launching the app, reading
live content, or submitting carrier traffic. Physical complete-history evidence
still requires the owner's explicit default-SMS approval.

### Phase 6 history follow-up — interrupted-refresh cache presentation — complete

The physical report confirmed the remaining presentation defect: the newest
partial generation exposed only 2,100 rows while 5,226 messages and 73
conversation projections remained in Aurora's private cache. ADR 0020 makes an
incomplete Inbox and Thread page across all best-known retained generations,
with explicit stale-cache disclosure. Exact-identity actions remain unavailable
until verified completion; a complete generation returns to strict generation
queries and atomically removes stale rows. No provider read, role change,
permission, network path, carrier action, or schema migration is introduced.

Focused acceptance passed on API 26 and API 36. Full aggregate, artifact, and
physical handoff evidence is recorded in `docs/TEST_MATRIX.md`.

### Phase 6E — local spam and blocking — complete

Implement bounded explainable rules, trust contacts by default, warn/highlight
before any auto-hide policy, and provide spam/not-spam plus block/unblock. Never
delete suspected spam and never use network reputation.

ADR 0019 fixes the first safe policy at warn-only: automatic warnings require
an unknown conventional phone sender plus a link, urgency term, and sensitive
request term. Rules pause if contact trust cannot be verified. Explicit blocks
suppress only Aurora notification/reply/reminder
effects after provider storage; failures fail open and provider messages remain
visible. Room schema 12 stores at most 256 purpose-separated, address-free user
decisions and the Spam & blocked route revalidates exact identities before
display or recovery.

Acceptance passed on 2026-07-19: 587 host tests, the complete 886-task offline
aggregate, 339 API 36 tests, and 342 API 26 tests all completed with zero
failures or errors. Release bundle and deterministic CycloneDX 1.6 SBOM
generation passed. No live message/address/body was inspected and no carrier
traffic was submitted.

### Phase 6F — voice memo — implementation and aggregate acceptance complete

Request microphone permission only after an explicit Record action. Keep a
visible recording indicator, hard duration/size limits, app-private temporary
files, cancellation cleanup, and one audited MMS attachment handoff.

ADR 0021 admits a pinned twelve-file Apache-2.0 outgoing `SendReq` composer
subset from official AOSP and no incoming parser, APN/network client, or
messaging-app source. `0.6.8-phase6` records MPEG-4/AAC-LC for at most 60 seconds
and 512 KiB under `noBackupFilesDir`, requires a separate review/Send action,
and cancels capture or review state on Thread/background lifecycle.

The one-person provider path writes parts first, verifies one exact
creator/thread/transaction-bound FAILED row, and crosses the platform boundary
only after an exact applied OUTBOX transition. A checksummed content-free
journal quarantines ambiguous submission state and authenticates the exact
callback before provider mutation. Focused golden/corpus, journal/recovery,
callback, fake-provider, UI, and real virtual-microphone tests pass across API
26/API 36 where applicable. No live provider content or carrier traffic is part
of this acceptance; group/general MMS and carrier/OEM verification remain open.

Acceptance passed on 2026-07-19: 601 host tests, the complete 888-task offline
aggregate, and 362 connected tests on each of API 26 and API 36 completed with
zero failures or errors. Release bundle and CycloneDX 1.6 SBOM generation
passed. The exact debug APK installed and hash-matched on both emulators while
preserving their non-Aurora SMS role. The Pixel was unreachable after the editor
crash, so the Phase 6F physical install and carrier/OEM journeys remain open.

### Phase 6G — streaming authenticated backup and restore

Finalize encryption/authentication and recovery policy before implementation.
Validate version, entry count, size, normalized paths, checksums, schema, and
media before an atomic import. Export/import is explicit and never enables OS
or cloud backup.

### Phase 6H — Android Auto and notification completion

Verify metadata, privacy modes, grouping, direct reply, failure alerts, channel
behavior, lockscreen/OEM behavior, and Android Auto without weakening durable
reply ownership or group identity.

## Stop conditions

Stop a slice if it requires hidden network access, provider-body mutation,
unbounded memory/storage/alarm work, silent transport fallback, automatic
destructive action, fabricated recovery, or a real carrier send without the
separate approved protocol.
