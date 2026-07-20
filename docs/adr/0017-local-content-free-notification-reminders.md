<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0017: local content-free notification reminders

- Status: Accepted and implemented
- Date: 2026-07-19

## Context

A later reminder can help a user return to an unread incoming message, but its
durable state, alarm intent, and notification must not become a second message
store or expose content. Repeating alarms, stale identities, role loss, clock
changes, and transient provider failures can otherwise produce misleading or
duplicated alerts.

## Decision

Message reminders are off by default. The user may choose 15 minutes, one hour,
or three hours from the inbox overflow. A successful incoming SMS notification
and provider acknowledgement may create one content-free durable owner for that
conversation. At most 64 owners exist. An owner contains only a monotonic local
ID, SMS provider-row ID, conversation ID, due time, creation time, and checksum;
the private one-shot alarm carries only the local ID.

At fire time AuroraSMS rechecks default-SMS role, lateness, and the exact SMS
provider row. Only a successfully read, exact, incoming, still-unread row can
produce a generic reminder. Durable ownership is removed before notification,
so a crash fails closed and no alarm repeats. A confirmed read, missing, or
mismatched row also cancels the exact incoming notification generation. A
provider error consumes only the reminder and preserves the original incoming
notification because the error proves neither read state nor row absence.

Opening the conversation cancels its owner and exact incoming notification.
Changing or disabling the setting clears existing owners. Role loss, reboot,
wall-clock change, and timezone change fence pending work; process/package
startup may rearm only validated future owners. Preference and owner state are
private and excluded from OS/cloud backup. The implementation requests neither
exact-alarm access nor a repeating wakeup.

## Consequences

- Reminder notification text is always generic and contains no sender, address,
  body, subject, or attachment metadata.
- A newer incoming SMS in the same conversation replaces the older reminder.
- Reboot and clock changes deliberately discard pending reminders rather than
  guessing at timing.
- Physical OEM/Doze timing and notification-channel behavior remain release
  evidence, not assumptions made by this local implementation.
