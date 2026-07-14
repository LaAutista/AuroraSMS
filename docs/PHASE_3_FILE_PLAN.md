# Phase 3 file-level plan

Status: Phase 3 implementation and API 36 synthetic-emulator profile gate
complete, 2026-07-13; owner-approved Pixel 8 alpha install/hash/redacted smoke
completed later that day. A subsequent Phase 4 physical window verified
real-provider reconciliation; release-equivalent physical performance remains
pending.

## Outcome

Deliver the Phase 3 browsing subset of an original, bounded Material 3 inbox,
complete-history search, and conversation thread. The implementation must
remain usable with 20,000
conversations and a 250,000-message thread without creating an unbounded
database result or UI list. It must preserve stable visible anchors across
paging and incoming updates, restore appropriate state, and produce
release-equivalent physical-device performance evidence.

Phase 3 is a browsing and presentation-performance vertical. It does not admit
AuroraMaterial, wallpaper/GIF behavior, scheduling, send delay, group-MMS
completion, permanent deletion, local spam, backup/restore, or the final
selection/action system.

The read-only structure delivered now includes inbox search/overflow placement,
Recent rows with avatar/title/snippet/time/unread hierarchy, bounded matching-
conversation previews, message results, thread identity/SIM subtitle, safe
system dial intent, accessible bubbles, group sender changes, useful delivery
status/failure presentation, IME/focus/back order, and an anchored disabled-send
draft field. Pin/archive/unread mutation and the populated pinned strip join the
Phase 5 action-state migration; block/spam joins Phase 6; permanent delete and
send remain Phase 5. Empty placeholder destinations are not added early.

## Acceptance criteria

- Telephony remains authoritative for real SMS/MMS. Presentation reads only
  the rebuildable index and bounded, explicit provider-media lookups.
- The index advances from version 1 to version 2 with a tested,
  non-destructive migration. The durable state database remains independent.
- Inbox ordering comes from a materialized conversation projection; opening
  the inbox never groups or scans the full message table.
- Participant addresses needed for trustworthy titles and group sender labels
  are stored as typed rows. Flattened FTS text is never parsed into identity.
- While a generation or participant projection is incomplete, titles fall back
  to a bounded address or `Unknown conversation` and the UI carries truthful
  incomplete coverage. It never asserts a confident group title from partial
  participants.
- Inbox and thread use deterministic keyset pages with one internal sentinel.
  No query uses deep `OFFSET`, `%LIKE%`, an unbounded list, or a provider loop.
- The default page is 50 rows and public requests are limited to 1..100. The
  retained inbox and thread windows have explicit hard caps.
- Lazy items use stable provider-thread or compound `ProviderMessageId` keys
  and content types. Local row IDs remain cursor/restoration hints only.
  Prepending preserves the first visible stable key and pixel offset.
- Incoming messages follow only while the reader is at the newest boundary.
  Otherwise the visible anchor remains fixed and a new-message action appears.
- A 250,000-message thread never creates a 250,000-item Kotlin or Compose list.
  Returning to newest or an exact hit replaces one bounded window.
- Rotation, split-screen recreation, process recreation, notification routing,
  search-to-anchor routing, and back retain an appropriate route, query, and
  stable visible anchor.
- Contact lookups, provider/Room work, files, and bounds/decode work run off the
  main thread. Contact data and previews use bounded caches or explicit release.
- Static MMS previews are a required Phase 3 deliverable. They enforce the
  exact hostile-media limits below, close every descriptor/stream, and evict
  lifecycle-owned bitmap references without recycling a bitmap Compose may
  still draw.
- Debug StrictMode retains only sanitized violation types/counts and reports no
  tested main-thread provider, database, file, or decode work.
- A generated Baseline Profile covers startup/inbox, inbox scroll, thread open,
  prepend, fling, search, exact jump/highlight, attachment open, and back.
- Release-equivalent, non-debuggable, R8-enabled, profileable physical-device
  runs measure the warm-inbox, thread-open, search, jump, frame, and memory
  gates. Debug timing remains supporting regression evidence only.
- No real content, address, contact, query, attachment, screenshot, database,
  broad log, role bypass, permission bypass, or carrier send is retained.
- Pixel 8/API 36 and a low-memory target run the applicable Phase 3 gates. The
  owner-assisted real-provider initial-sync responsiveness check requires only
  normal role/permission UI and retains sanitized timing/violation counts, not
  message content.

## Boundary decisions

### Paging

Paging 3 and `room-paging` remain unapproved. Room's generated paging path is
limit/offset based, while a custom cursor source would add refresh-key and
invalidation complexity without measured need. Phase 3 extends the existing
keyset/anchor design with explicit capped bidirectional windows.

### Identity

`ConversationId` remains the route/notification type and `ProviderThreadId`
the storage/provider type. Checked conversions replace raw `Long` casts. The
numeric value is intentionally the positive Telephony thread ID; it is not a
new independent message authority.

### Attachments

Phase 3 shows an exact visible message's static preview using Android platform
APIs. Only `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/heif`,
`image/heic`, and `image/avif` are candidates; the decoded header must also be
an image and a mismatched/unsupported type fails closed. Animated formats yield
one static frame. A source is rejected above 16 MiB encoded bytes, 8,192 pixels
on either source edge, or 40,000,000 source pixels. Unknown descriptor length is
read through a 16 MiB + 1 sentinel bound.

Decode is bounds-first, off-main, and sampled to the actual display request,
with a maximum target edge of 2,048 and maximum target area of 4,194,304 pixels.
At most two decode jobs and four retained previews exist process-wide; the
bitmap cache is additionally capped at 16 MiB by allocation byte count. API
26-27 uses `BitmapFactory` bounds/sample decode; API 28+ may use `ImageDecoder`
with explicit target sizing and software allocation. Every descriptor/stream
is closed. Cache removal drops lifecycle-owned strong references and does not
call `Bitmap.recycle()` while a UI consumer may draw. Attachment bytes and URIs
never enter index/timeline rows. Animated playback, appearance media, and a
third-party decoder remain Phase 4.

### Composer and actions

The thread receives an anchored durable text-draft field. Send stays clearly
disabled until the complete Phase 5 compose/transport contract. Scheduling,
subscription persistence, quote/forward send, deletion, block, and spam are
not pulled forward. Pin/archive/unread actions are not faked before their
durable/provider semantics exist. Each accepted edit enters one serialized,
conflated off-main writer immediately. The UI distinguishes saving, persisted
revision, and failure; only an acknowledged DAO revision is called durable.
Saved state retains the newest unacknowledged text and lifecycle stop requests a
bounded flush, but neither callback nor SavedState is falsely claimed to survive
every abrupt kill. Tests kill before and after acknowledgement, cancel a flush,
exercise stale revisions, and preserve the last acknowledged database value on
failure.

### Appearance

Phase 3 uses static opaque Material 3 with semantic 48 dp controls, RTL-safe
alignment, and basic accessibility. It adds no theme profile, wallpaper, blur,
GIF, avatar mask, density profile, bottom navigation, or adaptive rail.

## Index version 2

### `indexed_conversations`

One rebuildable row per positive provider thread stores:

- provider thread ID primary key;
- latest local row ID and timestamp;
- latest direction, status, subscription, sender, bounded snippet, attachment
  summary, and read state;
- indexed message and indexed unread counts, which are provider-total claims
  only when their returned coverage is verified; and
- generation metadata needed for transactional maintenance.

The paging index is `(latest_timestamp_ms DESC, latest_row_id DESC)`.

### `indexed_conversation_participants`

One row per `(provider_thread_id, address)` stores a validated address and
last-seen generation. The conversation row also persists whether any provider
participant projection was truncated. SMS contributes its counterpart. MMS
contributes its bounded participant list and sender. No contact graph/name/photo
is persisted.

### Transaction rules

- `IndexedMessageDao` remains the single transaction owner for scanning and
  steady-state writes. Its abstract SQL captures any old row before upsert and
  writes messages, the per-generation summary accumulator, participants,
  checkpoints, and generation progress inside one Room transaction. The new
  `ConversationDao` is read-only and is never sequenced beside a write DAO to
  simulate atomicity.
- A new generation resets a thread accumulator on its first encountered row,
  then increments it exactly once for each consumed provider row. The
  descending provider scan establishes latest metadata without rescanning a
  250,000-message thread.
- Only a verified Aurora-owned head insert uses incremental steady state.
  Updates, deletes, thread moves, and ambiguous external changes start a fresh
  full generation, which rebuilds summaries and participant sets rather than
  trying to infer removed membership.
- `IndexSyncDao.finishVerifiedGeneration` remains the single completion
  transaction owner. It deletes stale messages, summaries, and participants,
  performs canonical count/latest consistency checks, then marks complete.
- Migration 1-to-2 creates empty presentation tables and invalidates every v1
  COMPLETE, SCANNING, VERIFYING, PAUSED, or FAILED generation/checkpoint path by
  setting a dirty state that can only start a new full generation. Existing
  message/FTS rows remain intact, but no old checkpoint can claim v2 participant
  completeness.
- Crash/fault tests interrupt before and after each transaction boundary and
  prove checkpoints, projection rows, and completeness never diverge.

## Read contracts

`ConversationRepository` exposes first, older, newer, and stable-anchor pages.
Its cursor is `(generationId, latestTimestampMillis, latestLocalRowId)`.
Participant previews are capped and are loaded for a bounded set of thread IDs
in one query, never one query per conversation. Unique contact requests are
chunked through the resolver.
It also exposes a content-free, conflated invalidation signal; callers coalesce
reloads rather than reacting independently to every Room table callback.

`ThreadTimelineRepository` exposes latest, older, newer, exact-anchor, and one
exact full-content read. Its cursor is
`(generationId, providerThreadId, timestampMillis, localRowId)`. SQL display
projections omit FTS text, fingerprints, generation bookkeeping, and attachment
bytes. Long bodies use a bounded preview plus explicit one-message expansion.

Each repository transaction reads the latest generation/coverage and its rows
from one snapshot. A cursor for another generation returns a typed stale result.
Committed SCANNING, VERIFYING, PAUSED, or failed-generation rows may display
only with their truthful incomplete/failure coverage; integrity verification is
still mandatory before a database is opened for reads.

The existing safe debounced FTS pipeline remains authoritative. The Messages
section is the complete keyset-paged result. The Conversations section is
explicitly a deduplicated preview of the current bounded message page, not a
claim that every matching thread was independently enumerated. Both display
honest coverage and route a hit through the exact anchor API. Advanced filters
and a separately indexed distinct-conversation search remain later.

## Bounded window policy

- inbox retained cap: 200 summaries;
- thread retained cap: 200 messages;
- default page/prefetch: 50;
- maximum public page: 100;
- participant previews per conversation: 8;
- timeline body preview: 16,384 characters;
- retained thread text: 1,048,576 UTF-16 code units;
- exact anchor: existing maximum 101 rows;
- contact cache: 512 address results; and
- preview cache: 4 entries and 16 MiB allocation bytes, with at most 2 decodes.

Inbox order is newest-first. Older inbox pages append and, at the cap, evict
from the newest edge only after recording the visible stable anchor. A new or
reordered inbox row prepends only while the user is at newest; otherwise it
changes bounded pending state and leaves the visible window alone. Refresh at
newest evicts the oldest edge.

Thread display order is chronological. Older thread pages prepend and evict the
newest edge after anchor capture; newer pages append and evict the oldest edge.
Incoming messages append/follow only at newest and otherwise change bounded
pending state. Jump-to-newest and exact-anchor replace the window. Tests cover
all four paging directions plus reordering an existing inbox row.

Contact metadata resolves only the viewport plus ten prefetch rows, at most 50
conversations/400 participant rows per batched Room read and 100 unique contact
addresses per refresh or outstanding resolver call. Recomposition cannot start
a duplicate in-flight request. Permission transitions and a Contacts observer
clear metadata/photo references together; denial stays address-only.

## Planned files

### Planning and governance

```text
docs/PHASE_3_FILE_PLAN.md
docs/adr/0003-bounded-conversation-presentation.md
docs/DEPENDENCY_POLICY.md
docs/PERMISSION_LEDGER.md
docs/THREAT_MODEL.md
docs/TEST_MATRIX.md
THIRD_PARTY_NOTICES.md
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts
app/proguard-rules.pro
gradle.properties
gradle/libs.versions.toml
gradle/verification-metadata.xml
.github/workflows/verify.yml
scripts/update-baseline-profile.sh
scripts/verify-clean-room.sh
scripts/verify-apk-contents.sh
scripts/verify-dependencies.sh
scripts/verify-permissions.sh
```

### Model and index contracts

```text
core/model/src/main/kotlin/org/aurorasms/core/model/ConversationIdentity.kt
core/model/src/test/kotlin/org/aurorasms/core/model/ConversationIdentityTest.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/ConversationSummary.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/ConversationPage.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/ConversationRepository.kt
core/index/src/main/kotlin/org/aurorasms/core/index/timeline/TimelineMessage.kt
core/index/src/main/kotlin/org/aurorasms/core/index/timeline/TimelinePage.kt
core/index/src/main/kotlin/org/aurorasms/core/index/timeline/ThreadTimelineRepository.kt
```

### Index storage and synchronization

```text
core/index/schemas/org.aurorasms.core.index.storage.AuroraIndexDatabase/2.json
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedConversationEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedConversationParticipantEntity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/ConversationDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/AuroraIndexDatabase.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexDatabaseMigrations.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexedMessageDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexSyncDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexedProviderProjection.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/RoomConversationRepository.kt
core/index/src/main/kotlin/org/aurorasms/core/index/timeline/RoomThreadTimelineRepository.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexProjectionMapper.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizer.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexReconciler.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexMigration1To2Test.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/ConversationProjectionTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/ConversationPagingTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/ThreadTimelinePagingTest.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexProjectionAtomicityTest.kt
```

### Contact, attachment, and drafts

```text
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/ContactCache.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/BoundedContactCache.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidContactChangeObserver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/MmsAttachmentRepository.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidMmsAttachmentRepository.kt
core/telephony/src/test/kotlin/org/aurorasms/core/telephony/BoundedContactCacheTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/ContactChangeObserverTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/MmsAttachmentRepositoryTest.kt
core/state/src/main/kotlin/org/aurorasms/core/state/DraftRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/DraftDao.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/RoomDraftRepository.kt
core/state/src/test/kotlin/org/aurorasms/core/state/DraftIdentityLookupTest.kt
core/state/src/test/kotlin/org/aurorasms/core/state/DraftWriteAcknowledgementTest.kt
```

No state schema change is needed: version 1 already has unique provider-thread
and participant-set indices. Phase 3 adds typed lookup operations only.

### Conversation feature and app integration

```text
feature/conversations/build.gradle.kts
feature/conversations/src/main/AndroidManifest.xml
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ConversationUiModel.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/BoundedInboxWindow.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/BoundedThreadWindow.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/InboxStateHolder.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ThreadStateHolder.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/SearchStateHolder.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/InboxScreen.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ThreadScreen.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/SearchScreen.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/BoundedPreviewLoader.kt
feature/conversations/src/test/kotlin/org/aurorasms/feature/conversations/BoundedWindowTest.kt
feature/conversations/src/androidTest/kotlin/org/aurorasms/feature/conversations/ConversationUiStateTest.kt
app/src/main/kotlin/org/aurorasms/app/AppRoute.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRoot.kt
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/preview/AndroidBoundedPreviewLoader.kt
app/src/main/kotlin/org/aurorasms/app/contacts/AppContactCacheController.kt
app/src/main/kotlin/org/aurorasms/app/drafts/SerializedDraftWriter.kt
app/src/debug/kotlin/org/aurorasms/app/strictmode/BuildVariantStrictMode.kt
app/src/release/kotlin/org/aurorasms/app/strictmode/BuildVariantStrictMode.kt
app/src/benchmark/kotlin/org/aurorasms/app/benchmark/BenchmarkFixtureProvider.kt
app/src/benchmark/AndroidManifest.xml
app/src/androidTest/kotlin/org/aurorasms/app/PhaseThreeNavigationTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/PhaseThreeStrictModeTest.kt
```

The feature receives typed repositories, never DAOs, databases, providers,
`ContentResolver`, or an application scope. `MainActivity` retains role-before-
permission onboarding and notification routing. A pending notification route
is remembered but message content is gated behind role/read eligibility.

### Macrobenchmark and Baseline Profile

```text
macrobenchmark/build.gradle.kts
macrobenchmark/gradle.lockfile
macrobenchmark/src/main/AndroidManifest.xml
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/AuroraJourneys.kt
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/BaselineProfileGenerator.kt
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/StartupBenchmark.kt
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/ConversationFrameBenchmark.kt
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/SearchJumpBenchmark.kt
macrobenchmark/src/main/kotlin/org/aurorasms/macrobenchmark/MemoryBenchmark.kt
app/src/main/baseline-prof.txt
```

The existing `:benchmark` database harness stays separate. The new module
targets an R8-enabled, non-debuggable, profileable, release-equivalent app with
synthetic benchmark-only bootstrap data. Phase 3 admits the stable
`androidx.benchmark:benchmark-macro-junit4:1.4.1` test artifact and direct
`androidx.profileinstaller:profileinstaller:1.4.1` runtime artifact. It does not
apply the Baseline Profile Gradle plugin: stable 1.4.1 predates AGP 9's new DSL,
and Phase 3 does not silently adopt the 1.5 alpha compatibility fix.

`BaselineProfileRule` runs from the `com.android.test` module against the
benchmark variant. The generated HRF is normalized, reviewed, and copied by the
deterministic update script to `app/src/main/baseline-prof.txt`; there is no
`:app:generateBaselineProfile` claim. The debug build removes ProfileInstaller's
initializer and receiver. Release retains the audited non-exported initializer
and DUMP-protected receiver so sideloaded profiles work on supported Android
versions; benchmark retains the same profile path plus its separate fixture.
The release APK consumes the checked-in HRF through AGP and must contain
nonempty `baseline.prof` and `baseline.profm` assets.

The normal benchmark target remains R8-enabled for every performance run.
Because this plan intentionally omits the Baseline Profile Gradle plugin, the
manual update script sets `auroraBaselineProfileCapture=true` only while
capturing human-readable rules, which temporarily disables target obfuscation
so those rules use original signatures. The checked-in HRF is then consumed and
rewritten by R8 in the normal release build. Profile generation is never used
as product-performance evidence.

The as-built capture command requires an explicit device when more than one is
authorized and refuses an emulator unless `--allow-emulator-profile` is also
present. Two independently normalized Aurora-owned captures must share at least
99% of each rule set; only their exact intersection is admitted. The API 36
emulator run on 2026-07-13 produced two exact 1,977-rule captures and checked in
their intersection with SHA-256
`2eda2fae24a54e1a526dfca3f79a6dce12be7d4448317174882dc98de2f2bf9a`.
That proves deterministic profile generation and journey reachability, not
latency, frame, or memory performance. Stable resource-ID selectors and safe
drawing insets keep the synthetic journeys independent of localized labels and
system-bar overlap.

Fixture bootstrap is a benchmark-variant-only exported `ContentProvider`
protected by an Aurora signature permission requested by the same-signed test
APK. It accepts only a fixed synthetic shape enum and seed, performs a bounded
app-private database command, returns a synchronous success/failure bundle, and
supports explicit cleanup. It rejects arbitrary text, paths, URIs, provider
access, role/permission changes, and real Telephony data. Measurements force-
stop the control process and launch the real `MainActivity`; they never measure
the provider. Merged-manifest and APK checks prove the authority, permission,
classes, and synthetic corpus are present only in the benchmark target/test APK
and absent from debug/release. All fixture names and text are original synthetic
data and are never derived from private references.

## Fixtures and query-plan evidence

- Keep deterministic 0, 1, 10k, 100k, 500k, and 1m shapes.
- Use `SHALLOW_20_THOUSAND_THREADS` for inbox keysets.
- Use `SINGLE_THREAD_250_THOUSAND` for thread windows and anchors.
- Cover equal timestamps, provider-ID collisions, group participants, missing
  contacts, contact rename, latest deletion, thread move, read/unread, maximum
  body, RTL/emoji/combining, truncated participants, and attachment metadata.
- Migrate v1 databases whose latest generation is COMPLETE, SCANNING,
  VERIFYING, PAUSED, or FAILED; each must preserve message/FTS rows yet require
  a new full verified v2 projection before participant completeness.
- Inject failure before/after scanning and completion transactions and prove no
  checkpoint, summary, participant set, or coverage claim advances alone.
- Prove inbox first/after uses the conversation time/row index.
- Prove latest/older/newer uses the thread/time/row message index.
- Assert no inbox/thread query has `OFFSET`, `%LIKE%`, or a temporary sort.
- Assert limits, sentinel removal, non-advancing cursor rejection, and caps.
- Assert participant reads are batched, contact resolution is viewport-only,
  duplicate in-flight work is coalesced, negative results cache safely, and a
  rename/photo/permission change clears only the bounded contact cache.

## Performance evidence

| Journey | Required evidence |
|---|---|
| Warm usable inbox | 5 warmups + 30 measured; <=300 ms P50, <=500 ms P95 |
| Open text thread | 5 warmups + 30 measured; <=250 ms P50, <=450 ms P95 |
| Search at 500k | 5 warmups + 30 measured; <=120 ms P50, <=220 ms P95 |
| Exact old-result jump | 5 warmups + 30 measured; <=350 ms P50, <=650 ms P95 |
| 20k inbox and 250k thread fling/prepend | 10 fixed five-second flings; <1% deadline misses; 0 >=700 ms frozen frames |
| Fixed text browse | 10 fresh-process PSS samples after the fixed journey and 5 s quiescence; median aim below 150 MiB |
| APK delta | explain every change; unexplained growth <=5% |

Warm-inbox timing ends at `reportFullyDrawn()` only after the first bounded
window is placed and interactive. App trace sections end thread-open when the
header, first bubbles, and composer are placed; search when the first result
page is placed; and jump when the exact highlighted stable key is placed. Frame
evidence uses Macrobenchmark `frameOverrunMs > 0` against the device frame
deadline as the miss denominator, records refresh rate, and separately asserts
no frame at or above 700 ms. PSS uses sanitized `dumpsys meminfo` totals only.

The profile is generated twice; normalized nonempty HRF must be stable and pass
privacy/token scanning. The release APK is checked for compiled profile assets,
then the same journeys compare `CompilationMode.None` with
`CompilationMode.Partial(BaselineProfileMode.Require)` and retain synthetic-only
JSON and Perfetto results outside Git. Cold shell launch and cold index-ready
time are recorded separately. Warm setup uses a verified synthetic index and
states that coverage explicitly. This never justifies removing
`PRAGMA quick_check(1)`. Separately, committed partial rows may display with
truthful incomplete coverage, matching the Phase 2 contract.

Pixel 8/API 36 runs the physical warm/profile gates. A low-memory target runs
window, decode, recreation, and pressure checks. If no suitable target is
installed, the row remains open with an owner-approved limitation rather than
being silently waived. An owner-assisted real-provider initial-sync run checks
frame responsiveness and sanitized StrictMode counts after normal role and
`READ_SMS` UI; no content, screenshot, broad log, or database is retained.

## Dependency gate

Before production use of a new artifact:

1. resolve and lock the exact graph;
2. add checksums and license/notice entries;
3. verify no permission, exported component, initializer, network path, or
   release-runtime test leakage;
4. regenerate license inventory and SBOM; and
5. inspect debug, benchmark, and release APK contents.

Promoting the existing lifecycle ViewModel Compose artifact from debug-only and
the two exact benchmark/profile artifacts above are subject to that graph
check. Paging, Navigation Compose, media libraries, and any other runtime
artifact stay unapproved unless a measured decision updates this plan.

Clean-room scanning adds `macrobenchmark` to every implementation-root token
scan and private-asset inventory. Dependency/component checks cover its test APK
and the app benchmark APK. APK-content verification applies release-marker
rules by manifest/build identity rather than filename alone and explicitly
checks debug, release, benchmark target, and macrobenchmark test outputs.

## Validation sequence

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
./gradlew :core:index:connectedDebugAndroidTest
./gradlew :feature:conversations:connectedDebugAndroidTest
./gradlew :benchmark:connectedDebugAndroidTest
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
./scripts/update-baseline-profile.sh --verify-twice --device SERIAL
./gradlew :app:assembleRelease :macrobenchmark:connectedBenchmarkAndroidTest
./gradlew verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom
```

Exact task names are recorded after the tooling spike. At the gate, install and
launch the exact debug APK, copy it to
`/sdcard/Download/AuroraSMS-debug.apk`, and compare local/device SHA-256. Real
provider-history UI evidence requires owner acceptance of Android's role/read
permission UI; tests never bypass it. No carrier message is required.

The initial 2026-07-13 finalization deliberately deferred that physical
install/copy step because the owner needed uninterrupted access to a working
SMS app. In a later explicitly approved window, the exact Phase 3 debug APK was
installed in place on the Pixel 8/API 36, copied to
`/sdcard/Download/AuroraSMS-debug.apk`, and matched by size and SHA-256. The
existing AuroraSMS default role and grants remained intact. Redacted
diagnostics and content-free UI tags proved provider/inbox/thread/composer
reachability without retaining private content. Index reconciliation was still
scanning, so verified completion and representative physical Macrobenchmark
evidence remain open.

That account remains the historical result for the exact Phase 3 APK. A later
owner-approved Phase 4 window superseded only its reconciliation limitation:
generation 10 initially completed with zero pending changes, 10,183
verified/exhausted SMS rows, 4,926 verified/exhausted MMS rows, and 15,109
indexed messages. A later provider signal durably marked it pending and
triggered follow-up reconciliation. Representative physical Macrobenchmark
evidence remains open.

## Stop conditions

Stop and update this plan before adding Paging, Navigation Compose,
WorkManager, a media library, or another runtime dependency;
changing a database without migration tests; weakening integrity verification;
reading attachment bytes while indexing; retaining provider URIs in index/UI
rows; allowing a window/cache to grow with history; exposing benchmark fixture
components in debug/release; or implementing later-phase send/delete/spam/theme
features.
