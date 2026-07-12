# Phase 1 file-level plan

Status: proposed at the Phase 0 gate; no production files created

## Phase 1 outcome

Create an original Android foundation that qualifies for the default SMS role,
exposes a debug-only diagnostic UI, and proves one end-to-end received/sent SMS
and MMS path including group MMS and dual SIM. This is a telephony vertical
slice, not the final inbox, index, database, or AuroraMaterial UI.

## Acceptance criteria before editing

- New package installs and requests the default SMS role correctly on API 26
  and API 29+ paths.
- Official `SENDTO`, respond-via-message, SMS delivery, and MMS delivery
  components resolve with correct guards and explicit exported state.
- A physical device sends/receives one-to-one SMS and MMS through Aurora-owned
  code paths.
- A physical device sends one group MMS with no individual fan-out.
- Explicit dual-SIM selection uses subscription-specific public APIs and
  rejects an unavailable or stale supplied subscription safely; durable
  per-conversation remembering remains Phase 5 state work.
- Received messages are persisted through the Telephony provider and notify
  the user once.
- Provider, transport, role, subscription, receiver, and notification
  contracts have fakes/tests.
- Build, lint, tests, dependency/license report, merged-manifest permission
  check, private-asset check, and clean-room scan pass.
- No reference-app source/artifact/dependency/history, `INTERNET`, recycle bin,
  group-format preference, search index, final UI, or theme engine is added.

## Toolchain baseline

Use the locally verified compatible baseline unless official compatibility
changes before Phase 1 starts:

| Item | Pin/decision |
|---|---|
| Gradle wrapper | 9.4.1 |
| Android Gradle Plugin | 9.2.1 |
| Compile/target/min SDK | 37 / 36 / 26 |
| Kotlin/Compose plugin | 2.3.10 |
| Host build JDK | Existing JDK 26.0.1 locally; JDK 17 permitted in CI |
| Java/Kotlin bytecode | 17 |
| Application ID/namespace | `org.aurorasms.app` |
| UI | Compose diagnostic shell only |

AGP 9 supplies built-in Kotlin for Android modules. Do not apply
`org.jetbrains.kotlin.android`. Apply `org.jetbrains.kotlin.jvm` only to the
pure JVM model module and `org.jetbrains.kotlin.plugin.compose` only where
Compose is compiled. Do not request a Java 17 Gradle toolchain locally while no
JDK 17 installation exists; run Gradle with the host JDK and set source/target
and Kotlin JVM output to 17.

Use the wrapper exclusively. Local SDK configuration uses an ignored
`local.properties` or `ANDROID_SDK_ROOT`; the repository never commits an
absolute workstation path. Project/device commands resolve ADB from that SDK's
platform-tools rather than an unrelated system installation.

The exact Phase 1 dependency allowlist is in `docs/DEPENDENCY_POLICY.md`.
Compose BOM 2026.06.01, Core 1.19.0, Lifecycle 2.11.0, coroutines 1.10.2,
Material3 1.4.0, and JUnit 4.13.2 are locally available. Current stable Activity
Compose 1.13.0, AndroidX instrumentation artifacts, license-report 3.1.4, and
CycloneDX 3.2.4 require an audited download in this environment. A cache hit is
not approval and a cache miss is not permission to pin an older release. Every
downloaded plugin and library is admitted only after its resolved transitives,
license, startup/manifest behavior, and checksums pass the dependency policy.
Room, Paging, KSP, DataStore, navigation, WorkManager, image loaders, SQLCipher,
and benchmark plugins remain outside Phase 1.

Compile SDK 37 is required because approved Core 1.19.0 and Lifecycle 2.11.0
publish `minCompileSdk=37`; target SDK remains 36 so target-37 behavior changes
are not accepted accidentally. This build choice does not enable or claim API
37 alternative-message transport or RCS.

## Modules created in Phase 1

Only current-phase modules are created:

- `:app` - application, role onboarding, dependency wiring, compose-message
  entry, and debug-only diagnostics;
- `:core:model` - pure JVM immutable IDs and transport-neutral results;
- `:core:telephony` - provider/role/subscription adapters, receivers/service,
  and SMS/MMS transport;
- `:core:notifications` - channels, `MessagingStyle`, privacy, and inline reply;
- `:core:testing` - fakes/builders available only to tests/debug.

Defer `:core:index`, `:core:state`, `:core:designsystem`, feature modules, and
`:benchmark` until their roadmap phases. Do not invent a diagnostics feature
module or empty future modules.

## Root build and governance files

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
gradle/verification-metadata.xml
config/clean-room/private-reference-sha256.txt
config/licenses/allowed-licenses.json
config/licenses/license-normalization.json
scripts/verify-clean-room.sh
scripts/verify-permissions.sh
scripts/verify-apk-contents.sh
.github/workflows/verify.yml
```

Responsibilities:

- centralize plugin/dependency versions and approved repositories;
- configure Java/Kotlin target 17 and deterministic archives;
- enable dependency verification/locking and reproducible CI inputs;
- pin Gradle's `distributionSha256Sum` to
  `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
  and verify the committed wrapper JAR SHA-256 is
  `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`;
- expose root verification tasks for clean-room tokens, dependencies,
  permissions, private paths, backup rules, and APK contents;
- enable Android lint dependency checking from `:app` so lint traverses every
  Android library module, and retain an aggregate all-module host-test gate;
- run pinned license-report 3.1.4 locally with no remote imports and
  `--no-parallel`, writing its deterministic inventory below
  `build/reports/dependency-license/`, plus CycloneDX 3.2.4 aggregate SBOM
  generation at `build/reports/bom.json` and `build/reports/bom.xml`;
- review the build plugins' own resolved transitives before merge and make the
  report tasks fail on an unapproved artifact or incompatible/unknown license;
- build/test/lint without any network-capable runtime dependency.

Before any MMS implementation, add:

```text
docs/adr/0001-mms-pdu-strategy.md
```

The ADR must compare an original bounded implementation with official Android
platform/framework PDU material that is not sourced from an end-user messaging
app, or a maintained permissive library. It records exact provenance, license,
API/format coverage, carrier behavior, transitives, manifest/network effects,
fuzz strategy, size, and removal plan. The locally cached
`org.fossify:mmslib` is prohibited and may not be inspected or resolved.

## `:app` file plan

```text
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/kotlin/org/aurorasms/app/AuroraSmsApplication.kt
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
app/src/main/kotlin/org/aurorasms/app/compose/ComposeMessageActivity.kt
app/src/main/kotlin/org/aurorasms/app/role/DefaultSmsRoleCoordinator.kt
app/src/main/kotlin/org/aurorasms/app/role/SmsRolePlatform.kt
app/src/main/kotlin/org/aurorasms/app/role/AndroidSmsRolePlatform.kt
app/src/main/kotlin/org/aurorasms/app/role/RoleOnboardingState.kt
app/src/main/kotlin/org/aurorasms/app/message/IncomingMessageOrchestrator.kt
app/src/main/kotlin/org/aurorasms/app/message/InlineReplyOrchestrator.kt
app/src/main/kotlin/org/aurorasms/app/message/AppNotificationIntentFactory.kt
app/src/main/kotlin/org/aurorasms/app/diagnostics/DiagnosticsLauncher.kt
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/values/colors.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsRoute.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsViewModel.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsScreen.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/BuildVariantDiagnosticsLauncher.kt
app/src/debug/res/values/strings.xml
app/src/release/kotlin/org/aurorasms/app/diagnostics/BuildVariantDiagnosticsLauncher.kt
app/src/test/kotlin/org/aurorasms/app/role/DefaultSmsRoleCoordinatorTest.kt
```

`MainActivity` owns onboarding/navigation host only. `ComposeMessageActivity`
handles the official external `SENDTO` schemes and never auto-sends.
`AppContainer` performs small manual constructor wiring. The debug diagnostic
surface reports role, ledgered permissions, telephony feature, active
subscriptions, provider counts, and last synthetic/real transport result with
redacted output. Release code contains no diagnostics screen or synthetic
seeded messaging state.

`DefaultSmsRoleCoordinator` is platform-neutral and depends on the injected
`SmsRolePlatform` contract, so its host test uses a fake and requires no
Robolectric dependency. `AndroidSmsRolePlatform` alone owns `RoleManager` and
the legacy default-SMS intent. Instrumentation tests verify actual platform
intent/role behavior; the host test verifies coordinator transitions only.

`MainActivity` depends only on the main-source `DiagnosticsLauncher` interface.
Debug and release source sets provide the same `BuildVariantDiagnosticsLauncher`
type: debug opens the diagnostic route, while release reports unavailable and
contains no reference to debug UI classes. Release compilation and APK scans
enforce this boundary.

The launcher uses an original minimal vector/letter placeholder created for
AuroraSMS, not supplied Camera ICON or private/reference assets. Final launcher
identity remains an owner artwork decision.

## `:core:model` file plan

```text
core/model/build.gradle.kts
core/model/src/main/kotlin/org/aurorasms/core/model/MessageId.kt
core/model/src/main/kotlin/org/aurorasms/core/model/ProviderMessageId.kt
core/model/src/main/kotlin/org/aurorasms/core/model/ConversationId.kt
core/model/src/main/kotlin/org/aurorasms/core/model/ParticipantAddress.kt
core/model/src/main/kotlin/org/aurorasms/core/model/AuroraSubscriptionId.kt
core/model/src/main/kotlin/org/aurorasms/core/model/ProviderKind.kt
core/model/src/main/kotlin/org/aurorasms/core/model/MessageDirection.kt
core/model/src/main/kotlin/org/aurorasms/core/model/MessageTransportKind.kt
core/model/src/main/kotlin/org/aurorasms/core/model/TransportResult.kt
core/model/src/test/kotlin/org/aurorasms/core/model/IdContractTest.kt
```

IDs distinguish SMS, MMS, drafts, schedules, and pending operations without
assuming provider `_id` uniqueness across kinds. Models are immutable and have
no Android, Room, or UI annotations.

## `:core:telephony` file plan

```text
core/telephony/build.gradle.kts
core/telephony/src/main/AndroidManifest.xml
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/SmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/MmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/MessageTransport.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/ContactResolver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/SubscriptionRepository.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/DefaultSmsRoleState.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/RecipientSet.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/IncomingMessageSink.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/TelephonyEntryPoint.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidSmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidMmsProviderDataSource.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidContactResolver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidSubscriptionRepository.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidSmsTransport.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidMmsTransport.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/MmsPduStagingStore.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/MmsPduFileProvider.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/SmsDeliverReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/MmsWapPushReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/SmsSentReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/SmsDeliveredReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/MmsDownloadResultReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/MmsSendResultReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/DefaultSmsRoleChangedReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/receiver/ExternalProviderChangedReceiver.kt
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/service/RespondViaMessageService.kt
core/telephony/src/main/res/xml/mms_pdu_paths.xml
core/telephony/src/test/kotlin/org/aurorasms/core/telephony/RecipientSetTest.kt
core/telephony/src/test/kotlin/org/aurorasms/core/telephony/TransportPolicyTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/ContactResolverPermissionTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/MmsPduFileProviderTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/MmsPduStagingStoreTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/MmsResultReceiverTest.kt
```

Public interfaces stay free of screen state and styling. Android implementations
perform provider/transport work off main, normalize SMS/MMS date units, use
subscription-specific APIs, and emit typed results. `RecipientSet` is the sole
canonical ordinary-group decision point and has exhaustive no-fan-out tests.
`AndroidContactResolver` treats contact access as optional: when
`READ_CONTACTS` is absent, denied, or revoked, it returns address-only display
data without blocking provider reads, sending, receiving, role onboarding, or
notifications. Device tests cover denial, revocation, empty/malformed rows,
and successful synthetic lookup without exposing a real contact database.

Receivers validate the role and bounded input, finish within platform limits,
write through official provider APIs, and hand one bounded event to the app
orchestrator; the end-to-end pipeline posts at most one notification. Receivers
do not decode attachment media, reference the notifications module, or hold
full histories.

`MmsPduFileProvider` is a custom non-exported `FileProvider` subclass with
cache-only canonical paths. An MMS send stages one bounded source PDU and grants
its unique URI read-only; an MMS download creates one empty dedicated target
and grants its unique URI write-only. Both calls supply explicit result
`PendingIntent`s to the two MMS result receivers. Tests reject traversal,
over-broad paths, wrong-direction grant modes, URI reuse, and external provider
access. Results persist status/content, revoke grants, and delete operation
files idempotently. The MMS ADR may refine filenames/format, but cannot remove
asynchronous result handling, least-privilege URI modes, confinement, cleanup,
or tests.

## `:core:notifications` file plan

```text
core/notifications/build.gradle.kts
core/notifications/src/main/AndroidManifest.xml
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/MessageNotifier.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/NotificationPrivacy.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/NotificationChannels.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/NotificationIntentFactory.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/InlineReplyHandler.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/NotificationEntryPoint.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/AndroidMessageNotifier.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/InlineReplyReceiver.kt
core/notifications/src/main/kotlin/org/aurorasms/core/notifications/NotificationConfig.kt
core/notifications/src/main/res/values/strings.xml
core/notifications/src/main/res/drawable/ic_notification.xml
core/notifications/src/test/kotlin/org/aurorasms/core/notifications/NotificationPrivacyTest.kt
core/notifications/src/test/kotlin/org/aurorasms/core/notifications/PendingIntentPolicyTest.kt
```

The module owns `MessagingStyle`, privacy levels, channel behavior, tap/deep
link intent construction, and validated inline-reply input. It depends only on
model contracts, never telephony or app internals.

Dependency direction is acyclic: `app` depends on telephony and notifications;
neither depends on the other. System-created telephony receivers obtain a
`TelephonyEntryPoint` implemented by `AuroraSmsApplication`, then deliver a
bounded model to `IncomingMessageSink`; the app's
`IncomingMessageOrchestrator` calls `MessageNotifier`. The notification reply
receiver similarly obtains a `NotificationEntryPoint` and calls an
`InlineReplyHandler`; the app's `InlineReplyOrchestrator` validates current
role/subscription and delegates to `MessageTransport`. `AppContainer` owns all
implementations. `NotificationIntentFactory` is supplied by the app so the
notification module never names an app Activity or navigation route. No module
casts to an app implementation class or uses a global event bus.

## `:core:testing` file plan

```text
core/testing/build.gradle.kts
core/testing/src/main/kotlin/org/aurorasms/core/testing/SyntheticPeople.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/SyntheticMessages.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeSmsProviderDataSource.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeMmsProviderDataSource.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeMessageTransport.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeSubscriptionRepository.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeRoleState.kt
core/testing/src/main/kotlin/org/aurorasms/core/testing/FakeMessageNotifier.kt
```

This is an Android library because its fakes implement contracts from the two
Android AAR modules. It depends on model, telephony, and notifications, but the
app consumes it only through `debugImplementation`, `testImplementation`, or
`androidTestImplementation`; it is absent from release runtime resolution. All
identities/content are newly synthetic and visibly fictional; nothing is
transcribed from the private pack or interactive concept.

## Test and device file plan

After an audited AndroidX test dependency is available:

```text
app/src/androidTest/kotlin/org/aurorasms/app/DefaultSmsManifestContractTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/RoleOnboardingTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/ProviderContractTest.kt
core/telephony/src/androidTest/kotlin/org/aurorasms/core/telephony/ReceiverContractTest.kt
core/notifications/src/androidTest/kotlin/org/aurorasms/core/notifications/NotificationContractTest.kt
```

Instrumentation dependencies are currently not available locally and require a
normal audited download. A cache miss is not a reason to skip the tests, copy
another project's test setup, or claim the gate passed.

## Implementation slices

1. Build/governance: wrapper, version catalog, five modules, dependency
   verification, CI scans, and empty build gates.
2. Models/contracts: immutable IDs, provider/transport/role/subscription/
   notification interfaces, fakes, and host tests.
3. Role/manifest: four required entry points, onboarding, permission order,
   role-loss handling, backup rules, and debug diagnostics.
4. SMS vertical: provider reads/writes, SMS receive/send, sent/delivered states,
   subscription selection, notification, and real-device smoke evidence.
5. MMS ADR and vertical: bounded PDU strategy, MMS receive/send/attachments,
   carrier errors, group invariant, and real-device one-to-one/group evidence.
6. Gate hardening: inline reply, malformed-intent tests, complete lint/tests,
   dependency/license/provenance/permission/APK scans, and handoff report.

At most one architecture/schema migration is in flight per slice. Phase 1 adds
no Room schema, so there is no database migration.

## Validation commands at the gate

Discover tasks first, then use actual wrapper tasks. Expected core sequence:

```text
./gradlew tasks --group build
./gradlew tasks --group verification
./gradlew verifyCleanRoom verifyDependencies verifyPermissions verifyPrivateAssets
./gradlew test lintDebug lintRelease assembleDebug assembleRelease
./gradlew --no-parallel checkLicense generateLicenseReport
./gradlew cyclonedxBom
./gradlew connectedDebugAndroidTest            # when a device is available
```

The `:app` Android lint configuration sets `checkDependencies = true`, so both
lint variants traverse the Android library modules. The gate runs every
module's host tests, all available instrumentation tests, both app variants,
and both lint variants. It proves release runtime resolution
excludes `:core:testing` and debug diagnostics, then inspects both merged
manifests/APKs for permissions and private assets. When a telephony-capable
device is connected, use the
project SDK's ADB, install the exact APK, have the user explicitly approve the
default-SMS role and test destinations, verify role/package state, run scoped
SMS/MMS smoke procedures, launch the process, and avoid broad log capture.

After a successful device build, copy the exact debug APK to
`/sdcard/Download/AuroraSMS-debug.apk` and verify the remote file so the owner
has a manual-install fallback.

## Known Phase 1 gate blockers

- No telephony-capable Android device is currently connected, so install, role,
  provider, SMS/MMS, dual-SIM, and physical performance evidence cannot yet be
  produced.
- No MMS PDU implementation/dependency is pre-approved. The Phase 1 ADR and
  provenance/security audit must resolve it without using the prohibited
  cached reference artifact.
- Current stable Activity Compose, AndroidX instrumentation-test,
  license-report, and CycloneDX plugin artifacts require an audited dependency
  download in the current environment, including review of plugin transitives.

These blockers do not authorize reducing the gate. They are resolved during
the approved Phase 1 or reported precisely if external state remains missing.

## Owner-review items before authorization

- Accept or replace `org.aurorasms.app`, GPL-3.0-or-later, API 26 minimum, and
  the F-Droid/GitHub-first distribution baseline before the package is used.
- Provide a telephony-capable test device and explicitly approved test
  destination(s)/cost expectations when transport testing begins.
- Accept or replace the new-key policy and encrypted owner-controlled offline
  backup policy before Phase 1; actual key generation remains deferred until
  release preparation and no key is needed for the debug vertical slice.
- Artwork license and final launcher art may remain deferred because Phase 1
  does not ingest the supplied artwork.

Phase 1 starts only after explicit review authorization. This plan itself does
not authorize production code.

## Official toolchain references

- [Migrate to Android Gradle Plugin built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android Gradle Plugin 9.2 release notes and compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
