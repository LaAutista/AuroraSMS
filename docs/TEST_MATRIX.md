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
That warm journey left real carrier/provider/receiver/orchestrator message
origin; provider-backed and
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

Source commit `f41dfd4f0552ed249b2fbda65ec2e3b164842c23` now adds one
separate production incoming-SMS cold-notification journey on the dedicated
non-Play API 26 GSM AVD `AuroraSMS_SMSRX_API26`. One emulator-modem PDU reaches
the protected production `SMS_DELIVER` receiver, Telephony provider,
`COMPLETE` replay journal, verified conversation, production notifier, exact
receiver-process death, surviving notification, real AOSP shade tap, and a
distinct cold `MainActivity` process displaying the provider-backed Thread.
This closes only that synthetic emulator path; carrier-network, physical/OEM
shade and lockscreen, API 27+, permission-denial, group/multiple-message,
inline-reply execution, MMS, nonempty-provider, broader acceptance, and gold
coverage remain open.

Source commit `ec3e10299953253b1330d9440a07df981ed9a1af` adds a persistent, messaging-eligible
Inbox/Thread explanation and bounded recovery action for denied API 33+
notification permission. Its new owned API 36 AOSP journey passed twice: real
Settings established `USER_SET`, the recovery dialog established final
`USER_FIXED`, the next action opened exact app-notification Settings, one
documented emulator-modem SMS was independently captured as a raw PDU by the
test APK, and production provider/journal/timestamp identity completed with zero
Aurora SBNs and a cold readable Inbox and Thread. Exact cleanup completed on
both passes. The unchanged API 26 fixed raw-PDU journey also passed. API 33-35,
physical/OEM/carrier/lockscreen, group/multiple-message, inline-reply, MMS,
broader acceptance, and gold coverage remain open.

Source commit `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0` closes one
narrow part of the multiple-message gap: two sequential same-sender,
single-part SMS deliveries update one private conversation notification, which
survives process death and opens a distinct cold Thread containing the two
messages in order. Multipart SMS, other-conversation grouping/summary behavior,
alert counts, API 27+, physical/OEM/carrier/lockscreen, inline reply, MMS,
broader acceptance, and gold coverage remain open.

Implementation commit `7c9d848` hardens the durable incoming-SMS and
notification-reply boundaries and closes one narrow denied-reply gap. Its owned
API 26 AOSP `inline-reply-permission-denied` journey passed twice independently
from fresh disposable overlays: one real notification-shade RemoteInput reply
entered a cold, taskless receiver after only `SEND_SMS` was revoked, synchronous
preflight denied transport before submission, one durable claim and one generic
body-free failure notification remained, and no outgoing provider row appeared.
The original conversation notification and reply `PendingIntent` identity
remained stable; bounded shade/log scans and exact state cleanup passed. This is
not successful carrier-send, broader API/OEM, physical-device, or complete
lifecycle evidence. AuroraSMS remains incomplete and not gold.

A follow-on durability slice replaces two implementation residuals with
fail-safe provider staging and operation-scoped reply-failure notification
identity under an explicit single-owner model. Focused notification identity
and cancellation passed 29/29 on each of API 26 and API 36. The final-source
owner-gated provider contract passed 1/1 on each API, and a fresh disposable API
26 SystemUI denied-reply journey passed with exact cleanup. The final API 26 and
API 36 full connected matrices and the complete host/release/privacy/license
aggregate are also green. Broader carrier, physical/OEM, API 27 through 35,
MMS, process-death, and release acceptance remain open, so no checklist or
release gate closes from this slice alone.

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

The historical environment used for the physical evidence in this section had
SDK platforms 36 and 37.0, a Google Pixel 8 (`shiba`) running LineageOS Android
16/API 36, and the synthetic-only `AuroraSMS_API26` API 26 and
`AuroraSMS_API36` API 36 AVDs. That statement is not a claim that the Pixel was
attached for later phases. Earlier physical evidence below covers only that
Pixel/API combination. Phase 3 connected
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
- [x] All 34 originally supplied SHA-256 entries verified at the Phase 0 gate.
- [x] Full blueprint, HTML concept, 66-page PDF, nineteen originally supplied
  screenshot files, and nine original artwork files reviewed under the
  private-reference policy. One screenshot is no longer locally present; its
  historical fingerprint remains protected by the clean-room denylist.
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
  The focused API 36 partial evidence below covers one AOSP denial/recovery and
  incoming-SMS journey; this row remains unchecked for API 33-35 and the wider
  device and lifecycle matrix.
- [ ] Tap opens the exact conversation and restores state.
  The focused API 26 partial evidence below proves one updated same-conversation
  SBN survives process death and a real AOSP shade tap opens a distinct cold
  provider-backed Thread containing exactly two ordered messages. This row
  remains unchecked for multipart, other-conversation grouping, API 27+, and
  physical/OEM/carrier/lockscreen coverage.
- [ ] Inline reply validates role, permission, recipient, SIM, and current
  conversation before sending.
  The focused API 26 partial evidence below proves the exact durable target,
  recipient, subscription, current conversation, and synchronous permission-
  denial path before platform submission. Successful submission, live target/
  subscription mutation, API 27+, and physical/OEM coverage remain open.
  The follow-on `FAILED` staging-sentinel -> durable `PREPARED` -> one-shot
  `PENDING` arm -> durable `SUBMITTING` contract passed its final-source owner-
  gated real-provider test 1/1 on each of API 26 and API 36 without invoking
  `SmsManager`, including exact conditional rollback/conflict/idempotence and
  cleanup. Successful carrier submission and the wider matrix remain open.
- [ ] Duplicate/expired reply intents do not duplicate sends.
  The focused journey proves that one verified SystemUI submission creates one
  durable consumed claim and no outgoing row when permission is denied. It does
  not exercise a second live tap or an expired live `PendingIntent`, so this row
  remains unchecked.
- [ ] Failed reply posts a safe actionable notification without body leakage.
  The focused API 26 journey proves one generic body-free failure notification,
  a cold exact-Thread route, and bounded shade/log privacy scans. Broader API,
  OEM, lockscreen, Android Auto, and carrier-failure coverage remain pending.
  Operation-scoped failure tags, exact late-success cancellation, legacy-tag
  cleanup, sibling preservation, platform-manager identity, and crash-
  idempotent replay passed the focused notification module 29/29 on API 26 and
  29/29 on API 36. Broader surface and release evidence remain pending.
- [x] Android Auto metadata, `MessagingStyle`, background Reply/Mark as read
  semantics, privacy reset, bounded history, and generation fencing pass host
  plus API 26/API 36 notification contracts.
- [ ] Android Auto Desktop Head Unit or a physical car surface renders the
  conversation and completes a voice reply and Mark as read journey.

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
  The focused denied-reply journey kills the completed incoming receiver and
  then starts a distinct cold, taskless reply receiver, but it does not kill the
  process at every accepted reply-operation checkpoint. Focused tests cover
  exact success-side cancellation followed by pre-acknowledgement replay, and
  the final-source disposable API 26 SystemUI rerun passed. The full process-
  death matrix remains open.

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

#### Production API 26 emulator-modem incoming-SMS cold-notification partial evidence — 2026-07-17

Source commit `f41dfd4f0552ed249b2fbda65ec2e3b164842c23` adds the isolated,
owner-gated runner:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh
```

The runner exclusively owns a disposable overlay of the dedicated non-Play API
26 GSM AVD `AuroraSMS_SMSRX_API26`; it refuses an existing device on its owned
port and discards the overlay after success or recovery. Its prepare phase
establishes an exact empty controlled baseline. The host then injects exactly
one emulator-modem PDU, which traverses the protected production `SMS_DELIVER`
receiver, writes one Telephony provider row, reaches a `COMPLETE` replay-journal
entry, resolves a verified conversation and subscription-dependent optional
reply target, and posts through the production notifier.

The exact live SystemUI `StatusBarNotification` (SBN) record is required to be
`PRIVATE`, retain generic privacy text and a generic `publicVersion`, contain
neither controlled sender nor body, expose the Aurora activity content
`PendingIntent`, and match the expected subscription-dependent action contract.
The runner kills the exact receiver-process PID from the same application UID
and revalidates that
the notification survives unchanged. A real touchscreen swipe opens the AOSP
shade and taps the exact controlled row. A distinct cold app PID starts the real
`MainActivity`; the provider-backed verified Thread and controlled message are
visible, and auto-cancel removes the notification.

The verify phase checks the cold route and then restores the exact empty owned
delivery and notification state. The disposable emulator overlay is discarded.
Two consecutive final focused runs passed in 47.610s (prepare 1.083s, verify
0.554s) and 42.839s (prepare 0.987s, verify 0.549s).

At that exact source hash, the final API 26 root connected gate on
`emulator-5556` was `BUILD SUCCESSFUL` in 1m45s; project module XML totals were
180 tests/nine intentional skips with zero failures/errors. The API 36 root
connected gate on `emulator-5554` was `BUILD SUCCESSFUL` in 1m19s; project
module XML totals were 177 tests/six intentional skips with zero failures/
errors. The owner-gated incoming-SMS test was discovered and intentionally
skipped in both ordinary aggregate matrices. The 886-task host/release/privacy/
license gate was `BUILD SUCCESSFUL` in 18s with 32 executed and 854 up-to-date.
CycloneDX passed in 7s with 441 components and 442 dependency nodes. The
production debug APK remains 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This is one synthetic API 26 emulator-modem path, not a carrier-network test and
not completion of the unchecked physical/provider transport checklist. It does
not cover a physical or OEM notification shade, lockscreen delivery, API 27+,
notification-permission denial, grouped or multiple messages, inline reply
execution, MMS, a nonempty provider baseline, OEM/carrier matrices, or the
broader artwork, accessibility, form-factor, performance, complete-lifecycle,
and gold gates. AuroraSMS remains incomplete and not gold.

#### Production API 36 notification-denied incoming-SMS recovery partial evidence — 2026-07-17

Source commit `ec3e10299953253b1330d9440a07df981ed9a1af` adds the production recovery surface
in `app/src/main/kotlin/org/aurorasms/app/MainActivity.kt`. When AuroraSMS is
messaging-eligible on API 33+ but `POST_NOTIFICATIONS` is denied, an explanation
remains visible above both Inbox and Thread. The action requests permission
while a request remains available; after a recorded final denial it launches
`Settings.ACTION_APP_NOTIFICATION_SETTINGS` with AuroraSMS's exact package.
The decision policy and rendered notice/intent are covered by
`app/src/test/kotlin/org/aurorasms/app/NotificationPermissionRecoveryActionTest.kt`
and
`app/src/androidTest/kotlin/org/aurorasms/app/NotificationPermissionNoticeTest.kt`.
In addition,
`app/src/test/kotlin/org/aurorasms/app/message/IncomingMessageOrchestratorTest.kt`
proves `NotificationPostResult.NotificationsDisabled` still calls the handled-
delivery handoff once: replay returns the existing provider/conversation IDs,
with no duplicate provider insert or notification attempt.

The owner-gated end-to-end command was:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey notification-denied
```

`scripts/run-emulator-incoming-sms-cold-notification-smoke.sh` exclusively owns
a disposable overlay of the non-Play API 36 GSM AVD
`AuroraSMS_SMSRX_API36`. It installed the exact app and test APKs, assigned the
SMS role, proved the protected exported `DefaultSmsRoleChangedReceiver` started
and cleared package stopped state, and used the real AuroraSMS app-notification
Settings master switch to produce a denied `POST_NOTIFICATIONS` state carrying
`USER_SET`. Back in the
real Inbox, the recovery action opened the platform permission dialog. Selecting
its final denial produced `USER_FIXED`; the next recovery action opened the
exact disabled AuroraSMS app-notification Settings page. The denied state and
Inbox notice survived the Settings round trip and an exact cold, taskless app
boundary.

The host then issued one documented synthetic `emu sms send` through the owned
modem. The separately permissioned test APK armed
`app/src/androidTest/java/org/aurorasms/app/message/IncomingSmsPduCaptureReceiver.java`
and independently retained the single raw delivered PDU. Production received
the same delivery through its protected `SMS_DELIVER` receiver. Verification in
`app/src/androidTest/kotlin/org/aurorasms/app/message/IncomingSmsColdNotificationSmokeTest.kt`
required one provider row and one `COMPLETE` journal entry; the PDU-derived
journal key and sent timestamp had to be exact, and journal provider/thread IDs,
received/sent timestamps, and subscription identity had to match the provider
row. The delivery remained background and taskless, produced zero Aurora
`StatusBarNotification` records, and converged to a cold boundary. A cold
`MainActivity` launch then displayed the sole controlled conversation and the
missed-alert notice in Inbox; opening it displayed the provider-backed Thread,
message, and persistent notice, still with zero SBNs.

The verify phase removed only the exact provider, replay-journal, reply-target,
index, test-PDU-capture, and notification mutations and restored the empty
controlled baseline. This full journey, including exact cleanup, passed twice.
The unchanged default API 26 runner path, which injects the reviewed fixed raw
PDU, also passed after the recovery changes.

Focused API 36 notice and merged-manifest instrumentation passed 10 tests. The
definitive API 36 and API 26 root connected matrices each completed 456 Gradle
tasks with zero failures, in 1m21s and 1m49s respectively. The complete offline
host/lint/release/privacy gate was `BUILD SUCCESSFUL` in 1m19s across 883 tasks
(127 executed, 3 from cache, 753 up-to-date). The combined license-report and
CycloneDX gate passed 18 tasks in 8s. The exact debug APK is 13,993,426 bytes
with SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.

This is one owned API 36 AOSP emulator journey. It does not complete the still-
unchecked API 33-35 rows or prove a physical device, OEM/carrier behavior,
lockscreen delivery, grouped or multiple messages, inline-reply execution, MMS,
or the broader accessibility, form-factor, performance, complete-lifecycle,
and release gates. AuroraSMS remains incomplete and not gold.

#### Production API 26 same-sender two-message incoming-SMS cold-notification continuity partial evidence — 2026-07-17

Source commit `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0` adds the
host test
`twoDistinctSameSenderDeliveriesPersistAndNotifyOnceEachAcrossReplay` and the
owner-gated journey:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey multiple-message
```

The host test independently proves that two distinct delivery fingerprints
from one sender insert two provider messages, resolve one conversation, invoke
the notifier once per delivery, and replay as duplicates without another
provider insert or notification attempt.

The runner exclusively owns and finally discards a disposable overlay of the
dedicated non-Play API 26 GSM AVD `AuroraSMS_SMSRX_API26`. From an exact empty
controlled baseline it injects the reviewed fixed single-part GSM PDUs for
`AuroraSMS modem delivery 900017` at `1784328000000` and
`AuroraSMS modem delivery 900018` at `1784328060000`, exactly once and in
sequence. Their PDU-derived delivery fingerprints and replay-journal keys are
distinct. An uncertain injection outcome is never retried.

The first provider row, `COMPLETE` journal, exact private conversation SBN, and
its reply action must stabilize before the second injection.
After the second delivery, two `COMPLETE` journals have distinct positive
provider IDs but one positive thread ID and subscription. The same unambiguous
background process remains alive, exactly one notification tag/ID remains
active, and that SBN's `mUpdateTimeMs` must strictly advance and stabilize.
Generic notification text and `publicVersion` remain private; scans of the
active notification dump and real AOSP shade reject both controlled bodies and
the sender.

The runner then kills the exact receiver-process PID and proves the updated SBN
survives. A real shade-row tap starts a distinct cold `MainActivity` process on
the provider-backed verified Thread. Its UI contains exactly two total
`aurora-message-bubble` nodes, each expected body exactly once and in delivery
order; auto-cancel removes the notification. Instrumentation verifies the exact
two provider rows, two `COMPLETE` journals, two reply targets, two-message index
and timeline state with unread count two, then deletes only those controlled
mutations and restores the empty provider, journal, target, index, notification,
and channel baselines. Zero-, one-, or two-delivery abnormal recovery remains
bounded and fail-closed.

The complete journey and exact cleanup passed twice independently. The
unchanged API 26 single-message and API 36 notification-denied owner journeys
also passed after the generalized instrumentation and runner changes. At
`57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0`, the definitive API 26
connected matrix finished 97 tests with zero failures in 47s, and the API 36
matrix finished 91 tests with zero failures in 51s. The full 886-task host/
lint/release/benchmark/privacy/dependency/permission/
APK-content/license gate passed in 19s (35 executed, two from cache, 849 up-to-
date). CycloneDX passed 15 tasks in 7s. The unchanged production debug APK is
13,993,426 bytes with SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.

This closes only sequential same-sender, single-part SMS notification-update
continuity on one API 26 AOSP emulator. Multipart SMS, other-conversation
notification grouping/summary behavior, alert/sound/vibration counts, API 27+,
physical/OEM shade, carrier-network and lockscreen behavior, inline-reply
execution, MMS, nonempty-provider baselines, broader acceptance, and gold
remain open. AuroraSMS is incomplete and not gold.

#### Durable receive/reply recovery and API 26 denied-inline-reply partial evidence — 2026-07-18

Implementation commit `7c9d848` adds canonical, domain-separated SHA-256
checksums to the private incoming replay journal, reply target, consumed-claim,
reply-operation, and incoming-notification generation stores. The version 4
incoming journal also binds recovery to a redacted provider-content digest.
Malformed, missing, invalid, or content-mismatched owned rows fail closed into
durable key-bound `Q1` quarantine entries: the tombstone retains fingerprint
ownership and capacity while unrelated healthy entries remain recoverable.

Notification replies now have a durable operation state machine around provider
insertion, platform submission, sent/delivered callbacks, generic failure
notification acknowledgement, and provider-status reconciliation. Callback
origin, failure stage, unit identity, provider identity, and operation identity
are explicit. Same-kind provider message IDs order ahead of wall-clock time, so
equal or regressing callback clocks cannot invert provider-backed timeline
order. Recovery defers unresolved `PENDING` evidence instead of treating it as
success or resubmitting an operation with an uncertain platform boundary.

Default-role lifecycle work is serialized against authoritative platform state.
On confirmed role loss it cancels and joins pending messaging recovery, clears
reply targets, and retries cancellation only for tracker-owned incoming
notification generations. The bounded `goAsync` lease and accepted receiver
work now have separate jobs: lease timeout calls `finish()` without cancelling
the sibling work. That work can continue only while the process remains alive;
its accepted durable boundary owns later startup/foreground recovery after
process loss.

The owner-gated journey is:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey inline-reply-permission-denied
```

Each run exclusively owns and finally discards a fresh overlay of the dedicated
non-Play API 26 GSM AVD `AuroraSMS_SMSRX_API26`. From an exact empty controlled
baseline, one fixed synthetic modem PDU reaches the protected production
`SMS_DELIVER` receiver, Telephony provider, `COMPLETE` replay journal, durable
reply target, verified conversation, and private production notification. The
runner kills the completed receiver process without force-stopping the package,
revokes only `android.permission.SEND_SMS`, and proves the task, process,
provider row, journal, target, notification, channel, and permission boundary
before interaction.

The runner expands the real AOSP shade, exposes exactly one Reply action, opens
exactly one empty RemoteInput editor, enters and reads back the fixed synthetic
reply, and permits exactly one submit tap. An uncertain outcome is never
retried. A distinct cold, taskless `InlineReplyReceiver` process starts once.
Synchronous permission preflight rejects the operation before platform
submission: exactly one consumed replay claim and one checksummed notified
operation exist, no outgoing provider row appears, the incoming row is
unchanged, the original conversation SBN and reply `PendingIntent` identity
remain stable, and exactly one generic body-free reply-failure SBN appears.
Bounded SystemUI and log windows contain none of the controlled reply, sender,
or incoming-message body.

Cleanup restores `SEND_SMS`, taps the exact generic failure row through the real
shade, and proves a fresh cold `MainActivity` process routes to the exact
provider-backed Thread while preserving the conversation notification. It then
removes only the controlled provider, incoming-journal, reply-target,
consumed-claim, reply-operation, index, notification, and channel mutations and
restores the empty baseline. The complete journey and exact cleanup passed
twice independently on two fresh overlays.

The verification commands included:

```shell
./gradlew test lintDebug lintRelease assembleDebug assembleRelease \
    :app:lintBenchmark :app:assembleBenchmark \
    :macrobenchmark:check :macrobenchmark:assembleBenchmark \
    verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions \
    verifyApkContents checkLicense generateLicenseReport \
    --offline --no-daemon --no-parallel --console=plain
./gradlew cyclonedxBom --offline --no-daemon --no-parallel --console=plain
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain
```

The full offline aggregate completed all 886 Gradle tasks successfully, and the
separate CycloneDX gate passed. The API 36 connected runner reported zero
failures/errors with module totals of app 135, notifications 22, telephony 24,
state 43, index 31, conversations 5, and benchmark 4. API 26 reported zero
failures/errors with app 141, notifications 22, telephony 24, state 43, index
31, conversations 5, and benchmark 4. Retained API 26 XML reconciles 258
zero-failure test results plus 12 intentional assumption skips, matching 270
runner-discovered cases. Focused durable-store instrumentation passed 43/43 on
both APIs, notification-generation cancellation passed 18/18 on both, and the
incoming replay journal passed 9/9 on both. The exact debug APK is 13,993,426
bytes with SHA-256
`a8fdc6d227fa801c529bc5340fa538c9dec33715f74e12666ff606ad9b82c073`.

The two former implementation residuals now have explicit fail-safe contracts
and one durable pre-submission owner per path:

- An outgoing SMS provider row is inserted atomically as known-unsent `FAILED`
  with Aurora's staging sentinel and exact creator/row ownership. Notification
  inline reply owns its boundary in the caller's private durable reply-
  operation store and reserved high operation-ID namespace. Android
  `RESPOND_VIA_MESSAGE` uses ordinary low operation IDs and delegates ownership
  to the transport's separate private, content-free journal. After the one
  owner records an exact row as `PREPARED`, one conditional arm may consume the
  sentinel and transition it to `PENDING`; a second or wrong-state arm fails.
  Durable `SUBMITTING` is recorded before the irreversible platform call.
- A synchronous pre-boundary refusal or cancellation conditionally terminalizes
  only the exact Aurora-created row in an allowed staging, armed, or already-
  terminal state. An absent row is safely retired. An identity, creator,
  conversation, or state conflict creates a known-unsent quarantine tombstone
  and does not mutate a foreign or reused row. Inherited `PREPARED` retries that
  exact cleanup. Inherited `SUBMITTING` becomes `SUBMISSION_UNKNOWN` and is
  never rearmed or resubmitted; it does not falsely converge to `FAILED`.
- The transport-owned journal stores at most 128 content-free identities, part
  counts, states, and lifecycle times. It never evicts active `PREPARED` or
  `SUBMITTING` records. Only `SUBMISSION_UNKNOWN` and known-unsent quarantine
  tombstones expire after seven days; otherwise capacity rejects a new send.
  Corrupt, noncanonical, or uncommittable journal state globally fails
  transport-owned submission closed. A transient provider cleanup failure
  defers only that record, continues independent recovery, and does not by
  itself block an unrelated send.
- `PENDING` provider rows left by pre-journal alpha builds have no exact durable
  record. Upgrade recovery intentionally neither sweeps nor mutates those rows
  and makes no claim to repair them.
- A generic reply-failure notification is tagged by conversation plus durable
  reply-operation ID. A later positive result cancels only that operation's
  alert and the exact source generation, preserving unrelated failures in the
  same conversation. Posting or cancelling before a crash but acknowledging
  afterward replays the same exact keys idempotently.

On first role-enabled recovery after upgrading from the pre-operation-key
alpha, AuroraSMS dismisses any still-active conversation-only generic
reply-failure alerts because they cannot be mapped safely to one durable reply
operation. Previously user-dismissed alerts are not recreated. Message/provider
state and durable late-callback ownership are unchanged; users should verify
those replies in the conversation. If legacy-alert enumeration or cancellation
fails, pending replay is deferred and recovery retries. A migrated success
record without its historical source-message identity cannot cancel one exact
incoming generation; AuroraSMS cancels its operation-scoped failure alert but
leaves durable success acknowledgement pending rather than guessing.

Final-source focused verification passed the frozen 320-task host gate:
telephony 75/75, core testing 22/22, and app 191/191, together with green lint
and app/telephony `androidTest` compilation. Transport-owned submission-journal
instrumentation passed 7/7 on API 26 and 7/7 on API 36. The owner-gated real
Telephony-provider staging contract passed 1/1 on each API without invoking
`SmsManager`; it proved the exact failed/sentinel insert, one-shot arm and
sentinel consumption, wrong-thread conflict preservation, idempotent terminal
rollback, absent exact URI handling, and exact synthetic-row cleanup.

Notification verification passed 29/29 on API 26 and 29/29 on API 36. It
includes independent same-conversation operation identities, exact late-
success cancellation, sibling preservation, legacy cleanup/retry, crash replay,
and a real `NotificationManager` test proving that exact cancellation preserves
the sibling operation. A fresh disposable API 26 AOSP SystemUI
`inline-reply-permission-denied` journey passed on the final source with exact
cleanup; its overlay was then discarded.

The final API 26 root connected matrix was `BUILD SUCCESSFUL` in 1m51s across
456 Gradle tasks. Preserved console module roots record app 132 with 12 skips, benchmark 3
with one skip, notifications 29, telephony 31, state 43, index 31, and
conversations 5. That reconciles to 274 total tests, 13 intentional skips, and
zero failures/errors. The final API 36 root connected matrix was `BUILD
SUCCESSFUL` in 1m24s across 456 tasks. Its retained XML records app 129 with nine
skips, benchmark 3 with one skip, notifications 29, telephony 31, state 43,
index 31, and conversations 5: 271 total tests, 10 intentional skips, and zero
failures/errors.

The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m19s across all 886 Gradle tasks (130 executed, seven from cache,
749 up-to-date). The separate CycloneDX 1.6 gate passed 15 tasks in 8s and
reports 441 components and 442 dependencies. The final debug APK is 13,993,426
bytes with SHA-256
`16037c616d6d696b4974f3e3a14238c18937c6f677f2f60e677ca10f0ea0ef98`.

The first API 26 aggregate attempt remains diagnostic only. It exposed test-
order contamination because a channel test disabled the real production reply-
failure channel and API 26 preserves disabled importance across channel delete/
recreate. The corrected test injects a dedicated test-only disabled channel.
Only the later clean API 26 matrix above is pass evidence. The implementation
and tests for this final source are frozen in commit `3d7182c`.

One bounded implementation limitation remains explicit: the incoming replay
journal retains at most 512 owned entries and evicts the oldest `COMPLETE`
ownership records when full. An extremely old exact carrier redelivery after
eviction can therefore be inserted again.

This evidence is limited to fresh AOSP API 26 emulator runs with synthetic
modem SMS and deliberately denied `SEND_SMS`. It does not prove successful
carrier submission, sent/delivered callbacks, delivery reports, multipart
transport, carrier charging, physical/OEM devices, API 27+, API 29+ role flows,
API 31+ mutable RemoteInput behavior, lockscreen or Android Auto use, a live
role-loss race, process death at every accepted-operation boundary, reboot or
low-storage recovery, duplicate/expired live reply taps, subscription/current-
conversation mutation, dual-SIM behavior, or MMS reply. The broader release
matrix remains open; AuroraSMS is incomplete and not gold.

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

## Phase 5A bounded existing-Thread composer evidence — 2026-07-18

This section records local verification for implementation commit `17fc421`,
`0.5.0-phase5` (`versionCode` 4). It does not claim a pushed CI run,
physical-device handoff, release, or carrier submission. The exact debug APK did
complete the emulator handoff recorded below.

### Host, governance, and dependency gates

The complete offline aggregate was `BUILD SUCCESSFUL` in 1m27s across all 886
Gradle tasks (90 executed, two from cache, 794 up-to-date):

```shell
./gradlew test lintDebug lintRelease assembleDebug assembleRelease \
    :app:lintBenchmark :app:assembleBenchmark \
    :macrobenchmark:check :macrobenchmark:assembleBenchmark \
    verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions \
    verifyApkContents checkLicense generateLicenseReport \
    --offline --no-daemon --no-parallel --console=plain \
    -Pkotlin.incremental=false
```

All 508 retained host JUnit results passed with zero failures, errors, or skips:

| Module | Tests |
|---|---:|
| app | 236 |
| design | 9 |
| index | 69 |
| model | 19 |
| notifications | 21 |
| state | 38 |
| telephony | 79 |
| testing | 24 |
| conversations | 13 |
| **Total** | **508** |

`bundleRelease` separately passed 269 tasks in 7s. `cyclonedxBom` passed 15
up-to-date tasks in 7s; the generated CycloneDX 1.6 graph contains 441
components and 442 dependencies.

### Complete connected matrices

Only AOSP emulators were attached for this Phase 5A gate: API 26 on
`emulator-5556` and API 36 on `emulator-5554`. No physical device participated.
The full API 26 matrix was `BUILD SUCCESSFUL` in 1m54s across 456 tasks; the
full API 36 matrix was `BUILD SUCCESSFUL` in 1m31s across 456 tasks. The first
API 36 invocation is diagnostic only: the AVD had disconnected and Gradle
stopped before executing a test. After the existing AVD restarted and reported
boot complete, the definitive matrix below passed.

| Module | API 26 tests | API 26 skips | API 36 tests | API 36 skips |
|---|---:|---:|---:|---:|
| app | 134 | 12 | 131 | 9 |
| benchmark | 3 | 1 | 3 | 1 |
| index | 31 | 0 | 31 | 0 |
| notifications | 29 | 0 | 29 | 0 |
| state | 49 | 0 | 49 | 0 |
| telephony | 35 | 0 | 35 | 0 |
| conversations | 10 | 0 | 10 | 0 |
| **Total** | **291** | **13** | **288** | **10** |

Both matrices executed 278 non-skipped tests with zero failures or errors. The
API 36 root composer acceptance and external-compose isolation were also rerun
as focused gates and each passed 1/1. Those tests prove the bounded existing-
Thread UI/coordinator route and that external compose cannot bypass it; they do
not invoke a real destination or carrier network.

### Artifact and privacy inventory

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 13,831,154 | `d8e1dbd75fc4d4ea76c4ebe8d2abb4b6c70828707d9c2eb94cc4697d485d7d31` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 1,960,013 | `22fd58161ee3a99b7d849d0389a4b605bd4e7a84aa0d653c02b69d6ff18f21e9` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 1,886,352 | `a9664f9baf4b943fa1df6a2828cfa4e1db5320cae11f25a0670879fbbfe35197` |
| `macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk` | 41,212,868 | `d8a00f0c054fab8aa352583c8e3117661f91c80f426b9def64534bae9f1319fb` |
| `app/build/outputs/bundle/release/app-release.aab` | 4,750,239 | `ac5900eb1372934584d8740be248eff7ed10bb65427be25df1042875f2043fc4` |
| `build/reports/bom.json` | 1,014,122 | `c4504bdd75de9812810f2ee2911f00adafafa663efe2206cba7db52f58c56194` |
| `build/reports/bom.xml` | 922,055 | `6e40e26911f021d7fc9b7c3df11987b7c2a8143fc7d4c83d739648f60db79525` |

Pinned build-tools 36.0.0 `aapt2` inspection found the expected AuroraSMS
package, `versionCode` 4, `versionName` `0.5.0-phase5`, minimum API 26, and
target API 36 in all app variants. Only the debug app is `debuggable`. No app
variant requests `INTERNET` or `ACCESS_NETWORK_STATE`. The separate
macrobenchmark test APK is intentionally debuggable and carries its tooling
`INTERNET` permission; it is not an app variant. The release APK is unsigned
and therefore is not a distributable or installable release artifact.

The exact debug APK above installed successfully on the API 36 AOSP emulator
(`emulator-5554`) and was copied to
`/sdcard/Download/AuroraSMS-debug.apk`. The copied file was 13,831,154 bytes and
its device-side SHA-256 exactly matched
`d8e1dbd75fc4d4ea76c4ebe8d2abb4b6c70828707d9c2eb94cc4697d485d7d31`.
An explicit cold `MainActivity` launch returned status `ok`, launch state
`COLD`, and `TotalTime` 455 ms. The synthetic smoke screen rendered the expected
default-SMS approval route because the clean emulator retained
`com.android.messaging` as role holder; the smoke did not grant the role or SMS
permissions and did not submit a message. No screenshot or device data is
tracked in the repository.

### Closed local rows and remaining acceptance

- [x] Complete host unit, lint, release assembly, benchmark, clean-room,
  private-asset, dependency, permission, APK-content, and license gates pass.
- [x] Schema-5 composer operation, schema migration, content-free persistence,
  exact draft restoration/freeze, cancellation envelope, bounded non-sending
  recovery, exact callback retry, provider ownership, and terminal settlement
  suites pass.
- [x] Schema-6 acknowledged-unknown receipts atomically release the active
  operation, preserve the draft, checkpoint exact late success/failure, and retry
  only exact provider reconciliation across Room reopen. Focused host tests and
  nine API 36 Room/migration/reopen tests passed on 2026-07-19.
- [x] Complete API 26 and API 36 connected matrices pass with identical 278
  executed-test coverage and only documented assumption skips.
- [x] Production composer preflight is exercised only under unavailable role or
  permission, with zero provider mutation and zero platform/carrier submission.
- [x] Root existing-Thread composer and external-compose isolation each pass a
  focused API 36 acceptance run.
- [x] The exact final debug APK installs, copies to emulator Download with an
  identical SHA-256, and cold-launches to the expected role-approval route on
  API 36 without changing role or SMS permissions.
- [ ] A physical SMS-capable device and active SIM pass the exact one-person,
  one-unit existing-Thread journey.
- [ ] Real carrier acceptance/rejection, billing, roaming, airplane/no-service,
  sent callback, delivery callback, and exact provider reconciliation pass.
- [ ] Reboot and process death at each real-send lifecycle boundary pass without
  draft loss, duplicate submission, or foreign provider mutation.
- [ ] OEM/device coverage and API 27 through 35 release rows pass.

No real carrier SMS was sent. SIM, physical-device, OEM, carrier, billing,
roaming, sent/delivery, reboot, and live-send process-death gates remain open.
The Phase 5B manual-unknown late-provider residual is locally closed by the
schema-6 receipt protocol in ADR 0009; that synthetic/emulator proof does not
close any physical or carrier row. AuroraSMS is incomplete and not gold.

### Phase 5B acknowledged-unknown local acceptance — 2026-07-19

This source identifies as `0.5.1-phase5` (`versionCode` 5); the schema-6
migration and callback-receipt change therefore do not reuse the frozen Phase 5A
package version.

The exact final Phase 5B source passed the complete offline host/release/privacy/
license aggregate in 1m35s across 886 tasks (199 executed, 687 up-to-date). All
515 host JUnit results passed: app 239, design system 11, index
69, model 19, notifications 21, state 40, telephony 79, testing 24, and
conversations 13. Debug, R8 release, and benchmark assembly; debug/release/
benchmark lint; clean-room/private-art scans; dependency locks; permission and
APK-content ledgers; and license gates all passed. CycloneDX 1.6 separately
passed 15 up-to-date tasks in 7s.

The complete final-version API 36 connected matrix passed in 1m29s across 456
tasks. Its
authoritative XML reports 291 tests, 10 intentional assumption skips, 281
executed tests, and zero failures/errors: app 131/9 skips, benchmark 3/1, index
31/0, notifications 29/0, state 52/0, telephony 35/0, and conversations 10/0.
After the final receipt-domain invariant was tightened, all 52 state tests and
the focused host coordinator/state suites passed again. No test invoked a real
carrier destination.

### Phase 5C durable conversation-SIM local acceptance — 2026-07-19

This source identifies as `0.5.2-phase5` (`versionCode` 6) and Room schema 7.
The new conversation-subscription table is content-free and keyed by a
purpose-separated verified participant-set digest; its insert/update triggers
enforce key shape, positive subscription/revision/timestamps, and monotonic
revision/time transitions. The repository uses optimistic revisions and the
Thread/send paths re-read the preference before operation reservation.

The initial complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m50s across 886 tasks (372 executed, 174 from cache, 340
up-to-date). After the current-schema instrumentation assertion was corrected,
the exact final source passed the same 886-task aggregate again in 39s (57
executed, 22 from cache, 807 up-to-date). All 521 host JUnit results passed: app 241, design system 11, index
69, model 19, notifications 21, state 44, telephony 79, testing 24, and
conversations 13. Debug, R8 release, and benchmark assembly; debug/release/
benchmark lint; clean-room/private-art scans; dependency locks; permission and
APK-content ledgers; and license gates all passed. `bundleRelease` separately
passed 269 tasks in 21s. CycloneDX 1.6 passed all 15 tasks in 35s and reports
441 components and 442 dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m27s
across 456 tasks. Its module totals are 294 tests, 10 intentional assumption
skips, 284 executed, and zero failures/errors: app 131/9 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 54/0, telephony 35/0, and conversations
11/0. The first aggregate attempt exposed an old current-schema assertion that
still expected version 6; it is diagnostic only. After the test was updated to
validate the schema-7 table and reinstallable triggers, its focused 5/5 run and
the later complete matrix above passed.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 1m55s
across 456 tasks. Authoritative XML reports 297 tests, 13 intentional
assumption skips, 284 executed, and zero failures/errors: app 134/12 skips,
benchmark 3/1, index 31/0, notifications 29/0, state 54/0, telephony 35/0, and
conversations 11/0.

The debug APK is 14,740,845 bytes with SHA-256
`e0d614e4a472d7416d299ba384824c756cc94486ee30ba9fc050e8f04180ece1`.
The unsigned release APK is 2,730,477 bytes with SHA-256
`2496afe507692c803e725906b5b2cea456ffb0479b9b84383f6380527013bfeb`;
the release AAB is 5,575,056 bytes with SHA-256
`30cb96a0fb38b1d85d6665bec99f93583bd6e9559cba30004cb574af28044104`.

All subscription-choice acceptance used synthetic emulator subscriptions and
no real carrier SMS. Physical dual-SIM/eSIM selection, actual removal or
disablement, carrier routing, billing, roaming, groups, MMS, schedules, and OEM
lifecycle behavior remain open. This closes only the local durable-choice and
one-part composer no-silent-fallback contracts; AuroraSMS remains incomplete
and not gold.

### Phase 5D durable scheduled-SMS local acceptance — 2026-07-19

This source identifies as `0.5.3-phase5` (`versionCode` 7) and Room schema 8.
The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m28s across 886 tasks (126 executed, 2 from cache, 758
up-to-date). All 529 host JUnit results passed with zero failures, errors, or
skips: app 247, design system 11, index 69, model 19, notifications 21, state
46, telephony 79, testing 24, and conversations 13. Debug, R8 release, and
benchmark assembly; debug/release/benchmark lint; clean-room/private-art scans;
dependency locks; permission and APK-content ledgers; and license gates passed.
`bundleRelease` separately passed 269 tasks in 5s. CycloneDX 1.6 passed 15
up-to-date tasks in 7s and reports 441 components and 442 dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m28s
across 456 tasks. Its authoritative XML reports 298 tests, 10 intentional
assumption skips, 288 executed, and zero failures/errors: app 131/9 skips,
benchmark 3/1, index 31/0, notifications 29/0, state 56/0, telephony 35/0, and
conversations 13/0. An initial diagnostic invocation exposed a stale current-
schema test that expected version 7; after it was updated to verify schema 8's
content-free table, unique indexes, and reinstallable physical triggers, the
focused test passed 5/5 and the complete matrix above passed.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 1m56s
across 456 tasks. Authoritative XML reports 301 tests, 13 intentional assumption
skips, 288 executed, and zero failures/errors: app 134/12 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 56/0, telephony 35/0, and conversations
13/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,838,926 | `53f6f96a896763c4a17c3c56c9038c582a30f001645f93be01a1135c667a0c28` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,769,225 | `d76525882ec1ac332dbad4a40082c805278cd8ca893aef9f28740f00decb7bd5` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,662,825 | `8c27c29972772aac0ef7ce13e9e9ca935bdf1b6197124fc70ba85c3d4df35b67` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,650,271 | `354527d3b1ca49daa41400f442e33743e3d8869bce21048a42d79c07e7930008` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The packaged app variants contain the declared default-SMS permissions plus
`RECEIVE_BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM`; no app variant requests
`INTERNET` or `ACCESS_NETWORK_STATE`. Exact-alarm access remains optional and an
inexact fallback is visibly labeled. All schedule dispatch acceptance used
fakes, synthetic data, or unavailable production preconditions. No physical
reboot, Doze, live exact-access revocation, real SIM removal, carrier SMS,
billing, or OEM alarm timing was exercised. AuroraSMS remains incomplete and
not gold.

The exact debug APK above installed successfully on the API 36 emulator and was
copied to `/sdcard/Download/AuroraSMS-debug.apk`. The copied file was 14,838,926
bytes and its device-side SHA-256 exactly matched
`53f6f96a896763c4a17c3c56c9038c582a30f001645f93be01a1135c667a0c28`.
An explicit cold launch returned status `ok`, launch state `COLD`, and total
time 413 ms; `MainActivity` was top-resumed. The sole app-PID error-level line
was Android's platform `ashmem` deprecation notice, with no crash or AuroraSMS
functional error.
The emulator intentionally retained `com.android.messaging` as SMS role holder,
and AuroraSMS's SMS/notification runtime permissions remained denied, so the
smoke exercised the expected role-approval route without sending a message.

### Phase 5E durable short-delay Undo local and safe-device acceptance — 2026-07-19

This source identifies as `0.5.4-phase5` (`versionCode` 8), Room schema 9, and
ADR 0012. Immediate send remains the default; 1, 3, 5, and 10 second options
create a content-free durable owner for the exact frozen draft. The complete
offline host/release/privacy/license aggregate was `BUILD SUCCESSFUL` in 1m53s
across 886 tasks (159 executed, 7 from cache, 720 up-to-date). All 538 host
JUnit results passed with zero failures, errors, or skips: app 254, design
system 11, index 69, model 19, notifications 21, state 48, telephony 79,
testing 24, and conversations 13. Debug, R8 release, and benchmark assembly;
debug/release/benchmark lint; clean-room/private-art scans; dependency locks;
permission and APK-content ledgers; and license gates passed. `bundleRelease`
separately passed 269 tasks in 8s. CycloneDX 1.6 separately passed 15 tasks in
7s and reports 441 components and 442 dependencies.

The first API 36 run found three stale acceptance assertions that expected the
entire Thread overflow action to disappear when conversation-specific
appearance became unavailable. Phase 5E intentionally keeps that action for
the independent Send Delay preference while hiding only Appearance. After
updating those assertions, the focused root acceptance class passed and the
complete API 36 matrix passed 302 tests with 10 intentional assumption skips,
292 executed, and zero failures/errors: app 131/9 skips, benchmark 3/1, index
31/0, notifications 29/0, state 58/0, telephony 35/0, and conversations 15/0.
The run was `BUILD SUCCESSFUL` in 1m33s across 456 tasks.

The complete API 26 matrix was `BUILD SUCCESSFUL` in 1m59s across 456 tasks.
Authoritative XML reports 305 tests, 13 intentional assumption skips, 292
executed, and zero failures/errors: app 134/12 skips, benchmark 3/1, index 31/0,
notifications 29/0, state 58/0, telephony 35/0, and conversations 15/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,642,934 | `e177575c93b66be4cbf26a74646bbe91197ecbe03d030ab01493f7401c3b10e1` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,788,813 | `2d9a54462fa9ed0886886190ffe251e2befb457f9f450e3f1843c2eb6c231e51` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,666,025 | `8e9a67ea4d0d279911e6ba649c759146a1a0c6487e44b01c3b58bf89cd6f07e0` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,704,533 | `2114f16c0d70b972383fd82844b05fc8a858c4b887238aed1f5ca6bb2ff59b31` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed with `-r` on the connected Pixel 8
`192.168.68.50:42337`, preserving app data. The same 14,642,934-byte APK was
copied to `/sdcard/Download/AuroraSMS-debug.apk`; device-side SHA-256 exactly
matched `e177575c93b66be4cbf26a74646bbe91197ecbe03d030ab01493f7401c3b10e1`.
The installed package reports version code 8/name `0.5.4-phase5`, min API 26,
target API 36. A metadata-only private query verified schema version 9 and the
`send_delay_operations` table. Cold launch returned status `ok`, state `COLD`,
and total time 1,583 ms. The sole app-PID error-level line was Android's
platform `ashmem` deprecation notice.

The Pixel remained securely locked, so the keyguard was not bypassed and no
screenshot or UI input was attempted. `org.fossify.messages.debug` remained the
SMS role holder and Aurora's SMS/notification runtime permissions remained
denied. No message content was read and no carrier SMS was sent. This safe
install/schema/launch evidence does not close real process-kill timing, reboot,
SIM removal, carrier, radio, billing, or OEM lifecycle gates. AuroraSMS remains
incomplete and not gold.

### Phase 5F guarded permanent-deletion local and safe-device acceptance — 2026-07-19

This source identifies as `0.5.5-phase5` (`versionCode` 9), Room schema 10, and
ADR 0013. One exact SMS/MMS row has one confirmation; a whole Thread has a
two-step confirmation. Both enter a fixed five-second Undo window before an
exact-target provider commit. Durable recovery stores no address or message
content and never blindly repeats an interrupted delete.

The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 2m04s across 886 tasks (255 executed, 11 from cache, 620
up-to-date). All 546 host JUnit results passed with zero failures, errors, or
skips: app 259, design system 11, index 69, model 19, notifications 21, state
51, telephony 79, testing 24, and conversations 13. Debug, R8 release, and
benchmark assembly; debug/release/benchmark lint; clean-room/private-art scans;
dependency locks; permission and APK-content ledgers; and license gates passed.
`bundleRelease` separately passed 269 tasks in 8s. CycloneDX 1.6 separately
passed 15 up-to-date tasks in 7s and reports 441 components and 442
dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m36s
across 456 tasks. It passed 308 tests with 10 intentional assumption skips, 298
executed, and zero failures/errors: app 131/9 skips, benchmark 3/1, index 31/0,
notifications 29/0, state 61/0, telephony 35/0, and conversations 18/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m00s
across 456 tasks. Authoritative XML reports 311 tests, 13 intentional assumption
skips, 298 executed, and zero failures/errors: app 134/12 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations
18/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,749,634 | `b72761cef9e98147c1639613081e5f9fb4c0a6d8ec56344d4e126a819c44d99c` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,846,357 | `ff37c6cc419517edb533bb3859ec4a657bbc9c24679e51fef966b66b620efd25` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,690,809 | `06ae33148ace3fe3897abb50ec90f53f3632c235f8dc5b622160b927f329908c` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,797,617 | `4352cc8f4fd577660efc4cd65f062b0902e39c5718e91bb98e9ba7dd92030d73` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The Pixel 8 was not enumerated by ADB at Phase 5F handoff; its prior wireless
endpoint was unreachable, so physical install/migration/launch acceptance
remains pending. No automated or manual Phase 5F test read or deleted a live
message, changed the default SMS app, or submitted a carrier message.

### Phase 5G shared no-group-SMS acceptance — 2026-07-19

This source identifies as `0.5.6-phase5` (`versionCode` 10), Room schema 10,
and ADR 0014. `SmsSendRequest` structurally requires exactly one canonical
recipient. Thread, delayed, and scheduled paths each recheck a verified one-
person identity. Respond-via maps two or three recipients to one MMS request;
an MMS failure returns without any SMS call. The group Thread UI keeps draft
editing available, labels MMS unavailable, and disables Send.

The complete offline host/release/privacy/license aggregate passed. All 552
host JUnit results passed with zero failures, errors, or skips: app 262, design
system 11, index 69, model 19, notifications 21, state 51, telephony 82,
testing 24, and conversations 13. Debug, R8 release, and benchmark assembly;
debug/release/benchmark lint; clean-room/private-art scans; dependency locks;
permission and APK-content ledgers; and license gates passed. `bundleRelease`
separately passed 269 tasks in 25s. CycloneDX 1.6 separately passed 15
up-to-date tasks in 8s and reports 441 components and 442 dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m35s
across 456 tasks. It passed 309 tests with 10 intentional assumption skips, 299
executed, and zero failures/errors: app 132/9 skips, benchmark 3/1, index 31/0,
notifications 29/0, state 61/0, telephony 35/0, and conversations 18/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m01s
across 456 tasks. Authoritative XML reports 312 tests, 13 intentional assumption
skips, 299 executed, and zero failures/errors: app 135/12 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations
18/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,749,634 | `3bc8322e3ea920df07c52f9cd19082794d7be3bfc73f822f711237245d997a19` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,846,357 | `06cf5c7a208a888c333468bb0b59aa07efb7dae65c052516feb48a08ed403a95` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,690,813 | `6bbe43e335ca30fb6312f0ef3ab9d9c640fac66764b8c5eecd418730d9037eb1` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,797,124 | `1fb2e9f0b64ea335a86ac35b51aa57f1d756f52c675d2415bde8afb9c22f22b8` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The Pixel 8 was still absent from `adb devices` at Phase 5G handoff, so its
safe install/copy/metadata-only launch remains pending. Emulator and host tests
used only synthetic recipients and unavailable/fake transport boundaries; no
carrier SMS/MMS was submitted. The broader group-MMS codec, provider addressing,
reply identity, failure UI, carrier, and physical release rows remain open.

### Phase 6A conservative reaction-fallback acceptance — 2026-07-19

This source identifies as `0.6.0-phase6` (`versionCode` 11), Room schema 10,
and ADR 0015. Only exact, complete, bounded whole-message fallback forms render
as a structured reaction card. Truncated, malformed, differently cased,
multiline, unknown, trailing, blank, or oversized input remains the original
raw SMS. The presentation path does not rewrite, hide, or associate a provider
row and does not change index, search, timeline, or durable state.

The complete offline host/release/privacy/license aggregate passed. All 555
host JUnit results passed with zero failures, errors, or skips: app 262, design
system 11, index 69, model 19, notifications 21, state 51, telephony 82,
testing 24, and conversations 16. Debug, R8 release, and benchmark assembly;
debug/release/benchmark lint; clean-room/private-art scans; dependency locks;
permission and APK-content ledgers; and license gates passed. `bundleRelease`
and CycloneDX 1.6 also passed separately; the SBOM reports 441 components and
442 dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m36s
across 456 tasks. It passed 311 tests with 10 intentional assumption skips,
301 executed, and zero failures/errors: app 132/9 skips, benchmark 3/1, index
31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations 20/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m00s
across 456 tasks. Authoritative XML reports 314 tests, 13 intentional assumption
skips, 301 executed, and zero failures/errors: app 135/12 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations
20/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,754,958 | `eaf39a3454b5ba283dc2bdadcf035b33ed95cf02d14014ed0f46e4bea3ed0314` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,851,681 | `f0bcc5a9d153abb0f7e982c13c882a009424ec644b0f5ba627285e2b24256014` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,696,137 | `4145b0d5ba2accd65e39582dc2c1e0a9b3bf54f769023d3823be0d6212589297` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,804,008 | `d02f762878bb80abd190e7e863135853d88ee927bb8315532ba36563f5235b03` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

Only the API 36 and API 26 emulators were enumerated by ADB at Phase 6A
handoff. The Pixel 8 install/copy/metadata-only launch therefore remains
pending. All reaction fixtures were synthetic strings; no message content was
read, no default-SMS role changed, and no carrier SMS/MMS was submitted.

### Phase 6B selected-text copy and bounded-details acceptance — 2026-07-19

This source identifies as `0.6.1-phase6` (`versionCode` 12), Room schema 10,
and ADR 0016. Long press opens an explicit Message actions dialog. The
read-only selector copies exactly one non-collapsed valid range from only the
body displayed in that bubble and labels a truncated preview. Details contains
only bounded type, direction, localized time, status, subscription, and
attachment-count metadata. It excludes body, subject, address, provider/thread
IDs, and attachment paths. Deletion remains a separate choice with the Phase
5F confirmation and Undo protocol.

The complete offline host/release/privacy/license aggregate passed. All 558
host JUnit results passed with zero failures, errors, or skips: app 262, design
system 11, index 69, model 19, notifications 21, state 51, telephony 82,
testing 24, and conversations 19. Debug, R8 release, and benchmark assembly;
debug/release/benchmark lint; clean-room/private-art scans; dependency locks;
permission and APK-content ledgers; and license gates passed. `bundleRelease`
and CycloneDX 1.6 also passed separately; the SBOM reports 441 components and
442 dependencies.

The focused API 26 run initially exposed two compatibility-test assumptions:
the truncated bubble's center was occupied by its Show full message control,
and Android 8 creates its clipboard service on a Looper thread. The test now
targets the body area and accesses the clipboard on the main thread. The
corrected 22-test feature class passed on both APIs.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m37s
across 456 tasks. It passed 313 tests with 10 intentional assumption skips,
303 executed, and zero failures/errors: app 132/9 skips, benchmark 3/1, index
31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations 22/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m03s
across 456 tasks. Authoritative XML reports 316 tests, 13 intentional assumption
skips, 303 executed, and zero failures/errors: app 135/12 skips, benchmark 3/1,
index 31/0, notifications 29/0, state 61/0, telephony 35/0, and conversations
22/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,780,470 | `11652e008ce466f259e56ab642b17b73a5c65e3e1b698a3650b39676e9d27af2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,860,821 | `7a4673e81fc0837e1fc869a78ea627ab783e8244762149685ad42bbc48504921` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,721,657 | `68c505bab6f01e7f77914a5c8ba91dfa4c33e0fe977f8c79a5d28e4dafd1702c` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,826,971 | `3d56a196a7232c6d5dc230c65a8d30071d85db241702eaf2fb0504ea39827ba8` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

Only the API 36 and API 26 emulators were enumerated by ADB at Phase 6B
handoff. The Pixel 8 install/copy/metadata-only launch therefore remains
pending. Clipboard checks used only `Synthetic message` and reset the emulator
clipboard afterward. No live message content was read or copied, no default-SMS
role changed, and no carrier SMS/MMS was submitted.

### Phase 6C local content-free notification-reminder acceptance — 2026-07-19

This source identifies as `0.6.2-phase6` (`versionCode` 13), Room schema 10,
and ADR 0017. Reminders are off by default and offer explicit 15-minute,
one-hour, and three-hour choices. One checksummed content-free owner per
conversation is bounded to 64 total entries and drives a private ID-only,
one-shot inexact alarm. Fire-time handling requires role ownership and an exact
successful unread-SMS provider recheck before posting generic text. Read,
missing, and mismatched rows cancel the exact incoming generation; provider
failure consumes only the reminder and cannot fabricate read state or cancel
the original alert. Opening the conversation, setting changes, role loss,
reboot, wall-clock change, and timezone change fail closed.

The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m31s across 886 tasks. All 565 host JUnit results passed with
zero failures, errors, or skips: app 269, design system 11, index 69, model 19,
notifications 21, state 51, telephony 82, testing 24, and conversations 19.
Debug, R8 release, and benchmark assembly; debug/release/benchmark lint;
clean-room/private-art scans; dependency locks; permission and APK-content
ledgers; and license gates passed. `bundleRelease` passed 269 tasks in 8s, and
CycloneDX 1.6 passed 15 tasks in 8s with 441 components and 442 dependencies.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m40s
across 456 tasks. It passed 320 tests with 10 intentional assumption skips,
310 executed, and zero failures/errors: app 136/9 skips, benchmark 3/1, index
31/0, notifications 31/0, state 61/0, telephony 35/0, and conversations 23/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m03s
across 456 tasks. Authoritative XML reports 323 tests with 13 intentional
assumption skips, 310 executed, and zero failures/errors: app 139/12 skips,
benchmark 3/1, index 31/0, notifications 31/0, state 61/0, telephony 35/0, and
conversations 23/0.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,817,930 | `b197f321d66d8d17ebf20de95258f146b1f9865e3d43c9d6a0d15f0cb04734c3` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,881,893 | `89a79b47af3b5937d91e4cde83b6f7ff62d1b529e00958c20719ca7242132789` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,742,725 | `9b769bb2c6d1ce0479493e8a4a31ece22767041e85fc34bbfbf52cb3e952e54d` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,851,179 | `d153bb13fc21af671780d05930fd3ee33b85e98a6fd0e7725a7a0ff8ce3f5d97` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed on the Pixel 8 and API 36 emulator, was copied
to each device's Download directory, and hash-matched the host artifact. The
Pixel's non-Aurora default-SMS role holder and denied Aurora SMS-read permission
were preserved. Content-free inspection confirmed its retained index was
paused rather than verified complete, which explains a partial-history view
without exposing message, address, participant, or screenshot data. No role or
permission was changed, no live content was read, and no carrier SMS/MMS was
submitted.

### Phase 6 history-completeness hardening acceptance — 2026-07-19

This source identifies as `0.6.3-phase6` (`versionCode` 14), retains Room schema
10, and adds no permission. Content-free Pixel inspection found a non-Aurora
default-SMS role, denied Aurora SMS-read access, and a retained index generation
paused before verified completion. That explains the observed partial-history
view without implying provider row loss. The app now states the role boundary in
onboarding and boundedly continues Pending foreground reconciliation while role
and foreground-read eligibility remain true. Each initiating signal permits at
most four 500 ms retries; close, role loss, background loss, completion, and
failure stop continuation.

The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m20s across 886 tasks. All 568 host JUnit results passed with
zero failures, errors, or skips: app 272, design system 11, index 69, model 19,
notifications 21, state 51, telephony 82, testing 24, and conversations 19.
Debug, R8 release, and benchmark assembly; debug/release/benchmark lint;
clean-room/private-art scans; dependency locks; permission and APK-content
ledgers; and license gates passed.

The first combined `bundleRelease cyclonedxBom` invocation stopped during
configuration because CycloneDX attempted to mutate an already-resolved release
configuration. The gates passed when invoked separately immediately afterward:
`bundleRelease` passed 269 tasks in 7s, and CycloneDX 1.6 passed 15 tasks in 7s
with 441 components and 442 dependencies. This was an invocation-order tooling
failure, not a source or test failure.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m36s
across 456 tasks. Authoritative XML reports 321 tests with 10 intentional
assumption skips, 311 executed, and zero failures/errors: app 136/9 skips,
benchmark 3/1, index 32/0, notifications 31/0, state 61/0, telephony 35/0, and
conversations 23/0.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m02s
across 456 tasks. Authoritative XML reports 324 tests with 13 intentional
assumption skips, 311 executed, and zero failures/errors: app 139/12 skips,
benchmark 3/1, index 32/0, notifications 31/0, state 61/0, telephony 35/0, and
conversations 23/0. Before these green matrices, the focused large-history test
fixture failed during construction because two synthetic fingerprint seeds were
not hexadecimal. Correcting those non-production fixture values produced 3/3
focused passes on both APIs and the green full matrices above.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,818,018 | `f0a4e8ac419b61e1f2af841f00523f089d4482edc1d87861a0d826c18b39353e` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,881,981 | `0451ac52f4c0d302a19979dae75aa47bbcda9904983fb4ade63e393662d4ee25` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,742,809 | `6298472a377a84d672db21c4b5c7b239349b9c10d480c773adff521b8e493d8b` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,854,960 | `620c261a61acaeb3ae2e6490cb8526eff7b1a83a1e629c8d005cb8215d5d05d0` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed successfully on the Pixel 8 and API 36 emulator,
was copied to each Download directory, and hash-matched the host artifact. Both
devices report version code 14/name `0.6.3-phase6`; neither role holder changed.
The Pixel still denies Aurora SMS permissions because another app remains
default, so no claim is made that its personal index has completed. No app was
launched, no live message/address/content was read, and no carrier SMS/MMS was
submitted.

### Phase 6D bounded message-signature acceptance — 2026-07-19

This source identifies as `0.6.4-phase6` (`versionCode` 15), uses Room schema
11, and adds no permission. It provides global and verified-conversation
signature choices, exact unsigned/signed SMS-part disclosure, an unchanged
one-person/one-part SMS transport gate, and immutable frozen signature state
for immediate, delayed, and scheduled recovery. Corrupt preference state pauses
new sends rather than silently omitting content.

The complete offline host/release/privacy/license aggregate was `BUILD
SUCCESSFUL` in 1m32s across 886 tasks (92 executed, two from cache, 792
up-to-date). All 578 host JUnit results passed with zero failures, errors, or
skips: app 277, design system 11, index 69, model 19, notifications 21, state
56, telephony 82, testing 24, and conversations 19. Debug, R8 release, and
benchmark assembly; debug/release/benchmark lint; clean-room/private-art scans;
dependency locks; permission and APK-content ledgers; and license gates passed.

The complete API 36 matrix on `emulator-5554` was `BUILD SUCCESSFUL` in 1m56s
across 456 tasks. Authoritative XML reports 332 tests with 10 intentional
assumption skips, 322 executed, and zero failures/errors: app 142/9 skips,
benchmark 3/1, index 32/0, notifications 31/0, state 62/0, telephony 35/0, and
conversations 27/0. Two earlier diagnostic attempts lost the emulator process;
its quick-boot log showed invalid Vulkan color-buffer restoration. A cold boot
with snapshot loading disabled and software graphics produced the definitive
green matrix above; neither interrupted attempt is acceptance evidence.

The complete API 26 matrix on `emulator-5556` was `BUILD SUCCESSFUL` in 2m09s
across 456 tasks. Authoritative XML reports 335 tests with 13 intentional
assumption skips, 322 executed, and zero failures/errors: app 145/12 skips,
benchmark 3/1, index 32/0, notifications 31/0, state 62/0, telephony 35/0, and
conversations 27/0.

The focused schema suite passed all 62 tests after separating historical
version-5, version-8, and version-9 SQLite triggers from schema 11's
signature-aware triggers. `bundleRelease` passed 269 tasks in 7s. CycloneDX 1.6
passed 15 tasks in 7s with 441 components, 442 dependency nodes, and no random
serial number.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 15,826,882 | `42d6210e4d89b7e777e8fbd1fa96320173a78ffe05bbaa3b4e1fd00ab9a5acad` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,889,069 | `400226f8e1e0d017533bab3bbc5e745c65006f7dbdd6ea693010b6c8540682e9` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,749,901 | `cf996b07af95aae5f6c960fb433768481e97fd8bcf8fbcdee712fabb824be8c6` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,886,034 | `7ef95ff255e8bffafc408d0f4b868d9eaf6ceb545ebc31a2160034babb1c2d60` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed with data preserved on the Pixel 8 and API 36
emulator and was copied to each Download directory. Both copies hash-match the
host artifact and both packages report version code 15/name `0.6.4-phase6`.
The Pixel retained `org.fossify.messages.debug` as its SMS role holder; API 36
retained `com.android.messaging`. Aurora's SMS and notification permissions
remain denied on both. AuroraSMS was not launched, no live message/address/body
was read, and no carrier SMS/MMS was submitted.

### Phase 6 large-history role-resume follow-up — 2026-07-19

This source identifies as `0.6.5-phase6` (`versionCode` 16), retains index
schema 3 and durable-state schema 11, and adds no permission. A content-free
Pixel inspection of the preceding build found Fossify still held the SMS role
and all Aurora messaging permissions were denied. The private rebuildable index
contained four paused generations. The latest had committed 2,100 rows with
`pending_changes=1`; its SMS checkpoint had committed 1,507 rows and its MMS
checkpoint 593, and neither source was exhausted. The cache retained 5,226
physical message projections and 73 conversation projections, while only 46
conversation projections belonged to the newest incomplete generation. No
address, participant, timestamp, body, or screenshot was read.

The regression was that `ROLE_CHANGED` used the same durable dirty path as a
real provider mutation. Switching temporarily to another SMS app therefore
made a paused first-history checkpoint ineligible to resume, and the next role
grant restarted from the newest messages. Role transitions are now clean
authority signals: role loss still pauses immediately, but later explicit role
recovery resumes the same durable cursor. Content-observer and external-provider
signals still mark ambiguous changes dirty, and completion still requires both
sources exhausted plus provider-count and bounded head/fingerprint verification.
The incomplete Inbox and Thread notice is now a prominent progress surface that
shows only the content-free committed count and explicitly says some
conversations and older messages are missing.

The first complete aggregate stopped only because lint correctly found the old
generic incomplete-history string unused after both screens moved to the new
pluralized progress copy. Removing that dead resource produced a definitive
`BUILD SUCCESSFUL` in 1m41s across 886 tasks (110 executed, five from cache,
771 up-to-date). All 579 host JUnit results passed with zero failures, errors,
or skips: app 278, design system 11, index 69, model 19, notifications 21,
state 56, telephony 82, testing 24, and conversations 19. Debug, R8 release,
benchmark, all lint variants, clean-room/private-art scans, dependency locks,
permission and APK-content ledgers, and license gates passed.

The complete API 36 matrix was `BUILD SUCCESSFUL` in 2m01s across 456 tasks.
It reported 333 tests with 10 intentional environment skips, 323 executed, and
zero failures/errors: app 142/9 skips, benchmark 3/1, index 32/0,
notifications 31/0, state 62/0, telephony 35/0, and conversations 28/0. The
complete API 26 matrix was `BUILD SUCCESSFUL` in 2m11s across 456 tasks. Its
authoritative XML reports 336 tests with 13 intentional skips, 323 executed,
and zero failures/errors: app 145/12 skips, benchmark 3/1, index 32/0,
notifications 31/0, state 62/0, telephony 35/0, and conversations 28/0. The
focused updated conversation UI suite separately passed 28/28 on API 36.

`bundleRelease` passed 269 tasks in 9s. CycloneDX 1.6 passed 15 tasks in 8s
with its deterministic BOM unchanged.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 15,515,212 | `532d9e5a985db2b756f8c30593cbf7866b02740c5df7d838e624699619b4b4c1` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,888,993 | `e405e490abf546b3f30252da3ede967b2f5da384ef1b65ae9279751a28e2de7d` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,749,821 | `4d3732f984b3ea5a66811bddd8576bcb2af3fc5c7ea0e85089f914b514eeba57` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,888,098 | `b2b4e9f0986da28730a0555e381a5bdd3321f5c08c63895386b3a2956eecee43` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed with data preserved on the Pixel 8 and API 36
emulator and was copied to each Download directory. Both copies hash-match the
host artifact and both packages report version code 16/name `0.6.5-phase6`.
The Pixel retained `org.fossify.messages.debug`; API 36 retained
`com.android.messaging`. Aurora's SMS and notification permissions remain
denied on both. AuroraSMS was not launched, no live message/address/body was
read, and no carrier SMS/MMS was submitted. Physical completion remains open
until the owner explicitly grants AuroraSMS the SMS role and leaves it
foreground-readable through verified completion.

### Phase 6E local spam and provider-preserving blocking — 2026-07-19

This source identifies as `0.6.6-phase6` (`versionCode` 17), retains index
schema 3, advances durable state from schema 11 to 12, and implements ADR 0019.
The migration adds one bounded `spam_safety_decisions` table with physical
SQLite constraints for at most 256 rows, purpose-separated lowercase SHA-256
keys, verified one-person blocks, meaningful states, and monotonic optimistic
transitions. It stores no raw sender, participant, contact, body, snippet,
keyword, or score.

Automatic rules warn only for an unknown conventional phone sender whose newest
incoming snippet contains a link, a reviewed urgency term, and a reviewed
sensitive-request term. Contacts, short codes, alphanumeric senders, groups,
outgoing messages, and incomplete identities are not automatically warned.
Automatic rules pause when contact access is unavailable rather than treating
an unresolved sender as unknown.
User spam/not-spam and block/unblock choices are independent. Inbox and Thread
show the exact reason, while Spam & blocked revalidates every stored row against
the complete current index identity and exposes separate Not spam and Unblock
recovery. Nothing is automatically hidden or deleted.

The incoming blocking test proves the provider row is written and acknowledged
while Aurora notification posting, contact resolution, reply-target ownership,
and reminder scheduling are skipped. A lookup exception proves fail-open normal
notification behavior. Migration/repository tests cover persistence, physical
constraints, optimistic revisions, classification/block independence, clearing,
and refusal to block a group. Compose acceptance covers Inbox warnings and
route entry, Thread actions and explicit block confirmation, dedicated-route
reasons, and independent recovery actions.

The final complete offline host aggregate was `BUILD SUCCESSFUL` in 1m33s
across all 886 Gradle tasks (50 executed, 836 up-to-date). All 587 host tests
passed with zero failures, errors, or skips: app 284, design system 11,
index 69, model 19, notifications 21, state 58, telephony 82, testing 24, and
conversations 19. Debug, R8 release, benchmark, all lint variants, clean-room/
private-art scans, dependency locks, permission and APK-content ledgers, and
license gates passed.

The complete API 36 matrix was `BUILD SUCCESSFUL` in 2m05s across 456 tasks.
Authoritative XML reports 339 tests with 10 intentional environment-gated
skips, 329 executed, and zero failures/errors: app 142/9 skips, benchmark 3/1,
index 32/0, notifications 31/0, state 65/0, telephony 35/0, and conversations
31/0. The complete API 26 matrix was `BUILD SUCCESSFUL` in 2m13s across 456
tasks. It reports 342 tests with 13 intentional skips, 329 executed, and zero
failures/errors: app 145/12 skips, benchmark 3/1, index 32/0, notifications
31/0, state 65/0, telephony 35/0, and conversations 31/0. The focused full
conversation UI class separately passed 31/31 on API 36.

`bundleRelease` passed 269 tasks in 8s. CycloneDX 1.6 passed 15 tasks in 8s
with 441 components, 442 dependency nodes, and no random serial number. The
attempt to request bundle and SBOM in one Gradle invocation failed during task
configuration before either task executed; each independent clean invocation
then passed and is the acceptance evidence.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 15,216,441 | `a7d388c18af8582622fc8e1d446fd04b7cbc97dc1212f84ed3b7081af29ddb00` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,928,769 | `7eba04af5a1c020e4809094abb4de2523e49b7e662facef7d7df06c2c6127e0d` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,773,213 | `1a864861656911e21523c37b058ab76e07610482f594e482c6a46b704b29b782` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,966,424 | `f529efa8c9418d6dd5be4e3c1ba407b85cb8cd401c3d3b8cf1d21a03dacead91` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed with data preserved on the Pixel 8 and API 36
emulator and was copied to `/sdcard/Download/AuroraSMS-debug.apk` on both. Both
copies are 15,216,441 bytes and hash-match the host artifact. Both packages
report version code 17/name `0.6.6-phase6`. The Pixel retained
`org.fossify.messages.debug`; API 36 retained `com.android.messaging`.
Aurora's SMS and notification permissions remain denied on both. No Activity
launch was issued; both packages were left force-stopped and the final PID
checks were empty. No live message/address/body was read and no
carrier SMS/MMS was submitted.

### Phase 6 interrupted-history cache presentation repair — 2026-07-19

This source identifies as `0.6.7-phase6` (`versionCode` 18), retains index
schema 3 and durable-state schema 12, and implements ADR 0020 without a
migration. Content-free Pixel inspection confirmed the user-visible failure:
the newest paused generation had committed 2,100 rows, while the rebuildable
index physically retained 5,226 messages and 73 conversation projections across
four partial generations. Fossify held the SMS role and Android denied Aurora's
SMS permissions, so Aurora could neither continue the provider scan nor claim
that cached state was current.

While coverage is incomplete, Inbox pagination, exact conversation lookup,
Thread pagination, bounded content expansion, and participant previews now
query the best-known union of retained rows rather than only the newest partial
generation. Provider-qualified row keys still deduplicate content. The current
generation remains the cursor/invalidation epoch, the UI labels the result as
best-known cached history that may not reflect recent changes, and incomplete
coverage still produces no verified participant identity. Every send, delete,
spam/block, signature, subscription, and other exact-identity action therefore
remains disabled or fails closed. Verified completion returns presentation to
strict generation-qualified queries and the existing completion transaction
removes stale rows.

The new database regression constructs a verified generation, starts an
interrupted replacement generation, and proves Inbox pages both generations,
an older conversation remains openable, and Thread pages plus bounded content
expansion retain both old and new rows. It passed 4/4 as part of the complete
33-test index suite on both API 26 and API 36. The updated full conversation UI
class passed 31/31 on API 36 and verifies the explicit best-known-cache notice.

The complete offline host aggregate was `BUILD SUCCESSFUL` in 2m08s across all
886 Gradle tasks (160 executed, 5 from cache, 721 up-to-date). All 587 host
tests passed with zero failures, errors, or skips: app 284, design system 11,
index 69, model 19, notifications 21, state 58, telephony 82, testing 24, and
conversations 19. Debug, R8 release, benchmark, all lint variants, clean-room/
private-art scans, dependency locks, permission and APK-content ledgers, and
license gates passed.

The complete API 36 matrix was `BUILD SUCCESSFUL` in 2m06s across 456 tasks and
reports 340 tests with 10 intentional environment-gated skips, 330 executed,
and zero failures/errors: app 142/9 skips, benchmark 3/1, index 33/0,
notifications 31/0, state 65/0, telephony 35/0, and conversations 31/0. The
complete API 26 matrix was `BUILD SUCCESSFUL` in 2m14s across 456 tasks and
reports 343 tests with 13 intentional skips, 330 executed, and zero
failures/errors: app 145/12 skips, benchmark 3/1, index 33/0, notifications
31/0, state 65/0, telephony 35/0, and conversations 31/0.

`bundleRelease` passed 269 tasks in 9s. CycloneDX 1.6 passed 15 tasks in 8s
with 441 components, 442 dependency nodes, and no random serial number.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 14,979,598 | `9280c10acca23ac84dc69ab8301e137821b785de4c055097aae68dd16938c6e2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2,928,857 | `c1adc3e4eedc6c0a985e93341a6eefb09a1b7347067e826f1fe2d8a31fa78c7a` |
| `app/build/outputs/apk/benchmark/app-benchmark.apk` | 2,789,685 | `b4b31a11312704b9f75f30f3fca681290dbc6df896b8b95b9489a2c0cdf66cb7` |
| `app/build/outputs/bundle/release/app-release.aab` | 5,974,316 | `c940f4aba9cc589ac8c48451bf85e55cc60abb2002eaf395cf11cfed16b024f7` |
| `build/reports/bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK installed with data preserved on the Pixel 8 and API 36
emulator and was copied to `/sdcard/Download/AuroraSMS-debug.apk` on both. Both
copies are 14,979,598 bytes and hash-match the host artifact. Both packages
report version code 18/name `0.6.7-phase6`. The Pixel retained Fossify and API
36 retained AOSP Messaging as their default SMS apps. The acceptance procedure
did not request a role change, launch AuroraSMS, inspect a live message/address/
body, or submit carrier traffic. Authoritative complete-history acceptance
still requires the owner to explicitly make AuroraSMS default and keep it open
through verified completion; the repair makes the existing private cache useful
without misrepresenting it as current.

### Phase 6F bounded voice-memo MMS implementation evidence — 2026-07-19

This source identifies as `0.6.8-phase6` (`versionCode` 19), retains index
schema 3 and durable-state schema 12, and implements ADR 0021. Microphone access
is absent from messaging onboarding and originates only from the explicit
Thread Record action. Capture is one visible foreground MPEG-4/AAC-LC session,
limited to 60 seconds and 524,288 bytes in `noBackupFilesDir`; Stop enters a
separate review state and cancellation, Thread exit, or backgrounding deletes
the private file.

The exact official-AOSP composer subset is pinned and noticed under
`third_party/aosp-mms/`. It emits only one-person SMIL/optional signature-text/
audio PDUs and has no incoming parser, APN/network client, transaction service,
database, UI, group, or arbitrary-attachment surface. The deterministic golden
PDU is 539 bytes with SHA-256
`e8abd80ab558cc9ba2179519cb928b131889f78d35b7258ba419cc6a0bd87867`;
the corpus reaches the full 524,288-byte audio limit.

Provider persistence writes parts first, verifies one exact
creator/Thread/transaction-bound FAILED row, and requires an exactly applied
OUTBOX transition before the platform call. A checksummed content-free journal
owns preparation/submission/callback recovery; SUBMITTING process death becomes
non-retryable submission-unknown, and exact private callback identity is
authenticated before provider mutation.

Focused evidence is green: the real virtual-microphone controller executes 3/3
tests on API 26 and 3/3 on API 36; exact MMS callback-intent tests execute 3/3
on each; authenticated callback reconciliation executes 3/3 on each; the
encoder/journal/role group executes 10/10 on each; the complete Thread UI class
executes 33/33 on each; and the API 36 in-process provider fixture executes 3/3
byte-persistence/status/cleanup/rollback tests. API-independent host tests cover
bounded values, operation namespaces, onboarding permission exclusion, every
journal crash state, and the exact carrier-boundary transition. All fixtures use
synthetic recipients/content and fake transport/provider boundaries. No live
provider content was read and no carrier MMS was submitted.

The complete offline aggregate passed in 1m47s across all 888 Gradle tasks
(145 executed, six from cache, 737 up-to-date):

```shell
./gradlew test lintDebug lintRelease assembleDebug assembleRelease \
    :app:lintBenchmark :app:assembleBenchmark \
    :macrobenchmark:check :macrobenchmark:assembleBenchmark \
    verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions \
    verifyApkContents checkLicense generateLicenseReport \
    --offline --no-daemon --no-parallel --console=plain \
    -Pkotlin.incremental=false
```

All 601 retained host JUnit results passed with zero failures, errors, or
skips:

| Module | Tests |
|---|---:|
| app | 287 |
| design system | 11 |
| index | 69 |
| model | 19 |
| notifications | 21 |
| state | 58 |
| telephony | 93 |
| testing | 24 |
| conversations | 19 |
| **Total** | **601** |

`bundleRelease` separately passed 270 tasks in 25s. `cyclonedxBom` separately
passed all 15 tasks in 7s; the generated CycloneDX 1.6 graph contains 441
components and 442 dependency nodes.

The complete API 36 connected matrix passed 457 tasks in 2m03s. The complete
API 26 matrix passed 457 tasks in 2m16s. Authoritative XML counts are:

| Module | API 26 tests | API 26 skips | API 36 tests | API 36 skips |
|---|---:|---:|---:|---:|
| app | 148 | 12 | 145 | 9 |
| benchmark guards | 3 | 1 | 3 | 1 |
| index | 33 | 0 | 33 | 0 |
| notifications | 31 | 0 | 31 | 0 |
| state | 65 | 0 | 65 | 0 |
| telephony | 49 | 0 | 52 | 0 |
| conversations | 33 | 0 | 33 | 0 |
| **Total** | **362** | **13** | **362** | **10** |

All executed connected tests passed with zero failures or errors. Skips remain
explicit opt-in physical/carrier/system-picker journeys; the API 26 provider
fixture is also SDK-suppressed because its in-process `ContentResolver` harness
is platform API 29+, while API 26 still executes the encoder, callback, journal,
UI, and real virtual-microphone contracts.

The accepted artifacts are:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 15,196,362 | `35b373975865055cead5979d20e8ef6bb0c6225030b3d70be8bae52712b45a47` |
| `app-release-unsigned.apk` | 2,985,817 | `42f2e0691d8ecd81e0a4a5aba667db4e62cc7608ad3555ff919fa0e4a2003b29` |
| `app-benchmark.apk` | 2,830,261 | `0d4e475fb3d150127acd861858a45254ef02f462ac70a0e44288e959efd85a8b` |
| `app-release.aab` | 6,075,309 | `dff0c83488bf35fbddc1c6463eb897e411f1c769100b767b4e0af61426a1ad90` |
| `bom.json` | 1,014,122 | `4b88fc0a90b95b6d90607bc8717d8f7359dfa08ae0ee7ae9e75671b462a0e765` |

The exact debug APK was installed and copied to
`/sdcard/Download/AuroraSMS-debug.apk` on the API 26 and API 36 emulators. Both
15,196,362-byte copies hash-match the host artifact, report version code 19/name
`0.6.8-phase6`, remain force-stopped, and retained `com.android.messaging` as
the default SMS app. The Pixel's prior wireless endpoint was unreachable after
the editor crash (`No route to host`), so no Phase 6F physical handoff is
claimed. The acceptance did not request role or permission changes, launch the
app, inspect live message content, or submit carrier traffic. Carrier/OEM and
physical-device voice-MMS acceptance remain open and require a separate owner-
approved protocol.

## Phase 6G authenticated backup and synthetic provider-restore checkpoint

The version-one envelope, exact SMS/MMS/part schemas, bounded provider export,
full authenticated visitor, content-free recovery journal, duplicate analysis,
non-sendable staging, and Android `ContentResolver` restore adapter are now
implemented. Restore writes every new parent as an exact app-owned `FAILED`
placeholder, journals reserve/insert/expect/prepare ordering with `fsync`,
streams binary parts while hashing, re-reads every scalar/address/part, derives a
new local provider thread from restored participants, and exposes only Inbox,
Sent, or inert Failed history. Archived Draft/Outbox/Queued rows remain Failed.

Exact duplicate decisions are capped at 200 SMS or eight MMS parent candidates
and 1,000 parts, use the safe restored box, and re-read the matching row before
skip. A stateful test-only `ContentProvider` proves role/permission fencing before
provider access, exact SMS/MMS/text/binary persistence, local thread assignment,
idempotent full replay including historical Outbox, provider-normalization
cleanup, pre-ID and expected-digest rollback, changed-row quarantine, and
rollback of an already committed MMS after a later SMS commit conflict. The
fixture is private to the test APK and never proxies Telephony content.

Focused checkpoint evidence on 2026-07-20:

- 26/26 `feature:backup` host tests passed;
- strict module lint, Android-test packaging, clean-room/private-asset scans,
  and dependency verification passed;
- 9/9 connected tests passed on API 26 with zero failures/errors/skips; and
- 9/9 connected tests passed on API 36 with zero failures/errors/skips.

The following private staging slice also passed strict module lint and six of
six focused connected tests on each API. It proved owner-only regular files in
`noBackupFilesDir`, bounded source-copy failure cleanup, one combined wrong-
passphrase/tamper result, rejection of an authenticated but invalid message
schema, `.pending` to `.validated` promotion only after full validation, startup
cleanup, cancellation cleanup, and symlink/path-replacement rejection without
following or deleting the foreign target.
Together with the provider checkpoint, the complete module suite now passes
15/15 connected tests on API 26 and 15/15 on API 36 with zero failures, errors,
or skips.

The single-owner document controller adds six focused connected journeys on
each API. Fake content documents prove encrypted export, validation of the
produced archive, incomplete-destination deletion/reporting, rejection of
non-content URIs, immediate source-copy ownership, wrong-passphrase retry,
cancel invalidation, restore unreachable before authenticated-summary
confirmation, one-shot confirmed restore, and provider recovery before startup
staging cleanup. No test resolves a live document or Telephony URI.

The tests used only canonical synthetic addresses, bodies, metadata, and a
196,608-byte generated binary part. They did not acquire the SMS role, inspect
live provider rows, mutate a real Telephony provider, launch a carrier boundary,
or cross a carrier boundary.

The `0.6.9-phase6` app-integration slice at exact commit
`2c3cfb0bfef5092b734464e80f38792c8b012091` adds a searchable Settings screen and
the production `CreateDocument`/`OpenDocument` route. Passphrases are neither
saveable nor durable, startup recovery and private staging reconciliation gate
all file actions, only an authenticated summary exposes confirmation, and
background/navigation invalidates pre-mutation restore work. Export failure
reports when a provider refuses incomplete-destination deletion. Restore UI
states explicitly disclose duplicate skipping, non-sendable historical boxes,
and incomplete rollback.

Focused app evidence passed on both API 26 and API 36: five Backup & restore
state tests, one searchable-Settings test, and one real Inbox-to-Settings route
journey. The state suite proves that pending or failed startup recovery exposes
no file action, wrong-passphrase state exposes no confirmation, only
authenticated review exposes the explicit restore action, and incomplete-file
and rollback warnings remain visible. The complete backup module passed 21/21
connected tests on each API with no failures, errors, or skips.

The exact source then passed all 628 host tests and the complete offline
host/lint/R8-release/benchmark/privacy/dependency/permission/APK-content/license
aggregate: `BUILD SUCCESSFUL` in 1m54s across 977 tasks (175 executed, 24 from
cache, 778 up-to-date). Release-bundle generation passed 306 tasks, and the
CycloneDX 1.6 aggregate passed 16 tasks with 444 components and 445 dependency
relationships. The complete connected matrix passed 390 tests on API 26 (13
intentional opt-in skips) and 390 on API 36 (10 intentional opt-in skips), with
zero failures or errors on either device.

| Exact commit `2c3cfb0` artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 15,384,521 | `8652064112772bbdaaf62b3c641bddf9001081d8c81a2105c6f12b819bb2edab` |
| `app-release-unsigned.apk` | 3,104,353 | `bcbf1b9a66829c57551ba1e11243a829683175d6eb0602ffbc91364093c3124b` |
| `app-benchmark.apk` | 2,948,797 | `7d54b1fc9e5493d181b40c3e8b947f79be518d16db514545ed2be293a56ebe7e` |
| `app-release.aab` | 6,303,529 | `163a6705a25d6ddae82836e0d55ec2468819c81a79839e59b29a791f393d9de4` |

The exact debug APK installed and hash-matched at
`/sdcard/Download/AuroraSMS-0.6.9-phase6-debug.apk` on both emulators. Installed
versionCode 20/versionName `0.6.9-phase6` was confirmed, and both retained
`com.android.messaging` as the default SMS app. No live message inspection,
role transition, provider mutation, screenshot, or carrier submission occurred.
The Pixel was not attached. Phase 6G's code and release gates are closed, but the
phase remains open for real physical/OEM document-picker selection and
cancellation acceptance.

## Phase 6H generation-fenced Android Auto notification acceptance

Version `0.6.10-phase6` (`versionCode=21`) at exact implementation commit
`70552cff386895b9497e95d14992bf5284806e12` implements ADR 0023. The app manifest
references `automotive_app_desc.xml`, whose exact capabilities are
`notification` and `sms`. The merged notification module registers one enabled,
non-exported background action service while retaining the legacy receiver only
for already-issued reply intents.

Each incoming conversation publishes one private `MessagingStyle` generation
with a generic public version. At most 25 chronological same-conversation
messages are retained, and only when privacy mode, group flag, and conversation
title still match. A privacy or group-identity transition starts fresh. New
notifications expose semantic Reply with exactly one `RemoteInput` and semantic
invisible Mark as read without a `RemoteInput`; neither opens UI. Reply enters
the existing durable inline-reply owner. Mark as read validates the exact SMS
source row and expected thread, changes only incoming SMS rows through that ID,
then cancels only that generation and reminder owner before requesting index
reconciliation.

The first complete API 26 pass exposed a real platform timing defect: a second
notification posted before Android 8 surfaced the first in
`activeNotifications` could lose the first message from the car-visible style.
The corrected gateway keeps at most 64 just-posted snapshots for two seconds and
drops each as soon as the platform reports it. It is process-local presentation
state, not durable or provider authority. A subsequent run exposed that the
platform assertion itself could briefly observe the older shared slot; the test
now waits for the exact expected source generation. The focused seven-test
Android Auto class and final full notification module both pass on API 26 and
API 36.

Acceptance evidence on 2026-07-20:

- 636/636 retained host tests pass with zero skips, failures, or errors;
- the complete offline host/lint/R8/benchmark/privacy/dependency/permission/
  APK-content/license aggregate passes in 1m39s across 977 tasks (109 executed,
  two from cache, 866 up-to-date);
- retained connected XML records 399 tests on API 26 with 13 intentional opt-in
  skips and 399 tests on API 36 with 10, with zero failures or errors;
- release bundle generation passes 306 tasks, and CycloneDX 1.6 generation
  passes 16 tasks with 444 components and 445 dependency relationships; and
- permission and packaged-APK checks confirm no `INTERNET` or
  `ACCESS_NETWORK_STATE` addition.

| Exact commit `70552cf` artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 15,504,195 | `9aed671c7b1ed495264a48748ccbdacd74ba720ee78626d815b6b924aca835ed` |
| `app-release-unsigned.apk` | 3,150,197 | `cfcd1b334916d666ea387984c5b4cd9841f525c1b3728a4689fedf705928b94d` |
| `app-benchmark.apk` | 2,994,641 | `ff6a914fd746c23289dd76bbd955fa34a2c99ec6340c505d91a15d41d7f67944` |
| `app-release.aab` | 6,358,836 | `5a615999d38547d4403ddaf4b4b75a09400749d220f2bc03866f220933accf34` |

The exact debug APK installed and hash-matched at
`/sdcard/Download/AuroraSMS-0.6.10-phase6-debug.apk` on both emulators. Installed
versionCode 21/versionName `0.6.10-phase6` was confirmed, both retained
`com.android.messaging` as the default SMS application, and AuroraSMS was left
force-stopped. No live message/address/body was inspected, no role or permission
was changed, and no carrier action occurred. The Pixel was not attached.

Actual Android Auto/DHU rendering and voice interaction, physical/OEM shade and
lockscreen behavior, carrier-success reply, real-provider Mark as read mutation,
and incoming/group MMS remain open. These are not inferred from host or emulator
notification metadata. Phase 6H's implementation and synthetic API-floor/latest
contracts are closed; its physical/carrier acceptance is not.

## Remaining Phase 5 lifecycle/action matrix

- [x] Scheduled send has content-free durable state, duplicate-alarm idempotence,
  process-start recovery, and fail-closed reboot/time/timezone handling. Physical
  reboot/Doze/carrier timing remains open.
- [x] Exact-alarm denial/revocation has ADR 0011's labeled inexact fallback,
  distinct safety alarm, and explicit special-access route. Physical revocation
  acceptance remains open.
- [x] Send-delay Undo has content-free process-death recovery, duplicate-alarm
  idempotence, clock/lateness fail-closed handling, and no Undo after dispatch
  ownership. Real carrier and unlocked physical lifecycle timing remain open.
- [x] Pending deletion has deterministic process-death behavior. Pending work is
  recoverable; an interrupted provider commit is inspected and never blindly
  replayed.
- [x] Remembered subscription is durable and scoped per conversation for the
  verified one-person Thread composer path.
- [x] A removed/disabled remembered SIM prompts a safe fallback; scheduled and
  group sends never silently switch subscriptions.
  The existing one-part composer now disables Send and requires an explicit
  replacement in synthetic API 26/API 36 tests; scheduled dispatch also pauses
  for review without fallback. Physical removal plus group paths remain open.
- [x] Whole-thread deletion uses stronger two-step confirmation.
- [x] After provider deletion commits, UI never claims recoverability.
- [x] No recycle-bin UI/schema/preference/worker exists.
- [x] Every currently reachable group path proves no individual fan-out.
  `SmsSendRequest` cannot represent a group; Thread/delay/schedule refuse group
  identities; respond-via performs one MMS call and never falls back to SMS.
  Full group-MMS feature and carrier acceptance remain open below.

## Phase 6 feature/privacy matrix

- [x] Notification reminder scheduling is local and battery-conscious. It is
  off by default, bounded to one one-shot inexact alarm per conversation and 64
  content-free owners total, revalidates exact unread provider state, and fails
  closed across role, setting, reboot, and clock boundaries.
- [x] Reaction fallback parsing never mutates stored SMS and handles ambiguity.
  Exact bounded whole-message forms render locally; all ambiguous or incomplete
  forms fail open to the original raw text.
- [x] Voice memo requests microphone only from explicit Record action, indicates
  recording, limits output, requires separate review/Send, and cleans temporary
  files. Exact provider/journal/callback gates are automated; real carrier/OEM
  acceptance remains open.
- [x] Selected-text copy exposes only the selected content. Invalid/collapsed
  selections fail closed, truncated previews are labeled, and details excludes
  bodies, addresses, provider IDs, and attachment paths.
- [x] Global/per-thread signatures show segment/MMS impact before send. Exact
  signed text is frozen across immediate, delayed, and scheduled recovery;
  multipart and group transport continue to fail closed.
- [x] Spam rules are bounded, explainable, contacts-trusting by default, and
  support unspam/unblock; suspected spam is never silently deleted.
- [x] Versioned streaming backup validates limits, paths, checksums,
  authentication/encryption, schema, and media before atomic import.
- [x] Android Auto notification metadata, bounded conversation history,
  privacy/group reset, reply/mark-read action semantics, and exact stale-action
  fencing pass host and API 26/API 36 checks.
- [ ] Android Auto/DHU or a physical car completes reply and Mark as read.
- [x] Phase 6 adds no undeclared network path; merged manifests and packaged
  APKs pass the permission ledger.

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
