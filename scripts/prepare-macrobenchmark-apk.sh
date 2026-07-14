#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
SOURCE_APK="${1:-$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk}"
OUTPUT_APK="${2:-$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark-nonroot.apk}"
TARGET_APK="$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
KEYSTORE="${AURORA_ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
BUILD_TOOLS_VERSION="36.0.0"

if [[ $# -gt 2 ]]; then
    printf 'Usage: %s [source-apk [output-apk]]\n' "$0" >&2
    exit 2
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | tail -1 | sed 's/\\:/:/g; s/\\ / /g')"
fi
ZIPALIGN="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign"
APKSIGNER="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"

for required in "$SOURCE_APK" "$TARGET_APK" "$KEYSTORE" "$ZIPALIGN" "$APKSIGNER"; do
    if [[ ! -f "$required" ]]; then
        printf 'Required benchmark preparation input is missing: %s\n' "$required" >&2
        exit 1
    fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
UNALIGNED="$WORK/macrobenchmark-unsigned.apk"
ALIGNED="$WORK/macrobenchmark-aligned.apk"

python3 "$ROOT/scripts/patch-benchmark-apk.py" patch "$SOURCE_APK" "$UNALIGNED"
"$ZIPALIGN" -f -p 4 "$UNALIGNED" "$ALIGNED"
mkdir -p "$(dirname "$OUTPUT_APK")"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --v4-signing-enabled false \
    --out "$OUTPUT_APK" \
    "$ALIGNED"
"$ZIPALIGN" -c -p 4 "$OUTPUT_APK"
"$APKSIGNER" verify --verbose "$OUTPUT_APK" >/dev/null
python3 "$ROOT/scripts/patch-benchmark-apk.py" verify "$OUTPUT_APK"

target_certificate="$($APKSIGNER verify --print-certs "$TARGET_APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
test_certificate="$($APKSIGNER verify --print-certs "$OUTPUT_APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ -z "$target_certificate" || "$target_certificate" != "$test_certificate" ]]; then
    printf 'Prepared test APK certificate does not match the benchmark target.\n' >&2
    exit 1
fi

printf 'Prepared non-root macrobenchmark APK: %s (SHA-256 %s)\n' \
    "$OUTPUT_APK" \
    "$(sha256sum "$OUTPUT_APK" | awk '{print $1}')"
