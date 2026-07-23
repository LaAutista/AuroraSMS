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
APP_PACKAGE="org.aurorasms.app"
TEST_PACKAGE="org.aurorasms.app.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.aurorasms.app.AuroraSmsRootAcceptanceTest"
TEST_METHOD="durableImageAttachmentSurvivesHostForceStopAndColdProcessMmsRoute"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
GATE_ARGUMENT="auroraEmulatorDraftAttachmentColdRestart"
PHASE_ARGUMENT="auroraEmulatorDraftAttachmentColdRestartPhase"
INSTRUMENTATION_TIMEOUT_SECONDS=90
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ADB=(adb -s "$DEVICE_SERIAL")
PERMISSIONS=(
    android.permission.READ_SMS
    android.permission.SEND_SMS
    android.permission.RECEIVE_SMS
    android.permission.RECEIVE_MMS
    android.permission.RECEIVE_WAP_PUSH
    android.permission.READ_PHONE_STATE
    android.permission.POST_NOTIFICATIONS
)

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested cold-restart attachment emulator is not authorized: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
HARDWARE="$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')"
if [[ "$DEVICE_SERIAL" != emulator-* || "$IS_EMULATOR" != "1" ]]; then
    printf 'The cold-restart attachment smoke refuses physical device: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi
if [[ "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" ]]; then
    printf 'The cold-restart attachment smoke requires ranchu or goldfish.\n' >&2
    exit 1
fi

SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$SDK_LEVEL" != "36" ]]; then
    printf 'The cold-restart attachment smoke requires API 36 exactly.\n' >&2
    exit 1
fi

role_state() {
    "${ADB[@]}" shell cmd role get-role-holders android.app.role.SMS \
        | tr -d '\r' \
        | sed '/^[[:space:]]*$/d'
}

permission_state() {
    local package_dump permission
    package_dump="$("${ADB[@]}" shell dumpsys package "$APP_PACKAGE")" || return 1
    for permission in "${PERMISSIONS[@]}"; do
        if rg --fixed-strings --quiet "$permission: granted=true" <<<"$package_dump"; then
            printf '%s=true\n' "$permission"
        elif rg --fixed-strings --quiet "$permission: granted=false" <<<"$package_dump"; then
            printf '%s=false\n' "$permission"
        else
            return 1
        fi
    done
}

target_path() {
    "${ADB[@]}" shell pm path "$APP_PACKAGE" \
        | tr -d '\r' \
        | sed -n 's/^package://p' \
        | head -n 1
}

target_matches_build() {
    local installed_path local_sha256 device_sha256
    installed_path="$(target_path)"
    if [[ -z "$installed_path" ]]; then
        return 1
    fi
    local_sha256="$(sha256sum "$APP_APK" | awk '{print $1}')"
    device_sha256="$("${ADB[@]}" shell sha256sum "$installed_path" | awk '{print $1}')"
    [[ "$device_sha256" == "$local_sha256" ]]
}

current_target_pid() {
    "${ADB[@]}" shell pidof "$APP_PACKAGE" | tr -d '\r' || true
}

force_stop_and_require_absent() {
    local attempt pid
    "${ADB[@]}" shell am force-stop "$APP_PACKAGE"
    for attempt in {1..40}; do
        pid="$(current_target_pid)"
        if [[ -z "$pid" ]]; then
            return 0
        fi
        sleep 0.1
    done
    printf 'AuroraSMS remained alive after host force-stop.\n' >&2
    return 1
}

start_prepared_target() {
    local attempt pid
    "${ADB[@]}" shell am start -W -n "$APP_PACKAGE/.MainActivity" >/dev/null
    pid=""
    for attempt in {1..40}; do
        pid="$(current_target_pid)"
        if [[ -n "$pid" ]]; then
            break
        fi
        sleep 0.1
    done
    if [[ -z "$pid" ]]; then
        printf 'AuroraSMS did not expose a live prepared process.\n' >&2
        return 1
    fi
    PREPARED_TARGET_PID="$pid"
    printf 'Prepared normal target process is live at PID %s.\n' "$pid"
}

force_stop_live_target() {
    local expected_pid current_pid
    expected_pid="$1"
    current_pid="$(current_target_pid)"
    if [[ -z "$expected_pid" || "$current_pid" != "$expected_pid" ]]; then
        printf 'The exact prepared target process was not alive before force-stop.\n' >&2
        return 1
    fi
    force_stop_and_require_absent
    printf 'Host force-stop removed prepared target PID %s.\n' "$expected_pid"
}

run_phase() (
    local phase output instrumentation_status zero_status_count
    phase="$1"
    output="$2"
    printf 'Running cold-restart attachment phase: %s\n' "$phase"
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
        printf 'Cold-restart attachment phase did not pass exactly one test: %s\n' \
            "$phase" >&2
        return 1
    fi
)

"$ROOT/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain

BEFORE_TARGET_PATH="$(target_path)"
if [[ -z "$BEFORE_TARGET_PATH" ]]; then
    printf '%s must already be installed before this preservation-safe smoke can run.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi
if ! target_matches_build; then
    printf 'The installed target APK does not match the attachment smoke build.\n' >&2
    printf 'Update it separately, verify preserved state, and rerun this script.\n' >&2
    exit 1
fi
BEFORE_ROLE="$(role_state)"
if ! BEFORE_PERMISSIONS="$(permission_state)"; then
    printf 'The installed AuroraSMS permission state could not be captured safely.\n' >&2
    exit 1
fi

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
PREPARED_TARGET_PID=""

cleanup() {
    local status=$?
    local cleanup_ok role_after permissions_after test_path_after
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
    if [[ "$cleanup_ok" -ne 1 ]]; then
        status=1
        printf 'Exact attachment recovery cleanup failed; preserving test APK and logs.\n' >&2
    elif [[ "$TEST_INSTALLED" -eq 1 ]]; then
        "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    fi
    test_path_after="$(
        "${ADB[@]}" shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
    )"
    if [[ "$cleanup_ok" -eq 1 && -n "$test_path_after" ]]; then
        printf 'The instrumentation package remained installed after cleanup.\n' >&2
        status=1
    elif [[ "$cleanup_ok" -ne 1 && -z "$test_path_after" ]]; then
        printf 'The failed recovery path unexpectedly lost its test package.\n' >&2
        status=1
    fi
    if ! target_matches_build; then
        printf 'The target APK was removed or changed by attachment cleanup.\n' >&2
        status=1
    fi
    role_after="$(role_state)"
    if ! permissions_after="$(permission_state)"; then
        printf 'The post-smoke AuroraSMS permission state could not be captured.\n' >&2
        status=1
    elif [[ "$permissions_after" != "$BEFORE_PERMISSIONS" ]]; then
        printf 'The attachment smoke changed AuroraSMS permission state.\n' >&2
        status=1
    fi
    if [[ "$role_after" != "$BEFORE_ROLE" ]]; then
        printf 'The attachment smoke changed the SMS role holder.\n' >&2
        status=1
    fi
    if [[ "$status" -eq 0 ]]; then
        rm -rf "$WORK"
        printf 'Attachment cleanup preserved the target APK, SMS role, and permissions.\n'
    else
        printf 'Cold-restart attachment logs retained at: %s\n' "$WORK" >&2
    fi
    exit "$status"
}
trap cleanup EXIT

printf 'Installing the isolated instrumentation APK.\n'
"${ADB[@]}" install -r -t "$TEST_APK" >/dev/null
TEST_INSTALLED=1

MUTATION_MAY_EXIST=1
force_stop_and_require_absent
run_phase cleanup "$WORK/preflight-cleanup.txt"
force_stop_and_require_absent
MUTATION_MAY_EXIST=0
CLEANUP_COMPLETE=1

MUTATION_MAY_EXIST=1
CLEANUP_COMPLETE=0
run_phase prepare "$WORK/prepare.txt"
start_prepared_target
force_stop_live_target "$PREPARED_TARGET_PID"

run_phase verify "$WORK/verify.txt"
force_stop_and_require_absent

run_phase cleanup "$WORK/cleanup.txt"
CLEANUP_COMPLETE=1
MUTATION_MAY_EXIST=0
force_stop_and_require_absent

printf 'Cold-restart attachment prepare, verification, and cleanup passed exactly one test each.\n'
