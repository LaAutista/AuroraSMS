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
handoff gates. Complete picker/static-wallpaper UI acceptance remains pending;
Inbox/other-screen treatment, built-in artwork, GIF/live-URI media,
import/export, navigation variants, and the full accessibility/performance and
carrier matrices remain gated follow-on work. AuroraSMS is not complete or gold

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
app/src/androidTest/kotlin/org/aurorasms/app/DefaultSmsManifestContractTest.kt
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
