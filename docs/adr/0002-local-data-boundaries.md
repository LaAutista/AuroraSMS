# ADR 0002: separate local data boundaries and complete-history index

Status: accepted as the Phase 2 implementation control, 2026-07-12

## Context

Android's Telephony SMS/MMS provider remains authoritative for actual messages.
AuroraSMS nevertheless needs complete, fast local search and bounded timeline
queries that do not depend on a user manually scrolling old provider pages.
AuroraSMS also owns durable state such as drafts and, in later phases,
schedules, pending actions, spam decisions, remembered subscriptions, and
appearance assignments.

Those two kinds of local data have different failure semantics:

- a derived message index may be discarded and rebuilt from Telephony; and
- durable Aurora state may not be erased merely because an index or migration
  fails.

Combining them in one database would make safe index recovery capable of
destroying non-reconstructable user state. Querying Telephony directly for every
screen/search would make complete FTS, exact old-result jumps, deterministic
large-history paging, and honest partial coverage impractical. A manually
maintained SQLite layer would duplicate schema validation, invalidation, and
migration machinery without reducing the core synchronization risk.

Phase 2 therefore needs an explicit physical database boundary, a resumable
projection protocol, and a safe FTS/keyset contract before final UI work starts.

## Decision

### Two physical Room databases

AuroraSMS creates two credential-encrypted, application-private database files:

| Database | Physical filename | Authority | Recovery |
|---|---|---|---|
| Aurora index | `aurora_index.db` | Disposable projection of Telephony | Close, validate the exact private filename, delete only this database and its SQLite sidecars, recreate, and rebuild |
| Aurora state | `aurora_state.db` | Durable Aurora-only state | Explicit non-destructive migrations; never automatically delete or rebuild |

The databases have different Room classes, DAOs, schema export directories, and
module ownership. `:core:index` does not depend on `:core:state` and
`:core:state` does not depend on `:core:index`. Only application composition may
hold both.

There is no claimed transaction across the two files. Each index batch and its
checkpoints are atomic within the index database. Each durable state operation
is independently atomic within the state database.

Both databases start at schema version 1. Neither uses
`fallbackToDestructiveMigration`. Every schema is exported and validated. Every
future supported transition has an explicit migration test beginning with
version 1.

### Version-1 index schema

The index contains:

1. `indexed_messages`, with an Aurora local `Long` row ID; compound-unique
   `(provider_kind, provider_id)`; provider thread ID; normalized received/sent
   milliseconds; stable direction/box/status storage codes; subscription;
   sender; nullable body/subject; bounded attachment count/type summary;
   read/seen/locked state; sync fingerprint; and last-seen generation.
2. `indexed_messages_fts`, an external-content FTS4 table keyed to the local row
   and containing body, subject, and approved normalized searchable text under
   `unicode61` tokenization.
3. `index_generations`, recording one serialized scan's state, counts, dirty
   signal, timestamps, and redacted typed failure.
4. `index_checkpoints`, containing independent SMS and MMS consumed cursors and
   counts for one generation.

Required message indices cover compound provider identity, thread/time, global
time, sender/time, subscription/time, and read/time. Direction, box, status,
provider kind, and generation state use explicit stable codes rather than Kotlin
enum ordinals.

Attachment bytes, full-size media, raw PDUs, contact graphs, drafts, schedules,
pending actions, spam decisions, appearance data, and search queries are absent
from the index.

### Version-1 state schema

The state database begins with a minimal `drafts` table containing a local ID,
thread identity or bounded canonical participant-set key, nullable body/subject,
and created/updated millisecond timestamps. This real durable row allows Phase 2
to prove that index recovery cannot delete Aurora state.

Draft attachments, schedules, delayed/pending sends, pending deletion, remembered
SIM, spam decisions, and appearance assignments are not smuggled into version 1;
their roadmap phases will add explicit schema migrations. The final composer
remains disabled until its complete durable flow is integrated.

### Controlled index recovery

Index migration and index recovery are distinct operations. Normal schema
changes use explicit Room migrations even though the data is rebuildable. If
open/integrity verification identifies a corrupt index:

1. close every index handle;
2. verify the target is the application database path for exactly
   `aurora_index.db`;
3. delete only that file and its `-wal`/`-shm` sidecars;
4. recreate the latest schema; and
5. begin a new incomplete generation.

The recovery API is not passed an arbitrary path and cannot resolve the state
database name. A state open/migration/integrity failure preserves the bytes and
returns an actionable failure; it never falls back to deletion. An
instrumentation test retains a synthetic draft across index deletion/recreate.

### Telephony projection boundary

Only `:core:telephony` reads Android's providers. `:core:index` consumes bounded,
typed projections through interfaces and can therefore use deterministic fakes.

SMS and MMS are queried independently by provider timestamp descending and
provider ID descending. SMS milliseconds and MMS seconds are normalized to
milliseconds at the telephony boundary. Provider pages remain bounded at at most
200 returned rows, read no more than `limit + 1` even when a provider ignores
the query-limit hint, and reject non-advancing cursors.

Phase 2 extends the typed projections with the fields required by the index.
MMS address, text-part, and attachment metadata reads are bounded/chunked and do
not decode or retain attachment bytes or raw PDU data. Several bounded provider
pages may feed one approximately 500-row index transaction.

The synchronizer advances a provider cursor only through rows actually consumed
by a committed merged batch. Prefetched but unconsumed rows may be refetched.
This makes process-death recovery correct without persisting sensitive lookahead
buffers.

### Resumable generation protocol

One application-owned coordinator serializes index work. It starts only when
the default-SMS role and required read permission permit provider access and
runs provider/database work off the main thread.

For a complete generation:

1. transactionally create `SCANNING` generation metadata plus empty SMS and MMS
   checkpoints;
2. fill bounded provider lookahead and merge globally by normalized timestamp,
   an explicit stable provider-kind tie-breaker, and provider ID;
3. select an initial 500-row text/metadata batch, adapting downward only within
   documented bounds when measured transaction latency requires it;
4. transactionally upsert message/FTS rows, mark rows with the generation, update
   counts, and advance only consumed provider cursors;
5. expose committed batches to search with explicit incomplete coverage;
6. after both sources exhaust, persist `VERIFYING`, perform a bounded head and
   count/boundary consistency pass, and process accumulated dirty signals;
7. only after successful verification, transactionally reconcile rows not seen
   in the verified generation and persist `COMPLETE` with final counts/time.

An interrupted or failed verification never turns a partial generation into
complete coverage. Startup reads persisted generation/checkpoint state and
resumes a clean process interruption from the last committed cursor. Role or
read-permission loss marks the interrupted generation for a fresh scan after
authority returns; SQLite full/I/O errors, provider failure, or cancellation
do not advance an uncommitted checkpoint.

Aurora-owned provider inserts, the official external-provider receiver, a
Telephony `ContentObserver`, role reacquisition, process start, and a bounded
periodic check while the process is alive feed one coalesced dirty signal.
Duplicate signals are idempotent and concurrent scans are prohibited. Ambiguous
gaps, deletion mismatch, or cursor regression starts a new generation rather
than claiming completeness.

No WorkManager is admitted. Persisted checkpoints plus startup/provider signals
own process-death recovery. WorkManager may be reconsidered only after measured
evidence identifies a lifecycle requirement these mechanisms cannot meet.

### Safe FTS4 query contract

User input is normalized and parsed by original AuroraSMS code before it reaches
Room:

- trim and NFC normalization;
- maximum 256 UTF-16 code units, 16 terms, four phrases, 64 code units per term,
  and eight terms per phrase;
- reject controls, unmatched quotes, empty quoted phrases, and over-limit input;
- treat unsupported punctuation/operators as delimiters;
- emit only parser-owned quoted terms/phrases and at most one guarded final
  prefix of at least two letters/numbers; and
- return a typed no-query/invalid result instead of executing unsafe syntax.

Caller-supplied `*`, `OR`, `NEAR`, column selectors, parentheses, and unary
operators never retain operator meaning. The parser-produced FTS expression is
bound as a DAO parameter; raw user input is never concatenated into SQL.

Global and in-thread results use `(timestamp_ms DESC, row_id DESC)` keysets,
default/max page sizes of 50/100, and an `IndexCoverage` value on every response.
The query cursor is bound to a normalized-query fingerprint and optional thread
so it cannot be replayed against another search. UI-facing flows initially
debounce 150 ms and cancel obsolete work, but the database remains UI-neutral.

There is no deep `OFFSET`, `LIKE '%query%'`, unbounded list, or repeated provider
page loop.

### Exact-result anchor contract

A hit identifies both its stable local row and compound provider identity. An
exact jump resolves the local row and performs one bounded newer query plus one
bounded older query using timestamp/local-row keysets. The combined window has a
hard maximum and identifies one temporary highlight anchor.

If index rebuild invalidated the local row, the repository may re-resolve the
compound provider identity once. It then returns the bounded window or a typed
stale/not-found result. It never walks repeated 50-row pages or materializes a
whole thread.

### Dependency boundary

Phase 2 plans these exact direct additions only:

- KSP plugin `com.google.devtools.ksp` 2.3.9, Apache-2.0, build only;
- `androidx.room:room-runtime` 2.8.4, Apache-2.0, runtime;
- `androidx.room:room-compiler` 2.8.4, Apache-2.0, KSP only; and
- `androidx.room:room-testing` 2.8.4, Apache-2.0, Android test only.

They remain subject to AuroraSMS's transitive, checksum, license, maintenance,
manifest, permission, startup, network, data, and size review before production
resolution. Compiler/testing artifacts must not enter release runtime.

Paging and WorkManager are deferred pending measured need. `room-ktx`,
`room-paging`, RxJava, Guava, DataStore, image/GIF libraries, SQLCipher,
biometric, and a benchmark library are not admitted by this decision. The first
large-scale benchmark harness uses the existing Android test stack and original
timing/statistics code; a later benchmark dependency requires a separate review.

## Security and privacy consequences

- Both files remain in credential-encrypted app-private storage and are excluded
  from OS/cloud backup and device transfer by the existing all-private-data
  exclusion policy.
- The FOSS build keeps no `INTERNET` or `ACCESS_NETWORK_STATE` permission and no
  network-capable SDK.
- Bodies, addresses, subjects, queries, provider IDs, fingerprints, database
  paths, filenames, and SIM identifiers are never logged or placed in benchmark
  reports.
- Fixtures, previews, benchmarks, schema tests, and screenshots use synthetic
  identities/content only. No private reference is an implementation or test
  input.
- Sync fingerprints are private derived data and render only as redacted typed
  values.
- Text indexing never decodes MMS media.
- A complete-coverage claim is persisted only after verified reconciliation;
  an incomplete empty search says `No indexed matches yet` rather than claiming
  no messages exist.

## Alternatives rejected

### One database for index and state

Rejected because index corruption/rebuild could erase drafts or later durable
actions, and because cross-purpose migrations would couple disposable and
non-reconstructable data.

### Telephony-only paging and search

Rejected because it cannot provide complete local FTS4, complete-history search
without manual scrolling, deterministic exact old-result jumps, or the required
large-history budgets.

### Destructive Room migration fallback

Rejected for both databases. It hides schema mistakes and conflicts with the
requirement that every shipped schema has an explicit migration test. Explicit
index recovery is narrowly scoped and auditable instead.

### Shared preferences for durable feature state

Rejected as the Phase 2 durable database architecture. Existing bounded Phase 1
delivery/reply journals may remain in their reviewed private preferences until
their owning feature migrates, but new relational durable state belongs in
`aurora_state.db`.

### Paging 3 now

Deferred. Phase 2 can prove bounded keyset and anchor behavior with small typed
page contracts and fewer dependencies. A later UI phase may admit Paging only
after proving it does not introduce deep offsets or obscure exact anchors.

### WorkManager now

Deferred. Persisted checkpoints, application startup, official provider-change
signals, role recovery, and an in-process bounded periodic reconcile provide the
required resumability without a new manifest/background surface. Reconsider
only with measured missing-lifecycle evidence.

### SQLCipher now

Rejected for V1. AuroraSMS relies on Android sandboxing and file-based
encryption. A hardened-mode experiment remains post-V1 and must measure startup,
FTS, migration, memory, size, and low-end-device cost.

### Raw FTS syntax, substring `LIKE`, deep `OFFSET`, or repeated page walking

Rejected because they enable injection/resource exhaustion or violate bounded
search and exact-jump requirements.

## Consequences

Positive consequences:

- corrupt search state is independently recoverable without destroying durable
  Aurora state;
- search coverage, checkpoints, and completion are explicit and testable;
- compound provider identity prevents SMS/MMS ID collision;
- bounded provider/DB operations and keysets scale without materializing full
  histories;
- final UI phases consume stable repositories instead of Telephony cursors; and
- dependency and privacy growth remains narrow and auditable.

Costs and risks:

- two databases require separate schema/migration/recovery tests;
- projection synchronization must handle provider changes, deletions,
  timestamp-unit differences, and process death correctly;
- FTS content duplicates searchable text and consumes private storage;
- million-row evidence takes significant device time/storage; and
- a small original page/benchmark harness is more code to own until measured
  evidence justifies optional AndroidX helpers.

## Verification obligations

Before Phase 2 closes:

1. pass version-1 schema identity/export tests for both databases;
2. prove a synthetic draft survives index close/delete/recreate;
3. prove equal numeric SMS/MMS IDs, timestamp ties, and provider deletions remain
   correct;
4. prove process recreation resumes committed SMS/MMS cursors and never marks an
   interrupted verification complete;
5. prove observer/receiver duplicates coalesce and storage failure does not
   advance checkpoints;
6. prove safe parsing, bounded global/thread keysets, and bounded exact anchors
   across malformed, Unicode, RTL, and hostile queries;
7. record deterministic 500,000/1,000,000-row build/search/page/anchor evidence
   plus the 250,000-message-thread and 20,000-thread shapes;
8. inspect merged manifests/APKs, runtime graphs, locks, verification metadata,
   notices, license inventory, and SBOM for the admitted Room/KSP surface;
9. rerun every clean-room, private-asset, permission, no-network, APK-content,
   build, lint, unit, and connected-device gate; and
10. retain only redacted synthetic evidence with the exact commit, variant,
    command, device/API, scale/seed, and outcome.

The product targets are search at 500,000 rows within 120 ms P50/220 ms P95 and
an exact old-result jump within 350 ms P50/650 ms P95 after a warm index. A miss
is reported as a blocker or explicit owner-approved limitation, never obscured
by smaller fixtures or an incomplete-coverage claim.
