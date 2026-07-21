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
PRODUCTION_PACKAGE="org.aurorasms.app"
APP_PACKAGE="org.aurorasms.app.benchmark"
TEST_PACKAGE="org.aurorasms.macrobenchmark"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
RAW_TEST_APK="$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TEST_APK="$WORK/macrobenchmark-benchmark-nonroot.apk"
DESTINATION_TMP="$DESTINATION.tmp.$$"
DEVICE_OUTPUT_ROOT="/sdcard/Android/media/$TEST_PACKAGE/aurora-profile-$$"
INSTRUMENTATION_TIMEOUT_SECONDS=3600
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

role_state() {
    local sdk
    sdk="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
    if [[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 29 ]]; then
        "${ADB[@]}" shell cmd role get-role-holders android.app.role.SMS \
            | tr -d '\r' \
            | sed '/^[[:space:]]*$/d'
    else
        "${ADB[@]}" shell settings get secure sms_default_application | tr -d '\r'
    fi
}

package_path() {
    "${ADB[@]}" shell pm path "$1" | tr -d '\r' || true
}

ORIGINAL_ROLE="$(role_state)"
ORIGINAL_PRODUCTION_PATH="$(package_path "$PRODUCTION_PACKAGE")"
if [[ -z "$ORIGINAL_ROLE" || "$ORIGINAL_ROLE" == "$APP_PACKAGE" ]]; then
    printf 'The isolated benchmark target must not hold the SMS role before capture.\n' >&2
    exit 1
fi

cleanup_device() (
    local cleanup_role
    set +e
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1
    "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1
    "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    cleanup_role="$(role_state)"
    if [[ "$cleanup_role" != "$APP_PACKAGE" ]]; then
        "${ADB[@]}" uninstall "$APP_PACKAGE" >/dev/null 2>&1
    fi
    "${ADB[@]}" shell rm -rf "$DEVICE_OUTPUT_ROOT" >/dev/null 2>&1
)

cleanup() (
    set +e
    cleanup_device
    rm -f "$DESTINATION_TMP"
    rm -rf "$WORK"
)
trap cleanup EXIT

verify_cleanup() {
    if [[ -n "$(package_path "$TEST_PACKAGE")" || \
        -n "$(package_path "$APP_PACKAGE")" ]]; then
        printf 'Isolated Baseline Profile packages remained installed after cleanup.\n' >&2
        return 1
    fi
    if [[ "$(role_state)" != "$ORIGINAL_ROLE" || \
        "$(package_path "$PRODUCTION_PACKAGE")" != "$ORIGINAL_PRODUCTION_PATH" ]]; then
        printf 'The device role or production package changed during profile cleanup.\n' >&2
        return 1
    fi
    if "${ADB[@]}" shell test -e "$DEVICE_OUTPUT_ROOT"; then
        printf 'Temporary Baseline Profile artifacts remained on the device.\n' >&2
        return 1
    fi
}

synthetic_isolation_once() {
    local package_dump permission
    if [[ "$(role_state)" != "$ORIGINAL_ROLE" ]]; then
        return 1
    fi
    if [[ "$(package_path "$PRODUCTION_PACKAGE")" != "$ORIGINAL_PRODUCTION_PATH" ]]; then
        return 1
    fi
    if [[ -z "$(package_path "$APP_PACKAGE")" ]]; then
        return 1
    fi
    package_dump="$("${ADB[@]}" shell dumpsys package "$APP_PACKAGE")"
    for permission in \
        android.permission.READ_SMS \
        android.permission.SEND_SMS \
        android.permission.RECEIVE_SMS \
        android.permission.RECEIVE_MMS \
        android.permission.RECEIVE_WAP_PUSH \
        android.permission.READ_PHONE_STATE; do
        if rg --fixed-strings --quiet "$permission: granted=true" <<<"$package_dump"; then
            return 1
        fi
    done
    [[ "$(role_state)" != "$APP_PACKAGE" ]]
}

require_synthetic_isolation() {
    if ! synthetic_isolation_once; then
        printf '%s must remain package-isolated, messaging-permission-free, and outside the SMS role.\n' \
            "$APP_PACKAGE" >&2
        exit 1
    fi
}

wait_for_stable_synthetic_isolation() {
    local consecutive=0
    local deadline=$((SECONDS + 30))

    while ((SECONDS < deadline)); do
        if synthetic_isolation_once; then
            consecutive=$((consecutive + 1))
            if ((consecutive >= 3)); then
                return 0
            fi
        else
            consecutive=0
        fi
        sleep 1
    done

    printf '%s did not retain its denied messaging authority after install.\n' \
        "$APP_PACKAGE" >&2
    exit 1
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
    local device_output="$DEVICE_OUTPUT_ROOT/capture-$ordinal"
    local instrumentation_status tee_status zero_status_count
    local -a pipeline_status
    mkdir -p "$run_root"

    "${ADB[@]}" shell rm -rf "$device_output"
    "${ADB[@]}" shell mkdir -p "$device_output"
    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am instrument -w -r \
            -e additionalTestOutputDir "$device_output" \
            -e androidx.benchmark.enabledRules BaselineProfile \
            -e class org.aurorasms.macrobenchmark.BaselineProfileGenerator \
            "$TEST_PACKAGE/$TEST_RUNNER" | tee "$run_root/instrumentation.txt"
    pipeline_status=("${PIPESTATUS[@]}")
    instrumentation_status="${pipeline_status[0]}"
    tee_status="${pipeline_status[1]}"
    set -e
    zero_status_count="$(
        rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' \
            "$run_root/instrumentation.txt" || true
    )"
    if [[ "$instrumentation_status" -ne 0 || "$tee_status" -ne 0 ]] || \
        rg --quiet \
            'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
            "$run_root/instrumentation.txt" || \
        [[ "$zero_status_count" -ne 3 ]] || \
        ! rg --quiet '^OK \(3 tests\)$' "$run_root/instrumentation.txt" || \
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
    local device_output="$DEVICE_OUTPUT_ROOT/shell-preflight"
    local instrumentation_status tee_status zero_status_count
    local -a pipeline_status

    "${ADB[@]}" shell rm -rf "$device_output"
    "${ADB[@]}" shell mkdir -p "$device_output"
    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am instrument -w -r \
            -e additionalTestOutputDir "$device_output" \
            -e class org.aurorasms.macrobenchmark.BenchmarkShellPreflightTest \
            "$TEST_PACKAGE/$TEST_RUNNER" | tee "$WORK/shell-preflight.txt"
    pipeline_status=("${PIPESTATUS[@]}")
    instrumentation_status="${pipeline_status[0]}"
    tee_status="${pipeline_status[1]}"
    set -e
    "${ADB[@]}" shell rm -rf "$device_output" || true
    zero_status_count="$(
        rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' \
            "$WORK/shell-preflight.txt" || true
    )"
    if [[ "$instrumentation_status" -ne 0 || "$tee_status" -ne 0 ]] || \
        rg --quiet \
            'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
            "$WORK/shell-preflight.txt" || \
        [[ "$zero_status_count" -ne 1 ]] || \
        ! rg --quiet '^OK \(1 test\)$' "$WORK/shell-preflight.txt" || \
        ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$WORK/shell-preflight.txt"; then
        printf 'Non-root benchmark shell preflight failed.\n' >&2
        exit 1
    fi
}

"$ROOT/gradlew" :app:assembleBenchmark :macrobenchmark:assembleBenchmark \
    -PauroraBaselineProfileCapture=true --no-daemon --no-parallel
"$ROOT/scripts/prepare-macrobenchmark-apk.sh" "$RAW_TEST_APK" "$TEST_APK"
"${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
"${ADB[@]}" uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
if [[ "$(role_state)" != "$ORIGINAL_ROLE" || \
    "$(package_path "$PRODUCTION_PACKAGE")" != "$ORIGINAL_PRODUCTION_PATH" ]]; then
    printf 'The SMS role or production package changed before isolated capture.\n' >&2
    exit 1
fi
"${ADB[@]}" install "$APP_APK" >/dev/null
"${ADB[@]}" install -t "$TEST_APK" >/dev/null
wait_for_stable_synthetic_isolation
verify_benchmark_shell

wait_for_stable_synthetic_isolation
capture_once first
wait_for_stable_synthetic_isolation
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

require_synthetic_isolation
cleanup_device
verify_cleanup
cp "$STABLE_PROFILE" "$DESTINATION_TMP"
mv "$DESTINATION_TMP" "$DESTINATION"
rm -rf "$WORK"
trap - EXIT
printf 'Baseline Profile updated: %s (%s stable rules; captures %s/%s and %s/%s; SHA-256 %s)\n' \
    "$DESTINATION" \
    "$STABLE_RULES" \
    "$STABLE_RULES" "$FIRST_RULES" \
    "$STABLE_RULES" "$SECOND_RULES" \
    "$(sha256sum "$DESTINATION" | awk '{print $1}')"
