<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AuroraSMS release process

AuroraSMS has no gold release yet. These instructions define the reproducible
and signing path; they do not authorize publishing a release or crossing a
physical SMS/MMS carrier boundary.

## Pinned environment

- a clean checkout of the exact release commit;
- JDK 17;
- the repository Gradle 9.4.1 wrapper and its pinned distribution checksum;
- Android SDK platform 37 and Build Tools 36.0.0; and
- the dependency locks and verification metadata committed with that source.

Set `ANDROID_SDK_ROOT` or provide an untracked `local.properties`. Never commit
an SDK path, keystore, key password, or signing configuration.

## Verify the source

Run the same governed aggregate used by CI:

```shell
./gradlew --no-daemon --no-parallel --console=plain \
  verifyCleanRoom verifyPrivateAssets verifyDependencies verifyReleaseMetadata \
  test lintDebug lintRelease assembleDebug assembleRelease \
  :app:lintBenchmark :app:assembleBenchmark \
  :macrobenchmark:check :macrobenchmark:assembleBenchmark \
  verifyPermissions verifyApkContents checkLicense generateLicenseReport
./gradlew --no-daemon --no-parallel --console=plain :app:bundleRelease
./gradlew --no-daemon --no-parallel --console=plain cyclonedxBom
```

Then run all connected and owner-approved physical rows listed in
`TEST_MATRIX.md`. A green host build is necessary but cannot substitute for the
carrier, OEM, accessibility, or complete-history gates.

## Verify reproducibility

Commit all intended source changes and start from a clean worktree. The helper
builds the same `HEAD` twice in independent temporary source trees and compares
the unsigned release APK and AAB byte for byte:

```shell
./scripts/verify-reproducible-release.sh
```

Use `--offline` only when the pinned dependency graph and Android toolchain are
already available locally. Record the printed hashes with the release evidence.

## Sign outside the repository

The normal release build intentionally produces
`app-release-unsigned.apk`. The release owner signs from a restricted machine
or signing service with a long-lived key that never enters Git or CI logs. A
typical local command is:

```shell
apksigner sign --ks "$AURORASMS_KEYSTORE" \
  --out AuroraSMS-signed.apk app-release-unsigned.apk
apksigner verify --verbose --print-certs AuroraSMS-signed.apk
```

Do not create, rotate, recover, or upload the production signing key as an
automated repository step. F-Droid will use its own signing identity unless a
separate reproducible-build/signature-copying arrangement is approved.

## Checksums and publication

Stage the exact assets that will be published and generate one deterministic
checksum manifest alongside them:

```shell
./scripts/generate-release-checksums.sh --output dist \
  AuroraSMS-signed.apk app-release.aab build/reports/bom.json \
  THIRD_PARTY_NOTICES.md LICENSE
gpg --armor --detach-sign dist/SHA256SUMS
gpg --verify dist/SHA256SUMS.asc dist/SHA256SUMS
```

The GPG identity must be documented through a separately verified fingerprint.
Uploading artifacts, creating a GitHub release, pushing a release tag, or
submitting F-Droid metadata happens only after the gold gate and owner review.

The source-local `metadata/org.aurorasms.app.yml` remains disabled while the
application is pre-release. At the frozen release commit, replace that boundary
with a `Builds` entry containing the exact full commit hash and verify it with
the current F-Droid tooling before proposing it to `fdroiddata`.

Primary references:

- <https://f-droid.org/en/docs/Build_Metadata_Reference/>
- <https://f-droid.org/en/docs/Reproducible_Builds/>
- <https://developer.android.com/tools/apksigner>
