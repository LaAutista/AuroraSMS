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
  profiles; it never copies profile tokens or raw participant addresses into
  appearance state;
- a durable globally monotonic assignment-revision sequence prevents stale
  delete/recreate ABA writes, while target-specific Room flows keep observation
  bounded;
- Inbox and global conversation defaults are route-local modals, and Thread
  exposes conversation appearance only for a verified complete identity;
- the private restoration token is validated against the current target,
  mutation controls wait for both the durable profile snapshot and the exact
  target-assignment query after process load, and no production Activity is
  added; and
- the core fingerprint contract accepts 1 through 100 participants. The
  `ConversationSummary` display preview remains capped at 8, while a separate
  exact-thread index read retrieves at most 101 rows (100 plus one sentinel) and
  exposes a 1-through-100-member identity only for the matching
  verified-complete generation, exact declared count, and non-truncated row set.

A follow-on exact-thread identity prerequisite now lets conversations with 9
through 100 verified members use the same scoped appearance path without
expanding the display preview. Its address-bearing
`VerifiedConversationIdentity` exists only ephemerally between the index
repository, Thread holder, and immediate one-way fingerprint derivation. It is
projected from the existing private rebuildable index participant-address rows;
the derived identity object/list is redacted and is not newly persisted in
appearance state or placed in `SavedState`. Provider invalidation clears it
before re-query. An initial timeline-ready state may
precede the delayed exact-identity lookup: appearance stays unavailable, but a
restored editor target is retained until that lookup completes. Resolved-null,
terminal failure, missing, oversized, stale-generation, count-mismatched,
truncated, or route-mismatched identity closes the editor and inherits
`global_thread`.

Focused host, Room, holder, resolver, and real-root tests passed. The final
host/release/benchmark/governance/license gate passed 886 tasks in 1m05s, the
separate SBOM run passed, and the full API 36 connected matrix passed 455 tasks
in 57s. The final 13,212,416-byte debug APK
(`39a07d72b7c58b91a11b152458ba971161b1edd98883f68df4fdbc6ab724235d`)
was installed successfully on the Pixel 8 and copied to Download with the same
size and SHA-256. The privacy-safe Inbox-only physical smoke passed 1/1;
package, default-role, required-grant, and cold-launch checks also passed without
an app crash. This is not physical 9-member Thread evidence. Source commit
`83db9aa0f02cef44644f53d0bb149abe459dc20b` is pushed on `origin/main`; its
[GitHub Verify run](https://github.com/LaAutista/AuroraSMS/actions/runs/29380854714)
passed the 10m59s build job with every project step green. The only annotation
was GitHub's hosted Node 20 deprecation/forced-Node-24 notice for pinned actions,
not a project failure.

The earlier ADR 0006 slice's final frozen APK passed its intentionally
Inbox-only physical focus gate on an awake, unlocked Pixel 8. The gated
real-`MainActivity` smoke used only
package/view IDs and accessibility window metadata to prove a distinct focused
scoped dialog, then Cancel returned to the same MainActivity/Inbox window without
opening a Thread or applying an assignment; aggregate appearance state remained
`0|0|0`. The exact 13,396,196-byte APK was copied to Download with matching
SHA-256, and a privacy-safe cold launch resumed MainActivity without an app
crash. Physical eligible-Thread modal coverage remains follow-on work.

ADR 0007's managed private static Thread-wallpaper store is now crash-safe and
quota-bounded at source commit `f0f1ff9`. Its app-private namespace uses a
process-wide mutation lock, durable parent and leaf-directory synchronization,
exclusive no-follow pending files, verified same-directory atomic publication,
fresh bounded Room authority for cleanup, and fail-closed two-pass startup
reconciliation. Focused policy/controller tests passed 32/32; the combined
managed-store/crash protocol passed 29/29 on API 26, API 36, and a Pixel 8. The
complete local connected, release/governance, license/SBOM, APK install,
cold-launch, and exact Download-copy gates also passed. The physical 29-test run
was non-UI app-private filesystem evidence; it does not prove the Photo Picker
or user-visible static-wallpaper journey.

Source commit `111381dff31c46380eab969dea20234cba16fe08` now has one narrow
physical UI result: its dedicated platform-Photo-Picker runner passed 1/1 in
7.107s on Pixel 8 Android 16/API 36 serial `192.168.68.55:43069`. Cancel and
wallpaper Back preserved the empty baseline; Apply created one `global_thread`
assignment and one conforming managed file; Reset restored the baseline; and
the exact synthetic Downloads fixture was deleted. Post-run database counts
were `0/0`, managed files were `0`, the test package was absent, and the target,
SMS role, and all seven grants were preserved. The local, installed, and
Downloads APKs were each 13,993,426 bytes with SHA-256
`5c4c7255396f6a5676eaf7da3e617a045ecfc9b6e5e3ded7551990eb5f5267d1`.

Source commit `b9350be354991e36039e8136095bc25ebd520d60` adds synthetic API 36
verified-conversation acceptance. Root-level timeline pixel captures prove a
conversation assignment wins over `global_thread` and that its dimmed color is
equivalent after Activity recreation; editor and repository assertions prove
focal point and dim survive Apply plus recreation. Reset falls back to global
pixels, identity loss revokes conversation authority without mutating either
durable target, and unavailable assigned media falls through to the solid
background without stale pixels. A separate real-Room close/reopen test proves
exact global and conversation rows survive database close and reopen and that
conversation reset leaves global state untouched. This is Activity recreation
plus an independent Room reopen; it is not cold-process
renderer/filesystem-restart evidence.

Source commit `73b5ffa2827ad2cd96b922ccf4a529b5b052529d` adds one explicitly
gated API 36 emulator cold-target-process journey for a synthetic verified
conversation. The preservation-safe host runner started a normal AuroraSMS
process, recreated the same canonical pending-file path after ordinary startup
reconciliation, and proved `am force-stop` removed that exact live PID. A fresh
target process then reopened the production `AppContainer`, Room state,
`WallpaperController`, and app-private managed store; recovered the exact
assignment plus focal/dim metadata; removed the pending fixture while retaining
and revalidating the referenced derivative; and rendered the expected dimmed
pixels through the real `AuroraSmsRoot` Thread surface in a debug-only host with
synthetic conversation/index services. Fresh-process cleanup restored the
post-reconciliation managed-file-name and persisted-grant-count baselines. The
exact committed runner passed independently twice, force-stopping prepared
target PIDs 16995 and 17370, and preserved the target APK, SMS-role-holder
string, and all seven recorded permission states.

That result is not a production `MainActivity` launcher-renderer journey, a
real provider-backed SMS conversation, UI Apply/Reset, Photo Picker or SAF,
source-revocation, `global_thread`, physical/OEM/performance, or
low-memory/background/in-flight process-death evidence. `MainActivity` is used
only to expose the exact live process to the host; the force-stop occurs after
import, Room assignment, managed-file publication, and checkpoint commit. The
solid fixture proves dimmed rendering, while focal position is metadata-only;
persisted grants are compared by count rather than identity.

Source commit `826a20dbc3e965da8f269dde1351cf4d76d28f6c` adds an explicitly
gated API 36 AOSP Photo Picker cancellation journey using the accessibility
global Back action. With the emulator prepared under AuroraSMS's normal SMS-role
precondition, the exact method passed independently twice in 12s and 11s. It
opens the real `MainActivity` global-thread wallpaper editor, focuses
MediaProvider, invokes `GLOBAL_ACTION_BACK` without creating a synthetic picker
fixture or inspecting picker content, and proves the usable editor returns with
Pick enabled, Apply disabled, no loading/error, and the exact global assignment,
managed-file name set, and persisted-grant count unchanged. The physical runner
is pinned to its original exact method.

Source commit `37fd044df3b9b8933839b0f89f7018ec72b8ab1b` adds the narrow API
26 AOSP SAF-fallback cancellation counterpart. A separately constructed
instance of the same production AndroidX `PickVisualMedia(ImageOnly)` contract
produced `ACTION_OPEN_DOCUMENT`, requested `image/*`, and resolved to
DocumentsUI; the production `MainActivity` picker click independently focused
DocumentsUI, without intercepting the production outgoing intent. The test
selected no document and traversed no DocumentsUI content. Accessibility global
Back restored the usable global-thread editor with Pick enabled, Apply disabled,
no loading/error state, and the exact global assignment, immediate managed-file
name set, and persisted URI-grant identity/read/write/time set unchanged.

The exact preservation-checking runner passed independently twice in 2.751s and
2.754s, each reporting exactly `OK (1 test)`. Its per-emulator lock serializes
participating runner invocations, while point-in-time active/preinstalled
test-package checks reduce concurrent-run races; cleanup preserved the matching
target APK, legacy default-SMS setting, and all seven recorded permission
states, and left the instrumentation package absent. The final API 26 app-module
connected XML contains 76 tests, zero failures/errors, and three intentional
gated skips in 35.498s. The API 36 project connected matrix, 886-task offline
host/release/governance gate, and 15-task CycloneDX gate also passed for the
same source.
Emulator timings are not product-performance evidence.

Source commit `dd33737` adds a separate API 26 emulator-only real AOSP
DocumentsUI selection-lifecycle journey through the real `MainActivity`
global-thread editor and production `ACTION_OPEN_DOCUMENT` fallback. Its exact
local test root/document provides provider-open and preview evidence while the
journey validates the expected canonical bounded `content:` URI shape. Selected
preview is transient: editor Cancel, wallpaper Back, and Activity recreation
each preserve the empty assignment, managed-file ledger, persisted-grant
identity, and revision baseline. When the test document is made unavailable,
Apply reopens and rejects it without mutation or revision use; making it
available again and retrying creates exactly one managed final and advances the
revision exactly once. The resulting managed 40x20 raster still loads after the
source is made unavailable. UI Reset restores the empty assignment, file, and
persisted-grant baselines; the deliberately consumed revision remains baseline
plus one. The focused journey passed in 13.054s, 13.087s, and, after final
review, 12.952s; the independent no-selection cancellation runner passed in
2.65s. A later module-by-module XML/source-delta audit corrected the API 26
aggregate bookkeeping to 176 tests with five gated skips, rather than the
previously recorded 181/four; it ran in 1m53s. The API 36 aggregate completed
its current 176 tests with five skips in 1m23s, and both had zero failures. The
886-task host/release/privacy gate
passed in 19s, the 15-task CycloneDX gate passed in 8s, and the production debug
APK for this source is 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This new proof does not capture the raw outgoing production intent/result, prove
temporary URI-grant revocation, uninstall or remove the provider, exercise a
readable source-byte/content mutation or a cloud/blocking provider, cover target
loss or stale CAS, test configuration
variants beyond Activity recreation, or cover background/low-memory/in-flight
process death. The provider remains installed while only its exact document's
availability is toggled. Production-launcher/real-provider Thread rendering,
API 27-32, physical SAF-fallback/selection behavior, broader OEM
picker behavior beyond the recorded Pixel 8 Photo Picker journey, performance,
an explicit picker Cancel control, and cold restart remain open. The complete
compound Photo Picker/SAF lifecycle, import/export, navigation variants, GIF lifecycle, carrier
coverage, full accessibility/form-factor coverage, and approved canonical
artwork also remain Phase 4 or release follow-on gates. Artwork is still blocked
on the exact written publication/derivative/distribution terms in
`docs/ARTWORK_CATALOG.md`. AuroraSMS is therefore not complete or gold yet.

Source commit `65fc6552a877403523e499b457fdf015aaf6f753` adds a third
separately gated API 26 DocumentsUI journey through the selection runner's new
`stale-apply` mode; the original selection journey remains its default. After a
real selection, direct delivery of the production open-conversation intent to
`MainActivity` transitions from the pre-Apply Inbox editor to Thread and
dismisses both editors. Returning to Inbox and reopening the empty-baseline
global editor leaves Apply disabled, does not reopen the source, and preserves
the exact assignment, revision, persisted-grant identity, and no-follow
managed-file ledger.

A fresh real selection then captures the empty revision while a controlled
production-controller write commits one newer global winner. Stale UI Apply
reopens the SAF source, shows the exact stale-assignment error, preserves the
winner's assignment/revision/file/persisted-grant state and managed load, and
deletes its unreferenced candidate. UI Reset removes only the controlled winner
and restores the empty assignment/file/persisted-grant baseline while leaving
the one consumed revision. A host controller test independently proves late
repository-`StaleWrite` ordering, the second authoritative reference read, and
created-candidate deletion.

The corrected focused journey passed in 8.597s and 8.513s; the final
revision-hardened pass took 8.667s. Selection and cancellation regressions
passed in 13.012s and 2.692s. Final API 26/API 36 aggregates passed in 1m49s and 1m23s;
JUnit XML reports 177 tests/six skips and 176 tests/five skips respectively,
with zero failures/errors. The 886-task host/lint/release/privacy/license gate
passed in 21s. CycloneDX 1.6 passed in 8s with 441 components and 442 dependency
nodes. The production debug APK remains 13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This proves direct pre-Apply `onNewIntent` route disposal and one global
assignment-CAS conflict only. It does not prove an end-to-end system
notification/PendingIntent launch, in-flight Apply cancellation, verified-
conversation identity loss, or temporary URI-grant revocation; the test checks
persisted grants only. Readable source-byte/content mutation, provider
revocation/removal/replacement, cloud/blocking behavior, API 27-32,
physical/OEM SAF, explicit picker Cancel,
broader recovery, accessibility, form-factor, performance, artwork, carrier,
compound lifecycle, and gold gates remain open. Only synthetic emulator fixtures
were used, and AuroraSMS is still not complete or gold.

Source commit `12939eea321e8eb6a9a173a82cab2dfd245b64e5` adds a fourth,
separately gated API 26 AOSP journey through the selection runner's new
`notification-pending-intent` mode. On an awake, unlocked emulator, production
notification channels are initialized before a complete channel snapshot. The
real `MainActivity`/DocumentsUI/AndroidX SAF path then stages the exact synthetic
local PNG without Apply, and the production `messageNotifier` posts a fixed
synthetic `IncomingMessageNotification`. The test identifies the exact system
notification by package, UID, tag, ID, message channel, category, private
visibility, timestamp, clearable/auto-cancel flags, absence of actions, Aurora
activity content `PendingIntent`, public version, sender, and body.

A real touchscreen swipe expands the AOSP shade, and a tap on that controlled
SystemUI row/body delivers its production content `PendingIntent` to the same
warm `MainActivity`. The exact synthetic Thread ID/action is consumed; both
wallpaper editors are dismissed and the staged source is not reopened.
Assignment, revision, managed-file ledger, and persisted-grant snapshots remain
unchanged; auto-cancel restores the active-notification baseline; and the full
post-bootstrap channel snapshot, including each channel's DND-bypass setting,
remains exact. Returning to Inbox and reopening the global editor again shows
disabled Apply, no load or
error, and no staged selection.

The final focused pass took 6.927s after review passes of 7.170s, 6.961s, and
6.797s, reporting exactly custom status 44 with
`auroraSafNotificationPendingIntentResult=pass` and `OK (1 test)`. Selection,
stale-Apply, and cancellation regressions passed in 12.879s, 8.595s, and
2.745s. Final API 26/API 36 connected gates were `BUILD SUCCESSFUL` in 1m51s
and 1m26s across 456 tasks each; XML reports API 26 app 80 tests/seven skips,
API 26 project 179/eight, and API 36 project 176/five, all with zero failures or
errors. The 886-task host/lint/release/privacy/license gate passed in 12s with
26 executed and 860 up-to-date; CycloneDX 1.6 passed 15 tasks in 8s with 441
components and 442 dependency nodes. The unchanged production debug APK is
13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This is one synthetic, warm-task, pre-Apply API 26 AOSP notification-shade
journey. It does not originate from a real carrier/provider/receiver/orchestrator
incoming message, and its fixed Thread ID is neither provider-backed nor a
verified conversation identity. Cold or absent tasks, background/lockscreen/
process-death delivery, raw `PendingIntent` action/extras/flags, API 27+,
notification-permission denial, OEM or physical shades, reply/group/privacy/
alert/new-channel behavior, raw picker results, temporary URI-grant lifetime,
in-flight Apply, source mutation/provider removal/cloud behavior, nonempty
baselines, broader acceptance, and gold gates remain open. The runner remains
backward-compatible, bounded, fail-closed, and collision-safe; its status-45
cleanup-only instrumentation is reserved for abnormal recovery and was not run
by the passing journey. AuroraSMS remains incomplete and not gold.

Source commit `f41dfd4f0552ed249b2fbda65ec2e3b164842c23` adds a separate,
owner-gated production incoming-SMS cold-notification journey. The isolated
runner
`./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh` owns a
disposable overlay of the dedicated non-Play API 26 GSM AVD
`AuroraSMS_SMSRX_API26`. It injects exactly one emulator-modem PDU through the
protected production `SMS_DELIVER` receiver, verifies the resulting Telephony
provider write, `COMPLETE` replay journal, verified-conversation identity, and
subscription-dependent optional reply target, then requires the production
notifier to post one `PRIVATE` notification.

The live SystemUI `StatusBarNotification` (SBN) record must retain generic
privacy text and a generic `publicVersion`, exclude the controlled sender/body,
expose the exact Aurora activity content `PendingIntent`, and match the expected
action contract. After a same-UID kill of the exact receiver-process PID, the
notification must
remain posted. A real touchscreen opens the AOSP shade and taps that exact row;
a distinct cold app PID starts `MainActivity` on the provider-backed verified
Thread, and auto-cancel removes the notification. The verify phase then restores
the exact empty owned delivery and notification state, and the runner discards
its emulator overlay. Two consecutive final passes took 47.610s (prepare
1.083s, verify 0.554s) and 42.839s (prepare
0.987s, verify 0.549s).

At the same source hash, final API 26/API 36 root connected gates were
`BUILD SUCCESSFUL` in 1m45s and 1m19s. Project module XML totals were 180 tests/
nine intentional skips and 177/six, respectively, with zero failures/errors;
the new owner-gated test was discovered and intentionally skipped outside its
isolated runner. The 886-task host/release/privacy/license gate passed in 18s
with 32 executed and 854 up-to-date, and CycloneDX passed in 7s with 441
components and 442 dependency nodes. The production debug APK remains
13,993,426 bytes with SHA-256
`5081f67f55d16bb78a0c22bc6e735919184c2279252213c60c314a506104b0c3`.

This is one synthetic API 26 emulator-modem route, not carrier-network evidence.
It does not cover a physical or OEM notification shade, lockscreen behavior,
API 27+, notification-permission denial, grouped or multiple messages, inline
reply execution, MMS, a nonempty provider baseline, OEM/carrier matrices, or
the broader artwork, accessibility, form-factor, performance, complete-
lifecycle, and gold gates. AuroraSMS remains incomplete and not gold.

Source commit `ec3e10299953253b1330d9440a07df981ed9a1af` adds focused API 33+ notification-
permission recovery without making message access depend on system alerts.
`app/src/main/kotlin/org/aurorasms/app/MainActivity.kt` keeps an explanation
visible across Inbox and Thread whenever AuroraSMS is messaging-eligible and
`POST_NOTIFICATIONS` is denied. Its action requests the runtime permission
while Android still allows another request; after a recorded final denial it
opens AuroraSMS's exact app-notification Settings page. Focused coverage lives
in `app/src/test/kotlin/org/aurorasms/app/NotificationPermissionRecoveryActionTest.kt`
and
`app/src/androidTest/kotlin/org/aurorasms/app/NotificationPermissionNoticeTest.kt`.
`app/src/test/kotlin/org/aurorasms/app/message/IncomingMessageOrchestratorTest.kt`
also proves that `NotificationsDisabled` still completes the incoming-delivery
journal handoff: replay is a duplicate, with no second provider insert or
notification attempt.

The same snapshot extends
`scripts/run-emulator-incoming-sms-cold-notification-smoke.sh` with an isolated
API 36 journey:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey notification-denied
```

On the owned disposable `AuroraSMS_SMSRX_API36` AVD, assigning the SMS role
started AuroraSMS's protected default-role-change receiver and cleared package
stopped state. A real Settings master-switch action then produced a denied
`POST_NOTIFICATIONS` state with
`USER_SET`. The Inbox action then opened the real permission dialog; its final
denial produced `USER_FIXED`, and the next action opened the exact AuroraSMS
notification Settings page. From an exact cold, taskless baseline, the runner
sent one documented synthetic SMS through the emulator modem. The independently
permissioned test APK captured the resulting raw PDU, while production handled
the protected `SMS_DELIVER`. Verification tied that PDU to the exact replay-
journal key and decoded sent timestamp, the single Telephony provider row and
its provider/thread IDs and timestamps, and a `COMPLETE` journal state. No
Aurora `StatusBarNotification` appeared. A later cold launch showed the message
and missed-alert notice in Inbox, and the provider-backed Thread remained
readable with the notice still present. The exact cleanup completed on two
consecutive passes.

The unchanged default API 26 raw fixed-PDU journey also passed after this work.
For the exact source commit, the full API 36 and API 26 connected matrices
passed 456 Gradle tasks each in 1m21s and 1m49s with zero failures. The complete
offline host/lint/release/privacy gate passed 883 tasks in 1m19s; the combined
license-report and CycloneDX gate passed 18 tasks in 8s. The final debug APK is
13,993,426 bytes with SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.
This evidence does not complete API 33 through 35, a physical device, OEM or
carrier behavior, lockscreen delivery, grouped or multiple messages, inline-
reply execution, MMS, or the broader release matrix. AuroraSMS remains
incomplete and not gold.

Source commit `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0` adds a
same-sender two-message receive-continuity journey to
`scripts/run-emulator-incoming-sms-cold-notification-smoke.sh --journey multiple-message`.
The host test
`twoDistinctSameSenderDeliveriesPersistAndNotifyOnceEachAcrossReplay` also
proves that two distinct delivery fingerprints from one sender insert two
provider messages in one conversation, invoke the notifier once per delivery,
and replay as duplicates without another insert or notification attempt.

On the owned disposable API 26 `AuroraSMS_SMSRX_API26` overlay, the runner
injects two distinct fixed single-part GSM PDUs exactly once in sequence. The
first provider row, `COMPLETE` journal, and private conversation SBN stabilize
before the second injection. The second delivery receives a distinct provider
ID and journal while preserving the same thread, subscription, background
process, notification tag, and notification ID. The sole active SBN's
`mUpdateTimeMs` strictly advances and stabilizes; generic notification text and
`publicVersion` remain private, and neither synthetic body nor sender appears
in the notification dump or AOSP shade.

After exact receiver-process death, the updated notification survives. A real
AOSP shade tap starts a distinct cold `MainActivity` process on the provider-
backed verified Thread, which contains exactly two `aurora-message-bubble`
nodes, each expected body once and in delivery order. Auto-cancel follows.
Verification covers two provider rows, two `COMPLETE` journals, two
subscription-backed reply targets, two indexed messages with unread count two,
both timeline entries, and exact cleanup. Two independent owner-journey passes
completed successfully. The unchanged API 26 single-message and API 36
notification-denied journeys also passed.

At `57ec7cf093e24f4792a242f53d6cf13f2d0e1ff0`, the definitive API 26
and API 36 connected matrices finished 97 and 91 tests with zero failures in
47s and 51s. The complete 886-task host,
lint, release, benchmark, privacy, dependency, permission, APK-content, and
license gate passed in 19s; CycloneDX passed 15 tasks in 7s. The unchanged
production debug APK is 13,993,426 bytes with SHA-256
`876dfb17eabb95f28454998c2cd26c7d463c7212219cdd32ba4484adb37a60fc`.

This closes only sequential same-sender, single-part SMS notification-update
continuity on one API 26 AOSP emulator. Multipart SMS, other-conversation
notification grouping/summary behavior, alert/sound/vibration counts, API 27+,
physical/OEM shade, carrier-network and lockscreen behavior, inline-reply
execution, MMS, nonempty-provider baselines, broader acceptance, and gold
remain open. AuroraSMS is incomplete and not gold.

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
