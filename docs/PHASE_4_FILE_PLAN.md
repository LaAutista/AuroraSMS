# Phase 4 file-level plan

Status: AuroraMaterial foundation and foreground provider-read lifecycle
hardening implemented and verified on 2026-07-14; the bounded durable active
named-profile and Theme Studio slice is accepted for implementation with its
verification evidence pending; overrides, import/export, navigation variants,
media/artwork, and the full accessibility/performance matrix remain gated
follow-on work

## Outcome

Phase 4 gives AuroraSMS one original, versioned appearance system without
coupling appearance choices to Telephony, the rebuildable message index, or
carrier transport. The first slice introduces the immutable profile and token
boundary, preserves the Phase 3 appearance by default, and moves the app's
root Material theme into a dedicated `:core:designsystem` module.

The foundation slice deliberately does not ingest artwork, persist a
user-selected profile, decode a wallpaper/GIF, add a hidden destination screen,
or alter any SMS/MMS behavior. A separate physical-validation fix in this phase
bounds provider reconciliation by foreground lifecycle.

The next bounded slice extends the existing Aurora-owned Room state boundary
from version 1 to version 2 for named profiles and one active selection. It adds
an app-owned Theme Studio destination whose in-memory preview is confined to the
visible Appearance route until atomic `Apply`, Cancel, Back, or route disposal.
It still admits no provider/index/transport coupling, DataStore, override,
import/export, navigation variant, wallpaper, artwork, or decoder.

## Foundation acceptance criteria

- `:core:designsystem` is an Android/Compose library with no dependency on the
  app, Telephony, index, durable state, contacts, notifications, or transport.
- `AuroraMaterialProfile` is immutable, explicitly schema-versioned, and
  rejects unsupported schemas, invalid hue values, and unsafe wallpaper-dim
  values before rendering.
- The default profile retains the Phase 3 primary, secondary, background, and
  surface colors and a 48 dp minimum touch-target token.
- Dark, AMOLED black, light, and system-dynamic palette choices have local
  deterministic fallbacks. Dynamic color uses only the platform API.
- Compact, comfortable, and spacious density tokens never reduce a row below
  the 48 dp target floor.
- Circle, rounded-square, squircle, and hexagon avatar masks resolve to
  concrete Compose shapes without an icon-pack dependency.
- Reduced motion resolves at the token boundary and does not depend on a
  background service or decoder.
- The app root consumes `AuroraMaterialTheme`; the appearance foundation itself
  leaves default inbox/thread behavior, routes, role flow, provider access, and
  transport unchanged.
- Separate lifecycle hardening defers new provider batches and head
  verification when every Aurora messaging activity is stopped, then resumes
  without a false provider-dirty mark.
- No new external coordinate, repository, permission, exported component,
  initializer, native library, network path, artwork, or private asset enters
  the product.
- Dependency locks, checksum verification, licenses, SBOM, clean-room scans,
  APK-content checks, host tests, lint, emulator instrumentation, and a
  privacy-safe physical-device smoke run pass before the slice is pushed.

## Architecture boundary

The design-system module owns declarative appearance vocabulary and rendering
tokens only. It must remain safe to instantiate in a host unit test and must
never receive message bodies, addresses, contact identifiers, provider IDs,
attachment bytes, database handles, or content resolvers.

The application layer owns selection and lifecycle. The current state
repository exposes one validated active named profile, or the code-owned
canonical default, to Compose. It may persist only Aurora-owned appearance
state; changing appearance must not query Telephony, dirty the index generation,
reconstruct the current thread route, or touch carrier transport. Scoped
screen/conversation overrides extend this boundary only in a later slice.

The eventual resolution order is explicit and deterministic:

```text
conversation override [future]
  -> eligible screen override [future]
  -> active named profile
  -> canonical built-in default
  -> accessible solid fallback
```

Missing, revoked, corrupt, unsupported, or unlicensed media always falls
forward through this chain. It never produces an unbounded retry loop or a
blank/unreadable messaging surface.

## Foundation files

```text
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
core/designsystem/build.gradle.kts
core/designsystem/gradle.lockfile
core/designsystem/src/main/AndroidManifest.xml
core/designsystem/src/main/kotlin/org/aurorasms/core/designsystem/AuroraMaterialProfile.kt
core/designsystem/src/main/kotlin/org/aurorasms/core/designsystem/AuroraMaterialTheme.kt
core/designsystem/src/main/kotlin/org/aurorasms/core/designsystem/AuroraAvatarShapes.kt
core/designsystem/src/test/kotlin/org/aurorasms/core/designsystem/AuroraMaterialProfileTest.kt
docs/PHASE_4_FILE_PLAN.md
docs/adr/0004-aurora-material-profile-engine.md
docs/DEPENDENCY_POLICY.md
docs/TEST_MATRIX.md
README.md
THIRD_PARTY_NOTICES.md
```

The physical navigation fixes discovered while validating the prior alpha are
reviewed separately from the appearance foundation:

```text
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRoot.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsRoute.kt
app/src/debug/kotlin/org/aurorasms/app/diagnostics/DiagnosticsScreen.kt
app/src/androidTest/kotlin/org/aurorasms/app/diagnostics/DiagnosticsScreenTest.kt
```

The physical provider-lifecycle fix is also reviewed separately from the
appearance foundation:

```text
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
app/src/main/kotlin/org/aurorasms/app/compose/ComposeMessageActivity.kt
app/src/main/kotlin/org/aurorasms/app/index/AppIndexCoordinator.kt
app/src/main/kotlin/org/aurorasms/app/index/ForegroundIndexReadGate.kt
app/src/test/kotlin/org/aurorasms/app/AppContainerLedgerPolicyTest.kt
app/src/test/kotlin/org/aurorasms/app/index/AppIndexCoordinatorTest.kt
app/src/test/kotlin/org/aurorasms/app/index/ForegroundIndexReadGateTest.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexSignalCoalescer.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizer.kt
core/index/src/test/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizerTest.kt
```

Its contract is intentionally narrow:

- new provider batches and head verification are admitted only while at least
  one Aurora messaging activity is started;
- one already-admitted bounded provider unit may finish after `onStop`, while
  the next unit is denied;
- background/content-observer signals remain content-free and durable without
  continuously issuing provider binder reads;
- the zero-to-one foreground transition emits `FOREGROUND_RESUME` without a
  false dirty mark, and a successful resume may clear only an unchanged durable
  ambiguous-signal ledger;
- background deferral preserves resumable generation state and releases the
  synchronizer mutex, so role-loss pause is not blocked behind the foreground
  gate; and
- both the main inbox activity and a cold external compose activity participate
  in the balanced started-activity count.

## Durable active-profile and Theme Studio slice

ADR 0005 bounds this slice to one durable active named profile and an app-owned
editor/preview. Its acceptance criteria are:

- migrate the separate Aurora state Room database from version 1 to version 2
  explicitly and non-destructively, preserving version-1 drafts and identity
  enforcement;
- store bounded named profiles with explicit stable codes, never enum ordinals
  or declaration names, and validate every stored field before rendering;
- keep the canonical default code-owned and resolve absent, deleted, corrupt,
  or unsupported durable appearance state to that usable fallback;
- create/update and activate on `Apply` in one transaction, with optimistic
  revisions and typed conflict/failure outcomes; require the expected revision
  for deletion as well, so a stale confirmation cannot delete a newer edit;
- keep editor selection and preview in memory and confined to the visible
  Appearance route; `Cancel`, system Back, and route disposal discard the draft
  and restore the durable active profile;
- restore only bounded editor data after recreation, never an in-flight write
  or confirmation as though it completed;
- keep Classic as the only implemented navigation presentation even though a
  stable navigation code round-trips through the profile model;
- keep reduced motion as a validated, round-tripping foundation token but do
  not expose its editor control until a production animation consumes it; and
- add no external dependency, permission, production manifest component,
  network path, artwork, media reference, decoder, Telephony read, index
  invalidation, or carrier behavior.

Implementation and verification evidence for this slice remain pending. The
corresponding `docs/TEST_MATRIX.md` rows stay unchecked until the exact host,
migration, emulator, governance, and privacy-safe device gates run.

The bounded slice is reviewed as this separate file set; the list is reconciled
against the final diff before evidence is recorded:

```text
app/build.gradle.kts
app/src/debug/AndroidManifest.xml
app/src/debug/kotlin/org/aurorasms/app/appearance/ThemeStudioTestActivity.kt
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/AppRoute.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRoot.kt
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
app/src/main/kotlin/org/aurorasms/app/appearance/AppearanceController.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ThemeStudioModels.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ThemeStudioRoute.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ThemeStudioScreen.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ThemeStudioStateSaver.kt
app/src/main/kotlin/org/aurorasms/app/compose/ComposeMessageActivity.kt
app/src/main/res/values/strings.xml
app/src/test/kotlin/org/aurorasms/app/AppRouteStackTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/AppearanceControllerTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/ThemeStudioEditorStateTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/ThemeStudioScreenTest.kt
core/designsystem/src/main/kotlin/org/aurorasms/core/designsystem/AuroraMaterialTheme.kt
core/designsystem/src/test/kotlin/org/aurorasms/core/designsystem/AuroraMaterialProfileTest.kt
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceProfile.kt
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceProfileDao.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceProfileEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceSelectionEnforcement.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceSelectionEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AuroraStateDatabase.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/RoomAppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseFactory.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseMigrations.kt
core/state/src/test/kotlin/org/aurorasms/core/state/AppearanceProfileContractTest.kt
core/state/src/test/kotlin/org/aurorasms/core/state/storage/AppearanceProfileEntityTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/AppearanceProfileRepositoryInstrumentedTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateDatabaseReopenTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateMigration1To2Test.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateSchemaV1Test.kt
core/state/schemas/org.aurorasms.core.state.storage.AuroraStateDatabase/2.json
core/state/schemas/org.aurorasms.core.state.storage.AuroraStateDatabase/2-triggers.sql
feature/conversations/build.gradle.kts
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/InboxScreen.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ThreadScreen.kt
feature/conversations/src/main/res/values/strings.xml
docs/adr/0005-durable-active-profile-theme-studio.md
docs/PHASE_4_FILE_PLAN.md
docs/PRODUCT_REQUIREMENTS.md
docs/DEPENDENCY_POLICY.md
docs/TEST_MATRIX.md
README.md
```

The test host exists only in the debug source set, is non-exported, and must be
absent from release and benchmark manifests/APKs. It is not a production
Appearance entry point.

## Follow-on slices

### 1. Scoped overrides

- Add eligible screen and conversation overrides, global-thread inheritance,
  focal points, dim values, and reset-to-inherited without duplicating the
  active-profile source of truth.
- Prove index recovery and provider changes do not alter any appearance scope.
- Keep missing/revoked assignments on the deterministic fallback chain.

### 2. Import and export

- Define a bounded declarative interchange format independently from Room
  entities and editor restoration state.
- Validate every field, bound names/counts/serialized size, reject newer
  schemas, executable content, duplicate/conflicting names, and unapproved
  media, and commit an accepted import atomically.
- Round-trip only fields whose consumers and migration behavior have passed
  their own acceptance gates.

### 3. Navigation and surface parity

- Implement Classic overflow first, then bottom bar, then adaptive rail on a
  large-screen target.
- Prove route, deep-link, process-recreation, search, and back parity before a
  navigation style may be selected.
- Keep hot list/search/thread surfaces opaque and static; no live blur is
  allowed.

### 4. Artwork and wallpaper assignments

Artwork work is blocked until the exact written rights record required by
`docs/ARTWORK_CATALOG.md` exists. General authorization to work on the app is
not a publishable copyright license.

After that gate, derivatives are generated from unchanged source hashes with a
committed manifest of source hash, derivative hash, dimensions, encoder
settings, and visual review. Originals remain outside Git unless source
redistribution is granted. Camera ICON and all private reference material are
never runtime assets.

Local static/GIF assignments require bounded metadata reads, hostile-media
limits, persisted URI handling, static chooser thumbnails, and exactly one
visible animation. Background, covered, display-off, battery-saver, and
reduced-motion states pause decoding. No decoder may fetch from a network.

### 5. Accessibility and performance gate

- Body text reaches 4.5:1 and important non-text affordances reach 3:1 on every
  eligible built-in/surface combination.
- All essential controls retain 48 dp targets in every density and geometry.
- Validate talkback order, labels, RTL, 200% font, short/tall phones,
  landscape, split screen, tablet/foldable, and reduced motion.
- Run the controlled physical-device journeys separately for Classic, bottom
  bar, static wallpaper, and GIF wallpaper; emulator timing is not evidence.
- Keep total built-in wallpaper contribution at or below 12 MiB unless a
  measured, owner-approved exception is recorded.

## Dependency boundary

The foundation reuses only exact coordinates already admitted for Phase 1:
Kotlin, coroutines, AndroidX Core/Activity/Lifecycle, the Compose BOM, Compose
UI/Foundation/Material 3, and JUnit. Direct declarations align the otherwise
minimal module to the already-verified graph; they do not add a new artifact or
version.

The durable active-profile slice reuses the exact Room/KSP graph already
admitted for the version-1 Aurora state database and the existing Compose/app
graph. The version-2 schema and Theme Studio add no coordinate or repository.
Room remains the sole durable appearance owner; DataStore is not added.

No image loader, GIF decoder, navigation library, DataStore, icon pack, font,
remote theme service, or media SDK is approved by this plan. Any later
coordinate requires the full dependency-admission record before source code
uses it.

## Validation sequence

```text
./gradlew :core:designsystem:testDebugUnitTest :core:designsystem:lintDebug --offline
./gradlew test lintDebug lintRelease assembleDebug assembleRelease --offline
./gradlew :app:lintBenchmark :app:assembleBenchmark :macrobenchmark:check :macrobenchmark:assembleBenchmark --offline
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest --offline
./gradlew verifyCleanRoom verifyPrivateAssets verifyDependencies verifyPermissions verifyApkContents --offline
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline
```

At the device gate, install the exact debug APK in place, copy the same bytes to
`/sdcard/Download/AuroraSMS-debug.apk`, compare SHA-256, verify package/role
state, and exercise only privacy-safe UI/resource-ID and redacted diagnostic
checks. No carrier message is sent by this slice.

## Stop conditions

Stop and update this plan before adding an external dependency, ingesting any
artwork without the exact rights record, persisting raw enum ordinals, changing
the state database without a migration test, letting preview escape the
Appearance route or survive its disposal, changing durable state before atomic
`Apply` or outside a confirmed revision-checked delete, creating a second
durable appearance owner, exposing a production appearance component, adding a
network permission/path, reading message/provider data from the design system,
running more than one animated decoder, allowing unbounded media/profile input,
or weakening any Phase 1-3 role, transport, index, privacy, or performance
invariant.
