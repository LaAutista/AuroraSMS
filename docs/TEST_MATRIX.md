# AuroraSMS test matrix and phase-gate evidence

Status: Phase 1 local evidence, 2026-07-12

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

Current Phase 1 environment has SDK platforms 36 and 37.0 plus a physical
Google Pixel 8 (`shiba`) running Android 16/API 36 with telephony messaging and
subscription features. No emulator, system image, or AVD is installed. The
device evidence below covers only this Pixel/API combination; the remaining
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
  25 current tests pass on a physical Pixel 8/API 36, while the other required
  API/device rows remain pending.
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

- [ ] Initial sync indexes newest first while UI remains usable.
- [ ] Checkpoint resume after process death never restarts from zero.
- [ ] A completed sync runs and persists a lightweight consistency pass and
  completion generation; restart/reconcile does not mistake partial coverage
  for complete.
- [ ] Batch commit makes partial search coverage available and honestly labeled.
- [ ] Observer/receiver duplicate events coalesce.
- [ ] Deletions/external changes reconcile.
- [ ] Unique compound identity and deterministic tie ordering hold.
- [ ] FTS query parser handles empty, punctuation, quotes, prefix limits,
  Unicode normalization, malformed syntax, and hostile input.
- [ ] Result that was never manually scrolled into view is found.
- [ ] Global and in-thread results are paged/bounded.
- [ ] Exact old-result jump uses a bounded before/after anchor load.
- [ ] No deep `OFFSET`, `LIKE '%query%'`, unbounded list, or repeated 50-row
  jump loop exists.
- [ ] Every Room schema version has exported schema and explicit migration test.
- [ ] Dedicated 500k-row and 1m-row database benchmarks record index build,
  search, paging, and exact-anchor costs with repeatable fixture seeds.

## Phase 3 inbox/thread performance matrix

- [ ] Inbox and thread use stable keys and granular updates.
- [ ] A 250k-message thread never creates a 250k-item UI list.
- [ ] Prepending older rows preserves the visible anchor.
- [ ] Incoming messages do not force a user away from their read position.
- [ ] Rotation, split screen, process recreation, and back navigation preserve
  appropriate state.
- [ ] Scroll-to-bottom/new-message affordance works without forced jumps.
- [ ] Contact rename/photo change invalidates only the bounded contact cache.
- [ ] Attachment previews load at display size and release correctly.
- [ ] Debug StrictMode reports no tested main-thread provider/DB/file/decode
  operation.
- [ ] Baseline Profile includes startup, inbox, thread, search, exact jump,
  attachment open, and back journeys.

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
