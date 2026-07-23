#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail
export LC_ALL=C
export TZ=UTC

ROOT="$(git rev-parse --show-toplevel)"
if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
    printf 'Reproducibility verification requires a clean committed worktree.\n' >&2
    exit 1
fi

offline=false
if [[ "${1:-}" == '--offline' ]]; then
    offline=true
    shift
fi
if [[ $# -ne 0 ]]; then
    printf 'Usage: %s [--offline]\n' "$0" >&2
    exit 2
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" && -f "$ROOT/local.properties" ]]; then
    sdk_root="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | tail -1 | sed 's/\\:/:/g; s/\\ / /g')"
fi
if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
    printf 'Set ANDROID_SDK_ROOT or provide an untracked local.properties.\n' >&2
    exit 1
fi

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/aurorasms-repro.XXXXXX")"
trap 'rm -rf "$temporary_root"' EXIT
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
commit="$(git -C "$ROOT" rev-parse HEAD)"
gradle_args=(
    --no-daemon
    --no-parallel
    --console=plain
    :app:assembleRelease
    :app:bundleRelease
)
if [[ "$offline" == true ]]; then
    gradle_args=(--offline "${gradle_args[@]}")
fi

for pass in 1 2; do
    tree="$temporary_root/source-$pass"
    git clone --quiet --no-local "$ROOT" "$tree"
    git -C "$tree" checkout --quiet --detach "$commit"
    (
        cd "$tree"
        ANDROID_SDK_ROOT="$sdk_root" \
            GRADLE_USER_HOME="$gradle_home" \
            ./gradlew "${gradle_args[@]}"
    )
done

artifacts=(
    app/build/outputs/apk/release/app-release-unsigned.apk
    app/build/outputs/bundle/release/app-release.aab
)
for artifact in "${artifacts[@]}"; do
    first="$temporary_root/source-1/$artifact"
    second="$temporary_root/source-2/$artifact"
    if ! cmp -s "$first" "$second"; then
        printf 'Reproducibility mismatch: %s\n' "$artifact" >&2
        sha256sum "$first" "$second" >&2
        exit 1
    fi
    sha256sum "$first" | sed "s#$temporary_root/source-1/##"
done

printf 'Reproducible release verification passed for commit %s.\n' \
    "$commit"
