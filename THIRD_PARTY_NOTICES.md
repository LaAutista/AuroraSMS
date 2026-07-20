# Third-party notices

Status: Phase 1 resolved inventory; Phase 2 Room/KSP and Phase 3 performance
tool additions admitted; Phase 4 foundation adds no new coordinate; Phase 6F
admits one pinned official-AOSP outgoing composer source subset; Phase 7D
extends that exact-revision subset with the bounded incoming parser closure

AuroraSMS uses independently selected, exact-version build, AndroidX, Kotlin,
Compose, coroutine, and test dependencies admitted by
`docs/DEPENDENCY_POLICY.md`. Dependency lockfiles and
`gradle/verification-metadata.xml` bind the resolved graph and artifact
checksums.

The generated license inventory is the authoritative per-artifact notice:

- `build/reports/dependency-license/licenses.json`;
- `build/reports/dependency-license/index.html`.

The generated CycloneDX 1.6 software bills of materials are:

- `build/reports/bom.json`;
- `build/reports/bom.xml`.

Regenerate and validate these artifacts with:

```text
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline
```

Android SDK, AndroidX, Kotlin, Gradle, test tools, and build plugins are not
approved merely by appearing in a toolchain. Only the exact allowlisted and
locked graph may resolve. Build/test-only dependencies are inventoried even
when they are not packaged in the application.

Phase 2 directly adds KSP 2.3.9 and AndroidX Room 2.8.4 runtime, compiler, and
testing artifacts. KSP and the Room compiler are build-only; Room testing is
Android-test-only. The packaged Room runtime and its AndroidX/Kotlin transitives
are Apache-2.0-compatible FOSS, use Android's local SQLite framework, add no
network permission, and are used only for the separate private Aurora index and
state databases. Canonical notices and the exact transitive inventory remain in
the generated reports above.

Phase 3 directly adds AndroidX Benchmark Macro JUnit4 1.4.1 to the separate
test APK and AndroidX ProfileInstaller 1.4.1 to the app. The locked test graph
also contains AndroidX Benchmark/traceprocessor/UiAutomator/tracing components,
Square Moshi 1.13.0, Okio 3.9.1, and Wire 5.2.1 under Apache-2.0-compatible
terms. Perfetto and Benchmark native binaries are present only in the
macrobenchmark test APK. The generated inventory and SBOM provide the canonical
per-artifact notices; no benchmark/test native binary enters a production app
APK.

The Phase 4 AuroraMaterial foundation adds the `:core:designsystem` project
module but no third-party coordinate or version. It reuses only the exact
Kotlin, coroutine, AndroidX Core/Activity/Lifecycle, Compose, and JUnit graph
already inventoried above. Its strict lockfile aligns to the existing verified
graph and does not require a new dependency-verification checksum.

The private AuroraSMS handoff, private screenshots, original source artwork,
and all private high-resolution working masters are not distributed repository
content.
The owner-selected launcher derivative and launcher XML layers are first-party
AuroraSMS artwork offered under GPL-3.0-or-later and recorded in
`docs/ARTWORK_CATALOG.md`. They contain no letter or monogram marks. Other
artwork may enter the repository only after its per-asset owner approval,
license boundary, and attribution are recorded there.

## Android Open Source Project MMS codec subset

Phase 6F vendors twelve Java source files from the official AOSP
`platform/frameworks/opt/mms` repository at immutable revision
`4bfcd8501f09763c10255442c2b48fad0c796baa`. The files retain the copyright
notices of Esmertec AG and The Android Open Source Project and are licensed under
Apache-2.0. The complete license text and exact file/modification inventory are
in `third_party/aosp-mms/`.

AuroraSMS repackages this selected outgoing `SendReq` composition graph and
changes malformed first-part handling to fail closed without printing. It does
not include the AOSP APN/network client, persister, transaction service,
database, UI, or end-user messaging application. ADR 0021 limits the admitted
outgoing runtime use to one bounded one-person SMIL/text/AAC voice-memo PDU.

Phase 7D adds twelve files from the same immutable revision: `ContentType`, the
`PduParser`, notification/retrieval PDU models, the parser's remaining typed-PDU
closure, and its Base64/quoted-printable helpers. The parser is modified to use
defensive input copies, explicit byte/part/header/string/depth limits, checked
lengths and end-of-input reads, instance-local parameter state, deterministic
anonymous-part identifiers, and content-free logging behavior. ADR 0024 admits
only notification and retrieved-message results through an original,
GPL-3.0-or-later typed validation wrapper. Other parsed PDU types are rejected.
The wrapper, provider/journal policy, UI, and tests remain original
GPL-3.0-or-later code.

## Tux

Tux was created by Larry Ewing using The GIMP. Larry Ewing permits use and
modification of Tux when he and The GIMP are acknowledged:

- <https://isc.tamu.edu/~lewing/linux/>

The Linux Foundation confirms that it does not own Tux and identifies Larry
Ewing as its creator:

- <https://www.linuxfoundation.org/legal/the-linux-mark>

No original Tux bitmap is currently packaged in AuroraSMS. If a future
owner-approved wallpaper contains a recognizable Tux rather than a generic
penguin, preserve this notice with the distributed artwork. Use of the word
Linux as a trademark is a separate issue from the Tux artwork permission.
