#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

root_version="$(sed -nE 's/^version = "([^"]+)"$/\1/p' build.gradle.kts)"
if [[ -z "$root_version" ]]; then
    printf 'Could not read the root project version.\n' >&2
    exit 1
fi

if ! grep -Fqx '        versionName = rootProject.version.toString()' app/build.gradle.kts; then
    printf 'The app versionName must use the root project version.\n' >&2
    exit 1
fi

required_files=(
    LICENSE
    LICENSE_POLICY.md
    THIRD_PARTY_NOTICES.md
    SECURITY.md
    docs/ARTWORK_CATALOG.md
    docs/PHASE_7_RELEASE_PLAN.md
    docs/RELEASE_PROCESS.md
    fastlane/metadata/android/en-US/title.txt
    fastlane/metadata/android/en-US/short_description.txt
    fastlane/metadata/android/en-US/full_description.txt
    metadata/org.aurorasms.app.yml
)
for required in "${required_files[@]}"; do
    if [[ ! -s "$required" ]]; then
        printf 'Required release metadata is missing or empty: %s\n' "$required" >&2
        exit 1
    fi
done

title_length="$(wc -m < fastlane/metadata/android/en-US/title.txt)"
short_length="$(wc -m < fastlane/metadata/android/en-US/short_description.txt)"
full_length="$(wc -m < fastlane/metadata/android/en-US/full_description.txt)"
if ((title_length > 51)); then
    printf 'Localized title exceeds 50 characters.\n' >&2
    exit 1
fi
if ((short_length > 81)); then
    printf 'Localized short description exceeds 80 characters.\n' >&2
    exit 1
fi
if ((full_length > 4001)); then
    printf 'Localized full description exceeds 4000 characters.\n' >&2
    exit 1
fi

for expected in \
    'License: GPL-3.0-or-later' \
    'RepoType: git' \
    'Repo: https://github.com/LaAutista/AuroraSMS.git' \
    'Disabled:'; do
    if ! grep -Fq "$expected" metadata/org.aurorasms.app.yml; then
        printf 'F-Droid metadata is missing: %s\n' "$expected" >&2
        exit 1
    fi
done

for limitation in \
    'bounded synthetic incoming and general/group outgoing MMS paths are implemented' \
    'physical carrier/OEM acceptance is not complete' \
    'New chat/first-contact' \
    'still a pre-release'; do
    if ! grep -Fiq "$limitation" fastlane/metadata/android/en-US/full_description.txt; then
        printf 'Store copy is missing current limitation: %s\n' "$limitation" >&2
        exit 1
    fi
done

bash -n scripts/generate-release-checksums.sh
bash -n scripts/verify-reproducible-release.sh

printf 'Release metadata verification passed (version %s).\n' "$root_version"
