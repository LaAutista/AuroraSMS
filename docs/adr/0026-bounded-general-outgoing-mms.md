<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0026: bounded general outgoing MMS

- Status: Accepted; synthetic implementation verified
- Date: 2026-07-20
- Implementation: `7a45033`, `a71c623`, `0b27160`, and `1e2344b`

## Context

ADR 0014 forbids group SMS fan-out and ADR 0021 admits only one-person voice
memos. AuroraSMS still needs ordinary direct and group MMS for long text,
subjects, images, and image-only messages. That surface must reuse the existing
Android `SmsManager` carrier boundary without adding Internet access, retaining
picker capabilities, trusting caller MIME metadata, or weakening the durable
composer callback protocol.

## Decision

One `OutgoingMmsPayload.Message` represents one direct or group operation. It
accepts bounded UTF-8 text, an optional bounded subject, and at most 10
defensively copied attachments. Each attachment is limited to 786,432 bytes and
the aggregate is limited to 917,504 bytes before PDU overhead. The encoder uses
the already noticed official-AOSP `SendReq` composer at the same immutable
revision admitted by ADRs 0021 and 0024. It emits one multipart-related PDU with
bounded SMIL, optional text, and deterministic app-owned part names. Recipient
sets remain exact; a group is never converted to individual SMS messages and a
failed MMS is never silently downgraded.

The app routes an exact group, a subject, multi-unit text, or any attachment to
MMS. The existing composer operation reserves the exact durable draft revision,
subscription, signature, and transport kind. Provider parts and the outgoing
MMS row are written before the platform call. The operation crosses durable
`PREPARED` and `SUBMITTING` checkpoints through a caller-owned observer, then
uses the existing content-free staging journal and exact private callback.
Known-unsent work preserves the draft; ambiguous submission is not retried; an
exact sent callback clears only the reserved draft revision. State schema 13
keeps SMS and MMS callback ownership disjoint by provider kind and message ID.

The user-facing attachment surface uses Android Photo Picker with its system
fallback and asks for images only. Aurora copies at most 16 MiB from one selected
source, rejects invalid dimensions and pixel counts, bounds decode and output
dimensions, then re-encodes with the platform bitmap codec. The admitted MMS
part is JPEG or PNG under the tighter attachment limits. Source URI, grant,
filename, EXIF, color profile, and container metadata are not retained. The UI
shows only generic image size and bounded index; diagnostic strings redact
content. No `READ_MEDIA_*`, storage, Internet, or network-state permission is
added.

Image-only MMS is allowed only when the reservation explicitly declares an
attachment and MMS transport. A normal blank draft and every SMS reservation
remain ineligible. The sanitized attachment selection is process-local before
Send; process death before submission drops the selection while leaving any
durable text/subject draft untouched. After the user taps Send, the durable
operation/provider/staging protocol owns crash classification.

## Verification

- General encoder host tests cover direct/group recipients, text, subject,
  attachment ordering, bounds, deterministic SMIL, malformed metadata, and
  defensive copying.
- Fake-provider/platform tests cover parts-first preparation, exact ownership,
  rollback, callback authentication, and rejection before platform submission.
- Durable coordinator tests cover group no-fan-out, subject and long-text MMS,
  attachment propagation, image-only payloads, known-unsent preservation,
  ambiguous submission, callback completion, and restart classification.
- State schema 12-to-13 migration and repository tests pass on API 26 and API
  36. The attachment-only reservation suite passes on both API levels.
- Three sanitizer tests pass on API 26 and API 36; the API 36 Compose suite
  covers the extras menu, generic attachment row, removal, MMS label, disabled
  scheduling, and image-only Send state.
- At source commit `1eb7e57`, the complete API 36 and API 26 connected matrices
  pass 443 and 437 enumerated tests with 10 and 13 intentional protocol skips,
  respectively, and zero failures or errors. The root group-composer acceptance
  test verifies one MMS command for the exact group/subscription and no second
  send request.
- Relevant app, state, telephony, and conversations lint gates pass. No live
  provider, SMS role change, carrier send, physical message content, or broad
  log participates.

## Consequences

AuroraSMS now implements a bounded general one-person/group outgoing MMS path
without SMS fan-out and with one reviewed user-facing image pipeline. This is
synthetic implementation evidence, not carrier acceptance. Physical direct and
group send/receive, APN/carrier size behavior, billing/roaming, dual-SIM/OEM
callbacks, media interoperability, process death at every checkpoint, and
pre-send attachment restoration remain release gates. Audio/video/vCard and
animated-image composer UI are not admitted by this decision. AuroraSMS is not
gold.
