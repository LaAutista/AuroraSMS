# AuroraSMS clean-room charter

Status: Phase 0 baseline, 2026-07-12

AuroraSMS is a new, original Android SMS/MMS application. It is not a fork,
port, continuation, rebrand, source refactor, or line-by-line recreation of
another messaging application.

## Repository boundary

- This repository was initialized independently on branch `main` with zero
  inherited commits.
- No source, resources, schemas, tests, manifests, Gradle files, decompiled
  output, or Git objects from another SMS application are present.
- The local `AuroraSMS_Codex_Handoff_PRIVATE/` directory is an allowed private
  input, not a repository artifact. It is ignored in its entirety and must
  never be staged, committed, copied into a module, uploaded, or packaged.
- Production implementation must remain in this repository. If reference-app
  source becomes visible in the implementation environment, stop work, record
  the exposure, and restart implementation from a verified clean state.

## Allowed inputs

The following inputs may inform original AuroraSMS work:

1. The AuroraSMS engineering blueprint, reference manifest, private handling
   notice, interactive AuroraMaterial concept, and supplied artwork in the
   local handoff pack.
2. The eighteen currently present private screenshot files, plus the retained
   fingerprint of one manifest-recorded screenshot that is no longer present,
   as black-box evidence of visible structure and user expectations only.
3. Product decisions and black-box behavior observations supplied by the
   owner.
4. Official Android, AndroidX, Kotlin, Gradle, SQLite, and Material
   documentation.
5. FOSS examples or libraries only after their exact source, version,
   GPL-compatibility, necessity, and modifications are recorded. Permissive
   licenses are preferred; reviewed copyleft inputs follow their obligations.
   Official Android platform/framework material may be consulted, but source
   from an end-user SMS/messaging application is prohibited even when it ships
   in AOSP or has a compatible license.
6. Original code, schemas, strings, icons, test data, and documentation written
   specifically for AuroraSMS.

The owner-selected public launcher derivative is an allowed first-party asset
only when its provenance is recorded in `docs/ARTWORK_CATALOG.md` and it is
offered under GPL-3.0-or-later. Its canonical visual direction retains the SMS
bubble and exactly two simple purple hairpins and permits no `A`, monogram,
letter, or distribution-logo marks. This exception does not admit the private
source artwork or high-resolution working masters into Git or APKs.

## Prohibited inputs and actions

Do not open, clone, search, import, copy, translate, paraphrase line by line,
decompile, or compare against implementation material from AuroraFork,
Fossify Messages, Simple Mobile Tools, or any other SMS application. This ban
includes:

- source code and generated source;
- resources, strings, icons, layouts, artwork, and build configuration;
- manifests, intent declarations, database schemas, migrations, and queries;
- tests, fixtures, screenshots used as test goldens, and internal docs;
- dependency lists selected because a reference app used them;
- Git history, patches, diffs, binaries, or decompiled output;
- `org.fossify:*` artifacts or copied package/resource identifiers.

General behavior may be implemented from product requirements and official
platform APIs. The result must use independently designed names, interfaces,
models, schemas, navigation, components, strings, tests, and build files.

## Private visual-reference rules

The private screenshots and illustrated PDF contain personal conversations and
phone activity.

- Inspect pixels only to understand visible layout relationships and actions.
- Never transcribe or reuse visible names, numbers, dates, avatars, message
  text, or other personal details.
- Never put a private reference in Git, an APK, test assets, golden outputs,
  CI artifacts, issues, pull requests, public docs, store media, or releases.
- Every fixture, preview, demo, benchmark, screenshot, and test must use clearly
  synthetic identities, addresses, timestamps, avatars, and messages.
- The interactive HTML mockup is a concept, not production source. Its sample
  identities and messages are not fixtures, and its implementation details are
  not Android implementation instructions.

## Authorship process

Every phase follows this sequence:

1. Re-read the phase acceptance criteria and current repository diff.
2. State a file-level plan before production edits.
3. Use only allowed inputs and independently name new implementation elements.
4. Add tests with the behavior and keep each architectural/schema change
   reviewable.
5. Run focused verification continuously and the complete phase-gate suite at
   the end.
6. Record dependencies, migrations, permissions, performance evidence, risks,
   and provenance.
7. Stop at the phase gate for review before expanding scope.

No database migration may be made destructive to hide a failure. No claim of
performance, privacy, or transport support is accepted without a repeatable
check.

## Dependency provenance

All dependencies must satisfy `docs/DEPENDENCY_POLICY.md`. Before merge, each
new direct or transitive dependency must have:

- an exact pinned version and repository;
- a canonical project URL and license identifier;
- a necessity statement and rejected-alternative note;
- a privacy, manifest-permission, network, maintenance, and size review;
- an entry in `THIRD_PARTY_NOTICES.md` and the automated generated
  dependency/license report.

Reference-app dependency provenance is never an acceptable rationale.

## Automated enforcement design

Phase 1 CI will add a clean-room verification task with these independently
reviewable checks:

1. Scan production and test roots (`app`, `core`, `feature`, `benchmark`, and
   build logic) for prohibited namespaces and known reference identifiers.
2. Exclude this charter and other boundary documentation from token scanning,
   because those files must name the prohibited inputs to define the rule.
3. Fail if private reference paths, private screenshot filenames, or the
   illustrated private PDF are tracked by Git.
4. Hash repository files and APK/archive entries, then fail on any exact known
   private screenshot, PDF, source-artwork, or high-resolution working-master
   fingerprint even if it was renamed. Store only the SHA-256 denylist in
   clean-room configuration, never the private bytes or extracted personal
   metadata.
5. Fail if an `org.fossify` dependency appears in Gradle resolution output.
6. Fail if an unapproved repository, dynamic version, changing artifact, or
   undeclared dependency appears.
7. Generate and archive a dependency/license inventory without uploading user
   data or private visual references.
8. Inspect the merged FOSS manifest and fail on `android.permission.INTERNET`,
   undeclared permissions, exported components outside the approved ledger, or
   backup settings that contradict the threat model.

The initial prohibited token set includes `org.fossify`, `FossifyOrg`, and
`org.simplemobiletools`. Before Phase 1 code begins, CI will add any additional
known reference-only namespaces without embedding private conversation data.

## Incident response

If prohibited material is accidentally exposed:

1. stop implementation immediately;
2. do not copy, summarize, or commit the exposed implementation material;
3. record what category of material was visible and which AuroraSMS files may
   be affected, without reproducing the material;
4. quarantine affected uncommitted work;
5. restart affected work from the last verified clean commit in a source-
   isolated session;
6. perform a provenance review before resuming.

## Phase 0 attestation

This attestation applies only to this clean AuroraSMS implementation repository
and the current source-isolated implementation session. The supplied blueprint
discloses that an earlier drafting session saw reference source before the
boundary was clarified; that session is not an implementation input, and none
of its code, artifacts, history, or inferred internals is present here.

At this gate in the clean implementation repository/session:

- the Git history is independent and empty before Phase 0 documentation;
- the private handoff is ignored and untracked;
- no reference-application source or inherited artifact was opened or used in
  this clean implementation session;
- no production application code, Android manifest, schema, or build module
  has been created;
- all implementation work remains blocked until Phase 0 review authorizes
  Phase 1.
