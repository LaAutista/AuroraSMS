# AuroraSMS threat model

Status: Phase 0 baseline plus accepted ADR 0007 managed-wallpaper controls,
implemented Phase 1 durable-message hardening through commit `7c9d848`, and the
bounded ADR 0008 Phase 5A source implementation in commit `17fc421`, followed
by accepted Phase 5B–5G controls and Phase 6A–6H presentation/action controls
through ADR 0023 and Room schema 12. Local/API 26/API 36 focused acceptance
passes through the Phase 6H implementation. Safe install/migration and
locked-device cold launch passed
on a Pixel 8. Real-carrier, radio, billing,
and invasive physical lifecycle evidence remains open.

## Security and privacy objectives

AuroraSMS must:

1. keep message content, addresses, contacts, attachments, searches, drafts,
   schedules, themes, and notification choices on the device unless the user
   explicitly exports or sends them;
2. prevent untrusted apps from invoking privileged message operations;
3. preserve the distinction between Telephony-owned messages, a rebuildable
   index, and durable Aurora-only state;
4. fail safely across role loss, permission revocation, process death, reboot,
   corrupt media, malformed imports, and carrier errors;
5. make destructive, privacy-reducing, and externally visible actions explicit;
6. keep private development references and real personal content out of source,
   tests, builds, telemetry, and public artifacts;
7. maintain an auditable no-Internet FOSS build and minimal dependency surface.

## Protected assets

| Asset | Sensitivity | Required protection |
|---|---|---|
| SMS/MMS bodies, subjects, participants, timestamps, statuses | Critical personal data | Telephony authority, private processing, no logs/network/OS backup |
| MMS attachments and local voice memos | Critical personal data | Lazy bounded decode, private/temp storage, narrow URI grants, cleanup |
| Drafts, scheduled messages, pending sends/deletes | Critical action data | Durable transactional state, process/reboot recovery, explicit commit semantics |
| Search index and queries | Critical derived data | Rebuildable private DB, no backup/logging, safe FTS parser |
| Contacts and photos | Sensitive personal data | Optional just-in-time read permission, cancellable bounded query, memory-only results, no contact writes or metadata persistence |
| Subscription/SIM choice | Sensitive device data | Minimal access, validated fallback, no identifier logging |
| Notification content/settings | Sensitive disclosure control | User-selectable privacy levels, lock-screen safe defaults |
| Delivery/reply claims, accepted reply operations, and notification generations | Critical action metadata | Bounded content-minimized no-backup stores, checksums, explicit state transitions, role-scoped recovery |
| Appearance profiles, scoped participant fingerprints/thread hints, temporary picker URIs, and managed wallpaper files/digests | Sensitive pseudonymous/private data, especially when user photos are used | Validated declarative data, target-specific access, transient URI capability, bounded no-backup private storage, no executable themes or appearance logs |
| Backups/exports/shares | Critical portable data | User initiated, versioned, validated, authenticated/encrypted before shipping |
| Signing key and release metadata | Critical supply-chain asset | Never in Git; encrypted owner-controlled offline backups |
| Private handoff screenshots/PDF | Critical development-only personal data | Ignored local-only reference; never copied, committed, packaged, or uploaded |

## Trust boundaries

```text
Carrier/baseband
    |
Android telephony framework and Telephony provider
    | guarded default-SMS role + runtime permissions
Aurora telephony adapters
    | typed bounded models
Aurora index DB (rebuildable)  <->  Aurora state DB (durable)
                                      |
                         private managed wallpaper store
    | repositories and paged flows
Aurora UI / notifications / explicit user actions
    |
System picker, SAF, share targets, exports, and external recipients
```

Additional boundaries exist at exported Android components, package/role
manager, Contacts and Subscription providers, notification listeners/lock
screen, temporary picker URIs, any future persisted document URIs, the private
managed-media directory, dependency/build tooling, and Git/CI.

Carrier MMS transport is not general application Internet access. AuroraSMS
uses documented telephony APIs and does not declare `INTERNET` in the FOSS
build.

## Actors and adversaries

- another installed app sending forged implicit intents, broadcasts, content
  URIs, or oversized payloads;
- a person with transient access to an unlocked device or lock-screen
  notifications;
- a notification listener, screen recorder, accessibility service, backup
  agent, or share target authorized by the OS/user;
- malformed or hostile SMS/MMS PDUs, attachment metadata, GIFs, images, vCards,
  theme JSON, or backup archives;
- a malicious contact name/avatar or filename designed for spoofing;
- a removed/swapped SIM or unexpected default-role/permission change;
- a compromised, typosquatted, unmaintained, or unexpectedly networked
  dependency/build plugin;
- an accidental developer action that stages private reference material,
  device data, logs, keys, or reports;
- carrier/network observers inherent to SMS/MMS transport.

## Security assumptions and non-goals

AuroraSMS relies on the Android application sandbox, verified platform APIs,
credential-encrypted storage, OS lock screen, and telephony stack. V1 does not
claim protection against a compromised/rooted OS, unlocked forensic access,
baseband/carrier compromise, malicious firmware, or a user-authorized service
that can already read the screen/notifications.

SMS/MMS is not end-to-end encrypted by AuroraSMS. AuroraSMS does not provide an
RCS network, hide messages from the carrier/recipient, guarantee delivery,
provide universal read receipts, or turn app lock into storage encryption.

## Threats and controls

### T1: unauthorized component invocation

Threats: forged `SENDTO`/respond-via-message intents, spoofed delivery
broadcasts, mutable `PendingIntent` substitution, path traversal, or hostile
URI grants could send, disclose, or persist messages.

Controls:

- export only the official default-SMS entry points and reviewed share/deep
  links;
- apply official guarding permissions to delivery receivers and respond
  service;
- use explicit app-scoped sent/delivered callbacks and correct immutable flags;
- validate action, role state, scheme, MIME type, recipients, subscription,
  extras, URI authority, grant flags, sizes, and attachment count;
- never send merely because an external compose intent opened the app;
- keep externally supplied compose text non-durable until the user edits it
  inside Aurora;
- keep dangerous actions behind visible user confirmation and fresh state.

### T2: role or permission confusion

Threats: AuroraSMS writes after losing the SMS role, assumes a canceled grant,
or requests restricted permissions before explaining/obtaining role eligibility.

Controls:

- check role availability/held state at onboarding and immediately before
  privileged writes/sends;
- request role before associated SMS permissions;
- handle cancel, deny, revoke, role loss, and reacquisition as first-class
  states;
- serialize role-change and eligibility reconciliation behind a fence that
  re-reads authoritative platform role state instead of trusting broadcast
  extras; on confirmed loss, disable new recovery, cancel and join pending
  recovery jobs, then fence incoming persistence/recovery before cleanup;
- cancel only valid Aurora incoming-notification slots with exact generation
  evidence, rebuild durable generation state from an authoritative active-
  notification snapshot, and clear reply targets after bounded cleanup;
- treat role loss after an accepted carrier submission as non-recallable:
  reconcile exact owned callbacks when possible, but never guess or resubmit an
  operation whose platform submission boundary is uncertain;
- for a caller-owned composer send, capture a fence generation before acceptance,
  recheck authoritative role plus that generation before and after each awaited
  `PREPARED` and `SUBMITTING` checkpoint, and check role once more immediately
  before the `SmsManager` Binder call. A loss proven at that final pre-boundary
  check is known-unsent; any possible post-boundary acceptance is uncertainty;
- make the frozen-draft handoff and reserve-through-immediate-classification
  envelope non-cancellable. If reservation may have committed before a thrown
  cancellation, consult Room and retain fail-closed ownership; never infer a
  refusal merely from the caller's cancellation;
- stop writes and pending transport safely on role loss;
- keep drafts and read-only UI truthful without claiming full coverage.

### T3: message/index corruption or confusion

Threats: SMS/MMS provider ID collisions, timestamp ties, partial sync, duplicate
receiver/observer events, destructive migration, or index corruption cause
missing, duplicated, or wrong-thread messages.

Controls:

- compound provider identity plus local `Long` row ID;
- deterministic timestamp/row keyset ordering;
- transactional bounded upserts and committed checkpoints;
- idempotent/coalesced event handling and reconciliation;
- a bounded private pending/stored/complete delivery journal that fingerprints
  raw SMS PDUs without retaining them, serializes concurrent delivery, and
  recovers the provider-insert/notification boundary after process death;
- journal v4 binds its preference key to a canonical checksummed record and a
  redacted, domain-separated provider-content digest. Malformed or poisoned
  records become checksummed, key-bound `Q1` quarantine tombstones, so they
  retain ownership of the original delivery key while unrelated valid entries
  remain recoverable;
- the incoming replay journal remains bounded to 512 owned entries. Capacity
  pressure evicts the oldest `COMPLETE` ownership, so an extremely old exact
  redelivery after eviction can still be inserted again; this residual is not
  represented as impossible merely because recent replay is owned;
- stage an outgoing provider row atomically as app-owned, known-unsent `FAILED`
  with a dedicated sentinel and require exactly one durable owner. Notification
  inline reply uses the caller-owned private reply-operation store and reserved
  high operation IDs; `RESPOND_VIA_MESSAGE` uses ordinary low IDs and the
  transport-owned private, content-free outgoing journal. Durably bind the
  exact provider row as `PREPARED` before one conditional arm may consume the
  sentinel and make it `PENDING`, then durably record `SUBMITTING` before the
  irreversible platform call;
- for the existing-Thread composer, keep one bounded Room operation and bind
  only the exact Thread, draft ID/revision, subscription, phase, prepared
  provider IDs, one-unit count, timestamps, and—after ADR 0018—an optional
  immutable 160-character frozen signature. The draft remains the sole durable
  owner of the user-authored message body;
- treat app-private composer `SavedState` as an untrusted restoration hint rather
  than send authority. Hide its text until Room is read, require its exact base
  draft ID/revision to match (or require Room absence for a base-free hint), and
  discard stale or mismatched content. Atomically freeze edit acceptance and
  drain every earlier accepted write before capturing the one send snapshot;
- on synchronous pre-boundary refusal or cancellation, conditionally
  terminalize only an exact Aurora-created row in an allowed staging, armed, or
  terminal state. Treat absence as retired and turn an ownership, creator,
  thread, or state conflict into a known-unsent quarantine tombstone without
  changing a foreign or reused row. Inherited `PREPARED` retries exact cleanup;
  inherited `SUBMITTING` becomes `SUBMISSION_UNKNOWN` and is never rearmed or
  resubmitted;
- bound the transport-owned journal to 128 content-free records. Never evict
  active `PREPARED` or `SUBMITTING` ownership; only `SUBMISSION_UNKNOWN` and
  known-unsent quarantine tombstones expire after seven days. Fail new work
  closed at capacity and fail all transport-owned new work closed when the
  journal is corrupt, noncanonical, or uncommittable. A transient cleanup
  failure defers only its exact provider record and does not by itself globally
  block independent recovery or unrelated sends;
- recover composer operations without invoking transport. A valid bounded Room
  snapshot opens unrelated Threads even when provider access defers one exact
  operation; the owning Thread stays gated. Only role loss or an unreadable or
  corrupt operation snapshot globally blocks composer acceptance;
- commit exact one-unit composer sent-callback proof before provider settlement.
  Duplicate exact successes resume the same idempotent path. After durable sent
  proof or durable pre-boundary known-unsent proof, treat exact guarded provider
  `Success(APPLIED)`, `Success(ROW_ABSENT)`, and
  `Success(OWNERSHIP_CONFLICT)` as terminal: absence and conflict mutate no
  foreign row and never authorize an ID-only fallback. Provider access,
  permission, or storage failure defers the exact operation;
- retain a transient exact callback only by its content-free operation/binding
  identity for bounded checkpoint retry, schedule bounded non-sending recovery
  when typed classification or timeout storage work fails, and resubscribe Room
  observation after recoverable failures so an open Thread cannot remain stuck
  on a stale storage error;
- verify commit-ambiguous terminal transactions by exact operation identity. A
  proven missing row publishes one bounded, deduplicated process-local completion
  or acknowledgement signal so a correct atomic draft clear cannot leave stale
  text frozen, and an acknowledged unknown cannot leave its preserved draft
  locked;
- consume malformed, wrong-owner, missing-operation, or late explicit
  `COMPOSER` callbacks without allowing fallthrough to another outgoing owner.
  Manual acknowledgement of `SUBMISSION_UNKNOWN` preserves and reopens the draft
  only after a duplicate-risk warning; because it removes the durable operation,
  a later callback may leave the old provider row unreconciled. Keep that cleanup
  limitation explicit as a Phase 5B residual;
- do not sweep `PENDING` rows left by pre-journal alpha builds: without an exact
  durable record, upgrade recovery has no authority to identify or mutate them
  and does not claim to repair their status;
- the `goAsync()` lease watcher may time out and finish the broadcast lease
  without cancelling already accepted work in the sibling process-local
  coroutine. This limits receiver-deadline cancellation only: Android process
  death can still stop that work, so recovery relies on durable checkpoints
  and later lifecycle triggers, not on coroutine survival;
- separate rebuildable index from durable state;
- explicit migrations and migration tests from version 1; never destructive
  fallback;
- integrity/coverage UI that does not overstate search completeness.

### T4: search injection and resource exhaustion

Threats: raw FTS syntax or large/complex queries cause SQL injection, crashes,
long scans, memory pressure, or misleading results.

Controls:

- Unicode normalization, token limits, safe FTS escaping, bounded phrase/prefix
  behavior, and no concatenated raw SQL;
- debounced cancelable paged queries;
- keyset paging and exact anchor jumps, not deep OFFSET or unbounded lists;
- deterministic large-history, same-timestamp, Unicode, RTL, and malformed
  query tests;
- time/memory budgets and cancellation on lifecycle changes.

### T5: hostile or oversized media

Threats: decompression bombs, corrupt GIF frames, huge dimensions, malformed
MMS parts, unsupported MIME, stale URIs, provider bytes changing after
selection, corrupt managed files, orphan private files, or multiple active
decoders exhaust storage/memory/CPU, disclose metadata, cross targets, or crash
the app.

Controls:

- inspect metadata and enforce byte, dimension, frame, duration, and attachment
  limits before full decode;
- downsample to the exact display/send target off the main thread;
- bounded caches, static previews, and only one visible animated decoder;
- pause/release animation for lifecycle, reduced motion, display-off, and battery
  conditions;
- treat MIME and extensions as untrusted, fail closed, and offer recovery;
- never decode MMS media while text indexing;
- for ADR 0021 voice memos, request microphone access only from the explicit
  Record action, show continuous capture state, allow at most one foreground
  capture, and cancel/delete it on Thread exit, backgrounding, or cancellation;
- write at most 60 seconds/524,288 bytes to an owned `noBackupFilesDir` file,
  validate canonical parent and size before one bounded read, never serialize
  bytes into UI/preferences/Room/alarms/backup/logs, and require a separate
  reviewed Send action;
- compose only one-person SMIL/optional-text/`audio/mp4` with the pinned
  outgoing-only AOSP subset, cap the PDU at 1,048,576 bytes, persist provider
  parts before one exact owned row, and permit carrier submission only after an
  exactly applied OUTBOX transition;
- journal only content-free exact identities; roll back PREPARING state only by
  creator/Thread/transaction/FAILED identity, quarantine any at-boundary process
  death or exception as submission-unknown without retry, and authenticate the
  exact private callback before provider mutation;
- for ADR 0007 specifically, accept only bounded 8-bit Huffman baseline
  sequential-DCT (`SOF0`) JPEG with at most four components and complete scan
  coverage, or CRC-valid non-APNG PNG with at most 4,096 chunks, no
  `iCCP`/`zTXt`/`iTXt` ancillary chunks, and a complete zlib scanline stream;
  reject progressive, extended sequential, arithmetic, lossless,
  differential/hierarchical, and non-8-bit JPEG, APNG, GIF, every input WebP,
  HEIF, AVIF, malformed/partial data, a source above 16 MiB, an 8,192-pixel
  edge, or 40,000,000 pixels, and an output above a 2,048-pixel edge,
  4,194,304 pixels, or 16-MiB allocation;
- normalize orientation and color space off-main, strip source metadata, and
  encode one static private WebP no larger than 8 MiB before persistence;
- admit at most 128 distinct assigned managed wallpapers and 256 MiB total,
  with no LRU eviction of an assigned target; one serialized pre-CAS sanitized
  candidate of at most 8 MiB may temporarily raise physical storage to 129
  files/264 MiB, but is not durable quota, is removed on rejection/cancellation,
  and is collected as an orphan only after healthy startup validation;
- keep the app-private single-process namespace behind one process-wide mutex;
  durably create parent directories, use `O_EXCL|O_NOFOLLOW` pending leaves,
  sync and verify exact device/inode/single-link identity plus content, recheck
  final absence, atomically rename, reverify, deliver a synchronous cleanup
  lease, and sync the leaf directory before Room CAS;
- delete a failed/new or replaced/old candidate only after a fresh bounded Room
  reference check, and reconcile with a fail-closed two-pass scan so any unsafe
  entry or unavailable/corrupt/over-limit authority causes zero partial
  deletion; and
- share a two-permit app media-decode gate with MMS while allowing only one
  wallpaper import/decode and one current wallpaper allocation.

### T6: notification and screen disclosure

Threats: lock-screen bodies, recents thumbnails, screenshots, screen sharing,
inline replies, or notification listeners expose content.

Controls:

- notification privacy levels: sender/body, sender only, generic;
- safe channel/lock-screen defaults and explicit inline-reply authentication
  expectations;
- immutable notification routing plus one narrowly mutable explicit reply
  intent; app-private target, consumed-claim, reply-operation, and incoming-
  notification-generation stores use versioned canonical encodings,
  synchronous security-boundary commits, and checksums. The target retains the
  exact validated recipient required for cold-process routing but no message
  body; the claim stores a recipient digest; operation and generation records
  retain only bounded provider-qualified IDs, lifecycle/progress/status, and
  exact notification-generation ordering evidence;
- consume a reply claim durably before platform submission, retain it through
  token expiry, clear targets on role loss, and replace crash-replayed
  notifications without a second alert;
- make reply-failure alerts generic and body-free: they do not echo the reply,
  recipient, address, transport error, or message content and route the user
  back to AuroraSMS to confirm status before retrying. Bind each alert tag to
  both conversation and durable reply-operation identity, and carry the same
  operation marker in its notification extras;
- on later positive evidence, cancel only the matching operation-scoped alert
  and exact source notification generation. Preserve unrelated failures in the
  same conversation and retain durable success ownership until post-crash
  replay establishes and acknowledges those same idempotent cancellations;
- on first role-enabled recovery after upgrading from the pre-operation-key
  alpha, dismiss any still-active conversation-only generic reply-failure
  alerts because they cannot be mapped safely to one durable reply operation.
  Do not recreate previously user-dismissed alerts or change message/provider
  state or durable late-callback ownership; users must verify those replies in
  the conversation. Defer pending replay and retry recovery when legacy-alert
  enumeration or cancellation fails. If a migrated success lacks historical
  source-message identity, cancel its operation-scoped failure alert but leave
  success acknowledgement pending because no exact incoming generation can be
  chosen;
- optional secure-recents behavior with honest screenshot/sharing consequences;
- optional `BiometricPrompt`/device credential gate without encryption claims;
- no sensitive notification text in logs, analytics, or crash uploads.

### T7: backup, export, import, and share leakage

Threats: OS backup, temporary files, world-readable paths, malicious archives,
zip bombs, path traversal, newer schemas, missing media, or accidental share
targets disclose or corrupt data.

Controls:

- `allowBackup=false` plus explicit data-extraction exclusions;
- user-initiated SAF destinations and narrow temporary URI grants;
- versioned streaming formats with canonical paths, count/size limits, checksums,
  authentication/encryption decision, schema validation, and dry-run summary;
- no object deserialization or executable theme/import content;
- atomic import staging and rollback; never overwrite on partial validation;
- delete temporary share/export files after completion and again at startup;
- keep ADR 0007 picker access temporary and in memory, never persist its URI or
  grant, and place only the sanitized derivative in `noBackupFilesDir`; and
- reconcile managed pending/orphan files only when durable assignment state is
  healthy and authoritative, deleting nothing on database failure/corruption.

Backup/restore remains blocked until a dedicated format/security ADR defines
confidentiality, authentication, key recovery, media limits, and corruption
behavior.

### T8: scheduled-send and deletion safety

Threats: process death, reboot, time/zone change, duplicate alarms, revoked
exact-alarm access, SIM removal, or stale UI causes unintended sends/deletes.

Controls:

- durable idempotent operation IDs and explicit state machines;
- revalidate recipients, content, role, subscription, and due state at commit;
- make missed/failed schedules visible and never silently switch SIM/transport;
- group conversations never fan out;
- deletion confirmation and optional short pre-provider-write Undo only;
- after provider deletion succeeds, never represent it as recoverable;
- no recycle-bin storage or worker.

### T9: spam and blocking errors

Threats: false positives hide important messages, malicious rule inputs cause
denial of service, or unexplained blocking prevents recovery.

Controls:

- local, explainable, bounded rules; contacts trusted by default;
- warn/highlight only; no automatic hide policy;
- visible classification reasons and explicit spam/not-spam, block/unblock
  recovery;
- never silently delete suspected spam;
- normalize sender/rule input safely and test emergency/short/alphanumeric
  senders;
- no remote reputation or network lookup.

ADR 0019 resolves the Phase 6E defaults: automatic classification only warns
an unknown conventional phone sender when a link, reviewed urgency term, and
reviewed sensitive-request term all appear in the newest incoming snippet.
Saved contacts, short codes, alphanumeric senders, groups, outgoing messages,
and incomplete identities are not automatically warned. If contact access is
unavailable, automatic rules pause instead of treating unresolved senders as
unknown. There is no auto-hide
policy. Explicit spam/not-spam and block/unblock decisions are independent and
recoverable, and every dedicated-route row is revalidated against a complete
exact current identity.

### T10: appearance/import spoofing and contrast failure

Threats: user wallpapers obscure actions, red artwork looks like a live error,
malicious theme values create invisible controls, or one conversation's media
leaks into another.

Controls:

- deterministic contrast scrim and automatic accessible-color correction;
- validated ranges/schema for every token, with safe fallback;
- immutable scoped profiles and explicit precedence;
- versioned conversation fingerprints derived only from verified-complete,
  non-truncated participant sets, with no raw participant addresses stored in
  appearance tables and no resolution by provider thread ID alone;
- target-specific assignment flows and redacted models/logs so one
  conversation's fingerprint or appearance cannot leak through another target;
- one non-reusing monotonic assignment-revision sequence, so a stale revision
  cannot regain authority after a target is reset and recreated; physical
  singleton/exact-increment/no-delete triggers and a maximum-live-row floor
  check fail closed on rollback or tampering;
- semantic index versioning that preserves searchable rows but revokes all
  pre-v3 participant-completeness claims, forces a fresh scan from empty
  checkpoints, and leaves conversation assignment inherited while pending;
- a bounded app-private `SavedState` target token whose conversation form
  contains only the thread hint and one-way fingerprint; target mismatches are
  discarded synchronously, mutation stays disabled while durable profiles or
  the exact target assignment load, and the token is never logged, displayed,
  analyzed, or exported;
- keep `ConversationSummary` as an at-most-8-member display preview and use a
  separate exact-thread/exact-generation query limited to 101 rows (100 plus one
  overflow sentinel) for appearance identity. Emit a 1-through-100-member
  identity only when verified-complete coverage, entity/row thread and generation,
  false truncation, and declared/returned counts agree exactly;
- acknowledge that the private rebuildable index persists participant addresses
  in `indexed_conversation_participants.address`, while keeping the derived
  `VerifiedConversationIdentity` object/list ephemeral and redacted between the
  index repository, Thread holder, and immediate one-way fingerprint derivation;
  never add that derived identity to appearance persistence or `SavedState`, or
  log, export, analyze, or display it;
- clear exact identity before invalidation reload, require the current complete
  coverage generation and route thread at use, close an open scoped editor when
  identity becomes unavailable or changes, and inherit `global_thread` on every
  pending, stale, oversized, truncated, duplicate, count-mismatched, or terminal
  failure;
- distinguish timeline Ready from exact-identity lookup completion. An
  unresolved Ready state has no identity authority and cannot expose appearance,
  but it also cannot erase a restored target; resolved-null or terminal failure
  clears that target, and invalidation publishes resolved-null before re-query;
- independent per-screen/per-conversation media references and reset semantics;
- for ADR 0007, limit media authority to `global_thread` and an exact verified
  conversation; store only a redacted content digest plus assignment-local
  focal/dim/revision, never the picker URI, source metadata, path, or file name;
- reset to an accessible solid synchronously before reading a changed target,
  and publish a decode only when target, media token, assignment revision, and
  request epoch still match, so one conversation's old pixels cannot flash in
  another;
- treat missing, malformed, or hash-mismatched private media as unavailable and
  advance conversation -> `global_thread` -> accessible solid without silently
  mutating another assignment;
- 4.5:1 body-text target, 3:1 non-text affordances, 48 dp targets, TalkBack,
  RTL, 200% font, and reduced-motion tests;
- theme imports are declarative and never execute code.

Verification evidence for the exact-thread follow-on remains content-free. The
final 13,212,416-byte debug APK, SHA-256
`39a07d72b7c58b91a11b152458ba971161b1edd98883f68df4fdbc6ab724235d`,
installed successfully on the Pixel 8 and matched its Download copy. The
targeted privacy-safe MainActivity/Inbox smoke passed 1/1 in 17s across 197
tasks. Sole-package, default-role, required-grant, and cold-launch checks passed;
the error-only process log contained only the benign ashmem-pinning deprecation.
No physical 9-member Thread or participant data was exercised. Source commit
`83db9aa0f02cef44644f53d0bb149abe459dc20b` is pushed on `origin/main`, and its
GitHub Verify run `29380854714` passed its 10m59s build job with all project
steps green. Its only annotation was GitHub's hosted Node 20
deprecation/forced-Node-24 notice for pinned actions, not a project failure.

### T11: supply-chain or build privacy regression

Threats: a dependency adds network access, manifest components, trackers,
vulnerabilities, native code, or copied provenance; CI uploads sensitive data.

Controls:

- pinned allowlist, checksum verification, lockfiles, SBOM/license report, and
  merged-manifest inspection;
- ban dynamic/JitPack/reference-app artifacts and `org.fossify:*`;
- fail every FOSS variant on `INTERNET`, unapproved permissions, repositories,
  startup initializers, or exported components;
- admit only the release/benchmark ProfileInstaller initializer and its
  DUMP-protected receiver, while debug removes both and release rejects every
  synthetic benchmark authority, permission, and fixture marker;
- isolate the Phase 3 Macrobenchmark/Perfetto network and component exception
  in a separate test APK, while build-identity checks prove normal app variants
  contain neither the exception nor the signature-protected synthetic fixture;
- self-instrument the test package so its runner and test-only dependencies
  never execute in the default-SMS app process;
- allow the fixture only fixed seed/shape commands against the known private
  rebuildable index, reject role/permission/provider bypass strings, and expose
  no query/insert/update/delete data plane;
- permit one debug-only SMS snapshot provider for disposable-emulator evidence:
  it is exported only in debug, guarded by the platform `DUMP` permission and
  an explicit Binder shell-UID check, exposes only `_id`, `thread_id`, and
  `type`, and rejects selection, sorting, writes, calls, and files. Merged-
  manifest verification requires that exact debug boundary and rejects its
  authority or class from every non-debug variant;
- no real device data/private references in tests or CI artifacts;
- reproducible release instructions, signed checksums, and new signing identity.

### T12: private development-reference leakage

Threats: a screenshot/PDF or personal pixel/text appears in Git, fixtures,
goldens, generated docs, issues, APK resources, or store media.

Controls:

- ignore the entire local handoff directory;
- pre-commit/CI tracked-path checks and APK/resource inventory;
- no OCR/transcription into implementation artifacts;
- synthetic-only fixtures and public screenshots;
- clean-room incident process if prohibited input becomes visible.

### T13: contact discovery disclosure or authority expansion

Threats: onboarding requests contact access without context; a broad or stale
query retains names, numbers, or photo references; lifecycle or query changes
leave provider work running; malicious metadata reaches diagnostics; or a
future manifest change silently adds contact-write authority.

Controls:

- never request contacts permission during onboarding, startup, Inbox entry, or
  merely by opening New chat; the user must open **Find contacts** and then
  explicitly choose the in-panel permission action;
- retain bounded manual number entry as the complete denial, cancellation,
  revocation, and provider-failure fallback;
- accept only trimmed 1-to-100-character, control-free queries; request 20
  results in N2A, cap the public contract at 50, and inspect at most one extra
  provider row to disclose truncation;
- project only phone number, display name, and photo URI; validate and bound all
  returned fields, canonicalize unique selectable addresses, and never log
  their values or expose them through diagnostic strings;
- debounce the UI query, perform it off the main thread, propagate coroutine
  cancellation to the Contacts provider, hide stale replacement-query results,
  and clear query/results on panel close, Activity stop, or permission loss;
- keep query, labels, photo references, and results in memory only. Panel close
  clears the query and unselected results; a selected bounded display label may
  remain only for its recipient chip until removal, Activity stop, or permission
  loss. An explicit selection contributes only the validated address to the
  existing bounded recipient/draft authority;
- declare only `READ_CONTACTS` in production, forbid `WRITE_CONTACTS`, strip
  `READ_CONTACTS` from the isolated benchmark, and retain exact merged-manifest
  and packaged-APK permission equality checks; and
- perform no Contacts/Telephony provider write, provider-thread resolution, or
  SMS/MMS transport from discovery or selection.

### T14: first-contact provider authority ambiguity

Threats: New chat resolves or creates a provider thread before durable user
intent exists; a crash or cancellation after provider entry is mistaken for no
mutation and retried; equivalent recipient formatting creates sibling owners;
a stale draft or removed SIM is silently substituted; a resolved thread is
rebound; or a participant draft is rekeyed over an existing thread draft before
carrier submission.

Controls:

- reserve one schema-15, content-free first-contact operation against the exact
  participant-draft ID and revision before entering provider authority;
- use a first-contact-domain SHA-256 fingerprint of shared semantic recipient
  keys to reject reordered or harmlessly reformatted sibling reservations,
  while retaining the exact draft identity check as content authority;
- persist `RESOLUTION_STARTED` with compare-and-set before allocation. Once
  that fence exists, every non-verified result, cancellation, or exception
  becomes `RESOLUTION_UNKNOWN` and is never automatically retried;
- require current default-SMS role and `READ_SMS` before resolver entry, then
  verify a positive returned thread through a bounded exact provider recipient
  readback before a write-once binding can be committed;
- retain typed active-subscription failures, require the exact selected ID to
  remain active and SMS-capable at each authority boundary, and never substitute
  the platform default SIM;
- bridge only from the exact bound operation in one Room transaction: re-read
  the source draft identity and revision, reject a target-thread sibling, rekey
  the same draft at a strictly newer revision, and preserve its body, subject,
  creation time, and attachment ownership;
- retain `HANDOFF_RESERVED` until a future transaction reserves the mature
  composer journal; N2B cannot delete that owner, stage a provider message,
  register a callback, or invoke SMS/MMS transport; and
- keep the Android resolver and headless coordinator outside the New chat and
  production application graph until the separately reviewed N2C activation.

## Privacy defaults

- No general network permission or network-capable SDK.
- No analytics, crash upload, telemetry, account, ads, remote configuration,
  remote themes/fonts/GIF search, or reputation lookup.
- Credential-encrypted private storage only for normal app data.
- No OS/cloud backup of messages or derived state.
- Release logs redact bodies, addresses, URIs, searches, file names, and SIM
  identifiers; debug logs use synthetic fixtures only.
- Appearance participant fingerprints and provider thread hints are sensitive
  pseudonymous identifiers: exclude them from logs, `toString`, telemetry,
  exports, and OS/cloud backup. The private restoration token that combines
  them in bounded `SavedState` has the same restrictions.
- Composer restoration `SavedState` may contain bounded unsaved draft text only
  as an exact-Room-base hint. Exclude it from backup, logs, diagnostics, exports,
  and callback identity; hide it until base validation and discard it on mismatch
  or successful completion.
- Contacts permission is optional and just in time. Denial, cancellation, or
  revocation leaves number-based messaging usable; queries and result metadata
  remain memory-only.
- Destructive and external actions require explicit, contextual user intent.

## Security verification obligations

Every applicable phase gate includes:

- merged-manifest permission/export/backup inspection;
- role grant, denial, loss, and reacquisition tests;
- forged/malformed intent and URI tests;
- process-death/reboot/time-change state tests;
- large/corrupt/animated-media rejection, managed-file hash/fallback,
  quota/atomicity/cleanup, and archive parser tests;
- dependency/provenance/SBOM scan;
- private-path/tracked-resource/APK inventory scan;
- no-sensitive-log checks;
- N2A bounded contact-discovery contract, cancellation/permission-loss tests,
  production `READ_CONTACTS`/absent-`WRITE_CONTACTS` manifest assertion,
  isolated-benchmark permission removal, and API 26/API 36 synthetic UI/provider
  tests with zero contact/provider write or carrier send;
- Phase 5A content-free Room/schema tests, exact saved-state base restoration and
  stale-discard tests, atomic draft-freeze race tests, awaited checkpoint and
  role/fence race tests, per-operation recovery isolation, duplicate callback
  settlement, and all three exact terminal provider dispositions;
- production Phase 5A preflight on API 26 and API 36 only with role or permission
  unavailable, a synthetic recipient, and assertions of zero provider mutation,
  zero checkpoint, and zero platform/carrier send;
- owner-gated API 26 and API 36 real-provider tests for the exact failed/staging-
  sentinel insert, one-shot pending arm, sentinel consumption, wrong/stale arm
  rejection, conditional terminalization, foreign-row preservation, and exact
  synthetic-row cleanup without carrier submission;
- API 26 and API 36 notification-identity tests for independent same-
  conversation reply-operation tags, exact late-success cancellation, sibling
  preservation, retryable legacy cleanup, and crash replay between success
  effects and durable acknowledgement;
- Phase 6F golden/corpus tests, permission-on-tap contract, actual API 26/API 36
  virtual-microphone capture and foreground cleanup, exact callback identity,
  crash-ordering/journal recovery, and isolated provider part/status/rollback
  tests with zero live-provider or carrier access;
- physical-device SMS/MMS behavior where the phase claims transport support;
- accessibility/contrast/lifecycle tests for privacy-related UI.

The following retained baseline predates the Phase 5A composer worktree.
Final-source focused verification for the pre-Phase 5 durability slice completed
a 320-task host gate with telephony
75/75, core testing 22/22, and app 191/191, plus lint and app/telephony
`androidTest` compilation. The transport-owned submission journal passed 7/7
on API 26 and 7/7 on API 36. The owner-gated real-provider contract passed 1/1
on each API without invoking `SmsManager`; its assertions cover staged insert
and arm, wrong-thread conflict preservation, idempotent terminalization, absent
exact URI, and exact synthetic-row cleanup. Notification identity passed 29/29
on each API, including real `NotificationManager` sibling preservation. A fresh
disposable API 26 SystemUI `inline-reply-permission-denied` journey passed with
exact cleanup and its overlay was discarded.

The final API 26 connected matrix was `BUILD SUCCESSFUL` in 1m51s across 456
tasks; preserved console module roots record 274 tests, 13 intentional skips,
and zero failures/errors.
API 36 was `BUILD SUCCESSFUL` in 1m24s across 456 tasks, with 271 retained tests,
10 intentional skips, and zero failures/errors. The complete
host/release/privacy/license aggregate was `BUILD SUCCESSFUL` in 1m19s across
886 tasks (130 executed, seven from cache, 749 up-to-date). CycloneDX 1.6 passed
15 tasks in 8s with 441 components and 442 dependencies. The debug APK is
13,993,426 bytes with SHA-256
`16037c616d6d696b4974f3e3a14238c18937c6f677f2f60e677ca10f0ea0ef98`.

The first API 26 aggregate attempt remains diagnostic only: it exposed a
channel test disabling the production reply-failure channel. The corrected test
uses a dedicated test-only channel, and only the later clean matrix is pass
evidence. That pre-Phase 5 implementation and its tests are frozen in commit
`3d7182c`. Wider
carrier, physical/OEM, API 27
through 35, process-death, MMS, and lifecycle evidence remains open; these green
baseline gates do not make AuroraSMS complete, release-ready, or gold.

The Phase 5A `0.5.0-phase5` (`versionCode` 4) worktree subsequently passed the
complete 886-task offline host/release/privacy/license aggregate in 1m27s (90
executed, two from cache, 794 up-to-date). All 508 host JUnit results passed.
`bundleRelease` passed 269 tasks in 7s, and CycloneDX 1.6 passed 15 up-to-date tasks in 7s with
441 components and 442 dependencies. Complete API 26 and API 36 connected
matrices each executed 278 non-skipped tests with zero failures/errors: API 26
recorded 291 total with 13 intentional skips in 1m54s; API 36 recorded 288 total
with 10 intentional skips in 1m31s. Focused API 36 root-composer and external-
compose-isolation gates each passed 1/1.

Pinned `aapt2` inspection confirms package/version identity, minimum API 26,
target API 36, `debuggable` only in the debug app, and no `INTERNET` or
`ACCESS_NETWORK_STATE` in any app variant. The macrobenchmark test APK's
debuggable/tooling-network surface remains isolated from app variants. The
release APK is unsigned and is not a distribution artifact. This local evidence
is frozen in implementation commit `17fc421`; it makes no pushed-CI or
physical-device handoff claim. The exact debug APK installed and hash-matched on the API 36 emulator, then
cold-launched to the expected role-approval screen without role or SMS-permission
mutation.

Only API 26 and API 36 emulators were attached for Phase 5A acceptance. No Phase
5A automated evidence is a real carrier send. Physical SIM,
carrier-network, billing, roaming, OEM, sent/delivery callback, and reboot or
process-death behavior during a real send remain open and require a separately
approved destination-aware protocol. The acknowledged-unknown provider-row
cleanup residual was open in the frozen Phase 5A source and is locally closed by
the Phase 5B schema-6 receipt protocol; that does not close any physical or
carrier gate. AuroraSMS is not complete or gold.

Phase 5C treats a remembered subscription as security-relevant routing
authority. The preference key uses its own purpose-separated verified-
participant hash domain and persists no address, message content, or SIM label.
SQLite triggers reject malformed keys and non-monotonic updates, optimistic
revisions prevent stale UI writes, and the coordinator re-reads the exact scope
before reservation. A missing, unavailable, stale, or unreadable preference
fails before provider staging or transport; the app never silently substitutes
another active SIM. Emulator evidence covers these local controls, while real
SIM/eSIM removal, carrier routing, billing, roaming, and OEM lifecycle threats
remain open.

Phase 5D implements ADR 0011's bounded scheduled-SMS state machine. Schema 8
stores only an operation ID, exact draft/thread/subscription bindings, a purpose-
separated participant hash, due/phase/precision codes, and clock anchors. Alarm
intents carry only that ID. Due handling checks wall-versus-elapsed drift and
revalidates the verified one-person identity, exact durable draft revision,
default role, remembered active SMS-capable subscription, and one-unit limit
before entering the existing durable send coordinator. Duplicate alarms are
idempotent; boot/time changes, removed SIMs, lost role, arming failure, and an
interrupted pre-reservation dispatch become visible review state and never
silently retry or switch SIM. Exact-access denial/revocation retains a labeled
inexact path plus a distinct safety alarm; physical OEM/Doze/carrier evidence
remains open.

Phase 5E implements ADR 0012's truthful pre-submission Undo boundary. Schema 9
stores only a bounded operation ID, exact draft/thread/subscription bindings, a
purpose-separated participant digest, due/phase/review codes, clock anchors,
and timestamps; it stores no message or recipient content. The normal timer is
process-local and its private alarm contains only the local ID. A pending-to-
dispatch compare-and-set prevents duplicate alarms or a racing Undo from
submitting twice. Undo never crosses durable dispatch ownership.

Every due or restart path rechecks clock continuity and bounded lateness,
verified one-person identity, role, authoritative remembered active SIM, exact
draft revision, and one-unit eligibility. Reboot/time discontinuity, excessive
lateness, lost role/SIM, arming failure, or interrupted handoff pauses visibly
for review without transport retry. Dispatch reconciliation accepts only an
exact matching durable composer operation or terminal draft absence; mismatch
retains the draft for review. Emulator and no-send device evidence cannot close
real carrier, radio, billing, or OEM-kill threats.

Phase 5F implements ADR 0013's guarded permanent-deletion boundary. Schema 10
stores no address or message content: it binds a message to provider kind, row
ID, and synchronization fingerprint, or a Thread to its verified participant
digest, count, latest SMS/MMS IDs, and exact captured draft revision. Physical
triggers enforce bounded identifiers, legal phase transitions, and a maximum
of 128 operations. A private alarm exposes only the local operation ID.

One-message deletion requires explicit confirmation; whole-Thread deletion
requires two confirmations. Both provide one fixed five-second pre-commit Undo
window. Before mutation Aurora rechecks role, provider conflicts, target
identity, clock continuity, and lateness. Recovery never blindly replays an
interrupted provider call: confirmed target absence finalizes, an unchanged
present target cancels safely, and changed or unreadable state remains visible
for review. Provider success removes any exact old Thread draft and never
claims recoverability; newer drafts are protected by revision comparison. No
recycle-bin UI, table, preference, migration, service, or worker exists.
Emulator evidence does not close physical provider race, process-death, or OEM
lifecycle threats, and no live Pixel message is used for acceptance.

Phase 5G implements ADR 0014's shared no-group-SMS boundary. A group cannot be
represented as `SmsSendRequest`; every shared SMS transport caller therefore
inherits a one-canonical-recipient invariant. The externally reachable
respond-via surface routes a group to exactly one MMS request and returns MMS
failure without a loop or SMS fallback. Thread, delayed, and scheduled paths
also retain independent one-person verification before durable ownership. This
prevents silent recipient fan-out, privacy leakage, and unexpected per-message
billing in current surfaces. Full group-MMS encoding, addressing, reply,
carrier, and physical-device behavior remains open.

Phase 6A treats reaction-looking SMS as untrusted presentation input. ADR 0015
uses a whole-message allowlist, bounded matched quotes, complete non-truncated
text, and fail-open raw rendering. It never rewrites provider/index content,
hides the fallback row, guesses a target row, or logs the quoted text. This
prevents malformed or adversarial prose from masquerading as a reaction while
still presenting exact common fallback forms clearly.

Phase 6B treats clipboard export as an explicit privacy boundary. ADR 0016
places copy behind Message actions, requires a non-collapsed valid selection,
and copies only that displayed substring. The selector labels truncated input
and never adds sender, subject, conversation, timestamp, or hidden content.
Transient selection is not saved or logged. Message details is a body-free,
address-free projection with no provider/thread IDs or attachment paths.
Permanent deletion remains a separate choice and retains ADR 0013's confirmation
and Undo protocol.

Phase 6C treats later notification timing as durable action metadata. ADR 0017
stores one bounded, checksummed, content-free owner per conversation and places
only a monotonic local ID in a private one-shot inexact alarm. Fire-time logic
requires role ownership and an exact successful provider read proving the same
incoming SMS is unread; the resulting notification is generic. Ownership is
consumed before posting, provider failure cannot fabricate read state, and
reboot, wall-clock/timezone changes, role loss, setting changes, and opening the
conversation fail closed. Reminder state is excluded from backup, and no exact-
alarm permission, repeating wakeup, sender, address, or body is introduced.

Phase 6 history-completeness hardening preserves the same role and foreground
read boundary during resumable index work. A Pending result may schedule only
one delayed continuation at a time, only while role ownership and foreground
read permission remain true, and only four times per initiating signal. Explicit
foreground, provider-change, and periodic signals may renew that finite budget;
role loss, backgrounding, completion, failure, and shutdown cancel it. This
prevents a stalled partial index from depending on incidental user navigation
without introducing a background provider-read loop or weakening Android's
default-SMS authority boundary.

Physical large-history follow-up initially preserved paused cursors across role
transitions. Further review found that another default app can insert or mutate
deep/backdated rows during that authority gap, beyond both the saved cursor and
a bounded head check. Role recovery now dirties the generation and starts a
fresh full scan; ordinary clean process recovery remains resumable. Completion
requires exhausted SMS/MMS cursors, exact eligible provider/projection count
equality, and bounded head/fingerprint verification. The partial UI
exposes a content-free committed-row count. ADR 0020 permits Inbox and Thread to
show the best-known union of private cached generations while coverage is
incomplete, with explicit disclosure that recent provider changes may not be
reflected. This presentation union grants no verified conversation identity and
therefore cannot authorize send, delete, spam/block, subscription, or other
exact-identity actions. Verified completion returns to strict current-generation
queries and atomically removes stale rows.

Phase 6D treats a signature as outgoing message content, not decoration. ADR
0018 stores settings outside drafts, addresses conversation overrides only by a
purpose-separated hash of a complete verified participant set, and bounds the
checksummed store without eviction. Corruption blocks overwrite and new sends
instead of silently omitting content. Exact unsigned and signed SMS segmentation is visible before send;
groups retain the MMS-only boundary and a signed multipart text remains disabled
rather than silently changing transport or content. Immediate, delayed, and
scheduled sends freeze the selected value into their schema-11 durable owner so
recovery cannot adopt a later preference edit. This is an acknowledged bounded
content expansion of previously content-free owner rows, but it never includes
the draft body and is immutable, redacted, app-private, and excluded from backup
and device transfer. No permission, alarm payload, provider-body rewrite,
network path, automatic send, or carrier test is introduced.

Phase 6D acceptance passed 578 host tests, the complete offline privacy and
release aggregate, and 332 API 36 plus 335 API 26 connected tests. Safe install
and hash-matched Download handoff passed on the Pixel 8 and API 36 emulator with
their role holders and denied Aurora SMS permissions preserved. No app launch,
live-content read, or carrier transmission was part of this evidence.

Phase 6E treats false-positive suppression as the primary spam threat. ADR 0019
stores at most 256 meaningful user decisions in schema 12 using
purpose-separated participant/sender hashes and no raw address, body, snippet,
contact, keyword, or score. Physical SQLite constraints enforce the bound,
legal one-person blocks, and monotonic optimistic transitions. Incoming sender
blocks are checked after provider storage and before contact resolution,
notification, reply-target, or reminder effects. A match acknowledges the
provider delivery without those Aurora effects; it never deletes or hides the
provider row. Lookup failure fails open to normal notification, while corrupt
state disables user controls instead of guessing. The feature adds no network,
permission, reputation service, background worker, or carrier action.

Phase 6E acceptance passed 587 host tests in the complete offline privacy and
release aggregate, 339 API 36 connected tests with 10 intentional skips, and
342 API 26 tests with 13; both connected matrices executed 329 tests with zero
failures/errors. Release bundle and deterministic CycloneDX 1.6 SBOM generation
passed. No live message/address/body was inspected and no carrier transmission
was made. Safe exact-APK install and hash-matched Download handoff passed on the
Pixel 8 and API 36 emulator while their non-Aurora role holders and denied
Aurora messaging/notification permissions remained unchanged. No Activity
launch was issued and the Pixel was left force-stopped.

Phase 6F treats both microphone capture and the MMS submission boundary as
critical ownership transitions. ADR 0021 excludes microphone access from
onboarding, allows only one visible foreground capture, retains a reviewed file
only before an unambiguous send attempt, and deletes it on cancellation or
lifecycle exit. The pinned official-AOSP subset is outgoing-only and isolated;
all incoming decoders, APN/network code, transaction services, and general
attachment surfaces remain absent. Parts-first provider persistence and exact
creator/Thread/transaction checks prevent incomplete or foreign rows from
becoming send authority. A content-free checksummed journal makes process death
before submission safely reversible and process death at/after SUBMITTING
permanently non-retryable without an authenticated callback.

Focused acceptance covers a deterministic PDU golden/corpus, pure crash-state
recovery, exact callback authentication on API 26/API 36, three fake-provider
persistence/rollback cases on API 36, Compose states, permission policy, and
three real virtual-microphone lifecycle cases on both API 26 and API 36. No live
provider content or carrier transmission was used. Full carrier/OEM/group and
incoming-PDU acceptance remains open.

Phase 6G treats archive selection, passphrases, and provider restore as separate
capabilities. ADR 0022 authenticates and validates the entire bounded stream
before exposing confirmation, keeps passphrases ephemeral, stages only private
owner-checked regular files, and serializes a journaled provider mutation.
Historical send-capable boxes are restored only as inert Failed history. Path,
symlink, checksum, archive-limit, duplicate, partial-write, process-death, and
rollback ambiguity fail closed. Physical/OEM document-picker acceptance remains
open.

Phase 6H treats notification actions as stale external capabilities. ADR 0023
binds Mark as read to an exact incoming SMS generation, rechecks the default-SMS
role and expected thread before mutation, and updates only incoming rows through
that generation. Cleanup is exact and follows provider success, so a stale
action cannot consume a newer message or reminder. Reply enters the unchanged
durable send owner through a non-exported background service. Notification
history is bounded to 25 entries, restarts on privacy or group-identity change,
and uses a short bounded publication mirror only to cover platform propagation.
Actual Android Auto/DHU, physical lockscreen/OEM, real-provider read mutation,
and carrier-reply acceptance remain open.

Phase 7D ADR 0024 treats every incoming WAP/MMS byte and metadata field as
carrier-controlled. The exact-revision AOSP parser closure is isolated behind an
original typed wrapper and hard 1-MiB input/aggregate, 25-part, 8-KiB part-
header, 2-KiB WAP-string, and depth-eight multipart limits. Declared lengths,
end-of-input, and uintvar width are checked before use; unknown skips allocate
nothing; parser state is per instance; diagnostics contain no addresses, URLs,
IDs, subjects, text, or binary data. The wrapper accepts only HTTPS/HTTP
notification locations and concrete non-DRM part MIME types. It performs no
network, provider, notification, acknowledgement, or media-decode action.
Durable download/callback/provider ownership remains a closed gate, so the app
entry points remain closed until a separate durable handoff is accepted.

Phase 7D ADR 0025 opens that handoff through a dedicated incoming-operation
namespace and a checksummed metadata-only journal. The platform call is preceded
by a durable `SUBMITTING` checkpoint, and startup converts that checkpoint to
`SUBMISSION_UNKNOWN` without resubmitting. A private callback must match the
exact operation and staged filename. Provider persistence is idempotent and
parts-first with app-owned partial-row cleanup; notification acknowledgement is
the only successful owner of final journal and file removal. Startup may replay
bounded provider/notification work but never carrier retrieval. Group quick
reply remains disabled, the active line is used only ephemerally to exclude self
from Thread identity, and diagnostics redact all carrier/message identity and
content. Physical carrier/OEM, SIM-number availability, roaming/billing, and
malicious-carrier acceptance remain open. Implementation commit `f2f4f5c`
exercises the retained `PERSISTED` owner in an isolated package across two real
host force-stops on API 26 and API 36. The fresh process reconstructs one
pending notification without invoking the platform download seam; only the
later exact acknowledgement removes the journal and staged PDU. The runner
passes twice per API, removes its test package, and preserves the SMS role.
This is synthetic process-lifecycle evidence, not carrier/OEM callback or
physical-device evidence.

Phase 7D ADR 0026 routes exact direct/group, long-text, subject, and sanitized
image drafts through one MMS operation without SMS fan-out or fallback. Picker
input is read under source/dimension/pixel limits and re-encoded as bounded JPEG
or PNG, discarding its URI, grant, filename, EXIF, color profile, and container
metadata. State schema 14 retains only those sanitized bytes in app-private
storage under the exact draft; physical triggers cap each part, count, type, and
aggregate, while draft deletion cascades cleanup. UI attachment mutation commits
durably before publication, and Send freezes the text draft then rereads the
attachment authority. Missing, stale, corrupt, or unavailable state blocks
transport rather than dropping an image or downgrading. Room close/reopen and
Activity-recreation acceptance pass on API 26/API 36. A gated API 36 host
journey proves the exact draft and image bytes survive host force-stop into a
fresh process, pass through real-root restoration and one synthetic MMS route,
and are removed by fresh-process parent/cascade cleanup. In-flight transport
death, physical carrier/OEM, size/APN, billing/roaming, and dual-SIM acceptance
remain open.

## Open security decisions

These do not block Phase 1 foundation unless explicitly named, but block their
feature's release:

- physical/OEM backup document selection, cancellation, unavailable-provider,
  and user-recovery acceptance for ADR 0022's implemented archive protocol;
- physical OEM/Doze exact-alarm timing and revocation acceptance;
- app-lock and secure-recents defaults/copy;
- physical incoming MMS carrier/OEM/SIM-line/roaming/billing audit beyond ADR
  0024/0025's bounded synthetic codec and durable handoff, plus general/group
  outgoing composition;
- physical/OEM Contacts-provider permission, denial/revocation, query,
  cancellation, selection, and manual-fallback acceptance for ADR 0027;
- optional SQLCipher hardened mode after measured FTS/migration impact;
- artwork license and attribution.
