# Third-party notices

Status: Phase 1 resolved inventory; Phase 2 Room/KSP and Phase 3 performance-tool additions admitted

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

The private AuroraSMS handoff, private screenshots, and original source
artwork are not distributed repository content. Artwork ownership
and publication rights remain unresolved; artwork may enter the repository
only after its separate owner-approved license and attribution are recorded in
`docs/ARTWORK_CATALOG.md`.
