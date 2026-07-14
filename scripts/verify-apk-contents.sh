#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DENYLIST="$ROOT/config/clean-room/private-reference-sha256.txt"
PRIVATE_ROOT_NAME='AuroraSMS_Codex_Handoff_PRIVATE'
APKS=("$@")

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | tail -1 | sed 's/\\:/:/g; s/\\ / /g')"
fi
AAPT2=''
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    AAPT2="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
fi

if ((${#APKS[@]} == 0)); then
    for apk_root in \
        "$ROOT/app/build/outputs/apk/debug" \
        "$ROOT/app/build/outputs/apk/release" \
        "$ROOT/app/build/outputs/apk/benchmark" \
        "$ROOT/macrobenchmark/build/outputs/apk/benchmark"; do
        [[ -d "$apk_root" ]] || continue
        while IFS= read -r -d '' apk; do
            APKS+=("$apk")
        done < <(find "$apk_root" -type f -name '*.apk' -print0 2>/dev/null | sort -z)
    done
fi

if [[ -z "$AAPT2" ]]; then
    printf 'Built APKs exist, but aapt2 was not found under the configured Android SDK.\n' >&2
    exit 1
fi

if ((${#APKS[@]} == 0)); then
    printf 'No APK outputs found; APK-content verification deferred until assembly.\n'
    exit 0
fi

mapfile -t PRIVATE_HASHES < <(sed -nE 's/^([[:xdigit:]]{64})$/\L\1/p' "$DENYLIST" | sort -u)
declare -A PRIVATE_HASH_SET=()
for hash in "${PRIVATE_HASHES[@]}"; do
    PRIVATE_HASH_SET["$hash"]=1
done

classify_apk() {
    local canonical="$1"
    case "$canonical" in
        "$ROOT/app/build/outputs/apk/debug/"*.apk) printf 'app_debug\n' ;;
        "$ROOT/app/build/outputs/apk/release/"*.apk) printf 'app_release\n' ;;
        "$ROOT/app/build/outputs/apk/benchmark/"*.apk) printf 'app_benchmark\n' ;;
        "$ROOT/macrobenchmark/build/outputs/apk/benchmark/"*.apk) printf 'macro_benchmark\n' ;;
        *)
            printf 'Unrecognized Gradle APK build identity: %s\n' "$canonical" >&2
            return 1
            ;;
    esac
}

for apk in "${APKS[@]}"; do
    if [[ ! -f "$apk" ]]; then
        printf 'APK not found: %s\n' "$apk" >&2
        exit 1
    fi

    canonical_apk="$(realpath "$apk")"
    build_identity="$(classify_apk "$canonical_apk")"
    entry_list="$(mktemp)"
    strings_file="$(mktemp)"
    manifest_dump="$(mktemp)"
    trap 'rm -f "$entry_list" "$strings_file" "$manifest_dump"' EXIT
    unzip -Z1 "$apk" >"$entry_list"
    unzip -p "$apk" | strings >"$strings_file"
    "$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml >"$manifest_dump"

    badging="$($AAPT2 dump badging "$apk")"
    package_name="$(sed -nE "s/^package: name='([^']+)'.*/\\1/p" <<<"$badging" | head -1)"
    case "$build_identity" in
        app_debug|app_release|app_benchmark)
            [[ "$package_name" == 'org.aurorasms.app' ]] || {
                printf 'Unexpected app package in %s: %s\n' "$apk" "$package_name" >&2
                exit 1
            }
            ;;
        macro_benchmark)
            [[ "$package_name" == 'org.aurorasms.macrobenchmark' ]] || {
                printf 'Unexpected macrobenchmark package in %s: %s\n' "$apk" "$package_name" >&2
                exit 1
            }
            ;;
    esac
    if [[ "$build_identity" == app_release || "$build_identity" == app_benchmark ]]; then
        if rg -q '^application-debuggable' <<<"$badging"; then
            printf 'Release-equivalent APK is debuggable: %s\n' "$apk" >&2
            exit 1
        fi
    elif ! rg -q '^application-debuggable' <<<"$badging"; then
        printf 'Debug/test APK is unexpectedly non-debuggable: %s\n' "$apk" >&2
        exit 1
    fi

    if rg --fixed-strings -- "$PRIVATE_ROOT_NAME" "$entry_list"; then
        printf 'Private handoff path found in APK entry names: %s\n' "$apk" >&2
        exit 1
    fi

    while IFS= read -r entry; do
        [[ -z "$entry" || "$entry" == */ ]] && continue
        entry_hash="$(unzip -p "$apk" "$entry" | sha256sum | awk '{print tolower($1)}')"
        if [[ -n "${PRIVATE_HASH_SET[$entry_hash]:-}" ]]; then
            printf 'APK entry matches a private visual reference: %s!/%s\n' \
                "$apk" "$entry" >&2
            exit 1
        fi
    done <"$entry_list"

    for token in org.fossify FossifyOrg org.simplemobiletools; do
        if rg --fixed-strings -- "$token" "$strings_file" "$manifest_dump"; then
            printf 'Prohibited token found in APK %s: %s\n' "$apk" "$token" >&2
            exit 1
        fi
    done

    if [[ "$build_identity" == app_debug || "$build_identity" == app_release ]]; then
        for marker in \
            'org.aurorasms.app.benchmark.fixture' \
            'org.aurorasms.app.permission.BENCHMARK_CONTROL' \
            'BenchmarkFixtureProvider' \
            'SyntheticIndexFixtures' \
            'inbox_20k' \
            'search_500k' \
            'thread_250k'; do
            if rg --fixed-strings -- "$marker" "$strings_file" "$manifest_dump"; then
                printf 'Benchmark fixture marker leaked into %s APK %s: %s\n' \
                    "$build_identity" "$apk" "$marker" >&2
                exit 1
            fi
        done
    fi

    if [[ "$build_identity" == app_release ]]; then
        for marker in \
            'org/aurorasms/core/testing' \
            'org.aurorasms.core.testing' \
            'DiagnosticsRoute' \
            'DiagnosticsScreen' \
            'DiagnosticsViewModel' \
            'SyntheticPeople' \
            'SyntheticMessages'; do
            if rg --fixed-strings -- "$marker" "$strings_file" "$manifest_dump"; then
                printf 'Debug/testing marker found in release APK %s: %s\n' \
                    "$apk" "$marker" >&2
                exit 1
            fi
        done
        for entry in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
            if ! rg --fixed-strings --line-regexp -- "$entry" "$entry_list"; then
                printf 'Compiled Baseline Profile asset is missing from release APK %s: %s\n' \
                    "$apk" "$entry" >&2
                exit 1
            fi
            if [[ "$(unzip -p "$apk" "$entry" | wc -c)" -le 0 ]]; then
                printf 'Compiled Baseline Profile asset is empty in release APK %s: %s\n' \
                    "$apk" "$entry" >&2
                exit 1
            fi
        done
    fi

    if [[ "$build_identity" == app_benchmark || "$build_identity" == macro_benchmark ]]; then
        for marker in \
            'org.aurorasms.app.benchmark.fixture' \
            'org.aurorasms.app.permission.BENCHMARK_CONTROL' \
            'inbox_20k' \
            'search_500k' \
            'thread_250k'; do
            if ! rg --fixed-strings --quiet -- "$marker" "$strings_file" "$manifest_dump"; then
                printf 'Required synthetic benchmark marker is missing from %s: %s\n' \
                    "$apk" "$marker" >&2
                exit 1
            fi
        done
    fi

    rm -f "$entry_list" "$strings_file" "$manifest_dump"
    trap - EXIT
    printf 'APK-content verification passed (%s): %s\n' "$build_identity" "$apk"
done
