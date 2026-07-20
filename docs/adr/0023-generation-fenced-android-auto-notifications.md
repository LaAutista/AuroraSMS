<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# ADR 0023: generation-fenced Android Auto notifications

- Status: accepted and implemented
- Date: 2026-07-20

## Context

AuroraSMS already posts private `MessagingStyle` notifications with an exact
conversation route and a durable inline-reply owner. That is not yet an Android
Auto integration. Android Auto also requires an application capability
declaration and a background mark-as-read action, and it consumes the complete
message list carried by the active conversation notification.

A notification action can outlive the visible generation that created it. A
mark-as-read action identified only by thread could therefore mark a newer
message read after that thread's notification was replaced. Relaxing the
inline-reply checks for a car surface could likewise bypass Aurora's role,
recipient, subscription, replay, provider-staging, and transport ownership.

## Decision

AuroraSMS implements the notification-powered Android Auto messaging surface,
not a Car App Library activity. The application manifest references
`automotive_app_desc.xml`, which declares both `notification` and `sms` support.
The `sms` declaration prevents Android Auto's built-in SMS handler from posting
a duplicate notification while AuroraSMS holds the default-SMS role.

Each incoming conversation remains one notification with one stable tag and ID.
It uses `NotificationCompat.MessagingStyle`, an opaque stable `Person` key, an
explicit group-conversation flag, and messages in chronological order. A newer
message may retain only a bounded history extracted from the exact currently
active notification when its recorded privacy mode and group identity still
match. Changing privacy or group identity starts a fresh style, so old sender,
title, or body material can never survive a stricter presentation. Notification
history is presentation state, not a message store or provider authority.

Every reply-capable incoming notification carries two background actions:

- Reply has `SEMANTIC_ACTION_REPLY`, no user interface, exactly one
  `RemoteInput`, and the only mutable `PendingIntent`. Android decides whether
  authentication is needed on a locked device so the action remains usable by
  Android Auto. The existing bounded receiver remains available only for
  already-issued legacy intents; new actions target the background messaging
  service and enter the unchanged durable inline-reply handler.
- Mark as read is an invisible, immutable action with
  `SEMANTIC_ACTION_MARK_AS_READ` and no user interface. Its private URI binds the
  conversation to the exact latest SMS provider message ID.

The mark-read handler rechecks default-SMS role and validates that exact source
row as an incoming SMS in the expected thread. It may update `READ` and `SEEN`
only for incoming SMS rows in that thread whose provider ID is no newer than the
bound source row. A later row therefore remains unread. Only after a successful
or already-satisfied provider result may Aurora cancel the exact notification
generation and its exact reminder owner and request an index refresh. Provider
absence, mismatch, role loss, permission denial, or runtime failure is a
content-free rejection and leaves newer state untouched.

The visible notification remains `PRIVATE` and always has a generic public
version. `SENDER_ONLY` and `GENERIC` remove disallowed material before
`MessagingStyle` construction. Failure alerts and reminders remain generic and
body-free. Android Auto actions do not add a network permission, provider-body
mutation, carrier send path, or fallback transport.

## Consequences

- Android Auto can discover AuroraSMS without a proprietary runtime dependency.
- Voice reply still crosses the same durable, duplicate-resistant send boundary
  as a phone notification reply.
- A stale mark-read action cannot consume a newer message, reminder, or visible
  notification generation.
- The first implementation covers incoming SMS. Incoming/group MMS remains
  unavailable until Aurora has a complete MMS receive codec and provider path.
- Desktop Head Unit, physical lockscreen, OEM, and carrier-success behavior are
  acceptance evidence and are never inferred from host tests.
