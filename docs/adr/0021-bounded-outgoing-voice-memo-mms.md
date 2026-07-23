<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0021: bounded outgoing voice-memo MMS

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

AuroraSMS had platform MMS staging, download, and callback plumbing, but its
high-level outgoing payloads deliberately failed with `CODEC_UNAVAILABLE`.
Phase 6F needs one narrow attachment surface without admitting code from an
end-user messaging application, a network client, an incoming PDU parser, or a
general attachment composer. A voice memo is critical personal data and an MMS
submission is an irreversible carrier boundary, so permission timing, storage,
provider ownership, process death, and callback authentication must be decided
together.

The Android framework does not expose its MMS PDU composer as a supported SDK
API. Writing a WAP/MMS composer from memory would create a larger unreviewed
protocol surface. Adding a third-party MMS library would require a separate
maintenance, dependency, and transitive-risk owner.

## Decision

AuroraSMS vendors the smallest required outgoing `SendReq` composer subset from
the official AOSP `platform/frameworks/opt/mms` repository at immutable revision
`4bfcd8501f09763c10255442c2b48fad0c796baa`, under Apache-2.0. The exact source
list, retained copyright headers, modification record, and license are in
`third_party/aosp-mms/README.md` and `third_party/aosp-mms/LICENSE`.

The vendored source is isolated under
`org.aurorasms.core.telephony.codec.aosp`. Packages and internal imports are
rewritten, and the composer fails closed rather than printing a stack trace or
continuing after malformed first-part metadata. No AOSP parser, incoming-MMS
decoder, APN/network client, transaction service, database layer, UI, or
messaging-application source is included.

`VoiceMemoMmsEncoder` exposes only one deterministic shape:

- exactly one verified canonical recipient and provider Thread;
- one mandatory SMIL part, one optional bounded UTF-8 signature-text part, and
  one `audio/mp4` MPEG-4/AAC-LC part;
- at most 60 seconds and 524,288 audio bytes;
- at most one 1,048,576-byte encoded PDU;
- one validated transaction ID and one explicit subscription; and
- no group, arbitrary attachment, subject UI, incoming decode, or silent
  SMS-fan-out path.

Microphone permission is absent from onboarding and is requested only after the
user taps Record. Recording is visibly indicated, foreground-only, and written
under `noBackupFilesDir/voice_memo_staging`. Stop creates a separate review
state; Send is a second explicit action. Cancel, Thread/background lifecycle,
known validation failure, unknown submission failure, and startup cleanup remove
bounded staging files according to their ownership state. Audio bytes never
enter logs, Compose state, preferences, Room, alarms, or backup.

Before the platform call, provider parts are written under an operation-derived
temporary owner. Aurora then inserts one creator/thread/transaction-bound FAILED
MMS row, moves every part to the exact new row, inserts one TO address, and
verifies the complete row. Partial work is removed. Only an exact applied
`FAILED -> OUTBOX` transition may cross the carrier boundary.

A checksummed, content-free journal owns PREPARING, PREPARED, SUBMITTING,
SUBMISSION_UNKNOWN, CALLBACK_SENT, and CALLBACK_FAILED states. PREPARING work
may be rolled back only by exact creator, Thread, transaction, and FAILED state.
A process death or exception at/after SUBMITTING becomes submission-unknown and
is never automatically retried. The private immutable one-shot callback carries
the exact operation/provider/conversation tuple; a callback must authenticate
against the journal before provider mutation or acknowledgement.

The existing opaque `EncodedMmsPdu` transport primitive remains internal
plumbing and gains no user-facing general composer. Carrier/OEM acceptance,
group MMS, incoming decoding, arbitrary attachments, and a full MMS composer
remain separate release gates.

## Verification

- A deterministic synthetic golden PDU is 539 bytes with SHA-256
  `e8abd80ab558cc9ba2179519cb928b131889f78d35b7258ba419cc6a0bd87867`.
- The bounded synthetic corpus covers audio sizes through 524,288 bytes and
  rejects groups and malformed metadata.
- Android 8/API 26 and Android 16/API 36 execute real virtual-microphone capture,
  review, cancellation, foreground cleanup, fake submission, and callback
  state tests without provider or carrier access.
- Android 16's in-process fake provider verifies byte-exact part persistence,
  exact status transitions, partial-write cleanup, exact rollback, and refusal
  to roll back OUTBOX. API-independent crash ordering is exhaustively host
  tested, and callback identity/authentication tests run on API 26 and API 36.
- Real carrier submission is intentionally not part of this acceptance.

## Consequences

AuroraSMS can compose and submit one bounded one-person voice memo while keeping
its general and incoming MMS surfaces closed. The source distribution must
retain the AOSP notices and Apache-2.0 text. Any additional PDU type, recipient
shape, attachment MIME type, decoder, or upstream update requires a new reviewed
decision and new golden/corpus/provider/carrier evidence.

## Phase 7D amendment

ADR 0024 later admits the bounded parser dependency closure from the same exact
AOSP revision and an original notification/retrieved-message validation
wrapper. It does not broaden this ADR's outgoing voice-memo shape or carrier
authority.
