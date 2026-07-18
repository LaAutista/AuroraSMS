# Phase 4 file-level plan

Status: AuroraMaterial foundation, foreground provider-read lifecycle
hardening, and the bounded durable active named-profile/Theme Studio slice
implemented and verified on 2026-07-14; the durable scoped-profile-reference
foundation specified by ADR 0006 passed host, governance, emulator, and physical
install/package/role/permission gates plus real-root/modal acceptance on
2026-07-14; the final frozen APK also passed its deliberately Inbox-only physical
focus, exact copy/hash, and cold-launch gates on 2026-07-14; full process-death
end-to-end and physical eligible-Thread modal coverage are not claimed; a
follow-on exact-thread verified identity prerequisite for 1 through 100
participants passed focused verification plus the complete local
host/release/benchmark/governance/license/SBOM, API 36 connected,
frozen-artifact, deliberately Inbox-only Pixel, and source GitHub CI gates,
while physical 9-member Thread coverage remains pending;
ADR 0007's bounded managed private static wallpaper implementation for
`global_thread` and verified conversations landed at source commit
`c957995e74c7ba76ed25d1b7c4d23c05f42852be`, followed by acceptance hardening
at `975009f2b2c99cf389fb8020b270fd7c5bbf0bb2` and renderer isolation at
`e5aa4dfb1c695046c136d07e6b0c549e77e278ee`; its crash-safe managed-store and
quota protocol landed at `f0f1ff9` and passed focused host, API 26/API 36/Pixel
filesystem, complete connected, release/governance, license/SBOM, and exact APK
handoff gates. A narrow physical `global_thread` platform-Photo-Picker journey
at `111381dff31c46380eab969dea20234cba16fe08` now passes. Synthetic API 36
verified-conversation root rendering, focal/dim Apply, Activity recreation,
reset/identity fallback, stale-pixel clearing, and independent real-Room
close/reopen coverage pass at `b9350be354991e36039e8136095bc25ebd520d60`,
and an exact gated API 36 AOSP Photo Picker `GLOBAL_ACTION_BACK` cancellation
journey passes twice at `826a20dbc3e965da8f269dde1351cf4d76d28f6c`. A narrow
API 36 emulator host-`am force-stop` verified-conversation cold-target-process
journey passes twice at `73b5ffa2827ad2cd96b922ccf4a529b5b052529d`. An exact
gated API 26 AOSP DocumentsUI SAF-fallback, no-selection accessibility-Back
cancellation journey passes twice at
`37fd044df3b9b8933839b0f89f7018ec72b8ab1b`. Source commit `dd33737` adds a
separate real-`MainActivity` API 26 DocumentsUI selection lifecycle: canonical
URI-shape validation for the exact selected provider document with provider-open
and preview evidence, transient preview discarded by editor Cancel,
wallpaper Back, and Activity recreation, unavailable-document Apply rejection,
successful retry to one managed final/one revision, source-independent managed
load, and UI Reset now pass for one synthetic empty `global_thread` journey.
Source commit `65fc6552a877403523e499b457fdf015aaf6f753` adds a third
separately gated API 26 journey: direct pre-Apply conversation-route replacement
disposes the transient global selection, and one global stale-Apply conflict
preserves the newer managed winner while cleaning the unreferenced candidate.
Source commit `12939eea321e8eb6a9a173a82cab2dfd245b64e5` adds a fourth API
26 journey: from a staged pre-Apply SAF selection, the production notifier posts
one fixed synthetic message, a real touchscreen opens the AOSP notification
shade and taps its exact SystemUI row, and the production content `PendingIntent`
replaces the editor route in the same warm `MainActivity` without durable
wallpaper, notification-channel, or residual-notification mutation. Remaining
work at that point included a real carrier/provider/receiver/orchestrator
incoming-message trigger; provider-backed and verified-conversation identity;
cold/absent-task,
background, lockscreen, and process-death delivery; raw `PendingIntent` action/
extras/flags; API 27+, permission-denial, OEM, and physical-shade behavior;
reply/group/privacy/alert/new-channel variants; raw production picker-result
capture and temporary URI-grant revocation; readable source-byte/content
mutation, provider revocation/removal/replacement, cloud/blocking, in-flight
Apply, nonempty baselines, explicit picker Cancel, and the full lifecycle.
Broader picker/static-wallpaper UI acceptance, `global_thread` cold restart,
production-launcher/real-provider restart, and broader process-death recovery
also remain pending.
Source commit `f41dfd4f0552ed249b2fbda65ec2e3b164842c23` now closes one
narrow part of that list on the dedicated non-Play API 26 GSM AVD
`AuroraSMS_SMSRX_API26`: a single emulator-modem PDU traverses the protected
production `SMS_DELIVER` receiver, provider write, `COMPLETE` replay journal,
optional reply-target resolution, production private notification, exact
receiver-process death, surviving notification, real AOSP shade tap, and
distinct cold `MainActivity` process displaying the provider-backed verified
Thread. Carrier-network, physical/OEM shade and lockscreen, API 27+, permission-
denial, group/multiple-message, inline-reply execution, MMS, nonempty-provider,
broader process-death, and gold coverage remain open.
Source commit `ec3e10299953253b1330d9440a07df981ed9a1af` now closes one more narrow part: an API
33+ messaging-eligible Inbox/Thread notice and recovery action are implemented,
and the owned API 36 AOSP notification-denial journey passed twice from real
Settings `USER_SET` through final-dialog `USER_FIXED`, exact Settings recovery,
one documented modem SMS with independent test-APK raw-PDU capture, exact
provider/journal/timestamp identity, zero SBNs, cold readable Inbox/Thread, and
exact cleanup. The unchanged API 26 fixed raw-PDU journey also passed. API
33-35, physical/OEM/carrier/lockscreen, group/multiple-message, inline-reply,
MMS, broader acceptance, and gold coverage remain open.
Source commit `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0` now closes
one narrow part of the multiple-message gap: two sequential same-sender,
single-part SMS deliveries update one private conversation notification, which
survives process death and opens a distinct cold Thread containing both
messages in order. Multipart SMS, other-conversation grouping/summary behavior,
alert counts, API 27+, physical/OEM/carrier/lockscreen, inline reply, MMS,
broader acceptance, and gold coverage remain open.
Implementation commit `7c9d848` hardens durable incoming-message,
notification-generation, provider-status, role-loss, receiver-work, and inline-
reply recovery. Its dedicated API 26 AOSP
`inline-reply-permission-denied` journey passed twice independently from fresh
disposable overlays: one real SystemUI reply reached a distinct cold receiver,
only `SEND_SMS` was denied, preflight rejected transport before submission, one
durable consumed claim and one private generic failure alert remained, no
outgoing row appeared, the failure alert cold-routed to the exact Thread, and
exact cleanup passed. This closes only that denied-reply path; broader device,
carrier, API, lifecycle, and gold gates remain open.
A current follow-on durability slice replaces the previously listed provider-
insert/checkpoint and generic-failure-alert residuals with single-owner provider
staging and operation-scoped notification identity. Focused notification tests
passed 29/29 on both API 26 and API 36. The final-source real-provider contract
passed 1/1 on each API, and a fresh disposable API 26 SystemUI journey passed
with exact cleanup. Both full connected matrices and the complete
host/release/privacy/license aggregate are green. This work closes no wallpaper
phase or product-release row.
Inbox/other-screen treatment, built-in artwork, GIF/live-URI media,
import/export, navigation variants, and the full accessibility/performance and
carrier matrices remain gated follow-on work. AuroraSMS is not complete or gold.

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

The completed bounded slice extends the existing Aurora-owned Room state boundary
from version 1 to version 2 for named profiles and one active selection. It adds
an app-owned Theme Studio destination whose in-memory preview is confined to the
visible Appearance route until atomic `Apply`, Cancel, Back, or route disposal.
It still admits no provider/index/transport coupling, DataStore, override,
import/export, navigation variant, wallpaper, artwork, or decoder.

ADR 0006 defines the implemented bounded slice: durable references from an
eligible screen or verified conversation identity to an existing named profile.
It is a profile-resolution and route-preserving assignment foundation only. It
does not add wallpaper/media references, assignment-local focal/dim values,
artwork, pickers, decoders, GIF lifecycle, or final
accessibility/performance claims.

ADR 0007 defines the landed bounded slice. It adds a real
Thread-only static wallpaper consumer for `global_thread` and verified
conversation targets. The system picker URI is temporary process memory; Apply
sanitizes bounded 8-bit Huffman baseline sequential-DCT (`SOF0`) JPEG or the
reviewed bounded static-PNG subset into an app-private content-addressed
WebP under `noBackupFilesDir`, and Room retains only the managed token plus
assignment-local focal/dim values and revision. It adds no durable URI/grant,
media catalog, storage permission, external decoder, artwork, or animation.

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
screen/conversation profile references extend this boundary in ADR 0006 without
moving durable state into the design system.

The implemented resolution order is explicit and deterministic:

```text
conversation profile reference [ADR 0006; focused verification complete]
  -> global_thread profile reference [ADR 0006; focused verification complete]
  -> active named profile
  -> canonical built-in default
  -> accessible solid fallback

eligible screen profile reference [ADR 0006; focused verification complete]
  -> active named profile
  -> canonical built-in default
  -> accessible solid fallback
```

Missing, corrupt, unsupported, or unlicensed media always falls forward through
this chain. ADR 0007's managed static source has no durable external URI to
revoke; its missing/hash-mismatched private derivative falls from conversation
to `global_thread` to an accessible solid. Future live-URI media must define its
own revocation behavior. No failure produces an unbounded retry loop or a
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

Implementation commit `325f2ce` passed the exact host, migration, emulator,
governance, release-manifest, and privacy-safe Pixel gates. Commands, counts,
artifact hashes, and bounded outcomes are recorded in `docs/TEST_MATRIX.md`.

The bounded slice is reviewed as this separate file set; the list is reconciled
against the final diff before evidence is recorded:

```text
.gitignore
app/build.gradle.kts
app/src/androidTest/kotlin/org/aurorasms/app/MainActivityNotificationRouteTest.kt
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

`ScopedAppearanceTestActivity` is a second synthetic host beside the existing
Theme Studio test host. Both Activities are non-exported, debug-source-set only,
and required to be absent from release and benchmark manifests/APKs. The slice
adds no production Activity or navigation component.

## Implemented durable scoped-profile-reference foundation

ADR 0006 is the controlling decision for this bounded slice. Focused storage,
resolver, modal, host, governance, emulator, and physical install/copy/focus/
cold-launch evidence is recorded in `docs/TEST_MATRIX.md`; all acceptance rows
for this bounded slice are complete.

Acceptance criteria:

- migrate the Aurora state Room database explicitly and non-destructively from
  version 2 to version 3, preserving drafts, named profiles, the active
  selection, triggers, and all prior migrations;
- migrate the rebuildable Aurora index from version 2 to version 3 by preserving
  searchable rows but semantically invalidating every pre-v3 completeness claim:
  mark generations paused/pending, clear completion/failure markers, advance the
  signal sequence, and start a fresh scan rather than resume a stale checkpoint;
- store only references to existing named profiles, never copied profile-token
  snapshots, in separate screen and conversation assignment rows;
- admit only the exact stable screen codes `inbox`, `archive`, `settings`,
  `spam_blocked`, and `global_thread`; Search and Appearance/Theme Studio are
  not override scopes;
- expose Inbox appearance and `Conversation defaults` (`global_thread`) from
  Inbox More, and `Conversation appearance` for the verified current
  conversation from Thread More;
  Archive, Settings, and Spam & Blocked codes remain model vocabulary until
  those routes exist;
- identify a conversation with both its current positive provider thread ID and
  the exact versioned participant-set fingerprint from ADR 0006, computed only
  from verified-complete, non-truncated participants; store no raw address or
  participant-set serialization in appearance tables;
- treat the fingerprint as authoritative and the provider thread ID as a
  rebindable routing hint; incomplete, truncated, unavailable, or mismatched
  identity falls back to global-thread inheritance instead of resolving by
  thread ID alone; provider rows dropped as malformed taint the existing
  incomplete/truncated signal even when other valid addresses survive, while
  Android's intentional MMS insert-address placeholder is ignored only when a
  real bounded address set remains;
- expose only target-specific Room flows, never an unbounded collection of all
  conversation assignments, and keep provider IDs, fingerprints, repositories,
  and database handles outside `:core:designsystem`; treat the first Room
  row-or-null as authoritative and keep an explicit app loading state until a
  positive durable profile revision and the exact target query both arrive;
- require an expected assignment revision, or an explicit must-be-absent
  expectation for creation, on `Apply`; stage `Inherited` in the modal and
  delete the assignment only when Apply commits against its current revision;
  Cancel, Back, and dismissal make no durable change, and stale/failure outcomes
  make no partial change;
- allocate every actual assignment create/update revision from one durable,
  positive, globally monotonic Room singleton in the same transaction; never
  reuse an allocated revision after reset, cascade deletion, database reopen,
  or target recreation, so stale pre-deletion revisions cannot pass an ABA
  check; physically require singleton-zero insert, exact `old + 1` advance, and
  no delete, then fail closed when the sequence is missing, malformed, exhausted,
  or below the maximum live assignment revision;
- cascade a revision-checked named-profile deletion through referencing scope
  rows in the same transaction so each target immediately inherits its next
  valid fallback;
- resolve a conversation through conversation, `global_thread`, active named,
  canonical, and accessible-solid fallbacks; resolve an eligible screen through
  screen, active named, canonical, and accessible-solid fallbacks;
- keep Search on active named, canonical, and accessible-solid resolution; keep
  Theme Studio on its existing route-local transient preview followed by that
  same durable chain, with neither route receiving a scoped assignment;
- present assignment as a modal over the current Inbox or Thread route so,
  while the Activity/root composition remains alive, opening it, changing its
  selection, canceling, applying a named choice, applying an inherited reset,
  Back, and dismissal retain the same route stack, state-holder instance,
  in-memory paged window, visible scroll anchor, retained Search route/query,
  draft, and composer, with no provider/index presentation reload caused solely
  by the modal;
- on configuration or process restoration, permit holder reconstruction and
  the existing ADR 0003 bounded presentation re-query instead of serializing or
  claiming identity for an in-memory page; restore the logical route stack,
  exact modal target and bounded draft, retained Search query, and Thread
  draft/composer, and restore the same stable anchor when the route owns
  an exact saved Thread anchor; make no exact-anchor recreation claim for a
  normal Inbox or unanchored Thread until a separately reviewed bounded anchor
  API exists;
- give every fresh Thread entry, including same-thread re-entry, a unique
  route-state key and exact-jump state; remove SavedState for popped and evicted
  entries so retained route state never exceeds `MAXIMUM_RETAINED_ROUTES`;
- persist only a bounded private target token, baseline/selected profile IDs,
  and expected revision in `SavedState`; validate the target synchronously on
  restoration, discard a mismatched target draft, restore neither in-flight nor
  transient error state, and keep mutation controls disabled until both the
  first validated durable profile snapshot and the exact target-assignment
  query are loaded;
- keep `ConversationSummary` as a display preview of at most 8 members, and derive
  appearance identity separately with one exact-thread, exact-generation query
  limited to 101 rows (100 plus one overflow sentinel); expose only a valid,
  exact-distinct, value-sorted 1-through-100-member identity when coverage is
  verified complete, the entity and every row belong to that generation/thread,
  the declared and returned counts match, and truncation is false; reserve NFC
  normalization and fingerprint sorting for `AppearanceParticipantSetKey`; and
- make no appearance action query/write Telephony, mutate/rebuild the index,
  perform a carrier action, or change notifications; add no DataStore owner,
  production permission/component, network path, artwork, media reference,
  picker, decoder, assignment-local focal/dim field, or GIF behavior.

The implementation/review set is reconciled to the completed scoped foundation
and its real-root/modal acceptance extension below.

```text
app/build.gradle.kts
app/gradle.lockfile
app/src/androidTest/kotlin/org/aurorasms/app/AuroraSmsRootAcceptanceTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/MainActivityNotificationRouteTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/MainActivityScopedAppearancePhysicalSmokeTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/ScopedAppearanceDialogTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/diagnostics/DiagnosticsScreenTest.kt
app/src/debug/AndroidManifest.xml
app/src/debug/kotlin/org/aurorasms/app/AuroraSmsRootTestActivity.kt
app/src/debug/kotlin/org/aurorasms/app/appearance/ScopedAppearanceTestActivity.kt
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/AppRoute.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRoot.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRootServices.kt
app/src/main/kotlin/org/aurorasms/app/MainActivity.kt
app/src/main/kotlin/org/aurorasms/app/appearance/AppearanceController.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ScopedAppearanceDialog.kt
app/src/main/res/values/strings.xml
app/src/test/kotlin/org/aurorasms/app/AppRouteStackTest.kt
app/src/test/kotlin/org/aurorasms/app/NotificationRouteTest.kt
app/src/test/kotlin/org/aurorasms/app/ScopedAppearanceResolutionTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/AppearanceControllerTest.kt
core/index/schemas/org.aurorasms.core.index.storage.AuroraIndexDatabase/3.json
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexMigration2To3Test.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/IndexSchemaV1Test.kt
core/index/src/androidTest/kotlin/org/aurorasms/core/index/ConversationProjectionTest.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/ConversationRepository.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/RoomConversationRepository.kt
core/index/src/main/kotlin/org/aurorasms/core/index/conversation/VerifiedConversationIdentity.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/ConversationDao.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/AuroraIndexDatabase.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexDatabaseFactory.kt
core/index/src/main/kotlin/org/aurorasms/core/index/storage/IndexDatabaseMigrations.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexProjectionMapper.kt
core/index/src/main/kotlin/org/aurorasms/core/index/sync/IndexedProviderProjection.kt
core/index/src/test/kotlin/org/aurorasms/core/index/sync/IndexProjectionMapperTest.kt
core/index/src/test/kotlin/org/aurorasms/core/index/sync/TelephonyIndexSynchronizerTest.kt
core/index/src/test/kotlin/org/aurorasms/core/index/conversation/VerifiedConversationIdentityTest.kt
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceOverride.kt
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideDao.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideSequenceEnforcement.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideSequenceEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AuroraStateDatabase.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/RoomAppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseFactory.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseMigrations.kt
core/state/src/test/kotlin/org/aurorasms/core/state/AppearanceOverrideContractTest.kt
core/state/src/test/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideEntityTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/AppearanceProfileRepositoryInstrumentedTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateDatabaseReopenTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateSchemaV1Test.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateMigration2To3Test.kt
core/state/schemas/org.aurorasms.core.state.storage.AuroraStateDatabase/3.json
core/state/schemas/org.aurorasms.core.state.storage.AuroraStateDatabase/3-triggers.sql
core/telephony/src/main/kotlin/org/aurorasms/core/telephony/internal/AndroidMmsProviderDataSource.kt
core/telephony/src/test/kotlin/org/aurorasms/core/telephony/ProviderProjectionPolicyTest.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/InboxScreen.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ConversationUiModel.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ThreadStateHolder.kt
feature/conversations/src/main/kotlin/org/aurorasms/feature/conversations/ThreadScreen.kt
feature/conversations/src/main/res/values/strings.xml
feature/conversations/src/androidTest/kotlin/org/aurorasms/feature/conversations/ConversationUiStateTest.kt
feature/conversations/src/test/kotlin/org/aurorasms/feature/conversations/ThreadStateHolderIdentityTest.kt
gradle/libs.versions.toml
docs/adr/0006-durable-scoped-profile-references.md
docs/PHASE_4_FILE_PLAN.md
docs/PRODUCT_REQUIREMENTS.md
docs/DEPENDENCY_POLICY.md
docs/TEST_MATRIX.md
docs/THREAT_MODEL.md
README.md
```

## Implemented follow-on prerequisite: verified exact-thread identity

The original ADR 0006 slice deliberately stopped at the eight-member
`ConversationSummary` preview. This separately reviewed follow-on fulfills its
bounded full-identity prerequisite without changing the preview, state schema,
Telephony contract, assignment schema, or fingerprint algorithm:

- one exact provider-thread/generation Room query orders participant rows and is
  limited to `MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS + 1` (101), so the extra
  row is an overflow sentinel rather than an unbounded read;
- the repository emits an identity only when coverage is verified complete, the
  requested thread and generation match the conversation entity and every row,
  `participantsTruncated` is false, the declared count is in 1 through 100, the
  returned count matches it exactly, and addresses are valid, exact-distinct,
  and sorted by value. NFC canonicalization/deduplication happens later during
  `AppearanceParticipantSetKey` derivation;
- `ConversationSummary.participants` remains an at-most-8 display/contact
  preview. The exact 1-through-100 participant list is projected from existing
  private rebuildable `indexed_conversation_participants.address` rows into an
  ephemeral, redacted `VerifiedConversationIdentity`. The derived identity adds
  no appearance/state persistence and is not placed in `SavedState`; the app
  immediately derives the existing one-way private participant-set key from it;
- `ThreadStateHolder` clears the identity synchronously on content-free
  invalidation before starting the bounded re-query. The app additionally
  requires complete coverage plus exact route-thread and generation matches;
  missing, stale, oversized, truncated, count-mismatched, or route-mismatched
  identity removes conversation appearance availability and clears an open
  editor target. A timeline page may initially publish Ready while its delayed
  exact-identity lookup is explicitly unresolved: appearance remains unavailable
  and the restored target is retained. Resolved-null or terminal failure clears
  that target, while invalidation publishes resolved-null before re-query so
  stale authority is revoked immediately; and
- the implementation adds one private index-database read but no Telephony
  provider read, index mutation, schema migration, permission, component,
  dependency, network path, raw-address storage beyond the existing rebuildable
  index, or carrier action.

Focused verification covers the valid, exact-distinct, value-sorted, redacted
1-through-100 model and fail-closed projection, the Room nine-member exact
identity beyond an eight-row preview and its pending-generation revocation,
holder delivery/invalidation, app resolver route/generation/coverage checks,
editor target loss, and a real-root nine-member modal-open/invalidation-dismiss
journey. Those focused host, Room,
app compile/lint, three-test holder, and three-test real-root emulator runs
passed. The complete host/release/benchmark/governance/license gate then passed
886 tasks (66 executed) in 1m05s; the separate `cyclonedxBom` run passed with
all 15 tasks up-to-date. The full API 36 connected matrix passed 455 tasks
(13 executed, 442 up-to-date) in 57s, including app 32, benchmark 4, core index
31, notifications 3, state 29, telephony 15, and feature conversations 3 tests.
The final debug APK is 13,212,416 bytes with SHA-256
`39a07d72b7c58b91a11b152458ba971161b1edd98883f68df4fdbc6ab724235d`.
It installed successfully on Pixel 8 serial `192.168.68.51:38677`, and the copy
at `/sdcard/Download/AuroraSMS-debug.apk` has the same size and hash. The
targeted `MainActivityScopedAppearancePhysicalSmokeTest` passed 1/1 in a
17-second, 197-task build. The installed app was the sole Aurora package, its
default-SMS role and
`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, and
`POST_NOTIFICATIONS` grants were restored, and a cold MainActivity launch
reported `Status: ok`, state `COLD`, `TotalTime: 1112`, `WaitTime: 1114`, PID
4191, and `topResumed` MainActivity. The error-only PID log contained only the
benign ashmem-pinning deprecation. This content-free Inbox smoke is not physical
9-member Thread coverage. Source commit
`83db9aa0f02cef44644f53d0bb149abe459dc20b` is committed and pushed on
`origin/main`; GitHub Verify run `29380854714` passed its 10m59s build job with
all project steps green.

## ADR 0007 landed slice and remaining follow-ons

### 1. ADR 0007 managed private static Thread wallpaper — bounded implementation landed; acceptance pending

- Add direct wallpaper assignments only for `global_thread` and an ADR
  0006-verified conversation. The first resolver is conversation managed WebP
  -> `global_thread` managed WebP -> accessible solid; it does not claim an
  active-theme or canonical-artwork wallpaper.
- Use the system Photo Picker/SAF fallback only as a temporary `content:` read
  capability. Never persist the URI or take a persistable grant. Explicit Apply
  revalidates and sanitizes 8-bit Huffman baseline sequential-DCT (`SOF0`) JPEG
  with at most four components and complete scan coverage, or CRC-valid non-APNG
  PNG with at most 4,096 chunks, no `iCCP`/`zTXt`/`iTXt` ancillary chunks, and a
  complete zlib scanline stream, to a content-addressed
  private WebP under `noBackupFilesDir/appearance/wallpapers/`.
- Enforce the accepted source bounds (16 MiB, 8,192-pixel edge, 40,000,000
  pixels), output bounds (2,048-pixel edge, 4,194,304 pixels, 16-MiB decoded
  allocation), 8-MiB derivative bound, one wallpaper decode, and shared
  two-decode app media gate. Reject progressive, extended sequential,
  arithmetic, lossless, differential/hierarchical, and non-8-bit JPEG; GIF;
  every input WebP; HEIF; AVIF; APNG; and malformed/partial media.
- Add assignment-local focal X/Y 0..1,000 and dim 350..900 permill with real
  Thread renderer and live-preview consumers, revision-checked Apply/reset, and
  no dead stored controls.
- Keep assignment rows authoritative; add no media catalog table. A bounded
  distinct-token snapshot supports deduplication, last-reference deletion, and
  startup cleanup. Admit at most 128 distinct assigned files and 256 MiB total.
  A serialized replacement may stage exactly one unassigned sanitized candidate
  of at most 8 MiB before the Room CAS, so the private directory may momentarily
  reach 129 files/264 MiB. This is atomic-staging headroom, not a raised durable
  quota; rejection/cancellation removes the candidate, and healthy startup GC
  removes one left orphaned by a process death.
- Guard the app-private single-process namespace with one process-wide mutex;
  durably create parents, create the pending leaf with `O_EXCL|O_NOFOLLOW`,
  write/flush/sync and verify exact content/device/inode/single-link identity,
  immediately recheck final absence, atomically rename in the same directory,
  and reverify. Deliver the candidate cleanup lease synchronously before a
  suspendable checkpoint and sync the leaf directory before import return/Room
  CAS.
- Delete a candidate or replaced file only after fresh bounded Room authority
  proves it unreferenced. Reconcile with a validation-only first pass and a
  deletion second pass; any unsafe entry or unavailable/corrupt/over-limit
  authority causes zero partial deletion.
- Prove missing/corrupt/hash-mismatched managed media advances through
  conversation -> global-thread -> solid without changing profile references,
  another wallpaper assignment, provider/index state, or the current route.
- Keep Inbox and all other screen wallpaper controls/renderers absent until
  their separate surface/contrast treatment is accepted.

The implemented boundary is:

```text
docs/adr/0007-managed-private-static-thread-wallpapers.md
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceWallpaper.kt
core/state/src/main/kotlin/org/aurorasms/core/state/AppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceWallpaperEntity.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AppearanceOverrideDao.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/RoomAppearanceProfileRepository.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/AuroraStateDatabase.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseFactory.kt
core/state/src/main/kotlin/org/aurorasms/core/state/storage/StateDatabaseMigrations.kt
core/state/src/test/kotlin/org/aurorasms/core/state/AppearanceWallpaperContractTest.kt
core/state/src/test/kotlin/org/aurorasms/core/state/storage/AppearanceWallpaperEntityTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/AppearanceWallpaperRepositoryInstrumentedTest.kt
core/state/src/androidTest/kotlin/org/aurorasms/core/state/StateMigration3To4Test.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperImportPolicy.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/ManagedWallpaperFilePolicy.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/ManagedWallpaperStore.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperController.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperQuotaPolicy.kt
app/src/main/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperSurface.kt
app/src/main/kotlin/org/aurorasms/app/preview/BoundedMediaDecodeGate.kt
app/src/main/kotlin/org/aurorasms/app/preview/AndroidBoundedPreviewLoader.kt
app/src/main/kotlin/org/aurorasms/app/AppContainer.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRootServices.kt
app/src/main/kotlin/org/aurorasms/app/AuroraSmsRoot.kt
app/src/main/kotlin/org/aurorasms/app/appearance/ScopedAppearanceDialog.kt
app/src/test/kotlin/org/aurorasms/app/appearance/wallpaper/ManagedWallpaperFilePolicyTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperControllerTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperImportPolicyTest.kt
app/src/test/kotlin/org/aurorasms/app/appearance/wallpaper/WallpaperQuotaPolicyTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/wallpaper/ManagedWallpaperCrashProtocolTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/wallpaper/ManagedWallpaperStoreTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/appearance/ScopedAppearanceDialogTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/AuroraSmsRootAcceptanceTest.kt
app/src/androidTest/AndroidManifest.xml
app/src/androidTest/java/org/aurorasms/app/appearance/wallpaper/WallpaperTestContentProvider.java
app/src/androidTest/java/org/aurorasms/app/appearance/wallpaper/WallpaperTestDocumentsProvider.java
app/src/androidTest/kotlin/org/aurorasms/app/appearance/wallpaper/MainActivityStaticWallpaperSafFallbackSmokeTest.kt
app/src/androidTest/kotlin/org/aurorasms/app/DefaultSmsManifestContractTest.kt
scripts/run-emulator-wallpaper-cold-restart-smoke.sh
scripts/run-emulator-wallpaper-saf-cancellation-smoke.sh
scripts/run-emulator-wallpaper-saf-selection-smoke.sh
```

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

ADR 0007 local static Thread assignments use a temporary picker capability and
managed private import, not persisted URI handling. GIF and any future live-URI
assignment remain separate: they require bounded metadata/frame/duration reads,
their own URI/grant decision if applicable, static chooser thumbnails, and
exactly one visible animation. Background, covered, display-off,
battery-saver, and reduced-motion states pause that future animation. No decoder
may fetch from a network.

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

The implemented scoped-profile-reference foundation also reuses only that
admitted Room/KSP, Kotlin, coroutine, Compose, and platform Java cryptography
graph. A version-3 schema, SHA-256 participant fingerprint, target-specific
flow, and route-preserving modal require no new coordinate, repository,
permission, production component, initializer, native library, or network path.
The scoped dialog's direct Android-test declaration of
`androidx.test.espresso:espresso-core:3.5.0` promotes the already-resolved
transitive test artifact to the compile classpath; it introduces no new resolved
artifact/version, checksum, license, or production coordinate. The lock,
resolved-graph, license/SBOM, manifest, and packaged-output checks passed.

ADR 0007 approves only the small platform-backed static importer/renderer
described above, using the already admitted Activity/Compose/coroutine/Room
graph, Android platform bitmap/image/color-space/WebP APIs, SHA-256, and an
original bounded parser for baseline-JPEG entropy completeness, static-PNG
structure/zlib completeness, and JPEG APP1/PNG `eXIf` TIFF orientation fields.
It does not use `android.media.ExifInterface`. No external image loader, GIF
decoder, navigation library, DataStore, icon pack, font, remote theme service,
or media SDK is approved by this plan. Any later coordinate requires the full
dependency-admission record before source code uses it.

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
checks. The frozen `0.4.2-phase4` APK passed install/package/role/permission
inspection on an awake, unlocked Pixel 8. Its gated real-`MainActivity` smoke
used only package/view IDs and accessibility window metadata, passed one test in
2.098 seconds, proved a distinct focused Inbox scoped dialog, and proved that
Cancel returned to the same MainActivity/Inbox window without opening a Thread
or applying an assignment. Aggregate appearance state remained `0|0|0`. The
host and Download copies were both 13,396,196 bytes with SHA-256
`d26a6a1c515d941ac38bb6b8ea1649d27f2ee3f9efc7f815ff74dfcebf164c03`.
A cold MainActivity launch completed with `Status: ok`, `LaunchState: COLD`,
`TotalTime: 1081`, `WaitTime: 1083`, the expected resumed Activity, and a live
PID; its PID-only error log contained only Android's ashmem-pinning deprecation
and no app crash. Real-root synthetic-service instrumentation separately proved
live route/visible-state preservation with zero modal provider/index reload,
exact-anchor restoration with one allowed recreation query, and unique state for
fresh same-thread re-entry. Popped/evicted route state is removed and retention
remains bounded to `MAXIMUM_RETAINED_ROUTES`. Full process-death end-to-end and
physical eligible-Thread modal coverage are not claimed. No carrier message is
sent by this slice.

For crash/quota source commit `f0f1ff9`, focused host tests passed 32/32
(controller 20, file policy 7, quota 5). `ManagedWallpaperStoreTest` 15 plus
`ManagedWallpaperCrashProtocolTest` 14 passed 29/29 on API 26 and API 36
emulators and on the API 36 Pixel 8. The complete API 36 connected matrix passed
in 1m16s with 71 app tests, one intentional physical-only skip, zero failures,
and all benchmark/core/feature suites green. The 886-task offline
release/governance gate passed in 1m24s and the separate license/SBOM gate in
9s. The 13,993,426-byte debug APK installed and cold-launched on the Pixel; its
Download copy matched SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
The physical 29-test run exercised only non-UI app-private filesystem behavior;
it is not Photo Picker or static-wallpaper UI-journey evidence.

The later dedicated narrow runner passed 1/1 in 7.107s on Pixel 8 Android
16/API 36 serial `192.168.68.55:43069` at source commit
`111381dff31c46380eab969dea20234cba16fe08`: Cancel and wallpaper Back preserved
the empty baseline, Apply created one `global_thread` assignment and one
conforming managed file, Reset restored the baseline, and the exact synthetic
Downloads fixture was deleted. Post-run database/file counts were `0/0` and
`0`; the test package was absent; and the target, SMS role, and all seven grants
were preserved. The local, installed, and Downloads APKs were each 13,993,426
bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
This proves only the narrow platform-picker `global_thread` journey, not the
SAF/system-picker cancel path, performance, complete lifecycle, or gold
readiness.

Source commit `b9350be354991e36039e8136095bc25ebd520d60` adds synthetic API 36
verified-conversation evidence. The real root acceptance class passed 5/5 and
uses timeline pixel captures to prove conversation-over-global precedence,
applied dim, equivalent pixels after Activity recreation, reset-to-global
pixels, and identity-loss fallback without cross-target mutation. Editor and
repository assertions prove focal/dim values survive Apply plus recreation.
Wallpaper Apply/reset add no presentation-data loads; Activity recreation
performs the one expected anchor reload. Focused managed-surface and real-Room
close/reopen/reset tests each passed 1/1; the latter proves exact global and
conversation assignments survive database close and reopen and reset
independently. The complete API 36 connected matrix then passed in 1m15s with
456 Gradle tasks: app 75 tests with
two intentional physical-only skips, benchmark 3 with one scale-opt-in skip,
index 31, notifications 3, state 43, telephony 15, and feature-conversations 4.
The complete 886-task offline host/release/governance/license gate passed in
15s, CycloneDX passed in 7s, and the debug APK remained 13,993,426 bytes with
SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
Activity recreation and Room reopen are deliberately separate evidence: this
does not prove a cold-process root renderer plus managed-file restart, a
physical verified-conversation journey, SAF/system-picker cancellation,
carrier behavior, the complete lifecycle, or gold readiness.

Source commit `73b5ffa2827ad2cd96b922ccf4a529b5b052529d` adds a separate,
explicitly gated API 36 ranchu/goldfish emulator journey for one synthetic
verified-conversation assignment. Its host runner requires an already-installed
target APK whose hash matches the local build, captures the SMS-role-holder
string and seven permission states, and installs only the instrumentation APK.
Prepare uses the production `AppContainer`, Room repository,
`WallpaperController`, and managed store. Before Apply it derives the exact
expected media identity in an isolated cache-backed store and commits a
fail-closed recovery journal; after Apply it records the exact assignment,
revision, focal/dim values, post-reconciliation managed-file baseline, grant
count, process identity, and canonical pending fixture.

The runner then starts normal `MainActivity` only to expose a live prepared
target process. It observes ordinary startup remove the initial pending fixture,
recreates the same canonical pending-file path without changing the PID, and
requires host `am force-stop` to remove the exact live PID. Verification in a
fresh target process requires a different PID and later process start, the exact
durable assignment, pending-file absence, referenced-final retention and
validated load, the exact baseline-plus-referenced-final filename set,
unchanged grant count, and expected dimmed pixels from the real `AuroraSmsRoot`
Thread surface hosted by the debug-only test activity with synthetic
conversation/index services. A further fresh process performs
revision-qualified reset and restores the filename baseline. The two passing
runs force-stopped prepared target PIDs 16995 and 17370; their instrumentation
prepare/verify/cleanup times were 0.114s/2.773s/0.038s and
0.122s/2.716s/0.037s, respectively. Both runs preserved the target APK,
SMS-role-holder string, and recorded permission states.

The follow-on API 36 connected XML reports total 176 tests, zero failures, and
five intentional opt-in skips: app 77/4 skipped, benchmark 3/1 skipped, index
31, notifications 3, state 43, telephony 15, and feature-conversations 4. The
complete 886-task offline host/release/governance/license gate passed in 16s,
and the 15-task CycloneDX gate passed in 7s. The production debug APK remains
13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.

This closes only the named API 36 host-force-stop synthetic-conversation gap.
It is not a production `MainActivity` launcher-renderer restart, a real
provider-backed SMS conversation, UI Apply/Reset, Photo Picker/SAF,
source-revocation, `global_thread`, physical/OEM/performance, or
low-memory/background/in-flight process-death result. Force-stop occurs after
import, Room commit, managed-file publication, and checkpoint commit; the solid
fixture proves dimmed pixels while focal position is metadata-only; grant
preservation is count-only; and the provider remains installed. The broader
compound rows and gold-readiness gate stay open.

Source commit `826a20dbc3e965da8f269dde1351cf4d76d28f6c` adds a separately gated
API 36 AOSP Photo Picker cancellation journey using the accessibility global
Back action. With AuroraSMS installed under its normal emulator SMS-role
precondition, the exact
`realGlobalThreadSystemPickerCancellationRestoresEditorAndBaseline` method passed
independently twice in 12s and 11s. It waited for
`StateStorageStatus.Ready`, published after the startup reconciliation attempt,
opened the real `MainActivity` global-thread wallpaper editor, focused the exact
MediaProvider package, and invoked `GLOBAL_ACTION_BACK` without creating a
synthetic picker fixture or inspecting picker content. The wallpaper dialog
returned usable with Pick enabled, Apply disabled, no loading/error state, and
the exact global assignment object, managed-file name set, and persisted URI
grant count unchanged. Failure cleanup attempts to dismiss a still-focused
picker, and the physical runner now targets its original exact method so the two
instrumentation gates cannot affect each other's pass/skip accounting.

The complete follow-on API 36 connected matrix passed in 1m19s with 456 Gradle
tasks: app 76 tests with three intentional gated skips, benchmark 3 with one
scale-opt-in skip, index 31, notifications 3, state 43, telephony 15, and
feature-conversations 4. The 886-task offline
host/release/governance/license gate passed in 17s, CycloneDX passed in 7s, and
the debug APK remained 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.
This proves only cancellation through the accessibility global Back action in
the API 36 AOSP Photo Picker. It does not prove SAF fallback/cancellation, OEM
picker behavior, an explicit picker Cancel control, selected/staged-candidate
cancellation, URI or file-byte identity after a successful selection,
cold-process behavior, the complete lifecycle, or gold readiness. The compound
picker/SAF gate remains open.

Source commit `37fd044df3b9b8933839b0f89f7018ec72b8ab1b` adds a
separately gated API 26 ranchu/goldfish AOSP DocumentsUI SAF-fallback
no-selection cancellation journey. The API 26 app-module connected XML records
76 tests, zero failures, zero errors, and three intentional gated skips in
35.498s. The exact standalone runner method then passed twice in 2.751s and
2.754s.

The smoke separately constructs an AndroidX `PickVisualMedia(ImageOnly)`
contract instance and proves that this exact API 26 environment resolves it to
`ACTION_OPEN_DOCUMENT`, MIME type `image/*`, and AOSP DocumentsUI. It then opens
the real `MainActivity` global-thread wallpaper editor and independently proves
that the production Pick action focuses DocumentsUI, but it does not intercept
or inspect the outgoing production intent. It selects no document, does not
traverse DocumentsUI content, and cancels with the accessibility global Back
action. On return, Pick is enabled, Apply is disabled, no loading/error state is
present, and the editor remains usable. The exact global assignment object,
immediate managed-filename set, and persisted URI-grant identity/read/write/
persisted-time set remain unchanged both at the assertion point and final
cleanup.

The host runner holds a nonblocking per-device lock, refuses an active or
preinstalled instrumentation package, and installs then removes only that test
APK. It requires the installed target APK to match the local build and AuroraSMS
to already be the legacy default SMS app. Both passes preserved that exact
target, default-SMS state, and all seven captured permission states:
`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`,
`READ_PHONE_STATE`, and `READ_CONTACTS`.

This closes only the API 26 AOSP DocumentsUI SAF-fallback, no-selection
accessibility-Back cancellation case. It does not prove the outgoing production
intent itself, a document selection or returned URI, URI length/authority or
nonpersistence after a result, wallpaper preview/Apply/reset/import/rendering,
staged-candidate cancellation, source loss or revocation, configuration/
Activity/process-loss recovery, file bytes/inodes/timestamps/metadata, a
verified-conversation target, API 27-32, OEM DocumentsUI/pickers, an explicit
Cancel control, broader accessibility or form-factor coverage, performance,
carrier behavior, or the complete lifecycle. The corresponding compound gates
remain open, and AuroraSMS is not complete or gold.

### ADR 0007 API 26 AOSP DocumentsUI SAF selection-lifecycle partial evidence — 2026-07-16

Source commit `dd33737` adds the separately gated API 26 AOSP DocumentsUI SAF
selection-lifecycle journey and
`scripts/run-emulator-wallpaper-saf-selection-smoke.sh`. The preservation-safe
runner shares the cancellation runner's per-device lock; requires the exact API
26 ranchu/goldfish emulator, matching installed target, default-SMS state, and
captured seven-permission baseline; refuses an existing test package/process;
installs and removes only the test APK; and strictly requires one status 0, one
custom status 42 with `auroraSafSelectionResult=pass`, final code -1, and
`OK (1 test)` within 180 seconds.

The androidTest-only provider contributes one exact local root and one read-only
40x20 PNG. The real `MainActivity` global-thread editor and production AndroidX
contract traverse its API 26 `ACTION_OPEN_DOCUMENT` fallback. The journey acts
only on that exact root/document, obtains provider-open and preview evidence,
and validates the expected canonical `content:` URI shape with a non-empty
authority and at most 4,096 UTF-8 bytes. Exact empty assignment,
read-only revision sequence, persisted URI-grant identity/read/write/time set,
and no-follow managed-file name/device/inode/link-count/size/mtime/SHA-256 ledger
form the baseline.

Independent selections followed by editor Cancel, wallpaper Back, and Activity
recreation discard the preview and preserve every durable baseline. Making only
the test document unavailable leaves its provider installed; Apply reopens and
rejects it without assignment/file/grant/revision mutation. Re-enabling the same
document and retrying creates exactly one conforming managed final and advances
the revision exactly once. The production controller then loads the expected
40x20 managed raster while the source is unavailable. UI Reset restores the
empty assignment, file, and persisted-grant baselines. The consumed revision
correctly remains baseline plus one because Reset neither allocates nor rolls
back a revision.

The focused selection journey passed in 13.054s and 13.087s; its final
post-review pass took 12.952s. The independent no-selection cancellation runner
passed in 2.65s. A later module-by-module XML/source-delta audit corrected the
API 26 aggregate bookkeeping to 176 tests with five gated skips, rather than the
previously recorded 181/four; it had zero failures across 456 tasks in 1m53s.
The current API 36
aggregate completed 176 tests with five skips and zero failures across 456 tasks
in 1m23s.
The 886-task host/release/privacy gate passed in 19s, CycloneDX's 15 tasks passed
in 8s, and the production debug APK for this source is 13,993,426 bytes with
SHA-256 `5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This is one synthetic empty-`global_thread` API 26 AOSP journey. It does not
capture the raw production intent/result, prove temporary URI-grant revocation,
uninstall/remove or replace the provider, exercise readable source-byte/content
mutation or cloud/blocking,
target loss or stale CAS, configuration beyond Activity recreation, background/
low-memory/in-flight process death, the actual Thread surface or a verified
real-provider conversation, API 27-32, physical SAF-fallback/selection behavior,
broader OEM behavior beyond the recorded Pixel 8 Photo Picker journey,
performance, an explicit picker Cancel control, cold restart, complete lifecycle, or gold
readiness. Only test-document availability changes; the provider remains
installed through Apply and Reset. All corresponding compound gates stay open,
and AuroraSMS remains incomplete and not gold.

### ADR 0007 API 26 AOSP DocumentsUI pre-Apply route-disposal and global stale-Apply partial evidence — 2026-07-16

Source commit `65fc6552a877403523e499b457fdf015aaf6f753` extends the
preservation-safe selection runner with `--journey stale-apply`; omitting the
option retains the prior selection lifecycle. The new mode keeps the exact API
26 emulator, matching target, default-SMS, seven-permission, shared-lock,
test-package isolation, 180-second timeout, and cleanup checks. It strictly
requires one status 0, custom status 43 with
`auroraSafStaleApplyResult=pass`, final code -1, and `OK (1 test)`.

The real `MainActivity` global editor selects the exact synthetic DocumentsUI
document from an empty assignment. Before Apply, the test directly invokes the
Activity new-intent path with the production open-conversation action and a
fixed synthetic ID. The Thread route dismisses both editors. Back at Inbox, a
new global editor has disabled Apply and does not reopen the source; exact
assignment, revision, persisted-grant identity/read/write/time, and no-follow
managed-file ledger baselines remain unchanged.

After another real selection, a controlled production-controller write commits
one newer global winner. The stale editor reopens its source on UI Apply,
surfaces the exact stale-assignment error, and cannot replace the winner or
change its revision/file/persisted-grant state. The winner remains loadable and
the stale candidate is absent. UI Reset removes only the controlled winner and
restores the empty assignment/file/persisted-grant baseline while retaining the
one consumed revision. Cleanup recovery targets only the fixed scope/revision/
dim/focal values and single conforming managed final. A host test separately
proves the late repository `StaleWrite`, second authoritative reference read,
and unreferenced created-candidate deletion in exact call order.

The corrected focused journey passed in 8.597s and 8.513s; the final revision-
hardened pass took 8.667s. Selection and cancellation regressions passed in
13.012s and 2.692s. Final API 26/API 36 aggregates were `BUILD SUCCESSFUL` in
1m49s and 1m23s across 456 tasks each. Their JUnit XML reports 177 tests/six
intentional skips and 176 tests/five skips, with zero failures/errors. The 886-
task offline host/lint/release/privacy/license gate passed in 21s with 36
executed and 850 up-to-date tasks. The 15-task CycloneDX 1.6 gate passed in 8s
with 441 components and 442 dependency nodes. The unchanged production debug
APK is 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This is direct pre-Apply `onNewIntent` route disposal and one synthetic global
assignment-CAS conflict, not an end-to-end system notification/PendingIntent
launch, in-flight Apply cancellation, or verified-conversation target-identity
test. Persisted-grant snapshots do not prove temporary read-grant cleanup. Raw
intent/result capture, readable source-byte/content mutation, provider
revocation/removal/replacement, cloud/blocking behavior, API 27-32,
physical/OEM SAF, explicit picker Cancel,
broader process loss, production-launcher/real-provider Thread rendering,
accessibility, form factor, performance, artwork, carrier, compound lifecycle,
and gold readiness remain
open. Only synthetic emulator fixtures participated; the compound and unrelated
broad gates stay open, and AuroraSMS remains incomplete and not gold.

### ADR 0007 API 26 AOSP system-notification content-PendingIntent pre-Apply route-disposal partial evidence — 2026-07-16

Source commit `12939eea321e8eb6a9a173a82cab2dfd245b64e5` extends the
preservation-safe selection runner with
`--journey notification-pending-intent`; omitting the option still selects the
original selection lifecycle. The mode requires an awake, unlocked, exact API
26 ranchu/goldfish AOSP emulator, matching installed target, legacy default-SMS
state, captured seven-permission baseline with owner-granted `READ_SMS`, shared
per-device lock, absent test package/process, and bounded execution. It strictly
requires one status 0, custom status 44 with
`auroraSafNotificationPendingIntentResult=pass`, final code -1, and
`OK (1 test)`.

The test initializes the production notification channels before capturing the
complete owned-channel snapshot. Starting from an exact empty `global_thread`
assignment and a captured active-notification baseline with the reserved
identity absent, the real `MainActivity` global editor and production AndroidX
SAF fallback select the exact synthetic local PNG in DocumentsUI. No
Apply occurs. The production `messageNotifier` posts a fixed synthetic
`IncomingMessageNotification`, and the exact active system notification record
is fingerprinted by package, UID, tag, ID, message channel, category, private
visibility, timestamp, clearable/auto-cancel flags, no actions, Aurora activity
content `PendingIntent`, public version, fixed sender, and fixed body.

A real touchscreen swipe expands the AOSP notification shade. The journey
locates the exact controlled AOSP SystemUI notification row/body and taps it,
delivering the production content `PendingIntent` to the same warm
`MainActivity`. The exact synthetic Thread ID and production open-conversation
action are consumed; Thread becomes visible, both wallpaper editors are gone,
and the synthetic SAF source is not reopened. Assignment, revision, no-follow
managed-file ledger, persisted-grant identity/read/write/time, and source-open
baselines remain exact. Notification auto-cancel restores the active-notification
baseline, while the complete post-bootstrap channel snapshot, including each
channel's DND-bypass setting, is unchanged. Back at Inbox, reopening the global
editor shows disabled Apply, no loading or error, and no staged selection.

The runner remains backward-compatible, bounded, fail-closed, and collision-
safe. Notification cleanup first fingerprints the exact package/tag/ID before
canceling it. Custom status 45 with `auroraSafNotificationCleanupResult=pass`
belongs only to its bounded cleanup-only instrumentation for abnormal residue;
that recovery path did not run during the passing journey.

The final focused journey passed in 6.927s after review confirmations in
7.170s, 6.961s, and 6.797s. Selection, stale-Apply, and cancellation regressions
passed in 12.879s, 8.595s, and 2.745s. The final API 26 root connected gate was
`BUILD SUCCESSFUL` in 1m51s across 456 tasks; XML reports 80 app tests/seven
intentional skips and 179 project tests/eight skips, with zero failures or
errors. The API 36 root connected gate was `BUILD SUCCESSFUL` in 1m26s across
456 tasks; project XML reports 176 tests/five intentional skips and zero
failures/errors. The 886-task offline host/lint/release/privacy/license gate was
`BUILD SUCCESSFUL` in 12s with 26 executed and 860 up-to-date tasks. CycloneDX
1.6 passed 15 tasks in 8s with 441 components and 442 dependency nodes. The
unchanged production debug APK is 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This closes only one synthetic, warm-task, pre-Apply API 26 AOSP system-
notification content-`PendingIntent` route-disposal path. It does not trigger a
real carrier/provider/receiver/orchestrator incoming message; prove that the
synthetic ID is provider-backed or a verified conversation; cover a cold or
absent task, background, lockscreen, or process death; capture raw
`PendingIntent` action/extras/flags; cover API 27+, notification-permission
denial, OEM or physical notification shades, reply/group/privacy/alerts/new-
channel behavior, raw picker result or temporary URI-grant lifetime, in-flight
Apply, source mutation/provider removal/cloud behavior, or nonempty baselines;
or close any compound lifecycle or broader gold gate. Only synthetic emulator
fixtures participated. AuroraSMS remains incomplete and not gold.

### Production API 26 emulator-modem incoming-SMS cold-notification partial evidence — 2026-07-17

Source commit `f41dfd4f0552ed249b2fbda65ec2e3b164842c23` adds the isolated,
owner-gated
`scripts/run-emulator-incoming-sms-cold-notification-smoke.sh` runner. It owns
and finally discards a disposable overlay of the dedicated non-Play API 26 GSM
AVD `AuroraSMS_SMSRX_API26`; the default invocation is:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh
```

From an exact empty controlled baseline, the host injects one emulator-modem
PDU. The PDU traverses the protected production `SMS_DELIVER` receiver, creates
one Telephony provider row, reaches a `COMPLETE` replay-journal entry, resolves
the provider-backed verified conversation and subscription-dependent optional
reply target, and posts through the production notifier. The exact live SystemUI
`StatusBarNotification` (SBN) record must have `PRIVATE` visibility, generic
privacy text and a generic `publicVersion`, no controlled sender/body, the
Aurora activity content `PendingIntent`, and the expected subscription-dependent
action contract.

The runner kills the exact receiver-process PID through the same application
UID, then proves that the notification survives unchanged. A real touchscreen
opens the AOSP notification shade and taps that exact row. A distinct cold app
PID starts the production `MainActivity`, where the provider-backed verified
Thread and controlled message are visible; notification auto-cancel follows.
The verification phase restores the exact empty owned delivery and notification
state, and the runner discards its overlay. Two consecutive final focused
passes took 47.610s (prepare 1.083s, verify 0.554s) and 42.839s (prepare 0.987s,
verify 0.549s).

At that exact source hash, the final API 26/API 36 root connected gates were
`BUILD SUCCESSFUL` in 1m45s and 1m19s. Project module XML totals were 180 tests/
nine intentional skips and 177/six, respectively, with zero failures/errors;
the owner-gated incoming-SMS test was discovered and intentionally skipped in
ordinary aggregate matrices. The 886-task host/release/privacy/license gate was
`BUILD SUCCESSFUL` in 18s with 32 executed and 854 up-to-date. CycloneDX passed
in 7s with 441 components and 442 dependency nodes. The production debug APK
remains 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This proves one synthetic API 26 emulator-modem path, not carrier-network
delivery. It does not prove a physical or OEM notification shade, lockscreen
behavior, API 27+, notification-permission denial, grouped or multiple messages,
inline reply execution, MMS, a nonempty provider baseline, OEM/carrier matrices,
or the broader artwork, accessibility, form-factor, performance, complete-
lifecycle, and gold gates. AuroraSMS remains incomplete and not gold.

### Production API 36 notification-denied incoming-SMS recovery partial evidence — 2026-07-17

Source commit `ec3e10299953253b1330d9440a07df981ed9a1af` adds a persistent API 33+ explanation
to `app/src/main/kotlin/org/aurorasms/app/MainActivity.kt`. When AuroraSMS is
messaging-eligible but system notifications are denied, the notice remains
visible across Inbox and Thread. Its action requests the runtime permission
until Android reports a recorded final denial, then opens
`Settings.ACTION_APP_NOTIFICATION_SETTINGS` for AuroraSMS's exact package.
Focused policy, rendering, and intent coverage is in
`app/src/test/kotlin/org/aurorasms/app/NotificationPermissionRecoveryActionTest.kt`
and
`app/src/androidTest/kotlin/org/aurorasms/app/NotificationPermissionNoticeTest.kt`.
`app/src/test/kotlin/org/aurorasms/app/message/IncomingMessageOrchestratorTest.kt`
also proves a disabled-notification result still marks the incoming delivery
handled once, so replay neither inserts nor attempts notification again.

The exact owned-emulator command was:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey notification-denied
```

The extended
`scripts/run-emulator-incoming-sms-cold-notification-smoke.sh` runner owns and
finally discards a disposable overlay of the non-Play API 36 GSM AVD
`AuroraSMS_SMSRX_API36`. The real app-notification Settings master switch first
ran only after SMS-role assignment started the protected exported
`DefaultSmsRoleChangedReceiver` and cleared package stopped state. The switch
produced a denied `POST_NOTIFICATIONS` state carrying `USER_SET`. The Inbox
action then opened the real runtime-permission dialog; final denial produced
`USER_FIXED`, and the next action opened AuroraSMS's exact disabled notification
Settings page. That state and notice survived the Settings round trip and a
cold, taskless boundary.

One documented synthetic SMS was sent through the owned emulator modem. The
separately permissioned test APK independently captured the one raw delivered
PDU through
`app/src/androidTest/java/org/aurorasms/app/message/IncomingSmsPduCaptureReceiver.java`,
while the protected production `SMS_DELIVER` receiver handled the delivery.
`app/src/androidTest/kotlin/org/aurorasms/app/message/IncomingSmsColdNotificationSmokeTest.kt`
required the PDU-derived replay-journal key and decoded sent timestamp to be
exact, one `COMPLETE` journal entry to match the sole provider row's provider/
thread IDs and received/sent timestamps, and zero Aurora SBNs throughout. A
cold `MainActivity` launch displayed the message and missed-alert notice in
Inbox; the provider-backed Thread remained readable with the notice still
present. Exact provider, journal, reply-target, index, capture, and notification
cleanup restored the empty controlled baseline. The complete journey and exact
cleanup passed twice. The unchanged default API 26 fixed raw-PDU journey also
passed after these changes.

At that exact source commit, focused API 36 notice/manifest instrumentation
passed 10 tests. The complete API 36 and API 26 connected matrices each passed
456 Gradle tasks in 1m21s and 1m49s with zero failures. The offline host/lint/
release/privacy gate passed 883 tasks in 1m19s, and the combined license-report/
CycloneDX gate passed 18 tasks in 8s. The resulting 13,993,426-byte debug APK
has SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.

This closes only one API 36 AOSP emulator path. API 33 through 35, physical and
OEM devices, carrier behavior, lockscreen delivery, grouped or multiple
messages, inline-reply execution, MMS, and the broader artwork, accessibility,
form-factor, performance, complete-lifecycle, and release gates remain open.
AuroraSMS remains incomplete and not gold.

### Production API 26 same-sender two-message incoming-SMS cold-notification continuity partial evidence — 2026-07-17

Source commit `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0` adds
`twoDistinctSameSenderDeliveriesPersistAndNotifyOnceEachAcrossReplay` and the
owner-gated command:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey multiple-message
```

The host test proves two distinct same-sender delivery fingerprints insert and
notify once each in one conversation, then replay without another insert or
notification attempt. On the disposable API 26 `AuroraSMS_SMSRX_API26`
overlay, the runner injects two reviewed fixed single-part GSM PDUs exactly once
in sequence. The first row, `COMPLETE` journal, private conversation SBN, and
its reply action stabilize before the second injection; uncertain injection is
never retried. The second delivery has a distinct provider ID and journal while
preserving the same thread, subscription, background PID, notification tag, and
notification ID. The sole SBN's `mUpdateTimeMs` strictly advances and
stabilizes, while notification-dump and AOSP-shade scans exclude both bodies
and the sender.

After exact receiver-process death, a real AOSP shade-row tap launches a
distinct cold `MainActivity` process on the provider-backed verified Thread.
The Thread contains exactly two message bubbles, each expected body once and in
delivery order, and auto-cancel follows. Verification covers exactly two
provider rows, two `COMPLETE` journals, two subscription-backed reply targets,
two indexed/timeline messages with unread count two, and exact cleanup. Bounded
zero-, one-, or two-delivery recovery remains fail-closed. The journey passed
twice independently; the unchanged API 26 single-message and API 36
notification-denied journeys also passed.

At `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0`, the API 26 and API 36
connected matrices finished 97 and 91 tests with zero failures in 47s and 51s.
The 886-task host/lint/release/benchmark/
privacy/dependency/permission/APK-content/license gate passed in 19s; CycloneDX
passed 15 tasks in 7s. The unchanged 13,993,426-byte debug APK has SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.

This closes only sequential same-sender, single-part SMS notification-update
continuity on one API 26 AOSP emulator. Multipart SMS, other-conversation
notification grouping/summary behavior, alert/sound/vibration counts, API 27+,
physical/OEM shade, carrier-network and lockscreen behavior, inline-reply
execution, MMS, nonempty-provider baselines, broader acceptance, and gold
remain open. AuroraSMS is incomplete and not gold.

### Durable SMS/reply recovery and API 26 inline-reply permission-denial partial evidence — 2026-07-18

Implementation commit `7c9d848` introduces a durable reply-operation state
machine plus checksummed reply-target and replay-ownership records, provider-
status transition and callback handling, exact incoming-notification generation
ownership, and checksummed incoming replay records with provider-content digest
verification and poison-entry quarantine. Recovery orders same-kind provider
IDs before wall-clock time, returns unresolved outgoing `PENDING` rows as
deferred, serializes default-role loss with exact generation cancellation, and
allows accepted receiver work to continue in-process after the receiver lease
times out while durable checkpoints own later process-loss recovery.

The owner-gated journey is:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey inline-reply-permission-denied
```

Each of two independent passes started from a fresh disposable API 26
`AuroraSMS_SMSRX_API26` overlay. A fixed modem SMS traversed the protected
production `SMS_DELIVER` receiver into exactly one provider row, one complete
incoming replay record, one durable reply target, and one private reply-bearing
conversation SBN. After exact receiver-process death, the runner revoked only
`SEND_SMS` and proved the non-stopped package remained processless and taskless,
with the original SBN and reply `PendingIntent` identity unchanged. It opened
the real AOSP shade, selected the unique Reply action, entered and verified one
fixed synthetic RemoteInput value, and tapped Send exactly once. An uncertain
submission is never retried.

The tap started a distinct cold `InlineReplyReceiver` process and no Activity
task. Durable handling converged on one consumed reply claim and one notified
known-unsent operation. Synchronous permission preflight rejected transport
before platform submission, leaving no outgoing provider row; the original
conversation SBN remained and exactly one private, generic, body-free failure
SBN appeared. Notification-dump, shade, and bounded log scans excluded the
sender, incoming body, and reply text. A real tap on the unique failure row
started a fresh `MainActivity` process on the exact provider-backed Thread,
auto-cancelled only the failure SBN, and preserved the conversation SBN. Exact
teardown restored the empty controlled provider, incoming-journal, reply-target,
reply-claim, reply-operation, index, and notification baselines and restored
`SEND_SMS`. Both fresh-overlay passes completed independently.

For the same implementation, the complete offline host/lint/release/benchmark/
privacy/dependency/permission/APK-content/license gate passed 886 tasks and the
separate CycloneDX gate passed. Focused durable-store tests passed 43/43 on both
API levels, notification-generation tests passed 18/18 on both, and incoming-
journal tests passed 9/9 on both. The full API 36 connected matrix reported zero
failures/errors with runner-discovered module totals of app 135, notifications
22, telephony 24, state 43, index 31, conversations 5, and benchmark 4. API 26
likewise reported zero failures/errors with app 141, notifications 22,
telephony 24, state 43, index 31, conversations 5, and benchmark 4; its retained
XML reconciles 258 zero-failure results and 12 intentional gated skips to 270
runner-discovered cases.

The current follow-on slice addresses the first two formerly listed residuals
at the implementation-contract level through an explicit single-owner model.
Notification inline reply remains caller-owned by the private durable reply-
operation store and its reserved high operation-ID namespace.
`RESPOND_VIA_MESSAGE` uses ordinary low operation IDs and is owned by the
transport's separate private, content-free outgoing journal. Neither path may
silently opt out of one of those durability owners.

Outgoing SMS insertion creates an exact app-owned row as known-unsent `FAILED`
with a staging sentinel. Only after its owner durably records `PREPARED` may one
conditional arm consume that sentinel and transition that exact row to
`PENDING`; `SUBMITTING` is durable before the irreversible platform call. A
synchronous refusal or cancellation before that call conditionally
terminalizes only an exact Aurora-created row in an allowed staging, armed, or
terminal state. It treats an absent row as retired and turns an ownership/state
conflict into quarantine without mutating a foreign or reused row. Inherited
`PREPARED` retries exact cleanup. Inherited `SUBMITTING` instead becomes
`SUBMISSION_UNKNOWN` and is never rearmed or resubmitted; it does not falsely
converge to `FAILED`.

The transport-owned journal stores no body, recipient, or subscription. Its
128 entries contain only low operation/provider identities, conversation, part
count, state, and lifecycle times. Active `PREPARED` and `SUBMITTING` entries
are never evicted. Only `SUBMISSION_UNKNOWN` and known-unsent quarantine
tombstones expire after seven days; capacity otherwise rejects new work rather
than dropping ownership. Corrupt, noncanonical, or uncommittable journal state
globally fails transport-owned submissions closed. A transient cleanup failure
for one provider row remains deferred and scheduled for retry but does not by
itself block recovery of other rows or an unrelated new send.

Rows already left `PENDING` by pre-journal alpha builds have no exact durable
transport record. Upgrade recovery intentionally neither sweeps nor mutates
those rows and does not claim to repair their status.

Generic reply-failure notifications now use conversation-plus-operation tags
and carry that operation marker. Later positive evidence cancels only the
matching failure alert and exact source generation, preserving failures owned
by other replies in the same conversation. A crash between exact cancellation
and durable acknowledgement replays the same keys idempotently. On first
role-enabled recovery after upgrading from the pre-operation-key alpha,
AuroraSMS dismisses any still-active conversation-only generic
reply-failure alerts
because they cannot be mapped safely to one durable reply operation. Previously
user-dismissed alerts are not recreated. Message/provider state and durable
late-callback ownership are unchanged; users should verify those replies in the
conversation. If legacy-alert enumeration or cancellation fails, pending replay
is deferred and recovery retries. A migrated success record that lacks
historical source-message identity cannot cancel an exact incoming generation,
so its operation-scoped failure alert is cancelled but durable success
acknowledgement remains pending rather than guessing.

Final-source focused verification completed a 320-task host gate with telephony
75/75, core testing 22/22, and app 191/191, plus green lint and app/telephony
`androidTest` compilation. The transport-owned submission journal passed 7/7
on API 26 and 7/7 on API 36. The owner-gated real Telephony-provider staging
contract passed 1/1 on each API without invoking `SmsManager`; it covered the
staged insert and one-shot arm, wrong-thread conflict preservation, idempotent
terminalization, absent exact URI, and exact synthetic-row cleanup.
Notification identity/cancellation passed 29/29 on each API, including real
`NotificationManager` sibling preservation. A fresh disposable API 26 SystemUI
`inline-reply-permission-denied` journey passed with exact cleanup and its
overlay was discarded.

The final API 26 root connected matrix was `BUILD SUCCESSFUL` in 1m51s across
456 tasks. Preserved console module roots record app 132 with 12 skips, benchmark 3 with one
skip, notifications 29, telephony 31, state 43, index 31, and conversations 5:
274 total tests, 13 intentional skips, and zero failures/errors. The API 36
matrix was `BUILD SUCCESSFUL` in 1m24s across 456 tasks; retained XML records app
129 with nine skips, benchmark 3 with one skip, notifications 29, telephony 31,
state 43, index 31, and conversations 5: 271 total tests, 10 intentional skips,
and zero failures/errors. The complete host/release/privacy/license aggregate
was `BUILD SUCCESSFUL` in 1m19s across 886 tasks (130 executed, seven from cache,
749 up-to-date). CycloneDX 1.6 passed 15 tasks in 8s with 441 components and 442
dependencies. The debug APK is 13,993,426 bytes with SHA-256
`16037c616d6d696b4974f3e3a14238c18937c6f677f2f60e677ca10f0ea0ef98`.

The first API 26 aggregate attempt remains diagnostic only: it exposed a
channel test disabling the production reply-failure channel, whose disabled
importance persists across delete/recreate on API 26. The corrected test uses a
dedicated test-only channel, and only the later green matrix is pass evidence.
The implementation and tests for this final-source slice are frozen in commit
`3d7182c`.

The bounded 512-entry incoming journal remains an explicit limitation: it
eventually evicts completed ownership, so an extremely old exact redelivery may
eventually insert again. Successful carrier send and callback paths, API 27
through 35, physical/OEM shade and lockscreen behavior, Android Auto, low-
memory/background races, and process death at every durable checkpoint remain
unproven, as do broader group, multipart, MMS, accessibility, performance, and
release rows. AuroraSMS remains incomplete and not gold.

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

For ADR 0007, also stop before persisting a picker/source URI, taking a durable
URI grant, adding a storage/media permission, admitting a non-JPEG/non-PNG
source, an unsupported JPEG process/precision, or APNG, exceeding any accepted
byte/dimension/pixel/allocation quota,
exceeding the 128-file/256-MiB durable assigned quota or the single-candidate
129-file/264-MiB physical staging ceiling, writing the assignment before the
verified final derivative and leaf entry are durable, bypassing the process-wide
namespace mutex, following a managed path component, publishing without
exclusive pending creation/exact identity verification/final-absence recheck,
or partially deleting during a failed first reconciliation scan. Also stop
before deleting a file without fresh bounded Room authority, retaining source
metadata, rendering a stale target's bitmap while a new target loads, exposing
an Inbox/built-in/GIF/live-URI control, or adding a media catalog table.

For the scoped-profile-reference foundation, also stop before storing raw
participant addresses or a reversible participant serialization, resolving a
conversation by thread ID alone, accepting incomplete/truncated participant
identity, exposing an unbounded all-conversation assignment flow, treating
Search or Theme Studio as a scope, exposing controls for absent routes, applying
a profile or staged inherited choice without the expected revision,
pushing/replacing the current route for the modal, or adding focal/dim/media
state without its separately accepted renderer and UI contract.
