<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0014: no group-to-SMS fan-out

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

AuroraSMS requires every ordinary conversation with more than one unique
canonical recipient to remain one MMS operation. Splitting a group into
individual SMS submissions changes privacy, billing, delivery semantics, and
conversation identity. It is forbidden even when MMS encoding or submission
fails.

Phase 5 does not yet expose the full group-MMS composer. Existing Thread,
scheduled-send, and send-delay flows are intentionally one-person SMS only. The
exported Android respond-via-message surface can receive multiple recipients and
therefore needs an explicit single-operation route.

## Decision

`RecipientSet` remains the sole canonical recipient policy. Two or more unique
recipients require MMS and have no single SMS recipient.

`SmsSendRequest` now enforces exactly one canonical recipient at construction.
This is the final shared transport boundary used by composer, delayed,
scheduled, inline-reply, and respond-via paths; a group SMS request cannot be
represented.

Respond-via routing builds one typed submission. A group produces exactly one
`MmsSendRequest`, calls `sendMms` once, and returns any MMS failure unchanged.
There is no retry, loop, per-recipient request, or MMS-to-SMS fallback.

Existing Thread composer, send delay, and scheduled send independently recheck
that the exact verified identity contains one participant before any durable
SMS reservation or sender handoff. The UI keeps group text editable but labels
MMS as unavailable and disables Send.

## Consequences

- No current app path can silently fan a group out into individual SMS sends.
- A failed or unavailable group MMS remains failed/unavailable.
- This decision does not claim a complete group-MMS composer, MMS codec,
  provider addressing, group replies, carrier behavior, or physical send
  acceptance. Those release gates remain open.
- Future group features must pass one `RecipientSet` through one durable MMS
  owner and may not weaken the `SmsSendRequest` constructor invariant.
