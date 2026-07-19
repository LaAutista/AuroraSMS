# ADR 0010: Durable conversation subscription preferences

Status: Accepted and implemented for the bounded existing-Thread SMS composer

Date: 2026-07-19

## Context

Phase 5A could send one SMS unit only through the active SMS-capable subscription
recorded on the Thread's latest indexed message. That conservative association
prevented silent SIM fallback, but it did not let a user explicitly choose and
remember another active SIM for the same verified conversation.

A provider thread ID is only a routing hint: Android may recreate a thread while
the exact verified participant set remains the same. Raw participant addresses
must not be copied into Aurora's durable preference store, and a missing,
disabled, or removed remembered SIM must never cause an automatic switch.

## Decision

Aurora state schema 7 adds one content-free
`conversation_subscription_preferences` row per exact participant set. The
primary key is a purpose-separated SHA-256 token derived from the verified
participant set. It deliberately uses a different hash domain from appearance
and draft identities. The row stores only:

- the private participant-set token;
- the current provider thread ID as a routing hint;
- the chosen non-negative Android subscription ID;
- an optimistic revision; and
- a monotonic mutation timestamp.

The repository creates a row only when no preference was observed, and updates
only the exact observed revision. Room schema, migration, open callbacks, and
physical triggers enforce the key format, positive thread identity,
non-negative subscription, sequential revision, and increasing timestamp.

The Thread header exposes an explicit SIM selector from the bounded active,
SMS-capable subscription snapshot. Until the state preference read completes,
the composer cannot send. With no durable preference, the existing latest-message
association remains the conservative default. Once a preference exists, it is
authoritative. If that exact subscription is no longer active, the UI displays
an unavailable warning, requires an explicit replacement selection, and leaves
Send disabled.

The durable send coordinator independently reconstructs the purpose-separated
participant identity and re-reads the preference immediately before reservation.
It accepts the command only when the requested active subscription matches the
durable preference, or—when no preference exists—the verified Thread's latest
subscription. Preference storage failure, corrupt data, a stale selection, or a
missing active SIM refuses before draft reservation, provider mutation, or the
platform transport boundary.

## Consequences

- A user-selected SIM survives process death and provider thread recreation for
  the same exact verified participants.
- A removed or disabled remembered SIM never silently falls back.
- The state row contains no address, recipient, message body, subject, carrier
  label, phone number, or contact data.
- The currently implemented composer remains limited to a verified one-person,
  one-unit existing Thread. Scheduling, group MMS, multipart SMS, and automatic
  retry remain outside this slice.
- Real dual-SIM hardware, physical SIM removal, OEM behavior, and carrier sends
  still require explicit physical-device acceptance; emulator and fake evidence
  cannot close those gates.
