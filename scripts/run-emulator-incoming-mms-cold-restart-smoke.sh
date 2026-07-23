#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s --device SERIAL\n' "$0" >&2
}

if [[ "${1:-}" != "--device" || $# -ne 2 || -z "${2:-}" ]]; then
    usage
    exit 2
fi

DEVICE_SERIAL="$2"
ROOT="$(git rev-parse --show-toplevel)"
TEST_PACKAGE="org.aurorasms.core.telephony.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.aurorasms.core.telephony.internal.AndroidMmsIncomingDownloadSubmissionTest"
TEST_METHOD="completedIncomingMmsSurvivesHostForceStopUntilFreshProcessNotificationAcknowledgement"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
GATE_ARGUMENT="auroraEmulatorIncomingMmsColdRestart"
PHASE_ARGUMENT="auroraEmulatorIncomingMmsColdRestartPhase"
TEST_APK="$ROOT/core/telephony/build/outputs/apk/androidTest/debug/telephony-debug-androidTest.apk"
INSTRUMENTATION_TIMEOUT_SECONDS=90
ADB=(adb -s "$DEVICE_SERIAL")
EMPTY_ACTIVITY="${TEST_PACKAGE}/androidx.test.core.app.InstrumentationActivityInvoker"'$EmptyActivity'

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested incoming-MMS cold-restart emulator is unavailable: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
HARDWARE="$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')"
SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$DEVICE_SERIAL" != emulator-* || "$IS_EMULATOR" != "1" ]]; then
    printf 'The incoming-MMS cold-restart smoke refuses physical devices.\n' >&2
    exit 1
fi
if [[ "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" ]]; then
    printf 'The incoming-MMS cold-restart smoke requires ranchu or goldfish.\n' >&2
    exit 1
fi
if [[ "$SDK_LEVEL" != "26" && "$SDK_LEVEL" != "36" ]]; then
    printf 'The incoming-MMS cold-restart smoke requires API 26 or API 36.\n' >&2
    exit 1
fi

role_state() {
    if (( SDK_LEVEL >= 29 )); then
        "${ADB[@]}" shell cmd role get-role-holders android.app.role.SMS \
            | tr -d '\r' \
            | sed '/^[[:space:]]*$/d'
    else
        "${ADB[@]}" shell settings get secure sms_default_application | tr -d '\r'
    fi
}

current_test_pid() {
    "${ADB[@]}" shell pidof "$TEST_PACKAGE" | tr -d '\r' || true
}

force_stop_and_require_absent() {
    local attempt pid
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    for attempt in {1..40}; do
        pid="$(current_test_pid)"
        if [[ -z "$pid" ]]; then
            return 0
        fi
        sleep 0.1
    done
    printf 'The incoming-MMS test process remained alive after force-stop.\n' >&2
    return 1
}

start_test_process() {
    local attempt pid
    "${ADB[@]}" shell "am start -W -n '$EMPTY_ACTIVITY'" >/dev/null
    pid=""
    for attempt in {1..40}; do
        pid="$(current_test_pid)"
        if [[ -n "$pid" ]]; then
            break
        fi
        sleep 0.1
    done
    if [[ -z "$pid" ]]; then
        printf 'The incoming-MMS test package did not expose a live process.\n' >&2
        return 1
    fi
    printf '%s' "$pid"
}

force_stop_live_process() {
    local expected_pid current_pid
    expected_pid="$1"
    current_pid="$(current_test_pid)"
    if [[ -z "$expected_pid" || "$current_pid" != "$expected_pid" ]]; then
        printf 'The exact incoming-MMS test process was not alive before force-stop.\n' >&2
        return 1
    fi
    force_stop_and_require_absent
    printf 'Host force-stop removed incoming-MMS test PID %s.\n' "$expected_pid"
}

run_phase() (
    local phase output instrumentation_status zero_status_count
    phase="$1"
    output="$2"
    printf 'Running incoming-MMS cold-restart phase: %s\n' "$phase"
    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am instrument -w -r \
        -e class "$TEST_TARGET" \
        -e "$GATE_ARGUMENT" true \
        -e "$PHASE_ARGUMENT" "$phase" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$output"
    instrumentation_status="${PIPESTATUS[0]}"
    zero_status_count="$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$output" || true)"
    if [[ "$instrumentation_status" -ne 0 ]] || \
        rg --quiet \
            'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
            "$output" || \
        [[ "$zero_status_count" -ne 1 ]] || \
        ! rg --quiet '^OK \(1 test\)$' "$output" || \
        ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$output"; then
        printf 'Incoming-MMS cold-restart phase did not pass exactly one test: %s\n' \
            "$phase" >&2
        return 1
    fi
)

"$ROOT/gradlew" :core:telephony:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain

BEFORE_ROLE="$(role_state)"
BEFORE_TEST_PATH="$(
    "${ADB[@]}" shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
)"
if [[ -n "$BEFORE_TEST_PATH" ]]; then
    printf '%s is already installed; remove or preserve it explicitly first.\n' \
        "$TEST_PACKAGE" >&2
    exit 1
fi

WORK="$(mktemp -d)"
TEST_INSTALLED=0
MUTATION_MAY_EXIST=0
CLEANUP_COMPLETE=0

cleanup() {
    local status=$?
    local cleanup_ok role_after test_path_after
    trap - EXIT
    set +e
    cleanup_ok=1

    if [[ "$MUTATION_MAY_EXIST" -eq 1 && "$CLEANUP_COMPLETE" -eq 0 && \
        "$TEST_INSTALLED" -eq 1 ]]; then
        force_stop_and_require_absent || cleanup_ok=0
        if run_phase cleanup "$WORK/trap-cleanup.txt"; then
            CLEANUP_COMPLETE=1
        else
            cleanup_ok=0
        fi
        force_stop_and_require_absent || cleanup_ok=0
    fi
    if [[ "$cleanup_ok" -eq 1 && "$TEST_INSTALLED" -eq 1 ]]; then
        "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || cleanup_ok=0
    fi
    test_path_after="$(
        "${ADB[@]}" shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
    )"
    if [[ "$cleanup_ok" -eq 1 && -n "$test_path_after" ]]; then
        printf 'The incoming-MMS instrumentation package remained installed.\n' >&2
        cleanup_ok=0
    fi
    role_after="$(role_state)"
    if [[ "$role_after" != "$BEFORE_ROLE" ]]; then
        printf 'The incoming-MMS smoke changed the SMS role holder.\n' >&2
        cleanup_ok=0
    fi
    if [[ "$cleanup_ok" -ne 1 ]]; then
        status=1
        printf 'Incoming-MMS cleanup failed; logs remain in %s.\n' "$WORK" >&2
    else
        rm -rf "$WORK"
    fi
    exit "$status"
}
trap cleanup EXIT

"${ADB[@]}" install "$TEST_APK" >/dev/null
TEST_INSTALLED=1
MUTATION_MAY_EXIST=1

run_phase cleanup "$WORK/preflight-cleanup.txt"
force_stop_and_require_absent
run_phase prepare "$WORK/prepare.txt"
PREPARED_LIVE_PID="$(start_test_process)"
force_stop_live_process "$PREPARED_LIVE_PID"
run_phase verify "$WORK/verify.txt"
VERIFIED_LIVE_PID="$(start_test_process)"
force_stop_live_process "$VERIFIED_LIVE_PID"
run_phase cleanup "$WORK/cleanup.txt"
CLEANUP_COMPLETE=1
force_stop_and_require_absent

printf 'Incoming-MMS cold-restart smoke passed on %s (API %s).\n' \
    "$DEVICE_SERIAL" "$SDK_LEVEL"
