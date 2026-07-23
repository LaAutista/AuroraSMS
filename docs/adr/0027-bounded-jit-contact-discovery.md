<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0027: bounded just-in-time contact discovery

- Status: Accepted; synthetic implementation verified
- Date: 2026-07-21

## Context

The N1 New chat surface deliberately admitted only bounded manual recipient
entry. N2A needs a familiar way to select a saved phone contact without turning
contact access into messaging onboarding, retaining an address-book copy, or
opening the still-unreviewed first-contact provider-write and transport path.

Contacts contain sensitive names, numbers, and photo references. A search can
also outlive its UI owner, return hostile or oversized metadata, or expand from
read access into mutation authority through an unnoticed manifest change. A
denied or revoked optional permission must not make ordinary number-based
messaging unavailable.

## Decision

AuroraSMS keeps `READ_CONTACTS` as an optional production permission and never
requests `WRITE_CONTACTS`. It does not request contact access during app or
SMS-role onboarding, startup, Inbox entry, or merely because New chat opened.
The user must explicitly choose either the existing Inbox-overflow **Use
contacts** action or open **Find contacts** and choose its in-panel **Manage
contact access** action. Denial, dialog cancellation,
revocation, permanent denial, or provider failure leaves bounded manual number
entry and the existing draft flow available. A permanent denial may route an
explicit retry to Android application settings; Aurora never loops the
permission dialog.

Discovery is a read-only `ContactsContract.CommonDataKinds.Phone` filter query
on the IO dispatcher. It projects only phone number, primary display name, and
photo URI. The public request accepts a trimmed, control-free query of 1 through
100 characters and a limit of 1 through 50 results. N2A requests 20 results and
reads at most 21 rows so the UI can disclose truncation. The URI carries the
same provider limit, structured query arguments request it where supported, and
the cursor reader independently enforces it for OEM providers that reject those
arguments.

Provider rows are validated before publication. Invalid addresses and
control-bearing, blank, or over-bound metadata are discarded; display names
are limited to 1,000 characters and photo URIs to 2,048 characters. Selectable
addresses are canonicalized and unique, with deterministic ordering. Models
redact field values from diagnostic strings.

The New chat route debounces search by 250 milliseconds. Replacement queries,
panel close, Activity stop, permission loss, and coroutine cancellation cancel
the active operation; cancellation is propagated to the ContentResolver with
Android's `CancellationSignal`, and the cursor is always closed. Query text,
names, numbers returned as results, and photo references are memory-only. Query
and unselected result metadata clear when the panel closes. A selected bounded
display label may remain only for its recipient chip until removal, Activity
stop, or permission loss. None of that metadata is written to Room, preferences,
logs, diagnostics, exports, backup, or a contact cache. Explicitly selecting a
result contributes only its validated address to the existing bounded recipient
and participant-draft authority, just as manual entry does.

Discovery and selection do not resolve or create a Telephony provider thread,
write either provider, or invoke SMS/MMS transport. New chat Send remains
disabled. Provider-thread resolution, durable first-contact write ownership,
subscription choice, and explicit transport are separate N2B gates.

The isolated benchmark manifest continues to remove `READ_CONTACTS`. The exact
permission verifier continues to compare complete expected permission sets for
every production/benchmark merged manifest and packaged APK. The installed
production manifest contract requires `READ_CONTACTS` and rejects
`WRITE_CONTACTS`; N2A does not weaken the benchmark or exact-verifier boundary
and adds no network capability.

## Verification

The implementation includes synthetic contracts for invalid/bounded requests,
metadata projection and deduplication, permission denial/revocation, provider
failure, cancellation propagation and cursor closure, route debouncing and
stale-result isolation, explicit permission recovery, result selection, manual
fallback, and the production manifest permission pair. Presentation tests cover
closed/empty/loading/unavailable/error/truncated/results states with redacted,
bounded models.

Focused host, complete governed host, API 36, and compact API 26 synthetic
matrices pass; exact commands, counts, and durations are recorded in
`docs/TEST_MATRIX.md`. No live contact row, message, provider mutation,
permission or SMS-role change, or carrier send participated. A real-device
Contacts-provider journey with owner-controlled permission
grant/denial/revocation remains open even after these synthetic matrices pass.

## Consequences

AuroraSMS can discover and select a saved contact without persisting a second
address book or blocking manual entry. Contact access remains contextual,
bounded, cancellable, and read-only, at the cost of re-querying after the panel
or Activity leaves the active flow. N2A does not enable first-contact sending
and closes no carrier, provider-thread ownership, physical Contacts-provider,
signing, or publication gate. AuroraSMS is not gold.
