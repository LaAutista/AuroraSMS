#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s --device SERIAL (--smoke | --full) [--output DIRECTORY]\n' "$0" >&2
}

DEVICE_SERIAL=""
RUN_MODE=""
OUTPUT_DIRECTORY=""
while (($#)); do
    case "$1" in
        --device)
            if [[ $# -lt 2 || -z "$2" ]]; then
                usage
                exit 2
            fi
            DEVICE_SERIAL="$2"
            shift 2
            ;;
        --smoke)
            if [[ -n "$RUN_MODE" ]]; then
                usage
                exit 2
            fi
            RUN_MODE="smoke"
            shift
            ;;
        --full)
            if [[ -n "$RUN_MODE" ]]; then
                usage
                exit 2
            fi
            RUN_MODE="full"
            shift
            ;;
        --output)
            if [[ $# -lt 2 || -z "$2" ]]; then
                usage
                exit 2
            fi
            OUTPUT_DIRECTORY="$2"
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [[ -z "$DEVICE_SERIAL" || -z "$RUN_MODE" ]]; then
    usage
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
PRODUCTION_PACKAGE="org.aurorasms.app"
TARGET_PACKAGE="org.aurorasms.app.benchmark"
TEST_PACKAGE="org.aurorasms.macrobenchmark"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TARGET_APK="$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
RAW_TEST_APK="$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PATCHED_TEST_APK="$WORK/macrobenchmark-benchmark-nonroot.apk"
if [[ "$RUN_MODE" == "full" ]]; then
    INSTRUMENTATION_TIMEOUT_SECONDS=7200
else
    INSTRUMENTATION_TIMEOUT_SECONDS=1800
fi
ADB=(adb -s "$DEVICE_SERIAL")
RUN_STARTED_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
SAFE_DEVICE_SERIAL="$(sed 's/[^0-9A-Za-z._-]/_/g' <<<"$DEVICE_SERIAL")"
DEVICE_OUTPUT_ROOT="/sdcard/Android/media/$TEST_PACKAGE/aurora-performance-$RUN_ID"

if [[ -z "$OUTPUT_DIRECTORY" ]]; then
    OUTPUT_DIRECTORY="$ROOT/build/isolated-performance/$RUN_ID-$SAFE_DEVICE_SERIAL-$RUN_MODE"
fi
OUTPUT_DIRECTORY="$(realpath -m -- "$OUTPUT_DIRECTORY")"
case "$OUTPUT_DIRECTORY/" in
    "$ROOT/"*)
        if ! git -C "$ROOT" check-ignore --quiet -- "$OUTPUT_DIRECTORY"; then
            printf 'Performance evidence inside the repository must be Git-ignored: %s\n' \
                "$OUTPUT_DIRECTORY" >&2
            exit 1
        fi
        ;;
esac
if [[ -e "$OUTPUT_DIRECTORY" && ! -d "$OUTPUT_DIRECTORY" ]]; then
    printf 'Performance output path is not a directory: %s\n' "$OUTPUT_DIRECTORY" >&2
    exit 1
fi
if [[ -d "$OUTPUT_DIRECTORY" && \
    -n "$(find "$OUTPUT_DIRECTORY" -mindepth 1 -print -quit)" ]]; then
    printf 'Performance output directory must be empty: %s\n' "$OUTPUT_DIRECTORY" >&2
    exit 1
fi
if [[ "$RUN_MODE" == "full" && \
    -n "$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
    printf 'Full performance evidence requires a clean, committed worktree.\n' >&2
    exit 1
fi
mkdir -p "$OUTPUT_DIRECTORY"

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested performance device is unavailable or unauthorized: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ ! "$SDK_LEVEL" =~ ^[0-9]+$ || "$SDK_LEVEL" -lt 26 ]]; then
    printf 'The isolated performance suite requires Android API 26 or newer.\n' >&2
    exit 1
fi
if [[ "$RUN_MODE" == "full" && \
    ( "$DEVICE_SERIAL" == emulator-* || "$IS_EMULATOR" == "1" ) ]]; then
    printf 'Full performance evidence requires a physical device; use --smoke on emulators.\n' >&2
    exit 1
fi
if [[ "$RUN_MODE" == "smoke" && \
    "$DEVICE_SERIAL" != emulator-* && "$IS_EMULATOR" != "1" ]]; then
    printf 'Smoke mode is emulator-only; use --full for physical performance evidence.\n' >&2
    exit 1
fi

if [[ "$RUN_MODE" == "full" ]]; then
    REQUIRED_FREE_KIB=$((8 * 1024 * 1024))
else
    REQUIRED_FREE_KIB=$((1024 * 1024))
fi
HOST_FREE_KIB="$(df -Pk "$OUTPUT_DIRECTORY" | awk 'END { print $4 }')"
DEVICE_FREE_KIB="$(
    "${ADB[@]}" shell df -Pk /sdcard \
        | tr -d '\r' \
        | awk 'END { print $4 }'
)"
if [[ ! "$HOST_FREE_KIB" =~ ^[0-9]+$ || ! "$DEVICE_FREE_KIB" =~ ^[0-9]+$ ]]; then
    printf 'Could not determine host/device free space for benchmark artifacts.\n' >&2
    exit 1
fi
if ((HOST_FREE_KIB < REQUIRED_FREE_KIB || DEVICE_FREE_KIB < REQUIRED_FREE_KIB)); then
    printf 'Isolated %s evidence requires at least %s KiB free on host and device; found %s/%s KiB.\n' \
        "$RUN_MODE" "$REQUIRED_FREE_KIB" "$HOST_FREE_KIB" "$DEVICE_FREE_KIB" >&2
    exit 1
fi

role_state() {
    if ((SDK_LEVEL >= 29)); then
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
if [[ -z "$ORIGINAL_ROLE" || "$ORIGINAL_ROLE" == "$TARGET_PACKAGE" ]]; then
    printf 'The benchmark target must not hold the SMS role before this suite.\n' >&2
    exit 1
fi

cleanup() (
    local cleanup_role
    set +e
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1
    cleanup_role="$(role_state)"
    "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    if [[ "$cleanup_role" != "$TARGET_PACKAGE" ]]; then
        "${ADB[@]}" uninstall "$TARGET_PACKAGE" >/dev/null 2>&1
    fi
    "${ADB[@]}" shell rm -rf "$DEVICE_OUTPUT_ROOT" >/dev/null 2>&1
    rm -rf "$WORK"
)
trap cleanup EXIT

verify_cleanup() {
    if [[ -n "$(package_path "$TEST_PACKAGE")" || \
        -n "$(package_path "$TARGET_PACKAGE")" ]]; then
        printf 'Isolated benchmark packages remained installed after cleanup.\n' >&2
        return 1
    fi
    if [[ "$(role_state)" != "$ORIGINAL_ROLE" || \
        "$(package_path "$PRODUCTION_PACKAGE")" != "$ORIGINAL_PRODUCTION_PATH" ]]; then
        printf 'The device role or production package changed during isolated cleanup.\n' >&2
        return 1
    fi
    if "${ADB[@]}" shell test -e "$DEVICE_OUTPUT_ROOT"; then
        printf 'Temporary benchmark artifacts remained on the device after cleanup.\n' >&2
        return 1
    fi
}

synthetic_isolation_once() {
    local package_dump permission
    if [[ "$(role_state)" != "$ORIGINAL_ROLE" || \
        "$(role_state)" == "$TARGET_PACKAGE" ]]; then
        return 1
    fi
    if [[ "$(package_path "$PRODUCTION_PACKAGE")" != "$ORIGINAL_PRODUCTION_PATH" ]]; then
        return 1
    fi
    if [[ -z "$(package_path "$TARGET_PACKAGE")" ]]; then
        return 1
    fi
    package_dump="$("${ADB[@]}" shell dumpsys package "$TARGET_PACKAGE")"
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
    printf '%s did not remain package-isolated, messaging-permission-free, and outside the SMS role.\n' \
        "$TARGET_PACKAGE" >&2
    exit 1
}

run_instrumentation() (
    local label="$1"
    local expected_tests="$2"
    local classes="$3"
    local artifact_policy="$4"
    shift 4
    local output="$OUTPUT_DIRECTORY/$label.txt"
    local remote_output="$DEVICE_OUTPUT_ROOT/$label"
    local host_artifacts="$OUTPUT_DIRECTORY/$label-artifacts"
    local instrumentation_status tee_status zero_status_count pull_status json_count trace_count
    local -a pipeline_status
    "${ADB[@]}" shell rm -rf "$remote_output" >/dev/null
    "${ADB[@]}" shell mkdir -p "$remote_output" >/dev/null
    mkdir -p "$host_artifacts"
    local command=(
        "${ADB[@]}" shell am instrument -w -r
        -e class "$classes"
        -e additionalTestOutputDir "$remote_output"
        -e androidx.benchmark.output.enable true
    )
    while (($#)); do
        command+=(-e "$1" "$2")
        shift 2
    done
    command+=("$TEST_PACKAGE/$TEST_RUNNER")

    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${command[@]}" | tee "$output"
    pipeline_status=("${PIPESTATUS[@]}")
    instrumentation_status="${pipeline_status[0]}"
    tee_status="${pipeline_status[1]}"
    "${ADB[@]}" pull "$remote_output/." "$host_artifacts" >/dev/null
    pull_status="$?"
    set -e
    zero_status_count="$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$output" || true)"
    if [[ "$instrumentation_status" -ne 0 || "$tee_status" -ne 0 ]] || \
        rg --quiet \
            'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
            "$output" || \
        [[ "$zero_status_count" -ne "$expected_tests" ]] || \
        ! rg --quiet "^OK \\($expected_tests tests?\\)$" "$output" || \
        ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$output"; then
        printf 'Isolated instrumentation failed: %s\n' "$label" >&2
        return 1
    fi
    if [[ "$artifact_policy" != "none" ]]; then
        json_count="$(
            find "$host_artifacts" -type f -name '*benchmarkData.json' \
                | wc -l \
                | tr -d '[:space:]'
        )"
        trace_count="$(
            find "$host_artifacts" -type f -name '*.perfetto-trace' \
                | wc -l \
                | tr -d '[:space:]'
        )"
        if [[ "$pull_status" -ne 0 || "$json_count" -ne 1 ]]; then
            printf 'Required AndroidX JSON/Perfetto artifacts were not retained: %s\n' \
                "$label" >&2
            return 1
        fi
        require_trace_series() {
            local prefix="$1"
            local expected="$2"
            local actual
            actual="$(
                find "$host_artifacts" -type f \
                    -name "${prefix}_iter*.perfetto-trace" \
                    | wc -l \
                    | tr -d '[:space:]'
            )"
            if [[ "$actual" -ne "$expected" ]]; then
                printf 'Perfetto series %s has %s traces; expected %s.\n' \
                    "$prefix" "$actual" "$expected" >&2
                return 1
            fi
        }
        if [[ "$artifact_policy" == "preparation" ]]; then
            require_trace_series \
                StartupBenchmark_warmInboxWithBaselineProfile \
                3
            if [[ "$trace_count" -ne 3 ]]; then
                printf 'Baseline preparation retained unexpected Perfetto artifacts.\n' >&2
                return 1
            fi
        elif [[ "$artifact_policy" == "macro" ]]; then
            local timing_iterations frame_iterations expected_trace_count prefix
            local -a timing_prefixes=(
                StartupBenchmark_warmInboxWithBaselineProfile
                StartupBenchmark_warmInboxWithoutCompilation
                ConversationFrameBenchmark_threadOpenWithBaselineProfile
                ConversationFrameBenchmark_threadOpenWithoutCompilation
                SearchJumpBenchmark_searchWithBaselineProfile
                SearchJumpBenchmark_searchWithoutCompilation
                SearchJumpBenchmark_exactOldJumpWithBaselineProfile
                SearchJumpBenchmark_exactOldJumpWithoutCompilation
            )
            local -a frame_prefixes=(
                InboxFrameBenchmark_inboxFlingAtTwentyThousandThreads
                ConversationFrameBenchmark_threadFlingAndPrependAtTwoHundredFiftyThousandMessages
            )
            if [[ "$RUN_MODE" == "full" ]]; then
                timing_iterations=30
                frame_iterations=10
            else
                timing_iterations=3
                frame_iterations=1
            fi
            for prefix in "${timing_prefixes[@]}"; do
                require_trace_series "$prefix" "$timing_iterations"
            done
            for prefix in "${frame_prefixes[@]}"; do
                require_trace_series "$prefix" "$frame_iterations"
            done
            expected_trace_count=$((8 * timing_iterations + 2 * frame_iterations))
            if [[ "$trace_count" -ne "$expected_trace_count" ]]; then
                printf 'Macrobenchmark retained %s total traces; expected exactly %s.\n' \
                    "$trace_count" "$expected_trace_count" >&2
                return 1
            fi
        else
            printf 'Unknown artifact policy: %s\n' "$artifact_policy" >&2
            return 1
        fi
    fi
)

"$ROOT/gradlew" \
    :app:assembleBenchmark \
    :macrobenchmark:assembleBenchmark \
    --offline --no-daemon --no-parallel --console=plain
"$ROOT/gradlew" \
    verifyPermissions \
    verifyApkContents \
    --offline --no-daemon --no-parallel --console=plain
"$ROOT/scripts/prepare-macrobenchmark-apk.sh" "$RAW_TEST_APK" "$PATCHED_TEST_APK"

GIT_HEAD="$(git -C "$ROOT" rev-parse HEAD)"
GIT_BRANCH="$(git -C "$ROOT" branch --show-current)"
if [[ -n "$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
    GIT_DIRTY="true"
else
    GIT_DIRTY="false"
fi
if [[ "$RUN_MODE" == "full" && "$GIT_DIRTY" != "false" ]]; then
    printf 'The worktree changed while preparing full performance evidence.\n' >&2
    exit 1
fi
TARGET_APK_SHA256="$(sha256sum "$TARGET_APK" | awk '{print $1}')"
TEST_APK_SHA256="$(sha256sum "$PATCHED_TEST_APK" | awk '{print $1}')"
RAW_TEST_APK_SHA256="$(sha256sum "$RAW_TEST_APK" | awk '{print $1}')"
DEVICE_SERIAL_SHA256="$(printf '%s' "$DEVICE_SERIAL" | sha256sum | awk '{print $1}')"
DEVICE_MANUFACTURER="$("${ADB[@]}" shell getprop ro.product.manufacturer | tr -d '\r\n')"
DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r\n')"
DEVICE_FINGERPRINT="$("${ADB[@]}" shell getprop ro.build.fingerprint | tr -d '\r\n')"
DISPLAY_EVIDENCE="$(
    "${ADB[@]}" shell dumpsys display \
        | tr -d '\r' \
        | rg -m1 'renderFrameRate|physicalRefreshRate|refreshRate|fps=' \
        || true
)"
BATTERY_LEVEL="$(
    "${ADB[@]}" shell dumpsys battery \
        | sed -n 's/^[[:space:]]*level:[[:space:]]*//p' \
        | head -1 \
        | tr -d '\r' \
        || true
)"
BATTERY_TEMPERATURE_TENTHS_C="$(
    "${ADB[@]}" shell dumpsys battery \
        | sed -n 's/^[[:space:]]*temperature:[[:space:]]*//p' \
        | head -1 \
        | tr -d '\r' \
        || true
)"
THERMAL_STATUS="$(
    "${ADB[@]}" shell dumpsys thermalservice \
        | tr -d '\r' \
        | rg -m1 'Thermal Status|Status:' \
        || true
)"

{
    printf 'schema=aurorasms-isolated-performance-v1\n'
    printf 'run_started_utc=%s\n' "$RUN_STARTED_UTC"
    printf 'mode=%s\n' "$RUN_MODE"
    printf 'git_head=%s\n' "$GIT_HEAD"
    printf 'git_branch=%s\n' "$GIT_BRANCH"
    printf 'git_dirty=%s\n' "$GIT_DIRTY"
    printf 'target_package=%s\n' "$TARGET_PACKAGE"
    printf 'target_apk_sha256=%s\n' "$TARGET_APK_SHA256"
    printf 'target_apk_bytes=%s\n' "$(stat -c '%s' "$TARGET_APK")"
    printf 'controller_package=%s\n' "$TEST_PACKAGE"
    printf 'controller_apk_sha256=%s\n' "$TEST_APK_SHA256"
    printf 'controller_raw_apk_sha256=%s\n' "$RAW_TEST_APK_SHA256"
    printf 'controller_apk_bytes=%s\n' "$(stat -c '%s' "$PATCHED_TEST_APK")"
    printf 'device_serial_sha256=%s\n' "$DEVICE_SERIAL_SHA256"
    printf 'device_manufacturer=%s\n' "$DEVICE_MANUFACTURER"
    printf 'device_model=%s\n' "$DEVICE_MODEL"
    printf 'device_fingerprint=%s\n' "$DEVICE_FINGERPRINT"
    printf 'device_api=%s\n' "$SDK_LEVEL"
    printf 'device_is_emulator=%s\n' "$IS_EMULATOR"
    printf 'host_free_kib_before=%s\n' "$HOST_FREE_KIB"
    printf 'device_free_kib_before=%s\n' "$DEVICE_FREE_KIB"
    printf 'display_evidence=%s\n' "$DISPLAY_EVIDENCE"
    printf 'battery_level_percent=%s\n' "$BATTERY_LEVEL"
    printf 'battery_temperature_tenths_c=%s\n' "$BATTERY_TEMPERATURE_TENTHS_C"
    printf 'thermal_status=%s\n' "$THERMAL_STATUS"
    printf 'sms_role_unchanged_expected=%s\n' "$ORIGINAL_ROLE"
    printf 'production_package_present_before=%s\n' \
        "$([[ -n "$ORIGINAL_PRODUCTION_PATH" ]] && printf true || printf false)"
    printf 'privacy_class=message-content-free-device-identifying-local-evidence\n'
    printf 'raw_trace_publication=prohibited\n'
} >"$OUTPUT_DIRECTORY/metadata.txt"

"${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
"${ADB[@]}" uninstall "$TARGET_PACKAGE" >/dev/null 2>&1 || true
if [[ "$(role_state)" != "$ORIGINAL_ROLE" ]]; then
    printf 'The SMS role changed before benchmark installation; refusing to continue.\n' >&2
    exit 1
fi
"${ADB[@]}" install "$TARGET_APK" >/dev/null
"${ADB[@]}" install -t "$PATCHED_TEST_APK" >/dev/null
wait_for_stable_synthetic_isolation

run_instrumentation \
    boundary \
    5 \
    'org.aurorasms.macrobenchmark.BenchmarkBoundaryTest,org.aurorasms.macrobenchmark.BenchmarkShellPreflightTest' \
    none
wait_for_stable_synthetic_isolation

BENCHMARK_CLASSES='org.aurorasms.macrobenchmark.StartupBenchmark,org.aurorasms.macrobenchmark.InboxFrameBenchmark,org.aurorasms.macrobenchmark.ConversationFrameBenchmark,org.aurorasms.macrobenchmark.SearchJumpBenchmark'
if [[ "$RUN_MODE" == "full" ]]; then
    run_instrumentation \
        macro-measurements \
        10 \
        "$BENCHMARK_CLASSES" \
        macro \
        androidx.benchmark.enabledRules Macrobenchmark \
        auroraBenchmarkFull true
else
    run_instrumentation \
        macro-reachability \
        10 \
        "$BENCHMARK_CLASSES" \
        macro \
        androidx.benchmark.enabledRules Macrobenchmark \
        androidx.benchmark.suppressErrors EMULATOR
fi
wait_for_stable_synthetic_isolation

BASELINE_PREPARATION_TEST='org.aurorasms.macrobenchmark.StartupBenchmark#warmInboxWithBaselineProfile'
if [[ "$RUN_MODE" == "full" ]]; then
    run_instrumentation \
        baseline-preparation \
        1 \
        "$BASELINE_PREPARATION_TEST" \
        preparation \
        androidx.benchmark.enabledRules Macrobenchmark
else
    run_instrumentation \
        baseline-preparation \
        1 \
        "$BASELINE_PREPARATION_TEST" \
        preparation \
        androidx.benchmark.enabledRules Macrobenchmark \
        androidx.benchmark.suppressErrors EMULATOR
fi
wait_for_stable_synthetic_isolation

if [[ "$RUN_MODE" == "full" ]]; then
    run_instrumentation \
        memory-measurement \
        1 \
        'org.aurorasms.macrobenchmark.MemoryBenchmark' \
        none \
        auroraBenchmarkFull true
    python3 "$ROOT/scripts/verify-performance-results.py" \
        --results "$OUTPUT_DIRECTORY/macro-measurements-artifacts" \
        --memory-log "$OUTPUT_DIRECTORY/memory-measurement.txt" \
        | tee "$OUTPUT_DIRECTORY/budget-validation.txt"
    BUDGET_VALIDATION="passed"
else
    run_instrumentation \
        memory-reachability \
        1 \
        'org.aurorasms.macrobenchmark.MemoryBenchmark' \
        none
    BUDGET_VALIDATION="not-applicable-emulator-smoke"
fi
wait_for_stable_synthetic_isolation

printf 'budget_validation=%s\n' "$BUDGET_VALIDATION" >>"$OUTPUT_DIRECTORY/metadata.txt"
printf 'run_completed_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    >>"$OUTPUT_DIRECTORY/metadata.txt"

cleanup
verify_cleanup
trap - EXIT

(
    cd "$OUTPUT_DIRECTORY"
    find . -type f ! -name SHA256SUMS -print0 \
        | sort -z \
        | xargs -0 sha256sum >SHA256SUMS
)

if [[ "$RUN_MODE" == "full" ]]; then
    FINAL_GIT_HEAD="$(git -C "$ROOT" rev-parse HEAD)"
    FINAL_GIT_STATUS="$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all)"
    if [[ "$FINAL_GIT_HEAD" != "$GIT_HEAD" || -n "$FINAL_GIT_STATUS" ]]; then
        printf 'The source HEAD or worktree changed during full performance measurement; refusing pass evidence.\n' \
            >&2
        exit 1
    fi
fi

printf 'Isolated %s suite passed on %s (API %s).\n' \
    "$RUN_MODE" "$DEVICE_SERIAL" "$SDK_LEVEL"
printf 'SMS role remained %s; %s remained untouched and %s held no messaging authority.\n' \
    "$ORIGINAL_ROLE" "$PRODUCTION_PACKAGE" "$TARGET_PACKAGE"
printf 'Message-content-free, device-identifying local benchmark evidence: %s\n' \
    "$OUTPUT_DIRECTORY"
printf 'Do not publish raw JSON, metadata, or Perfetto traces.\n'
if [[ "$RUN_MODE" == "smoke" ]]; then
    printf 'Emulator results prove journey reachability only; they are not product-performance evidence.\n'
fi
