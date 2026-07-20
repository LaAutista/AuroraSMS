# AuroraSMS product requirements

Status: Phase 0 product baseline plus locally accepted Phase 1 through Phase 6E
controls and implemented Phase 6F bounded voice-memo controls, 2026-07-19.

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
| Source license | GPL-3.0-or-later | Applies to original AuroraSMS source and tracked launcher artwork; other artwork is gated separately |
| Minimum SDK | API 26 | Required test baseline |
| Compile SDK | API 37 | Required by the approved current AndroidX artifacts; build surface only |
| Target SDK | API 36 | Preserves the blueprint's reviewed target behavior until a separate target-37 audit |
| UI toolkit | Jetpack Compose with original AuroraMaterial components | No copied layouts or strings |
| Language/runtime target | Kotlin; Java/Kotlin bytecode target 17 | Host JDK may be newer |
| Distribution | F-Droid-compatible source and GitHub releases first; Play is optional | FOSS build remains the privacy baseline |
| Network permission | Absent | `INTERNET` is forbidden in the FOSS build |
| Signing | A new AuroraSMS release key | Generate outside Git; keep encrypted owner-controlled offline backups |

The signing key is not generated in Phase 0. The owner retained the Variant 2
neon SMS-bubble and portrait composition, required exactly two simple purple
hairpins and no `A` or other letter marks, and offered the tracked launcher
artwork under GPL-3.0-or-later. `docs/ARTWORK_CATALOG.md` records that direction;
the private wallpaper replacement set remains a separate per-asset approval
gate. The project source grant and SPDX policy are recorded in
`LICENSE_POLICY.md` without modifying the canonical GPL text in `LICENSE`.
Compiling against API 37 does not enable or imply RCS or an alternative message
transport; AuroraSMS's approved runtime scope remains SMS/MMS.

## Non-negotiable behavior

- Android's Telephony provider is authoritative for actual SMS and MMS.
- AuroraSMS maintains a rebuildable, synchronized local projection for display
  and full-text search.
- Drafts, scheduled messages, pending sends/deletes, spam decisions, named
  appearance profiles, the active-profile selection, and scoped profile
  overrides live in a separate durable Aurora state store. Bounded signature
  preferences remain separate from drafts; an active send owner may contain
  only its exact frozen signature in addition to content-free ownership.
  Conversation
  appearance assignment storage keeps only a versioned participant-set
  fingerprint plus the current provider thread ID, never raw participant
  addresses; the separate private rebuildable index retains its participant
  address rows for message indexing and exact verified projection.
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
- Bounded app-private Phase 1 journals: redacted incoming-delivery ownership,
  reply targets and claims, accepted reply-operation progress, and incoming-
  notification generations. They contain no message/reply body, are excluded
  from backup, and do not replace the Telephony provider or Aurora state
  database as a relational authority.
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
- For an accepted outgoing SMS, make the provider row fail-safe before it can
  become send-eligible: atomically insert `FAILED` with an Aurora-owned staging
  sentinel, assign exactly one durable owner, durably bind the exact row as
  `PREPARED`, permit one conditional sentinel-consuming transition to
  `PENDING`, then durably record `SUBMITTING` before invoking the irreversible
  platform transport. A synchronous pre-boundary refusal or cancellation may
  terminalize only the exact Aurora-created row in an allowed state. Inherited
  `SUBMITTING` must become `SUBMISSION_UNKNOWN`, never `FAILED`, and must never
  be rearmed or resubmitted. Never infer that a stale, reused, foreign, or
  otherwise changed row was armed or rolled back successfully.
- After the initial scan reaches the oldest provider boundary, run a bounded
  lightweight consistency pass and persist a completed generation only after
  that pass succeeds. A restart or reconcile must never mistake a partial
  checkpoint for complete index coverage.
- On default-role loss, stop provider writes safely and report the state.

### Implemented durable messaging hardening

Commit `7c9d848` implements the initial Phase 1 controls below. The current
follow-on implementation adds the provider-staging and operation-scoped alert
contracts described here. Its expanded provider, final-source SystemUI, full
connected, and aggregate gates are now green on the two AOSP baseline emulators.
Neither that evidence nor the follow-on implementation is a claim that AuroraSMS
is complete, release-ready, or gold:

- Reply targets, consumed claims, reply operations, and incoming notification
  generations use bounded private stores with versioned canonical encodings,
  synchronous security-boundary writes, and checksums. A target keeps the
  validated recipient needed to route a cold-process reply but no message body;
  a consumed claim keeps a recipient digest; reply-operation and generation
  state is limited to provider-qualified identity, lifecycle/progress/status,
  and exact ordering evidence.
- The incoming SMS replay journal v4 retains a redacted provider-content digest
  so recovery can identify an exact provider row after the insert/checkpoint
  crash boundary. Its checksum binds the delivery key and canonical payload;
  malformed records become key-bound, checksummed `Q1` quarantine tombstones
  instead of being mistaken for new deliveries or blocking unrelated valid
  recovery entries.
- Default-role lifecycle work is serialized and derived from authoritative
  platform role state. Confirmed loss disables new recovery, cancels and joins
  pending recovery jobs, fences live incoming work, and performs exact-
  generation notification cleanup before reply targets are cleared.
- A `goAsync()` lease timeout finishes the broadcast lease without cancelling
  already accepted sibling work in the app process. It does not make that work
  survive Android process death; durable journal checkpoints and later recovery
  triggers remain the recovery authority.
- Inline-reply failures use a generic, body-free alert that asks the user to
  confirm status in AuroraSMS before trying again. It does not repeat reply
  text, recipient, address, message content, or a carrier error.
- The provider row for an outgoing SMS is initially a known-unsent `FAILED` row
  carrying a dedicated staging sentinel and Aurora creator ownership. The
  single owner records `PREPARED` before a one-shot conditional arm moves that
  exact row to `PENDING` and consumes the sentinel; it records `SUBMITTING`
  before the platform call. A synchronous pre-boundary refusal or cancellation
  conditionally terminalizes the exact Aurora-created row. Missing rows are
  safely retired; ownership, creator, thread, or state conflicts are
  quarantined without changing a foreign or reused provider row. Inherited
  `PREPARED` retries exact cleanup, while inherited `SUBMITTING` becomes
  `SUBMISSION_UNKNOWN` and is never resubmitted.
- Notification inline reply is caller-owned by its private reply-operation
  store and reserved high operation IDs. Android `RESPOND_VIA_MESSAGE` is
  transport-owned, uses ordinary low operation IDs, and records no message
  content in a separate private outgoing journal. That journal admits at most
  128 entries. Active `PREPARED` and `SUBMITTING` ownership is never evicted;
  only `SUBMISSION_UNKNOWN` and known-unsent quarantine tombstones expire after
  seven days. Capacity rejects new transport-owned work instead of dropping
  active ownership. Corrupt, noncanonical, or uncommittable journal state
  globally fails transport-owned submission closed. A transient provider-
  cleanup failure for one record remains retryable without globally blocking
  unrelated sends.
- Pre-journal alpha builds may have left `PENDING` provider rows with no exact
  transport-owned record. Upgrade recovery intentionally does not sweep or
  mutate those rows and makes no claim to repair them.
- Each generic inline-reply failure alert is owned by the compound identity of
  conversation and durable reply operation. Positive evidence cancels only
  that alert and the exact source notification generation. Success-side
  cancellation remains pending durably until the same exact idempotent effects
  can be acknowledged.

On first role-enabled recovery after upgrading from the pre-operation-key
alpha, AuroraSMS dismisses any still-active conversation-only generic
reply-failure alerts because they cannot be mapped safely to one durable reply
operation. Previously user-dismissed alerts are not recreated. Message/provider
state and durable late-callback ownership are unchanged; users should verify
those replies in the conversation. If legacy-alert enumeration or cancellation
fails, pending replay is deferred and recovery retries. A migrated success
record without its historical source-message identity cannot cancel one exact
incoming-notification generation, so AuroraSMS cancels the operation-scoped
failure alert but leaves durable success acknowledgement pending rather than
guessing.

Final-source focused verification completed a 320-task host gate with telephony
75/75, core testing 22/22, and app 191/191, plus green lint and app/telephony
`androidTest` compilation. The transport-owned journal passed 7/7 on API 26 and
7/7 on API 36. The owner-gated real Telephony-provider staging contract passed
1/1 on each API without invoking `SmsManager`; it covered staged insert and arm,
wrong-thread conflict preservation, idempotent terminalization, absent exact
URI, and exact cleanup. Notification identity/cancellation passed 29/29 on each
API, including real `NotificationManager` sibling preservation. A final
disposable API 26 SystemUI `inline-reply-permission-denied` journey passed with
exact cleanup, after which its overlay was discarded.

The complete API 26 connected matrix was `BUILD SUCCESSFUL` in 1m51s across 456
tasks. Preserved console module roots have app 132 with 12 skips, benchmark 3 with one skip,
notifications 29, telephony 31, state 43, index 31, and conversations 5: 274
total tests, 13 intentional skips, and zero failures/errors. API 36 was `BUILD
SUCCESSFUL` in 1m24s across 456 tasks; retained XML has app 129 with nine skips,
benchmark 3 with one skip, notifications 29, telephony 31, state 43, index 31,
and conversations 5: 271 total tests, 10 intentional skips, and zero failures/
errors. The host/release/privacy/license aggregate was `BUILD SUCCESSFUL` in
1m19s across 886 tasks (130 executed, seven from cache, 749 up-to-date).
CycloneDX 1.6 passed 15 tasks in 8s with 441 components and 442 dependencies.
The debug APK is 13,993,426 bytes with SHA-256
`16037c616d6d696b4974f3e3a14238c18937c6f677f2f60e677ca10f0ea0ef98`.

An initial API 26 aggregate run exposed channel-test contamination; the
corrected test uses a dedicated test-only channel instead of disabling
production channel state. The failed run remains diagnostic only and is not
pass evidence. The implementation and tests are frozen in commit `3d7182c`.
The 512-entry incoming replay
retention bound and all broader carrier, physical/OEM, API 27 through 35,
process-death, MMS, accessibility, performance, and release limitations remain
in force; AuroraSMS is not complete or gold.

### Phase 5A existing-Thread one-part SMS composer

The `0.5.0-phase5` (`versionCode` 4) 2026-07-18 worktree implements the first
intentional Thread send as a deliberately narrow local-safety slice. It is
available only in an existing provider-backed Thread with one completed verified
participant, the Thread's associated active SMS-capable subscription, nonblank
text with no subject or attachment, and exactly one Android-calculated SMS unit.
New/external compose, groups, MMS, multipart text, delivery reports, SIM fallback,
schedules/delay, Undo Send, and automatic retry remain disabled.

- Room schema 5 owns one bounded, content-free composer operation per Thread. It
  binds only the provider Thread, exact draft ID/revision, subscription, phase,
  exact prepared provider IDs, unit count, and timestamps. It never copies the
  body, recipient, or subject out of the authoritative draft.
- App-private `SavedState` is only a bounded restoration hint. Its unsaved
  content names the exact Room draft ID/revision on which it was based, or has no
  base only for a still-absent Room draft. The writer hides the hint until Room
  is read, applies it only on an exact base match, and discards stale content so
  a successful send cannot be resurrected by configuration/process restoration.
- `freezeForSend()` atomically stops new edit acceptance, drains every edit
  accepted before the barrier, and returns one exact acknowledged content,
  draft-ID, and revision snapshot. Only that snapshot may be reserved and sent.
- The composer is the single caller-owned pre-submission authority. The
  transport awaits durable `PREPARED` and `SUBMITTING` Room checkpoints; role
  and coordinator fence generation are checked before and after both, followed
  by one final authoritative role check immediately before the `SmsManager`
  Binder call.
- After the draft freeze, the reserve-through-immediate-classification handoff
  is non-cancellable. A commit-then-cancellation race is classified from Room,
  and transient typed failures schedule bounded, non-sending recovery rather
  than leaving `RESERVED`, `PLATFORM_ACCEPTED`, or an observation permanently
  stranded. Exact callback proof is retained only as content-free identity for
  bounded checkpoint retry.
- If exact successful completion or unknown acknowledgement commits but Room
  reports a typed failure, re-read and boundedly verify the exact content-free
  operation. A proven removal publishes one deduplicated process-local signal:
  successful completion recreates an empty writer, while acknowledgement reopens
  the preserved draft.
- Recovery never submits. A valid bounded Room snapshot reopens unrelated
  Threads even if exact provider cleanup for one operation is deferred; the
  owning Thread remains gated. Role loss and unreadable/corrupt Room state remain
  global fail-closed conditions.
- An exact one-unit `COMPOSER` sent callback commits
  `SENT_CALLBACK_SUCCEEDED` before provider settlement. Duplicate exact success
  callbacks resume that idempotent settlement. After durable callback proof, an
  exact provider update may complete on terminal `Success(APPLIED)`,
  `Success(ROW_ABSENT)`, or `Success(OWNERSHIP_CONFLICT)`: the last two mutate no
  foreign row and do not turn proven success into retryable work. Provider
  access, permission, or storage failure defers exact completion.
- Proven pre-boundary refusal and exact failed sent callbacks preserve the draft
  as known-unsent. Any ambiguity at or after the platform boundary becomes
  submission-unknown and never retries automatically.
- The explicit “Keep as draft” uncertainty action warns that another send can
  duplicate a message, removes only the unknown operation, and reopens the
  preserved draft. A later composer callback is swallowed rather than routed to
  another owner, but the old provider row may remain unreconciled; that cleanup
  is an explicit Phase 5B residual.

Phase 5A automated acceptance uses fakes, Room, emulators, and deliberately
unavailable production preconditions. It does not send a real carrier SMS and
does not close physical-device, SIM, OEM, carrier, billing, roaming, sent, or
delivery gates. AuroraSMS remains incomplete and not gold.

### Phase 5C durable conversation subscription choice

The `0.5.2-phase5` (`versionCode` 6) 2026-07-19 source replaces implicit
latest-message SIM reuse with an explicit, durable choice for the currently
supported verified one-person Thread path.

- Room schema 7 stores one content-free preference per purpose-separated
  verified participant-set hash: the provider-Thread hint, subscription ID,
  optimistic revision, and timestamps. It stores no address, recipient, body,
  subject, or display label and does not reuse the appearance or draft hash
  domain.
- The Thread header exposes the active SMS-capable subscriptions. Choosing one
  persists it before it becomes authoritative; stale writes re-read rather
  than overwrite a newer choice.
- Until a preference exists, the exact Thread-associated subscription remains
  the conservative default. Once a preference exists, it is authoritative.
  If it is absent from the current active SMS-capable set, the UI identifies
  the remembered choice as unavailable, disables Send, and requires an
  explicit replacement. It never silently falls back.
- Immediately before operation reservation, the coordinator reconstructs the
  verified purpose-separated conversation scope and re-reads the durable
  preference. Storage failure, identity mismatch, stale authority, an
  unavailable subscription, or a command/preference mismatch refuses before
  provider staging or transport.

The local API 26/API 36 matrices prove persistence, migration, UI gating, and
pre-reservation fail-closed behavior with synthetic subscriptions. They do not
prove physical dual-SIM/eSIM lifecycle, removal, carrier routing, billing,
roaming, groups, MMS, or physical scheduled-send timing. Those release rows remain open.

### Phase 5D durable scheduled one-part SMS

The `0.5.3-phase5` (`versionCode` 7) 2026-07-19 source schedules only the exact
durable draft supported by the Phase 5A/5C verified one-person, one-unit Thread
path.

- Room schema 8 stores at most 128 content-free schedule rows. Each row binds a
  local operation ID to the exact Thread, draft ID/revision, chosen SIM, due
  instant, phase/precision, clock anchors, and a schedule-specific participant
  hash. It stores no address, body, subject, name, or SIM label.
- The composer freezes that exact revision, displays its local due time and
  exact/inexact precision, and confirms cancellation. Inexact timing is labeled
  “may send late” and offers Android's special-access screen without requiring
  access or repeatedly prompting.
- Alarm `PendingIntent`s are explicit, immutable, and contain only the local
  schedule ID. Exact access arms an exact alarm plus a distinct five-minute
  inexact safety alarm; denial or `SecurityException` retains only the honest
  inexact alarm.
- Due handling never trusts the alarm alone. It rechecks clock continuity,
  verified participants, default role, authoritative active SIM, exact draft
  revision, and one-unit eligibility before entering the existing durable send
  coordinator. The durable `PENDING` → `DISPATCHING` transition makes duplicate
  alarms idempotent.
- Once a schedule is `DISPATCHING`, neither UI nor coordinator accepts
  cancellation because carrier or transport ownership may already have transferred.
- Reboot/time change, excessive lateness, removed SIM, lost role, arming
  failure, or interruption before durable composer reservation pauses the
  schedule for visible review. Recovery never automatically retries an
  uncertain submission or silently changes SIM/transport.

Synthetic host/emulator evidence cannot close physical reboot, Doze,
exact-access revocation, OEM alarm timing, dual-SIM removal, live carrier, or
billing/roaming gates. AuroraSMS remains incomplete and not gold.

### Phase 5E durable short send delay and Undo

The `0.5.4-phase5` (`versionCode` 8) 2026-07-19 source adds only a truthful
pre-submission grace period to the verified one-person, one-unit Thread path.

- Immediate send remains the default; explicit choices are 1, 3, 5, or 10
  seconds.
- Room schema 9 stores at most 128 content-free delay owners bound to the exact
  durable draft, verified Thread, selected subscription, due instant, phase,
  and clock anchors. The draft remains the sole message-content authority.
- A process-local timer handles the ordinary path and a private ID-only alarm
  wakes process-death recovery. No exported surface, permission, network path,
  service, worker, or automatic retry is added.
- Undo is enabled only while the operation remains pending or safely paused for
  review. Once durable dispatch ownership begins, Aurora never claims the send
  can be recalled.
- Due handling revalidates clock continuity/lateness, verified participants,
  default role, remembered active SMS-capable SIM, exact draft revision, and
  one-unit eligibility. Duplicate wake-ups are idempotent.
- Reboot/clock discontinuity, excessive lateness, lost role/SIM, arming failure,
  stale state, or interrupted handoff preserves the draft in visible review
  state and never sends automatically.

Synthetic/emulator acceptance and safe physical UI smoke do not prove real
carrier recall, OEM process-kill timing, reboot during a live submission,
billing, roaming, or radio behavior. AuroraSMS remains incomplete and not gold.

### Phase 5F guarded permanent deletion

The `0.5.5-phase5` (`versionCode` 9) 2026-07-19 source adds permanent provider
deletion for one exact SMS/MMS row or an entire verified Thread.

- A message bubble requires an explicit confirmation. Whole-conversation
  deletion requires two confirmations, including a final irreversible warning.
- Both paths use one fixed five-second pre-commit Undo window. After provider
  success, the UI leaves no recovery claim and exposes no recycle bin.
- Room schema 10 stores at most 128 content-free operations. A message target is
  bound to its provider kind, row ID, and synchronization fingerprint; a Thread
  target is bound to its verified participant digest, provider count and latest
  row IDs, plus the exact Aurora draft ID and revision present at confirmation.
- The private alarm carries only an operation ID. Due handling revalidates role,
  conflicts, identity, target continuity, clock continuity, and bounded lateness
  before provider mutation. The composer is locked while its Thread is pending.
- Recovery never blindly repeats a possibly committed delete. It inspects the
  exact target: confirmed absence finalizes, an unchanged present target cancels
  safely, and changed or unreadable state remains in explicit review.
- Whole-Thread success removes only the exact old draft revision captured at
  confirmation; a newer edit is preserved.

Automated acceptance uses synthetic provider state. Safe physical installation
and metadata-only migration/launch checks do not delete live messages or close
real provider, process-kill, OEM, or carrier gates. AuroraSMS remains incomplete
and not gold.

### Phase 5G shared no-group-SMS boundary

The `0.5.6-phase5` (`versionCode` 10) 2026-07-19 source implements ADR 0014
without a database migration.

- `SmsSendRequest` can represent exactly one canonical recipient. A group
  cannot cross any shared SMS transport boundary even if a caller regresses.
- Thread, scheduled-send, and delayed-send paths independently require one exact
  verified participant before durable reservation or sender handoff.
- Android respond-via routes two or more recipients into one `MmsSendRequest`
  and invokes MMS exactly once. MMS rejection or failure is returned unchanged;
  no per-recipient loop or SMS fallback exists.
- The current group Thread composer remains visibly unavailable rather than
  pretending group SMS is safe.

This proves no fan-out across current paths. It does not claim the full group-
MMS composer, codec, provider-addressing, group-reply, or carrier matrix, which
remains open.

### Phase 6A conservative reaction fallback presentation

The `0.6.0-phase6` (`versionCode` 11) 2026-07-19 source implements ADR 0015
without a database migration.

- Only exact whole-message, bounded common English fallback forms with matched
  straight or curly quotes are recognized.
- Truncated, malformed, differently cased, multiline, unknown, trailing, blank,
  or oversized input remains the original raw SMS.
- An exact match renders a structured reaction card containing the action and
  quoted target. It is not associated with or hidden behind another provider
  row.
- Parsing is presentation-only. The Telephony provider body, rebuildable index,
  timeline/search model, and durable state are never changed.

This is compatible fallback presentation, not native reaction transport or an
outgoing reaction feature.

### Phase 6B explicit selected-text copy and bounded message details

The `0.6.1-phase6` (`versionCode` 12) 2026-07-19 source implements ADR 0016
without a database migration.

- Long press opens Message actions instead of invoking deletion directly.
- Select text uses a read-only view of only the body displayed in that bubble.
  Copy selected is disabled for collapsed or invalid ranges and writes exactly
  the selected substring to Android's clipboard.
- A truncated preview is explicitly labeled; copy does not fetch, fabricate, or
  imply hidden text and never adds a sender, subject, timestamp, adjacent text,
  conversation metadata, or provider identifier.
- Message details is bounded to SMS/MMS type, direction, localized timestamp,
  status, subscription availability, and attachment count. It contains no body,
  subject, address, provider/thread ID, or attachment path.
- Selection state is transient and content is not logged or stored. Clipboard
  export occurs only from the visible Copy selected action.
- Delete remains a separate Message actions choice and still requires Phase
  5F's confirmation and durable Undo protocol.

### Phase 6C local content-free notification reminders

The `0.6.2-phase6` (`versionCode` 13) 2026-07-19 source implements ADR 0017
without a database migration.

- Reminders are off by default, with explicit 15-minute, one-hour, and
  three-hour choices in the inbox overflow.
- Only a successfully posted and provider-acknowledged incoming SMS may create
  one bounded content-free reminder owner for its conversation.
- A private one-shot inexact alarm carries only a monotonic local ID. No exact-
  alarm access, repeating alarm, worker, sender, address, or message content is
  persisted.
- Fire-time handling requires role ownership and a successful exact-provider
  read proving the same incoming row remains unread. The reminder itself is
  generic, and ownership is consumed before posting for at-most-once behavior.
- A confirmed read, missing, or mismatched row cancels the exact incoming
  notification generation. Provider failure consumes only the reminder and
  does not invent a read state or cancel the original alert.
- Opening a conversation, changing the setting, role loss, reboot, and clock or
  timezone change cancel or fence pending work. Startup recovery rearms only
  validated future reminders.

### Phase 6 history-completeness hardening

The `0.6.3-phase6` (`versionCode` 14) 2026-07-19 source hardens the existing
rebuildable Telephony index without a database migration.

The `0.6.5-phase6` (`versionCode` 16) physical-history follow-up preserves the
same architecture and fixes repeated default-app switching during an incomplete
initial scan.

- The default-SMS role remains an explicit Android-controlled precondition.
  AuroraSMS never requests or grants itself that role, and onboarding now says
  that message-history access stays paused when another app is default.
- A reconciliation that returns Pending while AuroraSMS still owns the role and
  remains foreground-readable receives a 500 ms continuation, bounded to four
  retries per initiating signal. A new foreground, provider-change, or periodic
  signal resets that budget; role loss, backgrounding, close, completion, or
  failure cancels it.
- The Telephony synchronizer remains serialized, checkpointed, and resumable.
  No background busy loop, provider authority change, new permission, content
  log, or copy of message data is introduced.
- A default-SMS role transition is an authority boundary, not proof that a
  provider row changed. A partial generation therefore keeps its durable cursor
  across role loss and resumes after explicit role recovery. Actual content
  observer or external-provider signals still mark the generation dirty, and
  final provider-count/head verification still rejects changed coverage.
- Incomplete Inbox and Thread screens use a prominent progress notice with the
  content-free committed-row count. ADR 0020's `0.6.7-phase6` follow-up presents
  all best-known physically retained generations during an incomplete refresh
  and explicitly warns that recent provider changes may not be reflected.
- Incomplete cache presentation never creates a verified participant identity
  or enables an authoritative action. Verified completion returns to strict
  current-generation queries and deletes stale rows in the existing atomic
  reconciliation transaction.
- A large synthetic acceptance fixture proves cursor pagination returns 153
  conversations and all 151 messages in one long thread exactly once.

This closes a code-side continuation and pagination regression gate. It does not
claim a complete personal-device index while AuroraSMS is not the default SMS
app; Android denies that provider read, and the owner must explicitly choose the
role before the final physical history-completeness check can run.

### Phase 6D bounded global and conversation signatures

The `0.6.4-phase6` (`versionCode` 15) 2026-07-19 source implements ADR 0018
and state schema 11.

- Global signature state is separate from drafts. A verified conversation may
  inherit it, disable it, or use a custom value through a purpose-separated
  participant-set hash that stores no raw address.
- Signatures are normalized only on explicit save, limited to 160 UTF-16
  characters and four lines, and never truncated. Blank text disables the
  selected scope; corrupt settings pause new sends and cannot be overwritten.
- The exact outgoing body uses a visible `\n-- \n` separator. The composer shows
  Android-calculated unsigned and signed SMS-part impact before send and states
  that a group signature would be included in MMS text.
- The existing one-person, one-part transport boundary remains unchanged. If a
  signature requires multipart SMS, Send is disabled instead of dropping the
  signature or submitting an unacknowledged multipart message. A group never
  falls back to individual SMS.
- Immediate, delayed, and scheduled paths freeze the resolved signature into
  the durable owner. Schema 11 makes that value immutable for the owner's
  lifetime, so process recovery cannot pick up a later preference edit.
- Preferences and active frozen values are app-private and excluded from
  backup/device transfer. No permission, network path, provider-body rewrite,
  automatic send, or carrier acceptance claim is added.

Phase 6D acceptance passed 578 host tests, the complete 886-task offline gate,
332 API 36 tests, and 335 API 26 tests with zero failures or errors. The exact
debug APK installed and hash-matched on the Pixel 8 and API 36 emulator while
their existing non-Aurora SMS roles and denied Aurora SMS permissions remained
unchanged. No live message or address was read and no carrier send was made.

### Phase 6E local explainable spam and blocking

The `0.6.6-phase6` (`versionCode` 17) 2026-07-19 source implements ADR 0019
and state schema 12.

- Automatic classification is a warning only. It requires an unknown
  conventional phone sender, the newest incoming snippet, a link, one fixed
  reviewed urgency term, and one fixed reviewed sensitive-request term. Saved
  contacts, short codes, alphanumeric senders, groups, outgoing messages, and
  incomplete participant identities are not automatically warned. Automatic
  rules pause when contact access is unavailable rather than guessing that an
  unresolved sender is unknown.
- Users may independently mark a verified conversation spam/not-spam and
  block/unblock a verified one-person sender. An explicit not-spam choice
  overrides automatic warning. Inbox, Thread, and the dedicated Spam & blocked
  route explain the active reason and expose recovery.
- No automatic rule hides a conversation. Suspected or blocked messages remain
  in Android's authoritative provider and normal indexed history. Blocking
  suppresses only Aurora's notification, reply-target, and reminder effects
  after the incoming provider row is stored and acknowledged.
- Room schema 12 stores at most 256 user decisions with purpose-separated
  participant/sender hashes, Thread ID, classification, block bit, optimistic
  revision, and timestamp. It stores no raw address, body, snippet, contact,
  keyword, or score; SQLite triggers physically enforce the bound, legal hash
  forms, one-person blocks, and monotonic transitions.
- UI and mutation require a complete exact verified participant identity. The
  Spam & blocked route re-reads each current conversation and rejects stale or
  mismatched decisions. Storage corruption makes controls unavailable, while
  incoming block-lookup failure fails open to the normal notification path.
- No permission, network/reputation lookup, provider rewrite/deletion,
  background worker, archive action, carrier send, or backup exposure is added.

Phase 6E acceptance passed 587 host tests in the complete 886-task offline
host/lint/R8/benchmark/privacy/dependency/license gate, 339 API 36 tests with 10
intentional skips, and 342 API 26 tests with 13. Both connected matrices
executed 329 tests with zero failures/errors. Release bundle and deterministic
CycloneDX 1.6 SBOM generation passed. No live message/address/body was inspected
and no carrier traffic was submitted. The exact debug APK installed and
hash-matched on the Pixel 8 and API 36 emulator with their existing non-Aurora
SMS roles and denied Aurora messaging/notification permissions preserved; no
Activity launch was issued and the Pixel was left force-stopped.

### Phase 6F bounded one-person voice memo

The `0.6.8-phase6` (`versionCode` 19) source implements ADR 0021.

- A microphone button appears only for an exact verified one-person Thread with
  an SMS-capable selected subscription and an otherwise empty/idle composer.
  `RECORD_AUDIO` is excluded from onboarding and requested only after the user
  taps Record.
- Capture is visibly timed, foreground-only, and limited to 60 seconds and
  524,288 bytes of MPEG-4/AAC-LC under `noBackupFilesDir`. Stop enters a separate
  review state. Cancel, Thread exit, backgrounding, invalid capture, and startup
  cleanup delete only owned bounded staging files.
- Send is a second explicit action. A currently resolved signature is frozen as
  the optional MMS text part; no draft, schedule, send-delay, group, arbitrary
  attachment, or background-recording path can enter this first surface.
- A pinned official-AOSP Apache-2.0 outgoing `SendReq` subset produces exactly
  one bounded SMIL/optional-text/audio PDU. No incoming parser, general composer,
  APN/network client, transaction service, database, UI, or end-user messaging
  app code is admitted.
- Provider parts are persisted before one exact Aurora-owned FAILED row is
  completed. Only an exact applied OUTBOX transition permits the platform MMS
  call. A content-free checksummed journal owns crash recovery; ambiguous
  submission is never retried, and exact private callbacks authenticate before
  provider status mutation.
- Deterministic golden/corpus, crash-ordering, callback-identity, provider,
  Compose, permission-policy, and real virtual-microphone tests cover API 26 and
  API 36 as applicable without reading a live provider or invoking a carrier.

This is not a full MMS composer. Group MMS, incoming PDU decoding, arbitrary
attachments, carrier/OEM acceptance, billing/roaming behavior, and automatic
retry of ambiguous submissions remain explicitly open.

### Phase 6G authenticated streaming backup and restore

The `0.6.9-phase6` (`versionCode` 20) source implements ADR 0022. Export and
restore use Android document capabilities, ephemeral passphrases, authenticated
streaming archives, bounded private staging, full pre-mutation validation, and
a separate exact confirmation. Restore is serialized and journaled; historical
Draft, Outbox, or Queued rows become inert Failed history and can never acquire
send authority. Synthetic provider and UI journeys pass on API 26 and API 36.
Real physical/OEM document selection and cancellation remain open.

### Phase 6H generation-fenced Android Auto notifications

The `0.6.10-phase6` (`versionCode` 21) source implements ADR 0023.

- The application descriptor declares Android Auto `notification` and `sms`
  capability without adding a proprietary runtime or network permission.
- Each conversation owns one private `MessagingStyle` notification with at most
  25 chronological messages. History is retained only while privacy and group
  identity match; public versions and failure/reminder alerts remain generic.
- Reply and Mark as read use a non-exported background service. Reply retains
  the existing durable role, identity, SIM, replay, provider-staging, and
  transport checks.
- Mark as read validates one exact incoming SMS in the expected thread and may
  update only incoming rows no newer than that generation. A stale action cannot
  consume a newer message, notification, or reminder.
- A 64-entry, two-second process mirror covers only asynchronous notification-
  manager publication on older Android and is neither durable nor a message
  store.

Host and API 26/API 36 notification contracts pass. Actual Android Auto/DHU,
physical lockscreen/OEM behavior, carrier-success reply, and incoming/group MMS
remain release acceptance work.

### Phase 7D bounded incoming MMS codec foundation

ADR 0024 admits twelve additional Java files from the same immutable, official-
AOSP Apache-2.0 MMS revision as ADR 0021 and an original GPL validation wrapper.
The parser copies at most 1 MiB, caps parts/headers/strings/nesting, checks every
declared length and end-of-input, keeps parser parameters instance-local, and
emits no message-derived runtime logs. Only bounded `M-Notification.ind` and
`M-Retrieve.conf` results may cross the wrapper; other PDU types, unsafe URLs,
oversized advertisements, wildcard/malformed MIME, and OMA DRM fail closed.

The retrieved result preserves bounded sender/TO/CC group identity, subject,
timestamp, UTF-8 or declared-charset plain text, and defensively copied opaque
parts without media decode. API 26/API 36 synthetic tests cover complete notification
and group-retrieve fixtures, every truncation, 1,024 deterministic hostile
inputs, redaction/immutability, and part/URL/size limits.

ADR 0025 and implementation commit `260fd18` now authorize the synthetic
download-to-provider-to-notification handoff behind a dedicated content-free
operation namespace and bounded metadata journal. `SUBMITTING` is durable before
the platform call, an uncertain submission is never retried, callbacks must own
the exact staged file, and provider parts/message/addresses commit atomically or
are cleaned. Notification acknowledgement owns final journal/file removal, and
startup may replay only provider/notification completion. Group MMS never gains
an SMS quick-reply path. Eight focused end-to-end cases pass on API 26 and API
36 without live provider or carrier traffic. General/group outgoing composition
and physical carrier/OEM incoming acceptance remain release gates.

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
- the fingerprint contract accepts 1 through 100 participants. The
  `ConversationSummary` remains a display preview of at most 8 members; a
  separate exact-thread and exact-generation query reads at most 101 rows (100
  plus one overflow sentinel) and exposes a 1-through-100-member identity only
  for verified-complete, non-truncated data whose entity/row generations, thread
  IDs, and declared/returned counts agree exactly;
- repository observation is target-specific, not an unbounded collection of
  every customized conversation; its first Room row-or-null is authoritative,
  while the app retains explicit loading until a positive durable profile
  revision and the exact target query both arrive;
- an initial timeline Ready state may precede the exact conversation-identity
  lookup. That Ready state explicitly marks identity unresolved, exposes no
  conversation appearance scope, and preserves a restored editor target until
  lookup completion; resolved-null or terminal failure clears the target, and
  invalidation resolves null before re-query so stale identity is revoked;
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
  in-flight/error/dismissal state is not restored as completed work. The derived
  exact participant identity is projected from existing private rebuildable
  index address rows, remains ephemeral/redacted, and is not added to appearance
  persistence or `SavedState`; it is never exported or logged;
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
limited to the Inbox modal. The final frozen artifact passed that unlocked
physical acceptance: a distinct focused scoped dialog opened over the same
MainActivity/Inbox window and Cancel returned to it without opening a Thread or
applying an assignment. This does not claim full process-death end-to-end or
physical eligible-Thread modal coverage.

The separately reviewed exact-thread follow-on preserves that physical nonclaim
while making conversations with 9 through 100 verified members eligible.
Content-free invalidation clears the holder identity before re-query; the app
requires complete coverage plus exact current route-thread and generation
matches, closes an open editor on identity loss/change or terminal failure, and
inherits `global_thread` for every missing, stale, oversized, truncated, or
inconsistent result. Focused exact-model, Room, holder, resolver, and real-root
tests passed, followed by the complete local 886-task
host/release/benchmark/governance/license gate in 1m05s, separate SBOM run, and
full 455-task API 36 connected matrix in 57s. The final 13,212,416-byte debug
APK, SHA-256
`39a07d72b7c58b91a11b152458ba971161b1edd98883f68df4fdbc6ab724235d`,
installed successfully on the Pixel 8 and its Download copy matched exactly.
The content-free Inbox-only physical smoke passed 1/1 in a 17-second, 197-task
build; package, default-role, required-grant, and cold-launch checks passed
without an app crash. This does not claim a physical 9-member Thread. Source
commit `83db9aa0f02cef44644f53d0bb149abe459dc20b` is committed and pushed on
`origin/main`; GitHub Verify run `29380854714` passed its 10m59s build job with
all project steps green.

The profile-reference foundation does not claim the wallpaper-specific chains
merely because a named profile already has future wallpaper vocabulary. ADR
0007's landed bounded wallpaper implementation adds direct
`global_thread` and verified-conversation assignments with assignment-local
focal/dim values, a real Thread renderer, and a temporary system-picker import
sanitized to a private content-addressed static WebP. It persists no source URI
or grant. Source commit `f0f1ff9` completes the bounded managed-file quota and
crash protocol: durable exclusive/no-follow staging and atomic publication,
fresh Room-authoritative cleanup, and fail-closed two-pass reconciliation. Its
complete picker/static-wallpaper UI journey, accessibility/form-factor,
performance, and carrier acceptance remain pending; the physical filesystem
protocol run is not UI-journey evidence. Inbox/other screens, canonical artwork,
GIF, live external URI, static blur, and import/export media remain separate
acceptance work. The product is not complete or gold.

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

- Aurora herself has no `A`, monogram, letter, or logo-like brand marks.
- Aurora's only fixed hair ornaments are exactly two simple parallel purple
  hairpins; launcher art combines her portrait with an unmistakable SMS bubble.

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

ADR 0007 is a deliberately narrower first fulfillment of this final product
requirement. It accepts only 8-bit Huffman baseline sequential-DCT (`SOF0`)
JPEG with at most four components and complete scan coverage, or CRC-valid
non-APNG PNG with at most 4,096 chunks, no `iCCP`/`zTXt`/`iTXt` ancillary
chunks, and a complete zlib scanline stream, for `global_thread`
and verified conversations. It sanitizes accepted pixels to an app-private
static WebP and resolves conversation -> global thread -> accessible solid.
Progressive, extended sequential, arithmetic, lossless,
differential/hierarchical, and non-8-bit JPEG remain unsupported. The temporary
picker URI is not durable. The durable assigned set retains at most 128
distinct managed files and 256 MiB total. To replace an assignment at full
quota, the serialized importer may temporarily hold exactly one unassigned
sanitized candidate of at most 8 MiB before the Room CAS, for a physical ceiling
of 129 files/264 MiB.
This is atomic-staging headroom rather than a raised durable quota; rejection or
cancellation removes it, and healthy startup GC removes a process-death orphan.
Each source is at most 16 MiB, 8,192 pixels per edge, and 40,000,000 pixels, and
each derivative is at most 8 MiB, 2,048 pixels per edge, 4,194,304 pixels, and a
16-MiB decoded allocation. Inbox, built-ins, GIF/APNG/other formats or JPEG
processes, and live external URI references remain unfulfilled parts of the
broader requirement.

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
- for Phase 5C, enable Send only for an exact acknowledged frozen draft in an
  existing verified one-person Thread, on its active SMS-capable durable
  conversation choice (or its conservative associated-SIM default before the
  first choice), when the body is exactly one SMS unit;
- show an unavailable remembered SIM explicitly and require the user to choose
  an active replacement before Send can become available;
- expose truthful ready, sending, known-unsent, submission-unknown, and exact
  unavailability states; lock editing during active/unknown work and require the
  duplicate-risk acknowledgement before reopening an unknown draft;
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
- The first static Thread-wallpaper slice uses a temporary picker capability,
  strips metadata into `noBackupFilesDir`, and persists only a redacted managed
  media digest. It requests no storage/media permission and retains no source
  URI or persistable grant.
- Message bodies, addresses, URIs, and search terms never enter logs.
- Provider thread IDs and participant-set fingerprints used for conversation
  appearance are sensitive pseudonymous metadata: keep them private, excluded
  from backup, absent from logs/`toString`, and never describe them as anonymous
  or telemetry-safe. The bounded target token that combines them for app-private
  `SavedState` follows the same restrictions and is never displayed or exported.
- A composer draft-restoration token in app-private `SavedState` may contain
  bounded message text only as an exact-base restoration hint. It is never send
  authority, is hidden until Room validates its base draft ID/revision, and is
  discarded on mismatch or successful completion; it remains excluded from
  backup, logs, diagnostics, and exports.
- Release builds remove debug logging.
- Disposable-emulator validation may query one debug-only, read-only SMS
  snapshot provider. It is guarded by `android.permission.DUMP` plus an
  explicit shell-UID check, returns only `_id`, `thread_id`, and `type`, and
  accepts no selection, sorting, write, call, or file surface. Manifest
  verification rejects its class and authority in every non-debug variant.
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
6. Phase 5: first deliver the bounded Phase 5A existing-Thread one-part SMS
   composer, then the Phase 5B acknowledged-unknown cleanup, Phase 5C durable
   conversation-SIM choice, Phase 5D durable scheduled one-part SMS with an
   honest exact/inexact alarm boundary, Phase 5E durable short-delay Undo, and
   Phase 5F guarded permanent message/Thread deletion, and Phase 5G's shared
   no-group-SMS boundary; the full group-MMS feature remains a later slice.
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
