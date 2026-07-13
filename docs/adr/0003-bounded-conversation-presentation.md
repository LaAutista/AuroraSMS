# ADR 0003: bounded conversation presentation and index projections

Status: accepted as the Phase 3 implementation control, 2026-07-13

## Context

Phase 2 provides a resumable complete-history message index, safe FTS search,
and bounded exact-anchor queries. It deliberately does not provide an inbox or
a conversation UI. An inbox cannot be implemented safely by grouping the full
message table at render time, and collecting all messages for a thread would
make a 250,000-message conversation an unbounded database and memory result.

The version-1 index also flattens approved participant text for search. That
text is not a trustworthy identity source and must never be parsed back into
participant addresses. Telephony remains authoritative for messages, while the
index remains a disposable application-private projection.

Phase 3 must therefore add presentation-specific projections and bounded read
contracts without weakening Phase 2 search, index integrity, privacy, or role
and permission gates. It must also preserve a reader's visible position while
older pages or incoming messages are incorporated.

## Decision

### Separate presentation projection

The index advances to schema version 2 and adds:

- `indexed_conversations`, with one row per positive provider thread and the
  latest message metadata, bounded snippet, indexed message/unread counts,
  participant-truncation flag, and generation state required for deterministic
  inbox ordering; and
- `indexed_conversation_participants`, with typed, validated participant
  addresses keyed by provider thread and generation.

Inbox order is `(latest_timestamp_ms DESC, latest_row_id DESC)`. The index has
an explicit index supporting that order. Presentation never reconstructs a
conversation by grouping the complete message table and never parses identity
from FTS text.

Message rows, summaries, participants, checkpoints, and generation progress are
maintained transactionally. `IndexedMessageDao` owns the whole scanning/head
write transaction; the presentation `ConversationDao` is read-only and is
never called sequentially to simulate cross-DAO atomicity. A new descending
generation resets a thread accumulator on its first encountered row and then
increments it once per consumed row, avoiding repeated scans of a 250,000-row
thread. Only verified Aurora-owned head inserts update incrementally. Updates,
deletions, thread moves, or ambiguous signals force a fresh generation, which
rebuilds summaries and participant sets. `IndexSyncDao` owns stale deletion,
canonical consistency work, and the final complete marker in one transaction.

The version-1-to-version-2 migration creates empty presentation tables without
destroying existing messages or FTS rows. It dirties every v1 COMPLETE,
SCANNING, VERIFYING, PAUSED, and FAILED path so no old checkpoint can resume and
claim typed-participant completeness; only a new full verified generation can
complete v2. Durable state remains in its separate version-1 database and is not
part of this migration. Fault tests cover both sides of every transaction.

### UI-neutral bounded read contracts

The existing `MessageIndex` search and exact-anchor API remains compatible.
Phase 3 adds separate `ConversationRepository` and
`ThreadTimelineRepository` contracts. They expose immutable presentation
projections rather than DAOs, Room entities, providers, or database handles.

All public page requests have a default of 50 and a maximum of 100. Queries
read at most `limit + 1`, remove the sentinel internally, and return typed
continuation cursors. Inbox cursors contain generation, latest timestamp, and
local row ID. Thread cursors contain generation, provider thread, timestamp,
and local row ID. Each read transaction captures generation/coverage and rows
in one snapshot; another generation returns a typed stale result.

Queries select only fields required for display. They omit searchable text,
fingerprints, generation bookkeeping, attachment bytes, and raw provider data.
Timeline bodies are capped to a 16,384-character preview; an explicit bounded
single-message read owns expansion. Participant previews contain at most eight
addresses. Exact anchors retain the existing 101-row maximum.

Committed partial-generation rows may display with truthful incomplete/failure
coverage. Indexed counts are not presented as provider totals until coverage is
verified. Before participant completeness, titles use a bounded address or
`Unknown conversation`; truncated/partial data never produces a confident group
title. Integrity verification still happens before the database is opened.

`ProviderMessageId`, including its provider kind, is the stable timeline item
identity. The local row ID is an internal cursor and restoration hint, not an
independent message authority. A checked conversion centralizes the intentional
compatibility between the positive Telephony thread ID and Aurora's
`ConversationId` route type.

### Bounded presentation windows

Inbox and thread state holders retain at most 200 rows. Inbox order is newest-
first: older pages append and evict newest only after anchor capture; a new or
reordered row prepends only at the newest boundary and otherwise becomes
bounded pending state. Thread order is chronological: older pages prepend and
evict newest, while newer pages append and evict oldest. Duplicate stable
identities are removed without changing retained order. The thread additionally
retains at most 1,048,576 UTF-16 code units, so the body-preview limit cannot
multiply into an unreviewed text-memory result.

Incoming thread items append and follow only when the reader is already at the
newest boundary. While the reader is away from newest, the visible anchor
remains unchanged and a bounded pending-new-message state exposes an explicit
action.
Jump-to-newest, exact-search jump, and unrecoverable stale-cursor recovery
replace the retained window rather than accumulating pages.

Saved restoration state contains the route, stable compound item identity,
local-row hint, and scroll offset. It never serializes the message window.
Rotation, split-screen recreation, process recreation, notification routing,
search-to-anchor routing, and back therefore re-query one bounded window.

Message content is gated behind default-SMS role and `READ_SMS`. A notification
route may be retained while onboarding is visible, but it cannot render thread
content until eligibility succeeds.

### Contacts, drafts, and attachments

Contact resolution remains optional and outside the index. A 512-entry
application-owned metadata LRU resolves only the viewport plus ten prefetch
rows: at most 50 conversations/400 participant rows per batched index read and
100 unique addresses per refresh or in-flight resolver call. Duplicate in-flight
work is coalesced. A Contacts observer or permission transition clears metadata
and photo references only; denial remains address-only. Conversation models
never contain contact-photo bitmaps.

The existing durable draft table gains typed identity lookup and is exposed
through a deferred repository. Every accepted edit enters one serialized,
conflated writer immediately. UI distinguishes saving from an acknowledged
persisted revision; SavedState retains the newest unacknowledged text and stop
requests a bounded flush, but callbacks are not claimed to survive every abrupt
kill. Tests kill before/after acknowledgement and cover stale revisions,
cancellation, and storage failure. A failure keeps the thread readable,
disables editing with a visible error, and never deletes the state database or
falls back to the index/preferences. Sending remains disabled.

Phase 3 must load an exact visible message's static MMS preview through a
separately bounded platform loader. The allowlist is JPEG, PNG, WebP, GIF, HEIF,
HEIC, and AVIF with decoded-header verification; animation yields one frame.
Encoded input is at most 16 MiB, each source edge 8,192, source pixels 40
million, target edge 2,048, and target pixels 4,194,304. Unknown length uses a
16 MiB + 1 sentinel. API 26-27 uses bounds/sample `BitmapFactory`; API 28+ may
use target-sized software `ImageDecoder`. There are at most two decode jobs and
four/16 MiB retained previews. Descriptors/streams close, lifecycle eviction
drops references without unsafe recycle, and bytes/URIs do not enter timeline
models. Animated playback and third-party decoders remain deferred.

### Performance and verification

Custom keyset reads are used instead of Paging 3 or `room-paging`; no query uses
deep `OFFSET`, `%LIKE%`, or a full-history temporary sort. Query-plan and scale
tests cover 20,000 shallow conversations and one 250,000-message thread.

Debug StrictMode reports only sanitized violation types and counts and must
show no tested main-thread provider, database, file, or decode work. The
existing database benchmark remains separate from a release-equivalent,
profileable Macrobenchmark target. Stable Benchmark 1.4.1 plus direct
ProfileInstaller 1.4.1 use manual `BaselineProfileRule` HRF capture; the AGP 9-
incompatible stable Baseline Profile plugin and its alpha fix are not applied.
The profile covers startup, inbox, thread open/prepend, search, exact jump,
attachment open, and back. A signature-protected benchmark-only provider seeds
fixed synthetic private data and is proven absent from debug/release.

Every latency percentile uses five warmups and 30 measured iterations with
semantic readiness endpoints. Frame evidence uses deadline overrun and fixed
flings; memory uses ten fresh-process PSS samples. Pixel 8/API 36 and a low-
memory target run applicable gates. Physical evidence records warm verified
fixtures separately from cold index open; `PRAGMA quick_check(1)` is not
weakened. Owner-assisted real-provider initial sync retains sanitized counts
only.

## Consequences

- Inbox and timeline cost is proportional to the requested page and retained
  window, not the user's history size.
- Additional disposable rows and synchronization work are accepted to avoid a
  full-message grouping query at presentation time.
- Presentation remains testable with synthetic repositories and contains no
  Android provider or Room dependency.
- A rebuild can invalidate cursors, so callers must handle typed stale results
  by replacing one bounded window.
- Contact names and previews may arrive asynchronously without changing stable
  conversation or message identity.
- Static attachment preview and release/profileable journey evidence are hard
  completion gates for Phase 3, not optional demonstrations.

## Alternatives rejected

### Group the full message table for every inbox read

Rejected because it makes first-paint work depend on complete history and is
pathological for the established large-history fixtures.

### Derive participants from searchable text

Rejected because FTS text is lossy, flattened, and intentionally not an
identity schema.

### Adopt Paging 3 or Room Paging

Rejected for this phase because the reviewed Room path is limit/offset based
and a custom source would add refresh-key and invalidation complexity without
measured benefit over the existing keyset design.

### Retain every loaded page

Rejected because UI memory would grow with scroll depth and make the
250,000-message acceptance case unbounded.

### Force incoming messages into view

Rejected because it destroys the reader's visible anchor while reading older
history.

### Weaken index integrity checks to meet startup targets

Rejected because database integrity must be verified before reads. This does
not prohibit already-committed partial projection rows from displaying with
truthful incomplete coverage. Cold integrity cost and warm presentation latency
are measured as different paths.
