#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DENYLIST="$ROOT/config/clean-room/private-reference-sha256.txt"
PRIVATE_ROOT_NAME='AuroraSMS_Codex_Handoff_PRIVATE'
APKS=("$@")

if ((${#APKS[@]} == 0)); then
    while IFS= read -r -d '' apk; do
        APKS+=("$apk")
    done < <(find "$ROOT/app/build/outputs/apk" -type f -name '*.apk' -print0 2>/dev/null | sort -z)
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

for apk in "${APKS[@]}"; do
    if [[ ! -f "$apk" ]]; then
        printf 'APK not found: %s\n' "$apk" >&2
        exit 1
    fi

    entry_list="$(mktemp)"
    trap 'rm -f "$entry_list"' EXIT
    unzip -Z1 "$apk" >"$entry_list"

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
        if unzip -p "$apk" | strings | rg --fixed-strings -- "$token"; then
            printf 'Prohibited token found in APK %s: %s\n' "$apk" "$token" >&2
            exit 1
        fi
    done

    if [[ "$(basename "$apk")" == *release* ]]; then
        for marker in \
            'org/aurorasms/core/testing' \
            'DiagnosticsRoute' \
            'DiagnosticsScreen' \
            'DiagnosticsViewModel' \
            'SyntheticPeople' \
            'SyntheticMessages'; do
            if unzip -p "$apk" | strings | rg --fixed-strings -- "$marker"; then
                printf 'Debug/testing marker found in release APK %s: %s\n' \
                    "$apk" "$marker" >&2
                exit 1
            fi
        done
    fi

    rm -f "$entry_list"
    trap - EXIT
    printf 'APK-content verification passed: %s\n' "$apk"
done
