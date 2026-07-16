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
TEST_CLASS="org.aurorasms.app.appearance.wallpaper.MainActivityStaticWallpaperPhysicalSmokeTest"
TEST_METHOD="realGlobalThreadPickerCancelBackApplyAndResetRestoreBaseline"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
PHYSICAL_GATE="auroraPhysicalWallpaperPickerSmoke"
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
    printf 'Requested physical-smoke device is not authorized: %s\n' "$DEVICE_SERIAL" >&2
    exit 1
fi

IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$DEVICE_SERIAL" == emulator-* || "$IS_EMULATOR" == "1" ]]; then
    printf 'The wallpaper picker physical smoke refuses emulator serial: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$SDK_LEVEL" =~ ^[0-9]+$ || "$SDK_LEVEL" -lt 33 ]]; then
    printf 'The wallpaper picker physical smoke requires API 33 or newer.\n' >&2
    exit 1
fi

AUDIO_STATE="$("${ADB[@]}" shell dumpsys audio | tr -d '\r')"
if rg --quiet 'Actual mode = MODE_(IN_CALL|IN_COMMUNICATION|RINGTONE)' <<<"$AUDIO_STATE"; then
    printf 'The physical smoke refuses to run while a call or ringtone owns audio mode.\n' >&2
    exit 1
fi

TELEPHONY_CALL_STATES="$(
    "${ADB[@]}" shell \
        "dumpsys telephony.registry | grep -Eo 'mCallState=[0-9]+' | cut -d= -f2 | sort -u" \
        | tr -d '\r'
)"
if [[ -z "$TELEPHONY_CALL_STATES" ]]; then
    printf 'The physical smoke could not capture a content-free telephony call state.\n' >&2
    exit 1
fi
if rg --quiet '^[12]$' <<<"$TELEPHONY_CALL_STATES"; then
    printf 'The physical smoke refuses to run while telephony reports ringing or off-hook.\n' >&2
    exit 1
fi

POWER_STATE="$("${ADB[@]}" shell dumpsys power | tr -d '\r')"
if ! rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$POWER_STATE"; then
    printf 'Wake and unlock the physical device before running the wallpaper picker smoke.\n' \
        >&2
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

BEFORE_TARGET_PATH="$(target_path)"
if [[ -z "$BEFORE_TARGET_PATH" ]]; then
    printf '%s must already be installed before this preservation-safe smoke can run.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi

BEFORE_ROLE="$(role_state)"
if [[ "$BEFORE_ROLE" != "$APP_PACKAGE" ]]; then
    printf '%s must be the sole owner-selected SMS role holder before the physical smoke.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi

if ! BEFORE_PERMISSIONS="$(permission_state)"; then
    printf 'The installed AuroraSMS permission state could not be captured safely.\n' >&2
    exit 1
fi
if ! rg --fixed-strings --line-regexp \
    'android.permission.READ_SMS=true' <<<"$BEFORE_PERMISSIONS" >/dev/null; then
    printf '%s requires its owner-granted READ_SMS permission before the physical smoke.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi

BEFORE_TEST_PATH="$(
    "${ADB[@]}" shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
)"
if [[ -n "$BEFORE_TEST_PATH" ]]; then
    printf '%s is already installed; remove or preserve it explicitly before this smoke.\n' \
        "$TEST_PACKAGE" >&2
    exit 1
fi

"$ROOT/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain

if ! target_matches_build; then
    printf 'The installed target APK does not match the physical-smoke build.\n' >&2
    printf 'Update it separately, verify preserved app state, and then rerun this script.\n' >&2
    exit 1
fi
printf 'The installed target APK already matches the physical-smoke build.\n'
if [[ "$(role_state)" != "$BEFORE_ROLE" || "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'The owner role or permission state changed during preflight; refusing instrumentation.\n' \
        >&2
    exit 1
fi

WORK="$(mktemp -d)"
INSTRUMENTATION_OUTPUT="$WORK/instrumentation.txt"

cleanup() {
    local status=$?
    local role_after permissions_after test_path_after
    trap - EXIT
    set +e

    "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    test_path_after="$("${ADB[@]}" shell pm path "$TEST_PACKAGE" | tr -d '\r')"
    if [[ -n "$test_path_after" ]]; then
        printf 'The instrumentation package remained installed after cleanup.\n' >&2
        status=1
    fi
    if ! target_matches_build; then
        printf 'The target APK was removed or changed by physical-smoke cleanup.\n' >&2
        status=1
    fi
    role_after="$(role_state)"
    if ! permissions_after="$(permission_state)"; then
        printf 'The post-smoke AuroraSMS permission state could not be captured.\n' >&2
        status=1
    elif [[ "$permissions_after" != "$BEFORE_PERMISSIONS" ]]; then
        printf 'The physical smoke changed AuroraSMS permission state.\n' >&2
        status=1
    fi
    if [[ "$role_after" != "$BEFORE_ROLE" ]]; then
        printf 'The physical smoke changed the SMS role holder.\n' >&2
        status=1
    fi
    rm -rf "$WORK"

    if [[ "$status" -eq 0 ]]; then
        printf 'Physical smoke cleanup preserved the target APK, SMS role, and permissions.\n'
    fi
    exit "$status"
}
trap cleanup EXIT

printf 'Installing the isolated instrumentation APK.\n'
"${ADB[@]}" install -r -t "$TEST_APK" >/dev/null

set +e
"${ADB[@]}" shell am instrument -w -r \
    -e class "$TEST_TARGET" \
    -e "$PHYSICAL_GATE" true \
    "$TEST_PACKAGE/$TEST_RUNNER" | tee "$INSTRUMENTATION_OUTPUT"
INSTRUMENTATION_STATUS="${PIPESTATUS[0]}"
set -e

if [[ "$INSTRUMENTATION_STATUS" -ne 0 ]] || \
    rg --quiet \
        'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
        "$INSTRUMENTATION_OUTPUT" || \
    [[ "$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$INSTRUMENTATION_OUTPUT")" -ne 1 ]] || \
    ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$INSTRUMENTATION_OUTPUT"; then
    printf 'The physical wallpaper picker instrumentation did not pass exactly one test.\n' >&2
    exit 1
fi

printf 'Physical wallpaper picker instrumentation passed exactly one test.\n'
