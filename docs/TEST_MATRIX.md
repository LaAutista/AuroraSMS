# AuroraSMS test matrix and phase-gate evidence

Status: Phase 3 implementation, deterministic profile generation, API 36
synthetic-emulator functional gate, and owner-approved Pixel 8 alpha
install/hash/smoke complete; Phase 4 AuroraMaterial foundation, foreground
provider-read lifecycle hardening, exact physical APK handoff, and verified
real-provider reconciliation complete, 2026-07-14. Representative
physical-device performance, remaining API/OEM coverage, and carrier transport
rows remain pending.

## Evidence rules

- A feature is not complete because it compiles or looks correct in a preview.
- Every result records commit, variant, device/API, command or manual procedure,
  data scale, outcome, and retained non-sensitive artifact.
- Performance claims require release/profileable builds on physical hardware.
- Real SMS/MMS tests require an explicitly approved destination and awareness
  of carrier cost. Tests never send to an inferred or private-reference number.
- Broad logcat capture is prohibited. Use scoped process/tag/package diagnostics
  with message bodies, addresses, URIs, queries, and SIM identifiers redacted.
- All host/device fixtures and screenshots use synthetic identities, addresses,
  dates, avatars, media, and messages.
- No private handoff file may enter test assets, goldens, CI uploads, or reports.

## Required device/platform coverage

| Target | Purpose | Gate |
|---|---|---|
| API 26 AOSP | Minimum SDK, legacy default-handler path | Phase 1 and release |
| API 29/30 | `RoleManager.ROLE_SMS` transition | Phase 1 and release |
| API 31/32 | Background/exact-alarm behavior | Phase 5 and release |
| API 33 | Notification runtime permission | Phase 1/5 and release |
| API 34/35 | Modern role/backup/background behavior | Phase 1 onward |
| API 36 | Primary target behavior | Every phase |
| API 37 | Forward compatibility for the compile surface; target remains 36 and no alternative transport/RCS claim | Phase 1 build and release compatibility |
| Pixel/AOSP or GrapheneOS-style physical device | Reference functional/performance evidence | Phase gates as applicable |
| Samsung physical device | OEM telephony/provider/role behavior | Release |
| LineageOS device, where available | FOSS ROM behavior | Release |
| Low-memory device/emulator | Allocation/decode pressure | Phase 3/4/release |
| Tablet/foldable/large screen | Adaptive navigation and state parity | Phase 4/release |

The current environment has SDK platforms 36 and 37.0, a physical Google Pixel
8 (`shiba`) running LineageOS Android 16/API 36, and the synthetic-only
`AuroraSMS_API36` API 36 AVD. Earlier physical evidence below covers only the
Pixel/API combination. Phase 3 connected functional/profile-generation
evidence used the AVD; its timings are not representative performance
evidence. In a later owner-approved window, the exact Phase 3 debug APK was
installed in place and exercised against the real provider using only redacted
diagnostics and content-free UI tags. No message body, address, query,
attachment, screenshot, database, or broad log was retained. The remaining
required API and OEM rows stay pending.

## Phase 0 documentation gate

- [x] Independent `main` repository initialized with zero inherited commits.
- [x] Private handoff ignored and absent from tracked paths.
- [x] All 34 supplied SHA-256 entries verified.
- [x] Full blueprint, HTML concept, 66-page PDF, nineteen screenshot files, and
  nine artwork files reviewed under the private-reference policy.
- [x] Clean-room allowed/prohibited input charter created.
- [x] Product identity, scope, architecture, and non-negotiable behavior
  documented.
- [x] Official default-SMS role component/intent checklist documented.
- [x] Permission allowlist/denylist and role-before-permission flow documented.
- [x] Dependency admission policy, denylist, and phase allowlist documented.
- [x] Threat model and artwork integrity/mapping/license gate documented.
- [x] Full test/smoke matrix and Phase 1 file-level plan documented.
- [x] Phase 0 review explicitly authorizes Phase 1 production code.

## Phase 1 foundation and role matrix

### Build and static checks

- [x] Wrapper-only build succeeds on the documented toolchain.
- [x] `assembleDebug`, `assembleRelease`, host unit tests, and lint pass.
- [ ] Instrumentation/device tests pass on the required API/device matrix; all
  26 Phase 1 app/notification/telephony tests pass on a physical Pixel 8/API
  36, while the other required API/device rows remain pending.
- [x] No Android module applies `org.jetbrains.kotlin.android` under AGP 9.
- [x] Java and Kotlin outputs target 17 even when Gradle runs on host JDK 26.
- [x] Dependency verification/locks, notices, and resolved allowlist pass.
- [x] Source scan finds no prohibited production/test identifiers or
  `org.fossify` coordinate.
- [x] Merged manifest contains only ledgered permissions/components.
- [x] Every FOSS variant has no `INTERNET` or `ACCESS_NETWORK_STATE`.
- [x] `allowBackup=false` and data-extraction exclusions are present.
- [x] APK inventory contains no private PDF, screenshot, handoff path, raw
  source artwork, device logs, signing material, or Camera ICON.

### Local evidence for implementation commit `cf8b789`

This subsection preserves the pre-device evidence captured for that commit.
The later physical-device subsection supersedes only its device-availability
statement.

The following offline commands completed successfully on 2026-07-12:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease --offline --no-daemon
./gradlew verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline --no-daemon
./gradlew :app:compileDebugAndroidTestKotlin :core:notifications:compileDebugAndroidTestKotlin :core:telephony:compileDebugAndroidTestKotlin --offline --no-daemon
```

Retained local evidence:

- 55 host tests, zero failures/errors;
- 12 Android instrumentation source files compile; execution is pending a
  connected device;
- CycloneDX 1.6 aggregate SBOM: 342 components, no random serial number;
- generated license inventory: 137 dependency records;
- debug APK: 11,791,173 bytes,
  SHA-256 `3424701811b194d985b30f42fa7811b5d4b737afae779c3482a29116121e600b`;
- unsigned release APK: 8,397,302 bytes,
  SHA-256 `00df40fa5359b64a7f16262768cd0a87e7e9faa7221470c84d252d998fd7b221`;
- `adb devices -l` returned no connected device, so installation, role grant,
  instrumentation, and carrier SMS/MMS rows remain explicitly pending.

### Physical API 36 evidence for commits `30a4049` and `f5e591e`

The following commands completed successfully on 2026-07-12:

```text
./gradlew :core:notifications:testDebugUnitTest :core:notifications:lintDebug :core:notifications:lintRelease :core:notifications:compileDebugAndroidTestKotlin --offline --no-daemon
./gradlew connectedDebugAndroidTest --offline --no-daemon
./gradlew :app:assembleDebug --offline --no-daemon
./gradlew verifyCleanRoom verifyPrivateAssets verifyPermissions verifyApkContents --offline --no-daemon
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/AuroraSMS-debug.apk
```

Retained local and device evidence:

- device: Google Pixel 8 (`shiba`), Android 16/API 36, telephony capable;
- 25 instrumentation tests, zero failures/errors: 11 app, 3 notifications,
  and 11 telephony;
- debug APK: 11,818,622 bytes,
  SHA-256 `f6207c3497ad5e1807b9f5aba68b77fb552c04af096d77b7ec664430aa85aa3b`;
- APK permission inspection contains only the ledgered SMS/MMS, phone-state,
  contacts, notification, vibration, and private dynamic-receiver permissions;
  it contains neither `INTERNET` nor `ACCESS_NETWORK_STATE`;
- streamed install succeeded, `pm path` resolved the installed base APK, and a
  cold launch completed with `MainActivity` resumed and the app process alive;
- `/sdcard/Download/AuroraSMS-debug.apk` is 11,818,622 bytes and its on-device
  SHA-256 exactly matches the workstation artifact;
- AuroraSMS was deliberately left without the default-SMS role and without
  sensitive runtime permission grants. No role prompt was accepted and no
  carrier SMS or MMS was sent.

### Role eligibility and onboarding

For API 26-28 and API 29+ paths as applicable:

- [ ] Telephony unsupported state is honest and non-crashing.
- [ ] API 26-32 generic telephony checks and API 33+
  `FEATURE_TELEPHONY_MESSAGING` and `FEATURE_TELEPHONY_SUBSCRIPTION` checks
  prevent unsupported messaging/subscription calls.
- [ ] Role availability and already-held state are detected.
- [ ] Role request starts only from explicit user action.
- [ ] User acceptance enters the permission/diagnostic flow.
- [ ] Cancellation returns to a usable explanation without prompt loops.
- [ ] SMS runtime permissions are not requested before the role request.
- [ ] Individual permission denial/revocation has a precise recovery path.
- [ ] Role loss stops writes/sends, preserves drafts, and updates diagnostics.
- [ ] Role reacquisition reconciles state without duplicating messages.
- [ ] `ACTION_DEFAULT_SMS_PACKAGE_CHANGED` and external provider change are
  handled idempotently.

### Manifest component contracts

- [ ] `SENDTO` activity resolves `sms`, `smsto`, `mms`, and `mmsto`.
- [ ] External compose input never auto-sends.
- [ ] Respond-via-message service has all four schemes and the official guard.
- [ ] SMS delivery receiver has the official action and `BROADCAST_SMS` guard.
- [ ] MMS delivery receiver has the official action, MIME type, and
  `BROADCAST_WAP_PUSH` guard.
- [ ] Required `android.hardware.telephony` feature filtering and runtime
  feature/role checks agree.
- [ ] Exported state is explicit for every component.
- [ ] Malformed schemes, recipients, extras, MIME types, and oversized URI
  lists fail safely.
- [ ] App-private sent/delivered callbacks reject external spoofing.
- [ ] Inline reply alone uses an explicit mutable pending intent for
  `RemoteInput` on API 31+; tap/content/SMS/MMS result/alarm callbacks remain
  immutable and pass flag-policy tests.

## Provider and transport smoke checklist

Run on a telephony-capable device after explicit test-recipient approval.

### Provider read/write

- [ ] Read provider counts without main-thread access.
- [ ] Read SMS and MMS independently with correct date-unit normalization.
- [ ] Write one received SMS as the default holder and confirm one row only.
- [ ] Write one received MMS as the default holder and confirm parts/addresses.
- [ ] Replayed delivery and observer signals remain idempotent.
- [ ] External provider modification schedules bounded reconciliation.
- [ ] Provider failure/storage-full produces an actionable state and no false
  success notification.

### One-to-one SMS

- [ ] Send GSM-7 SMS; capture sent and delivered/unsupported status honestly.
- [ ] Receive GSM-7 SMS and post one privacy-compliant notification.
- [ ] Send/receive extended GSM and multipart SMS.
- [ ] Send/receive Unicode, emoji, combining marks, and RTL.
- [ ] Short-code and alphanumeric senders display and reply only where the
  platform/carrier permits, without unsafe normalization.
- [ ] Delivery reports on and off produce honest sent/delivered/unsupported
  presentation without claiming read receipts.
- [ ] Emergency-number safety rejects automation and follows platform/carrier
  restrictions without silently converting the action.
- [ ] Airplane mode, no service, invalid number, and carrier failure retain a
  retryable/failed state without duplicate sends.
- [ ] Long body reports segment/MMS policy before send.

### One-to-one MMS

- [ ] Send/receive text-only MMS.
- [ ] Send/receive image attachment with carrier-aware size handling.
- [ ] The non-exported custom MMS provider confines canonical cache paths,
  rejects traversal/external access, grants read-only for one send source and
  write-only for one download target, and never reuses an operation URI.
- [ ] MMS send/download result receivers handle every documented result once,
  revoke the exact platform URI access mode, and remove cache-only PDU operation
  files after success, failure, timeout, process death recovery, or cancellation.
- [ ] Exercise audio, video, vCard, unknown MIME, null subject, and malformed
  part handling as supported by the current phase.
- [ ] Mobile-data disabled, roaming policy, APN failure, and carrier-size
  rejection show specific actionable errors.
- [ ] Attachment decode remains lazy/bounded and off the main thread.

### Group MMS invariant

- [ ] Two unique recipients always select MMS.
- [ ] Duplicate-equivalent recipient forms canonicalize to one recipient.
- [ ] Three-or-more recipient sends remain one group MMS operation.
- [ ] No ordinary group path exposes a toggle or first-send choice.
- [ ] MMS failure never fans out into separate SMS sends.
- [ ] Provider addressing and replies preserve group identity.
- [ ] Removed/invalid participant and carrier failure are actionable.

### Dual SIM and subscription state

- [ ] No SIM, one SIM, dual physical SIM, eSIM + physical where available.
- [ ] Explicit SIM selection reaches subscription-specific platform transport.
- [ ] An unavailable, removed, disabled, or stale explicitly supplied
  subscription is rejected with an actionable choice and never silently
  switches to another SIM.
- [ ] Subscription/slot metadata on incoming SMS/MMS is normalized.
- [ ] Airplane, roaming, and mobile-data changes do not corrupt pending state.

## Notifications and direct reply

- [ ] Channel creation/update is deterministic and local.
- [ ] `MessagingStyle` conversation/person metadata is correct and synthetic in
  tests.
- [ ] Privacy modes render sender+body, sender-only, and generic forms.
- [ ] Lock-screen visibility respects the selected level.
- [ ] API 33+ notification denial leaves in-app messaging usable and explains
  missed system alerts.
- [ ] Tap opens the exact conversation and restores state.
- [ ] Inline reply validates role, permission, recipient, SIM, and current
  conversation before sending.
- [ ] Duplicate/expired reply intents do not duplicate sends.
- [ ] Failed reply posts a safe actionable notification without body leakage.
- [ ] Android Auto metadata/reply verification is completed in Phase 6.

## Cross-phase lifecycle and storage pressure

- [x] Provider batches and head verification defer at the next bounded unit
  after every Aurora messaging activity stops; zero-to-one foreground resume
  restarts cleanly without a false dirty mark, and role-loss pause is not
  blocked behind the foreground gate.
- [ ] Low/full storage during provider receive/write, index batch commit,
  attachment staging, pending send/delete, wallpaper import, and backup export
  never reports false success or destroys durable state.
- [ ] After storage is recovered, idempotent retry/resume continues from the
  last committed state without duplicate message, send, delete, or notification.
- [ ] Process death at each durable operation boundary has a deterministic
  expected state; no feature relies on an Activity-owned singleton.

## Phase 2 index/search matrix

Deterministic fixture sizes:

- 0 messages;
- 1 message;
- 10,000 messages;
- 100,000 messages;
- 500,000 messages;
- 1,000,000 messages;
- one 250,000-message thread;
- 20,000 shallow threads.

Every applicable scale contains SMS/MMS provider-ID collisions,
same-millisecond timestamps, missing contacts, deleted provider rows, dual-SIM
records, GSM/Unicode/emoji/combining/RTL, long bodies, null subjects, and
attachment-heavy MMS metadata.

- [x] Initial sync indexes newest first in bounded provider pages and
  application-lifetime coroutine work.
- [x] An owner-approved Pixel 8/API 36 window exercised the real SMS/MMS
  provider through verified completion while inbox, thread, and search remained
  reachable. This is functional evidence, not a latency claim.
- [x] Checkpoint resume after process death never restarts from zero.
- [x] A completed sync runs and persists a lightweight consistency pass and
  completion generation; restart/reconcile does not mistake partial coverage
  for complete.
- [x] Batch commit makes partial search coverage available and honestly labeled.
- [x] Observer/receiver duplicate events coalesce.
- [x] Deletions/external changes reconcile.
- [x] Unique compound identity and deterministic tie ordering hold.
- [x] FTS query parser handles empty, punctuation, quotes, prefix limits,
  Unicode normalization, malformed syntax, and hostile input.
- [x] Result that was never manually scrolled into view is found.
- [x] Global and in-thread results are paged/bounded.
- [x] Exact old-result jump uses a bounded before/after anchor load.
- [x] No deep `OFFSET`, `LIKE '%query%'`, unbounded list, or repeated 50-row
  jump loop exists.
- [x] Every Room schema version has exported schema and explicit migration test.
- [x] Dedicated 500k-row and 1m-row database benchmarks record index build,
  search, paging, and exact-anchor costs with repeatable fixture seeds.

### Phase 2 local and Pixel/API 36 evidence

Implementation commits `b0a7fac`, `bfce1dd`, `43cb64d`, and `d13ed8a` were
verified on 2026-07-12. The final candidate uses a bounded FTS candidate set
plus a same-transaction timeline proof; arbitrary row-ID/timestamp order falls
back to an exact FTS-first compact row-ID sort before the bounded page is
hydrated. Verified generations merge FTS4 segments, and complete-generation
coverage uses the exact physical row count persisted by the completion or
steady-state content transaction.

The following offline commands completed successfully:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon --no-parallel
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline --no-daemon
./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel
./gradlew :core:index:testDebugUnitTest :core:index:lintDebug :core:index:lintRelease :core:index:compileDebugAndroidTestKotlin :benchmark:compileDebugAndroidTestKotlin --offline --no-daemon --no-parallel
./gradlew :core:index:connectedDebugAndroidTest --offline --no-daemon --no-parallel -Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.core.index.IndexFtsEvidenceTest,org.aurorasms.core.index.IndexSchemaV1Test,org.aurorasms.core.index.TelephonyIndexSynchronizerInstrumentedTest
./gradlew :benchmark:connectedDebugAndroidTest --offline --no-daemon --no-parallel -Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.benchmark.IndexDatabaseScaleBenchmark#configuredScaleBenchmark_requiresExplicitOptIn -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkFull=true -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkShape=500000 -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkCommit=d13ed8a
./gradlew :benchmark:connectedDebugAndroidTest --offline --no-daemon --no-parallel -Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.benchmark.IndexDatabaseScaleBenchmark#configuredScaleBenchmark_requiresExplicitOptIn -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkFull=true -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkShape=1000000 -Pandroid.testInstrumentationRunnerArguments.auroraBenchmarkCommit=d13ed8a
```

Retained non-sensitive evidence:

- aggregate host tests, debug/release lint, debug/release assembly, clean-room,
  private-asset, dependency, permission, and APK-content gates passed;
- aggregate Pixel 8/API 36 instrumentation passed 11 app, 26 index, 3
  notification, 11 state, and 12 telephony tests; the benchmark module passed
  2 contract/smoke tests and skipped the explicit large-scale entry point by
  design;
- the final focused search/schema/synchronizer run passed 15 tests, including
  transactional FTS insert/update/delete, no-churn generation marks, cursor
  binding, inverted row-ID/timeline fallback, aligned proof/keyset pages,
  equal-timestamp proof rejection, FTS segment maintenance, exported schema
  validation,
  migration-helper reopen, verified synchronization, and the non-correlated
  compact FTS query plan;
- exact SQLite-full rollback, both-provider-cursor reopen/resume, controlled
  index-file corruption rebuild, and durable state-database preservation passed
  their focused physical-device tests;
- generated license inventory contains 182 dependency records with zero
  disallowed records; the CycloneDX 1.6 aggregate SBOM contains 386 components,
  387 dependency graph nodes, and no random serial number;
- the exact 12,419,067-byte debug APK installed successfully, launched cold
  with Android activity status `ok`, and was copied to
  `/sdcard/Download/AuroraSMS-debug.apk`; local and device SHA-256 both equal
  `49bdf7b054356a8e3c1f339f61ddf7325f3b3b88f23ec5e387f0f1b5301b172d`,
  while the existing default-SMS role and runtime-permission state were left
  unchanged;
- the full 500k run for `d13ed8a` passed in 30m43s using fixed seed
  `18389685492662578`, 500,000 synthetic messages, and 25,000 threads; and
- the full 1m run for the same commit passed in 1h2m51s with the same fixed
  seed, 1,000,000 synthetic messages, and 50,000 threads.

The controlled 500k debug regression metrics were:

| Operation | P50 | P95 |
|---|---:|---:|
| Fresh build including FTS merge | 630.092 s | 630.092 s |
| Clean rebuild including FTS merge | 649.249 s | 649.249 s |
| Committed 500-row batch, build | 420.101 ms | 505.055 ms |
| Committed 500-row batch, rebuild | 425.649 ms | 500.394 ms |
| Global common-token search | 63.046 ms | 71.455 ms |
| Global no-hit search | 20.076 ms | 27.244 ms |
| Thread common-token search | 244.209 ms | 316.756 ms |
| Forward keyset page | 315.309 ms | 357.900 ms |
| Backward timeline keyset | 7.352 ms | 8.096 ms |
| Anchor newest | 20.648 ms | 23.159 ms |
| Anchor middle | 22.366 ms | 24.310 ms |
| Anchor oldest | 22.826 ms | 25.601 ms |
| Verified deletion reconciliation and FTS merge | 11.745 s | 11.745 s |
| Database reopen and checkpoint read | 4.234 s | 5.354 s |

The controlled 1m debug regression metrics were:

| Operation | P50 | P95 |
|---|---:|---:|
| Fresh build including FTS merge | 1,305.130 s | 1,305.130 s |
| Clean rebuild including FTS merge | 1,312.074 s | 1,312.074 s |
| Committed 500-row batch, build | 425.107 ms | 502.986 ms |
| Committed 500-row batch, rebuild | 428.545 ms | 509.157 ms |
| Global common-token search | 60.630 ms | 77.804 ms |
| Global no-hit search | 36.319 ms | 50.804 ms |
| Thread common-token search | 462.316 ms | 496.579 ms |
| Forward keyset page | 446.748 ms | 573.079 ms |
| Backward timeline keyset | 7.133 ms | 12.095 ms |
| Anchor newest | 20.630 ms | 50.636 ms |
| Anchor middle | 21.367 ms | 25.102 ms |
| Anchor oldest | 22.064 ms | 25.431 ms |
| Verified deletion reconciliation and FTS merge | 46.101 s | 46.101 s |
| Database reopen and checkpoint read | 8.658 s | 9.149 s |

The warm 500k and 1m databases occupied 521,392,352 and 1,043,079,392 bytes,
respectively. Global common search passed the 120/220 ms controlled-regression
threshold at both scales, and every exact anchor passed the 350/650 ms
threshold. These debug runs are not a release/profileable product-performance
claim; that confirmation remains pending under the evidence rules. Earlier
full runs were retained as failed gates rather than hidden:
`b0a7fac` missed global search at 351.046/379.099 ms, and `bfce1dd` improved the
median but still missed at 238.921/641.798 ms. Commit `43cb64d` passed 500k at
104.775/111.857 ms, then its full 1m run correctly failed at
133.074/148.230 ms after 1h56m. Neither target nor fixture was changed while
correcting those defects.

All database/benchmark rows above use generated synthetic content. No carrier
SMS or MMS was sent, no default-role/runtime-permission choice was bypassed, and
no real provider content, address, query text, provider ID, fingerprint, SIM
identifier, database, screenshot, or broad log was retained as evidence.

### As-built file-plan deviations

The file lists in `PHASE_2_FILE_PLAN.md` remain the pre-implementation review
baseline. Responsibilities planned for `IndexMessageId.kt`,
`SyncFingerprintFactory.kt`, and `MmsMetadataReader.kt` landed in the existing
provider ID model, `ProviderProjectionFingerprint.kt` plus
`IndexProjectionMapper.kt`, and `AndroidMmsProviderDataSource.kt`. Planned fake
repositories were unnecessary because tests use deterministic fixtures, fake
provider data sources, and the real isolated Room repositories. Planned
`DraftRepositoryTest.kt`, `ProviderProjectionTest.kt`, and
`ProviderPagingPolicyTest.kt` became focused contract/instrumentation tests.
The as-built tree additionally separates `SearchPipeline.kt`,
`IndexStorageCodes.kt`, `DraftIdentityEnforcement.kt`, the state schema trigger
baseline, and physical-device durability/FTS/synchronization evidence tests.

## Phase 3 inbox/thread performance matrix

- [x] Inbox and thread use stable keys and granular updates.
- [x] A 250k-message thread never creates a 250k-item UI list.
- [x] Prepending older rows preserves the visible anchor.
- [x] Incoming messages do not force a user away from their read position.
- [ ] Rotation, split screen, process recreation, and back navigation preserve
  appropriate state. Rotation/process/back coverage passes; explicit
  split-screen device evidence remains pending.
- [x] Scroll-to-bottom/new-message affordance works without forced jumps.
- [x] Contact rename/photo change invalidates only the bounded contact cache.
- [x] Attachment previews load at display size and release correctly in focused
  bounded-loader tests.
- [x] Debug StrictMode reports no tested main-thread provider/DB/file/decode
  operation.
- [x] Baseline Profile includes startup, inbox, thread/prepend, search, exact
  jump, the synthetic attachment presentation path, and back journeys. No real
  attachment bytes are admitted to the profile fixture.

### Phase 3 implementation evidence for `d767295`

The following offline host gates passed on 2026-07-13:

```text
./gradlew test lintDebug lintRelease :app:lintBenchmark assembleDebug assembleRelease :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark --offline --no-daemon --no-parallel
./gradlew verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon --no-parallel
./gradlew checkLicense generateLicenseReport cyclonedxBom --offline --no-daemon --no-parallel
bash -n scripts/*.sh
PYTHONPYCACHEPREFIX=/tmp/aurorasms-pycache python3 -m py_compile scripts/patch-benchmark-apk.py
```

The build/lint/test gate completed 789 tasks. Clean-room verification checked
all 19 private-reference hashes. Manifest and packaged-APK checks passed for
debug, R8/resource-shrunk release, R8 benchmark target, and the separate
macrobenchmark APK. The release APK contains nonempty
`assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`. The generated
license inventory contains 201 records with zero disallowed licenses; the
aggregate CycloneDX JSON contains 440 components and 441 dependency nodes.

The post-commit connected command was pinned to the AVD so Gradle could not
select the physical phone:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel
```

It discovered 78 tests: app 15, existing database benchmark 3, index 29,
notifications 3, durable state 12, telephony 15, and presentation 1. Seventy-
seven passed with zero failures/errors; the existing
`configuredScaleBenchmark_requiresExplicitOptIn` row was the single intentional
skip. An earlier pre-commit run exposed that `:macrobenchmark` still offered a
default debug variant and incorrectly paired benchmark-only tests with the
fixture-free debug app. Phase 3 disabled that variant, reran the same root task,
and retained the initial failure rather than treating it as passing evidence.

The profile update used only the API 36 AVD and synthetic fixtures:

```text
./scripts/update-baseline-profile.sh --verify-twice \
  --device emulator-5556 --allow-emulator-profile
```

Both final Aurora-owned captures contained the same 1,977 normalized rules.
The exact intersection is `app/src/main/baseline-prof.txt`, SHA-256
`2eda2fae24a54e1a526dfca3f79a6dce12be7d4448317174882dc98de2f2bf9a`.
Startup/inbox/scroll/thread/prepend/fling/search/exact-jump/back journeys all
completed. This is profile determinism and reachability evidence only; no AVD
latency, frame, or memory number is used as a product claim.

The final Phase 3 debug APK is 12,865,082 bytes with SHA-256
`cda0f726c6cd931f386cb66e2b16d58bc74f84a3790cc0926ec57f6cccbc249e`.
It was initially withheld while the owner needed uninterrupted messaging, then
installed in place during an explicitly approved Pixel 8/API 36 window. The
same bytes were copied to `/sdcard/Download/AuroraSMS-debug.apk`; the device
size and SHA-256 matched the local artifact, and the existing default-SMS role
and grants remained intact.

Redacted diagnostics reported a working provider surface with 10,178 SMS rows,
4,926 MMS rows, one active subscription, all eight role grants, and ready index
and state databases. The index was still scanning and had not reached verified
complete reconciliation, so this is responsiveness/reachability evidence only,
not a complete-history claim. Content-free UI tags proved the inbox, one
conversation, two visible bubbles, and composer were reachable without
retaining private text. Release-equivalent Macrobenchmark percentiles,
verified real-provider reconciliation, low-memory, and decoded-attachment
profile rows remain open.

A later owner-approved Phase 4 physical window supersedes only that historical
reconciliation limitation. Generation 10 initially reached `COMPLETE` with
zero pending changes: 10,183 verified SMS rows plus 4,926 verified MMS rows,
for 15,109 indexed messages. Both provider checkpoints were exhausted and
their verified counts matched. A later provider signal durably marked that
completed generation pending and triggered follow-up reconciliation. No
message body, address, query result, attachment, database, or broad log was
retained, and no carrier message was sent. Representative physical
Macrobenchmark, low-memory, and decoded-attachment profile rows remain open.

Physical-device performance targets after a warm index:

| Journey | P50 | P95/other |
|---|---:|---:|
| Warm start to usable inbox | <=300 ms | <=500 ms |
| Open text-only thread | <=250 ms | <=450 ms |
| Search at 500k rows | <=120 ms | <=220 ms |
| Jump to old indexed result | <=350 ms | <=650 ms |
| Long text-thread fling | n/a | <1% slow; 0 frozen frames |
| Text-only browsing PSS | n/a | aim <150 MiB |

## Phase 4 appearance/accessibility matrix

### AuroraMaterial foundation

- [x] A dedicated `:core:designsystem` leaf module owns immutable appearance
  profiles, tokens, palettes, and shapes without app/provider/index/state or
  transport dependencies.
- [x] The schema-v1 profile rejects unsupported schema, hue, and wallpaper-dim
  inputs before rendering.
- [x] The default profile preserves the Phase 3 primary/background colors and
  exposes a 48 dp minimum touch-target token.
- [x] Compact, comfortable, and spacious row tokens remain at or above the
  target floor; reduced motion resolves to zero decorative motion scale.
- [x] Circle, rounded-square, squircle, and hexagon masks resolve to concrete
  original Compose shapes.
- [x] Dark, AMOLED, Light, and system-dynamic/fallback color paths use platform
  and already admitted Compose APIs only.
- [x] The app root consumes the default AuroraMaterial theme; the appearance
  foundation itself does not change role, permission, provider, index, route,
  draft, notification, or transport contracts.
- [x] The foundation adds no external coordinate/version, artwork, media
  decoder, permission, component, native binary, initializer, or network path.
- [x] Separate physical-validation hardening admits new provider batches and
  head verification only while at least one Aurora messaging activity is
  started, preserves one already-admitted bounded unit, and resumes deferred
  work without falsely marking provider state dirty.
- [ ] Durable profile/override persistence, Theme Studio, navigation variants,
  approved artwork, local static/GIF assignments, and full surface/device
  accessibility remain follow-on slices.

### Phase 4 foundation and lifecycle evidence

Implementation commits `bbf1369` (AuroraMaterial), `edbb200` (physical
navigation/diagnostics), and `013452d` (foreground reconciliation lifecycle)
were verified on 2026-07-14.

The final offline host gate was:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease :app:lintBenchmark :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon --no-parallel --console=plain
```

It completed 883 tasks successfully. Clean-room verification checked all 19
private-reference hashes. Dependency, merged-manifest, packaged-permission, and
APK-content gates passed for debug, release, benchmark target, and both
macrobenchmark APK outputs. The separate license/SBOM gate produced 201 license
records with zero unknown or disallowed licenses, 441 CycloneDX components,
and 442 dependency nodes. Shell syntax checks and Python bytecode compilation
also passed for the repository scripts.

The final connected command was pinned to `AuroraSMS_API36`:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel --console=plain
```

It discovered 79 tests: app 16, database benchmark 3, index 29, notifications
3, durable state 12, telephony 15, and presentation 1. Seventy-eight passed
with zero failures/errors; `configuredScaleBenchmark_requiresExplicitOptIn`
was the single intentional skip. An initial final run exposed a warm
notification-route state race; the exact isolated regression and the complete
79-test suite passed after the stale route callback was guarded.

The final debug APK is 12,783,155 bytes with SHA-256
`fa2714bbcd10e070fbf72cea79a37050c2e082a99e4c0b45abea1e62bf65cb68`.
Those exact bytes were installed over the existing Pixel 8/API 36 app and
copied to `/sdcard/Download/AuroraSMS-debug.apk`; local, installed `base.apk`,
and Download hashes matched. Version `0.4.0-phase4`, the AuroraSMS default-SMS
role, and the READ/SEND/RECEIVE SMS, RECEIVE MMS, and notification grants were
present after installation.

Privacy-safe physical evidence used only counts, process state, package state,
and content-free Compose resource tags. A generic search query reached the
results section without exporting any result text. During a live provider
scan, Home allowed one admitted bounded unit to finish; the committed count then
remained unchanged across a second interval while the app process stayed alive.
Foreground resume continued from the same checkpoint. A provider change during
the resumed run set the durable pending flag and caused the expected clean
follow-up generation rather than losing the signal. The latest process exit
after installation was `PACKAGE UPDATED`; the prior excessive-CPU exit remains
historical evidence from before lifecycle hardening. No carrier message was
sent.

- [ ] Before ingestion, written artwork permission is recorded for the exact
  repository, derivative, APK/store, promotional, redistribution, and
  attribution uses. Originals remain outside Git if source redistribution is
  not granted.
- [ ] Original source hashes remain unchanged before/after derivative
  generation.
- [ ] Fresh default goldens map Inbox/Night, Archive/Cabinet,
  Settings/Office, and Spam & Blocked/Spam & Block.
- [ ] Bridge, City, Cat, and Cherry Blossom are available optional presets.
- [ ] Camera ICON and raw/private assets are absent from runtime/APK.
- [ ] WebP comparisons preserve composition/color character without dark
  gradient banding or unacceptable loss of stars, rain, hair, blossoms, and
  city detail.
- [ ] Reset/replacement works independently for every screen and conversation.
- [ ] Exact inheritance resolves conversation/global-thread/theme/solid and
  each canonical screen override/canonical/theme/solid chain.
- [ ] One conversation retains a synthetic family photo, another a synthetic
  GIF, while inbox/global defaults remain independent.
- [ ] Only the visible GIF animates; background, covered screen, display off,
  battery saver, and reduced motion pause it.
- [ ] List diffs, incoming messages, rotation, and font changes do not restart
  active animation unexpectedly.
- [ ] Process recreation restores wallpaper/animation state without running a
  background decoder or restarting multiple GIFs.
- [ ] Revoked URI, moved file, corrupt/single-frame/huge GIF, unsupported media,
  and decode failure have safe fallbacks.
- [ ] Focal crop works on short/tall phones, landscape, split screen, foldable,
  and tablet.
- [ ] Focal point and per-assignment dim persist through restart, rotation, and
  profile export/import; live preview, accessible dim floor, and scoped reset
  behave correctly.
- [ ] Wallpaper chooser shows correct labels/thumbnails and selected state for
  all eight built-ins plus local static/GIF sources; apply, cancel,
  reset-to-inherited, focal crop, and dim persistence are deterministic.
- [ ] Startup does not decode all built-ins; chooser/list previews are bounded
  static thumbnails, and leaving/switching a wallpaper releases the prior
  decoder without an orphan.
- [ ] Classic overflow, bottom bar, and adaptive rail have route, state, deep
  link, and back parity.
- [ ] Appearance, navigation, avatar, hue, bubble, and wallpaper changes do not
  query Telephony, invalidate/rebuild the index, or reconstruct the thread
  route.
- [ ] Circle, rounded square, squircle, and hexagon avatars work with photos,
  initials, groups, RTL, and 200% font.
- [ ] Dark, AMOLED, Light, Dynamic, global hue, and conversation hue meet
  contrast checks.
- [ ] Every built-in is contrast-tested on every eligible surface, including
  white text over Cherry Blossom and live error/destructive controls over the
  red motifs in Spam & Block; body text reaches 4.5:1 and important non-text
  affordances reach 3:1.
- [ ] Compact/comfortable/spacious density and all supported bubble/composer
  geometry keep 48 dp targets and essential actions reachable.
- [ ] Search results are opaque, IME-safe, and show no inbox/FAB collision.
- [ ] Search back handling dismisses menu, then IME/focus, then search state or
  route predictably while retaining the query when appropriate.
- [ ] Pinned items are not duplicated in Recent, and New chat never covers the
  last list row at any supported inset/font/density.
- [ ] Segment count appears only near an SMS boundary or when enabled.
- [ ] Archive has an accessible empty state; Spam & Blocked explains recovery
  and exposes unspam/unblock where applicable.
- [ ] Menus are state-aware, bounded/scrollable at 200% font, large-font safe,
  separate destructive actions, and show mutually exclusive spam/not-spam and
  block/unblock actions.
- [ ] Profile import rejects malicious fields, unsupported newer versions,
  duplicate names, missing media, executable content, and out-of-range tokens.
- [ ] Named profile export/import round-trips every supported token and approved
  media reference, with deterministic handling of missing/revoked media.
- [ ] Built-in wallpaper APK total is <=12 MiB or has an approved measured
  exception.
- [ ] Separate physical-device performance runs cover Classic overflow, Bottom
  bar, static wallpaper, and GIF wallpaper using the same controlled journeys;
  adaptive rail is added on a large-screen target.

## Phase 5 lifecycle/action matrix

- [ ] Scheduled send survives process death, reboot, and time/timezone change.
- [ ] Exact-alarm denial/revocation is honest and has documented fallback.
- [ ] Send-delay Undo survives process death without duplicate sends.
- [ ] Pending deletion has deterministic process-death behavior.
- [ ] Remembered subscription is durable and scoped per conversation.
- [ ] A removed/disabled remembered SIM prompts a safe fallback; scheduled and
  group sends never silently switch subscriptions.
- [ ] Whole-thread deletion uses stronger confirmation.
- [ ] After provider deletion commits, UI never claims recoverability.
- [ ] No recycle-bin UI/schema/preference/worker exists.
- [ ] Every group path still proves no individual fan-out.

## Phase 6 feature/privacy matrix

- [ ] Notification reminder scheduling is local and battery-conscious.
- [ ] Reaction fallback parsing never mutates stored SMS and handles ambiguity.
- [ ] Voice memo requests microphone only from explicit Record action, indicates
  recording, limits output, and cleans temporary files.
- [ ] Selected-text copy exposes only the selected content.
- [ ] Global/per-thread signatures show segment/MMS impact before send.
- [ ] Spam rules are bounded, explainable, contacts-trusting by default, and
  support unspam/unblock; suspected spam is never silently deleted.
- [ ] Versioned streaming backup validates limits, paths, checksums,
  authentication/encryption, schema, and media before atomic import.
- [ ] Android Auto notification and reply behavior passes device/host checks.
- [ ] No feature adds an undeclared network path.

## Release gate

- [ ] All relevant platform/device/telephony/message/lifecycle/appearance rows
  pass or have a documented owner-approved limitation.
- [ ] Release build installs, acquires role, sends/receives SMS and MMS, sends a
  group MMS without fan-out, and handles notifications/direct reply.
- [ ] Complete indexed search and exact old-result jump meet measured budgets.
- [ ] Every shipped database version has a migration test.
- [ ] R8/resource shrinking, Baseline Profile, APK size, and permission
  regression checks pass.
- [ ] Clean-room source/dependency/private-asset scans pass.
- [ ] Notices/SBOM, source license, artwork license/attribution, reproducible
  build instructions, security policy, and signed checksums are complete.
- [ ] F-Droid metadata builds from source without proprietary services.
- [ ] Release language accurately distinguishes SMS/MMS from RCS.

## Evidence record template

```text
Requirement/test ID:
Commit and variant:
Command or manual procedure:
Device/model/API/ROM/carrier (redacted as appropriate):
SIM state and synthetic fixture scale:
Expected:
Observed:
Pass/fail/blocked:
Non-sensitive artifact or trace:
Known limitation/follow-up:
Reviewer/date:
```
