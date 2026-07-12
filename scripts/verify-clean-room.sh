#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DENYLIST="$ROOT/config/clean-room/private-reference-sha256.txt"
HASH_ONLY=false

if [[ "${1:-}" == "--hash-only" ]]; then
    HASH_ONLY=true
elif [[ $# -ne 0 ]]; then
    printf 'Usage: %s [--hash-only]\n' "$0" >&2
    exit 2
fi

if [[ ! -f "$DENYLIST" ]]; then
    printf 'Missing private-reference denylist: %s\n' "$DENYLIST" >&2
    exit 1
fi

mapfile -t PRIVATE_HASHES < <(
    sed -nE 's/^([[:xdigit:]]{64})$/\L\1/p' "$DENYLIST" | sort -u
)

if [[ ${#PRIVATE_HASHES[@]} -ne 19 ]]; then
    printf 'Expected exactly 19 unique private-reference hashes; found %d.\n' \
        "${#PRIVATE_HASHES[@]}" >&2
    exit 1
fi

declare -A PRIVATE_HASH_SET=()
for hash in "${PRIVATE_HASHES[@]}"; do
    PRIVATE_HASH_SET["$hash"]=1
done

PRIVATE_ROOT_NAME='AuroraSMS_Codex_Handoff_PRIVATE'
while IFS= read -r -d '' tracked; do
    if [[ "$tracked" == *"$PRIVATE_ROOT_NAME"* ]]; then
        printf 'Private handoff path is tracked by Git: %s\n' "$tracked" >&2
        exit 1
    fi
done < <(git -C "$ROOT" ls-files -z)

if [[ "$HASH_ONLY" == false ]]; then
    TOKENS=(
        'org.fossify'
        'FossifyOrg'
        'org.simplemobiletools'
    )
    SCAN_ROOTS=()
    for relative in app core feature benchmark build-logic buildSrc; do
        if [[ -e "$ROOT/$relative" ]]; then
            SCAN_ROOTS+=("$ROOT/$relative")
        fi
    done

    for token in "${TOKENS[@]}"; do
        if ((${#SCAN_ROOTS[@]} > 0)) && \
            rg --hidden --glob '!**/build/**' --fixed-strings --line-number \
                -- "$token" "${SCAN_ROOTS[@]}"; then
            printf 'Clean-room token found in implementation roots: %s\n' "$token" >&2
            exit 1
        fi
    done
fi

while IFS= read -r -d '' candidate; do
    candidate_hash="$(sha256sum "$candidate" | awk '{print tolower($1)}')"
    if [[ -n "${PRIVATE_HASH_SET[$candidate_hash]:-}" ]]; then
        printf 'Repository file matches a private visual reference: %s\n' \
            "${candidate#"$ROOT"/}" >&2
        exit 1
    fi
done < <(
    find "$ROOT" \
        -type d \( \
            -name .git -o \
            -name .gradle -o \
            -name build -o \
            -name "$PRIVATE_ROOT_NAME" \
        \) -prune -o \
        -type f -print0
)

printf 'Clean-room verification passed (%d private hashes checked).\n' \
    "${#PRIVATE_HASHES[@]}"
