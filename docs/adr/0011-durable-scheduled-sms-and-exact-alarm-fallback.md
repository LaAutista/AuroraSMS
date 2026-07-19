# ADR 0011: Durable scheduled SMS and exact-alarm fallback

Status: Accepted and implemented for the bounded existing-Thread SMS composer

Date: 2026-07-19

## Context

Scheduled sending crosses process, reboot, wall-clock, default-role, provider,
subscription, and Android alarm boundaries. An alarm containing recipients or
message text would duplicate sensitive state outside the draft authority. An
alarm that blindly retries after process death, clock changes, or an uncertain
transport handoff can send at the wrong time or create a duplicate.

Android 12 and newer require special access before exact alarms may be armed.
Fresh Android 14 installs do not receive that access by default. Android may
cancel exact alarms and kill the process when access is revoked, so exact-only
state is not a safe fallback. The product must not imply exact timing when the
platform offers only an inexact alarm.

## Decision

State schema 8 adds at most 128 content-free `scheduled_sms_operations` rows.
Each row binds one positive local schedule ID to the exact provider Thread,
draft ID/revision, chosen subscription, absolute due instant, lifecycle phase,
alarm precision, monotonic wall/elapsed anchors, and a purpose-separated hash
of the verified participant set. It stores no address, recipient, body, subject,
contact name, SIM label, or notification text. Unique Thread and draft indexes,
optimistic timestamps, Room migration, and physical SQLite triggers enforce the
ownership and transition rules.

The UI schedules only a durable, nonblank, subject-free, one-part draft in a
verified one-person existing Thread. It freezes that exact draft, displays the
local due time and either “exact alarm” or “may send late,” and requires an
explicit confirmation before cancellation. Inexact schedules expose an
explicit route to Android's exact-alarm special-access screen; denial leaves the
honest inexact schedule intact.

Alarms carry only the local schedule ID in an explicit immutable
`PendingIntent`. When exact access exists, AuroraSMS arms one exact alarm plus a
distinct inexact safety alarm five minutes later. If exact access is absent or
arming throws `SecurityException`, it arms only an inexact alarm at the due
instant. The durable state machine makes either alarm idempotent; successful,
review-required, and cancelled schedules cancel both pending intents.

At the due boundary AuroraSMS reopens durable state, verifies elapsed/wall clock
agreement, rejects early or excessively late delivery, reloads the exact
verified participant identity, re-reads the authoritative conversation SIM,
requires an active SMS-capable subscription and current default-SMS role, and
reuses the existing one-unit durable send coordinator. `PENDING` becomes
`DISPATCHING` before entering that boundary. A duplicate alarm cannot repeat a
dispatch. Once `DISPATCHING` begins, cancellation is no longer offered or
accepted because transport ownership may already have transferred. If process
death occurs before composer reservation, recovery changes the schedule to
visible `REVIEW_REQUIRED`; it never automatically retries an uncertain handoff.

`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, package replacement, and the
exact-access grant broadcast trigger bounded recovery. Future schedules are
re-armed with fresh anchors. A schedule already past due after reboot or a wall-
clock jump is paused for review. Ordinary process death retains the alarm and
may resume a due schedule only after the same clock and send preconditions pass.

## Consequences

- `RECEIVE_BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM` are now present under the
  permission ledger's Phase 5 conditional approval.
- Exact access remains optional and user-controlled; AuroraSMS has a documented
  battery-conscious inexact fallback and makes its timing limitation visible.
- Revocation cannot silently lose the durable schedule because the inexact
  safety alarm and later recovery both re-read the same content-free row.
- Removed SIMs, lost role, changed participants, changed drafts, clock drift,
  duplicate alarms, and interrupted pre-reservation dispatch all fail closed.
- This slice does not add multipart SMS, group fan-out, MMS scheduling,
  automatic retry, a recycle bin, or a claim of physical carrier/OEM acceptance.
- Physical reboot, Doze, exact-access revocation, dual-SIM removal, and live
  carrier timing remain release gates even when emulator and fake evidence pass.
