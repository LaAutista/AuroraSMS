<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0020: best-known cache during an incomplete history refresh

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

The rebuildable index retains rows from earlier durable generations until a new
generation exhausts both providers and passes count, head, fingerprint, and
projection verification. A refresh updates each deduplicated message and
conversation row in place with its newest generation ID. If role loss or an
actual provider change interrupts that refresh, restricting Inbox and Thread to
only the newest generation can hide valid cached rows that have not yet been
revisited. On the physical Pixel this exposed 2,100 rows from the newest partial
generation even though the private cache retained 5,226 rows and 73 conversation
projections across its durable generations.

Android still permits AuroraSMS to display its own private cache while another
SMS app holds the default role, but AuroraSMS cannot claim that cache matches the
current Telephony provider.

## Decision

When current index coverage is not verified complete, Inbox, conversation
lookup, Thread pagination, bounded full-message expansion, and participant
previews present the best-known union of physically retained index rows without
filtering by `last_seen_generation`. Provider-qualified message identity and
provider Thread identity remain deduplicated by the existing database keys, so
the union cannot create a second copy of the same cached provider row.

The page cursor still carries the newest generation ID. A replacement
generation invalidates old cursors, while Room invalidations reload presentation
as batches advance. The incomplete-history notice states that AuroraSMS is
showing best-known cached history and that recent changes may not be reflected.

This cache union is presentation evidence only. Incomplete coverage never
produces `VerifiedConversationIdentity`; sending, deletion, spam decisions,
blocking, signature overrides, subscription selection, and other exact-identity
actions remain disabled or fail closed. The change performs no Telephony
provider read, provider mutation, permission grant, role change, carrier action,
network request, or background work.

After a generation is verified complete, Inbox and Thread return to strict
generation-qualified queries. The existing atomic completion transaction then
deletes rows, conversations, and participants not seen in that authoritative
generation. Search already followed the same best-known-cache behavior and
continues to report incomplete coverage.

## Consequences

- Losing the SMS role no longer makes thousands of already cached older
  messages and conversations disappear merely because a newer refresh paused.
- Cached content may be stale, may include a provider row deleted since the last
  verified scan, or may omit a recent provider change; the UI discloses that
  limitation until authoritative verification completes.
- A complete current-provider proof still requires the user to explicitly make
  AuroraSMS the default SMS app and keep it foreground-readable through
  verification.
- No index or durable-state schema migration is required.
