<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Phase 6 feature and privacy plan

Status: active. Phase 5A through 5G are implemented and locally accepted. This
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

### Phase 6E — local spam and blocking

Implement bounded explainable rules, trust contacts by default, warn/highlight
before any auto-hide policy, and provide spam/not-spam plus block/unblock. Never
delete suspected spam and never use network reputation.

### Phase 6F — voice memo

Request microphone permission only after an explicit Record action. Keep a
visible recording indicator, hard duration/size limits, app-private temporary
files, cancellation cleanup, and one audited MMS attachment handoff.

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
