<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Dependency license report configuration

`allowed-licenses.json` accepts only the GPL-compatible license families listed
in `docs/DEPENDENCY_POLICY.md`. Some Android AAR and Kotlin module metadata does
not publish a license element that the pinned report plugin can read. Narrow
empty-license overrides therefore cover only the reviewed upstream namespaces
and coordinates used in the locked graph:

- AndroidX artifacts are Apache-2.0;
- Kotlin, kotlinx, and JetBrains annotations artifacts are Apache-2.0;
- `javax.inject` is Apache-2.0;
- JUnit 4 is EPL-1.0;
- Hamcrest and JSR-305 use approved BSD-family terms.

These overrides do not admit an artifact into the build. Repository filtering,
exact catalog pins, strict lockfiles, SHA-256 dependency verification, and the
resolved-component inventory remain independent gates. Any new coordinate or
lock change still requires the provenance review in the dependency policy.

The license report imports no remote data. `license-normalization.json` is the
only normalization bundle, and generated reports stay under
`build/reports/dependency-license/`.
