<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AuroraSMS

AuroraSMS is an original, clean-room Android SMS/MMS application. Its privacy
baseline is local-only: the application does not request `INTERNET` or
`ACCESS_NETWORK_STATE`, has no accounts, ads, analytics, or trackers, and uses
Android's Telephony provider as the authority for messages.

## Current status

Phase 1 established the independently implemented default-SMS foundation:

- role eligibility and role-before-permission onboarding;
- the complete Android SMS-role manifest surface;
- bounded SMS provider and subscription-specific transport adapters;
- group-recipient policy that selects one MMS operation and never fans out SMS;
- privacy-aware message notifications and validated inline reply;
- debug-only, redacted local diagnostics;
- strict dependency locks, checksums, license inventory, SBOM, and clean-room,
  permission, and APK-content gates.

Phase 2 added the complete-history local data and search foundation:

- separate private Room databases for the rebuildable message index and
  durable Aurora-owned state, initially introduced at schema version 1;
- bounded, newest-first SMS/MMS metadata projection with durable checkpoints,
  verified reconciliation, and truthful partial coverage;
- safe FTS4 global and in-thread keyset search plus bounded exact-result
  anchors; and
- controlled index-only corruption recovery, typed storage failures, redacted
  diagnostics, and deterministic synthetic scale benchmarks.

Phase 3 adds the bounded presentation and its release-equivalent performance
harness:

- stable-key inbox, thread, search, exact-result jump, attachment-preview, and
  durable-draft presentation over capped keyset windows;
- bounded contact resolution, scroll-anchor preservation, and explicit
  incomplete/unavailable states;
- an R8-enabled benchmark target with signature-protected synthetic 20k-inbox,
  250k-thread, and 500k-search fixtures; and
- deterministic Baseline Profile capture plus startup, trace, frame, search,
  jump, and memory Macrobenchmarks.

Phase 4 now has a verified AuroraMaterial foundation:

- an isolated, immutable, schema-versioned appearance profile and semantic
  token engine;
- Dark, AMOLED, Light, and platform-dynamic palette paths, three row densities,
  three bubble geometries, reduced motion, and four original avatar masks;
- the existing Phase 3 colors and behavior preserved as the default app theme;
- no new external dependency, permission, network path, artwork, or media
  decode; and
- physical validation hardening that admits bounded provider reads only while
  an Aurora messaging activity is started, then resumes cleanly in foreground.

The appearance foundation itself does not change role, permission, provider,
index, draft, notification, or carrier-transport contracts. The lifecycle
hardening is a separate reliability fix discovered during physical validation.

The bounded active-profile/Theme Studio slice is now implemented and verified:

- an explicit non-destructive version-1-to-version-2 migration of the durable
  Aurora state database for bounded named profiles and one active selection;
- stable storage codes and a code-owned canonical fallback rather than enum
  ordinals or a duplicated default row;
- an app-owned, in-memory preview limited to the visible Appearance route, with
  durable application theme changes only after a successful atomic `Apply` or
  confirmed revision-checked deletion of the active named profile;
  and
- deterministic `Cancel`/Back behavior with no DataStore, dependency,
  permission, network, artwork, or media addition.

Version `0.4.1-phase4` passed the complete offline host, lint, release,
benchmark, clean-room, permission, APK-content, license/SBOM, API 36 emulator,
and privacy-safe Pixel 8 persistence gates recorded in `docs/TEST_MATRIX.md`.

The `0.4.2-phase4` durable scoped-profile-reference slice passed its host,
governance, emulator, and physical install/package/role/permission gates. Its
final real-root/modal acceptance extension uses a real `AuroraSmsRoot` driven by
deterministic synthetic services and proved that live Inbox/global and
exact-anchor Thread modal operations
retain their route and visible state, Search query, draft, and composer without
a modal-caused provider/index reload. `ActivityScenario` recreation rebuilt the
holder, issued exactly the one bounded anchor query allowed by ADR 0003, and
restored the exact modal target/selection, visible provider-message anchor and
offset, Search query, draft, and composer. Fresh same-thread re-entry also owns
a distinct route-state entry and exact jump, while popped/evicted entries remove
their saved state and retention stays bounded to `MAXIMUM_RETAINED_ROUTES`.
This is not a full process-death end-to-end claim, nor an exact-anchor claim for
a normal Inbox or unanchored Thread:

- an explicit non-destructive version-2-to-version-3 state migration stores
  references from eligible screens and verified conversations to existing named
  profiles, never copied profile tokens or raw participant addresses;
- a durable globally monotonic assignment-revision sequence prevents stale
  delete/recreate ABA writes, while target-specific Room flows keep observation
  bounded;
- Inbox and global conversation defaults are route-local modals, and Thread
  exposes conversation appearance only for a verified complete identity;
- the private restoration token is validated against the current target,
  mutation controls wait for both the durable profile snapshot and the exact
  target-assignment query after process load, and no production Activity is
  added; and
- the core fingerprint contract accepts 1 through 100 participants, while the
  current `ConversationSummary` preview safely limits UI assignment to verified
  sets of at most 8. Larger indexed conversations inherit global defaults until
  a bounded full-identity query is added.

The final frozen APK passed its intentionally Inbox-only physical focus gate on
an awake, unlocked Pixel 8. The gated real-`MainActivity` smoke used only
package/view IDs and accessibility window metadata to prove a distinct focused
scoped dialog, then Cancel returned to the same MainActivity/Inbox window without
opening a Thread or applying an assignment; aggregate appearance state remained
`0|0|0`. The exact 13,396,196-byte APK was copied to Download with matching
SHA-256, and a privacy-safe cold launch resumed MainActivity without an app
crash. Physical eligible-Thread modal coverage remains follow-on work.

Assignment-local focal/dim controls, import/export, navigation variants,
wallpapers, GIF lifecycle, full accessibility/performance coverage, and
approved canonical artwork remain Phase 4 follow-on gates. Artwork is still
blocked on the exact written publication/derivative/distribution terms in
`docs/ARTWORK_CATALOG.md`.

Phase 3 does not change the existing carrier MMS limitations. Earlier Phase
1/2 functional evidence covers a Pixel 8 on Android 16/API 36. Phase 3 profile
capture and functional journeys are verified with synthetic data on the API 36
emulator. A later owner-approved Pixel window also verified complete
real-provider reconciliation and privacy-safe inbox/thread/search reachability;
release-equivalent physical performance measurements remain pending. Emulator
timings are not product performance evidence.

End-to-end MMS is not yet claimed. Platform MMS staging and result handling are
present, but encoding/decoding remains disabled until an independently audited
codec is admitted and verified on physical carrier hardware. The first
composer also keeps its send control disabled until the complete, durable
compose flow is integrated. See `docs/adr/0001-mms-pdu-strategy.md` and the
phase gates in `docs/TEST_MATRIX.md`.

## Build

Prerequisites are JDK 17 or newer and an Android SDK containing platform 37
(installed here as `platforms;android-37.0`). Set `sdk.dir` in an ignored
`local.properties` file or export `ANDROID_SDK_ROOT`, then use only the checked
wrapper:

```bash
./gradlew assembleDebug --offline
```

The debug APK is produced at
`app/build/outputs/apk/debug/app-debug.apk`.

## Verify

```bash
./gradlew test lintDebug lintRelease assembleDebug assembleRelease --offline
./gradlew :app:lintBenchmark :app:assembleBenchmark \
  :macrobenchmark:check :macrobenchmark:assembleBenchmark --offline
./gradlew connectedDebugAndroidTest --offline
./gradlew verifyGovernance --offline
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline
```

Generated dependency reports are placed under `build/reports/`. Device and
carrier tests require a telephony-capable Android device, explicit approval of
the destination number, and awareness that SMS/MMS charges may apply. Tests
must never infer a destination from private reference material.

## Clean-room and licensing

Read `CLEAN_ROOM.md` before contributing. Private screenshots, PDFs, handoff
materials, and reference-app code or resources must not enter the repository,
tests, reports, or APK. Original source is licensed under
GPL-3.0-or-later; artwork has a separate rights gate described in
`docs/ARTWORK_CATALOG.md`.
