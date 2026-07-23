# Phase 5 file-level plan

Status: ADR 0008 is accepted and the bounded Phase 5A source implementation is
frozen in commit `17fc421` as `0.5.0-phase5` (`versionCode` 4). Focused
and aggregate local/emulator acceptance passed on 2026-07-18. Physical-device,
SIM, OEM, carrier-network, billing, roaming, sent, and delivery evidence remains
open.
This document does not declare AuroraSMS complete or gold.
Phase 5B through Phase 5G addenda below are now implemented; the latest source
is `0.5.6-phase5` (`versionCode` 10), Room schema 10.

## Outcome

Phase 5A adds one intentional, crash-safe text SMS action to an existing
provider-backed Thread with one verified participant. It uses the Thread's
associated active SMS-capable subscription and accepts only text that calculates
to exactly one SMS unit.

The local safety boundary is:

1. atomically freeze edit acceptance, drain all accepted writes, and acknowledge
   one exact visible Room draft snapshot;
2. reserve the exact draft ID/revision in a content-free Room schema-5 operation,
   without clearing or copying its body;
3. call the SMS transport once with explicit `COMPOSER` origin and caller-owned,
   awaited `PREPARED` and `SUBMITTING` durability checkpoints;
4. route an exact one-unit sent callback by origin, operation ID, provider message
   ID, and provider conversation ID;
5. persist `SENT_CALLBACK_SUCCEEDED` before provider reconciliation;
6. classify the exact provider status attempt as a terminal `Success` disposition
   (`APPLIED`, `ROW_ABSENT`, or `OWNERSHIP_CONFLICT`), without ever mutating a
   foreign row; and
7. clear only the original draft revision and remove the operation, or preserve a
   newer revision.

Every known-unsent or uncertain path preserves the draft. Recovery never submits
or retries an SMS.

ADR 0008 is binding for this slice.

## Scope fence

### Included

- Existing provider-backed Thread only.
- Completed verified identity with exactly one participant.
- Nonblank text, no subject, no attachment, exactly one calculated SMS unit.
- The current associated active SMS-capable subscription; no SIM fallback.
- One content-free durable operation per Thread and exact draft revision.
- Awaited caller-owned checkpoints on both sides of provider preparation and the
  Android submission boundary.
- Exact sent-callback success/failure handling.
- Exact provider message-and-conversation status updates whose terminal success
  dispositions are `APPLIED`, `ROW_ABSENT`, and `OWNERSHIP_CONFLICT`.
- Draft retention through submission, callback uncertainty, and provider
  reconciliation.
- Startup/process recovery, role fencing, callback timeout, duplicate callback,
  storage-failure, and ownership-conflict behavior.
- Honest ready, sending, known-unsent, and submission-unknown UI.

### Excluded and kept disabled

- New-message and external `SENDTO`/`VIEW` sends.
- Multiple participants, group SMS fan-out, group MMS, and all other MMS sends.
- Subjects, media, files, vCards, locations, reactions, and previews in outgoing
  content.
- More-than-one-unit SMS text and all per-part callback bookkeeping.
- Delivery-report tracking for this composer path.
- SIM selection, remembered SIM choice, or fallback to another subscription.
- Scheduling, send delay, Undo Send, background retry, and automatic resend.
- Quote, forward, edit-after-send, and permanent-delete workflows.
- Claims that an Android return, emulator callback, fake, or provider row proves
  carrier acceptance or delivery.

`ComposeMessageActivity` remains outside the sending path. External intent text
does not bypass review or enter `ThreadSmsSendCoordinator`.

## Implemented boundary contract

### Exact draft reservation without content duplication

`SerializedDraftWriter.freezeForSend()` must return one exact acknowledged
content, `DraftId`, and `DraftRevision` snapshot before the root calls the
coordinator. The reservation transaction re-reads that draft and checks:

- exact provider Thread identity;
- exact revision;
- nonblank body; and
- null subject.

It creates a `RESERVED` operation and returns the authoritative body in memory.
The operation row stores no body, recipient, or subject. The draft is not deleted
or changed. A unique provider-Thread index prevents a second active operation.

The operation durably binds the exact draft ID/revision so it cannot clear a
different revision. The editor and send action are locked during submission and
while status is unknown. A known-unsent result preserves the draft and exposes
only explicit user retry. Only exact successful sent-callback completion may
clear the reserved revision; if a newer revision is present, completion preserves
it.

App-private `SavedState` is a restoration hint, not draft or operation authority.
Its bounded token carries the exact Room draft ID/revision on which unsaved
content was based; a base-free token is accepted only while Room still has no
draft. The writer first reads Room, hides saved-state content while that read is
pending, and applies the hint only on an exact base match. A stale or mismatched
hint is discarded, including after successful send completion, so it cannot
resurrect cleared text.

Send uses `SerializedDraftWriter.freezeForSend()`, which atomically stops new edit
acceptance, waits for every edit accepted before the barrier to become durable,
and returns one exact body/draft-ID/revision snapshot. The coordinator receives
that snapshot's durable identity only. Editing reopens only after an explicitly
classified refusal, known-unsent result, or acknowledged uncertainty.

### One-unit gate

Android `SmsMessage.calculateLength` supplies the displayed unit count. Both UI
and coordinator require exactly one unit. The durable provider binding also
enforces `unitCount == 1`. Text requiring two or more units is shown as unavailable
and never silently converted to MMS.

### Single durable pre-submission owner

Composer sends use `SmsSubmissionOwnership.CallerOwned` and
`OperationOrigin.COMPOSER`:

```text
RESERVED -> PREPARED(exact provider message, conversation, unitCount=1)
PREPARED -> SUBMITTING(same exact binding)
```

Both observer methods are suspending. `AndroidSmsTransport` awaits the Room commit
and does not cross the next boundary unless the observer returns `true`. It does
not launch checkpoint work for later completion. A mismatch, storage error,
cancellation, invalid origin/ID pairing, or ownership failure stops progress.

The coordinator captures a role/fence generation before acceptance and checks
both role and generation before and after each awaited checkpoint. The transport
then performs one final role check immediately before the `SmsManager` Binder
call. Role loss proven at that final pre-boundary check is known-unsent; an
exception once the Binder call may have begun is uncertainty.

After the exact writer snapshot freezes, the root's coordinator handoff is
non-cancellable. Reservation through immediate result classification is one
durable envelope: a commit-then-cancellation race is classified from Room, and a
present or temporarily unreadable operation remains started and enters bounded
non-sending recovery. Provider insertion completes far enough to return its exact
identity before cancellation is observed, allowing the first awaited-checkpoint
cancellation path to terminalize that exact staging row.

Composer sends do not create a transport-owned journal record.

### Durable state machine

```text
RESERVED
  | prepared checkpoint
  v
PREPARED
  | submitting checkpoint
  v
SUBMITTING
  | Android method returned
  v
PLATFORM_ACCEPTED

SUBMITTING / PLATFORM_ACCEPTED / SUBMISSION_UNKNOWN
  | exact successful sent callback, durably committed
  v
SENT_CALLBACK_SUCCEEDED
  | exact provider COMPLETE update returns terminal Success
  | (APPLIED / ROW_ABSENT / OWNERSHIP_CONFLICT)
  | exact-draft-clear + operation-remove transaction
  v
operation complete

RESERVED / PREPARED / proven live pre-boundary refusal -> KNOWN_UNSENT
bound active/unknown + exact failed sent callback      -> KNOWN_UNSENT
SUBMITTING / PLATFORM_ACCEPTED ambiguity               -> SUBMISSION_UNKNOWN
```

`PLATFORM_ACCEPTED` records only that the Android method returned without a
synchronous exception. It is not a carrier claim. A two-minute missing-callback
timeout moves it to `SUBMISSION_UNKNOWN`.

Only `RESERVED`, `PREPARED`, or a live refusal with independent proof that the
platform call was not made can become known-unsent during ordinary
interruption/recovery. Any other ambiguity at or after `SUBMITTING` remains
unknown unless a later exact one-unit sent callback supplies authoritative proof.
Exact success may complete from `SUBMISSION_UNKNOWN`; exact failure may transition
`SUBMISSION_UNKNOWN -> KNOWN_UNSENT`.

No phase schedules an automatic retry.

### Exact callback and provider ownership

Each sent/delivery `PendingIntent` has a content-free identity URI built from:

- callback channel;
- explicit operation origin;
- operation ID; and
- unit index.

The URI excludes recipient, body, provider IDs, and provider content. Callback
extras still carry the exact provider message and conversation identities needed
for validation.

Composer routing requires `COMPOSER` origin and durable Room ownership. An
explicitly composer-origin result never falls through to unmarked or inline-reply
mutation, even if its operation is missing or storage is unreadable.

After an exact successful callback, Room first commits
`SENT_CALLBACK_SUCCEEDED`. The coordinator then calls:

```text
updateOutgoingStatus(providerMessageId, providerConversationId, COMPLETE)
```

After that durable callback proof, all three exact terminal `Success` dispositions
permit local completion: `APPLIED`, `ROW_ABSENT`, or `OWNERSHIP_CONFLICT`.
`ROW_ABSENT` means there is no exact row left to update; `OWNERSHIP_CONFLICT`
means the exact guarded operation refused to mutate a row that no longer
satisfies Aurora's ownership/state guard. Neither outcome authorizes an ID-only
fallback or foreign mutation, and neither can turn proven callback success into
a retryable draft. Provider access, permission, or storage failure leaves the
operation/draft for later exact settlement. A malformed identity is swallowed by
the composer owner without any provider write. Failed sent callbacks use the
same exact pair for `FAILED`.

Duplicate exact successful callbacks are idempotent: if
`SENT_CALLBACK_SUCCEEDED` is already durable, the callback resumes only the same
provider/local settlement and never invokes transport again.

If Room cannot read or checkpoint a transient exact callback, the coordinator
retains only its content-free operation/binding identity for bounded retry. Typed
immediate-result or callback-timeout classification failures request bounded
non-sending recovery. Recoverable operation-observation failures emit a blocked
state and resubscribe instead of permanently freezing an already-open Thread on
the first error.

Exact successful completion and unknown acknowledgement also verify a typed
commit-ambiguous result by re-reading the same content-free operation. Proven
absence publishes one deduplicated process-local signal: sent completion clears
the stale frozen writer, while unknown acknowledgement reopens the preserved
draft. The bounded verifier never invokes transport.

### Operation origin and allocation

Future positive pending-operation values are structurally partitioned:

| Owner for new allocation | Numeric region |
| --- | --- |
| Transport-owned respond-via-message | `0 < id < 2^61` |
| Existing-Thread composer | `2^61 < id < 2^62` |
| Inline reply | `2^62 <= id <= Long.MAX_VALUE` |

`2^61` is the named composer boundary; Room's first local ID maps to `2^61 + 1`.
Numeric classification validates a new origin/ownership pairing but does not prove
durable ownership.

New respond-via-message allocation stays below `2^61`. Grandfathered unmarked
transport-journal records remain readable and recover by journal membership even
if their numeric values lie outside that new allocation region. Inline reply and
composer carry explicit origins through immediate results and callbacks.

### Recovery and role gate

The controller remains unavailable until the state database opens
non-destructively and its repository is installed. Composer recovery joins the
existing serialized messaging-recovery pass and must reach `READY` before Send is
eligible.

Recovery performs no transport send:

- `RESERVED`: preserve draft, mark `KNOWN_UNSENT`.
- `PREPARED`: durably mark the pre-boundary proof, then conditionally roll back
  only the exact app-owned provider row; terminalization, absence, or guarded
  ownership conflict mutates no foreign row and leaves it `KNOWN_UNSENT`.
- `SUBMITTING` or `PLATFORM_ACCEPTED`: mark `SUBMISSION_UNKNOWN`.
- `SENT_CALLBACK_SUCCEEDED`: retry only exact provider `COMPLETE` reconciliation
  and local completion.
- bound `KNOWN_UNSENT`: retry only exact provider `FAILED` reconciliation.
- `SUBMISSION_UNKNOWN`: preserve the operation for explicit user review.

An exact terminal provider disposition (`APPLIED`, `ROW_ABSENT`, or
`OWNERSHIP_CONFLICT`) settles the provider step after durable success or
pre-boundary known-unsent proof. Provider access/storage failure defers only that
exact operation. Once Room supplies a valid bounded snapshot, unrelated Threads
remain usable; the Thread that still owns an active operation remains gated by
that operation. Role loss or an unreadable/corrupt Room snapshot blocks
acceptance globally: role loss immediately fences the controller, while a failed
snapshot marks storage blocked. Role restoration re-runs recovery before
accepting new work. Recovery and live callbacks use the same guarded, idempotent
settlement paths.

### Honest UI and manual actions

The Thread composer exposes:

- unavailable with an exact reason: empty, draft not durable, identity not
  verified, group requires MMS, subscription unavailable, more than one SMS unit,
  recovery pending, or messaging unavailable;
- ready with “Draft saved locally · 1 SMS”;
- sending with editor and send action locked;
- known-unsent with the draft preserved and an explicit Retry send action; and
- submission-unknown with the draft locked and a Review action.

Uncertainty stays locked only until an exact sent callback supplies new proof or
the user acknowledges it. The unknown Review dialog warns that Android may
already have accepted the text
and that sending again could create a duplicate. “Wait” keeps the operation.
“Keep as draft” explicitly acknowledges and removes only the unknown operation;
it does not resend or delete the draft. A later send requires a separate user tap.
The frozen Phase 5A implementation intentionally discarded Aurora's durable
callback owner and left exact late-provider cleanup as a Phase 5B residual. ADR
0009 closes that residual with a separate content-free receipt while preserving
the duplicate-risk warning and separate later tap.

## File-level implementation map

### `core:model`

#### `core/model/src/main/kotlin/org/aurorasms/core/model/ProviderKind.kt`

- Defines the `2^61` composer and `2^62` inline-reply boundaries.
- Classifies future respond-via, composer, and inline-reply values without treating
  classification as durable ownership.

#### `core/model/src/main/kotlin/org/aurorasms/core/model/TransportResult.kt`

- Carries `COMPOSER`, `INLINE_REPLY`, or backward-compatible `UNMARKED` origin.
- Carries provider conversation identity through SMS immediate and callback
  results.
- Distinguishes submission uncertainty from sent/delivery callback failure.

#### Model tests

- `PendingOperationNamespaceTest` covers boundaries and invalid values.
- `TransportResultTest` covers origin/provider identity and failure invariants.

### `core:state`

#### `core/state/src/main/kotlin/org/aurorasms/core/state/ComposerSmsOperation.kt`

- Defines the seven durable phases, exact provider binding, content-free operation,
  redacted typed results, one-unit limit, table cap, and repository contract.

#### `core/state/src/main/kotlin/org/aurorasms/core/state/storage/ComposerSmsOperationEntity.kt`

- Maps the content-free Room row to a composer ID derived from its local monotonic
  key.
- Stores exact Thread/draft/revision/subscription/phase/binding/timestamps only.

#### `ComposerSmsOperationDao.kt`, `ComposerSmsOperationEnforcement.kt`, and
`RoomComposerSmsOperationRepository.kt`

- Provide guarded transitions, table integrity/cap triggers, exact reservation,
  exact callback checkpoints, exact draft clearance, terminal acknowledgement,
  bounded recovery, and typed failure behavior.
- Preserve cancellation and fail closed on corrupt or unavailable storage.

#### `AuroraStateDatabase.kt`, `StateDatabaseFactory.kt`, and
`StateDatabaseMigrations.kt`

- Advance the state database from 4 to 5 with an explicit, non-destructive table,
  indices, and triggers.
- Register the migration in every supported open path.

#### Schema and tests

- `core/state/schemas/.../5.json` and `5-triggers.sql` record schema 5.
- `ComposerSmsOperationContractTest` and `ComposerSmsOperationEntityTest` cover
  domain/storage invariants and redaction.
- `ComposerSmsOperationRepositoryInstrumentedTest` covers real Room reservation,
  transitions, callbacks, draft retention/clearance, reopen, and failure cases.
- `StateMigration4To5Test`, `StateDatabaseReopenTest`, and schema tests verify the
  migration and existing-data preservation.

#### Phase 5B acknowledged-unknown addendum

- `AcknowledgedComposerSmsEntity.kt` and
  `AcknowledgedComposerSmsEnforcement.kt` define the bounded content-free
  late-callback receipt and its physical invariants.
- The operation repository atomically transfers `SUBMISSION_UNKNOWN` ownership
  into that receipt, checkpoints exact late `SENT`/`FAILED` proof, and removes the
  receipt only after a terminal exact provider disposition.
- `AuroraStateDatabase` schema 6, `STATE_MIGRATION_5_6`, `6.json`, and
  `6-triggers.sql` record the non-destructive addition.
- `StateMigration5To6Test`, repository instrumentation, reopen coverage, and
  coordinator tests prove draft preservation, process-safe reconciliation, and
  zero resend. ADR 0009 owns the decision.

### `core:telephony`

#### `MessageTransport.kt` and `AndroidSmsTransport.kt`

- Validate origin/namespace pairing for new submissions.
- Await caller-owned `onPrepared` and `onSubmitting` commits inline.
- Preserve provider staging, exact binding, rollback, permission/role/feature/SIM/
  recipient checks, and post-boundary uncertainty.
- Build origin-aware content-free callback identity URIs.

#### `SmsProviderDataSource.kt` and `AndroidSmsProviderDataSource.kt`

- Add exact outgoing-status mutation keyed by provider message and conversation.
- Return `APPLIED`, `ROW_ABSENT`, or `OWNERSHIP_CONFLICT` instead of treating a
  broad write count as ownership proof.

#### Receivers and existing outgoing owners

- `SmsSentReceiver.kt` and `SmsDeliveredReceiver.kt` preserve explicit origin and
  exact provider conversation identity, while reading pre-upgrade callbacks.
- `RespondViaMessageService.kt` allocates new operations below `2^61`.
- `OutgoingSmsSubmissionJournal.kt` keeps grandfathered content-free records
  readable without reclassifying durable ownership.

#### Telephony tests

- Ownership-policy, checkpoint, callback-origin/identity, respond-via allocation,
  provider status-transition, and journal reopen tests exercise these seams without
  a carrier send.

### `core:testing`

- `FakeMessageTransport` records ownership, origin, provider identity, call count,
  and checkpoint decisions without invoking `SmsManager`.
- `FakeSmsProviderDataSource` models exact outgoing-status outcomes and keeps
  test message content private to the fake.

### `app`

#### `app/src/main/kotlin/org/aurorasms/app/message/ThreadSmsSendController.kt`

- Defines the redacted command, observable UI phase, recovery result, fencing,
  unknown acknowledgement, and exact result-routing contract.
- Defers the production controller until the state database is available.

#### `ThreadSmsSendCoordinator.kt`

- Revalidates role, exact current conversation identity, single recipient,
  associated active SMS-capable subscription, exact draft revision, and one-unit
  body at the durable boundary.
- Owns reservation, awaited observer checkpoints, immediate-result translation,
  callback timeout, exact callback processing, provider reconciliation, recovery,
  and completion epochs.
- Never schedules a resend.

#### `SmsSegmentCounter.kt`

- Uses Android's SMS length calculation and supplies the one-unit eligibility
  signal without retaining content.

#### `AppContainer.kt`

- Installs the Room repository/coordinator only after a safe database open.
- Routes explicit composer callbacks to their durable owner before legacy paths.
- Fences on role loss and includes composer reconciliation in the existing
  serialized recovery pass.
- Keeps exact provider status outcomes on untracked and inline-reply paths
  without broadening those owners' completion rules.

#### `AuroraSmsRootServices.kt`, `AuroraSmsRoot.kt`, and
`drafts/SerializedDraftWriter.kt`

- Expose the controller and segment counter to the existing Thread only.
- Treat app-private `SavedState` content only as a bounded restoration hint whose
  base must exactly match Room; hide it during the authoritative read and discard
  it on mismatch.
- Atomically freeze edit acceptance, drain every accepted edit, and send only the
  returned exact durable snapshot.
- Lock the draft through active/unknown work and recreate the writer only after
  an exact process-local completion epoch clears the restoration hint.
- Preserve visible text on known-unsent, uncertainty, and storage failure.

#### Existing inline-reply integration

- `InlineReplyOrchestrator`, `InlineReplyProviderUpdateCoordinator`,
  `ReplyOperationRegistry`, and `SharedPreferencesReplyOperationStore` carry the
  exact provider conversation and explicit origin needed by the hardened result
  seam without changing inline-reply ownership.

### `feature:conversations`

#### `ConversationUiModel.kt`, `ThreadScreen.kt`, and strings

- Add redacted ready/sending/known-unsent/unknown state and exact unavailability
  reasons.
- Enable Send only for an acknowledged one-unit eligible draft.
- Lock editing during active or unknown work.
- Present the duplicate-warning acknowledgement dialog for uncertainty.

#### Feature tests

- `ComposerUiStateHostTest` covers model invariants and redaction.
- `ConversationUiStateTest` covers enabled/disabled actions, one-unit gating,
  known-unsent behavior, and the uncertainty warning flow.

### Governance documents

- ADR 0008 and this plan describe the implemented content-free operation and
  retained-draft protocol.
- `docs/TEST_MATRIX.md`, `docs/THREAT_MODEL.md`,
  `docs/PRODUCT_REQUIREMENTS.md`, and `README.md` must record only evidence that
  actually passes and must keep physical/carrier claims open.

## Release gates

Phase 5A local acceptance is not complete until all of these pass:

- schema 4-to-5 and supported direct migrations preserve every draft and
  appearance record;
- stale, saving, failed, or in-memory-only drafts cannot reserve an operation;
- reservation persists no body and does not delete the draft;
- double tap, cancellation, recreation, restart, duplicate callback, or callback
  race cannot produce a second platform invocation;
- both caller-owned checkpoints are durably awaited before the next boundary;
- role and fence generation are rechecked through both checkpoints and role is
  checked once more immediately before the platform call;
- only an exact sent callback can commit `SENT_CALLBACK_SUCCEEDED`;
- only a terminal exact provider `Success` disposition after durable sent proof
  can clear the exact draft and operation;
- duplicate exact success callbacks resume idempotent settlement and never send;
- stale/mismatched saved-state content is hidden and discarded, and an atomic
  draft freeze prevents an accepted edit from racing the send snapshot;
- known-unsent and uncertainty preserve the draft and never auto-retry;
- manual unknown acknowledgement displays the duplicate warning and leaves the
  draft intact;
- role loss, unreadable/corrupt Room state, or recovery not ready keeps Send
  globally unavailable; provider access failure gates only its owning operation;
- callback origin/identity and future ID allocations remain isolated across all
  outgoing owners;
- commit-then-cancellation, first-checkpoint cancellation, timeout storage
  failure, callback-checkpoint storage failure, and recoverable observation
  failure all retain liveness without a second send;
- no body or recipient appears in operation rows, logs, diagnostics, exceptions,
  callback URIs, or `toString()` output;
- production no-role/no-permission paths create zero outgoing provider rows and
  zero platform calls; and
- existing receive/read, notification, inline-reply, respond-via-message, MMS,
  draft, appearance, and wallpaper suites remain green.

## No-carrier acceptance plan

No automated Phase 5A test sends a real SMS. Use synthetic recipients, fake
provider/transport boundaries, emulator Room, or deliberately unavailable
production preconditions.

### Host and Room tests

- Exercise every guarded transition and exact binding mismatch.
- Recreate/reopen after `RESERVED`, `PREPARED`, `SUBMITTING`,
  `PLATFORM_ACCEPTED`, `SENT_CALLBACK_SUCCEEDED`, `KNOWN_UNSENT`, and
  `SUBMISSION_UNKNOWN`.
- Assert zero sends during recovery and never more than one transport invocation
  for a user action.
- Inject transaction failure, full/corrupt storage, cancellation, provider row
  absence, ownership conflict, duplicate/late/wrong-origin callbacks, and callback
  success before the immediate Android return is reconciled.
- Verify the exact draft remains until successful callback checkpoint plus exact
  provider terminal `Success`; verify `APPLIED`, `ROW_ABSENT`, and
  `OWNERSHIP_CONFLICT` never mutate a foreign row and all preserve a newer draft
  revision while provider access failure defers completion.
- Verify stale saved-state hints cannot resurrect a cleared draft, matching and
  genuinely base-free hints restore, and freeze drains the edit/send race.
- Migrate seeded schema-4 databases with all draft and appearance tables on API 26
  and API 36.

### UI tests with fakes

- Launch an existing Thread with a verified one-person identity, synthetic
  subscription, and fake transport/provider.
- Verify saving-to-ready, one tap, immediate lock, one-unit label, more-than-one-
  unit disablement, recreation, known-unsent retry, unknown warning/Wait/Keep as
  draft, group disablement, and external-compose isolation.
- Never invoke a real `SmsManager` boundary or use a real destination.

### Production fail-closed tests

- Exercise production coordinator/transport preflight only with SMS role absent or
  `SEND_SMS` unavailable and a synthetic recipient.
- Assert zero provider insertion, zero callback intent creation, and zero platform
  invocation.
- This proves preflight refusal only; it is not carrier evidence.

### Aggregate commands

Use the repository's pinned toolchain and complete gates after focused tests:

```text
./gradlew test lintDebug lintRelease assembleDebug assembleRelease \
  :app:lintBenchmark :app:assembleBenchmark \
  :macrobenchmark:check :macrobenchmark:assembleBenchmark \
  verifyCleanRoom verifyPrivateAssets verifyDependencies \
  verifyPermissions verifyApkContents checkLicense generateLicenseReport \
  --offline --no-daemon --no-parallel --console=plain
./gradlew cyclonedxBom \
  --offline --no-daemon --no-parallel --console=plain
ANDROID_SERIAL=<api26-avd> ./gradlew connectedDebugAndroidTest \
  --offline --no-daemon --no-parallel --console=plain
ANDROID_SERIAL=<api36-avd> ./gradlew connectedDebugAndroidTest \
  --offline --no-daemon --no-parallel --console=plain
```

Focused tests do not substitute for the aggregate host, connected, privacy,
governance, clean-room, dependency, and artifact gates.

## Local/emulator acceptance evidence — 2026-07-18

Implementation commit `17fc421` passed the complete offline aggregate in
1m27s across 886 tasks (90 executed, two from cache, 794 up-to-date). All 508
host JUnit results passed: app 236, design 9, index 69, model 19,
notifications 21, state 38, telephony 79, testing 24, and conversations 13.
`bundleRelease` separately passed 269 tasks in 7s. CycloneDX 1.6 passed 15
up-to-date tasks in 7s and reports 441 components and 442 dependencies.

The complete API 26 matrix on `emulator-5556` passed in 1m54s across 456 tasks:
291 total tests, 13 intentional skips, 278 executed, and zero failures/errors.
The complete API 36 matrix on `emulator-5554` passed in 1m31s across 456 tasks:
288 total tests, 10 intentional skips, 278 executed, and zero failures/errors.
Focused API 36 root composer and external-compose-isolation gates each passed
1/1. All send-related acceptance used fakes, synthetic data, or deliberately
unavailable production preconditions; no carrier SMS was sent.

Pinned build-tools 36.0.0 `aapt2` inspection confirms the expected package and
`0.5.0-phase5`/4 identity, minimum API 26, target API 36, `debuggable` only in
the debug app, and no `INTERNET` or `ACCESS_NETWORK_STATE` in app variants. The
macrobenchmark test APK's debuggable/tooling-network boundary is expected and
isolated. The release APK is unsigned and not distributable. Exact artifact
sizes and SHA-256 values are recorded in `docs/TEST_MATRIX.md`.

This local evidence is frozen in implementation commit `17fc421` and covers two
attached emulators. It does not claim a pushed CI run, physical-device handoff,
signed release, physical-device acceptance, or carrier acceptance.

## Phase 5C addendum — durable conversation SIM choice

The 2026-07-19 `0.5.2-phase5` (`versionCode` 6) slice adds Room schema 7 and ADR
0010. A verified one-person conversation may persist one active SMS-capable
subscription against a purpose-separated participant-set digest. The row is
content-free, revisioned, and protected by physical SQLite triggers. The
provider Thread ID is only a hint; verified participant identity remains the
scope authority.

The Thread header presents the active choices and commits a selection before
using it. Once remembered, that exact choice is authoritative. If it disappears
from the active SMS-capable set, the UI names the unavailable state, keeps Send
disabled, and requires an explicit replacement. Immediately before reservation,
the coordinator re-creates the verified scope and re-reads the repository; a
missing/stale/mismatched preference or storage failure refuses before provider
staging and platform transport.

Local acceptance is green: 521 host tests and the complete 886-task aggregate;
separate 269-task release-bundle and 15-task CycloneDX gates; and the complete
456-task connected matrix on API 26 and API 36. The API 26 XML reports 297
tests/13 intentional skips and API 36 reports 294/10, both with 284 executed
tests and zero failures/errors. Exact artifact hashes and module totals are in
`docs/TEST_MATRIX.md`.

This addendum closes only durable scoping and synthetic/emulator no-silent-
fallback behavior for the current one-part composer. Physical SIM/eSIM removal,
carrier routing, physical scheduled-send timing, groups, MMS, billing/roaming, OEM behavior,
and other Phase 5 lifecycle/action rows remain open.

## Phase 5D addendum — durable scheduled one-part SMS

The 2026-07-19 `0.5.3-phase5` (`versionCode` 7) slice adds Room schema 8 and ADR
0011. One bounded, content-free schedule owns the exact verified Thread, draft
ID/revision, selected SIM, due instant, clock anchors, precision, and lifecycle;
its purpose-separated participant token cannot be correlated by copying the SIM-
preference or appearance keys. Alarm intents carry only the schedule ID.

The Thread composer exposes a clock action and native local date/time pickers.
Scheduling freezes the exact durable draft. The locked composer identifies an
exact alarm or honestly says it may send late, confirms cancellation, and offers
Android's exact-alarm special-access screen without making access mandatory.
At due time the coordinator checks clock continuity, verified participants,
default role, authoritative active SIM, exact draft revision, and one-unit
eligibility before reusing the Phase 5A sender. Duplicate alarms and process-
local restarts cannot produce a second dispatch; uncertain, missed, clock-
changed, removed-SIM, or pre-reservation interrupted state pauses visibly for
review and never retries automatically.

Host, migration, repository, manifest, UI, privacy, release, and connected
evidence must be recorded in `docs/TEST_MATRIX.md`. No fake alarm, emulator,
method result, or database assertion closes physical reboot, Doze, exact-access
revocation, SIM removal, OEM alarm timing, carrier submission, or billing gates.

## Phase 5E addendum — durable short send delay and Undo

The 2026-07-19 `0.5.4-phase5` (`versionCode` 8) slice adds Room schema 9 and ADR
0012. Immediate send remains the default. A user can explicitly choose 1, 3, 5,
or 10 seconds from the Thread overflow menu. Sending then freezes the exact
durable draft before creating one content-free delay operation.

The operation owns the exact Thread, draft ID/revision, selected subscription,
due instant, lifecycle, monotonic/wall-clock anchors, and a delay-specific
participant digest. It stores no message body, subject, address, contact label,
or SIM label. SQLite triggers enforce a 128-row bound, key shape, initial state,
immutable bindings, monotonic revisions, and only the approved pending-to-
dispatch/review lifecycle.

A process-local coroutine is the normal short timer; a private explicit alarm
carrying only the local operation ID is the process-death wake-up. The due path
rechecks clock continuity, a 30-second restart lateness ceiling, verified one-
person identity, default role, exact remembered active SIM, durable draft
revision, and one-unit eligibility before atomically claiming dispatch and
calling the existing crash-safe sender. Duplicate alarms cannot win that claim
twice.

Undo removes only `PENDING` or `REVIEW_REQUIRED`, cancels both wake-ups, and
unfreezes the preserved draft. It is unavailable in `DISPATCHING`. Reboot,
clock/time changes, excessive lateness, removed SIM, lost role, arming failure,
or interrupted handoff becomes visible review state and never automatically
retries or silently changes transport.

Host, schema/migration, repository, manifest, Compose UI, privacy, release, and
API 26/API 36 evidence belongs in `docs/TEST_MATRIX.md`. Synthetic timers,
emulators, and no-send physical UI checks do not close real carrier, billing,
radio, OEM-kill, or reboot-during-live-send gates.

## Phase 5F addendum — guarded permanent message and Thread deletion

The 2026-07-19 `0.5.5-phase5` (`versionCode` 9) slice adds Room schema 10 and
ADR 0013. A visible provider-backed SMS/MMS row can be selected with long press
or an accessibility custom action, then permanently deleted after explicit
confirmation. The Thread overflow offers whole-conversation deletion only when
the local index is verified complete and uses a separate last-chance
confirmation.

Both paths create one content-free operation with a fixed five-second Undo
window. Exact message fingerprint or bounded Thread count/latest-row metadata
detects provider changes. Whole-Thread state also snapshots the exact Aurora
draft ID/revision. Composer, send-delay, scheduled-send, and active-send
conflicts stop the deletion; the composer remains locked while an operation is
active.

The due path rechecks role, local conflicts, draft revision, clock continuity,
lateness, and provider identity before durably entering `COMMITTING`. Recovery
never retries provider deletion: it inspects whether the exact target is
absent, unchanged, changed, or unavailable. Only confirmed absence finalizes
the operation and clears the exact old draft revision. A newer draft is
preserved. After provider success, the UI offers no restoration claim. No
recycle-bin UI, schema, preference, or worker exists.

Host, schema/migration, repository, provider, coordinator, manifest, Compose
UI, privacy, release, and API 26/API 36 evidence belongs in
`docs/TEST_MATRIX.md`. Automated and safe physical acceptance must not read or
delete live messages and does not close physical process-death or OEM/provider
lifecycle gates.

## Phase 5G addendum — shared no-group-SMS boundary

The 2026-07-19 `0.5.6-phase5` (`versionCode` 10) slice implements ADR 0014
without changing Room schema 10. `SmsSendRequest` accepts exactly one canonical
recipient, so no caller can represent a group as SMS. The exported respond-via
surface makes one typed route decision: two or more unique recipients create
one `MmsSendRequest`, invoke MMS once, and return any failure without SMS
fallback.

Existing Thread, delayed, and scheduled composer paths retain independent
verified-one-person checks before any durable SMS reservation. Group Thread UI
keeps the draft editable, states that MMS is required and unavailable, and
disables Send. Host tests cover two- and three-recipient routing, MMS failure,
the unrepresentable group-SMS request, and all three app coordinators; emulator
UI acceptance proves the disabled group composer without a carrier call.

This closes only the current-surface no-fan-out invariant. It does not implement
the full group-MMS composer, codec, provider addressing, group notification
reply, or carrier acceptance in the broader Phase 6/release matrix.

## Evidence that remains open

No carrier-send evidence has been collected for Phase 5A. The following remain
explicitly open even after fake/emulator acceptance:

- the one-to-one carrier SMS and status rows in `docs/TEST_MATRIX.md`;
- physical SIM/subscription behavior;
- physical Pixel/OEM behavior;
- real carrier-network submission and rejection;
- billing, roaming, airplane-mode, weak/no-service, and radio transitions;
- actual sent/delivery callback and provider reconciliation behavior;
- device reboot or process death during a real network send; and
- all more-than-one-unit carrier behavior.

The frozen Phase 5A source left provider-row cleanup after manual unknown
acknowledgement as a Phase 5B residual. ADR 0009 closes that local residual in
schema 6: acknowledgement atomically transfers exact callback/provider ownership
to a content-free receipt, and recovery reconciles durable late proof without
resending or clearing the preserved draft.

No emulator modem injection, fake callback, Android method return, or provider
`COMPLETE` row may close those items. They require a separate user-approved
physical/carrier protocol with a user-controlled destination and charge/privacy
review.

## Stop conditions

Keep Send unavailable or fail the phase if any of these becomes true:

- an unacknowledged or stale draft can reserve/send;
- operation storage gains body or recipient content;
- reservation clears or consumes the draft;
- a checkpoint can return before its durable commit;
- any recovery path invokes the transport or automatically retries;
- `SUBMITTING`, `PLATFORM_ACCEPTED`, or ambiguity becomes retryable as though
  known-unsent;
- provider reconciliation can mutate by message ID without exact conversation
  ownership, or local completion can occur before a terminal exact provider
  `Success` disposition;
- callback routing relies on numeric range without explicit origin and durable
  ownership;
- role, permission, subscription, recipient, one-unit, or storage failure falls
  through to provider/platform submission;
- known-unsent or uncertainty deletes the draft;
- unknown acknowledgement lacks the duplicate warning or resends automatically;
- message content appears in logs, diagnostics, callback identity, operation
  storage, backup, or failure rendering;
- existing SMS/MMS receive/read/notification/inline-reply behavior regresses; or
- acceptance would require a real carrier send without a separate approved
  protocol.
