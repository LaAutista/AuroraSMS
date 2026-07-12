# Third-party notices

Status: Phase 1 resolved dependency inventory

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

The private AuroraSMS handoff, private screenshots, and original source
artwork are not distributed repository content. Artwork ownership
and publication rights remain unresolved; artwork may enter the repository
only after its separate owner-approved license and attribution are recorded in
`docs/ARTWORK_CATALOG.md`.
