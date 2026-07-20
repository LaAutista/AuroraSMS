#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail
export LC_ALL=C

usage() {
    printf 'Usage: %s [--output DIR] ARTIFACT [ARTIFACT ...]\n' "$0" >&2
}

output_dir='dist'
if [[ "${1:-}" == '--output' ]]; then
    if [[ $# -lt 3 || -z "${2:-}" ]]; then
        usage
        exit 2
    fi
    output_dir="$2"
    shift 2
fi
if [[ $# -eq 0 ]]; then
    usage
    exit 2
fi

mkdir -p "$output_dir"
manifest="$output_dir/SHA256SUMS"
temporary="$(mktemp "$output_dir/.SHA256SUMS.XXXXXX")"
trap 'rm -f "$temporary"' EXIT

declare -A names=()
for artifact in "$@"; do
    if [[ ! -f "$artifact" ]]; then
        printf 'Release artifact not found: %s\n' "$artifact" >&2
        exit 1
    fi
    name="$(basename "$artifact")"
    if [[ -n "${names[$name]:-}" ]]; then
        printf 'Duplicate release artifact basename: %s\n' "$name" >&2
        exit 1
    fi
    staged="$output_dir/$name"
    if [[ "$(realpath "$artifact")" != "$(realpath -m "$staged")" ]]; then
        cp -- "$artifact" "$staged"
    fi
    names["$name"]="$staged"
done

while IFS= read -r name; do
    artifact="${names[$name]}"
    hash="$(sha256sum -- "$artifact" | awk '{print $1}')"
    printf '%s  %s\n' "$hash" "$name" >> "$temporary"
done < <(printf '%s\n' "${!names[@]}" | sort)

mv "$temporary" "$manifest"
trap - EXIT
printf 'Wrote %s for %d artifact(s). Sign this file outside the repository.\n' \
    "$manifest" "$#"
