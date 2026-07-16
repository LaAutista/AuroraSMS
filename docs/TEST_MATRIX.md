# AuroraSMS test matrix and phase-gate evidence

Status: Phase 3 implementation, deterministic profile generation, API 36
synthetic-emulator functional gate, and owner-approved Pixel 8 alpha
install/hash/smoke complete; Phase 4 AuroraMaterial foundation, foreground
provider-read lifecycle hardening, exact physical APK handoff, and verified
real-provider reconciliation complete, 2026-07-14. The bounded durable active
named-profile/Theme Studio slice is also verified; the ADR 0006 durable scoped
profile-reference foundation and its real-root/modal acceptance extension
passed host, governance, emulator, and physical install/package/role/permission
gates. The final frozen APK's physical scoped-modal focus, exact copy/hash, and
cold-launch gates also passed on an awake, unlocked Pixel 8. The physical check
is intentionally Inbox-only; full process-death end-to-end and physical eligible-
Thread modal coverage are not claimed. Representative physical-device
performance, remaining API/OEM coverage, and carrier transport rows remain
pending. ADR 0007's bounded managed private static Thread-wallpaper
implementation now includes crash-safe managed-store and quota hardening at
source commit `f0f1ff9`. Focused host, API 26/API 36 emulator, physical Pixel 8
filesystem, complete API 36 connected, release/governance, license/SBOM, and
exact Pixel APK handoff gates passed. The physical managed-store test was
non-UI filesystem coverage only. A later owner-gated Pixel 8/API 36 smoke now
partially covers the real global-thread Appearance editor and platform Photo
Picker with synthetic media through editor Cancel, wallpaper Back, Apply, and
Reset. Synthetic API 36 acceptance at
`b9350be354991e36039e8136095bc25ebd520d60` now covers verified-conversation
root pixels, focal/dim Apply, Activity recreation, reset and identity fallback,
stale-pixel clearing, and independent Room close/reopen/reset durability. An
exact gated API 36 AOSP Photo Picker cancellation journey using the
accessibility global Back action at
`826a20dbc3e965da8f269dde1351cf4d76d28f6c` also passes twice. A separately
gated API 26 AOSP DocumentsUI no-selection accessibility-Back journey at
`37fd044df3b9b8933839b0f89f7018ec72b8ab1b` independently confirms the
identical AndroidX SAF contract shape and the production editor's DocumentsUI
focus, and passes twice with preservation checks. Source commit `dd33737` adds a
separate API 26 AOSP DocumentsUI selection-lifecycle journey through the real
`MainActivity` global-thread editor: its exact local document provides
provider-open and preview evidence while the journey validates the expected
canonical bounded `content:` URI shape; Cancel, wallpaper Back, and Activity recreation discard
the transient preview; unavailable-source Apply reopens and rejects without
mutation; retry creates exactly one managed final and consumes exactly one
revision; managed load survives later source unavailability; and UI Reset
restores the empty assignment/file/persisted-grant baseline while that consumed
revision remains. Source commit
`65fc6552a877403523e499b457fdf015aaf6f753` adds a third narrow API 26 journey:
direct pre-Apply conversation-route replacement disposes the transient global
selection, and a later global stale-Apply conflict preserves the newer managed
winner while deleting the unreferenced candidate. Source commit
`12939eea321e8eb6a9a173a82cab2dfd245b64e5` adds a fourth API 26 journey: the
production notifier posts a fixed synthetic message after a real SAF selection,
and a real touchscreen expands the AOSP shade and taps its exact SystemUI row so
the production content `PendingIntent` disposes the editor route in the same
warm `MainActivity` without durable wallpaper, channel, or notification residue.
Real carrier/provider/receiver/orchestrator message origin; provider-backed and
verified identity; cold/absent-task, background, lockscreen, and process-death
delivery; raw `PendingIntent` action/extras/flags; API 27+, permission-denial,
OEM, and physical-shade behavior; reply/group/privacy/alert/new-channel variants;
raw picker results, temporary URI-grant revocation, readable source-byte/content
mutation, provider revocation/removal/replacement, cloud/blocking, in-flight
Apply, nonempty baselines, and any explicit Photo Picker Cancel control remain
open. A narrow API
36 emulator host-`am force-stop`
verified-conversation cold-target-process journey at
`73b5ffa2827ad2cd96b922ccf4a529b5b052529d` passes twice; `global_thread` cold
restart, production-launcher/real-provider, broader process-death UX,
accessibility/form-factor/performance, carrier, and compound
implementation-complete rows remain outstanding; the app is not complete or
gold.

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
`AuroraSMS_API26` API 26 and `AuroraSMS_API36` API 36 AVDs. Earlier physical
evidence below covers only the Pixel/API combination. Phase 3 connected
functional/profile-generation
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
- [x] The bounded durable active named-profile/Theme Studio slice meets the
  narrowly scoped verified rows below. Overrides, import/export, navigation
  variants, approved artwork, local static/GIF assignments, and full
  surface/device accessibility remain follow-on slices.

### Durable active profile and Theme Studio slice — verified 2026-07-14

These rows define the safe slice accepted by ADR 0005. Implementation commit
`325f2ce` passed the exact commands, variants, and non-sensitive outcomes
recorded below; a compile or Compose preview alone was not treated as
verification.

- [x] The Aurora state database exports schema version 2 and an explicit
  version-1-to-version-2 migration preserves durable draft rows, draft identity
  enforcement, and all version-1 invariants without destructive fallback.
- [x] Named profile rows and the active selection enforce bounded count/name
  rules, referential integrity, and one coherent active target; the canonical
  code-owned default requires no mutable database row.
- [x] Palette, density, avatar-mask, navigation, and bubble choices round-trip
  explicit stable codes rather than enum ordinals/declaration names. Unknown
  codes, unsupported profile schemas, and out-of-range numeric values never
  reach rendering and resolve safely to the canonical profile.
- [x] Narrow host color tests prove custom hue changes coherent static role
  families without changing canonical defaults, all 360 tested dark/light hue
  role pairs reach at least 4.5:1, and high contrast uses opaque black/white
  foreground/container pairs. This is not full surface/device accessibility
  acceptance.
- [x] Repository tests cover create/update plus activation in one transaction,
  optimistic-revision stale writes and deletes, duplicate-name conflicts,
  profile limits, reset-to-canonical, and deletion of the active profile
  without an invalid intermediate snapshot.
- [x] With no durable selection the app root uses the canonical profile; after
  successful `Apply`, the selected named profile survives recreation/relaunch
  and becomes the root theme only from the validated repository snapshot.
- [x] Theme Studio is reachable through the existing app-owned route, and its
  bounded in-memory preview covers only the admitted palette, hue, density,
  avatar, bubble, and high-contrast controls. Reduced motion remains a validated
  profile/token value but is not exposed until a production animation consumes
  it. Preview may recompose only the visible Appearance route/root theme subtree
  and never escapes to another route. No bottom-bar/adaptive-rail variant,
  override, import/export, wallpaper, or media behavior is implied.
- [x] Editing, profile selection, and Reset affect only the transient preview
  before commit. `Cancel`, system Back, and route disposal discard the draft
  and restore the durable active profile; failed or stale `Apply` leaves that
  durable selection unchanged.
- [x] Bounded saveable editor state restores deterministically after
  recreation, while an in-flight database operation, error, or confirmation
  dialog is not restored as a completed write.
- [x] The slice adds no external coordinate, DataStore, permission, exported
  production component, initializer, native binary, network path, artwork,
  media reference, or decoder. Its non-exported debug-only UI test host is absent
  from release/benchmark outputs, and appearance actions do not query Telephony,
  invalidate the message index, reconstruct a thread route, or touch carrier
  transport.

### Durable active profile and Theme Studio evidence

Version `0.4.1-phase4` (`versionCode=2`) at implementation commit `325f2ce`
was verified with the final offline host gate:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease :app:lintBenchmark :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon --no-parallel --console=plain
```

It completed 883 tasks successfully. The generated XML reports contain 218
host unit tests with zero failures, errors, or skips. Clean-room verification
checked all 19 private-reference hashes. Dependency, merged-manifest,
packaged-permission, and APK-content gates passed for debug, release, benchmark
target, and both macrobenchmark APK outputs. The non-exported
`ThemeStudioTestActivity` appeared only in the debug merged manifest and was
absent from release and benchmark manifests.

The final license/SBOM command was:

```text
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline --no-daemon --console=plain
```

It produced 201 license records with zero unknown/disallowed dependencies, 441
CycloneDX components, and 442 dependency nodes.

The final connected command was pinned to `AuroraSMS_API36`:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel --console=plain
```

It discovered 89 tests: app 19, database benchmark 3, index 29, notifications
3, durable state 19, telephony 15, and presentation 1. Eighty-eight passed with
zero failures/errors; `configuredScaleBenchmark_requiresExplicitOptIn` was the
single intentional skip. The full run initially exposed an asynchronous warm
notification-route race. After exact-ID pending acknowledgement and bounded
delivery synchronization, its isolated Gradle regression passed 1/1 and ten
direct stress repetitions passed 10/10 before the complete 89-test suite
passed.

The final debug APK is 13,016,704 bytes with SHA-256
`691f8e6a07a45a594630b41e115ac06977db05e3555672ce371975a6bb91f5fe`.
Those exact bytes were installed over the existing Pixel 8/Android 16/API 36
app and copied to `/sdcard/Download/AuroraSMS-debug.apk`; the local artifact,
installed `base.apk`, and Download copy hashes matched. The default-SMS role
and READ/SEND/RECEIVE SMS, RECEIVE MMS/WAP push, and notification grants all
remained present.

The physical acceptance used only resource IDs, known static control-label
bounds, and non-sensitive Room metadata. Opening the retained app migrated the
Aurora state database to version 2 with zero named profiles and the canonical
selection. More -> Appearance was reachable. Staging a new copy and pressing
Cancel left profile count, canonical selection, and snapshot revision
unchanged at `0`, canonical, and `1`. Applying a synthetic Light profile
atomically produced one active profile (`profile_id=1`, profile revision `1`,
snapshot revision `2`); the same active ID, revision, and palette survived a
force-stop/cold relaunch. Confirmed deletion returned atomically to zero named
profiles and canonical snapshot revision `3`, which also survived relaunch.
AuroraSMS was left force-stopped on the canonical profile. No message text or
address was exported, no screenshot was captured, and no carrier message was
sent.

### Durable scoped-profile-reference foundation and real-root/modal acceptance — verification complete

These rows define the bounded ADR 0006 acceptance slice. Checked rows are backed
by the scoped evidence below. Real-root behavior is backed by the production
root driven through deterministic synthetic services; the physical check is a
separate privacy-safe real MainActivity/Inbox smoke. This slice does not claim
the complete scoped-wallpaper/GIF feature, full process-death end-to-end, or
physical eligible-Thread modal coverage. The final frozen-artifact Inbox focus,
exact copy/hash, and cold-launch evidence passed as described below.

- [x] The Aurora state database exports schema version 3 and an explicit
  version-2-to-version-3 migration preserves drafts, draft triggers, named
  profiles, active selection, revisions, and the complete version-1-to-version-3
  migration path without destructive fallback.
- [x] The rebuildable Aurora index exports schema version 3. Its explicit
  version-2-to-version-3 migration preserves searchable rows but semantically
  invalidates every pre-v3 completeness claim by marking generations
  paused/pending, clearing completion/failure markers, and advancing the signal
  sequence. The next synchronization starts a fresh scan from empty checkpoints
  instead of resuming a stale version-2 cursor.
- [x] Screen assignments admit only stable `inbox`, `archive`, `settings`,
  `spam_blocked`, and `global_thread` codes. Unknown codes, Search, and
  Appearance/Theme Studio cannot become override scopes; controls exist only
  for the currently reachable Inbox and Thread flows.
- [x] The participant fingerprint matches ADR 0006 byte-for-byte: narrow NFC
  normalization only, exact deduplication, unsigned UTF-8 byte ordering, exact
  domain separator plus NUL, four-byte big-endian count/length fields, SHA-256,
  and lowercase `sha256-v1:` storage. Case, telephone formatting, short codes,
  alphanumeric senders, and email-style MMS addresses are not rewritten; empty
  and greater-than-100-participant inputs are rejected before hashing.
- [x] Conversation appearance rows store only the fingerprint, current positive
  provider thread ID, named-profile reference, and revision. Schema, entities,
  persisted assignment models, exceptions, logs, and `toString` expose no raw
  participant address, display name, participant serialization, message content,
  or contact ID. This does not describe the private rebuildable index or the
  ephemeral verified-identity projection used for immediate fingerprinting.
- [x] A conversation assignment can be created, rebound, or resolved only from
  verified-complete, non-truncated participants. Missing, incomplete,
  truncated, malformed, or mismatched identity falls through to
  `global_thread`; a thread-ID match alone never resolves the assignment.
  Dropped malformed SMS/MMS address rows taint the completeness signal even
  when valid rows survive, while the platform MMS insert-address placeholder
  alone is not treated as a participant.
- [x] Screen and conversation repositories expose target-specific flows and do
  not load all conversation assignments. Updating one target leaves every
  unrelated target emission and durable row unchanged. The first requested Room
  row-or-null is authoritative; the app exposes typed Loading until a positive
  profile snapshot revision and Ready observation for the exact target arrive.
- [x] Assignment Apply uses an expected revision, or an explicit must-be-absent
  creation expectation. Duplicate creates and stale updates return typed
  outcomes and commit no partial state.
- [x] Every actual screen/conversation assignment create or update allocates the
  next positive value from one durable global revision singleton in the same
  transaction. Reset, cascade deletion, reopen, and recreation never reuse an
  allocated revision; stale pre-deletion revisions cannot update/delete a
  recreated target. Physical triggers admit only the singleton at revision zero,
  require exact `old + 1` updates, and reject deletion; a sequence below any
  live-row revision, or missing/malformed/exhausted state, fails closed without a
  partial write.
- [x] Selecting `Inherited` changes only modal draft state. Cancel, Back, and
  dismissal are durable no-ops; Apply revision-checks and deletes only that
  target row, while a stale inherited Apply leaves the newer assignment intact.
- [x] Revision-checked named-profile deletion cascades all referencing screen
  and conversation rows in the same transaction. Each affected target emits
  its inherited result without an intermediate dangling profile reference.
- [x] Resolver tests cover conversation -> `global_thread` -> active named ->
  canonical, eligible screen -> active named -> canonical, Search -> active
  named -> canonical, and Theme Studio's existing route-local preview -> active
  named -> canonical. Missing profiles, unknown codes, unsupported schemas,
  corrupt values, and storage failures remain usable and never mutate another
  scope; the renderer's accessible-solid fallback remains available after
  canonical failure.
- [x] Inbox More reaches separate Inbox appearance and `Conversation defaults`
  (`global_thread`) modals. Thread More reaches `Conversation appearance` only
  when its current participant identity is verified; no private participant
  value appears in the modal or semantics tree. `ConversationSummary` remains an
  at-most-8-member display preview. The separately reviewed exact-thread
  follow-on uses a maximum-101-row query (100 plus one overflow sentinel) and
  makes 1-through-100-member conversations eligible only from a matching
  verified-complete, non-truncated generation with exact declared/returned
  participant count.
- [x] Synthetic modal/controller instrumentation covers selection, inherited
  reset, Cancel, window-level Back, recreation, delayed profile/assignment
  loading, target mismatch, missing-profile reset, errors, and in-flight writes.
  Restored state carries its exact private target token and cannot render/apply
  another target; controls wait for both authoritative inputs. No production
  Activity is added, and all three non-exported debug hosts are absent from
  release/benchmark products.
- [x] The real `AuroraSmsRoot`, driven by deterministic synthetic services,
  proves both bounded halves of the route-state contract. Live Inbox/global and
  exact-anchor Thread modal operations cover selection changes, Cancel, named
  Apply, inherited-reset Apply, Back, and dismissal while retaining the route,
  visible state, retained Search route/query, draft, and composer; no operation
  pushes Theme Studio or causes a provider/index presentation reload.
  `ActivityScenario` recreation reconstructs the holder and performs exactly
  one ADR 0003-permitted anchor query while restoring the exact modal target and
  selection, stable visible `ProviderMessageId` plus offset, Search query,
  draft, and composer. Fresh same-thread re-entry receives a unique route-state
  entry and exact jump; popped or evicted route entries remove their saved state,
  and retention remains bounded to `MAXIMUM_RETAINED_ROUTES`. This is not a full
  process-death end-to-end claim and makes no exact-anchor recreation claim for
  a normal Inbox or unanchored Thread.
- [x] Applied screen/global-thread/conversation references and their revisions
  survive database reopen and process recreation. Modal draft restoration never
  resumes an in-flight write or treats an uncommitted selection as durable.
- [x] Index rebuild/recovery, provider reconciliation/change signals, role loss,
  and role regain do not delete, rewrite, or globally invalidate appearance
  assignments. Appearance Apply, including an inherited Apply, performs no
  Telephony query/write, index mutation/rebuild, carrier action, or notification
  action.
- [x] The slice adds no external production coordinate, repository, DataStore owner,
  permission, production component, initializer, native binary, network path,
  artwork, media URI/reference, picker, decoder, assignment-local focal/dim
  state, GIF behavior, or private asset. Direct Android-test Espresso 3.5.0
  exposes an already-resolved transitive artifact to instrumentation compile;
  it introduces no new artifact/version, license/SBOM component, or product APK
  content.
- [x] Focused host and migration tests, full host lint/build gates, clean-room,
  private-asset, dependency/checksum/lock/license/SBOM, permission/APK-content,
  complete emulator instrumentation, and privacy-safe physical-device
  install/package/role/permission inspection, exact Download copy/hash, cold
  launch, and scoped-modal focus pass with no private conversation data or
  carrier send.
- [x] The final frozen APK passed a privacy-safe physical real-MainActivity check
  covering the Inbox modal only. On an awake, unlocked Pixel 8, the gated
  `MainActivityScopedAppearancePhysicalSmokeTest` passed one test in 2.098
  seconds using only package/view IDs and accessibility window metadata. It
  proved a distinct focused scoped dialog and Cancel's return to the same
  MainActivity/Inbox window without opening a Thread or applying an assignment.
  Aggregate appearance state remained `0|0|0` before and after for screen-row
  count, conversation-row count, and allocation revision. No physical eligible-
  Thread modal claim is made.

### Verified exact-thread identity follow-on

Local, frozen-artifact, Inbox-only Pixel, and source GitHub CI verification are
complete.

This follow-on fulfills the later query prerequisite identified by ADR 0006; it
does not retroactively expand the original frozen `0.4.2-phase4` evidence below.
Source commit `83db9aa0f02cef44644f53d0bb149abe459dc20b` is committed and pushed
on `origin/main`.

- [x] `VerifiedConversationIdentity` accepts only a positive generation and a
  valid, exact-distinct list of 1 through 100 participants sorted by stored
  address value. NFC normalization, deduplication, and byte sorting occur later
  during `AppearanceParticipantSetKey` derivation. Its `toString` exposes only
  participant count, never thread ID, generation, or addresses.
- [x] The exact-thread Room query is constrained by one positive provider thread,
  one positive generation, raw address-value ordering, and limit 101. Projection
  requires verified-complete matching coverage, matching
  entity/thread/generation, false truncation, a declared count in 1 through 100,
  exact row-count equality, and matching row thread/generation; overflow,
  exact-duplicate, malformed, stale, pending, or inconsistent data
  returns no identity.
- [x] The existing `ConversationSummary` remains capped at 8 preview members.
  Room instrumentation proves a verified nine-member conversation retains an
  eight-row preview with `indexedParticipantCount == 9` and
  `participantsTruncated == false`, while returning all nine exact participants
  only after generation verification; pending changes then revoke the identity
  by making coverage incomplete.
- [x] Existing private rebuildable Room rows persist participant addresses in
  `indexed_conversation_participants.address`. The exact-thread query projects
  those rows into an ephemeral, redacted `VerifiedConversationIdentity`; this
  derived object/list is not added to appearance/state persistence, logs,
  exports, or `SavedState`. App code immediately derives the existing one-way
  private participant-set key, and only the thread-hint/fingerprint restoration
  token is saveable.
- [x] `ThreadStateHolderIdentityTest` covers delivery of verified identity with a
  bounded timeline page, an initial Ready state that remains explicitly
  unresolved while the exact metadata lookup is delayed, and synchronous
  resolved-null clear-before-reload on invalidation.
  `ScopedAppearanceResolutionTest` covers nine-member eligibility with an
  eight-member display preview, indexed count 9, and false provider/index
  truncation, plus incomplete coverage, missing identity, exact generation/thread
  matching, private-target changes, Loading/unresolved-Ready restoration,
  resolved-null clearing, and terminal-loss dismissal. A restored target is not
  cleared merely because timeline Ready beats the identity lookup.
- [x] The focused real `AuroraSmsRoot` synthetic-service class now contains three
  passing emulator tests. Its new journey opens conversation appearance from a
  strict nine-member identity while the summary preview remains eight,
  `indexedParticipantCount == 9`, and `participantsTruncated == false`; it then
  removes the identity, observes automatic modal dismissal, and confirms the
  conversation appearance action is unavailable.
- [x] The consolidated host/lint/release/benchmark/governance/license run was
  `BUILD SUCCESSFUL` in 1m05s: 886 tasks, 66 executed. The separate
  `cyclonedxBom` run passed with all 15 tasks up-to-date.
- [x] The full API 36 emulator `connectedDebugAndroidTest` matrix was
  `BUILD SUCCESSFUL` in 57s: 455 tasks, 13 executed and 442 up-to-date; app 32,
  benchmark 4, core index 31, notifications 3, state 29, telephony 15, and
  feature conversations 3 tests.
- [x] The final debug APK is 13,212,416 bytes with SHA-256
  `39a07d72b7c58b91a11b152458ba971161b1edd98883f68df4fdbc6ab724235d`.
  It installed successfully on Pixel 8 serial `192.168.68.51:38677`; the copy at
  `/sdcard/Download/AuroraSMS-debug.apk` has the same size and SHA-256.
- [x] `org.aurorasms.app` was the sole Aurora package. The default-SMS role was
  restored, and `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, and
  `POST_NOTIFICATIONS` were granted.
- [x] The targeted privacy-safe `MainActivityScopedAppearancePhysicalSmokeTest`
  passed 1/1; its 197-task run was `BUILD SUCCESSFUL` in 17s. It covers only
  MainActivity/Inbox; it does not exercise a physical 9-member Thread.
- [x] A cold MainActivity launch reported `Status: ok`, launch state `COLD`,
  `TotalTime: 1112`, `WaitTime: 1114`, PID 4191, and `topResumed` MainActivity.
  The error-only PID log contained only the benign ashmem-pinning deprecation.
- [x] GitHub Verify
  [run 29380854714](https://github.com/LaAutista/AuroraSMS/actions/runs/29380854714)
  passed its build job in 10m59s with every step green: clean-room/dependency
  checks, test/lint/assembly, manifest/APK checks, licenses, CycloneDX, and
  reports. Its only annotation was the GitHub-hosted Node 20 deprecation and
  forced Node 24 for pinned actions, not a project failure.

Focused evidence currently consists of three host projection/model tests, two
Room `ConversationProjectionTest` cases, three holder identity/race tests,
the app resolver/editor unit coverage, app Android-test compilation/lint, and the
three-test real-root class. `ThreadStateHolderIdentityTest` passed 3/3 with zero
failures, errors, or skips; the focused runs and complete local gates passed. The
frozen artifact, Inbox-only Pixel gate, and source GitHub CI also passed.

### Durable scoped-profile-reference evidence — automated/install gates 2026-07-14

Version `0.4.2-phase4` (`versionCode=3`) retains the durable scoped foundation
from implementation commit `1b33852`; this evidence also covers the subsequent
real-root/modal acceptance extension in the implementation/review set recorded
by `docs/PHASE_4_FILE_PLAN.md`.

The final offline host gate was:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease :app:lintBenchmark :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline --no-daemon --no-parallel --console=plain
```

It completed 883 tasks successfully. Generated host XML reports contained 245
tests: 245 passed with zero failures, errors, or skips. Lint, debug/release/
benchmark assembly, macrobenchmark checks, all 19 clean-room private-reference
hashes, private-asset scans, dependency/lock verification, merged/package
permission checks, and APK-content checks all passed. Release and benchmark
outputs contained none of the three non-exported debug test Activities.

The final license/SBOM gate was:

```text
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline --no-daemon --console=plain
```

It completed 18 tasks successfully. The direct Android-test Espresso 3.5.0
declaration changed compile-classpath lock membership only: that exact artifact,
version, and transitives were already resolved by the admitted Compose test
runtime, and no new production coordinate or packaged artifact was introduced.

The complete connected command was pinned to the API 36 emulator:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel --console=plain
```

It discovered 113 tests: 111 passed with zero failures or errors, and two were
explicitly gated skips (the configured-scale benchmark and app physical scoped-
appearance smoke). The module totals were app 30 with one skip, benchmark 3 with
one skip, index 30, notifications 3, state 29, telephony 15, and conversations
3. Coverage includes state and index version-2-to-version-3 migration/schema
tests, sequence trigger/live-row-floor and ABA regressions, target-specific
repository flows, strict participant completeness, scoped resolver/controller
tests, the synthetic modal restoration/loading/Back suite, the real-root
acceptance journey, and conversation menu callback instrumentation.

The frozen final debug APK is 13,396,196 bytes with SHA-256
`d26a6a1c515d941ac38bb6b8ea1649d27f2ee3f9efc7f815ff74dfcebf164c03`.
Those exact bytes were replace-installed on connected serial
`192.168.68.51:38677`, an awake, unlocked Google Pixel 8 (`shiba`) on Android
16/API 36. The installed package reported version code 3, `0.4.2-phase4`, target
SDK 36, the active SMS role, and granted READ/SEND/RECEIVE SMS, RECEIVE MMS, and
notification permissions.

The gated `MainActivityScopedAppearancePhysicalSmokeTest` passed its one test in
2.098 seconds. It used only package/view IDs and accessibility window metadata
to prove a distinct focused Inbox scoped dialog and Cancel's return to the same
MainActivity/Inbox window without opening a Thread or applying an assignment.
Aggregate appearance state before and after remained `0|0|0`: zero screen rows,
zero conversation rows, and allocation revision zero. The temporary app test
instrumentation package was removed after the run.

The same target artifact was copied to
`/sdcard/Download/AuroraSMS-debug.apk`. The workstation and Download files were
both 13,396,196 bytes, and their SHA-256 values both equaled
`d26a6a1c515d941ac38bb6b8ea1649d27f2ee3f9efc7f815ff74dfcebf164c03`.
A force-stop/cold MainActivity launch returned `Status: ok`,
`LaunchState: COLD`, `TotalTime: 1081`, and `WaitTime: 1083`; PID 30201 was
alive and the expected MainActivity was resumed. The PID-only error log contained
only Android's ashmem-pinning deprecation and no app crash. These timings are
functional smoke evidence, not product performance evidence.

Earlier foundation-artifact privacy-safe private-database metadata after cold
launch showed state schema 3, the revision singleton at initial value zero, and
all three singleton/exact-increment/no-delete triggers. The index reported
schema 3; old generations were paused/pending by migration and a new generation
entered a fresh scan with the pending flag set, confirming that the older
completeness claim was not reused. This paragraph is not frozen-artifact cold-
launch evidence. The temporary app test instrumentation package was absent
after that earlier run.

An earlier final-artifact gated attempt ran on connected serial
`192.168.68.51:35459`, a Google Pixel 8 (`shiba`) on Android 16/API 36. Because
the device was dozing behind the secure lockscreen, no app resource ID was
reached and that attempt is not a modal-focus result. Aggregate private database
metadata before and after remained `0|0|0`: zero screen rows, zero conversation
rows, and revision zero. The successful unlocked run above supersedes that
non-run as final-artifact physical acceptance evidence.

No message text, address, participant fingerprint, accessibility text/content
description, UI hierarchy, screenshot, or private asset was exported, and no
carrier message was sent. This physical result covers only the Inbox modal; it
does not cover an eligible Thread modal or full process-death end-to-end.

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

The foundation-slice debug APK was 12,783,155 bytes with SHA-256
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

### ADR 0007 managed private static Thread wallpaper — bounded implementation landed; acceptance outstanding

Current crash/quota source commit `f0f1ff9` is committed; initial implementation
commit `c957995e74c7ba76ed25d1b7c4d23c05f42852be`, acceptance hardening commit
`975009f2b2c99cf389fb8020b270fd7c5bbf0bb2`, and renderer-isolation commit
`e5aa4dfb1c695046c136d07e6b0c549e77e278ee` remain part of the retained evidence
chain. Checked rows are backed by the synthetic and privacy-safe artifact
evidence below. Compound rows remain unchecked whenever any named boundary or
journey has not run; this section does not claim complete ADR 0007 acceptance.

- [x] State schema version 4 has an explicit version-3-to-version-4 migration
  that preserves drafts, profiles, active selection, scoped profile references,
  triggers, and the complete version-1-to-version-4 path while creating empty
  direct global-thread/conversation wallpaper assignment tables. There is no
  media catalog table or destructive fallback.
- [x] Wallpaper rows store only the stable screen code or ADR 0006 participant
  fingerprint/current thread hint, `static_raster_v1` kind, `sha256-v1:` token,
  focal X/Y 0..1,000, dim 350..900, and revision. Models/entities/exceptions/logs/
  `toString` expose no picker URI, path, file name, source metadata, participant
  value, or message data.
- [x] Wallpaper writes use the existing protected non-reusing appearance
  revision sequence. Its live-row floor includes profile-reference and
  wallpaper rows; create/update/reset/recreate, stale Apply, sequence rollback,
  corruption, database reopen, and conversation-thread rebinding retain the ADR
  0006 CAS/ABA guarantees.
- [x] Production controls and rendering exist only for `global_thread` and an
  exact verified conversation. Resolution is conversation managed wallpaper ->
  `global_thread` managed wallpaper -> accessible solid. Thread-ID-only,
  pending/incomplete/truncated/changed identity, Search, Theme Studio, Inbox,
  Archive, Settings, and Spam & Blocked cannot acquire wallpaper authority.
- [ ] Photo Picker/SAF fallback returns one transient `content:` URI with a
  non-empty authority and <=4,096 UTF-8 bytes. It never enters Room, files,
  preferences, `SavedState`, logs, or analytics; Aurora takes no persistable
  grant. Pick, preview, Cancel, Back, lost target/source, recreation loss, and
  failed/stale Apply create no durable assignment or managed file.
- [x] A separately gated API 26 AOSP DocumentsUI no-selection journey proves
  the identical AndroidX `PickVisualMedia(ImageOnly)` contract resolves as
  `ACTION_OPEN_DOCUMENT` with `image/*`, independently proves the production
  editor's Pick action focuses DocumentsUI, and returns through the
  accessibility global Back action with its exact pre-launch assignment,
  managed-file-name, and persisted-grant-identity baselines intact. It does not
  intercept the production outgoing intent, select a document, or close the
  compound Photo Picker/SAF row above.
- [x] A second, separately gated API 26 AOSP DocumentsUI journey drives the real
  `MainActivity` global-thread editor and production contract through one exact
  local-only test root and read-only PNG. It obtains provider-open and preview
  evidence and validates the expected canonical `content:` URI shape with
  non-empty authority and at most 4,096 UTF-8 bytes; discards transient
  preview while keeping exact assignment/revision/persisted-grant identity and
  no-follow managed-file ledger unchanged across editor Cancel, wallpaper Back,
  and Activity recreation;
  reopens and rejects an unavailable source on Apply without allocating a
  revision; then retries to exactly one managed final and exactly one revision,
  loads its 40x20 managed raster while the source is unavailable, and UI-Resets
  the empty assignment/file/grant baseline. Reset does not reclaim the consumed
  revision. This one synthetic emulator journey does not close the compound
  Photo Picker/SAF row above.
- [x] A third, separately gated API 26 AOSP DocumentsUI journey selects the exact
  synthetic document, then directly delivers the production open-conversation
  intent to the real `MainActivity` before Apply. The route replacement
  dismisses both editors; returning to Inbox and reopening the empty-baseline
  editor leaves Apply disabled, does not reopen the source, and preserves the
  exact assignment, revision, persisted-grant identity, and no-follow managed
  file ledger. After a fresh real selection, a controlled production-controller
  write commits one newer global winner. The stale UI Apply reopens the selected
  source, surfaces the exact stale-assignment error, preserves that winner and
  its managed load/revision/file/persisted-grant state, and deletes its own
  candidate. UI Reset removes only the controlled winner and restores the empty
  assignment/file/persisted-grant baseline while the one consumed revision
  remains. A host controller test separately proves the late repository
  `StaleWrite` call order, second authoritative reference read, and created
  candidate deletion. This is direct pre-Apply `onNewIntent` route-disposal and
  one global assignment-CAS conflict only: it does not prove an end-to-end
  notification/PendingIntent launch, in-flight Apply cancellation, verified-
  conversation identity loss, or temporary URI-grant cleanup, and it does not
  close the compound Photo Picker/SAF row above.
- [x] A fourth, separately gated API 26 AOSP journey stages the exact synthetic
  local PNG through the real `MainActivity`/DocumentsUI/AndroidX SAF path without
  Apply, posts one fixed synthetic `IncomingMessageNotification` through the
  production notifier, fingerprints its exact active system notification and
  Aurora activity content `PendingIntent`, opens the real notification shade by
  touchscreen swipe, and taps the exact controlled SystemUI row/body. The same
  warm `MainActivity` consumes the exact synthetic Thread ID/action and dismisses
  both editors without reopening the source. Assignment, revision, no-follow
  managed-file ledger, persisted-grant, active-notification, and complete post-
  bootstrap channel baselines, including each channel's DND-bypass setting,
  remain exact; reopening shows disabled Apply, no load/error, and no staged
  selection. This does not originate from a real
  incoming carrier/provider/receiver/orchestrator message, prove a provider-
  backed or verified conversation, cover cold/background/lockscreen/process-
  death or API 27+/OEM/physical behavior, or close the compound Photo Picker/SAF
  row above or any real incoming-SMS row.
- [x] Import authoritatively accepts only 8-bit Huffman baseline sequential-DCT
  (`SOF0`) JPEG with at most four components and complete scan coverage, or
  CRC-valid non-APNG PNG with at most 4,096 chunks, no
  `iCCP`/`zTXt`/`iTXt` ancillary chunks, and a complete zlib scanline stream. It
  rejects MIME contradiction, every other reviewed JPEG process/precision, GIF,
  every input WebP, HEIF, AVIF, APNG, truncated/corrupt data, known/unknown
  source >16 MiB, edge >8,192, or source >40,000,000 pixels before an
  unbounded/full decode.
- [ ] Every orientation form is normalized consistently on API 26 and the
  newer decoder path; output is software sRGB/ARGB_8888 and <=2,048 pixels per
  edge, <=4,194,304 pixels, and <=16 MiB allocation. Chooser preview is <=512
  pixels per edge, <=262,144 pixels, and <=1 MiB allocation.
- [ ] Explicit Apply strips metadata and encodes a static quality-95 WebP no
  larger than 8 MiB. Its SHA-256-derived fixed name lives only under
  `noBackupFilesDir/appearance/wallpapers/`; hash/header/dimensions/allocation
  are revalidated before render, and source URI/bytes/metadata are not retained.
- [x] The durable assigned set admits at most 128 distinct referenced files and
  256 MiB total; same-hash assignments deduplicate. A replacement at full quota
  may use exactly one serialized unassigned <=8-MiB candidate before Room CAS,
  with a physical ceiling of 129 files/264 MiB and never a second candidate.
  Quota failure preserves current state, and no assigned target is LRU-evicted.
- [x] The app-private single-process namespace has one process-wide mutation
  mutex. Import durably creates parents; uses `O_EXCL|O_NOFOLLOW` pending write,
  flush/sync, digest and exact device/inode/single-link verification; rechecks
  final absence; atomically renames and reverifies; delivers a synchronous
  cleanup lease; and syncs the leaf directory before Room CAS. Failed/stale/
  rejected/cancelled and old-file cleanup uses fresh bounded Room authority.
  Startup uses a fail-closed two-pass scan: unsafe namespace or unavailable/
  corrupt/over-limit authority causes zero partial deletion.
- [ ] Missing, malformed, unexpected-name/symlink, or hash-mismatched managed
  media never publishes pixels or mutates another row. It produces explicit
  recovery and the exact conversation -> global-thread -> solid fallback.
- [x] A target/token/revision/request change synchronously publishes solid
  before any suspend/decode; late results cannot flash a previous conversation's
  bitmap. Wallpaper work is one-at-a-time, the full cache retains only the
  current <=16-MiB allocation, and the shared MMS/wallpaper full-decode gate
  never exceeds two.
- [x] An explicitly gated API 36 ranchu/goldfish emulator runner proves one
  synthetic verified-conversation assignment survives host `am force-stop`
  after commit and reopens in a fresh target process through the production
  Room/controller/managed-store path. It observes pending-file removal,
  revalidates the referenced final, renders expected dimmed pixels through the
  real root Thread surface in a debug-only synthetic-services host, and restores
  the post-reconciliation managed-file-name and grant-count baselines. This row
  does not cover `global_thread`, production-launcher/real-provider rendering,
  UI Apply/Reset, picker/SAF/source loss, physical/OEM/performance, or in-flight
  process death.
- [ ] Focal/dim live preview, revision-checked Apply/reset, configuration/reopen
  persistence, 200% font/scroll, TalkBack labels/state, RTL, short/tall,
  landscape, and split-screen tests pass without route/state-holder/provider/
  index/composer/draft reconstruction caused by wallpaper actions.
- [x] Production merged manifests and APKs add no storage/media/network
  permission,
  persistent-grant component, exported component, initializer, native binary,
  private artwork, or new dependency coordinate. Backup/data-extraction rules
  and `noBackupFilesDir` exclude all state, managed derivatives, previews, and
  pending files.
- [ ] Host policy/state tests, migration/repository/reopen instrumentation,
  hostile importer/store/cleanup tests, combined decode-concurrency tests,
  root/UI acceptance, API 26 legacy decode, API 36 connected, complete offline
  build/lint/governance/license/SBOM/APK gates, and privacy-safe physical static
  Thread-wallpaper journeys pass before implementation-complete is claimed.

#### ADR 0007 bounded implementation evidence — automated/install gates 2026-07-14

The complete offline host gate was:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease :app:lintBenchmark :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents checkLicense generateLicenseReport --offline --no-daemon --no-parallel --console=plain
```

It was `BUILD SUCCESSFUL` in 1m06s: 886 actionable tasks, 91 executed,
2 from cache, and 793 up-to-date. Unit tests, debug/release lint and assembly,
benchmark/macrobenchmark checks, clean-room/private-asset/dependency checks,
merged-manifest and APK permission/content checks, license checks, and the
license report passed. The separately required `cyclonedxBom` invocation was
`BUILD SUCCESSFUL` in 7s with all 15 tasks up-to-date.

The complete connected command pinned to API 36 was:

```text
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline --no-daemon --no-parallel --console=plain
```

It was `BUILD SUCCESSFUL` in 58s: 456 actionable tasks, 9 executed and 447
up-to-date. App 40, core index 31, notifications 3, state 40, telephony 15,
feature conversations 4, and the bounded benchmark guards passed. The
explicitly physical-only MainActivity scoped-modal smoke and opt-in scale
benchmark were skipped as designed.

The focused legacy-decoder command ran
`ManagedWallpaperStoreTest` on the Android 8.0/API 26 `AuroraSMS_API26` AVD;
all 5 tests passed. The same class passed 5/5 on API 36. Retained coverage
includes JPEG and PNG EXIF orientation, pixel-exact legacy transforms for all
eight orientations, ARGB_8888/sRGB normalization, static PNG happy-path import/
deduplication/load/reconcile, and fail-closed invalid-scheme, MIME-mismatch,
APNG, GIF, and truncated-input cases. At this source snapshot, the compound
every-orientation/new-decoder row remained unchecked because every orientation
was not exercised through the API 28+ `ImageDecoder` path. The compound Apply/
derivative row also remained unchecked pending metadata, exact quality-95/
encoder-boundary, no-backup placement, and tamper evidence. The 2026-07-15
follow-up below closes most, but not all, of that compound row.

Repository instrumentation passed 10/10 and covers shared protected sequence
allocation, independent reset, last-reference reporting, conversation rebinding,
prospective CAS/quota projection, unsupported-scope rejection, corrupt-row
fail-closed enumeration/reset, the 129th-media overflow sentinel, and sequence
rollback below a live-row floor. Migration/schema/reopen instrumentation also
passed in the complete API 36 matrix. Controller and resource-owner unit tests
cover conversation -> global-thread -> solid resolution, high-contrast solid,
stale-target rejection, exact prospective quota ordering, failure cleanup, and
late resource handoff/disposal. At this source snapshot, these tests did not
substitute for real-filesystem quota, crash-cleanup, combined MMS/wallpaper
concurrency, or physical picker journeys. Version 3-to-4 and the version 1-to-3
chain were covered, but the direct version-1-to-version-4 and reset/recreate ABA
tests had not yet run. The 2026-07-15 follow-up below supplies that missing
migration and CAS/ABA evidence and checks those two compound rows.

The exact debug APK built from source commit
`c957995e74c7ba76ed25d1b7c4d23c05f42852be` is 13,993,426 bytes with SHA-256
`188c6d6d692116dc3dedc33dae03f65e66d32fd154ea4860aa24a996456b09df`.
It installed successfully on API 36 Pixel 8 serial `192.168.68.51:38677`; the
copy at `/sdcard/Download/AuroraSMS-debug.apk` has the exact same size and hash.
The installed package is version `0.4.2-phase4` (`versionCode=3`, target 36),
remained the default-SMS role holder, and retained granted `READ_SMS`, `SEND_SMS`,
`RECEIVE_SMS`, `RECEIVE_MMS`, and `POST_NOTIFICATIONS` permissions. A clean
MainActivity launch reported `Status: ok`, launch state `COLD`, `TotalTime: 1000`,
`WaitTime: 1003`, PID 13329, and resumed/focused MainActivity. Its error-only PID
log contained only Android's benign ashmem-pinning deprecation. No screenshot,
message/address export, physical wallpaper selection, or carrier send occurred.

GitHub Verify
[run 29389036364](https://github.com/LaAutista/AuroraSMS/actions/runs/29389036364)
passed its build job in 13m39s with every project step green: wrapper identity,
clean-room/dependency checks, test/lint/assembly, merged-manifest/APK checks,
license inventory, aggregate CycloneDX SBOM, and governance-report upload. Its
only annotation was the GitHub-hosted Node 20 deprecation and forced Node 24 for
pinned actions, not a project failure.

#### ADR 0007 acceptance follow-up evidence — 2026-07-15

Source commit `975009f2b2c99cf389fb8020b270fd7c5bbf0bb2` hardens managed
derivative verification and adds the retained acceptance evidence in this
subsection. The pre-fix WebP predicate admitted any byte sequence with RIFF and
WEBP magic, including animated or malformed containers. The replacement parser
validates the exact RIFF length, bounded padded chunk traversal, the VP8X
reserved and animation flags, rejects `ANIM`/`ANMF`, and requires exactly one
`VP8 `/`VP8L` image payload before the platform decoder revalidates pixels.

Focused host tests passed `WallpaperControllerTest` 8/8 and
`WallpaperImportPolicyTest` 6/6. Focused API 36 app instrumentation passed 19
tests across the managed store, real MMS preview loader/shared decode gate, and
SavedState saver; focused wallpaper repository plus direct migration
instrumentation passed 12/12. A tightened migration-only rerun passed both the
direct version-1-to-version-4 and version-3-to-version-4 cases.

The direct migration preserved the version-1 draft body, subject, and
timestamps; seeded the canonical selection; created empty screen and
conversation wallpaper tables; retained required triggers; passed foreign-key
validation; and created no media-catalog table. The retained version-3 fixture
additionally preserved the named profile, active profile/snapshot revision,
both scoped profile references, and the protected revision sequence. Together
these results check the compound migration row.

Wallpaper repository instrumentation now resets and recreates one target,
proves the recreated revision is greater, and proves the pre-reset revision is
stale for prospective projection, set, and reset without disturbing the
recreated assignment or sequence. Controller tests additionally reject both a
new import and existing managed media when the target changes after prospective
quota validation; a new uncommitted derivative is cleaned only after an
authoritative unreferenced check. Together with retained create/update,
rollback, corruption, reopen, and conversation-rebinding coverage, these
results check the compound CAS/ABA row.

Managed-store instrumentation now proves the exact no-backup directory and
content-addressed filename, removal of synthetic JPEG COM/EXIF/XMP metadata,
animated/malformed-container and hash-tamper rejection, edge-bound rejection,
a real 2,048-square high-entropy encoder path at the exact 16-MiB allocation
ceiling, and acceptance of an installed exact-8-MiB static container with
rejection at 8 MiB plus two bytes. These are partial compound-row results: the
exact 8-MiB fixture is padded and installed rather than emitted by the real
encoder, quality 95 is not independently captured, and the one-pixel compound
overage is not an isolated allocation-only rejection. The Apply/derivative row
therefore remains unchecked.

The final combined real-client test deterministically holds both permits inside
two MMS repository reads, runs wallpaper inspection undispatched through its
source read, and proves the saturated shared gate is its first suspension; the
wallpaper proceeds only after a permit is released. Saver instrumentation
round-trips only schema, private target key, expected revision, dim, and focal
integers, with no `content:` URI or managed-media token. At this source
snapshot, these results did not exercise picker lifecycle or renderer
synchronous-solid/request-epoch behavior. The renderer follow-up below closes
only the latter compound row; picker lifecycle remains unchecked.

The exact-source complete offline host command above was `BUILD SUCCESSFUL` in
13s: 886 actionable tasks, 28 executed, 2 from cache, and 856 up-to-date. Its
unit, lint, debug/release/benchmark assembly, macrobenchmark, clean-room,
private-asset, dependency, permission/APK-content, license, and report gates all
passed. The required `cyclonedxBom` invocation was `BUILD SUCCESSFUL` in 7s
with all 15 tasks up-to-date.

The complete API 36 command above was `BUILD SUCCESSFUL` in 59s: 456 actionable
tasks, 9 executed, and 447 up-to-date. App 49, benchmark 4, core index 31,
notifications 3, state 42, telephony 15, and feature conversations 4 passed.
The physical-only scoped-modal smoke and opt-in scale benchmark were skipped as
designed. The focused `ManagedWallpaperStoreTest` also passed 10/10 with zero
failures, errors, or skips on a wiped Android 8.0/API 26 `AuroraSMS_API26` AVD;
that disposable emulator was shut down after the run.

The exact debug APK from source commit
`975009f2b2c99cf389fb8020b270fd7c5bbf0bb2` is 13,993,426 bytes with SHA-256
`4b24e1595755af250bb0d89a703708d94bb9d539d10a66e9c17d0f4213472197`.
It installed successfully on Pixel 8 serial `192.168.68.51:38677`; the copy at
`/sdcard/Download/AuroraSMS-debug.apk` has the same size and hash. The installed
package is version `0.4.2-phase4` (`versionCode=3`, target 36), remained the
default-SMS role holder, and retained granted `READ_SMS`, `SEND_SMS`,
`RECEIVE_SMS`, `RECEIVE_MMS`, and `POST_NOTIFICATIONS`. The wireless ADB
endpoint went offline immediately before the planned cold-launch repeat, so no
new launch or PID-log result is claimed for this commit. No screenshot,
message/address export, physical wallpaper selection, or carrier send occurred.

GitHub Verify
[run 29398649372](https://github.com/LaAutista/AuroraSMS/actions/runs/29398649372)
passed its build job in 14m22s with wrapper identity, clean-room/dependency,
test/lint/assembly, manifest/APK, license, aggregate CycloneDX, and governance
report steps green. Its only annotation was the GitHub-hosted Node 20
deprecation and forced Node 24 for pinned actions, not a project failure.

#### ADR 0007 renderer-isolation follow-up evidence — 2026-07-15

Source commit `e5aa4dfb1c695046c136d07e6b0c549e77e278ee` closes an
equal-candidate target gap in the Thread renderer. Before this change, a new
thread target inheriting the same `global_thread` candidate produced a
structurally equal candidate list, so the renderer had no local target-change
key. The root now creates a fresh opaque `WallpaperRenderRequestEpoch` whenever
the route entry or verified private restoration target changes. The epoch
contains no route, participant, media-token, or other target data and keys both
the complete owned renderer state and its load effect.

`ManagedWallpaperSurfaceTest` passed 4/4 on API 36. It uses only synthetic solid
pixels and a controlled store to prove that target/token changes, same-media
revision/focal/dim changes, and an equal inherited-global target-epoch change
leave the theme solid published and the old bitmap already recycled while the
replacement load is suspended at its first controlled handoff. A deliberately
non-cooperative old load completes late;
its bitmap is recycled and never appears over the new target. A failed new load
remains solid, a successful new load publishes only its own pixels, and surface
disposal recycles the current bitmap. The retained `WallpaperResourceOwnerTest`
separately counts replacement, disposal, and late handoff releases exactly
once.

Combined with the retained serialized managed-store/allocation boundary and
the real-client test that saturates both shared MMS decode permits before
wallpaper inspection, this evidence checks the compound synchronous-solid,
late-result, one-current-cache, and shared-gate row. It does not add or claim a
user-visible same-target retry action.

The complete API 36 matrix was `BUILD SUCCESSFUL` in 1m03s. App 53, benchmark
4, core index 31, notifications 3, state 42, telephony 15, and feature
conversations 4 passed; the physical-only scoped-modal smoke and opt-in scale
benchmark were skipped as designed. The complete host/release/governance command
and `cyclonedxBom` also passed; the latter was `BUILD SUCCESSFUL` in 12s with all
15 tasks up-to-date.

The exact debug APK from this source commit is 13,993,426 bytes with SHA-256
`b651ad9141c7f45ec81f25d19ce9e82dd9af944c593047e0c76bf390eadf957f`.
Only the API 36 emulator was connected, so this source commit has no claimed
Pixel install, Download copy, role/grant recheck, cold launch, physical
wallpaper selection, screenshot, message/address export, or carrier send.

#### ADR 0007 hostile-importer follow-up evidence — 2026-07-15

The source gate now treats platform decode as a second check rather than proof
that encoded media is complete. The accepted JPEG subset is 8-bit Huffman
baseline sequential DCT (`SOF0`), at most four components, with every component
covered by complete entropy scans. Progressive, extended sequential,
arithmetic, lossless, differential/hierarchical, and non-8-bit JPEG fail closed.
Static PNG requires valid chunk names/CRCs, at most 4,096 chunks, and a complete
bounded zlib scanline stream; `acTL`, `fcTL`, `fdAT`, `iCCP`, `zTXt`, and
`iTXt` are all rejected.

The focused host command was:

```text
./gradlew :app:testDebugUnitTest \
  --tests org.aurorasms.app.appearance.wallpaper.JpegCompletenessPolicyTest \
  --tests org.aurorasms.app.appearance.wallpaper.PngCompletenessPolicyTest \
  --tests org.aurorasms.app.appearance.wallpaper.WallpaperImportPolicyTest \
  --tests org.aurorasms.app.preview.StaticImagePolicyTest \
  --offline --no-daemon --no-parallel --console=plain
```

It passed 23/23 across
`JpegCompletenessPolicyTest`, `PngCompletenessPolicyTest`,
`WallpaperImportPolicyTest`, and `StaticImagePolicyTest`. They cover complete
baseline entropy and restart cadence, forged end markers after premature
entropy, malformed/oversubscribed Huffman tables, rejected JPEG
processes/precision, complete non-interlaced and Adam7 PNG scanlines, split
`IDAT`, invalid filters, extra/truncated zlib output, palette indices, PNG chunk
ordering/names/CRC/count, compressed ancillary rejection, all three APNG chunk
types, source-format signatures, MIME contradiction, and exact
source-dimension arithmetic.

The focused device commands were:

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.app.appearance.wallpaper.ManagedWallpaperStoreTest \
  --offline --no-daemon --no-parallel --console=plain
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.app.appearance.wallpaper.ManagedWallpaperStoreTest \
  --offline --no-daemon --no-parallel --console=plain
```

`ManagedWallpaperStoreTest` passed 15/15 with zero failures or skips on both the
Android 8.0/API 26 `AuroraSMS_API26` AVD and the API 36 AVD. The provider-backed
matrix accepts the exact 4,096-byte UTF-8 URI and 256-character declared MIME,
then rejects the one-byte/one-character overages before opening media; it also
rejects an over-limit multibyte URI and a blank authority. The exact 16-MiB
known-length source imports, while known- and unknown-length 16-MiB-plus-one
sources fail typed. Exact 8,192-edge and 40,000,000-pixel PNGs import; the
8,193-edge and 40,000,009-pixel fixtures fail typed.

The same device matrix rejects a disappeared provider source; real WebP; VP8,
VP8L, and VP8X signatures; GIF87a/GIF89a; HEIF, HEIC, and AVIF signatures; valid
progressive JPEG; synthetic extended sequential, lossless,
differential/hierarchical, arithmetic, and non-8-bit JPEG; and PNGs containing
genuine `acTL`, `fcTL`, or `fdAT` APNG chunks. A baseline JPEG with truncated
entropy followed by a forged EOI, a malformed JPEG segment, and a corrupt PNG
zlib stream all fail inside the completeness gate. Each hostile helper asserts
the typed result before cleanup and proves that no managed or pending artifact
was created.

The complete offline host, lint, release, benchmark, governance, license, and
APK gate was `BUILD SUCCESSFUL` in 1m10s: 886 actionable tasks, 79 executed, 7
from cache, and 800 up-to-date. The separately required `cyclonedxBom` command
was `BUILD SUCCESSFUL` in 7s: 18 actionable tasks, 1 executed and 17 up-to-date.
The resulting debug APK is 13,993,426 bytes with SHA-256
`ba99f50b9dedd159ece0b9a44c29638bae510955cf623824b70a342da9765216`.
Only the API 26 and API 36 emulators were connected, so this source snapshot has
no claimed physical-device install, Download copy, picker journey, screenshot,
carrier send, or role/grant recheck.

By itself, this evidence checked only the hostile-importer row above; the
subsequent evidence below separately checks the managed-file crash/quota rows.
It did not claim complete picker lifecycle, full wallpaper acceptance, a
physical-device wallpaper journey, or a completed application.

#### ADR 0007 crash-safe managed-store follow-up evidence — 2026-07-15

Source commit `f0f1ff9` makes the app-private single-process namespace
authoritative under one process-wide mutex. It durably creates parent
directories; creates pending leaves with `O_EXCL|O_NOFOLLOW`; writes, flushes,
syncs, and verifies digest plus exact device/inode/single-link identity;
immediately rechecks final absence; atomically publishes with same-directory
`Os.rename`; and reverifies exact identity/content. The store delivers the
candidate cleanup lease synchronously before any suspendable post-publication
checkpoint and syncs the leaf directory before import returns for Room CAS.
Candidate and old-file cleanup reacquire fresh bounded Room authority. Startup
reconciliation validates every direct entry in a no-deletion first pass, then
removes only exact pending or conforming unreferenced finals; any unsafe entry,
invalid/over-limit authority, or storage failure causes zero partial deletion.

The focused host matrix passed 32/32: `WallpaperControllerTest` 20,
`ManagedWallpaperFilePolicyTest` 7, and `WallpaperQuotaPolicyTest` 5. The
combined device matrix passed 29/29 (`ManagedWallpaperStoreTest` 15 plus
`ManagedWallpaperCrashProtocolTest` 14) independently on the API 26 emulator,
API 36 emulator, and physical Pixel 8/API 36. It covers exact 128/256-MiB durable
and 129/264-MiB staging bounds, second-candidate rejection, cancellation/Room
commit ambiguity, persistence checkpoints, no-clobber publication, identity
verification, owned cleanup, crash orphans, and fail-closed two-pass recovery.

The complete API 36 connected matrix was `BUILD SUCCESSFUL` in 1m16s. The app
ran 71 tests with one intentional physical-only skip and zero failures; all
benchmark, core, and feature suites also passed. The complete 886-task offline
host/release/benchmark/governance/APK gate was `BUILD SUCCESSFUL` in 1m24s, and
the separate license/SBOM command was `BUILD SUCCESSFUL` in 9s.

The resulting debug APK is 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`. The
exact APK installed and cold-launched on the Pixel 8, and its
`/sdcard/Download/AuroraSMS-debug.apk` copy matched that hash and size.

This evidence checks exactly the durable quota and crash-safe import/reconcile
rows above. The physical 29-test run was non-UI app-private filesystem coverage;
it does not prove the Photo Picker or static-wallpaper UI journey. The compound
overall row, manual wallpaper journey, carrier behavior,
accessibility/form-factor/performance, broader Phase 4 work, and completed/gold
application all remain unclaimed.

#### ADR 0007 physical global-thread Photo Picker partial evidence — 2026-07-15

Source commit `111381dff31c46380eab969dea20234cba16fe08` passed the explicitly
gated physical command:

```text
./scripts/run-physical-wallpaper-picker-smoke.sh --device 192.168.68.55:43069
```

`MainActivityStaticWallpaperPhysicalSmokeTest`, with
`auroraPhysicalWallpaperPickerSmoke=true`, passed exactly 1/1 in 7.107s on a
Pixel 8 running Android 16/API 36. The test opened the real `MainActivity`
global-thread Appearance editor and platform Photo Picker, created one uniquely
named synthetic Downloads PNG with a randomized future EXIF timestamp, and
selected that exact item. Editor Cancel and wallpaper Back independently left
the verified empty assignment/file baseline unchanged. A third selection still
left that baseline unchanged before Apply; Apply created exactly one
`global_thread` assignment and one conforming managed file, and Reset restored
the empty baseline. The exact synthetic fixture was deleted afterward.

Post-run state contained zero screen-wallpaper rows, zero conversation-wallpaper
rows, and zero managed files. The instrumentation package was absent, the target
package and APK were preserved, `org.aurorasms.app` remained the sole SMS-role
holder, and `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`,
`RECEIVE_WAP_PUSH`, `READ_PHONE_STATE`, and `POST_NOTIFICATIONS` all remained
granted. The local, installed, and `/sdcard/Download/AuroraSMS-debug.apk` target
APKs were each 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.

This is one synthetic, global-thread, platform-Photo-Picker happy-path smoke. It
does not prove system-picker cancellation, SAF fallback, conversation rendering,
restart persistence, focal/dim interaction, accessibility, form-factor or
performance behavior, carrier behavior, complete picker lifecycle, or a
complete/gold application. Every existing unchecked ADR 0007 and broader Phase
4 row therefore remains unchecked.

#### ADR 0007 verified-conversation rendering/recreation partial evidence — 2026-07-15

Source commit `b9350be354991e36039e8136095bc25ebd520d60` adds synthetic API 36
verified-conversation coverage. `AuroraSmsRootAcceptanceTest` passed 5/5; its
real-root timeline pixel captures prove conversation-over-global precedence,
the applied dim amount, equivalent pixels after Activity recreation,
reset-to-global pixels, and identity-loss fallback without cross-target
mutation. Editor and repository assertions prove focal/dim values survive Apply
plus recreation. Wallpaper Apply/reset add no presentation-data loads; Activity
recreation performs the one expected anchor reload.
The new `ManagedWallpaperSurfaceTest` case passed 1/1 and proves unavailable
conversation media falls back to global, target changes recycle/clear prior
media, and unavailable conversation plus global assignments clear to solid
without stale pixels. The new real-Room instrumented test passed 1/1 across two
database close/reopen cycles, proving exact global and conversation assignments
survive database close and reopen and conversation reset leaves global
untouched.

The complete API 36 connected matrix passed in 1m15s with 456 Gradle tasks: app
75 tests with two intentional physical-only skips, benchmark 3 with one
scale-opt-in skip, index 31, notifications 3, state 43, telephony 15, and
feature-conversations 4. The complete 886-task offline
host/release/governance/license gate passed in 15s, and CycloneDX passed in 7s.
The unchanged debug APK is 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.

Activity recreation and real-Room reopen are separate evidence. This does not
exercise a cold-process root renderer plus managed-file restart, a physical
verified-conversation journey, SAF/system-picker cancellation, accessibility,
form-factor or performance behavior, carrier behavior, the complete picker
lifecycle, or a complete/gold application. No broad compound checkbox changes
on this evidence alone.

#### ADR 0007 API 36 verified-conversation host-force-stop partial evidence — 2026-07-15

Source commit `73b5ffa2827ad2cd96b922ccf4a529b5b052529d` adds the explicitly
gated
`verifiedConversationWallpaperSurvivesHostForceStopAndColdTargetProcessRelaunch`
method and its preservation-safe runner:

```shell
./scripts/run-emulator-wallpaper-cold-restart-smoke.sh --device emulator-5554
```

The runner refuses a physical device and requires API 36 ranchu/goldfish, an
already-installed target APK exactly matching the local build, and no
preinstalled test package. It records the target APK identity, SMS-role-holder
string, and seven permission states, then installs only the instrumentation
APK. Preflight cleanup runs before the evidence baseline because initializing
the production container may reconcile pre-existing managed state.

Prepare waits for production state storage, requires the reserved synthetic
conversation target to be empty, derives the exact expected media identity in
an isolated cache-backed `ManagedWallpaperStore`, and durably records a
fail-closed PREPARING recovery journal before production Apply. It then applies
the deterministic fixture through the production `AppContainer`, Room
repository, controller, and app-private store; validates the exact assignment,
managed final, persisted-grant count, and decoded pixels; creates the canonical
pending fixture; and upgrades the checkpoint with exact media/revision,
focal/dim, baseline, and process evidence.

The host starts normal `MainActivity` only to create a live prepared target
process. It first requires that ordinary startup removed the initial pending
fixture, recreates that same canonical path while the PID remains unchanged,
then requires `am force-stop` to remove that exact PID. Verification in a fresh
target process requires a different PID and later process start, the exact Room
assignment and focal/dim metadata, observable removal of the recreated pending
file, the exact baseline-plus-referenced-final file-name set, unchanged grant
count, and a valid production load. The real `AuroraSmsRoot` Thread wallpaper
surface is hosted by the debug-only test activity with synthetic
conversation/index/timeline services; its timeline pixels match the persisted
dimmed color, and the in-memory synthetic wallpaper repository remains empty.
A further fresh process performs revision-qualified reset, authoritative
reconciliation, filename/grant-count baseline restoration, checkpoint removal,
and test-APK uninstall.

The exact committed runner passed independently twice. The host force-stopped
prepared target PIDs 16995 and 17370; their instrumentation
prepare/verify/cleanup times were 0.114s/2.773s/0.038s and
0.122s/2.716s/0.037s, respectively. Every phase reported exactly `OK (1 test)`,
one zero status, final instrumentation code -1, and no skip/failure/crash. Both
runs preserved the target APK, SMS-role-holder string, and all seven recorded
permission states.

The authoritative follow-on connected XML totals are 176 tests, zero failures,
and five intentional opt-in skips: app 77 with four skipped, benchmark 3 with
one skipped, index 31, notifications 3, state 43, telephony 15, and
feature-conversations 4. `connectedDebugAndroidTest` passed 456 Gradle tasks in
1m17s. The complete 886-task offline host/release/governance/license gate passed
in 16s, and the 15-task CycloneDX gate passed in 7s. The production debug APK is
unchanged at 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
After the final run, that APK remained installed on `emulator-5554` and was
copied to `/sdcard/Download/AuroraSMS-debug.apk`; local, installed, and Download
SHA-256 values matched, and the temporary instrumentation package was absent.

This is only API 36 emulator host-force-stop evidence for one synthetic
verified-conversation assignment. It is not a production `MainActivity`
launcher-renderer journey, a real provider-backed SMS conversation, UI
Apply/Reset, Photo Picker or SAF, source-unavailable/revoked behavior,
`global_thread`, physical/OEM/performance, or low-memory/background/in-flight
process-death recovery. Force-stop occurs after import, Room assignment,
managed-file publication, and checkpoint commit. The renderer runs once after
one cold restart; cleanup uses another fresh process but does not render again.
The uniform fixture proves dimmed pixels while focal position is metadata-only;
managed baselines compare file names rather than baseline bytes, and persisted
grants compare counts rather than identities. The test provider remains
installed during verification. No compound picker/SAF, broader process-death,
physical, or gold-readiness row closes from this evidence.

#### ADR 0007 API 36 Photo Picker accessibility-Back cancellation partial evidence — 2026-07-15

Source commit `826a20dbc3e965da8f269dde1351cf4d76d28f6c` adds the separately gated
`realGlobalThreadSystemPickerCancellationRestoresEditorAndBaseline` method. The
API 36 AOSP emulator was prepared with AuroraSMS installed under its normal
SMS-role precondition; the test does not grant itself a role or permission. Each
run targeted the exact method and set
`auroraEmulatorWallpaperPickerCancellation=true`:

```shell
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=org.aurorasms.app.appearance.wallpaper.MainActivityStaticWallpaperPhysicalSmokeTest#realGlobalThreadSystemPickerCancellationRestoresEditorAndBaseline' \
  -Pandroid.testInstrumentationRunnerArguments.auroraEmulatorWallpaperPickerCancellation=true \
  --offline --no-daemon --no-parallel --console=plain
```

A valid focused result must report exactly one test with zero failures, errors,
and skips; `BUILD SUCCESSFUL` alone is not evidence because an unmet API,
hardware, or explicit-gate assumption can skip the method. Both recorded runs
met the exact `1/0/0/0` result.

After `StateStorageStatus` reached `Ready`, following the startup reconciliation
attempt, the journey opened the real `MainActivity` global-thread editor,
launched the exact MediaProvider Photo Picker, and invoked the accessibility
global Back action. It created no synthetic picker fixture, inspected no picker
text or thumbnail, opened no conversation, created no provider message, and
invoked no carrier action. The wallpaper dialog
returned with Pick enabled, Apply disabled, and no loading/error state; the exact
assignment object, app-private managed-file name set, and persisted URI-grant
count matched the pre-launch baseline. Failure cleanup attempts to dismiss a
picker still in focus. The hardened exact-method journey passed independently
twice in 12s and 11s. The physical runner now pins its original physical method,
keeping both gate outcomes independent.

The follow-on complete API 36 connected matrix passed in 1m19s with 456 Gradle
tasks: app 76 tests with three intentional gated skips, benchmark 3 with one
scale-opt-in skip, index 31, notifications 3, state 43, telephony 15, and
feature-conversations 4. The complete 886-task offline
host/release/governance/license gate passed in 17s, CycloneDX passed in 7s, and
the unchanged 13,993,426-byte debug APK retained SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.

This is API 36 AOSP Photo Picker accessibility global-Back evidence only. It
does not prove SAF fallback/cancellation, OEM picker behavior, an explicit Photo
Picker Cancel control, selected/staged-candidate cancellation, URI
non-persistence or managed-file byte identity after selection, other assignment
tables, grant identity, cold-process behavior, or the complete picker lifecycle.
The compound Photo Picker/SAF row remains unchecked.

#### ADR 0007 API 26 AOSP DocumentsUI SAF accessibility-Back cancellation partial evidence — 2026-07-15

Source commit `37fd044df3b9b8933839b0f89f7018ec72b8ab1b` adds the separately
gated
`MainActivityStaticWallpaperSafFallbackSmokeTest` and its exact-method runner:

```shell
./scripts/run-emulator-wallpaper-saf-cancellation-smoke.sh --device emulator-5556
```

The runner refuses physical devices and requires the exact API 26
ranchu/goldfish emulator, an already-installed target APK matching the local
artifact, and AuroraSMS already holding the default-SMS role. It requires all
seven listed `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`,
`RECEIVE_WAP_PUSH`, `READ_PHONE_STATE`, and `READ_CONTACTS` permission states
to be readable and owner-granted `READ_SMS` to be true. A per-device `flock`
serializes participating runner invocations; point-in-time active/preinstalled
test-package checks reject an existing test process/package but do not exclude
unrelated external device use. The runner installs and later removes only the
test APK; it does not grant a role or permission.

The test separately constructs the same AndroidX
`PickVisualMedia(ImageOnly)` contract and proves that its API 26 intent is
`ACTION_OPEN_DOCUMENT`, has MIME type `image/*`, and resolves to AOSP
DocumentsUI. It then independently opens the real `MainActivity` global-thread
wallpaper editor and proves that the production Pick click focuses DocumentsUI.
It does not intercept or inspect the production outgoing intent. The journey
uses the accessibility global Back action without selecting a document or
traversing DocumentsUI content. The editor returns with Pick enabled, Apply
disabled, and no loading or error state. The exact global assignment, immediate
managed-file-name set, and persisted URI-grant identity/read/write/persisted-time
set all match their pre-launch baselines.

The exact runner passed independently twice in 2.751s and 2.754s. Each run
reported exactly one test, one zero status, final instrumentation code -1, and
`OK (1 test)`, with no skip, failure, or crash. Cleanup preserved the target
APK hash, default-SMS role, and all seven recorded permission states; the test
package was absent afterward.

The same source commit contains test-only compact API 26 portability hardening
for root anchor/recreation visibility, notification-route teardown, and Theme
Studio Cancel reachability. The final `:app:connectedDebugAndroidTest` XML on
`AuroraSMS_API26` records 76 tests, zero failures/errors, three intentional
gated skips, and 35.498s. This is app-module evidence on one AOSP API 26 AVD,
not project-wide API 26 coverage and not coverage for API 27 through 32.

This narrow result does not prove interception of the production outgoing
intent; document selection or a returned URI; preview, Apply, Reset, import,
rendering, or staged-candidate behavior; source loss or revocation;
configuration, Activity, or process loss; managed-file bytes, inode,
timestamps, or metadata preservation; a verified-conversation journey; API
27–32 or OEM behavior; an explicit DocumentsUI/Photo Picker Cancel control; or
broader accessibility, form-factor, performance, carrier, full-lifecycle, or
gold readiness. The compound Photo Picker/SAF row remains unchecked.

#### ADR 0007 API 26 AOSP DocumentsUI SAF selection-lifecycle partial evidence — 2026-07-16

Source commit `dd33737` adds the separately gated
`MainActivityStaticWallpaperSafFallbackSmokeTest#realGlobalThreadSafFallbackSelectionLifecycleRestoresBaseline`
journey and its exact-method runner:

```shell
./scripts/run-emulator-wallpaper-saf-selection-smoke.sh --device emulator-5556
```

The runner refuses physical devices and requires an API 26 ranchu/goldfish AOSP
emulator, an already-installed target APK matching the local build, AuroraSMS as
the legacy default SMS app, and readable snapshots of the seven listed SMS,
phone, and contacts permission states with owner-granted `READ_SMS`. It shares a
per-device nonblocking lock with the no-selection SAF cancellation runner,
refuses a preinstalled or active instrumentation package/process, and installs
and removes only the test APK. Its strict parser accepts exactly one status 0,
one custom status 42 with `auroraSafSelectionResult=pass`, final instrumentation
code -1, and `OK (1 test)`; a bounded 180-second timeout fails closed. These
guards reduce participating-runner races but do not exclude unrelated external
emulator use.

The androidTest-only exported `DocumentsProvider` exposes exactly one local-only
root, `AuroraSMS SAF Fixture 7E3B2C91`, and one read-only 40x20 PNG,
`aurora-saf-fixture-7e3b2c91.png`, under authority
`org.aurorasms.app.wallpaper.testdocuments`. The production APK and merged
production manifests add no provider or `MANAGE_DOCUMENTS` permission. The test
opens the real `MainActivity` global-thread wallpaper editor and the production
AndroidX contract's API 26 `ACTION_OPEN_DOCUMENT` fallback, acts only on the exact
synthetic root/document, obtains provider-open and preview evidence, and
validates the expected canonical `content:` URI shape with non-empty authority
and an at-most-4,096-byte UTF-8 form. It records the exact empty
global assignment, read-only revision sequence, persisted URI-grant identity/
read/write/persisted-time set, and no-follow managed-file name/device/inode/link-
count/size/mtime/SHA-256 ledger.

Selection followed by editor Cancel preserves every durable baseline. A second
selection followed by wallpaper Back does the same. A third selection followed
by `ActivityScenario.recreate()` loses the selected source and preview, leaves
Apply disabled, and preserves the baselines. A fourth selection makes only the
test document unavailable while leaving the provider installed: Apply reopens
the source, rejects it, and changes no assignment, managed file, persisted grant,
or revision. After the same document becomes available, retry Apply reopens it,
creates exactly one conforming managed final, and advances the revision exactly
once. Making the source unavailable again does not prevent the production
controller from loading the expected 40x20 managed raster. UI Reset restores
the empty assignment, managed-file, and persisted-grant baselines. Reset does not
allocate or roll back a revision, so the sequence deliberately remains baseline
plus one.

The focused selection runner passed cleanly in 13.054s and 13.087s; its final
post-review run passed in 12.952s. The separately gated no-selection cancellation
runner passed in 2.65s under the shared lock. A later module-by-module XML/
source-delta audit corrected the API 26 connected aggregate bookkeeping to 176
tests with five intentional gated skips, rather than the previously recorded
181/four; it had zero failures across 456 Gradle tasks in 1m53s. The complete
current API 36 aggregate
completed 176 tests with five intentional skips and zero failures across 456
tasks in 1m23s. The 886-task offline host/release/privacy gate passed in 19s,
and the separate 15-task CycloneDX gate passed in 8s. The production debug APK
for this source is 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This closes only one synthetic empty-`global_thread`, API 26 AOSP DocumentsUI
selection lifecycle. It does not capture the raw outgoing production intent or
raw Activity result; prove temporary URI-grant revocation; uninstall/remove the
provider; exercise readable source-byte/content mutation, cloud fetch, or
blocking; cover target
loss, stale CAS, configuration variants beyond Activity recreation, background/
low-memory/in-flight process death, or a cold process restart; render the actual
Thread surface or use a verified real-provider conversation; cover API 27-32,
physical SAF-fallback/selection behavior, broader OEM behavior beyond the
recorded Pixel 8 Photo Picker journey, performance, or an explicit
DocumentsUI/Photo Picker Cancel control; or prove the complete picker/static-wallpaper lifecycle
or gold readiness. The provider remains installed throughout Apply and Reset;
only its exact document availability is toggled. The compound Photo Picker/SAF
row and every unrelated implementation-complete, artwork, accessibility,
form-factor, performance, carrier, physical, and gold gate remain unchecked.

#### ADR 0007 API 26 AOSP DocumentsUI pre-Apply route-disposal and global stale-Apply partial evidence — 2026-07-16

Source commit `65fc6552a877403523e499b457fdf015aaf6f753` extends the
selection runner with a backward-compatible, separately gated journey:

```shell
./scripts/run-emulator-wallpaper-saf-selection-smoke.sh \
  --device emulator-5556 --journey stale-apply
```

Omitting `--journey` still selects the prior selection-lifecycle method. The new
mode requires the same exact API 26 ranchu/goldfish emulator, matching installed
target APK, legacy default-SMS state, captured seven-permission baseline with
owner-granted `READ_SMS`, shared per-device lock, absent test package/process,
and bounded 180-second execution. It installs and removes only the test APK. Its
strict parser accepts exactly one status 0, one custom status 43 with
`auroraSafStaleApplyResult=pass`, final instrumentation code -1, and
`OK (1 test)`; cleanup rechecks the target APK, default-SMS setting, and all
seven permission states.

The journey starts from an exact empty `global_thread` assignment and selects
the one synthetic DocumentsUI document through the real `MainActivity` editor
and production AndroidX SAF fallback. It then directly calls the Activity's
new-intent path with the production open-conversation action and a fixed
synthetic conversation ID before Apply. Thread becomes visible from the Inbox
editor and dismisses both editors. Returning to Inbox and reopening the global
editor shows disabled Apply; provider counters prove the dismissed source was not
reopened. The assignment, revision sequence, persisted URI-grant identity/read/
write/time set, and no-follow managed-file ledger stay exact throughout.

After a fresh real DocumentsUI selection, the production controller commits a
controlled newer global winner from the ordinary test provider at exactly one
new revision. The still-open editor owns the captured empty revision. Its stale
UI Apply reopens the selected SAF source, surfaces the exact stale-assignment
error, and leaves the winner assignment, revision, managed-file
ledger, persisted-grant set, and production managed load unchanged. The stale
candidate is absent from the exact ledger. Reopening against the winner's
revision and using UI Reset removes only that controlled assignment/file and
restores the empty assignment/file/persisted-grant baseline; the consumed
revision correctly remains baseline plus one. Failure cleanup can recover and
reset only a commit matching the fixed scope, revision, dim/focal values, and
single conforming managed final.

`WallpaperControllerTest#lateRepositoryStaleWriteDeletesCreatedUnreferencedCandidate`
separately drives a late repository `StaleWrite`. It proves the exact
`references -> reconcile -> quota -> import -> projection -> quota -> set ->
references -> delete` order, one set attempt, the second authoritative reference
snapshot, and deletion of the created unreferenced candidate.

The corrected focused journey passed in 8.597s and 8.513s; the final
post-review revision-hardened pass took 8.667s. The prior selection lifecycle
and no-selection cancellation regressions passed in 13.012s and 2.692s. The
definitive API 26 aggregate was `BUILD SUCCESSFUL` in 1m49s across 456 tasks;
JUnit XML reports 177 tests, six intentional gated skips, and zero failures or
errors. The definitive API 36 aggregate was `BUILD SUCCESSFUL` in 1m23s across
456 tasks; XML reports 176 tests, five intentional skips, and zero failures or
errors. The API 26-only class is excluded from API 36 discovery by class-level
`@SdkSuppress`, so the new method does not change the API 36 count. The complete
886-task offline host/lint/release/privacy/license gate was
`BUILD SUCCESSFUL` in 21s with 36 executed and 850 up-to-date tasks. The separate
15-task CycloneDX 1.6 gate passed in 8s with 441 components and 442 dependency
nodes. The unchanged production debug APK is 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This closes only direct pre-Apply editor route disposal and one synthetic empty-
baseline global assignment-CAS conflict. It does not launch through the system
notification/PendingIntent path, cancel an in-flight Apply, or prove verified-
conversation target identity loss. Persisted-grant snapshots do not establish
temporary read-grant lifetime or revocation. Raw outgoing intent/result capture,
readable source-byte/content mutation, provider revocation/removal/replacement,
cloud/blocking behavior, API 27-32, physical/OEM SAF, explicit picker Cancel,
background/low-
memory/process-death variants, production-launcher/real-provider Thread
rendering, accessibility, form factor, performance, artwork, carrier, compound
picker/static-wallpaper
lifecycle, and gold readiness remain open. Only synthetic emulator fixtures
were used; no real message/address content, shared user document, carrier send,
or physical device participated. The compound Photo Picker/SAF and every
unrelated broad acceptance row remain unchecked; AuroraSMS is not complete or
gold.

#### ADR 0007 API 26 AOSP system-notification content-PendingIntent pre-Apply route-disposal partial evidence — 2026-07-16

Source commit `12939eea321e8eb6a9a173a82cab2dfd245b64e5` extends the
selection runner with a backward-compatible, separately gated journey:

```shell
./scripts/run-emulator-wallpaper-saf-selection-smoke.sh \
  --device emulator-5556 --journey notification-pending-intent
```

The mode requires an awake, unlocked, exact API 26 ranchu/goldfish AOSP
emulator, matching installed target APK, legacy default-SMS state, captured
seven-permission baseline with owner-granted `READ_SMS`, shared per-device lock,
absent test package/process, and bounded execution. It installs and removes only
the test APK. Its strict parser accepts exactly one status 0, one custom status
44 with `auroraSafNotificationPendingIntentResult=pass`, final instrumentation
code -1, and `OK (1 test)`; cleanup rechecks the target APK, default-SMS setting,
and all seven permission states.

The test initializes the production notification channels before its complete
owned-channel snapshot. From an exact empty `global_thread` assignment and a
captured active-notification baseline with the reserved identity absent, the
real `MainActivity` global editor and production AndroidX SAF
fallback select the exact synthetic local PNG in DocumentsUI. No Apply occurs.
The production `messageNotifier` then posts one fixed synthetic
`IncomingMessageNotification`. The exact active system notification is
fingerprinted by package, UID, tag, ID, message channel, category, private
visibility, timestamp, clearable/auto-cancel flags, absence of actions, Aurora
activity content `PendingIntent`, public version, fixed sender, and fixed body.

A real touchscreen swipe expands the AOSP shade. The journey locates and taps
the exact controlled AOSP SystemUI notification row/body, delivering its
production content `PendingIntent` to the same warm `MainActivity`. The exact
synthetic Thread ID and production open-conversation action are consumed;
Thread is visible, both wallpaper editors are dismissed, and source counters
prove the staged document was not reopened. Exact assignment, revision,
no-follow managed-file ledger, persisted-grant identity/read/write/time, and
source-open baselines remain unchanged. Auto-cancel restores the active-
notification baseline, and the full post-bootstrap notification-channel
snapshot, including each channel's DND-bypass setting, is unchanged. Back at
Inbox, reopening the editor shows disabled Apply, no loading/error, and no staged
selection.

The runner remains backward-compatible, bounded, fail-closed, and collision-
safe. Before canceling abnormal residue it fingerprints the exact package/tag/
ID. Its custom status 45 and `auroraSafNotificationCleanupResult=pass` belong
only to bounded cleanup-only instrumentation for abnormal recovery; that path
did not run during the passing journey.

The final focused journey passed in 6.927s after review confirmations in
7.170s, 6.961s, and 6.797s. Selection, stale-Apply, and cancellation regressions
passed in 12.879s, 8.595s, and 2.745s. The definitive API 26 root connected gate
was `BUILD SUCCESSFUL` in 1m51s across 456 tasks. JUnit XML reports 80 app tests/
seven intentional skips and 179 project tests/eight skips, with zero failures
or errors. The definitive API 36 root connected gate was `BUILD SUCCESSFUL` in
1m26s across 456 tasks; project XML reports 176 tests/five intentional skips and
zero failures/errors. The complete 886-task offline host/lint/release/privacy/
license gate was `BUILD SUCCESSFUL` in 12s with 26 executed and 860 up-to-date
tasks. The separate CycloneDX 1.6 gate passed 15 tasks in 8s with 441 components
and 442 dependency nodes. The unchanged production debug APK is 13,993,426
bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This closes only one synthetic, warm-task, pre-Apply API 26 AOSP system-
notification content-`PendingIntent` route-disposal path. It does not trigger a
real carrier/provider/receiver/orchestrator incoming message; prove its fixed
Thread ID provider-backed or a verified conversation; cover cold/absent-task,
background, lockscreen, or process-death delivery; capture raw `PendingIntent`
action/extras/flags; cover API 27+, notification-permission denial, OEM or
physical notification shades, reply/group/privacy/alerts/new-channel behavior,
raw picker result or temporary URI-grant lifetime, in-flight Apply, source
mutation/provider removal/cloud behavior, or nonempty baselines; or close a
compound picker/static-wallpaper, real incoming-SMS, broader acceptance, or
gold gate. Only synthetic emulator fixtures participated. AuroraSMS remains
incomplete and not gold.

### Remaining complete Phase 4 wallpaper/artwork/accessibility matrix

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
- [ ] A future live-URI slice defines revoked-grant/moved-file behavior, and a
  future GIF slice covers corrupt/single-frame/huge GIF, unsupported media, and
  decode failure with safe fallbacks; ADR 0007 does not imply either feature.
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
  media reference, with deterministic handling of missing managed media and any
  separately approved future revoked live reference.
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
