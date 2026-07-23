<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0022: authenticated streaming message backup and restore

- Status: accepted; implementation staged in bounded slices
- Date: 2026-07-19

## Context

Phase 6G requires an explicit user-controlled SMS/MMS archive, including MMS
parts, without enabling Android/cloud backup or copying Aurora's private
databases. A portable archive crosses the app sandbox and may be stored or shared
by another document provider. It therefore needs confidentiality, authenticity,
strict streaming limits, a dry-run summary, and provider-write recovery.

The Telephony provider remains the authority. The disposable search index,
private journals, drafts, schedules, delayed sends, pending deletions, reminder
owners, signatures, spam decisions, scoped fingerprints, and appearance media
are not message history and are excluded. In particular, no pending or scheduled
operation may become send authority through restore.

## Decision

### Portable envelope

`AuroraMessageBackup v1` is one binary streaming document. A fixed plaintext
header contains only magic/version, algorithm identifiers, iteration count, a
fresh 128-bit random salt, and a fresh 96-bit random nonce. The whole header is
AES-GCM additional authenticated data. Everything else, including record counts,
addresses, bodies, timestamps, MIME metadata, and attachment bytes, is encrypted
and authenticated with AES-256-GCM and a 128-bit tag.

The 256-bit key is derived from a user-entered 12-1024-character passphrase with
PBKDF2-HMAC-SHA-256, 300,000 iterations, and the archive salt. The app never
stores the passphrase or derived key and clears mutable copies after use. There
is deliberately no recovery key, account, network escrow, or device-keystore
wrapping: losing the passphrase loses the archive, while a device-bound key would
defeat cross-device restore. UI must say both facts before export.

Android recommends AES with 256-bit keys in GCM mode when the application can
choose its format. NIST SP 800-132 defines salted, iterated password-based key
derivation for stored data. The implementation uses platform JCA primitives and
does not add a crypto dependency or name a non-Keystore provider.

### Plaintext record format and limits

Inside the authenticated envelope, records have generated canonical paths,
strictly increasing ordinals, a one-byte schema, 64-KiB chunks, and a trailing
SHA-256 content checksum. Paths cannot come from provider data and therefore
cannot contain absolute paths, separators outside the fixed grammar, `.` or
`..`. The final authenticated manifest repeats observed counts and byte totals.

Version-one bounds are:

- 2,000,000 combined SMS, MMS, and MMS-part records;
- 64 MiB per record;
- 16 GiB combined record content; and
- 16.5 GiB plaintext framing, with a separately bounded encrypted input.

The first implementation slice admitted and tested the encrypted envelope,
framing, canonical paths, per-record checksums, final manifest, and API 26/API 36
platform crypto. Phase 6G remains incomplete until provider restore, recovery,
SAF/UI, and the complete acceptance matrix also pass.

The second slice defines lossless, backup-only schemas rather than reusing the
bounded presentation projections. Message archive IDs are sequential from one,
contain no provider/thread IDs, and never encode send ownership. SMS records
carry the historical box, address/body, provider timestamps and flags, raw
status/error/protocol metadata, service center, and subscription. MMS records
carry the historical box, timestamps and flags, standard transport/PDU metadata,
subscription, and at most 100 typed addresses. Each MMS part immediately follows
its parent and carries bounded MIME metadata plus empty, UTF-8 text, or streamed
binary content. A full validator enforces this graph in constant memory.

### Export

Export requires the default-SMS role and current read permission, is launched
only from the visible Backup & restore screen, and writes directly to one
user-selected SAF destination. Unknown-length provider parts stream in chunks;
message bodies or attachments are never collected into one in-memory archive.
Any provider, size, document, or crypto failure reports failure and attempts to
delete the incomplete destination. No automatic/background export exists.

The provider reader snapshots each provider's current highest positive row ID,
then uses ascending 200-row keyset pages capped at that ID. New messages arriving
during export remain for the next archive instead of extending the operation.
Provider IDs are transient cursors and attachment handles only. An archive is a
best-effort point-in-time history across Android's independent SMS/MMS providers;
it does not claim a cross-provider read transaction.

### Validate, review, and restore

Restore uses `OpenDocument`, copies only the encrypted source into a bounded
credential-encrypted `noBackupFilesDir` staging area, and decrypts to a `.pending`
private file. Decrypted bytes are not parsed or applied until AES-GCM reaches and
verifies its final tag. Authentication failure deletes the pending file and uses
one combined wrong-passphrase/corruption error. A successful tag is followed by
a full structural, checksum, field-schema, MIME, count, and size validation.
Only then may the file become `.validated` and produce a dry-run summary. Leaving
the screen/backgrounding before confirmation and startup reconciliation delete
all owned temporary files.

Provider application reopens only that private validated file and rechecks the
complete framing, record checksums, schemas, sequential message graph, and final
manifest while visiting records. SMS/MMS metadata callbacks occur after their
record checksum is complete. A binary MMS payload is exposed only as a one-shot
record stream during its callback and must be explicitly copied or discarded;
retaining or abandoning it fails the visit.

The user must explicitly confirm the dry-run summary. Restore requires the
default-SMS role and write permission again. Existing provider rows are never
overwritten. Exact content fingerprints skip duplicates.

The coordinator performs a bounded duplicate-analysis pass before creating the
restore journal. A Boolean decision table indexed by sequential archive message
ID is capped by the archive's two-million-record limit. Duplicate SMS and fully
matched MMS parents/addresses/parts are skipped before mutation. The staging
pass then keeps every new parent in `FAILED`; a final journal-driven pass exposes
only safe historical boxes. Imported `DRAFT`, `OUTBOX`, `QUEUED`, and `FAILED`
rows all remain `FAILED`, so archive data cannot recreate send authority.

The Android adapter bounds one SMS fingerprint query to 200 candidates and one
MMS fingerprint query to eight candidates and 1,000 parts. Exceeding a bound
fails the restore rather than guessing. A matching candidate is re-read and
re-fingerprinted immediately before it is skipped, so a concurrently removed or
changed row cannot satisfy a stale duplicate decision. Duplicate comparison uses
the safe restored box, which also makes repeated restores of an archived
`DRAFT`, `OUTBOX`, or `QUEUED` row idempotent after its first `FAILED` import.

### Provider staging and crash recovery

Every new provider message begins in a non-sendable placeholder state. The
content-free import journal durably records the session/ordinal before insert,
then the returned provider ID before parts or final values are written. SMS uses
a private unique placeholder address until the ID is durable. MMS uses a private
unique transaction ID and a draft/failed parent while parts and addresses are
staged. Original pending/outbox/queued states restore only as failed historical
rows; restore never invokes `SmsManager`, schedules alarms, or creates pending
send ownership.

Provider and thread IDs are never portable. During prepare, the Android adapter
derives a new local thread through `Telephony.Threads` from the restored SMS
address or bounded MMS address set (excluding the provider's insert-address
token), and writes that local ID in the same conditional update as the final
metadata. Every provider call rechecks default-role and read-permission access.
SMS scalar values, MMS parents and ordered addresses, and every text/empty/binary
part are then re-read through `ContentResolver` and compared with the canonical
digest. Binary parts are copied and hashed in one pass.

After all rows and parts are staged and re-read exactly, a bounded commit pass
makes the historical boxes visible. Any in-process error rolls back every exact
app-created row. Process-death recovery uses recorded IDs and, for the pre-ID
window, the unique placeholder identity to resume rollback before a new import.
Foreign, changed, or recycled rows cause a fail-closed ownership conflict rather
than deletion. This provides logical all-or-rollback behavior without claiming a
cross-provider Android transaction that the public Telephony API does not offer.

The journal is a private `noBackupFilesDir` append-only log. A fresh opaque UUID
binds every checksummed event. Its strict grammar permits only sequential
reserve/insert/expect/prepare groups, at most one unfinished message, and a
durable complete marker. `EXPECT` durably records the redacted canonical SHA-256
ownership digest before final provider values replace the deterministic
placeholder. `PREPARE` is admitted only after the exact forced-FAILED parent,
addresses, and parts re-read to that same digest, so recovery can identify either
side of the provider-prepare crash window and reject a changed or recycled row.
Each line is flushed and `fsync`ed before the provider boundary advances;
recovery streams ownership instead of collecting a large import in memory. The
log stores only session, ordinal, provider kind/ID, intended historical box,
prepared digest, event sequence, timestamps, and checksums—never addresses,
bodies, subjects, MIME metadata, or attachment bytes. A malformed log blocks a
new restore and cannot be cleared as trusted.

The expected digest is also passed into the provider prepare boundary. If an OEM
provider normalizes a just-written row, Aurora conditionally removes only the
exact row it just re-read before returning an ownership conflict. If that exact
conditional cleanup no longer matches, recovery remains quarantined instead of
deleting changed data. Synthetic `ContentProvider` journeys exercise successful
restore/replay, role and permission fences, provider normalization, both
pre-ID/expected-digest recovery windows, and rollback after a later commit
conflict without touching real Telephony content.

The staging adapter uses a dedicated credential-encrypted `noBackupFilesDir`
directory and owner-only files. It creates each pending file exclusively without
following links, verifies owner/regular-file/single-link identity through the
opened descriptor, `fsync`s bytes before same-directory rename, and `fsync`s the
directory after rename or cleanup. An encrypted selection can become
`.plaintext.validated` only after GCM authentication and the complete message
schema pass. Wrong-passphrase/tamper, invalid-schema, source-failure, size-limit,
cancel, stale-startup, and replaced-path cases remove or reject only the owned
files. Six focused journeys pass on both API 26 and API 36.

The document workflow retains neither URI nor passphrase and accepts only
explicit `content` URIs. Export reads the Telephony snapshot only after policy
checks, streams it to one destination, and attempts to delete that destination
on every role, permission, provider, source, crypto, or document failure. Restore
copies and closes the selected source immediately; a wrong passphrase can retry
against the still-encrypted private copy, while confirmation is impossible until
an authenticated summary exists. Cancel/background invalidates either staged or
validated state. Startup performs journal recovery before deleting staging
residue. Six fake-document/coordinator journeys pass on each boundary API.

## Consequences

- Portable archives are confidential and tamper-evident but only as strong as
  the user's passphrase; UI must not imply password recovery.
- Validation and restore intentionally read the archive more than once and may
  require temporary private disk roughly equal to the plaintext archive.
- OS/cloud backup remains disabled; the explicit archive is the only backup
  surface.
- SAF/UI integration, the complete aggregate/release matrix, and physical/OEM
  smoke tests remain mandatory before Phase 6G can be called complete.
