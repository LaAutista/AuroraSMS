# AuroraSMS product requirements

Status: Phase 0 product baseline, 2026-07-12

## Product statement

AuroraSMS is a complete, private, original Android SMS/MMS application. It
indexes the user's full Telephony history locally for fast, complete search
while keeping inbox, thread, and search presentation paged and bounded. It is
FOSS, ad-free, tracker-free, account-free, and server-free.

Approved public scope language:

> Complete private SMS/MMS, with compatible presentation of common reaction
> fallback texts where possible. RCS is not promised.

## Phase 0 identity decisions

These decisions apply before the first production module is created:

| Decision | Phase 0 selection | Constraint |
|---|---|---|
| Product name | AuroraSMS | Original identity |
| Application ID | `org.aurorasms.app` | Must be changed before Phase 1 if the owner rejects it |
| Source license | GPL-3.0-or-later | Applies to original AuroraSMS source; artwork licensing is separate |
| Minimum SDK | API 26 | Required test baseline |
| Compile SDK | API 37 | Required by the approved current AndroidX artifacts; build surface only |
| Target SDK | API 36 | Preserves the blueprint's reviewed target behavior until a separate target-37 audit |
| UI toolkit | Jetpack Compose with original AuroraMaterial components | No copied layouts or strings |
| Language/runtime target | Kotlin; Java/Kotlin bytecode target 17 | Host JDK may be newer |
| Distribution | F-Droid-compatible source and GitHub releases first; Play is optional | FOSS build remains the privacy baseline |
| Network permission | Absent | `INTERNET` is forbidden in the FOSS build |
| Signing | A new AuroraSMS release key | Generate outside Git; keep encrypted owner-controlled offline backups |

The signing key is not generated in Phase 0. The artwork license and original
AuroraSMS launcher icon remain explicit owner decisions; see
`docs/ARTWORK_CATALOG.md`. The project source grant and SPDX policy are recorded
in `LICENSE_POLICY.md` without modifying the canonical GPL text in `LICENSE`.
Compiling against API 37 does not enable or imply RCS or an alternative message
transport; AuroraSMS's approved runtime scope remains SMS/MMS.

## Non-negotiable behavior

- Android's Telephony provider is authoritative for actual SMS and MMS.
- AuroraSMS maintains a rebuildable, synchronized local projection for display
  and full-text search.
- Drafts, scheduled messages, pending sends/deletes, spam decisions, named
  appearance profiles, the active-profile selection, and scoped profile
  overrides live in a separate durable Aurora state store. Conversation
  appearance identity stores only a versioned participant-set fingerprint plus
  the current provider thread ID, never raw participant addresses.
- No UI path loads an entire message history or unbounded result list.
- Search coverage does not depend on a user manually scrolling old messages.
- Ordinary conversations with more than one unique canonical recipient always
  send as group MMS. There is no toggle, first-send question, informational
  settings row, or silent fan-out to individual SMS.
- AuroraSMS has no recycle-bin screen, table, preference, worker, migration,
  menu item, or compatibility path. Archive is non-destructive; deletion is
  permanent after clear confirmation, with at most a short pre-commit Undo.
- The FOSS build has no ads, analytics, telemetry, account, remote logging,
  remote config, remote theme service, remote fonts/GIF search, or network spam
  reputation.
- AuroraSMS does not claim RCS, universal SMS read receipts, guaranteed
  scheduling, or a universal MMS size limit.

## Information architecture

Required destinations are:

- default-SMS onboarding and role recovery;
- inbox and New chat;
- global complete-history search;
- conversation thread and composer;
- Archive;
- Spam & Blocked;
- searchable Settings and Theme Studio;
- backup and restore;
- About, privacy, and permission surfaces;
- an internal diagnostics surface in debug builds only, never a seeded or
  user-facing release destination.

Classic navigation is the default: a top search/app bar, a right-side vertical
three-dot overflow, the conversation list, and an extended `New chat` action.
The overflow reaches Archive, Spam & Blocked, Mark all as read, Settings, and
About. Optional bottom navigation and an adaptive rail must use the same route
graph, screen instances, deep links, back behavior, and restored state.

## Complete-history architecture

### Ownership

- Telephony SMS/MMS provider: real-message source of truth.
- Aurora index database: disposable/rebuildable projection for paging and FTS.
- Aurora state database: durable Aurora-only state, including drafts, named
  appearance profiles, the active-profile selection, and scoped
  appearance overrides. A conversation override uses the ADR 0006
  participant-set fingerprint as its stable identity and a provider thread ID
  only as a current routing hint.
- No DataStore owner exists in the approved implementation. Adding one for a
  future lightweight preference requires a measured ADR, dependency admission,
  and field ownership that does not overlap the Aurora state database.

Corrupt index recovery may discard and rebuild only the index. It must never
erase a draft, schedule, pending send, or appearance assignment.

### Index requirements

Each projected row has a local `Long` row ID and a compound-unique provider
identity `(provider_kind, provider_id)`. SMS and MMS IDs may collide. Required
data includes provider/thread identity, normalized millisecond timestamps,
direction, box/status, subscription, sender address, body/subject, attachment
summary, read/seen/locked state, and a sync fingerprint.

Required indices cover thread/time, global time, sender, subscription/time, and
read/time. Contact names/photos come from a bounded, invalidatable contact
cache; contact graphs and attachment bytes never live in message list rows.

### Search and paging

- Use Room FTS4 for body, subject, and approved normalized searchable text.
- Safely normalize and escape user input; support quoted phrases and guarded
  prefix matching.
- Debounce around 120-180 ms and cancel obsolete searches.
- Return paged results; initial tuning is page size 50 and prefetch 50.
- Use keyset paging, not deep `OFFSET` or repeated provider-page loops.
- Selecting a result opens a bounded page around its exact stable anchor and
  briefly highlights it.
- Incomplete indexing displays honest progress and says `No indexed matches
  yet` instead of claiming there are no messages.

### Synchronization

- After role/permission success, index newest messages first in bounded,
  resumable transactions while the app remains usable.
- Query SMS and MMS separately and normalize their different date units.
- Cursor deterministically by provider timestamp plus provider ID.
- Begin measurement with 500 text rows per batch and adapt when transaction
  latency exceeds the target.
- Never decode MMS media during text indexing.
- Coalesce receiver and `ContentObserver` signals and periodically reconcile
  external changes and deletions.
- Resume after process death from the last committed checkpoint.
- After the initial scan reaches the oldest provider boundary, run a bounded
  lightweight consistency pass and persist a completed generation only after
  that pass succeeds. A restart or reconcile must never mistake a partial
  checkpoint for complete index coverage.
- On default-role loss, stop provider writes safely and report the state.

## AuroraMaterial requirements

AuroraMaterial is one immutable, versioned token/profile engine. It controls
palette, hue, typography, shapes, spacing, navigation, motion, bubbles, avatar
masks, composer treatment, icons, wallpaper, dimming, and contrast. It must not
create duplicated Activities, screens, layouts, or state paths for presets.

Appearance resolves in this order:

1. conversation override;
2. current-screen override;
3. active user profile;
4. canonical AuroraMaterial default;
5. safe solid-color fallback.

Wallpaper inheritance is surface-specific:

| Surface | Resolution order |
|---|---|
| Conversation | Conversation override -> global thread wallpaper -> active theme wallpaper -> safe solid |
| Inbox | User inbox override -> Aurora Night -> active theme wallpaper -> safe solid |
| Archive | User Archive override -> Aurora Cabinet -> active theme wallpaper -> safe solid |
| Settings | User Settings override -> Aurora Office -> active theme wallpaper -> safe solid |
| Spam & Blocked | User Spam override -> Aurora Spam & Block -> active theme wallpaper -> safe solid |

The bounded scoped-profile-reference foundation is distinct from those final
wallpaper chains:

- eligible screen codes are exactly Inbox, Archive, Settings, Spam & Blocked,
  and global thread fallback; Search and Appearance/Theme Studio are not
  override scopes;
- each scoped row references one existing named profile and never copies that
  profile's token values;
- assignment creates and actual updates allocate positive revisions from one
  durable, globally monotonic sequence in the same transaction; reset, cascade
  deletion, database reopen, and recreation never reuse a revision, so a stale
  pre-deletion modal cannot pass an ABA check; physical triggers admit only the
  singleton-zero insert, exact `old + 1` advances, and no deletion, while
  repository validation rejects a sequence below any live assignment revision;
- a conversation row stores its current positive provider thread ID and the
  exact versioned SHA-256 participant-set fingerprint defined by ADR 0006, but
  no raw address, display name, participant serialization, message content, or
  contact identifier;
- the fingerprint is computed only for verified-complete, non-truncated
  participants and is authoritative; unavailable, incomplete, truncated, or
  mismatched identity inherits `global_thread` rather than resolving by thread
  ID alone;
- the index version-2-to-version-3 migration preserves searchable rows but
  revokes every older verified-complete claim by marking generations
  paused/pending; a fresh scan from empty checkpoints must complete under the
  stricter missing/malformed participant rules before conversation assignment
  is available;
- the fingerprint contract accepts 1 through 100 participants, but the current
  Thread UI has only the maximum-8-member `ConversationSummary` preview and
  exposes assignment only when that preview is the complete verified set;
  conversations with 9 through 100 indexed participants inherit
  `global_thread` until a bounded full-identity query is implemented;
- repository observation is target-specific, not an unbounded collection of
  every customized conversation; its first Room row-or-null is authoritative,
  while the app retains explicit loading until a positive durable profile
  revision and the exact target query both arrive;
- Inbox More exposes Inbox appearance and `Conversation defaults`
  (`global_thread`), while Thread More exposes `Conversation appearance` only
  for the verified current conversation; future screen codes have no controls
  until their routes exist;
- the chooser is modal over the current route, stages a named profile or
  `Inherited`, and writes only on revision-checked Apply; Cancel, Back, and
  dismissal are durable no-ops;
- restorable chooser state contains only a bounded schema, private target token,
  baseline/selected profile IDs, and expected revision; a target mismatch is
  discarded synchronously, controls stay disabled while either the durable
  profile snapshot or exact target-assignment query is loading, and
  in-flight/error/dismissal state is not restored as completed work;
- applying `Inherited` revision-checks and deletes only that assignment;
  revision-checked profile deletion cascades its referencing assignments so
  each target immediately inherits; and
- while the Activity/root composition remains alive, opening a scoped chooser,
  changing its selection, canceling, applying a named choice, applying an
  inherited reset, pressing Back, or dismissing it does not query Telephony,
  alter index state, recreate its route/state holder,
  reload provider/index presentation, or disturb the in-memory paged window,
  visible scroll anchor, retained Search route/query, draft, or composer;
- configuration/process restoration may reconstruct the state holder and
  perform the existing ADR 0003 bounded presentation re-query; it restores the
  logical route stack, exact chooser target and bounded draft, retained Search
  query, Thread draft/composer, and the same stable anchor when an exact
  saved Thread anchor exists, but does not serialize or claim identity for the
  in-memory page; and
- this scoped slice makes no exact-anchor recreation claim for a normal Inbox
  or unanchored Thread without a separately reviewed bounded anchor API. The
  one-time index-schema semantic invalidation is an upgrade/reconciliation
  boundary, not an appearance action.

The completed bounded acceptance extension proves this contract with the real
`AuroraSmsRoot` and deterministic synthetic services: Inbox/global and
exact-anchor Thread modal operations preserve their route and visible state,
Search query, draft, and composer with zero modal-caused provider/index reload.
`ActivityScenario` recreation reconstructs the holder, performs exactly one
allowed anchor query, and restores the exact target/selection, stable visible
`ProviderMessageId` plus offset, Search query, draft, and composer. A fresh
same-thread re-entry receives a unique route-state entry and exact jump;
popped/evicted entries remove their SavedState and retention is bounded to
`MAXIMUM_RETAINED_ROUTES`. The physical real-app evidence is intentionally
limited to the Inbox modal. The final frozen artifact still requires that
unlocked focus rerun, so this does not yet claim its physical modal acceptance,
full process-death end-to-end, or physical eligible-Thread modal coverage.

Assignment-local focal points/dim values and every wallpaper/media reference,
picker, renderer, decoder, canonical artwork mapping, and GIF lifecycle remain
separate acceptance work. The profile-reference foundation does not claim the
wallpaper-specific chains merely because a named profile already has future
wallpaper vocabulary.

Required themes include Aurora Dark, AMOLED Black, Light, and System/Dynamic.
Required avatar masks are circle, rounded square, squircle, and hexagon.
Rows support compact, comfortable, and spacious density. Profiles support
preview, apply, duplicate, rename, scoped reset, export, and validated import.
Theme imports are declarative data and explicitly approved media, never code.

Accessibility overrides decoration: body text targets 4.5:1 contrast, touch
targets are at least 48 dp, controls remain available at 200% font scale, and
the app supports TalkBack, RTL, high contrast, reduced motion, dark/light, and
dynamic color. Hot paths use opaque/static surfaces rather than live blur.

## Canonical artwork and wallpaper behavior

- Inbox: Aurora Night.
- Archive: Aurora Cabinet.
- Settings: Aurora Office.
- Spam & Blocked: Aurora Spam & Block.
- Bridge, City, Cat, and Cherry Blossom: optional offline presets.
- The supplied AuroraCamera icon has no AuroraSMS runtime role.

All defaults are replaceable. The user can independently assign a built-in,
solid/gradient, static image, or animated GIF to the inbox, Archive, Settings,
Spam & Blocked, global thread fallback, and each conversation. Resetting one
assignment must not change another.

Large user images are downsampled, and every source is decoded/resampled to a
bounded device target with saved focal crops; a built-in smaller than a tall
display may be upscaled only to that bounded target. Every assignment stores a
user-adjustable dim value with an enforced accessible floor, live preview, and
scoped reset. Static blur is precomputed and cached. Only the visible GIF may
animate; inactive surfaces use preview frames. Animation pauses when covered,
backgrounded, display-off, battery-saving, or reduced-motion, and ordinary
list updates must not restart it.

## Screen requirements

### Inbox

- top search, right-side overflow, short horizontal pinned strip, recent rows,
  and extended New chat action;
- pinned conversations are not duplicated as full rows in Recent, and content
  padding prevents the New chat action from covering a row;
- avatar/title/snippet/time hierarchy with pin and unread state;
- selection actions for pin, archive, unread, block, and delete;
- customizable non-gesture alternatives for every swipe action.

### Search

- opaque result surface grouped into Conversations, Messages, and later
  Attachments where implemented;
- match emphasis without changing type size;
- indexing coverage and exact-result jump;
- correct IME insets, focus, overflow reachability, and dismissal;
- deterministic back dismissal order for menu, IME/search focus, search state,
  and route, while retaining the current query appropriately;
- later filters: `from:`, `before:`, `after:`, `has:attachment`, `is:unread`.

### Thread and composer

- back, identity/title, relevant SIM subtitle, call via a safe system intent,
  and overflow;
- independently paged history with anchored composer;
- accessible incoming/outgoing bubbles and group sender labels only when the
  identity changes;
- useful status only, with prominent actionable failures;
- attachment, message field, optional schedule state, and send;
- segment count near an SMS boundary or when explicitly enabled, never
  permanent noise;
- selection for local reply/quote, selected-text copy, details, forward, and
  permanent delete;
- reaction fallback presentation never mutates the stored message.

### Archive and Spam & Blocked

Archive reuses the inbox component family and provides an accessible empty
state. Spam protection is entirely local, explainable, contacts-trusting by
default, and offers unspam/unblock recovery and visible decision reasons.

### Settings and Theme Studio

Settings is searchable and grouped into Appearance, Conversations,
Notifications, Sending, Privacy & security, Spam & blocked, Backup & restore,
Storage & search index, Accessibility, Advanced, and About. Granular palette,
geometry, navigation, motion, and wallpaper controls live in a Basic/Advanced
Theme Studio with a live preview.

## Privacy and security requirements

- No `INTERNET` permission or undeclared network path in the FOSS build.
- `android:allowBackup="false"` until a documented secure policy replaces it.
- Data-extraction rules exclude message/state databases, indexes, preferences,
  thumbnails, temporary attachments, and other sensitive caches.
- Normal data stays in credential-encrypted private storage.
- Photo Picker/Storage Access Framework is preferred over broad storage access.
- Message bodies, addresses, URIs, and search terms never enter logs.
- Provider thread IDs and participant-set fingerprints used for conversation
  appearance are sensitive pseudonymous metadata: keep them private, excluded
  from backup, absent from logs/`toString`, and never describe them as anonymous
  or telemetry-safe. The bounded target token that combines them for app-private
  `SavedState` follows the same restrictions and is never displayed or exported.
- Release builds remove debug logging.
- Notification privacy supports sender and body, sender only, or generic.
- App lock may use `BiometricPrompt`/device credential but must not be called
  database encryption.
- Secure-recents wording explains that screenshot/screen-sharing behavior is
  also affected.
- V1 relies on Android sandboxing and file-based encryption. SQLCipher is a
  later, measured hardened-mode investigation, not a Phase 1 dependency.

## Performance budgets

These are physical-device targets after a warm index, not current claims:

| Journey | Target |
|---|---:|
| Warm start to usable inbox | <=300 ms P50; <=500 ms P95 |
| Open text thread to visible content | <=250 ms P50; <=450 ms P95 |
| Search response at 500k rows | <=120 ms P50; <=220 ms P95 |
| Jump to old indexed result | <=350 ms P50; <=650 ms P95 |
| Slow frames during text-thread fling | <1% |
| Frozen frames | 0 |
| Text browsing PSS | Aim below 150 MiB |
| Unexplained APK growth | <=5% per release |

Provider/DB/file/bitmap/contact work stays off the main thread. Timeline models
never contain attachment bytes or full-size bitmaps. Lists use stable IDs and
granular updates. Release builds use R8 and a measured Baseline Profile.

## Delivery roadmap

1. Phase 0: clean-room/product/security/dependency/artwork/test specifications.
2. Phase 1: Android/default-role foundation and real SMS/MMS vertical slice.
3. Phase 2: separate databases, resumable index, FTS4, and exact-result jump.
4. Phase 3: bounded inbox/thread paging and performance evidence.
5. Phase 4: AuroraMaterial, canonical artwork, Theme Studio, independent
   static/GIF assignments, and accessibility.
6. Phase 5: schedules, send delay, dual-SIM persistence, group-MMS hardening,
   and permanent-delete behavior.
7. Phase 6: notifications/reminders, reactions, voice memo, selected-text copy,
   signatures, local spam, backup/restore, and Android Auto.
8. Phase 7: provenance, migrations, reproducible release, F-Droid metadata,
   security policy, size, privacy, and full device hardening.

Each phase stops at its acceptance gate. Passing a later-looking UI demo never
waives an earlier telephony, privacy, data, accessibility, or performance gate.

## Reference interpretation decisions

Where the interactive mockup conflicts with this specification, this document
and the clean-room blueprint control:

- use the required three-dot inbox overflow, not the mockup's settings gear;
- omit the mockup's locked group-MMS settings row and enforce the invariant in
  transport logic;
- replace live backdrop blur with opaque/static hot-path surfaces;
- raise sub-48-pixel concept controls to 48 dp minimum targets;
- use actual canonical artwork rather than the mockup's CSS wallpaper;
- never reuse concept/reference names or message text in fixtures.

The short pinned strip and grouped search sections are deliberate AuroraSMS
blueprint redesigns. The private pixels show pin state and mixed result types,
but do not themselves show those final grouping layouts; they are requirements,
not claims of pixel-evidenced reference behavior.
