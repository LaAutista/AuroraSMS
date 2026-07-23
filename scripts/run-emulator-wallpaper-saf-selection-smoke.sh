#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s --device SERIAL [--journey selection|stale-apply|notification-pending-intent]\n' \
        "$0" >&2
}

if [[ "${1:-}" != "--device" || -z "${2:-}" ]]; then
    usage
    exit 2
fi
if [[ $# -ne 2 && $# -ne 4 ]]; then
    usage
    exit 2
fi

DEVICE_SERIAL="$2"
JOURNEY="selection"
if [[ $# -eq 4 ]]; then
    if [[ "${3:-}" != "--journey" || -z "${4:-}" ]]; then
        usage
        exit 2
    fi
    JOURNEY="$4"
fi
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
SENTINEL_VALUE="pass"
NOTIFICATION_CLEANUP_TEST_METHOD="exactSyntheticNotificationPendingIntentCleanupOnly"
NOTIFICATION_CLEANUP_GATE_ARGUMENT="auroraEmulatorWallpaperSafNotificationCleanup"
NOTIFICATION_CLEANUP_SENTINEL_KEY="auroraSafNotificationCleanupResult"
NOTIFICATION_CLEANUP_SENTINEL_STATUS_CODE=45
NOTIFICATION_ID=8600027
NOTIFICATION_TAG="aurora-conversation:8600027"
SYSTEM_UI_NOTIFICATION_STACK_RESOURCE="com.android.systemui:id/notification_stack_scroller"
case "$JOURNEY" in
    selection)
        TEST_METHOD="realGlobalThreadSafFallbackSelectionLifecycleRestoresBaseline"
        GATE_ARGUMENT="auroraEmulatorWallpaperSafSelection"
        SENTINEL_KEY="auroraSafSelectionResult"
        SENTINEL_STATUS_CODE=42
        JOURNEY_LABEL="SAF-selection"
        PASS_DESCRIPTION="real DocumentsUI selection, transient lifecycle, Apply/load, and Reset"
        ;;
    stale-apply)
        TEST_METHOD="realGlobalThreadSafFallbackRouteLossAndStaleApplyPreserveAuthority"
        GATE_ARGUMENT="auroraEmulatorWallpaperSafStaleApply"
        SENTINEL_KEY="auroraSafStaleApplyResult"
        SENTINEL_STATUS_CODE=43
        JOURNEY_LABEL="SAF-stale-Apply"
        PASS_DESCRIPTION="real DocumentsUI route replacement, stale Apply, winner preservation, and Reset"
        ;;
    notification-pending-intent)
        TEST_METHOD="realGlobalThreadSafFallbackNotificationPendingIntentRouteLossPreservesBaseline"
        GATE_ARGUMENT="auroraEmulatorWallpaperSafNotificationPendingIntent"
        SENTINEL_KEY="auroraSafNotificationPendingIntentResult"
        SENTINEL_STATUS_CODE=44
        JOURNEY_LABEL="SAF-notification-PendingIntent"
        PASS_DESCRIPTION="real DocumentsUI selection, system notification tap, PendingIntent routing, and baseline restoration"
        ;;
    *)
        usage
        exit 2
        ;;
esac
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
INSTRUMENTATION_TIMEOUT_SECONDS=180
ADB_SHORT_TIMEOUT_SECONDS=8
ADB_UI_DUMP_TIMEOUT_SECONDS=15
ADB_INSTALL_TIMEOUT_SECONDS=60
ADB_UNINSTALL_TIMEOUT_SECONDS=20
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

package_pids() {
    local package="$1" output pid
    output="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell "pidof $package 2>/dev/null || true" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    for pid in $output; do
        [[ "$pid" =~ ^[0-9]+$ ]] || return 1
        printf '%s\n' "$pid"
    done
}

package_paths() {
    local package="$1" listing raw line present=0
    listing="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell pm list packages "$package" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    if [[ -n "$listing" ]]; then
        while IFS= read -r line; do
            [[ "$line" == package:* && -n "${line#package:}" ]] || return 1
            if [[ "${line#package:}" == "$package" ]]; then
                present=1
            fi
        done <<<"$listing"
    fi
    [[ "$present" -eq 1 ]] || return 0
    raw="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell pm path "$package" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    [[ -n "$raw" ]] || return 1
    while IFS= read -r line; do
        [[ "$line" == package:* && -n "${line#package:}" ]] || return 1
        printf '%s\n' "${line#package:}"
    done <<<"$raw"
}

if ! DEVICE_LIST="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        adb devices 2>/dev/null
)"; then
    printf 'Connected Android devices could not be queried safely.\n' >&2
    exit 1
fi
mapfile -t DEVICES < <(
    awk 'NR > 1 && $2 == "device" { print $1 }' <<<"$DEVICE_LIST"
)
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested %s emulator is not authorized: %s\n' \
        "$JOURNEY_LABEL" "$DEVICE_SERIAL" >&2
    exit 1
fi

if ! IS_EMULATOR="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r'
)" || ! HARDWARE="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.hardware 2>/dev/null | tr -d '\r'
)"; then
    printf 'The requested emulator identity could not be queried safely.\n' >&2
    exit 1
fi
if [[ "$DEVICE_SERIAL" != emulator-* || "$IS_EMULATOR" != "1" ]]; then
    printf 'The wallpaper %s smoke refuses physical device: %s\n' \
        "$JOURNEY_LABEL" "$DEVICE_SERIAL" >&2
    exit 1
fi
if [[ "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" ]]; then
    printf 'The wallpaper %s smoke requires a ranchu or goldfish emulator.\n' \
        "$JOURNEY_LABEL" >&2
    exit 1
fi

if ! SDK_LEVEL="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'
)"; then
    printf 'The requested emulator API level could not be queried safely.\n' >&2
    exit 1
fi
if [[ "$SDK_LEVEL" != "26" ]]; then
    printf 'The wallpaper %s smoke requires API 26 exactly.\n' "$JOURNEY_LABEL" >&2
    exit 1
fi

device_is_awake_and_unlocked() {
    local power_state keyguard_state
    power_state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys power 2>/dev/null | tr -d '\r'
    )" || return 1
    keyguard_state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys window policy 2>/dev/null | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$power_state" && \
        rg --fixed-strings --quiet 'isStatusBarKeyguard=false' <<<"$keyguard_state" && \
        rg --fixed-strings --quiet 'mIsShowing=false' <<<"$keyguard_state"
}

POWER_STATE="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell dumpsys power 2>/dev/null | tr -d '\r'
)" || POWER_STATE=""
if ! rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$POWER_STATE"; then
    printf 'Wake and unlock the API 26 emulator before running the %s smoke.\n' \
        "$JOURNEY_LABEL" >&2
    exit 1
fi
KEYGUARD_STATE="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell dumpsys window policy 2>/dev/null | tr -d '\r'
)" || KEYGUARD_STATE=""
if ! rg --fixed-strings --quiet 'isStatusBarKeyguard=false' <<<"$KEYGUARD_STATE" || \
    ! rg --fixed-strings --quiet 'mIsShowing=false' <<<"$KEYGUARD_STATE"; then
    printf 'Unlock the API 26 emulator before running the %s smoke.\n' \
        "$JOURNEY_LABEL" >&2
    exit 1
fi

if ! ACTIVE_TEST_PIDS="$(package_pids "$TEST_PACKAGE")"; then
    printf '%s process state could not be queried safely on %s.\n' \
        "$TEST_PACKAGE" "$DEVICE_SERIAL" >&2
    exit 1
fi
if [[ -n "$ACTIVE_TEST_PIDS" ]]; then
    printf '%s has an active process on %s; stop it before this smoke.\n' \
        "$TEST_PACKAGE" "$DEVICE_SERIAL" >&2
    exit 1
fi

default_sms_state() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell settings get secure sms_default_application \
        | tr -d '\r' \
        | sed '/^[[:space:]]*$/d'
}

permission_state() {
    local package_dump permission
    package_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys package "$APP_PACKAGE" 2>/dev/null
    )" || return 1
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
    package_paths "$APP_PACKAGE" | head -n 1
}

target_matches_build() {
    local installed_path local_sha256 device_sha256
    installed_path="$(target_path)" || return 1
    if [[ -z "$installed_path" ]]; then
        return 1
    fi
    local_sha256="$(sha256sum "$APP_APK" | awk '{print $1}')"
    device_sha256="$(
        timeout --foreground --kill-after=2s "${ADB_UI_DUMP_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell sha256sum "$installed_path" 2>/dev/null \
            | awk '{print $1}'
    )" || return 1
    [[ "$device_sha256" == "$local_sha256" ]]
}

focused_package() {
    local window_dump focused
    window_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys window windows 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    focused="$(
        sed -n 's/.*mCurrentFocus=.* u[0-9][0-9]* \([^/[:space:]]*\)\/.*/\1/p' \
            <<<"$window_dump" \
            | head -n 1
    )"
    [[ -n "$focused" ]] || return 1
    printf '%s\n' "$focused"
}

dismiss_documents_ui_if_focused() {
    local attempt focused stable_checks=0
    for attempt in {1..8}; do
        focused="$(focused_package)" || return 1
        if [[ "$focused" == "$DOCUMENTS_UI_PACKAGE" ]]; then
            stable_checks=0
            timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
                "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 \
                || return 1
        else
            stable_checks=$((stable_checks + 1))
            if [[ "$stable_checks" -ge 2 ]]; then
                return 0
            fi
        fi
        sleep 0.15
    done
    return 1
}

exact_synthetic_notification_state() {
    local notification_dump parsed_state
    notification_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys notification 2>/dev/null \
            | tr -d '\r'
    )" || {
        printf 'unknown\n'
        return
    }
    parsed_state="$(
        awk \
            -v package_token="pkg=$APP_PACKAGE" \
            -v id_token="id=$NOTIFICATION_ID" \
            -v tag_token="tag=$NOTIFICATION_TAG" \
            '
            function trimmed(line, result) {
                result = line
                sub(/^[[:space:]]*/, "", result)
                sub(/[[:space:]]*$/, "", result)
                return result
            }
            function has_token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) {
                    if (fields[i] == expected) {
                        return 1
                    }
                }
                return 0
            }
            {
                clean = trimmed($0)
                if (clean == "Notification List:") {
                    notification_list_count++
                    if (archive_count > 0) {
                        invalid_order = 1
                    }
                    in_active_list = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    if (!in_active_list) {
                        invalid_order = 1
                    }
                    in_active_list = 0
                    next
                }
                if (in_active_list && index(clean, "NotificationRecord(") == 1 &&
                    has_token(clean, package_token) &&
                    has_token(clean, id_token) &&
                    has_token(clean, tag_token)) {
                    exact_count++
                }
            }
            END {
                if (notification_list_count != 1 || archive_count != 1 || invalid_order) {
                    print "unknown"
                } else if (exact_count == 1) {
                    print "active"
                } else if (exact_count > 1) {
                    print "unknown"
                } else {
                    print "inactive"
                }
            }
            ' <<<"$notification_dump"
    )" || parsed_state="unknown"
    case "$parsed_state" in
        active|inactive) printf '%s\n' "$parsed_state" ;;
        *) printf 'unknown\n' ;;
    esac
}

collapse_notification_shade() {
    local attempt hierarchy stable_checks=0
    for attempt in {1..8}; do
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1 \
            || return 1
        sleep 0.15
        hierarchy="$(
            timeout --foreground --kill-after=2s "${ADB_UI_DUMP_TIMEOUT_SECONDS}s" \
                "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null
        )" || return 1
        if ! rg --fixed-strings --quiet '<?xml' <<<"$hierarchy" || \
            ! rg --fixed-strings --quiet '<hierarchy' <<<"$hierarchy" || \
            ! rg --fixed-strings --quiet '</hierarchy>' <<<"$hierarchy"; then
            return 1
        fi
        if rg --fixed-strings --quiet \
            "resource-id=\"$SYSTEM_UI_NOTIFICATION_STACK_RESOURCE\"" \
            <<<"$hierarchy"; then
            stable_checks=0
        else
            stable_checks=$((stable_checks + 1))
            if [[ "$stable_checks" -ge 2 ]]; then
                return 0
            fi
        fi
    done
    return 1
}

wait_for_exact_notification_absence() {
    local attempt state stable_checks=0
    for attempt in {1..8}; do
        state="$(exact_synthetic_notification_state)"
        if [[ "$state" == "inactive" ]]; then
            stable_checks=$((stable_checks + 1))
            if [[ "$stable_checks" -ge 3 ]]; then
                return 0
            fi
        else
            stable_checks=0
        fi
        sleep 0.15
    done
    return 1
}

device_pid_state() {
    local pid="$1" state
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell \
            "if [ -d /proc/$pid ]; then printf alive; else printf gone; fi" \
            2>/dev/null \
            | tr -d '\r'
    )" || return 1
    case "$state" in
        alive|gone) printf '%s\n' "$state" ;;
        *) return 1 ;;
    esac
}

force_stop_timed_out_instrumentation_and_wait() {
    local original_instrumentation_target_pids original_test_pids original_pids
    local attempt current_target_pids current_test_pids pid pid_state
    local all_gone stable_checks=0

    # AndroidJUnitRunner executes inside the target process on this API level.
    original_instrumentation_target_pids="$(package_pids "$APP_PACKAGE")" || return 1
    original_test_pids="$(package_pids "$TEST_PACKAGE")" || return 1
    original_pids="$original_instrumentation_target_pids $original_test_pids"

    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 \
        || return 1
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 \
        || return 1

    for attempt in {1..12}; do
        current_target_pids="$(package_pids "$APP_PACKAGE")" || return 1
        current_test_pids="$(package_pids "$TEST_PACKAGE")" || return 1
        all_gone=1
        if [[ -n "$current_target_pids" || -n "$current_test_pids" ]]; then
            all_gone=0
        fi
        for pid in $original_pids; do
            pid_state="$(device_pid_state "$pid")" || return 1
            if [[ "$pid_state" != "gone" ]]; then
                all_gone=0
            fi
        done
        if [[ "$all_gone" -eq 1 ]]; then
            stable_checks=$((stable_checks + 1))
            if [[ "$stable_checks" -ge 2 ]]; then
                return 0
            fi
        else
            stable_checks=0
        fi
        sleep 0.2
    done
    return 1
}

recover_exact_synthetic_notification() {
    local cleanup_output cleanup_status
    cleanup_output="$WORK/notification-cleanup.txt"
    timeout --foreground --kill-after=5s 45s \
        "${ADB[@]}" shell am instrument -w -r \
        -e class "$TEST_CLASS#$NOTIFICATION_CLEANUP_TEST_METHOD" \
        -e "$NOTIFICATION_CLEANUP_GATE_ARGUMENT" true \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$cleanup_output" >/dev/null
    cleanup_status="${PIPESTATUS[0]}"
    [[ "$cleanup_status" -eq 0 ]] && \
        rg --quiet \
            "^INSTRUMENTATION_STATUS_CODE: $NOTIFICATION_CLEANUP_SENTINEL_STATUS_CODE$" \
            "$cleanup_output" && \
        rg --quiet \
            "^INSTRUMENTATION_STATUS: $NOTIFICATION_CLEANUP_SENTINEL_KEY=$SENTINEL_VALUE$" \
            "$cleanup_output" && \
        rg --quiet '^OK \(1 test\)$' "$cleanup_output"
}

"$ROOT/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain

if ! BEFORE_TARGET_PATH="$(target_path)"; then
    printf '%s installed path could not be queried safely.\n' "$APP_PACKAGE" >&2
    exit 1
fi
if [[ -z "$BEFORE_TARGET_PATH" ]]; then
    printf '%s must already be installed before this preservation-checking smoke can run.\n' \
        "$APP_PACKAGE" >&2
    exit 1
fi
if ! target_matches_build; then
    printf 'The installed target APK does not match the %s smoke build.\n' \
        "$JOURNEY_LABEL" >&2
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

if ! BEFORE_TEST_PATH="$(package_paths "$TEST_PACKAGE")"; then
    printf '%s installed state could not be queried safely.\n' "$TEST_PACKAGE" >&2
    exit 1
fi
if [[ -n "$BEFORE_TEST_PATH" ]]; then
    printf '%s is already installed; remove or preserve it explicitly before this smoke.\n' \
        "$TEST_PACKAGE" >&2
    exit 1
fi

WORK="$(mktemp -d)"
INSTRUMENTATION_OUTPUT="$WORK/instrumentation.txt"
TEST_INSTALL_MAY_EXIST=0
NOTIFICATION_RECOVERY_ARMED=0

cleanup() {
    local status=$?
    local default_sms_after permissions_after test_path_after notification_state
    trap - EXIT
    set +e

    if [[ "$JOURNEY" == "notification-pending-intent" ]]; then
        if ! collapse_notification_shade; then
            printf 'The AOSP notification shade did not collapse after bounded cleanup.\n' >&2
            status=1
        fi
        if ! dismiss_documents_ui_if_focused; then
            printf 'AOSP DocumentsUI remained focused after bounded cleanup.\n' >&2
            status=1
        fi
        notification_state="$(exact_synthetic_notification_state)"
        if [[ "$notification_state" == "active" ]]; then
            if [[ "$NOTIFICATION_RECOVERY_ARMED" -eq 1 ]]; then
                printf 'Recovering the exact synthetic AuroraSMS notification after abnormal exit.\n' >&2
                if ! recover_exact_synthetic_notification; then
                    printf 'Exact synthetic AuroraSMS notification recovery instrumentation failed.\n' >&2
                    status=1
                fi
            else
                printf 'The exact synthetic AuroraSMS notification was active before recovery was armed.\n' >&2
                status=1
            fi
        fi
        if ! wait_for_exact_notification_absence; then
            printf 'The exact synthetic AuroraSMS notification was not proven stably absent.\n' >&2
            status=1
        fi
    elif ! dismiss_documents_ui_if_focused; then
        printf 'AOSP DocumentsUI remained focused after bounded cleanup.\n' >&2
        status=1
    fi
    if [[ "$TEST_INSTALL_MAY_EXIST" -eq 1 ]]; then
        if ! timeout --foreground --kill-after=3s "${ADB_UNINSTALL_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1; then
            printf 'The instrumentation package uninstall command did not complete safely.\n' >&2
            status=1
        fi
    fi
    if ! test_path_after="$(package_paths "$TEST_PACKAGE")"; then
        printf 'The instrumentation package uninstall state could not be queried safely.\n' >&2
        status=1
    elif [[ -n "$test_path_after" ]]; then
        printf 'The instrumentation package remained installed after cleanup.\n' >&2
        status=1
    fi
    if [[ "$JOURNEY" == "notification-pending-intent" ]]; then
        if ! wait_for_exact_notification_absence; then
            printf 'The exact synthetic AuroraSMS notification reappeared after cleanup.\n' >&2
            status=1
        fi
    fi
    if ! target_matches_build; then
        printf 'The target APK was removed or changed by %s cleanup.\n' \
            "$JOURNEY_LABEL" >&2
        status=1
    fi
    default_sms_after="$(default_sms_state)"
    if ! permissions_after="$(permission_state)"; then
        printf 'The post-smoke AuroraSMS API 26 permission state could not be captured.\n' >&2
        status=1
    elif [[ "$permissions_after" != "$BEFORE_PERMISSIONS" ]]; then
        printf 'The %s smoke changed AuroraSMS permission state.\n' "$JOURNEY_LABEL" >&2
        status=1
    fi
    if [[ "$default_sms_after" != "$BEFORE_DEFAULT_SMS" ]]; then
        printf 'The %s smoke changed the legacy default SMS app.\n' "$JOURNEY_LABEL" >&2
        status=1
    fi

    if [[ "$status" -eq 0 ]]; then
        rm -rf "$WORK"
        printf '%s cleanup preserved the target APK, default SMS app, and permissions.\n' \
            "$JOURNEY_LABEL"
    else
        printf '%s evidence logs retained at: %s\n' "$JOURNEY_LABEL" "$WORK" >&2
    fi
    exit "$status"
}
trap cleanup EXIT

printf 'Installing the isolated instrumentation APK with its one synthetic SAF root.\n'
TEST_INSTALL_MAY_EXIST=1
if ! timeout --foreground --kill-after=3s "${ADB_INSTALL_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" install -r -t "$TEST_APK" >/dev/null; then
    printf 'The isolated instrumentation APK install did not complete safely.\n' >&2
    exit 1
fi

if [[ "$(default_sms_state)" != "$BEFORE_DEFAULT_SMS" ]] || \
    [[ "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'Installing the test APK changed target default-SMS or permission state.\n' >&2
    exit 1
fi

if [[ "$JOURNEY" == "notification-pending-intent" ]]; then
    PREFLIGHT_NOTIFICATION_STATE="$(exact_synthetic_notification_state)"
    if [[ "$PREFLIGHT_NOTIFICATION_STATE" != "inactive" ]] || \
        ! wait_for_exact_notification_absence; then
        printf 'The exact synthetic AuroraSMS notification identity must be inactive before this smoke.\n' >&2
        exit 1
    fi
fi
if ! device_is_awake_and_unlocked; then
    printf 'The API 26 emulator must remain awake and unlocked immediately before instrumentation.\n' >&2
    exit 1
fi
if [[ "$JOURNEY" == "notification-pending-intent" ]]; then
    NOTIFICATION_RECOVERY_ARMED=1
fi

set +e
timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell am instrument -w -r \
    -e class "$TEST_TARGET" \
    -e "$GATE_ARGUMENT" true \
    "$TEST_PACKAGE/$TEST_RUNNER" | tee "$INSTRUMENTATION_OUTPUT"
INSTRUMENTATION_STATUS="${PIPESTATUS[0]}"
set -e

if [[ "$INSTRUMENTATION_STATUS" -eq 124 || "$INSTRUMENTATION_STATUS" -eq 137 ]]; then
    printf 'The API 26 wallpaper %s instrumentation timed out; force-stopping its device processes.\n' \
        "$JOURNEY_LABEL" >&2
    if ! force_stop_timed_out_instrumentation_and_wait; then
        printf 'Timed-out instrumentation processes were not proven quiescent.\n' >&2
        NOTIFICATION_RECOVERY_ARMED=0
    fi
fi

ZERO_STATUS_COUNT="$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$INSTRUMENTATION_OUTPUT" || true)"
SENTINEL_STATUS_COUNT="$(
    rg --count "^INSTRUMENTATION_STATUS_CODE: $SENTINEL_STATUS_CODE$" \
        "$INSTRUMENTATION_OUTPUT" || true
)"
SENTINEL_VALUE_COUNT="$(
    rg --count \
        "^INSTRUMENTATION_STATUS: $SENTINEL_KEY=$SENTINEL_VALUE$" \
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
    printf 'The API 26 wallpaper %s instrumentation did not pass exactly once.\n' \
        "$JOURNEY_LABEL" >&2
    exit 1
fi

printf 'API 26 %s passed.\n' "$PASS_DESCRIPTION"
