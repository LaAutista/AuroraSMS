<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0016: explicit selected-text copy and bounded message details

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

Copying an entire message on long press is convenient but can expose more text
than intended. Combining copy and destructive actions without an intermediate
choice also makes permanent deletion too easy to invoke. Message details are
useful for troubleshooting, but provider IDs, thread IDs, attachment paths, and
message content do not belong in a metadata panel.

## Decision

Long press opens an explicit Message actions dialog. Select text opens a
read-only selector containing only the body currently displayed in that bubble.
The Copy selected action remains disabled for a collapsed or invalid range and
writes exactly the normalized selected substring to Android's clipboard. It
never adds the sender, subject, conversation, provider identifiers, timestamps,
or adjacent text. If only a truncated preview is loaded, the selector labels
that limitation and does not fetch or imply hidden content.

Selection and dialog state remain transient Compose state and are not saved,
logged, indexed, or written to AuroraSMS storage. Clipboard export occurs only
after the visible Copy selected action. Clipboard failure is shown locally
without logging the selected text.

Message details shows a bounded, body-free projection: SMS/MMS type, direction,
localized timestamp, status, subscription availability, and attachment count.
It excludes body, subject, addresses, provider/thread IDs, and attachment paths.
Delete remains available from Message actions and still enters ADR 0013's
separate confirmation and Undo protocol.

## Consequences

- The user controls the exact clipboard range and can see when the source is
  only a preview.
- Long press is no longer synonymous with permanent deletion.
- The Android clipboard is an intentional external surface; other software may
  read clipboard content according to platform policy after the user copies it.
- Rich forwarding, quoting, multi-message selection, and attachment export are
  not added by this slice.
