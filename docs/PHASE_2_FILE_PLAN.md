# Phase 2 file-level plan

Status: approved Phase 2 planning baseline, 2026-07-12; production edits have
not started

## Phase 2 outcome

Create two physically separate, private Room databases and prove a complete,
bounded, resumable projection of Android's authoritative SMS/MMS provider into
an Aurora-owned index. The index supports safe FTS4 search, deterministic
keyset pages, honest partial-coverage reporting, and a bounded jump around one
exact stable result. The durable state database establishes the independent
Aurora-only state boundary and a minimal text-draft record without expanding
into later scheduling, deletion, spam, or appearance work.

This is the complete-history data and search vertical. It is not the final
inbox/thread UI, AuroraMaterial, a transport rewrite, or an MMS codec phase.

## Acceptance criteria before editing

- `aurora_index.db` and `aurora_state.db` are separate physical files with
  independently exported version-1 Room schemas.
- The index is disposable and rebuildable. A controlled corrupt-index recovery
  may delete only the closed index database and its SQLite sidecars; it never
  deletes, migrates destructively, or reinitializes the state database.
- The state database has no destructive-migration fallback. Version 1 stores a
  minimal text draft so an isolation test can prove index recovery preserves
  durable Aurora state. Future schedules, pending sends/deletes, spam decisions,
  remembered subscriptions, and appearance assignments remain deferred to
  their roadmap phases.
- Each projected message has an Aurora local `Long` row ID and compound-unique
  `(provider_kind, provider_id)` identity. Equal numeric SMS and MMS provider IDs
  remain distinct.
- Required projection data covers provider/thread identity, normalized
  millisecond timestamps, direction, box/status, subscription, sender address,
  body/subject, bounded attachment summary, read/seen/locked state, and a sync
  fingerprint. Attachment bytes and contact graphs never enter an index row.
- Indices cover thread/time, global time, sender, subscription/time, and
  read/time. SMS/MMS timestamp ties use deterministic provider-ID ordering.
- Initial synchronization merges independently paged SMS and MMS streams newest
  first, commits bounded batches, and exposes each committed batch to search.
- Per-provider checkpoints advance in the same transaction as their consumed
  rows. Process recreation resumes from the last committed SMS and MMS cursors
  and never restarts a healthy partial generation at zero.
- A generation is not complete merely because both scans reached an old
  boundary. It enters verification, runs a bounded consistency pass, reconciles
  changes/deletions, and persists `COMPLETE` only when verification succeeds.
- Receiver, `ContentObserver`, lifecycle, and role-recovery signals coalesce;
  retries are idempotent. Role/permission loss and storage failure stop work
  safely and leave the last committed checkpoint truthful.
- FTS4 covers body, subject, and explicitly normalized searchable text. Raw user
  input is never concatenated into SQL or passed through as unrestricted FTS
  syntax.
- Empty, punctuation-only, quoted, prefixed, Unicode, malformed, and hostile
  queries have deterministic bounded results or a typed validation result.
- Global and in-thread searches use keyset pages. The initial tuning is 50 rows
  with 50-row prefetch; there is no deep `OFFSET`, `LIKE '%query%'`, unbounded
  list, provider-page jump loop, or repeated 50-row exact-jump loop.
- Selecting a hit resolves its stable local row, loads one bounded window before
  and after the anchor, and returns explicit temporary-highlight metadata.
- Deterministic fixtures cover 0, 1, 10,000, 100,000, 500,000, and 1,000,000
  messages, one 250,000-message thread, and 20,000 shallow threads. Dedicated
  physical-device runs record build, search, keyset, and anchor costs at 500,000
  and 1,000,000 rows with repeatable seeds.
- Build, lint, unit/instrumentation tests, schema checks, dependency/license
  report, SBOM, merged-manifest permission check, APK-content check,
  private-asset check, and clean-room scan pass.
- No reference-app source/artifact/dependency/history, `INTERNET`,
  `ACCESS_NETWORK_STATE`, final UI/theme engine, broad media decode, carrier send,
  recycle bin, Paging, WorkManager, or destructive migration is added.

## Phase boundary

Phase 2 includes:

- `:core:index` and `:core:state`;
- exact Room/KSP dependency admission;
- provider projection repair needed by complete indexing;
- version-1 schemas and migration baselines;
- resumable synchronization, reconciliation, coverage, FTS4, keyset search, and
  exact-anchor contracts;
- application-scope wiring and redacted debug diagnostics;
- synthetic scale fixtures and a dedicated database benchmark module.

Phase 2 explicitly excludes:

- final inbox, thread, composer, Archive, Spam & Blocked, or search presentation;
- Paging 3 until Phase 2 measurements show that a later UI needs it and its
  keyset/anchor behavior passes a separate dependency decision;
- WorkManager until a measured lifecycle requirement cannot be met by the
  application coordinator, official provider-change signals, and resumable
  persisted checkpoints;
- DataStore, navigation, image/GIF loaders, SQLCipher, biometric support, remote
  services, or general network access;
- schedules, send delay, durable per-conversation SIM choice, pending deletion,
  spam decisions, appearance state, backup, or restore;
- MMS PDU encoding/decoding and attachment-media decode. Phase 2 indexes only
  bounded text and metadata exposed by the Telephony provider.

Phase 1 transport limitations remain truthful. Synthetic/database Phase 2 work
does not claim carrier SMS/MMS behavior that has not been physically exercised.

## Dependency admission before production schema work

The planned direct additions are intentionally limited to:

| Coordinate/plugin | Pin | Scope | License | Planned purpose |
|---|---:|---|---|---|
| `com.google.devtools.ksp` | 2.3.9 | Build only | Apache-2.0 | Generate Room implementation code without annotation-processing runtime leakage |
| `androidx.room:room-runtime` | 2.8.4 | Runtime in `:core:index` and `:core:state` | Apache-2.0 | SQLite ownership, transactions, FTS4, DAO implementation, and invalidation |
| `androidx.room:room-compiler` | 2.8.4 | KSP processor only | Apache-2.0 | Generate and validate DAO/database code and exported schemas |
| `androidx.room:room-testing` | 2.8.4 | Android test only | Apache-2.0 | Schema identity, migration, and database-isolation tests |

No `room-ktx`, `room-paging`, RxJava, Guava, Paging, WorkManager, benchmark
library, or additional processor is admitted by this plan. Existing coroutines
and Android test dependencies provide the surrounding asynchronous and test
contracts. The dedicated benchmark module initially uses the existing Android
test stack and a small original timing/percentile harness; admitting AndroidX
Benchmark later requires its own exact-coordinate review.

Before any of the four additions is resolved into production:

1. record canonical upstream/source URLs, SPDX license, maintenance and security
   status, exact need, alternatives, resolved transitives, native code, manifest
   entries, permissions, startup behavior, network behavior, APK/DEX size, and a
   removal plan in `docs/DEPENDENCY_POLICY.md` and `THIRD_PARTY_NOTICES.md`;
2. resolve only through the already approved repositories;
3. review and commit version-catalog, lockfile, and verification-metadata
   changes;
4. prove compiler/testing artifacts do not enter release runtime;
5. regenerate the deterministic license inventory and CycloneDX SBOM; and
6. inspect both merged app manifests and APKs for added components, permissions,
   initializers, and network surface.

Approval of a coordinate does not approve an unreviewed transitive. A cache hit
is not dependency admission.

## Modules created in Phase 2

- `:core:index` - Android library containing the disposable Room index,
  provider-to-index projection, synchronization/reconciliation coordinator,
  coverage model, safe FTS parser, keyset search, and exact-anchor repository.
- `:core:state` - Android library containing the durable Room state boundary and
  minimal text-draft storage used to prove isolation. It does not own Telephony
  messages or index checkpoints.
- `:benchmark` - Android library test harness containing deterministic large
  synthetic database fixtures and explicit index/search/anchor measurements. It
  has no production entry point and is absent from app release runtime.

Continue to defer `:core:designsystem` and all final feature modules. Do not add
an empty search feature or move the debug diagnostics surface into a feature
module.

## Physical database boundary and schema version 1

The complete decision is recorded in
`docs/adr/0002-local-data-boundaries.md`.

### Index database

Physical name: `aurora_index.db`.

Version-1 tables:

1. `indexed_messages`
   - local `row_id INTEGER PRIMARY KEY AUTOINCREMENT`;
   - `provider_kind` and `provider_id`, unique together;
   - `provider_thread_id`;
   - `timestamp_ms` and nullable `sent_timestamp_ms`;
   - direction, box, and status values stored through explicit stable storage
     codes, never enum ordinals;
   - nullable subscription ID and sender address;
   - nullable body and subject;
   - bounded attachment count plus normalized attachment-type summary only;
   - read, seen, and locked flags;
   - fixed-format sync fingerprint; and
   - `last_seen_generation` used only for verified deletion reconciliation.
2. `indexed_messages_fts`
   - external-content FTS4 table keyed to `indexed_messages.row_id`;
   - body, subject, and approved normalized searchable text;
   - `unicode61` tokenization;
   - no contact photo, attachment bytes, raw PDU, or executable content.
3. `index_generations`
   - monotonically increasing local generation ID;
   - `SCANNING`, `VERIFYING`, `COMPLETE`, `FAILED`, or `PAUSED` storage state;
   - start/update/completion timestamps, committed row counts, dirty-signal
     state, and a redacted typed failure code;
   - at most one active generation.
4. `index_checkpoints`
   - compound primary key `(generation_id, provider_kind)`;
   - last consumed normalized timestamp/provider ID, exhausted flag, committed
     count, and update timestamp;
   - exactly one SMS and one MMS checkpoint for an active generation.

Required `indexed_messages` indices:

- unique `(provider_kind, provider_id)`;
- `(provider_thread_id, timestamp_ms DESC, row_id DESC)`;
- `(timestamp_ms DESC, row_id DESC)`;
- `(sender_address, timestamp_ms DESC, row_id DESC)`;
- `(subscription_id, timestamp_ms DESC, row_id DESC)`; and
- `(is_read, timestamp_ms DESC, row_id DESC)`.

The FTS external-content table is updated in the same Room transaction as its
content row. Upsert compares the sync fingerprint so an unchanged provider row
does not churn FTS content or invalidation.

The index database does not contain drafts, schedules, pending actions, spam
decisions, appearance assignments, attachment bytes, contact graphs, or a copy
of a raw MMS/SMS PDU.

### State database

Physical name: `aurora_state.db`.

Version 1 contains one minimal `drafts` table:

- local `draft_id INTEGER PRIMARY KEY AUTOINCREMENT`;
- nullable provider thread ID plus a bounded canonical participant-set key for a
  not-yet-created thread;
- nullable body and subject;
- created and updated millisecond timestamps; and
- a constraint that a draft has a thread identity or a participant-set key.

Phase 2 does not add draft attachments, schedule fields, remembered SIM state,
send/delete operations, spam state, or appearance state. The production
composer remains disabled until its later complete durable flow is integrated.

State migrations are always explicit and non-destructive. There is no Room
destructive-migration fallback. Index corruption recovery must be unable to
resolve or delete `aurora_state.db`; an instrumentation test retains a synthetic
draft across index close/delete/recreate.

### Schema export and migration baseline

Both modules configure deterministic Room schema export into tracked module
directories. Version 1 has an identity/export test created through
`MigrationTestHelper`; every future version must add an explicit migration from
every supported prior version and a retained migration test. No migration is
allowed to become destructive merely because the index is rebuildable. A
rebuild is a separate, explicit recovery operation, never hidden inside Room
migration fallback.

## Provider paging repair

Phase 1 provider pages are the only Telephony input. Phase 2 repairs and extends
them rather than adding direct provider queries to `:core:index`.

- Query SMS and MMS independently, off the main thread, ordered by provider
  timestamp descending and provider ID descending.
- Normalize SMS milliseconds and MMS seconds at the telephony boundary before
  constructing any shared cursor or index row.
- Keep provider queries bounded at no more than the existing 200-row page.
  Build an approximately 500-row index transaction by merging several bounded
  pages, not by requesting or retaining an unbounded provider cursor.
- Read at most `limit + 1`, stop iteration even if a provider ignores the
  platform query-limit hint, and derive `hasMore` without returning the extra
  row.
- Advance a source cursor only through the last row actually consumed into a
  committed merged batch. Prefetched but unconsumed rows are safe to refetch
  after process death.
- Reject a non-advancing cursor as a typed provider failure instead of looping.
- Extend SMS projection with stable box/status and locked values.
- Extend MMS projection with bounded sender/participant, text-part, subject,
  attachment count/type metadata, status, and locked values. Secondary address
  and part lookups are bounded/chunked, avoid unbounded `IN` lists, and never
  decode or retain media bytes.
- Keep address normalization conservative. Alphanumeric senders, short codes,
  national numbers, and email-style MMS addresses are not silently rewritten.
- Compute one sync fingerprint from stable projected fields. The fingerprint is
  sensitive derived data: store privately, render as redacted, and never log it.

## Synchronization, checkpoints, generations, and reconciliation

### Start and stop conditions

An application-owned coordinator may start after default-SMS role and required
read permission success. It is not Activity-owned and all provider/database work
runs off the main thread. Role loss, permission revocation, cancellation,
storage failure, or provider failure moves the current generation to a truthful
paused/failed state without advancing an uncommitted checkpoint.

The coordinator resumes from:

- application process start while eligible;
- role reacquisition;
- an Aurora-owned provider insert completion;
- `ACTION_EXTERNAL_PROVIDER_CHANGE`;
- a registered Telephony `ContentObserver`; and
- a bounded periodic reconciliation while the process remains alive.

No WorkManager is added. If later evidence shows that process-lifetime signals
and startup reconciliation cannot meet an owned lifecycle requirement, the
dependency decision is reopened with measured evidence.

### Generation protocol

1. In one index transaction, create a `SCANNING` generation and independent SMS
   and MMS checkpoints with no consumed cursor.
2. Fill one bounded lookahead page from each provider and merge by normalized
   timestamp descending, provider kind as an explicit tie-breaker, and provider
   ID descending. The tie-breaker is documented and stable.
3. Select an initial batch of 500 text/metadata rows. If measured transaction
   latency exceeds the target, adapt downward within a documented lower/upper
   bound; never adapt into an unbounded transaction.
4. In one transaction, upsert rows and FTS content, mark
   `last_seen_generation`, update committed counts, and advance only the source
   cursors consumed by this batch.
5. Search may read committed batches immediately. Coverage reports generation,
   committed counts, provider exhaustion, state, and whether complete coverage
   is verified. Incomplete empty search says `No indexed matches yet`; it does
   not claim that no messages exist.
6. Coalesce duplicate observer/receiver/lifecycle signals into a dirty flag and
   one serialized reconciliation request. A `Mutex`/actor owns synchronization;
   concurrent scans never advance competing checkpoints.
7. After both sources exhaust, move to `VERIFYING`. Perform a bounded head pass,
   compare provider counts/boundaries, process accumulated dirty signals, and
   verify both checkpoints still describe the completed scan.
8. Only after successful verification, transactionally remove index rows not
   seen in the verified generation, mark the generation `COMPLETE`, and persist
   its completion timestamp/counts. A failed or interrupted verification leaves
   the previous complete generation truthful and the new one incomplete.
9. Subsequent changes use a bounded head/reconcile pass. Ambiguous gaps,
   deletion count mismatches, or cursor regression start a new generation rather
   than claiming local completeness.

Startup always inspects persisted generation/checkpoint state. This closes the
process-death gap even if an in-memory observer signal was lost.

### Storage and corruption behavior

- SQLite full/I/O errors produce a typed paused/failed state and no false
  success. Recovery resumes idempotently at the last committed checkpoint.
- Index open/integrity failure closes all index handles, verifies the target is
  exactly the known private index filename, deletes only the index file and its
  `-wal`/`-shm` sidecars, recreates schema version 1, and begins a new generation.
- State open/migration/integrity failure is never auto-deleted. It reports an
  actionable failure and preserves bytes for explicit recovery.
- No cross-database transaction is claimed. Index rows and checkpoints are
  internally atomic; durable state operations are independently atomic.

## Safe FTS4 and keyset contracts

### Query normalization and grammar

The original parser:

- trims and NFC-normalizes input using `Locale.ROOT` behavior where case
  handling is required;
- caps the original query at 256 UTF-16 code units, 16 searchable terms, four
  quoted phrases, 64 code units per term, and eight terms per phrase;
- rejects ISO control characters, unmatched quotes, empty quoted phrases, and
  over-limit input with a typed validation result;
- treats unsupported punctuation/operators as delimiters rather than FTS
  instructions;
- emits only parser-owned quoted terms/phrases and an optional final guarded
  prefix;
- allows a prefix only for a final unquoted term of at least two normalized
  letters/numbers and never accepts caller-supplied `*`, `OR`, `NEAR`, column
  selectors, parentheses, or unary operators as syntax; and
- returns `NoQuery` for empty/punctuation-only input without issuing SQL.

The DAO binds the parser-produced match expression through a query parameter.
It never concatenates raw user input into SQL. Tests cover quotes, doubled
quotes, punctuation, combining marks, emoji, RTL, malformed syntax, prefix
limits, and hostile operator-like input.

### Search pages

- Default page size is 50; maximum is 100; the caller may prefetch one additional
  50-row page.
- Global ordering is `(timestamp_ms DESC, row_id DESC)`.
- In-thread ordering uses the same keys after exact `provider_thread_id`
  filtering.
- A cursor contains the last returned timestamp and local row ID plus a query
  fingerprint; it cannot be reused with a different normalized query/thread.
- Every response contains `IndexCoverage` so a partial index cannot overstate
  negative results.
- Debounce is 150 ms by default within the required 120-180 ms range. Obsolete
  jobs use structured cancellation/`mapLatest`; the database contract itself
  remains callable without UI.

### Exact-result anchor

A search hit carries its local row ID and compound provider identity. Selection:

1. resolves the exact local row;
2. loads one bounded newer half-window and one bounded older half-window using
   timestamp/local-row keysets;
3. combines at most the configured anchor-window limit;
4. identifies the exact row for temporary highlight; and
5. if rebuilding removed the local row, re-resolves the compound provider
   identity once and returns a typed stale/not-found result without looping.

There is no deep `OFFSET`, full-thread materialization, repeated provider page,
or repeated 50-row database loop.

## Root/build/governance modifications

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle/verification-metadata.xml
THIRD_PARTY_NOTICES.md
docs/DEPENDENCY_POLICY.md
docs/TEST_MATRIX.md
README.md
.github/workflows/verify.yml
```

Responsibilities:

- include `:core:index`, `:core:state`, and `:benchmark` only when their first
  implementation slice lands;
- pin and audit Room/KSP, lock every resolvable configuration, and retain
  deterministic schema/report outputs;
- raise the project version to the Phase 2 development line only with the first
  production slice;
- ensure `:benchmark`, `room-compiler`, `room-testing`, `:core:testing`, and debug
  diagnostics never enter app release runtime;
- ensure Android lint traverses both new Android library modules;
- include new source/schema roots in clean-room and APK-content checks while
  keeping generated database files and benchmark results out of Git; and
- record Phase 2 evidence without committing device history, database copies,
  queries, addresses, or broad logs.

## `:core:index` exact file plan

```text
core/index/build.gradle.kts
core/index/gradle.lockfile
core/index/src/main/AndroidManifest.xml
core/index/schemas/org.aurorasms.core.index.storage.AuroraIndexDatabase/1.json
core/index/src/main/kotlin/org/aurorasms/core/index/MessageIndex.kt
core/index/src/main/kotlin/org/aurorasms/core/index/IndexCoverage.kt
core/index/src/main/kotlin/org/aurorasms/core/index/SearchQuery.kt
core/index/src/main/kotlin/org/aurorasms/core/index/SearchPage.kt
core/index/src/main/kotlin/org/aurorasms/core/index/SearchHit.kt
core/index/src/main/kotlin/org/aurorasms/core/index/AnchorWindow.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/AuroraIndexDatabase.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexDatabaseFactory.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedMessageEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedMessageFtsEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexGenerationEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexCheckpointEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedMessageDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexSyncDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/search/SafeFts4QueryParser.kt
core/index/src/main/kotlin/org/aurorasms/core/index/search/RoomMessageIndex.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizer.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/ProviderMergeCursor.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexProjectionMapper.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/SyncFingerprintFactory.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexSignalCoalescer.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexReconciler.kt
core/index/src/test/kotlin/org/aurorasms/core/index/search/SafeFts4QueryParserTest.kt
core/index/src/test/kotlin/org/aurorasms/core/index/sync/ProviderMergeCursorTest.kt
core/index/src/test/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizerTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexSchemaV1Test.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexedMessageDaoTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexResumeAndReconcileTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexRecoveryIsolationTest.kt
```

Package-private helpers may share an existing planned file when that keeps one
small behavior reviewable. Do not add empty abstractions solely to match the
tree; any file omitted or renamed is recorded in the phase evidence with its
responsibility preserved.

## `:core:state` exact file plan

```text
core/state/build.gradle.kts
core/state/gradle.lockfile
core/state/src/main/AndroidManifest.xml
core/state/schemas/org.aurorasms.core.state.storage.AuroraStateDatabase/1.json
core/state/src/main/kotlin/org/aurorasms/core/state/Draft.kt
core/state/src/main/kotlin/org/aurorasms/core/state/DraftRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AuroraStateDatabase.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseFactory.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/DraftEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/DraftDao.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/RoomDraftRepository.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateSchemaV1Test.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/DraftRepositoryTest.kt
```

The state module has no dependency on `:core:index`. The index module has no
dependency on `:core:state`. Application wiring may depend on both.

## Provider/model/testing modifications

```text
core/model/src/main/kotlin/org/aurorasms/core/model/IndexMessageId.kt
core/model/src/main/kotlin/org/aurorasms/core/model/MessageBox.kt
core/model/src/main/kotlin/org/aurorasms/core/model/MessageStatus.kt
core/model/src/main/kotlin/org/aurorasms/core/model/MessageSyncFingerprint.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/SmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/MmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidSmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidMmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/MmsMetadataReader.kt
core/telephony/src/test/kotlin/org/aurorasms/core/telephony/ProviderPagingPolicyTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/ProviderProjectionTest.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeMessageIndex.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeDraftRepository.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/SyntheticIndexFixtures.kt
core/testing/src/test/kotlin/org/aurorasms/core/testing/SyntheticIndexFixtureTest.kt
```

Stable storage codes are explicit conversion functions. Neither enum ordinal nor
localized/display text is persisted.

## Application integration modifications

```text
app/build.gradle.kts
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsApplication.kt
app/src/main/kotlin/org/aurorasms/app/index/AppIndexCoordinator.kt
app/src/test/kotlin/org/aurorasms/app/index/AppIndexCoordinatorTest.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsViewModel.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsScreen.kt
app/src/debug/res/values/strings.xml
```

`AppContainer.onExternalProviderChanged()` becomes a bounded signal into the
coordinator. Incoming provider persistence also signals a reconcile after its
provider transaction completes. Debug diagnostics may show only redacted
generation state, counts, coverage, and typed failures; it never shows message
content, addresses, search queries, provider IDs, raw fingerprints, or SIM IDs.

No new exported Android component is planned. If implementation evidence shows
one is necessary, stop and update the permission/component ledger before adding
it.

## Benchmark exact file plan

```text
benchmark/build.gradle.kts
benchmark/gradle.lockfile
benchmark/src/main/AndroidManifest.xml
benchmark/src/androidTest/kotlin/org/aurorasms/benchmark/DeterministicIndexFixtures.kt
benchmark/src/androidTest/kotlin/org/aurorasms/benchmark/IndexDatabaseScaleBenchmark.kt
benchmark/src/androidTest/kotlin/org/aurorasms/benchmark/BenchmarkStatistics.kt
```

The benchmark module uses only synthetic records and a documented fixed seed.
It records variant, commit, device/API, seed, fixture shape, warmup count, sample
count, P50/P95, database size, and operation. It does not commit generated
databases, raw timing dumps containing device identifiers, screenshots, broad
logs, or test-message content.

Required measured operations:

- build/rebuild at 500,000 and 1,000,000 messages;
- committed 500-row batch latency during initial indexing;
- global and in-thread FTS searches, including no-hit and common-token cases;
- forward/backward keyset page loads;
- an exact anchor near the newest, middle, and oldest boundary; and
- deletion reconciliation and database reopen after a committed checkpoint.

Product targets after a warm index are search at 500,000 rows within 120 ms P50
and 220 ms P95, and exact old-result jump within 350 ms P50 and 650 ms P95. A
miss remains an explicit Phase 2 blocker or owner-approved limitation; it is not
hidden by changing fixture data or reducing coverage.

## Required tests

### Host contract tests

- compound provider identity and local-ID stability;
- stable box/status storage-code conversion;
- provider merge order across SMS/MMS, timestamp ties, and exhausted sources;
- bounded provider page and non-advancing cursor rejection;
- sync fingerprint stability and change sensitivity;
- query normalization, limits, phrases, guarded prefix, Unicode/RTL, punctuation,
  malformed syntax, and hostile operator-like input;
- search/keyset cursor binding to normalized query and thread;
- checkpoint advancement only for committed/consumed rows;
- coalesced duplicate signals, cancellation, role loss, and retry policy; and
- deterministic synthetic fixture generation with no private/reference values.

### Room/instrumentation tests

- exact version-1 index and state schema identities and exported JSON;
- future migration harness starts from version 1 with no destructive fallback;
- equal numeric SMS/MMS IDs coexist, while duplicate same-kind IDs upsert;
- all required indices exist and query plans avoid deep offsets/full `%LIKE%`;
- FTS content follows transactional insert/update/delete and unchanged
  fingerprints avoid churn;
- global/thread keyset pages have no duplicates/gaps under timestamp ties;
- exact anchor returns a bounded window and one stable highlight;
- process recreation reopens and resumes both provider checkpoints;
- partial generation never reports complete after interrupted verification;
- observer/receiver duplication produces one serialized reconcile;
- provider deletion/update reconciles only after verified coverage;
- storage-full/fault injection does not advance a checkpoint or report success;
- controlled index deletion/recreate retains a synthetic state draft exactly;
- state database open/migration failure is preserved, never auto-deleted; and
- all database/provider operations execute off the main thread in tested paths.

### Scale fixtures

Applicable fixtures at every scale include SMS/MMS provider-ID collisions,
same-millisecond timestamps, missing contacts, deleted provider rows, dual-SIM
records, GSM, Unicode, emoji, combining marks, RTL, long bodies, null subjects,
and attachment-heavy MMS metadata. Media bytes are never generated or decoded
for text indexing.

## Implementation slices

1. Planning/dependency gate: this file, ADR 0002, exact Room/KSP admission,
   locks/checksums/notices/SBOM review, and empty module build gates.
2. Models/provider repair: stable storage types, complete bounded SMS/MMS
   projections, deterministic paging, fingerprints, fakes, and contract tests.
3. Database v1: separate index/state files, entities/DAOs, FTS4, indices, schema
   export, draft boundary, and version-1 tests.
4. Search vertical: safe parser, global/thread keyset pages, honest coverage, and
   exact bounded anchor.
5. Sync vertical: merged newest-first scan, transactional checkpoints,
   generations, process-death resume, coalescing, consistency, deletion
   reconciliation, and controlled index recovery.
6. App integration: application-scope coordinator, role/permission lifecycle,
   provider signals, and redacted debug diagnostics.
7. Scale/gate hardening: deterministic 0-to-1m fixtures, physical-device
   benchmark evidence, full lint/tests/build/governance/license/SBOM suite, and
   handoff evidence.

At most one schema or architectural migration is in flight per slice. The state
database and index database land with separately reviewable commits and schema
exports. Stop at the Phase 2 gate before starting final inbox/thread UI.

## Validation commands at the gate

Discover generated task names first; use only the checked wrapper. Expected
focused and aggregate sequence:

```text
./gradlew tasks --group build
./gradlew tasks --group verification
./gradlew :core:index:testDebugUnitTest :core:telephony:testDebugUnitTest :core:testing:test
./gradlew :core:index:compileDebugAndroidTestKotlin :core:state:compileDebugAndroidTestKotlin :benchmark:compileDebugAndroidTestKotlin
./gradlew test lintDebug lintRelease assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
./gradlew :benchmark:connectedDebugAndroidTest
./gradlew verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents
./gradlew --no-parallel checkLicense generateLicenseReport
./gradlew cyclonedxBom
```

Use `--offline --no-daemon` after all admitted artifacts and verification
metadata are present. The benchmark task may be renamed to its actual discovered
variant task if the module uses a release-like test build; record the exact
command rather than claiming the expected name ran.

At the gate:

- run all host and connected instrumentation tests on the physical API 36
  device;
- run the scale benchmark separately so million-row setup does not make the
  ordinary CI test gate nondeterministic;
- inspect exported schemas and every migration path;
- inspect merged manifests and APK permissions/components;
- prove compiler/testing/benchmark modules are absent from release runtime;
- install and launch the exact debug APK, copy it to
  `/sdcard/Download/AuroraSMS-debug.apk`, and verify its on-device SHA-256; and
- record only redacted, synthetic evidence in `docs/TEST_MATRIX.md`.

No carrier message is required to validate Phase 2. Reading real Telephony
history on the connected device requires the owner to accept the default-SMS
role and runtime permission UI; tests never bypass or silently grant that
choice.

## Known Phase 2 blockers and risks

- Room 2.8.4 and KSP 2.3.9 are planned but not admitted merely by this document.
  Their exact artifacts/transitives, checksums, licenses, build behavior, and
  release leakage must pass the dependency gate before production use.
- The current Phase 1 MMS projection intentionally omits participant/text/part
  detail because no codec was admitted. Phase 2 must implement bounded
  provider-metadata reads without parsing raw PDU bytes or claiming MMS codec
  support.
- Real provider-history synchronization cannot be physically evidenced until
  the owner explicitly accepts the Android default-SMS role and required read
  permission. Synthetic and isolated database tests proceed without that grant.
- Only the Pixel 8/API 36 physical target is currently available. Other required
  API/OEM rows remain pending for their release gates and are not silently
  claimed by Phase 2.
- 500,000/1,000,000-row runs require substantial device time and private app
  storage. Fixture generation must check available space, clean only its own
  synthetic benchmark database, and never inspect or export real Telephony
  content.
- The original timing harness records useful controlled evidence but is not an
  AndroidX Benchmark claim. If scheduler noise prevents repeatable evidence,
  stop and separately admit a benchmark dependency rather than overstating
  precision.
- Phase 1 carrier MMS and complete transport rows remain pending. They do not
  authorize Phase 2 scope reduction and Phase 2 does not convert them into
  passing claims.

These blockers do not authorize a destructive state migration, a fake completed
generation, a smaller mislabeled fixture, a deep-offset fallback, an unbounded
provider scan, or an unreviewed dependency.
