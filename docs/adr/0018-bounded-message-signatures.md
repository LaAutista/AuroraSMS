<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0018: bounded global and conversation message signatures

- Status: Accepted, implemented, and locally accepted
- Date: 2026-07-19

## Context

A message signature changes the exact text submitted to the carrier. Treating it
as presentation-only state would let segment counts, delayed sends, scheduled
sends, or crash recovery disagree with what the user reviewed. Storing it inside
the draft would also make enabling or changing a setting silently rewrite the
user's authored draft.

## Decision

AuroraSMS stores signature settings separately from drafts. A global signature
may be absent. A verified conversation may explicitly inherit the global value,
disable it, or use a custom value. Conversation settings use a purpose-separated
SHA-256 key over the complete canonical participant set; raw addresses are not
written to this preference store. The store is checksummed, fails closed when
corrupt, refuses overwrites while corrupt, and is bounded to 256 conversation
overrides with an absolute implementation ceiling of 1,024. It never evicts an
existing choice to make room for another. A corrupt production store pauses new
sends rather than guessing that no signature was intended.

A signature is normalized to LF line endings and trimmed only when the user
saves it. Blank text means disabled. A nonblank value is limited to 160 UTF-16
characters and four lines, rejects invalid Unicode and non-newline control
characters, and is never truncated. At the transport boundary the exact body is:

```text
user-authored draft
--
signature
```

The separator is the literal string `\n-- \n`. The draft remains unchanged.
The composer counts both the unsigned draft and exact resolved outgoing body
with Android's SMS segmentation logic. It visibly reports when the signature
changes the part count. Group conversations disclose that the signature would
be part of the MMS text. AuroraSMS's current transport gate still allows only
one-person, one-part SMS; it disables submission instead of silently sending a
multipart text, changing a group into SMS, or dropping the signature.

When the user starts an immediate, delayed, or scheduled send, the resolved
signature is frozen into that path's durable owner. State schema 11 adds one
nullable `signature_text` column to each of the composer, scheduled-send, and
send-delay tables. The value is immutable for the owner's lifetime and remains
bounded by SQLite triggers. Recovery and dispatch reuse that frozen value and
still recheck role, verified participant identity, exact draft revision,
subscription, clock, and one-part eligibility before transport.

Signature preferences and frozen operation values are app-private message
content. They are redacted from `toString()`, logs, alarms, intents, and test
fixtures that could escape the app. The application has `allowBackup=false`,
and both legacy and current extraction rules exclude all databases and shared
preferences from backup and device transfer. This feature adds no permission,
network access, provider-body rewrite, or automatic send.

## Consequences

- A preference edit affects later send ownership only; it cannot change an
  already scheduled or delayed message.
- Durable composer owners are no longer strictly content-free. They contain at
  most one bounded frozen signature while active; the authoritative draft still
  owns the user's message body.
- Corrupt settings disable editing and new sends rather than silently dropping
  content. A conversation override is unavailable until the index provides a
  complete verified participant identity.
- Multipart SMS transport and full group MMS remain separate implementation and
  carrier-acceptance work. This decision does not claim those gates complete.
