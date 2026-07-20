<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AuroraSMS

AuroraSMS is an original, clean-room Android SMS/MMS application. Its privacy
baseline is local-only: the application does not request `INTERNET` or
`ACCESS_NETWORK_STATE`, has no accounts, ads, analytics, or trackers, and uses
Android's Telephony provider as the authority for messages.

## Current status

Phase 7 release hardening is active. Implementation commit
`d163811653e69ec8e1ad505a454b51770180ef73` aligns the app and CycloneDX
version at `0.6.10-phase6`, adds a data-minimizing security policy, deterministic
checksum/reproducibility helpers, release instructions, localized store copy,
and valid F-Droid metadata that remains disabled while the app is pre-release.
Two independent Git clones of that exact commit produced byte-identical R8
unsigned release APKs and AABs. The reproducible APK SHA-256 is
`acd1517b5c01a7c14be6d2fce06cb9dbe44276f6db51693bf2f31253e8d78ee6`;
the AAB SHA-256 is
`349ea4f6a1be6f348cbd54c64bdf06e3fe56f7bc13f1eaef2aa4d807d7a86b1b`.
All 653 host tests and the 986-task governed aggregate pass. The exact release
and gold checklist is in [the Phase 7 plan](docs/PHASE_7_RELEASE_PLAN.md).
Signing, a complete nonempty physical-provider scan, physical outgoing/incoming
MMS and OEM/carrier/accessibility/performance/Android Auto
acceptance, and the final F-Droid build recipe remain open. AuroraSMS is not
gold.

Implementation commit `d052db0` gives the current-candidate Pixel history gate a
reviewed [physical provider-completion protocol](docs/PHYSICAL_PROVIDER_COMPLETION_PROTOCOL.md)
and a governed fail-closed runner. It requires the owner to select AuroraSMS
through normal Android UI, never changes the SMS role or permissions itself,
and emits only aggregate generation/checkpoint/index consistency counts. A
paused first-history scan still resumes its durable cursor, while recovery from
a previously complete state now starts a fresh full generation so changes made
under another default SMS app cannot hide beyond the bounded head. The
runner and its parser are implemented, but the physical scan and private
Inbox/Thread/search/old-result confirmation remain unexecuted until the Pixel
is connected and the owner opens that explicit provider-read window.
The protocol parser, fail-closed emulator refusal, role-recovery host coverage,
and complete API 36/API 26 connected matrices pass with zero failures or
errors; those local checks do not substitute for the unexecuted physical gate.

Outgoing MMS implementation commits `7a45033`, `a71c623`, `0b27160`, and
`1e2344b` complete ADR 0026's synthetic direct/group vertical. Commit
`0d93626` adds schema-14 app-private persistence for the sanitized attachment
bytes owned by each exact draft. Exact groups,
multi-unit text, subjects, captions, images, and image-only drafts submit as one
MMS operation and can never fan out as SMS. Photo Picker sources are bounded,
decoded defensively, and re-encoded as metadata-free JPEG/PNG without retaining
their URI, filename, grant, or metadata. The existing durable draft,
provider-first preparation, submission journal, and authenticated callback
protocol now owns SMS or MMS explicitly. Focused host, Room, sanitizer, and UI
tests pass on API 26/API 36 where applicable without a live provider, role
change, or carrier send. Attachment sets survive Room close/reopen and Activity
recreation; a missing, corrupt, or unavailable attachment authority disables
Send rather than silently downgrading the intended MMS. All 652 host tests and
the complete 978-task governed
host/lint/R8/benchmark/privacy/dependency/license aggregate pass; standalone
release AAB and CycloneDX 1.6 generation pass as well. At source commit
`0d93626`, the complete connected matrices passed on API 36 and API 26 with
448 and 442 enumerated tests, respectively, 10 and 13 intentional protocol
skips, and zero failures or errors. Implementation commit `b29b5ba` adds an
explicit API 36 host-force-stop journey that twice restores the production-Room
draft and identical image bytes in a fresh process, renders the attachment in
the real root, routes one exact synthetic MMS operation, and proves fresh-
process parent/cascade cleanup while preserving the APK, SMS role, and
permissions. The follow-on attachment matrices passed 449 API 36 tests with 11
intentional skips and 443 API 26 tests with 14 intentional skips, with zero
failures or errors. Implementation commit `f2f4f5c` then adds an isolated,
preservation-safe incoming-MMS host-force-stop journey on both API 26 and API
36. A completed synthetic download/provider result survives into a fresh
process as exactly one pending notification without another platform download;
a third process acknowledges it and removes the exact journal owner and staged
PDU. The journey passed independently twice per API. The complete follow-on
matrices pass 450 API 36 tests with 12 intentional skips and 444 API 26 tests
with 15 intentional skips, with zero failures or errors. Physical carrier/OEM
interoperability and broader in-flight process-death coverage remain open.

Incoming MMS implementation commit
`260fd18522a31b7bce4c4e6dbfbac99c9c83fecd` completes ADR 0025's
metadata-only crash journal, duplicate WAP suppression, exact staged-file
callback, bounded RetrieveConf projection, atomic idempotent provider write,
group-aware notification acknowledgement, and startup replay without carrier
resubmission. Eight ordinary synthetic end-to-end cases pass on both API 26 and
API 36; provider failure is deferred with the authenticated PDU retained. The
separately gated `f2f4f5c` cold-process journey additionally proves durable
provider-to-notification replay and exact acknowledgement cleanup across two
host force-stops on both API levels. No live provider read, role change,
carrier download, or message-content capture was used. Physical carrier receive
remains open.

The 2026-07-20 Phase 6H implementation identifies as `0.6.10-phase6`
(`versionCode` 21) and implements ADR 0023. AuroraSMS now advertises Android
Auto notification and SMS capability, publishes one bounded chronological
`MessagingStyle` history per conversation, and exposes background semantic
Reply and invisible Mark as read actions. New replies enter the existing
durable role/recipient/subscription/replay/transport boundary through a
non-exported service. Mark as read revalidates the exact incoming SMS generation
before updating only that conversation through that row, and exact notification
and reminder cleanup happens only after provider success.

A two-second, 64-slot in-process mirror covers Android 8's asynchronous active-
notification publication without becoming message storage. The API 26 matrix
caught the original rapid-arrival history loss and the corrected exact-
generation platform test now passes on both API 26 and API 36. All 636 host
tests, the complete 977-task offline release/privacy gate, and 399 retained
connected tests on each API pass with zero failures or errors. Release APK/AAB,
benchmark, permission/APK-content, license, clean-room, dependency, and
CycloneDX 1.6 gates are green. Exact implementation commit `70552cf` produced
the 15,504,195-byte debug APK with SHA-256
`9aed671c7b1ed495264a48748ccbdacd74ba720ee78626d815b6b924aca835ed`.
It installed and hash-matched on both emulators while preserving
`com.android.messaging` as their default SMS app, and AuroraSMS was left
force-stopped. No live provider content, role transition, or carrier action
participated. A real Android Auto/DHU surface, physical lockscreen/OEM behavior,
carrier reply, and incoming/group MMS remain open; AuroraSMS is not gold.

The 2026-07-20 Phase 6G app integration identifies as `0.6.9-phase6`
(`versionCode` 20). Inbox now opens a searchable Settings screen whose encrypted
Backup & restore journey uses Android `CreateDocument`/`OpenDocument`, ephemeral
passphrases, private authenticated staging, an exact pre-mutation summary, and a
separate confirmation action. Startup recovery and staging reconciliation must
both succeed before either file action appears. Backgrounding or navigation
invalidates pre-mutation restore authority, while a confirmed restore remains a
serialized, journaled provider operation. Historical Draft/Outbox/Queued rows
can restore only as inert Failed history and are never sent or scheduled.

All 628 host tests, the complete 977-task offline release/privacy aggregate, and
390 connected tests on each of API 26 and API 36 passed with zero failures or
errors. The 21-test backup module and six focused Settings/backup UI tests passed
on both APIs; the real Inbox-to-Settings route passed on both as well. R8 release
APK/AAB, benchmark, permission/APK-content, license, and CycloneDX 1.6 gates are
green. Exact pushed commit `2c3cfb0` produced the 15,384,521-byte debug APK with
SHA-256 `8652064112772bbdaaf62b3c641bddf9001081d8c81a2105c6f12b819bb2edab`;
it installed and hash-matched on both emulators while preserving
`com.android.messaging` as their default SMS app. No live message content, SMS
role transition, or carrier boundary participated. The Pixel was not attached,
so physical/OEM document-picker acceptance remains open and AuroraSMS is not
gold.

The 2026-07-19 Phase 6F source identifies as `0.6.8-phase6` (`versionCode` 19)
and implements ADR 0021's bounded one-person voice memo. Microphone access is
excluded from onboarding and requested only after Record. Capture is visibly
timed, foreground-only, limited to 60 seconds/512 KiB in private no-backup
storage, and requires separate Stop/review and Send actions. Cancel,
backgrounding, and Thread exit remove the owned staging file.

One pinned and noticed Apache-2.0 official-AOSP `SendReq` subset composes only
SMIL, optional signature text, and MPEG-4/AAC-LC audio. Parts-first provider
persistence, exact ownership/status transitions, a content-free checksummed
crash journal, non-retryable ambiguous submission, and authenticated exact
callbacks guard the carrier boundary. Golden/corpus, host recovery, Compose,
fake-provider, and real virtual-microphone tests are green across API 26/API 36
where applicable without reading live provider content or sending carrier
traffic. All 601 host tests, the complete 888-task offline aggregate, and 362
connected tests on each of API 26 and API 36 passed with zero failures or
errors. Release bundle and CycloneDX 1.6 SBOM generation passed. The exact debug
APK installed and hash-matched on both emulators while preserving their existing
non-Aurora SMS role. The Pixel was unreachable after the editor crash, so the
Phase 6F physical handoff remains open. Group/general/incoming MMS and carrier/
OEM acceptance also remain open, and AuroraSMS is not gold.

The 2026-07-19 interrupted-history repair identifies as `0.6.7-phase6`
(`versionCode` 18) and implements ADR 0020. When a newer refresh is incomplete,
Inbox and Thread now present all best-known rows retained across durable index
generations instead of hiding rows not yet revisited by the partial scan. The
incomplete notice explicitly says cached history may not reflect recent provider
changes, and every exact-identity action remains disabled until full provider
verification. On the Pixel this addresses the observed split between 2,100 rows
in the newest partial generation and 5,226 cached messages across 73 conversation
projections. Aurora remains unable to refresh provider data while Fossify holds
the default SMS role; one explicit Aurora-default foreground session is still
required for authoritative complete-history acceptance.

The 2026-07-19 Phase 6E source identifies as `0.6.6-phase6` (`versionCode` 17)
and implements ADR 0019's local explainable spam and provider-preserving
blocking. Saved contacts, short codes, and alphanumeric senders are trusted by
the automatic rules. Only an unknown conventional phone sender with a link,
urgency language, and a sensitive request receives an automatic warning. Users
can independently mark spam/not-spam and block/unblock; no rule hides or
deletes provider messages. Explicit blocks suppress only Aurora's incoming
notification, reply-target, and reminder effects after the provider row is
stored. Block-store failures fail open.

Room schema 12 retains at most 256 content-free user decisions addressed by
purpose-separated hashes of exact verified identities. The dedicated Spam &
blocked route revalidates every row and exposes recovery actions. All 587 host
tests passed inside the complete 886-task offline host/lint/R8/benchmark/privacy/
dependency/license aggregate. The complete API 36 matrix passed 339 tests with
10 intentional skips; API 26 passed 342 with 13. Release bundle and deterministic
CycloneDX 1.6 SBOM generation passed. The exact debug APK installed and
hash-matched on the Pixel 8 and API 36 emulator. Both retained their existing
non-Aurora SMS role and denied Aurora's messaging/notification permissions; no
Activity launch was issued and the Pixel was left force-stopped. Phase 6F and
later release work remain, so AuroraSMS is not gold.

The 2026-07-19 large-history follow-up identifies as `0.6.5-phase6`
(`versionCode` 16). A default-SMS role transition now pauses and resumes the
same clean, checkpointed initial-history generation instead of falsely marking
it as a provider mutation and restarting from the newest messages. Actual
content-observer and external-provider signals remain conservative dirty
boundaries. Incomplete Inbox and Thread screens prominently show the
content-free number of messages checked and ask the user to keep AuroraSMS open
and default until verification completes. ADR 0020 subsequently changed partial
presentation to show all best-known cached generations rather than hiding cached
older rows.

The preceding Phase 6D source identifies as `0.6.4-phase6` (`versionCode` 15)
and implements bounded global and verified-conversation message signatures.
The signed text, exact SMS-part impact, and group-MMS boundary are visible
before Send. Immediate, delayed, and scheduled sends freeze the reviewed
signature in Room schema 11, and corrupt signature settings pause new sends
instead of silently changing outgoing content. A signature can never turn a
group into individual SMS messages or bypass the existing one-person,
one-part transport gate.

All 579 host tests passed inside the complete 886-task offline host, lint,
R8-release, benchmark, privacy, dependency, APK-content, and license aggregate.
The complete API 36 matrix passed 333 tests with 10 intentional skips; API 26
passed 336 with 13. The exact debug APK installed and hash-matched on the Pixel
8 and API 36 emulator without launching AuroraSMS or changing either SMS role.
The Pixel retained Fossify as default and denied Aurora provider reads. A
content-free follow-up found four abandoned partial generations; the latest had
checked 2,100 rows while the rebuildable cache retained 5,226 rows across 73
conversation projections. The role-resume fix addresses that exact restart
pattern, but the owner's explicit default-SMS approval is still required before
physical complete-history acceptance. No carrier message was sent. Phase 6E
and later release work remain, so AuroraSMS is not gold.

The 2026-07-19 Phase 5G source identifies as `0.5.6-phase5` (`versionCode` 10)
and implements ADR 0014's shared no-group-SMS boundary. Every `SmsSendRequest`
is structurally limited to one canonical recipient. Existing Thread, delayed,
and scheduled paths recheck a one-person verified identity, while Android
respond-via routes a group into exactly one MMS request. MMS failure is never
retried or fanned out as individual SMS messages. The current group composer
remains visibly disabled until full group MMS is implemented. This closes the
current-path fan-out invariant, not the broader MMS codec/provider/carrier
matrix; AuroraSMS remains incomplete and not gold.

Phase 5G local acceptance is green. All 552 host tests passed inside the full
debug/R8-release/benchmark, lint, privacy, dependency, APK-content, and license
aggregate. The complete API 36 matrix passed 309 tests with 10 intentional
environment-gated skips, and API 26 passed 312 with 13; both executed 299 tests
with zero failures/errors. No test used a real destination or carrier transport.
The Pixel was not enumerated by ADB at handoff, so its safe install remains
pending.

The 2026-07-19 Phase 5F source identifies as `0.5.5-phase5` (`versionCode` 9)
and adds ADR 0013's guarded permanent deletion for one exact SMS/MMS row or an
entire provider Thread. Message deletion has one explicit confirmation;
whole-conversation deletion has a stronger two-step confirmation. Both use a
fixed five-second Undo window backed by bounded, content-free Room schema 10
state and a private ID-only recovery alarm. Provider fingerprints or Thread
count/latest-row metadata, exact draft revision, role, conflicts, clock
continuity, and lateness are revalidated before mutation. Crash recovery only
inspects an ambiguous commit and never blindly repeats a delete. Provider
success removes the operation and any exact old Thread draft without claiming
later recovery; no recycle-bin surface or state exists.

Phase 5F local acceptance is green. All 546 host tests passed inside the full
886-task debug/R8-release/benchmark, lint, privacy, dependency, APK-content,
and license aggregate. The complete API 36 matrix passed 308 tests with 10
intentional environment-gated skips, and API 26 passed 311 with 13; both
executed 298 tests with zero failures/errors. These tests use synthetic
provider state. They did not read or delete live messages. Physical install and
metadata-only migration/launch acceptance remains pending because the Pixel was
not enumerated by ADB at Phase 5F handoff. Physical deletion/process-death and
broader carrier/OEM gates remain open, so AuroraSMS remains incomplete and not
gold.

The 2026-07-19 Phase 5E source identifies as `0.5.4-phase5` (`versionCode` 8)
and adds ADR 0012's durable short send delay and truthful pre-submission Undo
to the existing verified one-person, one-part SMS composer. Immediate sending
remains the default; the user may choose 1, 3, 5, or 10 seconds. Room schema 9
owns a bounded content-free delay operation, the exact durable draft remains
the sole message-content authority, and a process-local timer plus private ID-
only alarm survives process death. Dispatch revalidates clock continuity,
lateness, identity, role, exact remembered SIM, draft revision, and segment
count. Duplicate alarms are compare-and-set idempotent; reboot/clock changes,
late recovery, lost role/SIM, arming failure, or interrupted handoff pauses for
review without retry. Undo is accepted only before durable dispatch ownership.
Physical lifecycle and carrier gates remain open, so AuroraSMS remains
incomplete and not gold.

Phase 5E local acceptance is green. All 538 host tests passed inside the full
886-task debug/R8-release/benchmark, lint, privacy, dependency, APK-content,
and license aggregate. The complete API 36 matrix passed 302 tests with 10
intentional environment-gated skips, and API 26 passed 305 with 13; both
executed 292 tests with zero failures/errors. The exact debug APK was installed
on the connected Pixel 8 with data preserved and schema 9 verified. Fossify
remained the default SMS app, Aurora's SMS/notification permissions remained
denied, and cold launch produced no AuroraSMS error. The secure keyguard was not
bypassed, no messages were read, and no carrier send was attempted.

The 2026-07-19 Phase 5D source identifies as `0.5.3-phase5` (`versionCode` 7)
and adds bounded scheduled sending for the existing verified one-person,
one-part SMS composer. Room schema 8 owns one content-free schedule per exact
draft/Thread, alarm intents carry only its local ID, and the due path revalidates
the draft, participants, default role, selected active SIM, segment count, and
clock before entering the existing durable send coordinator. Exact-alarm access
is optional: the UI labels an inexact schedule “may send late,” offers Android's
special-access screen, and retains a distinct inexact safety alarm. Reboot/time
changes, duplicate alarms, removed SIMs, and interrupted handoff fail closed to
visible review state. Physical reboot/Doze/SIM/carrier/OEM acceptance is still
open, so AuroraSMS remains incomplete and not gold.

The Phase 5C source identifies as `0.5.2-phase5` (`versionCode` 6)
and adds an explicit, durable SIM choice for a verified one-person
conversation. Room schema 7 stores only a purpose-separated participant-set
hash, provider-Thread hint, subscription ID, revision, and timestamps. The
composer and coordinator re-read that authority before reservation; if the
remembered SIM is unavailable, Send stays disabled until the user explicitly
chooses an active replacement. AuroraSMS never silently switches this path to a
different SIM.

Implementation commit `7c9d848` hardens AuroraSMS's durable incoming-message,
notification-generation, and inline-reply recovery boundaries. Its dedicated
API 26 AOSP `inline-reply-permission-denied` journey passed twice independently
from fresh disposable emulator overlays, including a real SystemUI RemoteInput
submission, synchronous pre-transport `SEND_SMS` denial, one durable consumed
claim, no outgoing provider row, one private generic failure alert, a cold
failure-alert route, and exact cleanup. This closes only that denied-reply path;
AuroraSMS remains incomplete and not gold.

The current follow-on durability slice gives every outgoing SMS path exactly
one durable pre-submission owner. Notification inline reply uses its caller-
owned reply-operation store and high operation-ID namespace; Android
`RESPOND_VIA_MESSAGE` uses ordinary low operation IDs and the transport-owned,
private, content-free outgoing journal. Provider rows begin as app-owned,
known-unsent `FAILED` rows with a staging sentinel. Durable `PREPARED` ownership
precedes one conditional arm, and durable `SUBMITTING` precedes the platform
call. Inherited `SUBMITTING` is conservatively changed to
`SUBMISSION_UNKNOWN` and is never resubmitted. Generic reply-failure alerts are
keyed by conversation plus operation, so later success can cancel only its own
alert.

The 2026-07-19 Phase 5B worktree identifies as `0.5.1-phase5` (`versionCode` 5)
and retains Phase 5A's first intentional Thread composer send behind a
deliberately narrow gate: an existing provider-backed Thread, one completed
verified participant, its associated active SMS-capable subscription, and exactly one
Android-calculated text SMS unit. New/external compose, groups, MMS, multipart
text, delivery reports, SIM fallback, schedules, delay, Undo Send, and automatic
retry remain disabled.

Its Room schema-5 active operation and schema-6 acknowledged-callback receipt are
bounded and content-free; the exact Room draft remains the message-text authority.
App-private saved state is only an exact-base
restoration hint, hidden until Room validation and discarded when stale. An
atomic writer freeze drains every accepted edit and captures the one durable
snapshot used for send. Caller-owned `PREPARED` and `SUBMITTING` checkpoints are
awaited with repeated role/generation fences and a final role check immediately
before the platform call. Once that snapshot is frozen, cancellation cannot
abandon the reserve-and-classify envelope. Transient typed storage failures use
bounded non-sending recovery, exact callbacks retain only their content-free
identity for bounded checkpoint retry, and Room observation resubscribes after a
recoverable failure. Recovery never sends, isolates deferred provider cleanup to
its exact operation so unrelated Threads stay usable, and lets duplicate exact
success callbacks resume idempotent settlement. Commit-ambiguous terminal draft
clearance and “Keep as draft” acknowledgement are re-read and boundedly verified;
deduplicated process-local signals then clear or reopen the composer exactly once.

After durable sent-callback proof or durable pre-boundary known-unsent proof,
exact provider `Success` dispositions `APPLIED`, `ROW_ABSENT`, and
`OWNERSHIP_CONFLICT` are terminal; absence and conflict perform no foreign
mutation, while provider access failure defers. Explicitly acknowledging
submission uncertainty preserves the draft and warns that another send may
duplicate it. Schema 6 atomically transfers only the old callback identity and
exact provider binding into a separate receipt. A late exact success/failure is
checkpointed before its old provider row is reconciled, survives process death,
never clears the preserved draft, and never resends. Phase 5 automated work sends
no real carrier SMS, closes no physical/SIM/OEM/carrier gates, and does not make
AuroraSMS complete or gold.

Phase 5A final-source local acceptance is green on the API 26 and API 36 AOSP
emulators. The complete offline host/release/privacy/license aggregate was
`BUILD SUCCESSFUL` in 1m27s across 886 tasks (90 executed, two from cache,
794 up-to-date); all 508 host JUnit results passed. `bundleRelease` passed 269
tasks in 7s, and CycloneDX 1.6 passed 15 up-to-date tasks in 7s with 441
components and 442 dependencies. The full API 26 connected matrix passed 291 tests with 13
intentional skips (278 executed), and the full API 36 matrix passed 288 tests
with 10 intentional skips (also 278 executed), with zero failures or errors.
Focused API 36 root-composer and external-compose-isolation tests each passed
1/1. These tests use fakes, synthetic data, or deliberately unavailable
production preconditions and do not send a carrier SMS.

The Phase 5A debug APK is 13,831,154 bytes with SHA-256
`d8e1dbd75fc4d4ea76c4ebe8d2abb4b6c70828707d9c2eb94cc4697d485d7d31`.
Artifact inspection confirms package/version identity, minimum API 26, target
API 36, `debuggable` only on the debug app, and no `INTERNET` or
`ACCESS_NETWORK_STATE` permission in app variants. The release APK is unsigned,
so it is verification output rather than an installable distribution. Only the
API 26 and API 36 emulators were attached for this acceptance; no physical
device, SIM, OEM, carrier, billing, roaming, sent/delivery, reboot, or live-send
process-death gate closes. This local evidence is frozen in implementation
commit `17fc421`; it is not a pushed-CI, physical-device handoff, release,
completion, or gold claim. The exact debug
APK did install and copy to the API 36 emulator with a matching device-side
SHA-256, then cold-launched successfully to the expected role-approval screen
without changing the role or SMS permissions. Exact commands, module totals,
and all artifact hashes are recorded in `docs/TEST_MATRIX.md`.

Phase 5B acknowledged-unknown cleanup is locally green as of 2026-07-19. The
exact source passed the 886-task host/release/privacy/license aggregate with 515
host tests, plus the complete API 36 connected matrix with 291 tests, 10
intentional skips, and zero failures/errors. These remain synthetic/emulator
results; they do not replace physical SIM, carrier, OEM, or live lifecycle
acceptance.

Phase 5C durable conversation-SIM selection is locally green as of 2026-07-19.
The exact source passed the 886-task host/release/privacy/license aggregate with
all 521 host tests, separate release-bundle and CycloneDX gates, and the full
456-task connected matrix on both API 26 and API 36. Authoritative XML reports
297 API 26 tests (13 intentional skips) and 294 API 36 tests (10 intentional
skips), with zero failures or errors. The debug APK is 14,740,845 bytes with
SHA-256 `e0d614e4a472d7416d299ba384824c756cc94486ee30ba9fc050e8f04180ece1`.
These are synthetic/emulator results: real dual-SIM removal/disablement,
carrier submission, scheduled sends, groups, MMS, and the wider physical/OEM
matrix remain open, so AuroraSMS is not complete or gold.

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

### Historical pre-Phase 5 incoming/reply durability evidence

The evidence through frozen commit `3d7182c` below predates the Phase 5A
composer worktree and remains a retained baseline; it is not Phase 5A
acceptance evidence.

Implementation commit `7c9d848` adds a durable reply-operation state machine,
checksummed reply-target and replay ownership, provider-status transition and
callback handling, exact incoming-notification generation ownership, and
checksummed incoming replay records with provider-content verification and
poison-entry quarantine. Recovery orders same-kind provider IDs before wall
clock time, defers unresolved outgoing `PENDING` rows, serializes role-loss
cleanup with exact notification cancellation, and lets accepted receiver work
continue in-process after the receiver lease times out while durable checkpoints
own later process-loss recovery.

Its owner-gated command is:

```shell
./scripts/run-emulator-incoming-sms-cold-notification-smoke.sh \
    --journey inline-reply-permission-denied
```

Each of two independent passes began from a fresh disposable API 26
`AuroraSMS_SMSRX_API26` overlay. One fixed modem SMS crossed the protected
production `SMS_DELIVER` receiver into one provider row, one complete replay
journal, one durable reply target, and one private reply-bearing conversation
notification. The runner killed that completed receiver process without
stopping the package, revoked only `SEND_SMS`, and proved the processless,
taskless boundary plus the original notification and reply `PendingIntent`
identity were unchanged. It then opened the real AOSP shade, exposed the unique
Reply action, entered and verified one fixed synthetic RemoteInput value, and
tapped Send exactly once; an uncertain submit is never retried.

That one tap started a distinct cold `InlineReplyReceiver` process without an
Activity task. The durable operation converged on one consumed replay claim and
one notified known-unsent operation; synchronous permission preflight rejected
transport before platform submission, so no outgoing provider row appeared.
The original conversation notification remained stable and exactly one private,
generic, body-free failure notification appeared. Bounded notification-dump,
shade, and log scans excluded the incoming body, sender, and reply text. A real
tap on the unique failure row started a fresh `MainActivity` process on the
exact provider-backed Thread, auto-cancelled only the failure alert, and
preserved the conversation notification. Exact teardown restored the empty
provider, incoming-journal, reply-target, reply-claim, reply-operation, index,
and notification baselines and restored `SEND_SMS`.

The complete offline host/lint/release/benchmark/privacy/dependency/permission/
APK-content/license gate passed 886 tasks, and the separate CycloneDX gate
passed. Focused durable-store, notification-generation, and incoming-journal
suites passed 43/43, 18/18, and 9/9 on both API 26 and API 36. The full API 36
connected matrix reported zero failures/errors with runner-discovered module
totals of app 135, notifications 22, telephony 24, state 43, index 31,
conversations 5, and benchmark 4. API 26 likewise reported zero failures/errors
with app 141, notifications 22, telephony 24, state 43, index 31, conversations
5, and benchmark 4; its retained XML reconciles 258 zero-failure results and 12
intentional gated skips to 270 runner-discovered cases.

The two former code residuals now have fail-safe implementation contracts. An
outgoing provider insert is atomically visible as `FAILED` with the Aurora
staging sentinel, not as sendable `PENDING`. After its one durable owner records
the exact provider identity as `PREPARED`, one conditional arm may consume the
sentinel and move only that row to `PENDING`; `SUBMITTING` is committed before
the irreversible platform call. A synchronous refusal or cancellation before
that call conditionally terminalizes only the exact Aurora-created row in an
allowed staging, armed, or terminal state. A missing row is safely retired; an
identity, creator, thread, or state conflict becomes a content-free quarantine
tombstone and cannot mutate a foreign or reused provider row. An inherited
`PREPARED` record retries that exact cleanup, while inherited `SUBMITTING`
becomes `SUBMISSION_UNKNOWN` and is never rearmed or resubmitted.

Notification inline reply is caller-owned by its private reply-operation store
and reserved high operation IDs. `RESPOND_VIA_MESSAGE` is transport-owned by a
separate private, content-free journal using ordinary low IDs. That journal
stores at most 128 operation/provider identities, part counts, states, and
times. Active `PREPARED` and `SUBMITTING` records are never evicted; only
`SUBMISSION_UNKNOWN` and known-unsent quarantine tombstones expire after seven
days. Full capacity rejects new transport-owned work instead of evicting active
ownership. A corrupt, noncanonical, or uncommittable journal globally fails
transport-owned submission closed. A transient cleanup failure for one exact
provider row remains scheduled for retry but does not, by itself, block an
unrelated send.

Pre-existing `PENDING` rows created by builds before this transport journal
have no exact durable journal record. Upgrade recovery intentionally does not
sweep or mutate them and does not claim to repair their status.

Generic failure alerts use operation-scoped tags and cancellation, and a crash
after success-side cancellation but before durable acknowledgement replays the
same exact keys. On first role-enabled recovery after upgrading from the
pre-operation-key alpha, AuroraSMS dismisses any still-active conversation-only
generic reply-failure alerts because they cannot be mapped safely to one
durable reply operation. Previously user-dismissed alerts are not recreated.
Message/provider state and durable late-callback ownership are unchanged; users
should verify those replies in the conversation. If legacy-alert enumeration
or cancellation fails, pending replay is deferred and recovery retries. A
migrated success record without its historical source-message identity cannot
safely cancel one exact incoming-notification generation; AuroraSMS cancels its
operation-scoped failure alert but leaves success acknowledgement pending rather
than guessing.

Final-source focused verification completed a 320-task host gate with telephony
75/75, core testing 22/22, and app 191/191, plus green lint and app/telephony
`androidTest` compilation. The transport-owned submission journal passed 7/7
on API 26 and 7/7 on API 36. The owner-gated real Telephony-provider contract
passed 1/1 on each API without invoking `SmsManager`; it covered exact staged
insert and arm, wrong-thread conflict preservation, idempotent terminalization,
an absent exact URI, and exact synthetic-row cleanup. Notification identity and
cancellation passed 29/29 on each API, including real `NotificationManager`
sibling preservation. A final disposable API 26 SystemUI
`inline-reply-permission-denied` journey passed with exact cleanup, after which
its overlay was discarded.

The final API 26 connected matrix was `BUILD SUCCESSFUL` in 1m51s across 456
tasks. Preserved console module roots record app 132 with 12 skips, benchmark 3 with
one skip, notifications 29, telephony 31, state 43, index 31, and conversations
5: 274 total tests, 13 intentional skips, and zero failures/errors. The API 36
matrix was `BUILD SUCCESSFUL` in 1m24s across 456 tasks; retained XML records app
129 with nine skips, benchmark 3 with one skip, notifications 29, telephony 31,
state 43, index 31, and conversations 5: 271 total tests, 10 intentional skips,
and zero failures/errors. The complete host/release/privacy/license aggregate
was `BUILD SUCCESSFUL` in 1m19s across 886 tasks (130 executed, seven from cache,
749 up-to-date). CycloneDX 1.6 passed 15 tasks in 8s and reports 441 components
and 442 dependencies. The final debug APK is 13,993,426 bytes with SHA-256
`16037c616d6d696b4974f3e3a14238c18937c6f677f2f60e677ca10f0ea0ef98`.

An initial API 26 aggregate run usefully exposed test-order contamination: a
channel test disabled the production reply-failure channel, whose disabled
importance survives delete/recreate on API 26. The corrected test uses a
dedicated test-only channel. That failed run remains diagnostic evidence only
and is not counted as pass evidence. The implementation and tests for this
final-source slice are frozen in commit `3d7182c`.

The bounded 512-entry incoming replay journal still eventually evicts completed
ownership, so an extremely old exact redelivery may eventually insert again.
Successful carrier reply and callback behavior, physical and OEM devices,
lockscreen and Android Auto surfaces, API 27 through 35 journeys, and process
death at every lifecycle checkpoint remain unproven, along with broader group,
multipart, MMS, and release acceptance. AuroraSMS is incomplete and not gold.

Phase 3 does not change the existing carrier MMS limitations. Earlier Phase
1/2 functional evidence covers a Pixel 8 on Android 16/API 36. Phase 3 profile
capture and functional journeys are verified with synthetic data on the API 36
emulator. A later owner-approved Pixel window also verified complete
real-provider reconciliation and privacy-safe inbox/thread/search reachability;
release-equivalent physical performance measurements remain pending. Emulator
timings are not product performance evidence.

General outgoing and incoming MMS are now implemented behind bounded synthetic
contracts, but end-to-end carrier acceptance is not yet claimed. ADR 0021 owns
the one-person voice-memo path, ADRs 0024/0025 own bounded incoming decode and
crash-safe persistence, and ADR 0026 owns direct/group text, subject, and
sanitized JPEG/PNG composition. Other arbitrary attachment UI, physical
carrier/OEM behavior, billing/roaming, and the complete process-death matrix
remain open. Platform staging and result handling fail closed outside their
admitted payloads. See the phase gates in `docs/TEST_MATRIX.md`.

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
materials, raw source artwork, high-resolution masters, and reference-app code
or resources must not enter the repository, tests, reports, or APK. The
owner-selected public launcher derivative is the documented artwork exception.
Original source and the tracked launcher artwork are licensed under
GPL-3.0-or-later; other artwork has a separate per-asset rights gate described in
`docs/ARTWORK_CATALOG.md`.
