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
LOCK_SERIAL="${DEVICE_SERIAL//[![:alnum:]._-]/_}"
LOCK_FILE="${TMPDIR:-/tmp}/aurorasms-wallpaper-saf-${LOCK_SERIAL}.lock"
exec 9>"$LOCK_FILE"
if ! flock --nonblock 9; then
    printf 'Another AuroraSMS SAF smoke owns emulator %s.\n' "$DEVICE_SERIAL" >&2
    exit 1
fi

ROOT="$(git rev-parse --show-toplevel)"
APP_PACKAGE="org.aurorasms.app"
TEST_PACKAGE="org.aurorasms.app.test"
DOCUMENTS_UI_PACKAGE="com.android.documentsui"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.aurorasms.app.appearance.wallpaper.MainActivityStaticWallpaperSafFallbackSmokeTest"
TEST_METHOD="realGlobalThreadSafFallbackSelectionLifecycleRestoresBaseline"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
GATE_ARGUMENT="auroraEmulatorWallpaperSafSelection"
SELECTION_SENTINEL_KEY="auroraSafSelectionResult"
SELECTION_SENTINEL_VALUE="pass"
SELECTION_SENTINEL_STATUS_CODE=42
INSTRUMENTATION_TIMEOUT_SECONDS=180
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
    android.permission.READ_CONTACTS
)

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested SAF-selection emulator is not authorized: %s\n' "$DEVICE_SERIAL" >&2
    exit 1
fi

IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
HARDWARE="$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')"
if [[ "$DEVICE_SERIAL" != emulator-* || "$IS_EMULATOR" != "1" ]]; then
    printf 'The wallpaper SAF-selection smoke refuses physical device: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi
if [[ "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" ]]; then
    printf 'The wallpaper SAF-selection smoke requires a ranchu or goldfish emulator.\n' >&2
    exit 1
fi

SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$SDK_LEVEL" != "26" ]]; then
    printf 'The wallpaper SAF-selection smoke requires API 26 exactly.\n' >&2
    exit 1
fi

POWER_STATE="$("${ADB[@]}" shell dumpsys power | tr -d '\r')"
if ! rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$POWER_STATE"; then
    printf 'Wake and unlock the API 26 emulator before running the SAF-selection smoke.\n' >&2
    exit 1
fi

ACTIVE_TEST_PIDS="$(
    "${ADB[@]}" shell pidof "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
)"
if [[ -n "$ACTIVE_TEST_PIDS" ]]; then
    printf '%s has an active process on %s; stop it before this smoke.\n' \
        "$TEST_PACKAGE" "$DEVICE_SERIAL" >&2
    exit 1
fi

default_sms_state() {
    "${ADB[@]}" shell settings get secure sms_default_application \
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

focused_package() {
    "${ADB[@]}" shell dumpsys window windows \
        | tr -d '\r' \
        | sed -n 's/.*mCurrentFocus=.* u[0-9][0-9]* \([^/[:space:]]*\)\/.*/\1/p' \
        | head -n 1
}

dismiss_documents_ui_if_focused() {
    local attempt
    for attempt in {1..3}; do
        if [[ "$(focused_package)" != "$DOCUMENTS_UI_PACKAGE" ]]; then
            return 0
        fi
        "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null || return 1
        sleep 0.1
    done
    [[ "$(focused_package)" != "$DOCUMENTS_UI_PACKAGE" ]]
}

"$ROOT/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain

BEFORE_TARGET_PATH="$(target_path)"
if [[ -z "$BEFORE_TARGET_PATH" ]]; then
    printf '%s must already be installed before this preservation-checking smoke can run.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi
if ! target_matches_build; then
    printf 'The installed target APK does not match the SAF-selection smoke build.\n' >&2
    printf 'Update it separately, verify preserved app state, and then rerun this script.\n' >&2
    exit 1
fi

BEFORE_DEFAULT_SMS="$(default_sms_state)"
if [[ "$BEFORE_DEFAULT_SMS" != "$APP_PACKAGE" ]]; then
    printf '%s must already be the legacy default SMS app on the API 26 emulator.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi
if ! BEFORE_PERMISSIONS="$(permission_state)"; then
    printf 'The installed AuroraSMS API 26 permission state could not be captured safely.\n' >&2
    exit 1
fi
if ! rg --fixed-strings --line-regexp \
    'android.permission.READ_SMS=true' <<<"$BEFORE_PERMISSIONS" >/dev/null; then
    printf '%s requires its owner-granted READ_SMS permission before the SAF smoke.\n' \
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

WORK="$(mktemp -d)"
INSTRUMENTATION_OUTPUT="$WORK/instrumentation.txt"
TEST_INSTALL_MAY_EXIST=0

cleanup() {
    local status=$?
    local default_sms_after permissions_after test_path_after
    trap - EXIT
    set +e

    if ! dismiss_documents_ui_if_focused; then
        printf 'AOSP DocumentsUI remained focused after bounded cleanup.\n' >&2
        status=1
    fi
    if [[ "$TEST_INSTALL_MAY_EXIST" -eq 1 ]]; then
        "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    fi
    test_path_after="$(
        "${ADB[@]}" shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true
    )"
    if [[ -n "$test_path_after" ]]; then
        printf 'The instrumentation package remained installed after cleanup.\n' >&2
        status=1
    fi
    if ! target_matches_build; then
        printf 'The target APK was removed or changed by SAF-selection cleanup.\n' >&2
        status=1
    fi
    default_sms_after="$(default_sms_state)"
    if ! permissions_after="$(permission_state)"; then
        printf 'The post-smoke AuroraSMS API 26 permission state could not be captured.\n' >&2
        status=1
    elif [[ "$permissions_after" != "$BEFORE_PERMISSIONS" ]]; then
        printf 'The SAF-selection smoke changed AuroraSMS permission state.\n' >&2
        status=1
    fi
    if [[ "$default_sms_after" != "$BEFORE_DEFAULT_SMS" ]]; then
        printf 'The SAF-selection smoke changed the legacy default SMS app.\n' >&2
        status=1
    fi

    if [[ "$status" -eq 0 ]]; then
        rm -rf "$WORK"
        printf 'SAF-selection cleanup preserved the target APK, default SMS app, and permissions.\n'
    else
        printf 'SAF-selection evidence logs retained at: %s\n' "$WORK" >&2
    fi
    exit "$status"
}
trap cleanup EXIT

printf 'Installing the isolated instrumentation APK with its one synthetic SAF root.\n'
TEST_INSTALL_MAY_EXIST=1
"${ADB[@]}" install -r -t "$TEST_APK" >/dev/null

if [[ "$(default_sms_state)" != "$BEFORE_DEFAULT_SMS" ]] || \
    [[ "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'Installing the test APK changed target default-SMS or permission state.\n' >&2
    exit 1
fi

set +e
timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell am instrument -w -r \
    -e class "$TEST_TARGET" \
    -e "$GATE_ARGUMENT" true \
    "$TEST_PACKAGE/$TEST_RUNNER" | tee "$INSTRUMENTATION_OUTPUT"
INSTRUMENTATION_STATUS="${PIPESTATUS[0]}"
set -e

ZERO_STATUS_COUNT="$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$INSTRUMENTATION_OUTPUT" || true)"
SENTINEL_STATUS_COUNT="$(
    rg --count "^INSTRUMENTATION_STATUS_CODE: $SELECTION_SENTINEL_STATUS_CODE$" \
        "$INSTRUMENTATION_OUTPUT" || true
)"
SENTINEL_VALUE_COUNT="$(
    rg --count \
        "^INSTRUMENTATION_STATUS: $SELECTION_SENTINEL_KEY=$SELECTION_SENTINEL_VALUE$" \
        "$INSTRUMENTATION_OUTPUT" || true
)"
if [[ "$INSTRUMENTATION_STATUS" -ne 0 ]] || \
    rg --quiet \
        'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
        "$INSTRUMENTATION_OUTPUT" || \
    [[ "$ZERO_STATUS_COUNT" -ne 1 ]] || \
    [[ "$SENTINEL_STATUS_COUNT" -ne 1 ]] || \
    [[ "$SENTINEL_VALUE_COUNT" -ne 1 ]] || \
    ! rg --quiet '^OK \(1 test\)$' "$INSTRUMENTATION_OUTPUT" || \
    ! rg --quiet '^INSTRUMENTATION_CODE: -1$' "$INSTRUMENTATION_OUTPUT"; then
    printf 'The API 26 wallpaper SAF-selection instrumentation did not pass exactly once.\n' >&2
    exit 1
fi

printf 'API 26 real DocumentsUI selection, transient lifecycle, Apply/load, and Reset passed.\n'
