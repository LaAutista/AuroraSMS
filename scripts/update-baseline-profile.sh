#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s --verify-twice [--device SERIAL] [--allow-emulator-profile]\n' "$0" >&2
}

if [[ "${1:-}" != "--verify-twice" ]]; then
    usage
    exit 2
fi
shift

REQUESTED_SERIAL=""
ALLOW_EMULATOR_PROFILE=false
while (($#)); do
    case "$1" in
        --device)
            if [[ $# -lt 2 || -z "$2" ]]; then
                usage
                exit 2
            fi
            REQUESTED_SERIAL="$2"
            shift 2
            ;;
        --allow-emulator-profile)
            ALLOW_EMULATOR_PROFILE=true
            shift
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

ROOT="$(git rev-parse --show-toplevel)"
DESTINATION="$ROOT/app/src/main/baseline-prof.txt"
APP_PACKAGE="org.aurorasms.app"
TEST_PACKAGE="org.aurorasms.macrobenchmark"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
RAW_TEST_APK="$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TEST_APK="$WORK/macrobenchmark-benchmark-nonroot.apk"
MINIMUM_CAPTURE_AGREEMENT_PERCENT=99

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ -n "$REQUESTED_SERIAL" ]]; then
    if ! printf '%s\n' "${DEVICES[@]}" \
        | rg --fixed-strings --line-regexp -- "$REQUESTED_SERIAL" >/dev/null; then
        printf 'Requested capture device is not authorized: %s\n' "$REQUESTED_SERIAL" >&2
        exit 1
    fi
    DEVICE_SERIAL="$REQUESTED_SERIAL"
elif [[ ${#DEVICES[@]} -ne 1 ]]; then
    printf 'Baseline Profile capture requires exactly one authorized physical device; found %d.\n' \
        "${#DEVICES[@]}" >&2
    exit 1
else
    DEVICE_SERIAL="${DEVICES[0]}"
fi

ADB=(adb -s "$DEVICE_SERIAL")
IS_EMULATOR="$(adb -s "$DEVICE_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$DEVICE_SERIAL" == emulator-* || "$IS_EMULATOR" == "1" ]]; then
    if [[ "$ALLOW_EMULATOR_PROFILE" != true ]]; then
        printf 'Emulator capture requires explicit --allow-emulator-profile: %s\n' \
            "$DEVICE_SERIAL" >&2
        exit 1
    fi
    printf 'Generating a Baseline Profile on emulator %s; no performance claim will be derived.\n' \
        "$DEVICE_SERIAL"
fi

owner_eligible_once() {
    local role_holders package_dump
    role_holders="$("${ADB[@]}" shell cmd role get-role-holders android.app.role.SMS | tr -d '\r')"
    if ! rg --fixed-strings --line-regexp -- "$APP_PACKAGE" <<<"$role_holders" >/dev/null; then
        return 1
    fi
    package_dump="$("${ADB[@]}" shell dumpsys package "$APP_PACKAGE")"
    if ! rg --quiet 'android[.]permission[.]READ_SMS: granted=true' <<<"$package_dump"; then
        return 1
    fi
}

require_owner_eligibility() {
    if ! owner_eligible_once; then
        printf '%s requires owner-granted READ_SMS through normal app UI before capture.\n' \
            "$APP_PACKAGE" >&2
        exit 1
    fi
}

wait_for_stable_owner_eligibility() {
    local consecutive=0
    local deadline=$((SECONDS + 30))

    while ((SECONDS < deadline)); do
        if owner_eligible_once; then
            consecutive=$((consecutive + 1))
            if ((consecutive >= 3)); then
                return 0
            fi
        else
            consecutive=0
        fi
        sleep 1
    done

    printf '%s did not retain the owner-selected SMS role and READ_SMS grant after install.\n' \
        "$APP_PACKAGE" >&2
    exit 1
}

installed_target_matches_build() {
    local installed_path local_sha256 device_sha256
    installed_path="$(
        "${ADB[@]}" shell pm path "$APP_PACKAGE" \
            | tr -d '\r' \
            | sed -n 's/^package://p' \
            | head -n 1
    )"
    if [[ -z "$installed_path" ]]; then
        return 1
    fi

    local_sha256="$(sha256sum "$APP_APK" | awk '{print $1}')"
    device_sha256="$("${ADB[@]}" shell sha256sum "$installed_path" | awk '{print $1}')"
    [[ "$device_sha256" == "$local_sha256" ]]
}

normalize_profile() {
    local output="$1"
    shift
    LC_ALL=C sed 's/\r$//' "$@" \
        | sed '/^[[:space:]]*$/d' \
        | awk '/^[HSP]*Lorg\/aurorasms\//' \
        | LC_ALL=C sort -u >"$output"
    if [[ ! -s "$output" ]]; then
        printf 'Generated Aurora-owned Baseline Profile is empty.\n' >&2
        exit 1
    fi
    if rg --invert-match --line-number '^[HSP]*L[^[:space:]]+$' "$output"; then
        printf 'Generated Baseline Profile contains a non-HRF line.\n' >&2
        exit 1
    fi
    if rg --ignore-case --line-number \
        'AuroraSMS_Codex_Handoff_PRIVATE|FossifyOrg|org[.]fossify|org[.]simplemobiletools|content://|fixtures[.]example[.]invalid|[+]1202555' \
        "$output"; then
        printf 'Generated Baseline Profile failed the privacy/provenance scan.\n' >&2
        exit 1
    fi
}

capture_once() {
    local ordinal="$1"
    local run_root="$WORK/run-$ordinal"
    local device_output="/sdcard/Android/media/$TEST_PACKAGE/additional_test_output/aurora-$ordinal-$$"
    local instrumentation_status
    mkdir -p "$run_root"

    "${ADB[@]}" shell rm -rf "$device_output"
    "${ADB[@]}" shell mkdir -p "$device_output"
    set +e
    "${ADB[@]}" shell am instrument -w -r \
        -e additionalTestOutputDir "$device_output" \
        -e androidx.benchmark.enabledRules BaselineProfile \
        -e class org.aurorasms.macrobenchmark.BaselineProfileGenerator \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$run_root/instrumentation.txt"
    instrumentation_status="${PIPESTATUS[0]}"
    set -e
    if [[ "$instrumentation_status" -ne 0 ]] || \
        rg --quiet 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' \
            "$run_root/instrumentation.txt" || \
        ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$run_root/instrumentation.txt"; then
        "${ADB[@]}" shell rm -rf "$device_output" || true
        printf 'Baseline Profile instrumentation failed during capture %s.\n' "$ordinal" >&2
        exit 1
    fi

    "${ADB[@]}" pull "$device_output" "$run_root/device-output" >/dev/null
    "${ADB[@]}" shell rm -rf "$device_output"

    mapfile -d '' -t generated < <(
        find "$run_root/device-output" -type f \
            \( -iname '*baseline*prof*.txt' -o -iname '*baseline*profile*.txt' \) \
            -print0 | sort -z
    )
    if [[ ${#generated[@]} -eq 0 ]]; then
        printf 'No BaselineProfileRule HRF output was found after capture %s.\n' "$ordinal" >&2
        exit 1
    fi
    normalize_profile "$run_root/baseline-prof.txt" "${generated[@]}"
}

verify_benchmark_shell() {
    local device_output="/sdcard/Android/media/$TEST_PACKAGE/additional_test_output/aurora-shell-$$"
    local instrumentation_status

    "${ADB[@]}" shell rm -rf "$device_output"
    "${ADB[@]}" shell mkdir -p "$device_output"
    set +e
    "${ADB[@]}" shell am instrument -w -r \
        -e additionalTestOutputDir "$device_output" \
        -e class org.aurorasms.macrobenchmark.BenchmarkShellPreflightTest \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$WORK/shell-preflight.txt"
    instrumentation_status="${PIPESTATUS[0]}"
    set -e
    "${ADB[@]}" shell rm -rf "$device_output" || true
    if [[ "$instrumentation_status" -ne 0 ]] || \
        rg --quiet 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' \
            "$WORK/shell-preflight.txt" || \
        ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$WORK/shell-preflight.txt"; then
        printf 'Non-root benchmark shell preflight failed.\n' >&2
        exit 1
    fi
}

"$ROOT/gradlew" :app:assembleBenchmark :macrobenchmark:assembleBenchmark \
    -PauroraBaselineProfileCapture=true --no-daemon --no-parallel
"$ROOT/scripts/prepare-macrobenchmark-apk.sh" "$RAW_TEST_APK" "$TEST_APK"
if ! installed_target_matches_build; then
    "${ADB[@]}" install -r "$APP_APK" >/dev/null
    printf '%s was installed or updated. Select it as the default SMS app through normal UI, then rerun capture.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi
"${ADB[@]}" install -r -t "$TEST_APK" >/dev/null
wait_for_stable_owner_eligibility
verify_benchmark_shell

wait_for_stable_owner_eligibility
capture_once first
wait_for_stable_owner_eligibility
capture_once second

FIRST_PROFILE="$WORK/run-first/baseline-prof.txt"
SECOND_PROFILE="$WORK/run-second/baseline-prof.txt"
STABLE_PROFILE="$WORK/baseline-prof-stable.txt"
comm -12 "$FIRST_PROFILE" "$SECOND_PROFILE" >"$STABLE_PROFILE"

FIRST_RULES="$(wc -l <"$FIRST_PROFILE")"
SECOND_RULES="$(wc -l <"$SECOND_PROFILE")"
STABLE_RULES="$(wc -l <"$STABLE_PROFILE")"
if [[ "$STABLE_RULES" -eq 0 ]] || \
    ((STABLE_RULES * 100 < FIRST_RULES * MINIMUM_CAPTURE_AGREEMENT_PERCENT)) || \
    ((STABLE_RULES * 100 < SECOND_RULES * MINIMUM_CAPTURE_AGREEMENT_PERCENT)); then
    diff -u "$FIRST_PROFILE" "$SECOND_PROFILE" >"$WORK/profile-diff.txt" || true
    sed -n '1,200p' "$WORK/profile-diff.txt" >&2
    printf 'Aurora-owned Baseline Profile captures agreed on %s/%s and %s/%s rules; required %s%%.\n' \
        "$STABLE_RULES" "$FIRST_RULES" "$STABLE_RULES" "$SECOND_RULES" \
        "$MINIMUM_CAPTURE_AGREEMENT_PERCENT" >&2
    exit 1
fi

cp "$STABLE_PROFILE" "$DESTINATION"
printf 'Baseline Profile updated: %s (%s stable rules; captures %s/%s and %s/%s; SHA-256 %s)\n' \
    "$DESTINATION" \
    "$STABLE_RULES" \
    "$STABLE_RULES" "$FIRST_RULES" \
    "$STABLE_RULES" "$SECOND_RULES" \
    "$(sha256sum "$DESTINATION" | awk '{print $1}')"
