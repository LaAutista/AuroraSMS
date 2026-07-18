# ADR 0008: crash-safe existing-Thread SMS composer

Status: accepted as the Phase 5A implementation control on 2026-07-18. The
bounded `0.5.0-phase5` (`versionCode` 4) implementation is present in the
worktree; aggregate host/API 26/API 36 emulator acceptance passed on 2026-07-18.
All physical-device, SIM, OEM, and carrier evidence remains open.

## Context

AuroraSMS can read provider-backed conversations, save drafts in its private Room
database, and stage outgoing SMS provider rows. The normal Thread composer needs
an additional durable owner because the irreversible Android SMS call can race
with process death, callback delivery, role loss, and storage failure.

The unsafe cases are straightforward:

- a stale or in-memory-only draft could be submitted;
- a double tap or restart could invoke the platform twice;
- deleting a draft before a proven sent callback could lose the user's text;
- treating an ambiguous boundary as retryable could duplicate a message or charge;
- a callback could mutate a provider row that no longer belongs to its operation;
- composer, respond-via-message, and inline-reply IDs or callbacks could be routed
  to the wrong durable owner; and
- a platform return, provider row, emulator result, or fake callback could be
  mislabeled as carrier evidence.

Draft text and pending-send state remain app-private. The design must fail closed
without logging message content and must preserve existing incoming, inline-reply,
respond-via-message, and MMS behavior.

## Decision

### 1. Phase 5A is one deliberately bounded path

Phase 5A enables an intentional text SMS action only from an existing
provider-backed Thread when the current identity lookup is complete, verified,
and contains exactly one participant. It uses the Thread's associated active,
SMS-capable subscription. There is no fallback to another SIM.

Only a body that `SmsMessage.calculateLength` reports as exactly one SMS unit is
eligible. The transport requests no delivery report for this slice.

The following remain disabled or out of scope:

- new-message and external `SENDTO` composition;
- group fan-out, MMS, subjects, attachments, and more-than-one-unit SMS text;
- SIM selection or fallback;
- scheduling, send delay, Undo Send, and automatic retry;
- quote, forward, and edit-after-send behavior; and
- any carrier-success or delivery-success claim.

### 2. Room schema 5 owns a content-free composer operation

`AuroraStateDatabase` schema 5 adds the bounded
`composer_sms_operations` table. A row stores only:

- a local monotonic operation key, mapped into the composer pending-operation
  namespace;
- provider Thread ID;
- source draft ID and exact `DraftRevision`;
- selected subscription ID;
- durable phase;
- exact provider message ID, provider conversation ID, and unit count after
  preparation; and
- creation and update timestamps.

The row does **not** store recipient text, message body, subject, or per-part
callback rows. The one-unit limit makes per-part bookkeeping unnecessary. The
table is capped at 128 active operations, has one active operation per Thread,
uses guarded compare-and-set transitions, and rejects partial or impossible
provider bindings through Room/domain validation and SQLite triggers.

Reservation re-reads the exact draft inside the Room transaction, checks its
Thread identity and revision, and verifies a nonblank body with no subject. It
returns that authoritative body to the caller in memory while persisting only the
content-free operation. `toString()` and typed results remain redacted.

No saved-instance state, Compose state, preference, or transport-owned journal is
a second authority for a composer operation. App-private `SavedState` may hold a
bounded restoration hint for an edit newer than Room, but the token names the
exact draft ID/revision on which it was based. The writer reads Room first and
accepts the hint only when that base still matches; a base-free hint is accepted
only when Room still has no draft. Saved text is hidden during that check and a
stale or mismatched hint is discarded, so successful completion cannot be undone
by restored UI state.

### 3. The exact draft remains durable through the operation

Send is unavailable while draft loading, saving, or persistence failure leaves no
exact acknowledged draft ID and revision. `SerializedDraftWriter.freezeForSend()`
atomically closes edit acceptance, drains every edit accepted before that
barrier, and returns one exact acknowledged content, `DraftId`, and
`DraftRevision` snapshot. The send path validates and uses only that frozen
snapshot, eliminating the edit/save/send race. Editing is reopened only after an
explicitly classified refusal, known-unsent result, or acknowledged uncertainty.

Reservation does not delete, clear, or copy the draft body into the operation.
The operation durably binds the exact draft ID/revision so it cannot clear a
different revision. The editor and send action are locked during submission and
while status is unknown. A known-unsent result preserves the draft and exposes
only an explicit user retry; it never clears or resends in the background.

The draft may be cleared only after all of these facts are durable:

1. an exact `COMPOSER` sent callback matches operation ID, SMS provider message
   ID, provider conversation ID, unit index zero, and unit count one;
2. the operation commits `SENT_CALLBACK_SUCCEEDED`;
3. the provider status update for that exact message-and-conversation pair returns
   a terminal `ProviderAccessResult.Success` disposition: `APPLIED`,
   `ROW_ABSENT`, or `OWNERSHIP_CONFLICT`; and
4. one Room transaction deletes only the original draft ID/revision and removes
   the successful operation.

`ROW_ABSENT` proves that the exact row is no longer present;
`OWNERSHIP_CONFLICT` proves that the guarded exact update declined to mutate a
row that no longer satisfies Aurora's exact ownership/state guard. After durable
callback proof, neither may turn the draft back into retryable work and neither
authorizes a broad ID-only write.
If the draft is already absent, completion may remove the operation. If a newer
revision exists, completion preserves it. If provider access, permission, or
storage is unavailable, the row stays in `SENT_CALLBACK_SUCCEEDED` so recovery
can retry the exact settlement; the app does not clear the draft on an access
failure.

An exact failed sent callback moves the operation to `KNOWN_UNSENT`, including
when that authoritative callback arrives after the operation became
`SUBMISSION_UNKNOWN`. It preserves the draft and reconciles only the exact
provider row to failed. A user may then explicitly choose Retry send; there is no
background or automatic resend.

### 4. Caller-owned checkpoints are awaited before each boundary

Composer sends call `MessageTransport.sendSms` with explicit `COMPOSER` origin and
`SmsSubmissionOwnership.CallerOwned`. Its `SmsSubmissionObserver` methods are
suspending durability gates:

- `onPrepared` must commit `RESERVED -> PREPARED` with the exact provider message
  ID, provider conversation ID, and one-unit binding.
- `onSubmitting` must commit `PREPARED -> SUBMITTING` with the same binding
  immediately before Android's irreversible SMS call.

The transport awaits each callback and crosses no next boundary until it returns
`true`. A failed commit, phase/revision mismatch, binding mismatch, cancellation,
or invalid ownership returns `false` and prevents the next boundary. The observer
does not launch deferred checkpoint work.

The coordinator captures a fence generation when accepting the action. Role and
that generation are checked before and after both awaited checkpoints. The
transport also checks authoritative role state one final time immediately before
the `SmsManager` Binder call. A loss proven at this last pre-boundary check can be
durably classified known-unsent; an exception after the Binder boundary may have
accepted the SMS and is therefore uncertainty.

After the writer freezes the exact draft, the root completes the coordinator
handoff in a non-cancellable context. Within the coordinator, reservation through
immediate transport-result classification is one cancellation envelope. A
reservation transaction that commits immediately before cancellation is
classified by re-reading Room; confirmed absence is refusal, while a present or
temporarily unreadable operation remains started and enters bounded non-sending
recovery. Provider insertion is likewise allowed to return its exact identity
before cancellation is observed, so cancellation of the first awaited checkpoint
can conditionally terminalize that exact staging row instead of orphaning it.

The caller-owned path does not also create a transport-owned submission-journal
record. Respond-via-message remains transport-owned; inline reply keeps its own
durable owner.

### 5. The durable state machine separates proof from uncertainty

| Phase | Durable meaning | Recovery or completion action |
| --- | --- | --- |
| `RESERVED` | Exact durable draft ID/revision and subscription are reserved; no provider row is bound. | Preserve draft and move to `KNOWN_UNSENT`; never submit during recovery. |
| `PREPARED` | Exact provider message/conversation and one-unit binding are committed; Android submission has not been authorized. | Persist `KNOWN_UNSENT` proof first, then conditionally roll back only that exact row. |
| `SUBMITTING` | The irreversible Android call may have begun. | Move to `SUBMISSION_UNKNOWN`; never retry automatically. |
| `PLATFORM_ACCEPTED` | The Android method returned without a synchronous exception; no sent callback is yet durable. | Wait for the exact callback; timeout/restart becomes `SUBMISSION_UNKNOWN`. |
| `SENT_CALLBACK_SUCCEEDED` | The exact sent callback is durable. | Require an exact terminal provider `Success` disposition (`APPLIED`, `ROW_ABSENT`, or `OWNERSHIP_CONFLICT`), then clear only the exact draft revision and remove the operation. Provider access failure defers. |
| `KNOWN_UNSENT` | Work is proven not sent, including an exact failed sent callback. | Preserve draft; only an explicit user retry may start a new operation. |
| `SUBMISSION_UNKNOWN` | Android may have accepted the SMS, or reconciliation is ambiguous. | Preserve and lock the draft; never retry automatically. A later exact successful sent callback may complete it; a later exact failed sent callback may prove `KNOWN_UNSENT`. |

The coordinator starts a bounded two-minute callback timeout after
`PLATFORM_ACCEPTED`. Timeout is uncertainty, not proof of failure. Exceptions,
cancellation, process death, role loss, or missing evidence at or after
`SUBMITTING` are also conservative uncertainty unless a live transport result
independently proves that the platform call was never made.

Typed storage failures are not allowed to become silent terminal events. Failed
immediate classification, commit-then-cancellation classification, or timeout
reconciliation schedules a bounded, non-sending recovery loop. A transient exact
sent callback is retained in memory only as content-free operation/binding
identity for bounded Room checkpoint retry, so generic recovery cannot erase the
proof before it becomes durable. Recoverable Room observation failure emits a
truthful recovery-pending state and resubscribes; it does not permanently complete
the observation for an already-open Thread.

The terminal Room transactions are also commit-ambiguity aware. After a typed
failure from exact successful completion or unknown acknowledgement, the
coordinator re-reads that operation and boundedly verifies only the same immutable
ownership. Proven absence means the atomic transaction committed. A bounded,
deduplicated process-local completion signal recreates an empty writer after sent
success; a separate acknowledgement signal reopens the still-durable draft. No
terminal verifier invokes transport or carries message content.

Uncertainty therefore remains only until an exact sent callback supplies new
proof or the user explicitly acknowledges it. The uncertainty UI requires a
Review action. Its dialog warns that
Android may already have accepted the message and that another send could create
a duplicate. “Wait” leaves the operation locked. “Keep as draft” acknowledges and
removes only the `SUBMISSION_UNKNOWN` record; it does not resend or delete the
draft. Any later send is a separate explicit user action after that warning.

Manual acknowledgement deliberately gives up the durable owner for the old
callback. A later explicit composer-origin callback is still consumed and cannot
fall through to another outgoing owner, but its old provider row may remain
unchanged because no operation remains with reconciliation authority. This is an
explicit Phase 5B cleanup residual, not a hidden Phase 5A guarantee.

### 6. New operations have explicit origin and disjoint allocation regions

Named constants define the future allocation regions:

| New owner | Pending-operation values |
| --- | --- |
| Transport-owned respond-via-message | `0 < id < 2^61` |
| Existing-Thread composer | `2^61 < id < 2^62` |
| Inline reply | `2^62 <= id <= Long.MAX_VALUE` |

The value `2^61` is a boundary constant; Room auto-generated local IDs make the
first composer ID `2^61 + 1`. Classification is structural validation, never
durable ownership proof.

Composer requests and callbacks carry explicit `OperationOrigin.COMPOSER`.
Callback `PendingIntent`s also use a stable, content-free identity URI containing
channel, origin, operation ID, and unit index. Recipient, body, provider ID, and
provider content are excluded from the URI.

Pre-upgrade transport journals may contain grandfathered ordinary IDs outside the
new respond-via allocation region. They remain readable and recover by exact
journal membership with unmarked origin; they are not reinterpreted as composer
work. New respond-via allocations stay below `2^61`.

App routing checks explicit origin and the durable owner before any legacy path.
An explicitly composer-origin result never falls through to inline-reply or
unmarked provider mutation, even when its Room record is missing or unreadable.

### 7. Provider mutation requires exact ownership and a terminal result

Provider status writes use `updateOutgoingStatus(providerMessageId,
providerConversationId, status)`. The implementation checks both identities and
returns one of `APPLIED`, `ROW_ABSENT`, or `OWNERSHIP_CONFLICT`.

After durable successful-callback proof, all terminal
`ProviderAccessResult.Success` outcomes complete the composer provider step:
`APPLIED`, `ROW_ABSENT`, and `OWNERSHIP_CONFLICT`. The latter two perform no
foreign mutation; they terminally classify why the exact guarded write cannot or
must not be applied. Provider access, permission, or storage failure retains the
operation and draft for exact recovery. Malformed callback identity is consumed
by the composer route without a provider write. The same exact-update seam
hardens inline-reply and untracked callback handling without broadening those
owners' completion rules.

An already-checkpointed duplicate exact successful callback resumes this same
idempotent provider/local settlement. It never starts transport again.

### 8. Role changes and startup share a recovery gate

The state database installs the coordinator only after a non-destructive open.
Until then, or after a storage-open failure, the composer reports recovery pending
and refuses sends.

Startup recovery runs under the existing serialized messaging-recovery lane after
the SMS role is held. It never calls `sendSms`:

- `RESERVED` becomes `KNOWN_UNSENT`;
- `PREPARED` first becomes durably `KNOWN_UNSENT`, then retries conditional
  terminalization of only its exact app-owned provider row;
- `SUBMITTING` and `PLATFORM_ACCEPTED` become `SUBMISSION_UNKNOWN`;
- `SENT_CALLBACK_SUCCEEDED` retries only exact provider reconciliation and local
  completion;
- bound `KNOWN_UNSENT` retries only the exact provider failed-status write; and
- terminal known/unknown records remain for their explicit UI actions.

Exact `APPLIED`, `ROW_ABSENT`, and `OWNERSHIP_CONFLICT` dispositions terminally
settle provider work after durable sent or pre-boundary known-unsent proof.
Unavailable provider access defers only its exact operation. A valid bounded Room
snapshot is sufficient to open unrelated Threads; the Thread with an active
operation remains governed by that operation. An unreadable or corrupt Room
snapshot or role loss remains global. Role loss immediately fences new
acceptance, cancels pending recovery, and preserves drafts and operations. Role
restoration re-runs recovery before Send becomes ready.

Live typed classification failures and callback-timeout storage failures also
request the same bounded recovery lane. This retry work never invokes transport
and never turns uncertainty into an automatic resend. Exact callback checkpoint
retry is separate because it preserves the stronger callback proof until Room can
commit it.

## Local acceptance evidence

The final-source `0.5.0-phase5` worktree passed the complete 886-task offline
host/release/privacy/license aggregate in 1m27s (90 executed, two from cache,
794 up-to-date), with all 508 host JUnit results green. `bundleRelease` passed
269 tasks in 7s. CycloneDX 1.6 passed 15 up-to-date tasks in 7s and reports 441
components and 442 dependencies.

The complete API 26 connected matrix passed 291 tests with 13 intentional skips
(278 executed) in 1m54s, and the complete API 36 matrix passed 288 tests with 10
intentional skips (278 executed) in 1m31s. Both had zero failures/errors across
456 Gradle tasks. Focused API 36 root composer and external-compose isolation
each passed 1/1. No acceptance path invoked a real destination or carrier send.

Artifact inspection with pinned build-tools 36.0.0 confirms package/version
identity, minimum API 26, target API 36, `debuggable` only on the debug app, and
no `INTERNET` or `ACCESS_NETWORK_STATE` in app variants. The separate
macrobenchmark test APK retains only its expected debuggable/tooling-network
surface. The release APK is unsigned and is not a distribution artifact. Exact
artifact sizes and hashes are retained in `docs/TEST_MATRIX.md`.

Only API 26 and API 36 emulators were attached. The exact debug APK installed
and copied to the API 36 emulator with a matching device-side SHA-256, then
cold-launched to the expected role-approval screen without role or SMS-permission
mutation. This is local worktree evidence and does not claim a final commit, CI
run, physical-device handoff, physical device/SIM, OEM,
carrier submission, billing, roaming, sent/delivery behavior, reboot, or process
death during a real network send. The Phase 5B acknowledged-unknown late-provider
cleanup residual remains open. AuroraSMS is not complete or gold.

## Consequences

- The first normal composer send is intentionally narrow and one-unit only.
- Schema 4 migrates explicitly to schema 5 without modifying existing draft or
  appearance tables.
- The operation store is content-free; the draft remains the sole durable owner
  of message text until exact callback completion.
- Saved state is only an exact-base restoration hint, and the atomic draft freeze
  makes the persisted revision and submitted content one snapshot.
- Users may see known-unsent or uncertainty instead of a convenient automatic
  retry. That is the intended duplicate-prevention behavior.
- A successful sent callback is checkpointed before provider reconciliation, so
  transient provider/storage failure cannot erase the callback proof.
- Manual unknown acknowledgement can leave the old provider row unreconciled;
  its mandatory duplicate warning and explicit Phase 5B residual are intentional.
- Fake and emulator tests can prove local ordering, persistence, role fencing,
  exact ownership, and zero-send preflight. They cannot prove a physical SIM,
  carrier submission, billing, roaming, OEM behavior, or delivery.

## Rejected alternatives

### Persist the message body in the operation

Rejected. The authoritative body is read from the exact Room draft inside the
reservation transaction and kept in memory only for the one transport call.
Duplicating it in operation state enlarges the private-data and recovery surface.

### Delete or consume the draft when reserving the operation

Rejected. A crash or later callback/reconciliation failure could lose the user's
only durable text. The draft stays intact until exact sent-callback proof and exact
provider reconciliation complete.

### Use the transport-owned journal for composer sends

Rejected. Composer state must bind exact draft identity/revision and drive Thread
UI. Giving the same send two pre-submission owners would make recovery ambiguous.

### Support more than one SMS unit in Phase 5A

Rejected for this slice. More-than-one-unit text would require per-unit outcome
bookkeeping and broader carrier/device evidence. The UI reports the calculated
unit count and keeps Send unavailable.

### Automatically retry `SUBMITTING` or uncertainty

Rejected because Android or the carrier may already have accepted the message.
Convenience cannot justify a possible duplicate SMS or charge.

### Update an outgoing provider row by message ID alone

Rejected because the ID can be stale or no longer owned by the expected Thread.
Completion requires the exact message-and-conversation pair and a terminal
`Success` outcome; row absence or guarded ownership conflict never authorizes a
foreign mutation or broader fallback.

### Treat emulator callback success as carrier acceptance

Rejected because fake transports and emulator modem behavior do not traverse the
user's carrier network. Physical/carrier evidence remains explicitly open.
