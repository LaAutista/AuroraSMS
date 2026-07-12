# Dependency policy and allowlist

Status: Phase 0

AuroraSMS prefers small original implementations and Android platform APIs.
Dependencies are admitted only when they provide a necessary, maintained,
well-tested capability more safely than a small internal implementation.
Familiarity or use by another SMS application is never a rationale.

## Admission requirements

Before adding a plugin, library, processor, native binary, or transitive
runtime component, record:

- exact group, artifact, version, and repository;
- canonical upstream and source repository;
- SPDX license and compatibility with GPL-3.0-or-later distribution;
- maintenance/release health and known security status;
- exact AuroraSMS use and why platform/internal alternatives are insufficient;
- transitive graph, native code, manifest entries, permissions, services,
  providers, receivers, startup initializers, and network behavior;
- release APK/DEX/resource size impact;
- data accessed, retained, logged, or transmitted;
- rejection/removal plan and the phase that owns it.

The reviewer must inspect the resolved artifact and merged manifest. Marketing
claims and top-level license badges are not sufficient.

## Resolution rules

- Pin every version. Dynamic versions, ranges, snapshots, changing modules, and
  unreviewed local binaries are forbidden.
- Resolve only from `google()`, `mavenCentral()`, and the Gradle Plugin Portal
  where a reviewed build plugin requires it.
- Do not add JitPack or arbitrary Maven repositories.
- Enable Gradle dependency verification with checksums and trusted signing
  metadata where available.
- Commit dependency locks/version catalogs and review lock changes as code.
- Reject duplicate libraries that solve the same problem.
- Prefer pure Kotlin/Java artifacts; native code requires a separate ABI,
  update, and vulnerability plan.
- Debug/test tooling must not leak into release variants.
- No dependency may add `INTERNET` or another permission outside
  `docs/PERMISSION_LEDGER.md`.

## Absolute denylist

- `org.fossify:*` and artifacts selected from a reference app;
- ad, analytics, attribution, telemetry, crash-upload, A/B testing, remote
  configuration, account, social, or tracking SDKs;
- Firebase and Google Play services in the FOSS build;
- remote theme, font, GIF-search, spam-reputation, or content SDKs;
- closed-source runtime libraries when a credible FOSS path exists;
- dependencies that execute downloaded code or silently initialize a network
  client;
- unmaintained MMS/security/media libraries without an owned replacement plan;
- broad "utility" libraries whose used functionality is small and clear enough
  to implement and test internally.

## Exact Phase 1 allowlist

Only the coordinates/plugins below are approved before implementation. A row is
still included only when Phase 1 actually uses it; the generated notices and
resolved transitive graph are reviewed before merge.

| Coordinate or plugin | Version | Scope | License | Approved purpose |
|---|---:|---|---|---|
| Gradle distribution | 9.4.1 | Build | Apache-2.0 | Wrapper-only build runtime |
| `com.android.application` / `com.android.library` | 9.2.1 | Build | Apache-2.0 | Android modules and lint |
| `org.jetbrains.kotlin.jvm` | 2.3.10 | Build | Apache-2.0 | Pure JVM model module only |
| `org.jetbrains.kotlin.plugin.compose` | 2.3.10 | Build | Apache-2.0 | Compose compiler integration |
| `com.github.jk1.dependency-license-report` | 3.1.4 | Build/report only | Apache-2.0 | Pinned dependency license inventory and allowlist check |
| `org.cyclonedx.bom` | 3.2.4 | Build/report only | Apache-2.0 | Machine-readable aggregate CycloneDX SBOM |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.3.10 | Runtime | Apache-2.0 | Kotlin standard library |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Runtime | Apache-2.0 | Structured asynchronous contracts |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Runtime | Apache-2.0 | Android dispatch/lifecycle integration |
| `androidx.core:core` | 1.19.0 | Runtime | Apache-2.0 | Minimal Android compatibility and Kotlin extensions; `core-ktx` is not added because it is empty compatibility at this version |
| `androidx.activity:activity-compose` | 1.13.0 | Runtime | Apache-2.0 | Activity result/role host and debug Compose UI |
| `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | Runtime | Apache-2.0 | Lifecycle-aware state/collection; the separate KTX compatibility artifact is not added |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Runtime | Apache-2.0 | Compose lifecycle collection |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Debug only | Apache-2.0 | Debug diagnostic state holder; excluded from release runtime resolution |
| `androidx.compose:compose-bom` | 2026.06.01 | Version platform | Apache-2.0 | Align approved Compose artifacts |
| `androidx.compose.ui:ui` | BOM | Runtime | Apache-2.0 | Compose UI primitives |
| `androidx.compose.foundation:foundation` | BOM | Runtime | Apache-2.0 | Lists/layout primitives used by diagnostics |
| `androidx.compose.material3:material3` | BOM | Runtime | Apache-2.0 | Accessible diagnostic primitives |
| `junit:junit` | 4.13.2 | Test only | EPL-1.0 | Host-side tests |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Test only | Apache-2.0 | Deterministic coroutine tests |
| `androidx.test:core` / `core-ktx` | 1.7.0 | Android test only | Apache-2.0 | Component/application test APIs |
| `androidx.test:runner` | 1.7.0 | Android test only | Apache-2.0 | Instrumentation runner |
| `androidx.test:rules` | 1.7.0 | Android test only | Apache-2.0 | Receiver/service/activity contracts |
| `androidx.test.ext:junit` / `junit-ktx` | 1.3.0 | Android test only | Apache-2.0 | Android JUnit integration |
| `androidx.compose.ui:ui-test-junit4` | BOM | Android test only | Apache-2.0 | Diagnostic Compose behavior |
| `androidx.compose.ui:ui-test-manifest` | BOM | Debug test only | Apache-2.0 | Compose test host manifest |

No icon-pack artifact is approved; AuroraSMS uses small original vector assets.
No static-analysis plugin beyond Android Lint is approved at this gate. Detekt
and formatting plugins require a later exact-coordinate review. License and
SBOM reporting use only the two exact build-only plugins above. The license
report runs locally with remote resource import disabled and `--no-parallel`
under Gradle 9; CycloneDX produces an aggregate BOM without uploading it.

Approved repositories are `google()`, `mavenCentral()`, and the Gradle Plugin
Portal for the listed plugins only. Approval does not cover other artifacts in
the same group, and no unlisted transitive may remain without license,
permission, startup, network, and necessity review.

## Deferred and decision-gated dependencies

| Capability | Earliest phase | Decision gate |
|---|---:|---|
| MMS PDU compose/parse | 1 | Write an ADR comparing an original implementation, official Android platform/framework PDU material that is not sourced from an end-user messaging app, and a maintained permissive library; audit provenance, license, network/APN assumptions, and transitive graph |
| Phone-number normalization | 1 | Prefer platform APIs; approve libphonenumber only if measured correctness needs justify its size |
| Room | 2 | Separate index/state database design, FTS4 support, schema export, explicit migrations |
| Paging 3 | 2-3 | Confirm bounded keyset/anchor behavior and no deep OFFSET fallback |
| DataStore | 4 | Versioned profile/preferences only, never message storage |
| Image/GIF decoding library | 4 | One-visible-decoder lifecycle, bomb limits, no network fetcher, APK size, API 26 behavior |
| WorkManager | 2 or 5 | Only for a lifecycle need platform alarms/receivers cannot meet; audit manifest and background behavior |
| Biometric | Later privacy phase | App-lock semantics and fallback wording |
| SQLCipher | Post-V1 experiment | Startup/FTS/migration/size benchmarks on low- and mid-range devices |

No MMS PDU, image, GIF, Room, Paging, WorkManager, biometric, or SQLCipher
dependency is approved at the Phase 0 gate.

## License policy

Preferred dependencies use Apache-2.0, MIT, BSD, ISC, or another clearly
GPL-compatible FOSS license. Copyleft or weak-copyleft libraries require an
explicit distribution/source-obligation review. Test-only EPL components must
not be bundled into the APK. Unclear, custom, source-available, non-commercial,
or no-license artifacts are rejected.

Artwork is governed separately and is not assumed to inherit the source
license.

## Supply-chain and privacy verification

Phase 1 CI design:

1. resolve from a clean cache in a controlled verification job;
2. validate dependency checksums/locks;
3. produce direct and transitive dependency trees for every variant;
4. scan resolved coordinates for the denylist;
5. generate license notices and a machine-readable SBOM;
6. inspect the merged manifest for added permissions/components/initializers;
7. fail on `INTERNET`, an unapproved repository, or an unrecorded artifact;
8. run a release-size comparison and explain growth over 5%;
9. keep reports free of user messages, addresses, private reference images, and
   workstation secrets.

## Dependency record template

```text
Coordinate/version:
Phase and owner:
Upstream/source URL:
SPDX license:
Purpose:
Platform/internal alternatives considered:
Transitive dependencies:
Manifest/permission/startup effects:
Data/network behavior:
APK size delta:
Security/maintenance evidence:
Removal or replacement plan:
Reviewer/date:
```

## Removal rule

A dependency is removed or replaced when it becomes unmaintained, changes to
an incompatible license, introduces an undeclared permission/network path,
cannot meet a supported API/security requirement, duplicates a platform
capability, or costs more size/performance/complexity than its measured value.
