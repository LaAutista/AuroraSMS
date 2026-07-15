# Dependency policy and allowlist

Status: Phase 1 resolved; Phase 2 Room/KSP and Phase 3 benchmark/profile
admissions reviewed; Phase 4 AuroraMaterial foundation and bounded durable
active-profile/Theme Studio slice reuse the admitted graph; the scoped
profile-reference foundation reused the same graph and passed its resolved,
license/SBOM, manifest, and packaged-output checks; ADR 0007 accepts a
platform-only managed static Thread-wallpaper implementation without a new
coordinate, 2026-07-14

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
| `androidx.test.espresso:espresso-core` | 3.5.0 | Android test only | Apache-2.0 | Dialog-window Back behavior; already resolved transitively by Compose UI test and now exposed only to instrumentation source |
| `androidx.compose.ui:ui-test-junit4` | BOM | Android test only | Apache-2.0 | Diagnostic Compose behavior |

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

## Exact Phase 2 Room/KSP admission

The Phase 2 data-boundary ADR requires generated Room databases and FTS4. The
following exact additions are approved for that scope only:

| Coordinate or plugin | Version | Scope | License | Approved purpose |
|---|---:|---|---|---|
| `com.google.devtools.ksp` | 2.3.9 | Build only | Apache-2.0 | KSP2 code generation compatible with AGP built-in Kotlin; never packaged |
| `androidx.room:room-runtime` | 2.8.4 | Runtime in `:core:index` and `:core:state` | Apache-2.0 | Separate private SQLite databases, validated DAOs, transactions, and FTS4 |
| `androidx.room:room-compiler` | 2.8.4 | KSP processor only | Apache-2.0 | Generate Room implementations and deterministic schema JSON; never packaged |
| `androidx.room:room-testing` | 2.8.4 | Android test only | Apache-2.0 | Version-1 schema identity and future explicit migration tests; never packaged |

Canonical upstreams are the AndroidX Room release/source repositories at
`developer.android.com/jetpack/androidx/releases/room` and
`android.googlesource.com/platform/frameworks/support`, and the KSP source at
`github.com/google/ksp`. Room 2.8.4 is the current stable Room 2 release; Room 3
is not admitted. KSP 2.3.9 is the current stable KSP2 release and supports the
project's Kotlin 2.3 language line. Both projects are actively maintained.

Platform/manual SQLite was rejected because it would duplicate Room's schema,
query, migration, and code-generation validation while retaining the hard sync
risks. KAPT was rejected because it is incompatible with AGP 9 built-in Kotlin;
the official migration guidance recommends KSP. `room-ktx`, Room's Gradle
plugin, `room-paging`, RxJava, Guava, Paging, and WorkManager remain unapproved.
Schema export uses the KSP compiler option directly, and transactions use
Room-generated DAO transaction methods.

Reviewed runtime transitives are AndroidX Room common/runtime, SQLite framework,
SQLite, annotation, collection, arch-core runtime, Kotlin stdlib/coroutines,
AtomicFU, and JSpecify. They are source-compatible FOSS artifacts from the
already approved Google/Maven Central repositories. Room uses Android's SQLite
framework and introduces no packaged native database engine, remote client,
permission, provider, receiver, or initializer. Its AAR declares
`MultiInstanceInvalidationService` as non-exported; AuroraSMS does not enable
multi-process invalidation and removes this unused service during app manifest
merge.

The Room compiler and KSP graphs are build-only. They include the Room compiler
processing/ANTLR tools, KotlinPoet/JavaPoet, Auto Common/annotations,
commons-codec, and a host SQLite JDBC verifier. The host verifier may contain
build-machine native binaries but no processor or native binary enters an app
runtime configuration or APK. `room-testing` and its transitives remain in the
Android-test graph only.

Data behavior is local and app-private: Room accesses only the two AuroraSMS
database files, performs no network I/O, logs no message/search content, and
adds no permission. Both databases remain covered by `allowBackup=false` and
the existing data-extraction exclusions. The index is explicitly rebuildable;
the state database has no destructive-migration fallback.

Expected size impact is the Room/SQLite-framework Java/Kotlin runtime only and
must be measured against the Phase 1 release APK. A growth above the existing
five-percent budget requires explanation before the Phase 2 gate. Removal means
replacing the generated databases/DAOs with an independently validated local
SQLite layer plus explicit schema/migration/FTS tests; the on-disk schema and
exported JSON remain the compatibility contract.

Admission remains conditional on resolved lock/checksum review, license report,
SBOM, release-runtime leakage checks, merged-manifest inspection, and APK
permission/content scans. An unexpected transitive or component reopens this
decision before merge.

## Exact Phase 3 benchmark and profile admission

Phase 3 adds release-equivalent journey evidence and a checked-in Baseline
Profile without applying the Baseline Profile Gradle plugin. The exact direct
additions are:

| Coordinate or plugin | Version | Scope | License | Approved purpose |
|---|---:|---|---|---|
| `com.android.test` | 9.2.1 | Build/test module only | Apache-2.0 | Builds the separate self-instrumenting APK that controls `:app`; never packaged in the product |
| `androidx.benchmark:benchmark-macro-junit4` | 1.4.1 | `:macrobenchmark` only | Apache-2.0 | Physical-device Macrobenchmark metrics and manual `BaselineProfileRule` capture |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | App runtime | Apache-2.0 | Supported profile capture/reset bridge and release Baseline Profile installation |

Canonical upstream and source are the AndroidX Benchmark and ProfileInstaller
release pages at `developer.android.com/jetpack/androidx/releases/benchmark`
and `developer.android.com/jetpack/androidx/releases/profileinstaller`, with
source in `android.googlesource.com/platform/frameworks/support`. These are the
minimum stable 1.4.1 versions documented together for manual Baseline Profile
generation. Alpha tooling and the Baseline Profile Gradle plugin remain
unapproved.

The locked Macrobenchmark graph adds Benchmark common/macro/traceprocessor,
UiAutomator 2.3.0, AndroidX test rules 1.5.0, tracing/perfetto 1.0.0/1.2.0,
Moshi 1.13.0, Okio 3.9.1, and Wire 5.2.1. All are Apache-2.0-compatible FOSS.
The trace tooling packages `libbenchmarkNative.so` and
`libtracing_perfetto.so` for four test ABIs. Those native files, the Benchmark
classes, UiAutomator, Moshi, Okio, Wire, and `:core:testing` are confined to the
separately installed test/benchmark APKs and are rejected from the app release
runtime.

The macrobenchmark test manifest has an exact audited exception for
`INTERNET` (localhost-only Perfetto trace processing), `QUERY_ALL_PACKAGES`,
legacy external-storage report permissions, and `REORDER_TASKS`. It contains
AndroidX test isolation activities plus a DUMP-protected tracing receiver and
Startup initializer. This exception never applies to the app APK: normal debug
and release manifests retain no network permission, profileable tag, benchmark
authority, or benchmark control permission. Release intentionally retains only
ProfileInstaller's non-exported Startup initializer and DUMP-protected receiver
to install the checked-in profile; debug removes both.

Only the app benchmark target enables the signature-protected synthetic fixture
provider. Its normal performance build is non-debuggable and R8-enabled. The
deterministic update script sets
`auroraBaselineProfileCapture=true` so manual HRF capture uses unobfuscated
method signatures as required by the Android tooling guidance; the final
release remains fully R8-enabled and rewrites the checked-in rules. Generated
profile output is normalized twice and scanned before admission. Because
connected-test cleanup uninstalls the default-SMS target and revokes its role,
the update script uses replace-install plus the self-instrumenting runner and
asserts the owner-granted role/`READ_SMS` state both before and after install.

The connected Pixel uses a MagiskSU release whose post-29 CLI no longer exits
for AndroidX Benchmark 1.4.1's legacy `su root id` capability probe. AuroraSMS
does not grant, revoke, or otherwise mutate device root policy. The device-run
script deterministically changes that single, same-length string in the
separate test APK to `su root<&-`, which closes standard input before MagiskSU
can enter an interactive shell and returns no root identity. It verifies that
the replacement preserves DEX string-table order, repairs the DEX headers,
zip-aligns, and
re-signs it with the same debug certificate as the benchmark target. It then
verifies the exact replacement, DEX integrity, APK signature, certificate
match, and a no-fixture shell preflight before any expensive seed. This forces
AndroidX down its supported non-root shell path and does not modify the app
APK, its code, its profileable surface, or measured journeys. The resolved
official AndroidX artifact, locks, checksums, notices, and SBOM remain
unchanged; the prepared APK is a generated local test artifact only.

ProfileInstaller performs no application network I/O and adds no native code.
The release initializer schedules its bounded installation work off the startup
critical path; its exported receiver is guarded by the platform DUMP permission.
Both are removed from debug, and the benchmark fixture/control surface remains
absent from release. Removing this admission means deleting the macrobenchmark
module, benchmark variant and fixture, update script, checked-in profile,
release/benchmark ProfileInstaller dependencies, and associated manifest
exceptions; product message/index data remains compatible because the benchmark
database is synthetic and disposable.

## Phase 4 AuroraMaterial, active-profile reuse, and scoped-reference constraint

The initial `:core:designsystem` module introduces no new external coordinate
or version. It directly declares the already admitted Kotlin stdlib,
coroutines-android, AndroidX Core, Activity Compose, Lifecycle Runtime,
Lifecycle Runtime Compose, Compose BOM/UI/Foundation/Material 3, and JUnit
coordinates so its otherwise minimal graph resolves to the same reviewed
versions as `:app` and `:feature:conversations`.

The direct alignment declarations prevent Compose's lower transitive minima
from selecting a second AndroidX Core, Lifecycle, SavedState, tracing, or
coroutine graph. The generated `core/designsystem/gradle.lockfile` contains
only artifacts already represented in the admitted aggregate graph;
`gradle/verification-metadata.xml` requires no new checksum. The module adds no
manifest component, initializer, permission, native binary, provider, receiver,
service, or network behavior.

The bounded durable active-profile slice migrates the existing Aurora state
Room database from schema version 1 to version 2 and uses the already admitted
Room runtime, Room testing, and KSP compiler coordinates. Its app-owned Theme
Studio uses the existing Compose graph. A schema migration, DAO, repository,
and UI destination do not add a third-party coordinate, repository, checksum,
production manifest component, permission, initializer, native binary, or
network path. DataStore is not added and does not share ownership of appearance
state.

The implemented durable scoped-profile-reference foundation migrates that same
state database from version 2 to version 3, exposes target-specific Room flows,
adds a durable monotonic assignment-revision singleton, and adds an app-owned
Compose modal. It reuses the exact admitted Room/KSP, Kotlin, coroutine, and
Compose coordinates. Its versioned participant-set fingerprint uses platform
`java.security.MessageDigest` SHA-256 and adds no cryptography, normalization,
or hashing dependency. No DataStore owner, Navigation Compose artifact,
media/image/GIF library, picker helper, or other coordinate is admitted by ADR
0006.

The scoped modal's Android test uses Espresso's root-level Back action. The app
therefore declares `androidx.test.espresso:espresso-core:3.5.0` directly in
`androidTestImplementation`, using a version-catalog alias. The exact artifact,
version, and transitives were already present in the resolved Android-test
runtime through the admitted Compose test graph; the lockfile changes only add
those existing artifacts to the Android-test compile classpath. This declaration
adds no resolved artifact/version, repository, checksum, license/SBOM component,
production coordinate, runtime class, manifest component, or APK content. The
final dependency, lock, license/SBOM, manifest, and APK-content gates passed.

Instrumentation uses two non-exported Activities in the debug source set: the
existing Theme Studio host and the scoped-assignment modal host. They exercise
synthetic Compose state without acquiring the default-SMS role, add no
coordinate, and must be absent from release and benchmark manifests/APKs. The
slice adds no production Activity, navigation destination, or component
dependency.

ADR 0007 authorizes no new dependency. Its landed static implementation reuses
the admitted Activity Result/Activity Compose, Compose, coroutine, and
Room graph plus Android platform `BitmapFactory`, `ImageDecoder`, color-space,
WebP-compression and file APIs, `java.security.MessageDigest`, and a small
original parser that reads only bounded JPEG APP1 and PNG `eXIf` TIFF
orientation fields. It has no `android.media.ExifInterface` dependency. The
bounded importer, content-addressed private-file store, and static renderer
introduce no external coordinate, repository, transitive graph, native binary,
manifest component, permission, initializer, or network fetcher.

The ADR 0007 platform path accepts only JPEG/static-PNG source and creates its
own static managed WebP. It is not approval for a general image pipeline,
animated input, GIF playback, a picker helper, a Photo Picker backport-install
component, or a media SDK. External image/GIF loading, Navigation Compose,
DataStore, icon packs, fonts, and remote theme/media SDKs remain unapproved.
Artwork remains outside dependency policy and behind the separate rights gate
in `docs/ARTWORK_CATALOG.md`.

## Deferred and decision-gated dependencies

| Capability | Earliest phase | Decision gate |
|---|---:|---|
| MMS PDU compose/parse | 1 | Write an ADR comparing an original implementation, official Android platform/framework PDU material that is not sourced from an end-user messaging app, and a maintained permissive library; audit provenance, license, network/APN assumptions, and transitive graph |
| Phone-number normalization | 1 | Prefer platform APIs; approve libphonenumber only if measured correctness needs justify its size |
| Room | 2 | Approved only as recorded above for separate index/state databases, FTS4, schema export, and explicit migrations |
| Paging 3 | 2-3 | Confirm bounded keyset/anchor behavior and no deep OFFSET fallback |
| DataStore | Unscheduled | Admit only if a measured ADR proves a lightweight-preference need that the Aurora state database should not own; it must never duplicate named-profile, active-selection, override, draft, message, or index ownership |
| External image/GIF decoding library | 4 | ADR 0007 does not admit one; any later need must prove one-visible-decoder lifecycle, bomb limits, no network fetcher, APK size, API 26 behavior, and why the bounded platform path is insufficient |
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
