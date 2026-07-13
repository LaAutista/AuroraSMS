<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# AuroraSMS

AuroraSMS is an original, clean-room Android SMS/MMS application. Its privacy
baseline is local-only: the application does not request `INTERNET` or
`ACCESS_NETWORK_STATE`, has no accounts, ads, analytics, or trackers, and uses
Android's Telephony provider as the authority for messages.

## Current status

Phase 1 established the independently implemented default-SMS foundation:

- role eligibility and role-before-permission onboarding;
- the complete Android SMS-role manifest surface;
- bounded SMS provider and subscription-specific transport adapters;
- group-recipient policy that selects one MMS operation and never fans out SMS;
- privacy-aware message notifications and validated inline reply;
- debug-only, redacted local diagnostics;
- strict dependency locks, checksums, license inventory, SBOM, and clean-room,
  permission, and APK-content gates.

Phase 2 added the complete-history local data and search foundation:

- separate private Room v1 databases for the rebuildable message index and
  durable Aurora-owned draft state;
- bounded, newest-first SMS/MMS metadata projection with durable checkpoints,
  verified reconciliation, and truthful partial coverage;
- safe FTS4 global and in-thread keyset search plus bounded exact-result
  anchors; and
- controlled index-only corruption recovery, typed storage failures, redacted
  diagnostics, and deterministic synthetic scale benchmarks.

Phase 2 does not add the final inbox, thread, search, or composer presentation
and does not change the existing carrier MMS limitations. Physical evidence
currently covers a Pixel 8 on Android 16/API 36; the remaining API/OEM and
carrier rows stay pending.

End-to-end MMS is not yet claimed. Platform MMS staging and result handling are
present, but encoding/decoding remains disabled until an independently audited
codec is admitted and verified on physical carrier hardware. The first
composer also keeps its send control disabled until the complete, durable
compose flow is integrated. See `docs/adr/0001-mms-pdu-strategy.md` and the
phase gates in `docs/TEST_MATRIX.md`.

## Build

Prerequisites are JDK 17 or newer and an Android SDK containing platform 37
(installed here as `platforms;android-37.0`). Set `sdk.dir` in an ignored
`local.properties` file or export `ANDROID_SDK_ROOT`, then use only the checked
wrapper:

```bash
./gradlew assembleDebug --offline
```

The debug APK is produced at
`app/build/outputs/apk/debug/app-debug.apk`.

## Verify

```bash
./gradlew test lintDebug lintRelease assembleDebug assembleRelease --offline
./gradlew connectedDebugAndroidTest --offline
./gradlew verifyGovernance --offline
./gradlew --no-parallel checkLicense generateLicenseReport cyclonedxBom --offline
```

Generated dependency reports are placed under `build/reports/`. Device and
carrier tests require a telephony-capable Android device, explicit approval of
the destination number, and awareness that SMS/MMS charges may apply. Tests
must never infer a destination from private reference material.

## Clean-room and licensing

Read `CLEAN_ROOM.md` before contributing. Private screenshots, PDFs, handoff
materials, and reference-app code or resources must not enter the repository,
tests, reports, or APK. Original source is licensed under
GPL-3.0-or-later; artwork has a separate rights gate described in
`docs/ARTWORK_CATALOG.md`.
