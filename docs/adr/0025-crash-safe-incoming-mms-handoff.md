<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0025: crash-safe incoming MMS handoff

- Status: Accepted; synthetic implementation verified
- Date: 2026-07-20
- Implementation: `260fd18522a31b7bce4c4e6dbfbac99c9c83fecd`

## Context

ADR 0024 admits bounded `M-Notification.ind` and `M-Retrieve.conf` parsing, but
decoding alone cannot safely receive an MMS. Android owns the carrier download,
Aurora owns an app-private staging file, the Telephony provider owns durable
message history, and the notification is the user-visible completion. Process
death or a duplicated private callback can occur between any two of those
owners. Reissuing an uncertain carrier download can duplicate work or charges;
deleting a staged PDU before provider and notification acknowledgement can lose
a message.

## Decision

Each incoming notification is assigned an operation ID from the dedicated
`INCOMING_MMS` namespace and reserved in a checksummed, metadata-only journal.
The journal stores the subscription, transaction ID, a SHA-256 notification
digest, advertised size, receive time, owned staging filename, and later the
exact MMS provider and conversation IDs. It never stores the carrier URL,
sender, recipients, subject, text, or attachment bytes and is capped at 128
entries.

The state machine is:

`RESERVED -> STAGED -> SUBMITTING -> CALLBACK_SUCCEEDED -> PERSISTED`

`SUBMITTING` may instead become `SUBMISSION_UNKNOWN` or `CALLBACK_FAILED`.
Aurora writes `SUBMITTING` before calling
`SmsManager.downloadMultimediaMessage`. A runtime exception after that
checkpoint becomes non-retryable `SUBMISSION_UNKNOWN`; startup recovery never
calls the platform downloader. Repeated notification indications return the
existing operation by exact digest rather than submitting again.

The private callback must match the incoming operation and exact staged file.
Successful bytes are read through the existing 1-MiB staging boundary and ADR
0024 decoder. Projection rejects a transaction mismatch or missing sender,
preserves bounded TO/CC address rows, combines bounded `text/plain` parts, and
removes the active line from group Thread identity when that line is available.
A group whose own line cannot be identified remains deferred rather than being
silently attached to the wrong Android Thread.

Provider persistence is one idempotent transaction. Aurora writes parts under a
temporary owner, resolves one Thread, inserts the Inbox `RetrieveConf`, moves
the parts, inserts FROM/TO/CC rows, then validates the exact message identity,
size, and address/part counts. Any failure removes only the app-owned partial
rows. Exact replay returns the existing complete row; ambiguous duplicates fail
closed.

After provider success, the journal records `PERSISTED`. The app requests index
reconciliation, applies the existing local block decision, and posts a
group-aware notification. Incoming MMS quick reply is disabled so a group can
never fan out as individual SMS messages. Only an exact successful notification
or blocked-sender acknowledgement removes the journal owner and staging file.
Startup replays provider/notification completion from the retained PDU without
ever replaying the carrier download.

The app still requests no `INTERNET` or `ACCESS_NETWORK_STATE` permission.
Carrier retrieval remains exclusively in Android's `SmsManager`; Aurora has no
APN client or socket path. The active line number is used only ephemerally for
group identity and is never stored, logged, exported, or included in a
diagnostic string.

## Verification

- All host unit tests pass, including projection, app intake injection, and
  group-notification acknowledgement contracts.
- Eight synthetic submission/recovery tests pass on both API 26 and API 36.
  They cover journal-before-platform ordering, duplicate WAP suppression,
  post-checkpoint platform uncertainty, provider runtime deferral, group
  persistence, notification replay after restart, non-resubmission across two
  recovery passes, and cleanup before the platform boundary.
- Implementation commit `f2f4f5c` adds an explicitly gated host-force-stop
  journey that passes independently twice on API 26 and twice on API 36. One
  process commits the synthetic PDU/provider result as `PERSISTED`; after host
  force-stop, a fresh process produces exactly one pending notification without
  a platform resubmission; after another force-stop, a third process repeats
  the durable handoff, acknowledges it, and removes the exact journal and file.
- ADR 0024's five-test hostile/truncation decoder corpus passes on both API
  levels. The metadata journal/staging suite passes on both API levels, and the
  seven-case atomic fake-provider suite passes on API 36.
- `:core:telephony:lintDebug`, `:app:lintDebug`, clean-room, private-asset, and
  dependency gates pass. No live provider, role change, carrier send/download,
  or physical message content participates.

## Consequences

AuroraSMS now has an end-to-end incoming MMS implementation through durable
provider persistence and notification acknowledgement, with synthetic
API-floor/latest evidence. This is not carrier acceptance. A physical receive
still must prove carrier configuration, billing/roaming behavior, OEM callback
delivery, SIM line resolution for groups, media rendering, and physical/in-
flight process death. General one-person/group outgoing carrier acceptance also
remains open. AuroraSMS is not gold.
