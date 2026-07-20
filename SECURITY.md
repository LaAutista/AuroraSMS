<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AuroraSMS security policy

## Supported versions

AuroraSMS has not published a stable release yet. The current source is a
pre-release build and is not supported as a finished messaging replacement.
Security reports against the latest source revision are welcome and will be
triaged on a best-effort basis.

| Version | Security support |
|---|---|
| Latest pre-release source | Best-effort triage |
| Older snapshots | Upgrade and reproduce first |

This table will be replaced with a concrete supported-release window before the
first gold release.

## Reporting a vulnerability

Email `keklegion@proton.me` with a subject beginning `[AuroraSMS security]`.
Do not open a public issue for a vulnerability that could expose message data,
bypass an authorization boundary, trigger an unintended carrier operation, or
compromise a user's device.

Include only the minimum information needed to reproduce the issue:

- the AuroraSMS commit and build variant;
- Android version and device class;
- a description of the affected boundary and expected behavior;
- reproduction steps using synthetic data; and
- a minimal redacted trace, if one is necessary.

Never send real message bodies, phone numbers, contacts, attachment contents,
backup passphrases, signing material, private artwork, broad device logs, or a
copy of an AuroraSMS database. If the report cannot be explained safely without
personal data, say so first and wait for a narrower collection protocol.

## Scope and disclosure

High-priority areas include SMS/MMS role enforcement, provider writes, carrier
submission, notification actions, backup authentication, URI grants, exported
components, signing/update integrity, and any unexpected network capability.
UI defects without a security impact can use the public issue tracker.

Please allow time for a fix and a coordinated disclosure decision before
publishing details. Receipt or remediation time is not guaranteed while the
project remains pre-release. Credit will be offered when requested and safe.
