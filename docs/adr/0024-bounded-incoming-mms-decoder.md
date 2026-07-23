<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0024: bounded incoming MMS decoder

- Status: Accepted; codec boundary implemented
- Date: 2026-07-20

## Context

AuroraSMS can scan MMS rows already present in Android's Telephony provider and
has public-SDK staging for `SmsManager.downloadMultimediaMessage`, but incoming
WAP pushes and completed downloads still stop at `CODEC_UNAVAILABLE`. That
omission explains why a device can show historical MMS conversations while new
incoming MMS remains unavailable. A decoder consumes carrier-controlled bytes,
addresses, URLs, lengths, MIME metadata, encodings, and multipart structure, so
it must be bounded before it can own a provider or notification transition.

The exact official-AOSP revision already admitted by ADR 0021 contains a mature
parser dependency graph under compatible Apache-2.0 terms. Reusing that exact
revision avoids a new dependency or source family, but upstream framework code
alone does not provide Aurora's privacy, resource, type, or lifecycle policy.

## Decision

AuroraSMS adds twelve Java files from official AOSP
`platform/frameworks/opt/mms` at immutable revision
`4bfcd8501f09763c10255442c2b48fad0c796baa`: `ContentType`, `PduParser`,
`NotificationInd`, `RetrieveConf`, the parser's remaining typed-PDU closure,
and its Base64 and quoted-printable helpers. The complete file list, retained
headers, modifications, and Apache-2.0 text are under `third_party/aosp-mms/`.
No APN client, downloader, persister, transaction service, database, UI, or
end-user messaging-application source is copied.

The repackaged parser is modified to enforce these limits before projection:

- one defensive-copy input of 1 through 1,048,576 bytes;
- at most 25 parts, 8,192 bytes of headers per part, and 2,048 bytes per WAP
  string;
- at most eight nested multipart-alternative levels;
- at most five uintvar octets, checked declared lengths, checked end-of-input,
  and no allocation merely to skip an unknown value;
- instance-local content-type parameters rather than static process state;
- deterministic local names for otherwise anonymous parts; and
- no message-derived parser logging or dependency on a private Android resource.

An original GPL-3.0-or-later `BoundedMmsPduDecoder` is the only admitted runtime
entry point. It accepts an `EncodedMmsPdu` and returns either a redacted typed
result or one content-free failure. It exposes only:

- `M-Notification.ind`, with a visible-ASCII transaction ID, bounded optional
  sender and subject, a 1-through-1,048,576-byte advertised size, and an
  absolute `http` or `https` content location with authority and without user
  information or a fragment; and
- `M-Retrieve.conf`, with bounded sender/TO/CC values, at most 100 distinct
  participants, bounded subject/IDs/timestamp, and defensively copied parts.

Part MIME types must be concrete, syntactically bounded ASCII values. OMA DRM
parts and wildcard or malformed types are rejected. `text/plain` is decoded
with its declared supported charset and bounded to 100,000 characters; other
parts remain opaque bytes and are never media-decoded by this boundary. Total
decoded part bytes cannot exceed the encoded-PDU cap. Diagnostic strings reveal
only counts, sizes, booleans, and MIME types—not addresses, URLs, IDs, subjects,
text, or binary content. Other PDU kinds remain unsupported.

This decision does not authorize immediate download, provider mutation,
notification, acknowledgement, or carrier testing. Those transitions require a
separate content-free durable download journal, exact subscription and callback
ownership, parts/addresses/provider cleanup on partial failure, replay fences,
and API 26/API 36 synthetic process-death evidence. `AppContainer.onDownloadedMms`
therefore remains closed until that integration is implemented and verified.

## Verification

- Five focused tests pass on both API 26 and API 36 emulators with only
  synthetic data.
- One notification golden fixture covers sender, subject, transaction, expiry,
  size, and HTTPS content-location projection plus diagnostic redaction.
- One group retrieved-message fixture covers sender, two TO recipients, UTF-8
  text, binary image bytes, defensive copies, and diagnostic redaction.
- Every truncation of both valid fixtures and 1,024 deterministic hostile byte
  inputs returns without throwing.
- Unsafe transport schemes, over-limit advertised size, and 26-part PDUs fail
  closed before provider projection.

## Consequences

AuroraSMS now has an admitted incoming codec boundary but not end-to-end receive
support. The source distribution grows from twelve to twenty-four noticed AOSP
codec files without adding a runtime coordinate, native library, application
network permission, or private source asset. Any larger limit, new PDU kind,
DRM behavior, parser revision, or decoded content policy requires a reviewed
ADR and renewed corpus/provider/carrier evidence.
