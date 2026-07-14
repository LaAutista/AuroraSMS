# Phase 4 file-level plan

Status: AuroraMaterial foundation and foreground provider-read lifecycle
hardening implemented and verified on 2026-07-14; profile persistence, Theme
Studio, approved artwork, animated media, navigation variants, and the full
accessibility/device matrix remain gated follow-on work

## Outcome

Phase 4 gives AuroraSMS one original, versioned appearance system without
coupling appearance choices to Telephony, the rebuildable message index, or
carrier transport. The first slice introduces the immutable profile and token
boundary, preserves the Phase 3 appearance by default, and moves the app's
root Material theme into a dedicated `:core:designsystem` module.

The appearance slice deliberately does not ingest artwork, persist a
user-selected profile, decode a wallpaper/GIF, add a hidden destination screen,
or alter any SMS/MMS behavior. A separate physical-validation fix in this phase
bounds provider reconciliation by foreground lifecycle; the appearance
capabilities remain separate acceptance slices below.

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

The application layer owns selection and lifecycle. A later state repository
will expose one validated active profile and scoped overrides to Compose. That
repository may persist only Aurora-owned appearance state; changing appearance
must not query Telephony, dirty the index generation, reconstruct the current
thread route, or touch carrier transport.

The eventual resolution order is explicit and deterministic:

```text
conversation override
  -> eligible screen override
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

## Follow-on slices

### 1. Durable profile and override state

- Define stable storage codes rather than enum ordinals.
- Add an explicit migration to the separate Aurora-owned state database.
- Persist named profiles, the active profile, eligible screen overrides,
  conversation overrides, focal points, dim values, and reset-to-inherited.
- Validate every imported field, bound names/counts/serialized size, reject
  newer schemas and executable content, and use atomic import.
- Prove index recovery and provider changes do not alter appearance state.

DataStore remains unapproved. The existing durable-state boundary is preferred
unless a measured ADR demonstrates a need for another storage artifact.

### 2. Theme Studio

- Add one reachable Appearance destination with live, cancellable preview.
- Support Dark, AMOLED, Light, Dynamic, global hue, density, avatar mask,
  bubble geometry, navigation style, reduced motion, and high contrast.
- Provide Apply, Cancel, scoped Reset, profile naming, export, and import with
  deterministic process-recreation behavior.
- Keep all controls reachable at 200% font, in RTL, and above system/IME insets.

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
the state database without a migration test, exposing an appearance component,
adding a network permission/path, reading message/provider data from the design
system, running more than one animated decoder, allowing unbounded media/profile
input, or weakening any Phase 1-3 role, transport, index, privacy, or
performance invariant.
