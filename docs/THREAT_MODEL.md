# AuroraSMS threat model

Status: Phase 0 baseline, 2026-07-12

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
| Contacts and photos | Sensitive personal data | Optional permission, bounded cache, invalidate on change, no serialization per message |
| Subscription/SIM choice | Sensitive device data | Minimal access, validated fallback, no identifier logging |
| Notification content/settings | Sensitive disclosure control | User-selectable privacy levels, lock-screen safe defaults |
| Appearance profiles, scoped participant fingerprints/thread hints, and media URIs | Sensitive pseudonymous/private data, especially when user photos are used | Validated declarative data, target-specific access, scoped URI access, no executable themes or appearance logs |
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
    | repositories and paged flows
Aurora UI / notifications / explicit user actions
    |
System picker, SAF, share targets, exports, and external recipients
```

Additional boundaries exist at exported Android components, package/role
manager, Contacts and Subscription providers, notification listeners/lock
screen, persisted document URIs, dependency/build tooling, and Git/CI.

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
MMS parts, unsupported MIME, stale URIs, or multiple active decoders exhaust
memory/CPU or crash the app.

Controls:

- inspect metadata and enforce byte, dimension, frame, duration, and attachment
  limits before full decode;
- downsample to the exact display/send target off the main thread;
- bounded caches, static previews, and only one visible animated decoder;
- pause/release animation for lifecycle, reduced motion, display-off, and battery
  conditions;
- treat MIME and extensions as untrusted, fail closed, and offer recovery;
- never decode MMS media while text indexing.

### T6: notification and screen disclosure

Threats: lock-screen bodies, recents thumbnails, screenshots, screen sharing,
inline replies, or notification listeners expose content.

Controls:

- notification privacy levels: sender/body, sender only, generic;
- safe channel/lock-screen defaults and explicit inline-reply authentication
  expectations;
- immutable notification routing plus one narrowly mutable explicit reply
  intent; app-private target and consumed-claim journals bind provider token,
  conversation, recipient, subscription, and expiry across process death;
- consume a reply claim durably before platform submission, retain it through
  token expiry, clear targets on role loss, and replace crash-replayed
  notifications without a second alert;
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
- delete temporary share/export files after completion and again at startup.

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
- warn/highlight before auto-hide defaults are considered;
- visible classification reasons and explicit spam/not-spam, block/unblock
  recovery;
- never silently delete suspected spam;
- normalize sender/rule input safely and test emergency/short/alphanumeric
  senders;
- no remote reputation or network lookup.

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
- current Thread UI assignment only for a complete verified participant preview
  of at most 8 members; 9-through-100-member indexed conversations inherit
  `global_thread` until a bounded full-identity query is reviewed;
- independent per-screen/per-conversation media references and reset semantics;
- 4.5:1 body-text target, 3:1 non-text affordances, 48 dp targets, TalkBack,
  RTL, 200% font, and reduced-motion tests;
- theme imports are declarative and never execute code.

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
- Contacts permission is optional and denial leaves number-based messaging
  usable.
- Destructive and external actions require explicit, contextual user intent.

## Security verification obligations

Every applicable phase gate includes:

- merged-manifest permission/export/backup inspection;
- role grant, denial, loss, and reacquisition tests;
- forged/malformed intent and URI tests;
- process-death/reboot/time-change state tests;
- large/corrupt media and archive parser tests;
- dependency/provenance/SBOM scan;
- private-path/tracked-resource/APK inventory scan;
- no-sensitive-log checks;
- physical-device SMS/MMS behavior where the phase claims transport support;
- accessibility/contrast/lifecycle tests for privacy-related UI.

## Open security decisions

These do not block Phase 1 foundation unless explicitly named, but block their
feature's release:

- backup archive encryption, authentication, key derivation/recovery, limits,
  retention, and corruption policy;
- exact-alarm eligibility and fallback for scheduled sends;
- app-lock and secure-recents defaults/copy;
- MMS PDU implementation/dependency audit;
- final local spam rule set and auto-hide default;
- optional SQLCipher hardened mode after measured FTS/migration impact;
- artwork license and attribution.
