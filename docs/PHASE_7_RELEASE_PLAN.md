<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Phase 7 release and gold plan

Status: active; AuroraSMS is not gold

Phase 7 finishes the required pre-release product surfaces, converts the app
into an auditable release candidate, then proves the remaining messaging
behavior on real platform and carrier surfaces. Passing packaging checks cannot
waive an open functional, telephony, data-completeness, accessibility, privacy,
or physical-device gate.

## Current boundary

The Phase 7 N1 source is `0.7.0-phase7` (`versionCode` 22). It adds the first
review-only New chat surface while keeping every first-contact transport path
disabled. Host, R8 release, benchmark, privacy, dependency, and license gates
remain required for every candidate. The following boundaries remain open and
prevent a gold claim:

- contact discovery and an explicit first-contact SMS/MMS send path, including
  provider-thread resolution and write ownership;
- Archive, pinning, inbox selection/Mark all read, complete Settings/About, and
  a persisted notification-privacy control wired to the notifier;
- forward and local quote actions, named-profile import/export, bottom/rail
  navigation parity, and the remaining accessibility/adaptive-layout surface;
- a complete provider scan on the owner's nonempty Pixel history after one
  deliberate Aurora-default, foreground-readable session;
- physical incoming MMS carrier/OEM acceptance, including reliable group
  self-line resolution;
- physical general one-person and group MMS carrier/OEM acceptance;
- physical carrier SMS/MMS, dual-SIM, roaming/billing, OEM notification,
  lockscreen, picker, alarm, backup, and Android Auto/DHU acceptance;
- measured physical-device search, jump, startup, frame, and memory budgets;
- an owner-controlled release signing identity and signed checksums; and
- a source-buildable F-Droid recipe tied to a frozen release commit.

The observed missing-history symptom and remaining physical MMS acceptance are
different problems. While provider coverage is incomplete, AuroraSMS presents
the best-known union of cached index generations and disables exact-identity
mutations. Android still requires the default-SMS role for a new authoritative
provider scan. General incoming and group MMS now have bounded synthetic codec,
composer, persistence, and recovery coverage; carrier/OEM acceptance remains a
separate open gate, not an indexing delay.

### N1 New chat review surface

- [x] Add an Inbox New chat entry point with bottom-list clearance.
- [x] Share one Aurora composer between the internal route and external
  `SENDTO` activity without exposing Thread-only controls.
- [x] Validate bounded manual recipient sets and persist body text under the
  existing participant-set draft identity without a schema migration.
- [x] Preserve an existing durable draft when external intent text conflicts;
  never auto-send or persist caller-supplied text merely because an external
  intent opened the app.
- [x] Fail closed across overlapping participant-draft writers, preserve an
  in-flight edit through Activity recreation, and replace reused external
  request state without carrying recipients or text across intents.
- [ ] Add explicit contact discovery/selection with a reviewed permission and
  bounded-query contract.
- [ ] Resolve or create the provider conversation only after an explicit send
  action with durable ownership.
- [ ] Enable and physically verify first-contact SMS/MMS transport.

New chat is a draft-safe review surface only. Send is disabled; recipients are
entered manually and remain fixed while text is present. This slice performs no
contact picking, provider-thread creation or mutation, or SMS/MMS transport.

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
  adjacent migration: index 1 through 3 and durable state 1 through 14.
- [ ] Complete and verify a nonempty physical-provider generation with both SMS
  and MMS checkpoints exhausted and provider counts reconciled.
- [ ] Prove Inbox, Thread, search, and exact old-result jump across that
  complete history without collecting content in evidence.
- [ ] Exercise upgrade installs from every public release candidate retained for
  support; no destructive migration fallback is permitted.

The physical scan requires a separate owner-visible protocol because assigning
AuroraSMS the SMS role temporarily displaces the daily messaging app and grants
provider access. It does not authorize a carrier send. The reviewed procedure
is `docs/PHYSICAL_PROVIDER_COMPLETION_PROTOCOL.md`; its runner is fail-closed,
does not change the role or permissions itself, and reports only aggregate
generation/checkpoint/index consistency fields. The protocol remains unexecuted
for the current candidate until the Pixel is connected and the owner opens that
provider-read window. Role recovery preserves the durable cursor of an
incomplete first-history scan, but now starts a fresh full generation when the
previous state was complete, closing the interval in which another default SMS
app could have changed deep provider history beyond a bounded head check.

## Workstream 7D: complete SMS/MMS vertical

- [x] Admit a bounded incoming WSP/MMS decoder from an approved, immutable,
  noticed source or a separately reviewed original implementation.
- [x] Persist notification-indication/download/retrieve results atomically with
  bounded addresses, text, SMIL, and supported media parts.
- [x] Add a general outgoing `SendReq` composer for one-person and group MMS,
  including subject and reviewed attachment types.
- [x] Keep one group as one MMS operation; never fan out individual SMS messages
  and never silently downgrade after MMS failure.
- [x] Add malformed/mutation corpus, provider, process-death, callback, and
  notification tests on API 26 and API 36 before any carrier exercise.
- [ ] Pass approved physical one-person and group MMS send/receive cases.

The existing ADR 0021 voice-memo path remains a narrow one-person outgoing
subset. ADR 0024 admits the incoming codec; ADR 0025 and implementation commit
`260fd18522a31b7bce4c4e6dbfbac99c9c83fecd` add the metadata-only download
journal, authenticated callback, atomic provider transaction, notification
acknowledgement, and no-resubmission startup recovery. ADR 0026 and commits
`7a45033`, `a71c623`, `0b27160`, and `1e2344b` add the bounded general/group
composer, one-operation durable ownership, subject/long-text routing, and the
metadata-stripping JPEG/PNG picker surface. Commit `0d93626` adds schema-14
draft-owned sanitized attachment persistence, close/reopen restoration,
Activity-recreation acceptance, and fail-closed Send gating when that authority
cannot be read. Commit `b29b5ba` adds a separately gated API 36 host-force-stop
journey: a production-Room draft and identical sanitized attachment bytes
survive into a fresh process, the real root renders them and routes exactly one
synthetic MMS operation, and a third fresh process proves parent/cascade
cleanup. This is not physical carrier behavior; in-flight send death, physical
carrier/OEM, and the broader process-death matrix remain open. Implementation
commit `f2f4f5c` adds a separate isolated test-package journey on API 26 and API
36: a completed synthetic incoming PDU/provider owner survives one host
force-stop, a fresh process reconstructs exactly one pending notification
without a platform resubmission, and a third process acknowledges and removes
the exact journal/staged-file owner after another force-stop. It passes twice
per API and closes the synthetic malformed/provider/callback/notification/
process-death test row, not physical carrier receipt or death during an actual
platform callback.

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
- [x] Isolate the R8-enabled synthetic benchmark target from the production
  package, private index, SMS role, messaging permissions, and carrier/role
  components.
- [ ] Meet the physical performance budgets in `PRODUCT_REQUIREMENTS.md`.

The performance runner is `scripts/run-isolated-performance-suite.sh`.
`--smoke` is restricted to emulators and proves only boundary/journey
reachability. `--full` refuses emulators and dirty source, uses the 30/10/10
physical counts, records exact commit/APK/device provenance, retains strictly
accounted AndroidX JSON and Perfetto artifacts, and fails any documented
timing, frame, frozen-frame, or PSS budget. Full runs require 8 GiB free on the
host and device; smoke requires 1 GiB. Smoke substitutes a renderer-independent
RSS metric for startup/frame reachability and cannot close those budgets. Raw
instrumentation, JSON, metadata, and traces are message-content-free but
device-identifying private evidence and must not be published. Both modes
require the role and production package path to remain unchanged and all
messaging authority to remain absent; cleanup proves
`org.aurorasms.app.benchmark`, its same-signed controller, and device-side
temporary output are gone before reporting success. Passing the runner does
not waive the separate real-provider responsiveness or carrier/device gates.

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
