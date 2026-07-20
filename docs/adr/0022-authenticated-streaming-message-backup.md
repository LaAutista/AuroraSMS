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

The first implementation slice admits and tests only the encrypted envelope,
framing, canonical paths, per-record checksums, final manifest, and API 26/API 36
platform crypto. A later slice in this ADR adds the exact SMS/MMS field schemas
and provider adapter; Phase 6G is not implementation-complete before those pass.

### Export

Export requires the default-SMS role and current read permission, is launched
only from the visible Backup & restore screen, and writes directly to one
user-selected SAF destination. Unknown-length provider parts stream in chunks;
message bodies or attachments are never collected into one in-memory archive.
Any provider, size, document, or crypto failure reports failure and attempts to
delete the incomplete destination. No automatic/background export exists.

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

The user must explicitly confirm the dry-run summary. Restore requires the
default-SMS role and write permission again. Existing provider rows are never
overwritten. Exact content fingerprints skip duplicates.

### Provider staging and crash recovery

Every new provider message begins in a non-sendable placeholder state. The
content-free import journal durably records the session/ordinal before insert,
then the returned provider ID before parts or final values are written. SMS uses
a private unique placeholder address until the ID is durable. MMS uses a private
unique transaction ID and a draft/failed parent while parts and addresses are
staged. Original pending/outbox/queued states restore only as failed historical
rows; restore never invokes `SmsManager`, schedules alarms, or creates pending
send ownership.

After all rows and parts are staged and re-read exactly, a bounded commit pass
makes the historical boxes visible. Any in-process error rolls back every exact
app-created row. Process-death recovery uses recorded IDs and, for the pre-ID
window, the unique placeholder identity to resume rollback before a new import.
Foreign, changed, or recycled rows cause a fail-closed ownership conflict rather
than deletion. This provides logical all-or-rollback behavior without claiming a
cross-provider Android transaction that the public Telephony API does not offer.

## Consequences

- Portable archives are confidential and tamper-evident but only as strong as
  the user's passphrase; UI must not imply password recovery.
- Validation and restore intentionally read the archive more than once and may
  require temporary private disk roughly equal to the plaintext archive.
- OS/cloud backup remains disabled; the explicit archive is the only backup
  surface.
- Provider import, exact schemas, UI, cleanup, API matrices, and physical-device
  smoke tests remain mandatory before Phase 6G can be called complete.
