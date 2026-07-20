<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Phase 7 release and gold plan

Status: active; AuroraSMS is not gold

Phase 7 converts the implemented pre-release application into an auditable
release candidate, then proves the remaining messaging behavior on real
platform and carrier surfaces. Passing packaging checks cannot waive an open
telephony, data-completeness, accessibility, privacy, or physical-device gate.

## Current boundary

The Phase 6H source is `0.6.10-phase6` (`versionCode` 21). Host, R8 release,
benchmark, privacy, dependency, license, and API 26/API 36 connected suites are
green. The following boundaries remain open and prevent a gold claim:

- a complete provider scan on the owner's nonempty Pixel history after one
  deliberate Aurora-default, foreground-readable session;
- general incoming MMS decoding and provider persistence;
- general one-person and group MMS composition/submission without SMS fan-out;
- physical carrier SMS/MMS, dual-SIM, roaming/billing, OEM notification,
  lockscreen, picker, alarm, backup, and Android Auto/DHU acceptance;
- measured physical-device search, jump, startup, frame, and memory budgets;
- an owner-controlled release signing identity and signed checksums; and
- a source-buildable F-Droid recipe tied to a frozen release commit.

The observed missing-history symptom and the missing MMS implementation are
different problems. While provider coverage is incomplete, AuroraSMS presents
the best-known union of cached index generations and disables exact-identity
mutations. Android still requires the default-SMS role for a new authoritative
provider scan. Incoming/group MMS remains a separate codec and carrier feature,
not an indexing delay.

## Workstream 7A: release truth and governance

- [x] Use one project version for app packaging and aggregate SBOM metadata.
- [x] Publish a private-reporting security policy with a data-minimizing report
  protocol.
- [x] Add deterministic release/checksum instructions and source metadata.
- [ ] Freeze the first release candidate and replace all pre-release wording
  only after the functional gates below pass.
- [ ] Record the supported-version window and release-maintainer identity.

## Workstream 7B: reproducible artifacts

- [x] Prove two isolated builds from one clean commit produce byte-identical
  unsigned release APK and AAB outputs.
- [x] Retain exact source commit, toolchain, artifact sizes, SHA-256 hashes,
  license inventory, and CycloneDX 1.6 SBOM.
- [x] Keep release builds R8/resource-shrunk, non-debuggable, Baseline Profile
  equipped, and free of debug/test/private-reference material.
- [x] Explain every release-APK growth above the five-percent budget.
- [ ] Sign final artifacts and `SHA256SUMS` only with an owner-controlled key
  stored outside the repository.

## Workstream 7C: data completeness and migrations

- [x] Export every shipped Room schema and retain an explicit test for every
  adjacent migration: index 1 through 3 and durable state 1 through 12.
- [ ] Complete and verify a nonempty physical-provider generation with both SMS
  and MMS checkpoints exhausted and provider counts reconciled.
- [ ] Prove Inbox, Thread, search, and exact old-result jump across that
  complete history without collecting content in evidence.
- [ ] Exercise upgrade installs from every public release candidate retained for
  support; no destructive migration fallback is permitted.

The physical scan requires a separate owner-visible protocol because assigning
AuroraSMS the SMS role temporarily displaces the daily messaging app and grants
provider access. It does not authorize a carrier send.

## Workstream 7D: complete SMS/MMS vertical

- [ ] Admit a bounded incoming WSP/MMS decoder from an approved, immutable,
  noticed source or a separately reviewed original implementation.
- [ ] Persist notification-indication/download/retrieve results atomically with
  bounded addresses, text, SMIL, and supported media parts.
- [ ] Add a general outgoing `SendReq` composer for one-person and group MMS,
  including subject and reviewed attachment types.
- [ ] Keep one group as one MMS operation; never fan out individual SMS messages
  and never silently downgrade after MMS failure.
- [ ] Add malformed/mutation corpus, provider, process-death, callback, and
  notification tests on API 26 and API 36 before any carrier exercise.
- [ ] Pass approved physical one-person and group MMS send/receive cases.

The existing ADR 0021 voice-memo path remains a narrow one-person outgoing
subset. It is not evidence for general or incoming MMS.

## Workstream 7E: physical and platform hardening

- [ ] Complete physical Pixel SMS receive/send, delivery reporting, direct
  reply, Mark as read, notification privacy, lockscreen, and process-death rows.
- [ ] Complete dual-SIM removal/disablement and explicit subscription routing.
- [ ] Complete exact/inexact scheduled-send and Doze/OEM timing rows.
- [ ] Complete system picker, wallpaper, backup export/import/cancellation, and
  low/full-storage recovery rows.
- [ ] Complete TalkBack, large text, contrast, rotation, narrow/wide layouts,
  hardware keyboard, and reduced-motion checks.
- [ ] Complete Android Auto/DHU reply and Mark as read.
- [ ] Meet the physical performance budgets in `PRODUCT_REQUIREMENTS.md`.

No live message body, address, attachment, contact, database, broad log, role
change, or carrier send is part of an ordinary build/install handoff. Each
privacy- or carrier-sensitive acceptance run needs its own exact procedure and
owner approval.

## Workstream 7F: distribution

- [x] Add localized store text and valid disabled upstream F-Droid metadata that
  states the current pre-release limitations.
- [ ] At the frozen gold commit, add the exact full-hash F-Droid build entry,
  remove `Disabled`, and prove the unsigned APK builds from source without
  proprietary services.
- [ ] Create an annotated release tag, owner-signed APK/AAB/checksum manifest,
  source archive, SBOM, notices, and changelog.
- [ ] Verify the published downloads independently against the signed manifest.

## Gold definition

AuroraSMS goes gold only when every applicable release-gate row in
`TEST_MATRIX.md` is checked or has an explicit owner-approved limitation,
Workstreams 7A through 7F are complete, the exact release commit and artifacts
are reproducible and signed, and the installed release passes the approved
physical/carrier matrix. Until then every build and store description must say
pre-release and must not imply RCS or complete MMS support.
