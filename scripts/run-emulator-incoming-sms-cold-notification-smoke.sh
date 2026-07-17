#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s [--journey cold-notification|multiple-message|notification-denied] [--port EVEN_PORT]\n' \
        "$0" >&2
}

JOURNEY="cold-notification"
PORT=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --journey)
            [[ $# -ge 2 && -n "${2:-}" ]] || { usage; exit 2; }
            JOURNEY="$2"
            shift 2
            ;;
        --port)
            [[ $# -ge 2 && -n "${2:-}" ]] || { usage; exit 2; }
            PORT="$2"
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

case "$JOURNEY" in
    cold-notification)
        DEFAULT_PORT=5580
        AVD_NAME="AuroraSMS_SMSRX_API26"
        AVD_TARGET="android-26"
        AVD_IMAGE="system-images/android-26/default/x86_64/"
        EXPECTED_SDK="26"
        API_LABEL="API 26"
        TEST_METHOD="realModemSmsTraversesReceiverProviderOrchestratorAndColdNotificationRoute"
        GATE_ARGUMENT="auroraEmulatorIncomingSmsColdNotification"
        PHASE_ARGUMENT="auroraEmulatorIncomingSmsColdNotificationPhase"
        PREPARE_STATUS_CODE=46
        VERIFY_STATUS_CODE=47
        CLEANUP_STATUS_CODE=48
        PREPARE_SENTINEL="auroraIncomingSmsPrepareResult"
        VERIFY_SENTINEL="auroraIncomingSmsVerifyResult"
        CLEANUP_SENTINEL="auroraIncomingSmsCleanupResult"
        INCOMING_BODY="AuroraSMS modem delivery 900017"
        FIRST_INCOMING_BODY=""
        ;;
    multiple-message)
        DEFAULT_PORT=5584
        AVD_NAME="AuroraSMS_SMSRX_API26"
        AVD_TARGET="android-26"
        AVD_IMAGE="system-images/android-26/default/x86_64/"
        EXPECTED_SDK="26"
        API_LABEL="API 26"
        TEST_METHOD="twoRealModemSmsShareOneColdNotificationThread"
        GATE_ARGUMENT="auroraEmulatorIncomingSmsMultipleMessage"
        PHASE_ARGUMENT="auroraEmulatorIncomingSmsMultipleMessagePhase"
        PREPARE_STATUS_CODE=52
        VERIFY_STATUS_CODE=53
        CLEANUP_STATUS_CODE=54
        PREPARE_SENTINEL="auroraMultipleMessagePrepareResult"
        VERIFY_SENTINEL="auroraMultipleMessageVerifyResult"
        CLEANUP_SENTINEL="auroraMultipleMessageCleanupResult"
        FIRST_INCOMING_BODY="AuroraSMS modem delivery 900017"
        INCOMING_BODY="AuroraSMS modem delivery 900018"
        ;;
    notification-denied)
        DEFAULT_PORT=5582
        AVD_NAME="AuroraSMS_SMSRX_API36"
        AVD_TARGET="android-36"
        AVD_IMAGE="system-images/android-36/default/x86_64/"
        EXPECTED_SDK="36"
        API_LABEL="API 36"
        TEST_METHOD="realModemSmsRemainsReadableWhenNotificationPermissionIsDenied"
        GATE_ARGUMENT="auroraEmulatorIncomingSmsNotificationDenied"
        PHASE_ARGUMENT="auroraEmulatorIncomingSmsNotificationDeniedPhase"
        PREPARE_STATUS_CODE=49
        VERIFY_STATUS_CODE=50
        CLEANUP_STATUS_CODE=51
        PREPARE_SENTINEL="auroraNotificationDeniedPrepareResult"
        VERIFY_SENTINEL="auroraNotificationDeniedVerifyResult"
        CLEANUP_SENTINEL="auroraNotificationDeniedCleanupResult"
        INCOMING_BODY="AuroraSMS modem delivery marker-alpha"
        FIRST_INCOMING_BODY=""
        ;;
    *)
        usage
        exit 2
        ;;
esac

PORT="${PORT:-$DEFAULT_PORT}"
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 5554 || PORT > 5584 || PORT % 2 != 0 )); then
    printf 'The owned emulator port must be even and between 5554 and 5584.\n' >&2
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
EMULATOR_BIN="/home/kek/Desktop/FLUORINE/android-sdk/emulator/emulator"
ADB_BIN="/home/kek/Desktop/FLUORINE/android-sdk/platform-tools/adb"
DEVICE_SERIAL="emulator-$PORT"
APP_PACKAGE="org.aurorasms.app"
TEST_PACKAGE="org.aurorasms.app.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.aurorasms.app.message.IncomingSmsColdNotificationSmokeTest"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
SENTINEL_VALUE="pass"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
INCOMING_PDU="00040B915155210310F70000627071220400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BDD00"
SECOND_INCOMING_PDU="00040B915155210310F70000627071221400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BE100"
INCOMING_SENDER="15551230017"
INCOMING_MODEM_SENDER="+15551230017"
EMITTED_PDU_ARGUMENT="auroraEmulatorIncomingSmsEmittedPduHex"
JOURNAL_PATH="shared_prefs/aurora_sms_delivery_journal_v1.xml"
PDU_CAPTURE_PATH="shared_prefs/aurora_incoming_sms_pdu_capture_v1.xml"
PDU_CAPTURE_ARM_ACTIVITY="org.aurorasms.app.message.IncomingSmsPduCaptureArmActivity"
SYSTEM_UI_PACKAGE="com.android.systemui"
SYSTEM_UI_PANEL_RESOURCE="$SYSTEM_UI_PACKAGE:id/notification_panel"
SYSTEM_UI_STACK_RESOURCE="$SYSTEM_UI_PACKAGE:id/notification_stack_scroller"
GENERIC_NOTIFICATION_TITLE="AuroraSMS"
GENERIC_NOTIFICATION_BODY="New message"
APP_THREAD_RESOURCE="aurora-thread-screen"
APP_INBOX_RESOURCE="aurora-inbox-screen"
APP_MESSAGE_RESOURCE="aurora-message-bubble"
APP_INBOX_ROW_RESOURCE="aurora-inbox-row"
APP_NOTIFICATION_NOTICE_RESOURCE="aurora-notification-permission-notice"
APP_NOTIFICATION_ACTION_RESOURCE="aurora-notification-permission-cta"
PERMISSION_CONTROLLER_PACKAGE="com.android.permissioncontroller"
PERMISSION_DENY_RESOURCE="$PERMISSION_CONTROLLER_PACKAGE:id/permission_deny_and_dont_ask_again_button"
SETTINGS_PACKAGE="com.android.settings"
SETTINGS_MAIN_SWITCH_RESOURCE="$SETTINGS_PACKAGE:id/main_switch_bar"
SETTINGS_SWITCH_WIDGET_RESOURCE="android:id/switch_widget"
POST_NOTIFICATIONS_PERMISSION="android.permission.POST_NOTIFICATIONS"
OPEN_CONVERSATION_ACTION="org.aurorasms.app.action.OPEN_CONVERSATION"
CONVERSATION_CATEGORY_PREFIX="org.aurorasms.core.notifications.category.CONVERSATION."

BUILD_TIMEOUT_SECONDS=900
EMULATOR_BOOT_TIMEOUT_SECONDS=180
INSTRUMENTATION_TIMEOUT_SECONDS=120
ADB_SHORT_TIMEOUT_SECONDS=8
ADB_UI_TIMEOUT_SECONDS=15
ADB_INSTALL_TIMEOUT_SECONDS=60
DELIVERY_TIMEOUT_SECONDS=30
ROUTE_TIMEOUT_SECONDS=30

PERMISSIONS=(
    android.permission.READ_SMS
    android.permission.SEND_SMS
    android.permission.RECEIVE_SMS
    android.permission.RECEIVE_MMS
    android.permission.RECEIVE_WAP_PUSH
    android.permission.READ_PHONE_STATE
    android.permission.READ_CONTACTS
)

for required_tool in \
    "$EMULATOR_BIN" "$ADB_BIN" flock rg xmllint timeout sha256sum awk sed ps tee tr; do
    if [[ "$required_tool" == */* ]]; then
        if [[ ! -x "$required_tool" ]]; then
            printf 'Required executable is unavailable: %s\n' "$required_tool" >&2
            exit 1
        fi
    elif ! command -v "$required_tool" >/dev/null 2>&1; then
        printf 'Required host tool is unavailable: %s\n' "$required_tool" >&2
        exit 1
    fi
done

exact_ini_value() {
    local file="$1" key="$2" values
    [[ -f "$file" ]] || return 1
    values="$(
        awk -v key="$key" '
            index($0, key "=") == 1 { print substr($0, length(key) + 2) }
        ' "$file"
    )" || return 1
    [[ -n "$values" && "$values" != *$'\n'* ]] || return 1
    printf '%s\n' "$values"
}

AVD_POINTER="$HOME/.android/avd/$AVD_NAME.ini"
AVD_PATH="$(exact_ini_value "$AVD_POINTER" path)" || {
    printf 'The dedicated receive-test AVD pointer is unavailable or ambiguous.\n' >&2
    exit 1
}
AVD_CONFIG="$AVD_PATH/config.ini"
if [[ "$(exact_ini_value "$AVD_POINTER" target)" != "$AVD_TARGET" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" target)" != "$AVD_TARGET" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" image.sysdir.1)" != "$AVD_IMAGE" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" tag.id)" != "default" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" abi.type)" != "x86_64" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" PlayStore.enabled)" != "no" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" hw.gsmModem)" != "yes" ]]; then
    printf 'The dedicated receive-test AVD is not the canonical non-Play %s GSM image.\n' \
        "$API_LABEL" >&2
    exit 1
fi

LOCK_FILE="${TMPDIR:-/tmp}/aurorasms-owned-incoming-sms-${PORT}.lock"
exec 9>"$LOCK_FILE"
if ! flock --nonblock 9; then
    printf 'Another owned incoming-SMS smoke already reserves port %s.\n' "$PORT" >&2
    exit 1
fi

if ! AVD_LIST="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "$EMULATOR_BIN" -list-avds 2>/dev/null
)" || ! rg --fixed-strings --line-regexp --quiet "$AVD_NAME" <<<"$AVD_LIST"; then
    printf 'The required AVD is unavailable: %s\n' "$AVD_NAME" >&2
    exit 1
fi

if ! PRELAUNCH_DEVICE_LIST="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "$ADB_BIN" devices 2>/dev/null
)"; then
    printf 'ADB device state could not be queried safely before launch.\n' >&2
    exit 1
fi
if awk -v serial="$DEVICE_SERIAL" 'NR > 1 && $1 == serial { found = 1 } END { exit !found }' \
    <<<"$PRELAUNCH_DEVICE_LIST"; then
    printf '%s is already allocated; refusing to touch that existing emulator.\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

printf 'Building the exact target and instrumentation APKs before launching the owned overlay.\n'
timeout --foreground --kill-after=10s "${BUILD_TIMEOUT_SECONDS}s" \
    "$ROOT/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest \
    --offline --no-daemon --no-parallel --console=plain
if [[ ! -f "$APP_APK" || ! -f "$TEST_APK" ]]; then
    printf 'The exact target or instrumentation APK was not produced.\n' >&2
    exit 1
fi

WORK="$(mktemp -d)"
EMULATOR_LOG="$WORK/emulator.log"
ADB=("$ADB_BIN" -s "$DEVICE_SERIAL")
EMULATOR_PID=""
EMULATOR_OWNED=0
DEVICE_READY=0
TEST_INSTALLED=0
RECOVERY_REQUIRED=0
CLEANUP_COMPLETE=0
EXPECTED_NOTIFICATION_TAG=""
EXPECTED_NOTIFICATION_ID=""
PROVIDER_ID=""
FIRST_PROVIDER_ID=""
CONVERSATION_ID=""
FIRST_CONVERSATION_ID=""
DELIVERY_SUBSCRIPTION_ID=""
RECEIVER_PID=""
EMITTED_PDU_HEX=""
INJECTION_NOT_BEFORE_MILLIS=""
ROUTE_PID=""

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

single_package_pid() {
    local package="$1" pids
    pids="$(package_pids "$package")" || return 1
    [[ "$pids" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$pids"
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

package_stopped_state() {
    local package="$1" package_dump states
    package_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys package "$package" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    states="$(rg --only-matching 'stopped=(true|false)' <<<"$package_dump" || true)"
    [[ "$states" == "stopped=true" || "$states" == "stopped=false" ]] || return 1
    printf '%s\n' "${states#stopped=}"
}

wait_for_package_stopped_state() {
    local package="$1" expected="$2" deadline state
    [[ "$expected" == "true" || "$expected" == "false" ]] || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if state="$(package_stopped_state "$package")" && [[ "$state" == "$expected" ]]; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

wait_for_single_target_pid() {
    local deadline pid
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if pid="$(single_package_pid "$APP_PACKAGE")"; then
            printf '%s\n' "$pid"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

wait_for_exact_pid_absence() {
    local expected_pid="$1" deadline current stable=0 state
    deadline=$((SECONDS + 12))
    while (( SECONDS < deadline )); do
        current="$(package_pids "$APP_PACKAGE")" || return 1
        state="$(device_pid_state "$expected_pid")" || return 1
        if [[ -z "$current" && "$state" == "gone" ]]; then
            stable=$((stable + 1))
            if (( stable >= 3 )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep 0.2
    done
    return 1
}

wait_for_all_target_processes_absent() {
    local deadline current stable=0
    deadline=$((SECONDS + 12))
    while (( SECONDS < deadline )); do
        current="$(package_pids "$APP_PACKAGE")" || return 1
        if [[ -z "$current" ]]; then
            stable=$((stable + 1))
            if (( stable >= 3 )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep 0.2
    done
    return 1
}

task_dump_has_app_identity() {
    local dump="$1"
    if rg --fixed-strings --quiet "$APP_PACKAGE/" <<<"$dump"; then
        return 0
    fi
    awk -v package="$APP_PACKAGE" '
        /^[[:space:]]*\* (Recent #[0-9]+: )?Task\{/ &&
        index($0, " type=standard A=") {
            needle = ":" package
            position = index($0, needle)
            if (position > 0) {
                suffix = substr($0, position + length(needle), 1)
                if (suffix == "" || suffix ~ /[[:space:]}]/) found = 1
            }
        }
        /^[[:space:]]*affinity=[0-9]+:/ {
            affinity = $0
            sub(/^[[:space:]]*affinity=[0-9]+:/, "", affinity)
            if (affinity == package) found = 1
        }
        END { exit found ? 0 : 1 }
    ' <<<"$dump"
}

app_active_task_is_absent() {
    local activities
    activities="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER ACTIVITIES' <<<"$activities" || return 1
    ! task_dump_has_app_identity "$activities"
}

app_task_is_absent() {
    local recents
    app_active_task_is_absent || return 1
    recents="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity recents 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER RECENT TASKS' <<<"$recents" || return 1
    ! task_dump_has_app_identity "$recents"
}

wait_for_app_task_absence() {
    local deadline stable=0
    deadline=$((SECONDS + 12))
    while (( SECONDS < deadline )); do
        if app_task_is_absent; then
            stable=$((stable + 1))
            if (( stable >= 3 )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep 0.2
    done
    return 1
}

app_root_task_id() {
    local activities ids
    activities="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER ACTIVITIES' <<<"$activities" || return 1
    ids="$(
        awk -v package="$APP_PACKAGE" '
            /^[[:space:]]*\* Task\{/ &&
            index($0, " type=standard A=") &&
            index($0, ":" package " ") {
                id = $0
                sub(/^.* #/, "", id)
                sub(/ type=standard A=.*$/, "", id)
                if (id ~ /^[0-9]+$/ && !seen[id]++) print id
            }
        ' <<<"$activities"
    )" || return 1
    [[ "$ids" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$ids"
}

app_task_is_present() {
    local activities
    activities="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER ACTIVITIES' <<<"$activities" && \
        rg --fixed-strings --quiet "$APP_PACKAGE/" <<<"$activities"
}

focused_package() {
    local window_dump activity_dump focused
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
    if [[ -z "$focused" ]]; then
        activity_dump="$(
            timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
                "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
                | tr -d '\r'
        )" || return 1
        focused="$(
            sed -n \
                's/.*topResumedActivity=ActivityRecord{.* u[0-9][0-9]* \([^/[:space:]]*\)\/.*/\1/p' \
                <<<"$activity_dump" \
                | head -n 1
        )"
    fi
    [[ -n "$focused" ]] || return 1
    printf '%s\n' "$focused"
}

main_activity_is_focused_and_resumed() {
    local window_dump activity_dump
    window_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys window windows 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    activity_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    if rg --quiet \
        "mCurrentFocus=.* u0 $APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
        <<<"$window_dump" && \
        rg --quiet \
            "mResumedActivity:.* $APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
            <<<"$activity_dump"; then
        return 0
    fi
    rg --quiet \
        "topResumedActivity=ActivityRecord.* u0 $APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
        <<<"$activity_dump"
}

device_is_awake_and_unlocked() {
    local power_state keyguard_state
    power_state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys power 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    keyguard_state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys window policy 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$power_state" || return 1
    rg --fixed-strings --quiet 'mIsShowing=false' <<<"$keyguard_state" || return 1
    if rg --fixed-strings --quiet 'isStatusBarKeyguard=false' <<<"$keyguard_state"; then
        return 0
    fi
    rg --quiet '^[[:space:]]+showing=false$' <<<"$keyguard_state" && \
        rg --quiet '^[[:space:]]+inputRestricted=false$' <<<"$keyguard_state"
}

ensure_awake_and_unlocked() {
    local attempt
    for attempt in {1..20}; do
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
        if device_is_awake_and_unlocked; then
            return 0
        fi
        sleep 0.3
    done
    return 1
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

test_receive_sms_is_granted() {
    local package_dump
    package_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys package "$TEST_PACKAGE" 2>/dev/null
    )" || return 1
    rg --fixed-strings --quiet \
        'android.permission.RECEIVE_SMS: granted=true' <<<"$package_dump"
}

default_sms_state() {
    if [[ "$EXPECTED_SDK" == "26" ]]; then
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell settings get secure sms_default_application 2>/dev/null \
            | tr -d '\r' \
            | sed '/^[[:space:]]*$/d'
    else
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell cmd role get-role-holders android.app.role.SMS 2>/dev/null \
            | tr -d '\r' \
            | sed '/^[[:space:]]*$/d'
    fi
}

grant_legacy_default_sms_via_dialog() {
    local deadline xml button_count button_bounds values left top right bottom
    [[ "$(default_sms_state)" != "$APP_PACKAGE" ]] || return 0
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am start -W \
        -a android.provider.Telephony.ACTION_CHANGE_DEFAULT \
        --es package "$APP_PACKAGE" >/dev/null 2>&1 || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if xml="$(ui_hierarchy_xml)"; then
            button_count="$(
                xpath_value "$xml" \
                    "count(//node[@resource-id='android:id/button1' and @text='YES' and @enabled='true' and @clickable='true'])"
            )" || button_count=""
            if [[ "$button_count" == "1" ]]; then
                button_bounds="$(
                    xpath_value "$xml" \
                        "string((//node[@resource-id='android:id/button1' and @text='YES' and @enabled='true' and @clickable='true'])[1]/@bounds)"
                )" || return 1
                values="$(parse_bounds "$button_bounds")" || return 1
                IFS=',' read -r left top right bottom <<<"$values"
                (( left < right && top < bottom )) || return 1
                timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
                    "${ADB[@]}" shell input touchscreen tap \
                    "$(((left + right) / 2))" "$(((top + bottom) / 2))" \
                    >/dev/null 2>&1 || return 1
                break
            fi
        fi
        sleep 0.2
    done
    [[ "${button_count:-}" == "1" ]] || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        [[ "$(default_sms_state)" == "$APP_PACKAGE" ]] && return 0
        sleep 0.2
    done
    return 1
}

grant_modern_default_sms_role() {
    local deadline holders
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell cmd role add-role-holder \
        android.app.role.SMS "$APP_PACKAGE" >/dev/null 2>&1 || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        holders="$(default_sms_state)" || return 1
        [[ "$holders" == "$APP_PACKAGE" ]] && return 0
        sleep 0.2
    done
    return 1
}

console_sms_modem_is_ready() {
    local sms_help gsm_status sms_ok_count gsm_ok_count
    sms_help="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" emu help sms 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    gsm_status="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" emu gsm status 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'allows you to simulate an inbound SMS' <<<"$sms_help" || return 1
    rg --quiet '^[[:space:]]*sms send[[:space:]]+send inbound SMS text message$' \
        <<<"$sms_help" || return 1
    rg --quiet '^[[:space:]]*sms pdu[[:space:]]+send inbound SMS PDU$' \
        <<<"$sms_help" || return 1
    rg --quiet '^gsm voice state:[[:space:]]+(home|roaming)$' <<<"$gsm_status" || return 1
    rg --quiet '^gsm data state:[[:space:]]+(home|roaming)$' <<<"$gsm_status" || return 1
    sms_ok_count="$(rg --count '^OK$' <<<"$sms_help" || true)"
    gsm_ok_count="$(rg --count '^OK$' <<<"$gsm_status" || true)"
    [[ "$sms_ok_count" == "1" && "$gsm_ok_count" == "1" ]]
}

test_pdu_capture_xml() {
    local xml
    xml="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" exec-out run-as "$TEST_PACKAGE" cat "$PDU_CAPTURE_PATH" \
            2>/dev/null
    )" || return 1
    [[ -n "$xml" ]] || return 1
    xmllint --nonet --noout - <<<"$xml" 2>/dev/null || return 1
    printf '%s' "$xml"
}

test_pdu_capture_is_armed_empty() {
    local xml total armed count armed_at
    xml="$(test_pdu_capture_xml)" || return 1
    total="$(xmllint --nonet --xpath 'count(/map/*)' - <<<"$xml" 2>/dev/null)" || return 1
    armed="$(xmllint --nonet --xpath 'string(/map/boolean[@name="armed"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    count="$(xmllint --nonet --xpath 'string(/map/int[@name="capture_count"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    armed_at="$(xmllint --nonet --xpath 'string(/map/long[@name="armed_at_millis"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    [[ "$total" == "3" && "$armed" == "true" && "$count" == "0" ]] || return 1
    [[ "$armed_at" =~ ^[0-9]+$ ]] && (( armed_at > 0 ))
}

arm_test_pdu_capture() {
    local launch deadline
    launch="$(
        timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell am start -W \
            -n "$TEST_PACKAGE/$PDU_CAPTURE_ARM_ACTIVITY" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'Status: ok' <<<"$launch" || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if [[ "$(package_stopped_state "$TEST_PACKAGE")" == "false" ]] && \
            test_pdu_capture_is_armed_empty; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

single_test_pdu_capture() {
    local xml total armed count format pdu armed_at received_at now_seconds
    xml="$(test_pdu_capture_xml)" || return 1
    total="$(xmllint --nonet --xpath 'count(/map/*)' - <<<"$xml" 2>/dev/null)" || return 1
    armed="$(xmllint --nonet --xpath 'string(/map/boolean[@name="armed"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    count="$(xmllint --nonet --xpath 'string(/map/int[@name="capture_count"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    format="$(xmllint --nonet --xpath 'string(/map/string[@name="format"])' - <<<"$xml" 2>/dev/null)" || return 1
    pdu="$(xmllint --nonet --xpath 'string(/map/string[@name="pdu_hex"])' - <<<"$xml" 2>/dev/null)" || return 1
    armed_at="$(xmllint --nonet --xpath 'string(/map/long[@name="armed_at_millis"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    received_at="$(xmllint --nonet --xpath 'string(/map/long[@name="received_at_millis"]/@value)' - <<<"$xml" 2>/dev/null)" || return 1
    [[ "$total" == "6" && "$armed" == "false" && "$count" == "1" ]] || return 1
    [[ "$format" == "3gpp" && "$pdu" =~ ^[0-9a-f]+$ ]] || return 1
    (( ${#pdu} >= 40 && ${#pdu} <= 524288 && ${#pdu} % 2 == 0 )) || return 1
    [[ "$armed_at" =~ ^[0-9]+$ && "$received_at" =~ ^[0-9]+$ ]] || return 1
    (( armed_at > 0 && received_at >= armed_at )) || return 1
    if [[ -n "$INJECTION_NOT_BEFORE_MILLIS" ]]; then
        [[ "$INJECTION_NOT_BEFORE_MILLIS" =~ ^[0-9]+$ ]] || return 1
        (( received_at >= INJECTION_NOT_BEFORE_MILLIS )) || return 1
    fi
    now_seconds="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell date +%s 2>/dev/null | tr -d '\r'
    )" || return 1
    [[ "$now_seconds" =~ ^[0-9]+$ ]] || return 1
    (( received_at <= now_seconds * 1000 + 5000 )) || return 1
    printf '%s\n' "$pdu"
}

wait_for_single_test_pdu_capture() {
    local deadline pdu
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if pdu="$(single_test_pdu_capture)"; then
            printf '%s\n' "$pdu"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

delete_test_pdu_capture() {
    local state
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell run-as "$TEST_PACKAGE" rm -f "$PDU_CAPTURE_PATH" \
        >/dev/null 2>&1 || return 1
    state="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell run-as "$TEST_PACKAGE" sh -c \
            "'if [ -e \"$PDU_CAPTURE_PATH\" ]; then printf present; else printf absent; fi'" \
            2>/dev/null | tr -d '\r'
    )" || return 1
    [[ "$state" == "absent" ]]
}

clear_process_start_event_window() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" logcat -b events -c >/dev/null 2>&1
}

receiver_process_start_pid() {
    process_start_pid_for_receiver \
        "org.aurorasms.core.telephony.receiver.SmsDeliverReceiver"
}

process_start_pid_for_receiver() {
    local receiver="$1"
    local events pids
    events="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" logcat -b events -d -v raw -s am_proc_start:I 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    pids="$(
        awk -F',' -v package="$APP_PACKAGE" -v receiver="$receiver" '
            {
                for (field = 1; field <= NF; field++) {
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", $field)
                }
                if ($1 == "[0" && $2 ~ /^[0-9]+$/ && $4 == package &&
                    $5 == "broadcast" && $6 == "{" package "/" receiver "}]") {
                    if (!seen[$2]++) print $2
                }
            }
        ' <<<"$events"
    )" || return 1
    [[ "$pids" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$pids"
}

wait_for_receiver_process_start() {
    local receiver="$1" deadline pid
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if pid="$(process_start_pid_for_receiver "$receiver")"; then
            printf '%s\n' "$pid"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

target_cold_boundary_is_exact() {
    local pids stopped
    wait_for_all_target_processes_absent || return 1
    pids="$(package_pids "$APP_PACKAGE")" || return 1
    [[ -z "$pids" ]] || return 1
    stopped="$(package_stopped_state "$APP_PACKAGE")" || return 1
    [[ "$stopped" == "false" ]] || return 1
    app_task_is_absent || return 1
    pids="$(package_pids "$APP_PACKAGE")" || return 1
    [[ -z "$pids" ]]
}

notification_route_intent_is_exact() {
    local activities category
    category="$CONVERSATION_CATEGORY_PREFIX$CONVERSATION_ID"
    activities="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER ACTIVITIES' <<<"$activities" && \
        rg --fixed-strings --quiet "act=$OPEN_CONVERSATION_ACTION" <<<"$activities" && \
        rg --fixed-strings --quiet "$category" <<<"$activities" && \
        rg --quiet "$APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
            <<<"$activities"
}

package_matches_build() {
    local package="$1" apk="$2" paths installed_path local_sha device_sha
    paths="$(package_paths "$package")" || return 1
    [[ "$paths" != *$'\n'* && -n "$paths" ]] || return 1
    installed_path="$paths"
    local_sha="$(sha256sum "$apk" | awk '{print $1}')" || return 1
    device_sha="$(
        timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell sha256sum "$installed_path" 2>/dev/null \
            | awk '{print $1}'
    )" || return 1
    [[ "$local_sha" =~ ^[0-9a-f]{64}$ && "$device_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$device_sha" == "$local_sha" ]]
}

run_phase() (
    local phase="$1" output="$2" expected_code expected_key
    local instrumentation_status zero_count code_count key_count ok_count final_count
    local -a emitted_pdu_args=()
    if [[ -n "${EMITTED_PDU_HEX:-}" ]]; then
        emitted_pdu_args=(-e "$EMITTED_PDU_ARGUMENT" "$EMITTED_PDU_HEX")
    fi
    case "$phase" in
        prepare)
            expected_code="$PREPARE_STATUS_CODE"
            expected_key="$PREPARE_SENTINEL"
            ;;
        verify)
            expected_code="$VERIFY_STATUS_CODE"
            expected_key="$VERIFY_SENTINEL"
            ;;
        cleanup)
            expected_code="$CLEANUP_STATUS_CODE"
            expected_key="$CLEANUP_SENTINEL"
            ;;
        *)
            return 2
            ;;
    esac
    printf 'Running owned %s incoming-SMS instrumentation phase: %s\n' \
        "$JOURNEY" "$phase"
    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am instrument -w -r \
        -e class "$TEST_TARGET" \
        -e "$GATE_ARGUMENT" true \
        -e "$PHASE_ARGUMENT" "$phase" \
        "${emitted_pdu_args[@]}" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$output"
    instrumentation_status="${PIPESTATUS[0]}"
    set -e
    zero_count="$(rg --count '^INSTRUMENTATION_STATUS_CODE: 0$' "$output" || true)"
    code_count="$(rg --count "^INSTRUMENTATION_STATUS_CODE: $expected_code$" "$output" || true)"
    key_count="$(
        rg --count "^INSTRUMENTATION_STATUS: $expected_key=$SENTINEL_VALUE$" \
            "$output" || true
    )"
    ok_count="$(rg --count '^OK \(1 test\)$' "$output" || true)"
    final_count="$(rg --count '^INSTRUMENTATION_CODE: -1$' "$output" || true)"
    if [[ "$instrumentation_status" -ne 0 ]] || \
        rg --quiet \
            'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' \
            "$output" || \
        [[ "$zero_count" -ne 1 || "$code_count" -ne 1 || "$key_count" -ne 1 ]] || \
        [[ "$ok_count" -ne 1 || "$final_count" -ne 1 ]]; then
        printf 'Owned incoming-SMS phase did not pass exactly once: %s\n' "$phase" >&2
        return 1
    fi
)

journal_xml() {
    local exists xml
    if ! exists="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell \
            "run-as '$APP_PACKAGE' sh -c 'if [ -f \"$JOURNAL_PATH\" ]; then printf yes; else printf no; fi'" \
            2>/dev/null \
            | tr -d '\r'
    )"; then
        return 1
    fi
    case "$exists" in
        no)
            printf '<map />'
            return 0
            ;;
        yes) ;;
        *) return 1 ;;
    esac
    xml="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" exec-out run-as "$APP_PACKAGE" cat "$JOURNAL_PATH" \
            2>/dev/null
    )" || return 1
    [[ -n "$xml" ]] || return 1
    xmllint --nonet --noout - <<<"$xml" 2>/dev/null || return 1
    printf '%s' "$xml"
}

journal_entry_count() {
    local xml count
    xml="$(journal_xml)" || return 1
    count="$(
        xmllint --nonet --xpath \
            'count(/map/string[starts-with(@name,"delivery.")])' - \
            <<<"$xml" 2>/dev/null
    )" || return 1
    [[ "$count" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$count"
}

complete_journal_identity() {
    local xml count value
    local -a fields
    xml="$(journal_xml)" || return 1
    count="$(
        xmllint --nonet --xpath \
            'count(/map/string[starts-with(@name,"delivery.")])' - \
            <<<"$xml" 2>/dev/null
    )" || return 1
    [[ "$count" == "1" ]] || return 1
    value="$(
        xmllint --nonet --xpath \
            'string(/map/string[starts-with(@name,"delivery.")][1])' - \
            <<<"$xml" 2>/dev/null
    )" || return 1
    IFS=',' read -r -a fields <<<"$value"
    [[ "${#fields[@]}" -eq 9 ]] || return 1
    [[ "${fields[0]}" == "2" && "${fields[2]}" == "C" ]] || return 1
    [[ "${fields[1]}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || return 1
    [[ "${fields[3]}" =~ ^[0-9]+$ && "${fields[4]}" =~ ^[0-9]+$ ]] || return 1
    (( fields[3] > 0 && fields[4] > 0 )) || return 1
    [[ "${fields[5]}" =~ ^[0-9]+$ && "${fields[6]}" =~ ^[0-9]+$ ]] || return 1
    [[ "${fields[7]}" =~ ^-?[0-9]+$ && "${fields[8]}" =~ ^[0-9]+$ ]] || return 1
    printf '%s|%s|%s\n' "${fields[3]}" "${fields[4]}" "${fields[7]}"
}

wait_for_complete_journal() {
    local deadline identity
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if identity="$(complete_journal_identity)"; then
            IFS='|' read -r PROVIDER_ID CONVERSATION_ID DELIVERY_SUBSCRIPTION_ID \
                <<<"$identity"
            [[ "$PROVIDER_ID" =~ ^[0-9]+$ && "$CONVERSATION_ID" =~ ^[0-9]+$ ]] || return 1
            [[ "$DELIVERY_SUBSCRIPTION_ID" =~ ^-?[0-9]+$ ]] || return 1
            return 0
        fi
        sleep 0.2
    done
    return 1
}

complete_multiple_journal_identity() {
    local xml count value index provider conversation subscription
    local first_provider="" second_provider="" common_conversation="" common_subscription=""
    local -a fields
    xml="$(journal_xml)" || return 1
    count="$(
        xmllint --nonet --xpath \
            'count(/map/string[starts-with(@name,"delivery.")])' - \
            <<<"$xml" 2>/dev/null
    )" || return 1
    [[ "$count" == "2" ]] || return 1
    for index in 1 2; do
        value="$(
            xmllint --nonet --xpath \
                "string(/map/string[starts-with(@name,\"delivery.\")][$index])" - \
                <<<"$xml" 2>/dev/null
        )" || return 1
        IFS=',' read -r -a fields <<<"$value"
        [[ "${#fields[@]}" -eq 9 ]] || return 1
        [[ "${fields[0]}" == "2" && "${fields[2]}" == "C" ]] || return 1
        [[ "${fields[1]}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || return 1
        provider="${fields[3]}"
        conversation="${fields[4]}"
        subscription="${fields[7]}"
        [[ "$provider" =~ ^[0-9]+$ && "$conversation" =~ ^[0-9]+$ ]] || return 1
        (( provider > 0 && conversation > 0 )) || return 1
        [[ "${fields[5]}" =~ ^[0-9]+$ && "${fields[6]}" =~ ^[0-9]+$ ]] || return 1
        [[ "$subscription" =~ ^-?[0-9]+$ && "${fields[8]}" =~ ^[0-9]+$ ]] || return 1
        if (( index == 1 )); then
            first_provider="$provider"
            common_conversation="$conversation"
            common_subscription="$subscription"
        else
            second_provider="$provider"
            [[ "$conversation" == "$common_conversation" ]] || return 1
            [[ "$subscription" == "$common_subscription" ]] || return 1
        fi
    done
    [[ "$first_provider" != "$second_provider" ]] || return 1
    printf '%s|%s|%s|%s\n' \
        "$first_provider" "$second_provider" "$common_conversation" "$common_subscription"
}

wait_for_multiple_complete_journals() {
    local deadline identity first second conversation subscription
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if identity="$(complete_multiple_journal_identity)"; then
            IFS='|' read -r first second conversation subscription <<<"$identity"
            [[ "$first" == "$FIRST_PROVIDER_ID" || "$second" == "$FIRST_PROVIDER_ID" ]] || return 1
            FIRST_PROVIDER_ID="$first"
            PROVIDER_ID="$second"
            CONVERSATION_ID="$conversation"
            DELIVERY_SUBSCRIPTION_ID="$subscription"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

notification_id_for_conversation() {
    local conversation_id="$1" folded
    [[ "$conversation_id" =~ ^[0-9]+$ ]] || return 1
    (( conversation_id > 0 )) || return 1
    folded=$(((conversation_id ^ (conversation_id >> 32)) & 0x7fffffff))
    (( folded != 0 )) || folded=1
    printf '%s\n' "$folded"
}

notification_dump() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell dumpsys notification --noredact 2>/dev/null \
        | tr -d '\r'
}

app_notification_count() {
    local dump parsed
    dump="$(notification_dump)" || return 1
    parsed="$(
        awk -v package_token="pkg=$APP_PACKAGE" '
            function trim(line, value) {
                value = line
                sub(/^[[:space:]]*/, "", value)
                sub(/[[:space:]]*$/, "", value)
                return value
            }
            function token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) if (fields[i] == expected) return 1
                return 0
            }
            {
                clean = trim($0)
                if (clean == "Notification List:") {
                    list_count++
                    if (archive_count > 0) invalid = 1
                    active = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    if (!active) invalid = 1
                    active = 0
                    next
                }
                if (active && index(clean, "NotificationRecord(") == 1 &&
                    token(clean, package_token)) app_count++
            }
            END {
                if (list_count != 1 || archive_count != 1 || invalid) print "unknown"
                else print app_count + 0
            }
        ' <<<"$dump"
    )" || return 1
    [[ "$parsed" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$parsed"
}

wait_for_zero_app_notifications() {
    local stable_required="$1" deadline count stable=0
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        count="$(app_notification_count)" || return 1
        if [[ "$count" == "0" ]]; then
            stable=$((stable + 1))
            if (( stable >= stable_required )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep 0.2
    done
    return 1
}

active_notification_id_for_tag() {
    local tag="$1" dump parsed
    dump="$(notification_dump)" || return 1
    parsed="$(
        awk \
            -v package_token="pkg=$APP_PACKAGE" \
            -v tag_token="tag=$tag" '
            function trim(line, value) {
                value = line
                sub(/^[[:space:]]*/, "", value)
                sub(/[[:space:]]*$/, "", value)
                return value
            }
            function token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) if (fields[i] == expected) return 1
                return 0
            }
            function id_value(line, fields, count, i, value) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) {
                    if (fields[i] ~ /^id=[0-9]+$/) {
                        value = fields[i]
                        sub(/^id=/, "", value)
                        return value
                    }
                }
                return ""
            }
            {
                clean = trim($0)
                if (clean == "Notification List:") {
                    list_count++
                    if (archive_count > 0) invalid = 1
                    active = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    if (!active) invalid = 1
                    active = 0
                    next
                }
                if (active && index(clean, "NotificationRecord(") == 1 &&
                    token(clean, package_token)) {
                    app_count++
                    if (token(clean, tag_token)) {
                        exact_count++
                        exact_id = id_value(clean)
                    }
                }
            }
            END {
                if (list_count != 1 || archive_count != 1 || invalid ||
                    app_count != 1 || exact_count != 1 || exact_id !~ /^[0-9]+$/) {
                    print "unknown"
                } else {
                    print exact_id
                }
            }
        ' <<<"$dump"
    )" || return 1
    [[ "$parsed" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$parsed"
}

active_notification_update_time() {
    local tag="$1" id="$2" dump parsed
    dump="$(notification_dump)" || return 1
    parsed="$(
        awk \
            -v package_token="pkg=$APP_PACKAGE" \
            -v tag_token="tag=$tag" \
            -v id_token="id=$id" '
            function trim(line, value) {
                value = line
                sub(/^[[:space:]]*/, "", value)
                sub(/[[:space:]]*$/, "", value)
                return value
            }
            function token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) if (fields[i] == expected) return 1
                return 0
            }
            {
                clean = trim($0)
                if (clean == "Notification List:") {
                    list_count++
                    active = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    active = 0
                    capture = 0
                    next
                }
                if (active && index(clean, "NotificationRecord(") == 1) {
                    capture = token(clean, package_token)
                    if (capture) {
                        app_count++
                        if (token(clean, tag_token) && token(clean, id_token)) exact_count++
                        else invalid = 1
                    }
                    next
                }
                if (capture && clean ~ /^mUpdateTimeMs=[0-9]+$/) {
                    update_count++
                    sub(/^mUpdateTimeMs=/, "", clean)
                    update_time = clean
                }
            }
            END {
                if (list_count != 1 || archive_count != 1 || invalid ||
                    app_count != 1 || exact_count != 1 || update_count != 1 ||
                    update_time !~ /^[0-9]+$/) print "unknown"
                else print update_time
            }
        ' <<<"$dump"
    )" || return 1
    [[ "$parsed" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$parsed"
}

wait_for_notification_update_after() {
    local before="$1" deadline current stable=0 last=""
    [[ "$before" =~ ^[0-9]+$ ]] || return 1
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if current="$(
            active_notification_update_time \
                "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID"
        )" && (( current > before )); then
            if [[ "$current" == "$last" ]]; then
                stable=$((stable + 1))
            else
                last="$current"
                stable=1
            fi
            if (( stable >= 3 )); then
                return 0
            fi
        else
            stable=0
            last=""
        fi
        sleep 0.2
    done
    return 1
}

notification_contract_reject() {
    printf 'Active notification contract rejected fixed stage: %s.\n' "$1" >&2
    return 1
}

active_notification_contract_is_exact() {
    local tag="$1" id="$2" dump record expected_actions
    local title_count body_count self_count template_count messages_count action_count
    [[ "$DELIVERY_SUBSCRIPTION_ID" =~ ^-?[0-9]+$ ]] || \
        notification_contract_reject subscription || return 1
    if (( DELIVERY_SUBSCRIPTION_ID >= 0 )); then
        expected_actions=1
    else
        expected_actions=0
    fi
    dump="$(notification_dump)" || {
        notification_contract_reject dump
        return 1
    }
    if rg --fixed-strings --quiet "$INCOMING_BODY" <<<"$dump"; then
        notification_contract_reject body-privacy
        return 1
    fi
    if [[ -n "$FIRST_INCOMING_BODY" ]] && \
        rg --fixed-strings --quiet "$FIRST_INCOMING_BODY" <<<"$dump"; then
        notification_contract_reject first-body-privacy
        return 1
    fi
    if rg --fixed-strings --quiet "$INCOMING_SENDER" <<<"$dump"; then
        notification_contract_reject sender-privacy
        return 1
    fi
    record="$(
        awk -v package_token="pkg=$APP_PACKAGE" '
            function trim(line, value) {
                value = line
                sub(/^[[:space:]]*/, "", value)
                sub(/[[:space:]]*$/, "", value)
                return value
            }
            function token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) if (fields[i] == expected) return 1
                return 0
            }
            {
                clean = trim($0)
                if (clean == "Notification List:") {
                    list_count++
                    active = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    active = 0
                    capture = 0
                    next
                }
                if (active && index(clean, "NotificationRecord(") == 1) {
                    capture = token(clean, package_token)
                    if (capture) app_count++
                }
                if (active && capture) print $0
            }
            END {
                if (list_count != 1 || archive_count != 1 || app_count != 1) exit 1
            }
        ' <<<"$dump"
    )" || {
        notification_contract_reject record-scope
        return 1
    }
    [[ -n "$record" ]] || {
        notification_contract_reject record-empty
        return 1
    }
    rg --quiet \
        "NotificationRecord\\(.* pkg=$APP_PACKAGE .* id=$id tag=$tag importance=4 " \
        <<<"$record" || {
        notification_contract_reject header
        return 1
    }
    rg --fixed-strings --quiet \
        "channel=aurora_messages_v1: Notification(channel=aurora_messages_v1 pri=1 contentView=null vibrate=null sound=null defaults=0x0 flags=0x18 color=0x00000000 category=msg actions=$expected_actions vis=PRIVATE publicVersion=Notification(" \
        <<<"$record" || {
        notification_contract_reject primary-notification
        return 1
    }
    rg --fixed-strings --quiet \
        'publicVersion=Notification(channel=aurora_messages_v1 pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x10 color=0x00000000 category=msg vis=PRIVATE)' \
        <<<"$record" || {
        notification_contract_reject public-version
        return 1
    }
    rg --quiet \
        "contentIntent=PendingIntent\\{.* PendingIntentRecord\\{.* $APP_PACKAGE startActivity \\(whitelist:" \
        <<<"$record" || {
        notification_contract_reject content-intent
        return 1
    }
    rg --quiet '^[[:space:]]+deleteIntent=null$' <<<"$record" || {
        notification_contract_reject delete-intent
        return 1
    }
    title_count="$(
        rg --fixed-strings --count 'android.title=String (AuroraSMS)' <<<"$record" || true
    )"
    body_count="$(
        rg --fixed-strings --count 'android.text=String (New message)' <<<"$record" || true
    )"
    self_count="$(
        rg --fixed-strings --count 'android.selfDisplayName=String (AuroraSMS)' \
            <<<"$record" || true
    )"
    template_count="$(
        rg --fixed-strings --count \
            'android.template=String (android.app.Notification$MessagingStyle)' \
            <<<"$record" || true
    )"
    messages_count="$(
        rg --fixed-strings --count 'android.messages=Parcelable[] (1)' <<<"$record" || true
    )"
    [[ "$title_count" =~ ^[1-9][0-9]*$ ]] || {
        notification_contract_reject generic-title
        return 1
    }
    [[ "$body_count" =~ ^[1-9][0-9]*$ ]] || {
        notification_contract_reject generic-body
        return 1
    }
    [[ "$self_count" =~ ^[1-9][0-9]*$ ]] || {
        notification_contract_reject generic-self-display
        return 1
    }
    [[ "$template_count" =~ ^[1-9][0-9]*$ && "$messages_count" =~ ^[1-9][0-9]*$ ]] || {
        notification_contract_reject messaging-style
        return 1
    }
    action_count="$(
        rg --count '^[[:space:]]+\[[0-9]+\] ".*" -> PendingIntent\{' <<<"$record" || true
    )"
    action_count="${action_count:-0}"
    [[ "$action_count" == "$expected_actions" ]] || {
        notification_contract_reject action-count
        return 1
    }
    if (( expected_actions == 1 )); then
        rg --quiet \
            "^[[:space:]]+\\[0\\] \"Reply\" -> PendingIntent\\{.* PendingIntentRecord\\{.* $APP_PACKAGE broadcastIntent " \
            <<<"$record" || {
            notification_contract_reject reply-action
            return 1
        }
    else
        if rg --fixed-strings --quiet ' broadcastIntent ' <<<"$record"; then
            notification_contract_reject unexpected-action
            return 1
        fi
    fi
}

exact_notification_state() {
    local tag="$1" id="$2" dump parsed
    dump="$(notification_dump)" || {
        printf 'unknown\n'
        return
    }
    parsed="$(
        awk \
            -v package_token="pkg=$APP_PACKAGE" \
            -v tag_token="tag=$tag" \
            -v id_token="id=$id" '
            function trim(line, value) {
                value = line
                sub(/^[[:space:]]*/, "", value)
                sub(/[[:space:]]*$/, "", value)
                return value
            }
            function token(line, expected, fields, count, i) {
                count = split(line, fields, /[[:space:]]+/)
                for (i = 1; i <= count; i++) if (fields[i] == expected) return 1
                return 0
            }
            {
                clean = trim($0)
                if (clean == "Notification List:") {
                    list_count++
                    if (archive_count > 0) invalid = 1
                    active = 1
                    next
                }
                if (clean ~ /^mArchive=/) {
                    archive_count++
                    if (!active) invalid = 1
                    active = 0
                    next
                }
                if (active && index(clean, "NotificationRecord(") == 1 &&
                    token(clean, package_token)) {
                    app_count++
                    if (token(clean, tag_token) && token(clean, id_token)) exact_count++
                }
            }
            END {
                if (list_count != 1 || archive_count != 1 || invalid) print "unknown"
                else if (app_count == 1 && exact_count == 1) print "active"
                else if (app_count == 0 && exact_count == 0) print "inactive"
                else print "unknown"
            }
        ' <<<"$dump"
    )" || parsed="unknown"
    case "$parsed" in
        active|inactive) printf '%s\n' "$parsed" ;;
        *) printf 'unknown\n' ;;
    esac
}

wait_for_exact_notification_state() {
    local expected="$1" stable_required="$2" deadline state stable=0
    deadline=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        state="$(exact_notification_state "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID")"
        if [[ "$state" == "$expected" ]]; then
            stable=$((stable + 1))
            if (( stable >= stable_required )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep 0.2
    done
    return 1
}

ui_hierarchy_xml() {
    local raw xml
    raw="$(
        timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null
    )" || return 1
    [[ "$raw" == *'<?xml'*'</hierarchy>'* ]] || return 1
    xml="<?xml${raw#*<?xml}"
    xml="${xml%%</hierarchy>*}</hierarchy>"
    xmllint --nonet --noout - <<<"$xml" 2>/dev/null || return 1
    printf '%s' "$xml"
}

xpath_value() {
    local xml="$1" expression="$2"
    xmllint --nonet --xpath "$expression" - <<<"$xml" 2>/dev/null
}

parse_bounds() {
    local bounds="$1"
    if [[ "$bounds" =~ ^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$ ]]; then
        printf '%s,%s,%s,%s\n' \
            "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" \
            "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"
    else
        return 1
    fi
}

tap_unique_resource() {
    local package="$1" resource="$2" xml count bounds values left top right bottom
    xml="$(ui_hierarchy_xml)" || return 1
    count="$(
        xpath_value "$xml" \
            "count(//node[@package='$package' and @resource-id='$resource' and @enabled='true' and @clickable='true'])"
    )" || return 1
    [[ "$count" == "1" ]] || return 1
    bounds="$(
        xpath_value "$xml" \
            "string((//node[@package='$package' and @resource-id='$resource' and @enabled='true' and @clickable='true'])[1]/@bounds)"
    )" || return 1
    values="$(parse_bounds "$bounds")" || return 1
    IFS=',' read -r left top right bottom <<<"$values"
    (( left < right && top < bottom )) || return 1
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell input touchscreen tap \
        "$(((left + right) / 2))" "$(((top + bottom) / 2))" \
        >/dev/null 2>&1
}

notification_notice_inbox_is_exact() {
    local xml="$1" notice_count action_count inbox_count thread_count
    local background_count body_count row_count
    notice_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_NOTICE_RESOURCE'])"
    )" || return 1
    action_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_ACTION_RESOURCE' and @enabled='true' and @clickable='true'])"
    )" || return 1
    inbox_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_RESOURCE'])"
    )" || return 1
    thread_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_THREAD_RESOURCE'])"
    )" || return 1
    background_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and contains(@text,'background') and contains(@text,'alerts')])"
    )" || return 1
    body_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])"
    )" || return 1
    row_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_ROW_RESOURCE'])"
    )" || return 1
    [[ "$notice_count" == "1" && "$action_count" == "1" ]] || return 1
    [[ "$inbox_count" == "1" && "$thread_count" == "0" ]] || return 1
    [[ "$background_count" == "1" && "$body_count" -le 1 && "$row_count" -le 1 ]]
}

wait_for_notification_notice_inbox() {
    local require_message="$1" deadline xml body_count row_count
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if main_activity_is_focused_and_resumed && xml="$(ui_hierarchy_xml)" && \
            notification_notice_inbox_is_exact "$xml"; then
            body_count="$(
                xpath_value "$xml" \
                    "count(//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])"
            )" || return 1
            row_count="$(
                xpath_value "$xml" \
                    "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_ROW_RESOURCE'])"
            )" || return 1
            if [[ "$require_message" == "true" &&
                "$body_count" == "1" && "$row_count" == "1" ]]; then
                return 0
            fi
            if [[ "$require_message" == "false" &&
                "$body_count" == "0" && "$row_count" == "0" ]]; then
                return 0
            fi
        fi
        sleep 0.2
    done
    return 1
}

granted_empty_inbox_is_exact() {
    local xml="$1" notice_count action_count inbox_count thread_count row_count body_count
    notice_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_NOTICE_RESOURCE'])"
    )" || return 1
    action_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_ACTION_RESOURCE'])"
    )" || return 1
    inbox_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_RESOURCE'])"
    )" || return 1
    thread_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_THREAD_RESOURCE'])"
    )" || return 1
    row_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_ROW_RESOURCE'])"
    )" || return 1
    body_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])"
    )" || return 1
    [[ "$notice_count" == "0" && "$action_count" == "0" ]] || return 1
    [[ "$inbox_count" == "1" && "$thread_count" == "0" ]] || return 1
    [[ "$row_count" == "0" && "$body_count" == "0" ]]
}

wait_for_granted_empty_inbox() {
    local deadline xml
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if main_activity_is_focused_and_resumed && xml="$(ui_hierarchy_xml)" && \
            granted_empty_inbox_is_exact "$xml"; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

permission_dialog_is_exact() {
    local xml="$1" deny_count app_name_count
    deny_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$PERMISSION_CONTROLLER_PACKAGE' and @resource-id='$PERMISSION_DENY_RESOURCE' and @enabled='true' and @clickable='true'])"
    )" || return 1
    app_name_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$PERMISSION_CONTROLLER_PACKAGE' and contains(@text,'AuroraSMS')])"
    )" || return 1
    [[ "$deny_count" == "1" && "$app_name_count" -ge 1 ]]
}

wait_for_permission_dialog() {
    local deadline xml
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if xml="$(ui_hierarchy_xml)" && permission_dialog_is_exact "$xml"; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

launch_notification_settings() {
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am start -W \
        -a android.settings.APP_NOTIFICATION_SETTINGS \
        --es android.provider.extra.APP_PACKAGE "$APP_PACKAGE" 2>/dev/null \
        | tr -d '\r'
}

notification_settings_page_is_exact() {
    local expected_checked="$1" activity_dump xml app_name_count
    local title_count main_switch_count switch_count
    [[ "$expected_checked" == "true" || "$expected_checked" == "false" ]] || return 2
    [[ "$(focused_package)" == "$SETTINGS_PACKAGE" ]] || return 1
    activity_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet \
        'act=android.settings.APP_NOTIFICATION_SETTINGS' \
        <<<"$activity_dump" || return 1
    xml="$(ui_hierarchy_xml)" || return 1
    app_name_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$SETTINGS_PACKAGE' and (@text='AuroraSMS' or @content-desc='AuroraSMS')])"
    )" || return 1
    title_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$SETTINGS_PACKAGE' and @text='All AuroraSMS notifications'])"
    )" || return 1
    main_switch_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$SETTINGS_PACKAGE' and @resource-id='$SETTINGS_MAIN_SWITCH_RESOURCE' and @enabled='true' and @clickable='true'])"
    )" || return 1
    switch_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$SETTINGS_PACKAGE' and @resource-id='$SETTINGS_SWITCH_WIDGET_RESOURCE' and @enabled='true' and @checkable='true' and @checked='$expected_checked'])"
    )" || return 1
    [[ "$app_name_count" == "1" && "$title_count" == "1" ]] || return 1
    [[ "$main_switch_count" == "1" && "$switch_count" == "1" ]]
}

wait_for_notification_settings_page() {
    local expected_checked="$1" deadline
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        notification_settings_page_is_exact "$expected_checked" && return 0
        sleep 0.2
    done
    return 1
}

notification_permission_denied_line() {
    local package_dump line
    package_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys package "$APP_PACKAGE" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    line="$(
        rg --fixed-strings "$POST_NOTIFICATIONS_PERMISSION: granted=false" \
            <<<"$package_dump" || true
    )"
    [[ -n "$line" && "$line" != *$'\n'* ]] || return 1
    printf '%s\n' "$line"
}

notification_permission_is_granted() {
    local package_dump line
    package_dump="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys package "$APP_PACKAGE" 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    line="$(
        rg --fixed-strings "$POST_NOTIFICATIONS_PERMISSION: granted=true" \
            <<<"$package_dump" || true
    )"
    [[ -n "$line" && "$line" != *$'\n'* ]]
}

notification_permission_is_user_denied() {
    local line
    line="$(notification_permission_denied_line)" || return 1
    rg --fixed-strings --quiet 'USER_SET' <<<"$line"
}

notification_permission_is_user_fixed_denied() {
    local line
    line="$(notification_permission_denied_line)" || return 1
    rg --fixed-strings --quiet 'USER_SET' <<<"$line" && \
        rg --fixed-strings --quiet 'USER_FIXED' <<<"$line"
}

establish_notification_denied_cold_boundary() {
    local focus task_id
    task_id="$(app_root_task_id)" || {
        printf 'The denied target did not own exactly one removable standard root task.\n' >&2
        return 1
    }
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am stack remove "$task_id" >/dev/null 2>&1 || {
        printf 'Failed to remove the exact denied target root task.\n' >&2
        return 1
    }
    wait_for_all_target_processes_absent || {
        printf 'The denied target process remained after exact root-task removal.\n' >&2
        return 1
    }
    [[ "$(package_stopped_state "$APP_PACKAGE")" == "false" ]] || {
        printf 'Exact root-task removal unexpectedly stopped the denied target package.\n' >&2
        return 1
    }
    wait_for_app_task_absence || {
        printf 'The denied target retained an activity or recent task after exact removal.\n' >&2
        return 1
    }
    focus="$(focused_package)" || {
        printf 'The focus owner could not be proven after exact root-task removal.\n' >&2
        return 1
    }
    [[ "$focus" != "$APP_PACKAGE" ]] || {
        printf 'The denied target retained focus after exact root-task removal.\n' >&2
        return 1
    }
    notification_permission_is_user_fixed_denied || {
        printf 'The denied notification permission lost its USER_SET or USER_FIXED state.\n' >&2
        return 1
    }
}

launch_main_activity() {
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am start -W -n "$APP_PACKAGE/.MainActivity" 2>/dev/null \
        | tr -d '\r'
}

thread_with_notice_is_exact() {
    local xml="$1" notice_count action_count screen_count inbox_count bubble_count body_count
    notice_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_NOTICE_RESOURCE'])"
    )" || return 1
    action_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_NOTIFICATION_ACTION_RESOURCE' and @enabled='true' and @clickable='true'])"
    )" || return 1
    screen_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_THREAD_RESOURCE'])"
    )" || return 1
    inbox_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_RESOURCE'])"
    )" || return 1
    bubble_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_MESSAGE_RESOURCE' and (@text='$INCOMING_BODY' or descendant::node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])])"
    )" || return 1
    body_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])"
    )" || return 1
    [[ "$notice_count" == "1" && "$action_count" == "1" ]] || return 1
    [[ "$screen_count" == "1" && "$inbox_count" == "0" ]] || return 1
    [[ "$bubble_count" == "1" && "$body_count" == "1" ]]
}

wait_for_thread_with_notice() {
    local deadline xml
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if main_activity_is_focused_and_resumed && xml="$(ui_hierarchy_xml)" && \
            thread_with_notice_is_exact "$xml"; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

display_size() {
    local output size
    output="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell wm size 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    size="$(sed -n 's/.*size: \([0-9][0-9]*x[0-9][0-9]*\).*/\1/p' <<<"$output" | tail -n 1)"
    [[ "$size" =~ ^([0-9]+)x([0-9]+)$ ]] || return 1
    (( BASH_REMATCH[1] > 0 && BASH_REMATCH[2] > 0 )) || return 1
    printf '%s,%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
}

shade_row_geometry() {
    local xml="$1" body_xpath row_xpath
    local panel_count stack_count body_count title_count private_count first_private_count sender_count
    local body_bounds row_bounds stack_bounds
    body_xpath="//node[@package='$SYSTEM_UI_PACKAGE' and @text='$GENERIC_NOTIFICATION_BODY' and (@resource-id='android:id/text' or @resource-id='android:id/big_text') and ancestor::node[@resource-id='$SYSTEM_UI_STACK_RESOURCE']]"
    row_xpath="($body_xpath)/ancestor::node[@clickable='true'][1]"
    panel_count="$(xpath_value "$xml" "count(//node[@resource-id='$SYSTEM_UI_PANEL_RESOURCE'])")" || return 1
    stack_count="$(xpath_value "$xml" "count(//node[@resource-id='$SYSTEM_UI_STACK_RESOURCE'])")" || return 1
    body_count="$(xpath_value "$xml" "count($body_xpath)")" || return 1
    title_count="$(
        xpath_value "$xml" \
            "count(($row_xpath)//node[@package='$SYSTEM_UI_PACKAGE' and @resource-id='android:id/title' and @text='$GENERIC_NOTIFICATION_TITLE'])"
    )" || return 1
    private_count="$(
        xpath_value "$xml" \
            "count(//node[contains(@text,'$INCOMING_BODY') or contains(@content-desc,'$INCOMING_BODY')])"
    )" || return 1
    if [[ -n "$FIRST_INCOMING_BODY" ]]; then
        first_private_count="$(
            xpath_value "$xml" \
                "count(//node[contains(@text,'$FIRST_INCOMING_BODY') or contains(@content-desc,'$FIRST_INCOMING_BODY')])"
        )" || return 1
    else
        first_private_count=0
    fi
    sender_count="$(
        xpath_value "$xml" \
            "count(//node[contains(@text,'$INCOMING_SENDER') or contains(@content-desc,'$INCOMING_SENDER')])"
    )" || return 1
    [[ "$panel_count" == "1" && "$stack_count" == "1" && "$body_count" == "1" ]] || return 1
    [[ "$title_count" == "1" && "$private_count" == "0" && \
        "$first_private_count" == "0" && "$sender_count" == "0" ]] || return 1
    body_bounds="$(xpath_value "$xml" "string(($body_xpath)[1]/@bounds)")" || return 1
    row_bounds="$(xpath_value "$xml" "string(($row_xpath)[1]/@bounds)")" || return 1
    stack_bounds="$(
        xpath_value "$xml" \
            "string((//node[@resource-id='$SYSTEM_UI_STACK_RESOURCE'])[1]/@bounds)"
    )" || return 1
    parse_bounds "$body_bounds" >/dev/null || return 1
    parse_bounds "$row_bounds" >/dev/null || return 1
    parse_bounds "$stack_bounds" >/dev/null || return 1
    printf '%s|%s|%s\n' "$body_bounds" "$row_bounds" "$stack_bounds"
}

open_shade_and_tap_exact_row() {
    local size width height attempt xml geometry
    local body_bounds row_bounds stack_bounds body_values row_values stack_values
    local body_left body_top body_right body_bottom
    local row_left row_top row_right row_bottom stack_left stack_top stack_right stack_bottom
    local tap_x tap_y
    size="$(display_size)" || return 1
    IFS=',' read -r width height <<<"$size"
    for attempt in {1..3}; do
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell input touchscreen swipe \
            "$((width / 2))" 1 "$((width / 2))" "$((height * 85 / 100))" 500 \
            >/dev/null 2>&1 || return 1
        sleep 0.35
        xml="$(ui_hierarchy_xml)" || continue
        geometry="$(shade_row_geometry "$xml")" || continue
        IFS='|' read -r body_bounds row_bounds stack_bounds <<<"$geometry"
        body_values="$(parse_bounds "$body_bounds")" || return 1
        row_values="$(parse_bounds "$row_bounds")" || return 1
        stack_values="$(parse_bounds "$stack_bounds")" || return 1
        IFS=',' read -r body_left body_top body_right body_bottom <<<"$body_values"
        IFS=',' read -r row_left row_top row_right row_bottom <<<"$row_values"
        IFS=',' read -r stack_left stack_top stack_right stack_bottom <<<"$stack_values"
        (( body_left < body_right && body_top < body_bottom )) || return 1
        (( row_left <= body_left && row_top <= body_top )) || return 1
        (( row_right >= body_right && row_bottom >= body_bottom )) || return 1
        (( stack_left <= row_left && stack_top <= row_top )) || return 1
        (( stack_right >= row_right && stack_bottom >= row_bottom )) || return 1
        tap_x=$(((body_left + body_right) / 2))
        tap_y=$(((body_top + body_bottom) / 2))
        (( tap_x > 0 && tap_x < width && tap_y > 0 && tap_y < height )) || return 1
        target_cold_boundary_is_exact || return 1
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell input touchscreen tap "$tap_x" "$tap_y" \
            >/dev/null 2>&1 || return 1
        return 0
    done
    return 1
}

thread_ui_is_exact() {
    local xml="$1" screen_count inbox_count total_bubble_count bubble_count body_count
    local first_bubble_count first_body_count first_bounds second_bounds
    local first_values second_values first_left first_top first_right first_bottom
    local second_left second_top second_right second_bottom
    screen_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_THREAD_RESOURCE'])"
    )" || return 1
    inbox_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_INBOX_RESOURCE'])"
    )" || return 1
    total_bubble_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_MESSAGE_RESOURCE'])"
    )" || return 1
    bubble_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_MESSAGE_RESOURCE' and (@text='$INCOMING_BODY' or descendant::node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])])"
    )" || return 1
    body_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])"
    )" || return 1
    [[ "$screen_count" == "1" && "$inbox_count" == "0" ]] || return 1
    [[ "$bubble_count" == "1" && "$body_count" == "1" ]] || return 1
    if [[ -z "$FIRST_INCOMING_BODY" ]]; then
        [[ "$total_bubble_count" == "1" ]] || return 1
        return 0
    fi
    [[ "$total_bubble_count" == "2" ]] || return 1
    first_bubble_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @resource-id='$APP_MESSAGE_RESOURCE' and (@text='$FIRST_INCOMING_BODY' or descendant::node[@package='$APP_PACKAGE' and @text='$FIRST_INCOMING_BODY'])])"
    )" || return 1
    first_body_count="$(
        xpath_value "$xml" \
            "count(//node[@package='$APP_PACKAGE' and @text='$FIRST_INCOMING_BODY'])"
    )" || return 1
    [[ "$first_bubble_count" == "1" && "$first_body_count" == "1" ]] || return 1
    first_bounds="$(
        xpath_value "$xml" \
            "string((//node[@package='$APP_PACKAGE' and @text='$FIRST_INCOMING_BODY'])[1]/@bounds)"
    )" || return 1
    second_bounds="$(
        xpath_value "$xml" \
            "string((//node[@package='$APP_PACKAGE' and @text='$INCOMING_BODY'])[1]/@bounds)"
    )" || return 1
    first_values="$(parse_bounds "$first_bounds")" || return 1
    second_values="$(parse_bounds "$second_bounds")" || return 1
    IFS=',' read -r first_left first_top first_right first_bottom <<<"$first_values"
    IFS=',' read -r second_left second_top second_right second_bottom <<<"$second_values"
    (( first_left < first_right && first_top < first_bottom )) || return 1
    (( second_left < second_right && second_top < second_bottom )) || return 1
    (( first_top < second_top ))
}

wait_for_cold_route() {
    local deadline pid xml
    deadline=$((SECONDS + ROUTE_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if pid="$(single_package_pid "$APP_PACKAGE")" && \
            [[ "$pid" != "$RECEIVER_PID" ]] && \
            main_activity_is_focused_and_resumed && \
            app_task_is_present && \
            notification_route_intent_is_exact && \
            [[ "$(exact_notification_state "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID")" == "inactive" ]]; then
            if xml="$(ui_hierarchy_xml)" && thread_ui_is_exact "$xml"; then
                ROUTE_PID="$pid"
                return 0
            fi
        fi
        sleep 0.2
    done
    return 1
}

kill_receiver_process_without_stopping() {
    local current
    current="$(single_package_pid "$APP_PACKAGE")" || return 1
    [[ "$current" == "$RECEIVER_PID" ]] || return 1
    app_task_is_absent || return 1
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell run-as "$APP_PACKAGE" kill -9 "$RECEIVER_PID" \
        >/dev/null 2>&1 || return 1
    wait_for_exact_pid_absence "$RECEIVER_PID" || return 1
    [[ "$(package_stopped_state "$APP_PACKAGE")" == "false" ]] || return 1
}

quiesce_denied_delivery_process_without_stopping() {
    local current
    current="$(package_pids "$APP_PACKAGE")" || return 1
    if [[ -n "$current" ]]; then
        [[ "$current" == "$RECEIVER_PID" ]] || return 1
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell run-as "$APP_PACKAGE" kill -9 "$current" \
            >/dev/null 2>&1 || true
    fi
    target_cold_boundary_is_exact || return 1
    notification_permission_is_user_fixed_denied
}

quiesce_for_recovery() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || return 1
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || return 1
    wait_for_all_target_processes_absent
}

capture_failure_diagnostics() {
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" logcat -b all -d -v threadtime \
        >"$WORK/failure-logcat.txt" 2>&1 || true
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell dumpsys activity broadcasts \
        >"$WORK/failure-broadcasts.txt" 2>&1 || true
    timeout --foreground --kill-after=2s "${ADB_UI_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell dumpsys package "$APP_PACKAGE" \
        >"$WORK/failure-package.txt" 2>&1 || true
}

collapse_shade_best_effort() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1
}

host_process_is_live() {
    local pid="$1" state
    kill -0 "$pid" 2>/dev/null || return 1
    state="$(ps -o stat= -p "$pid" 2>/dev/null | awk '{print $1}')"
    [[ -n "$state" && "$state" != Z* ]]
}

stop_owned_emulator() {
    local attempt
    [[ "$EMULATOR_OWNED" -eq 1 && "$EMULATOR_PID" =~ ^[0-9]+$ ]] || return 0
    if [[ "$DEVICE_READY" -eq 1 ]] && host_process_is_live "$EMULATOR_PID"; then
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" emu kill >/dev/null 2>&1 || true
    fi
    for attempt in {1..40}; do
        host_process_is_live "$EMULATOR_PID" || break
        sleep 0.25
    done
    if host_process_is_live "$EMULATOR_PID"; then
        kill -TERM "$EMULATOR_PID" 2>/dev/null || true
        for attempt in {1..20}; do
            host_process_is_live "$EMULATOR_PID" || break
            sleep 0.25
        done
    fi
    if host_process_is_live "$EMULATOR_PID"; then
        kill -KILL "$EMULATOR_PID" 2>/dev/null || true
        for attempt in {1..20}; do
            host_process_is_live "$EMULATOR_PID" || break
            sleep 0.25
        done
    fi
    host_process_is_live "$EMULATOR_PID" && return 1
    wait "$EMULATOR_PID" 2>/dev/null || true
    EMULATOR_OWNED=0
}

cleanup() {
    local status=$? cleanup_ok=1 recovery_journal_count=""
    trap - EXIT INT TERM
    set +e
    if [[ "$DEVICE_READY" -eq 1 ]]; then
        if [[ "$status" -ne 0 ]]; then
            capture_failure_diagnostics
        fi
        collapse_shade_best_effort || cleanup_ok=0
        if [[ "$RECOVERY_REQUIRED" -eq 1 && "$CLEANUP_COMPLETE" -eq 0 && \
            "$TEST_INSTALLED" -eq 1 ]]; then
            if [[ "$JOURNEY" == "notification-denied" && -z "$EMITTED_PDU_HEX" ]]; then
                recovery_journal_count="$(journal_entry_count)" || cleanup_ok=0
                if [[ "$recovery_journal_count" == "1" ]]; then
                    EMITTED_PDU_HEX="$(wait_for_single_test_pdu_capture)" || cleanup_ok=0
                elif [[ -n "$recovery_journal_count" && "$recovery_journal_count" != "0" ]]; then
                    cleanup_ok=0
                fi
            fi
            quiesce_for_recovery || cleanup_ok=0
            if run_phase cleanup "$WORK/trap-cleanup.txt"; then
                CLEANUP_COMPLETE=1
                RECOVERY_REQUIRED=0
            else
                cleanup_ok=0
            fi
        fi
        if [[ "$JOURNEY" == "notification-denied" && "$TEST_INSTALLED" -eq 1 ]]; then
            delete_test_pdu_capture || cleanup_ok=0
        fi
    fi
    stop_owned_emulator || cleanup_ok=0
    if [[ "$cleanup_ok" -ne 1 ]]; then
        status=1
    fi
    if [[ "$status" -eq 0 ]]; then
        rm -rf "$WORK"
        printf 'Owned read-only %s overlay was discarded after exact cleanup.\n' "$API_LABEL"
    else
        printf 'Owned overlay was discarded; redacted instrumentation logs remain at: %s\n' \
            "$WORK" >&2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Launching owned read-only %s overlay as %s.\n' "$AVD_NAME" "$DEVICE_SERIAL"
"$EMULATOR_BIN" \
    -avd "$AVD_NAME" \
    -port "$PORT" \
    -read-only \
    -no-snapshot \
    -no-window \
    -no-audio \
    -no-boot-anim \
    </dev/null >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!
EMULATOR_OWNED=1

if ! timeout --foreground --kill-after=5s "${EMULATOR_BOOT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" wait-for-device >/dev/null 2>&1; then
    printf 'Owned %s emulator did not become reachable in time.\n' "$API_LABEL" >&2
    exit 1
fi
DEVICE_READY=1

BOOT_DEADLINE=$((SECONDS + EMULATOR_BOOT_TIMEOUT_SECONDS))
BOOTED=0
while (( SECONDS < BOOT_DEADLINE )); do
    if ! host_process_is_live "$EMULATOR_PID"; then
        printf 'Owned emulator process exited during boot.\n' >&2
        exit 1
    fi
    BOOT_COMPLETE="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '\r'
    )" || BOOT_COMPLETE=""
    if [[ "$BOOT_COMPLETE" == "1" ]]; then
        BOOTED=1
        break
    fi
    sleep 0.5
done
if [[ "$BOOTED" -ne 1 ]]; then
    printf 'Owned %s emulator did not finish booting in time.\n' "$API_LABEL" >&2
    exit 1
fi

if ! OWNED_AVD_REPLY="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" emu avd name 2>/dev/null \
        | tr -d '\r'
)" || ! rg --fixed-strings --line-regexp --quiet "$AVD_NAME" <<<"$OWNED_AVD_REPLY"; then
    printf 'The launched emulator did not identify as the required AVD.\n' >&2
    exit 1
fi
if ! IS_QEMU="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r'
)" || ! HARDWARE="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.hardware 2>/dev/null | tr -d '\r'
)" || ! SDK_LEVEL="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'
)"; then
    printf 'Owned emulator identity could not be queried safely.\n' >&2
    exit 1
fi
if [[ "$IS_QEMU" != "1" || ( "$HARDWARE" != "ranchu" && "$HARDWARE" != "goldfish" ) || \
    "$SDK_LEVEL" != "$EXPECTED_SDK" ]]; then
    printf 'Owned emulator identity did not match exact %s AOSP requirements.\n' \
        "$API_LABEL" >&2
    exit 1
fi
if ! PIDOF_PATH="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell 'command -v pidof' 2>/dev/null | tr -d '\r'
)" || [[ -z "$PIDOF_PATH" ]]; then
    printf 'Owned emulator does not expose the required bounded PID query.\n' >&2
    exit 1
fi
console_sms_modem_is_ready || {
    printf 'Owned emulator console did not expose a ready GSM/SMS modem.\n' >&2
    exit 1
}

ensure_awake_and_unlocked || {
    printf 'Owned %s emulator could not be made awake and unlocked.\n' "$API_LABEL" >&2
    exit 1
}
timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell settings put global stay_on_while_plugged_in 7 >/dev/null
for animation_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell settings put global "$animation_scale" 0 >/dev/null
done

for package in "$TEST_PACKAGE" "$APP_PACKAGE"; do
    if ! EXISTING_PATHS="$(package_paths "$package")"; then
        printf 'Overlay package state could not be queried safely: %s\n' "$package" >&2
        exit 1
    fi
    if [[ -n "$EXISTING_PATHS" ]]; then
        if ! timeout --foreground --kill-after=3s "${ADB_INSTALL_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" uninstall "$package" >/dev/null 2>&1; then
            printf 'Existing overlay package could not be removed safely: %s\n' "$package" >&2
            exit 1
        fi
    fi
done

printf 'Installing exact APKs only inside the owned overlay.\n'
if ! timeout --foreground --kill-after=3s "${ADB_INSTALL_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" install -r -t "$APP_APK" >/dev/null; then
    printf 'Target APK install failed inside the owned overlay.\n' >&2
    exit 1
fi
if ! timeout --foreground --kill-after=3s "${ADB_INSTALL_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" install -r -t "$TEST_APK" >/dev/null; then
    printf 'Instrumentation APK install failed inside the owned overlay.\n' >&2
    exit 1
fi
TEST_INSTALLED=1
package_matches_build "$APP_PACKAGE" "$APP_APK" || {
    printf 'Installed target APK does not match the exact local build.\n' >&2
    exit 1
}
package_matches_build "$TEST_PACKAGE" "$TEST_APK" || {
    printf 'Installed instrumentation APK does not match the exact local build.\n' >&2
    exit 1
}
if [[ "$JOURNEY" == "notification-denied" ]]; then
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell pm grant "$TEST_PACKAGE" android.permission.RECEIVE_SMS \
        >/dev/null || {
        printf 'The test-only independent SMS observer could not receive SMS.\n' >&2
        exit 1
    }
    test_receive_sms_is_granted || {
        printf 'The test-only RECEIVE_SMS grant was not exact.\n' >&2
        exit 1
    }
fi

if [[ "$JOURNEY" != "notification-denied" ]]; then
    grant_legacy_default_sms_via_dialog || {
        printf 'The exact API 26 default-SMS confirmation dialog did not complete.\n' >&2
        exit 1
    }
else
    clear_process_start_event_window || {
        printf 'The role-change receiver evidence window could not be cleared.\n' >&2
        exit 1
    }
    grant_modern_default_sms_role || {
        printf 'The exact API 36 default-SMS role assignment did not complete.\n' >&2
        exit 1
    }
    ROLE_CHANGE_RECEIVER_PID="$(
        wait_for_receiver_process_start \
            "org.aurorasms.core.telephony.receiver.DefaultSmsRoleChangedReceiver"
    )" || {
        printf 'The protected API 36 role-change broadcast did not start AuroraSMS.\n' >&2
        exit 1
    }
    wait_for_package_stopped_state "$APP_PACKAGE" false || {
        printf 'The role-change receiver did not clear stopped-package state.\n' >&2
        exit 1
    }
fi
timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell appops set "$APP_PACKAGE" WRITE_SMS allow >/dev/null
for permission in "${PERMISSIONS[@]}"; do
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell pm grant "$APP_PACKAGE" "$permission" >/dev/null
done
if [[ "$(default_sms_state)" != "$APP_PACKAGE" ]]; then
    printf 'AuroraSMS did not become the sole default SMS app inside the overlay.\n' >&2
    exit 1
fi
if ! GRANTED_PERMISSIONS="$(permission_state)" || \
    rg --fixed-strings --quiet '=false' <<<"$GRANTED_PERMISSIONS"; then
    printf 'Required permissions were not granted exactly inside the overlay.\n' >&2
    exit 1
fi
if ! WRITE_SMS_STATE="$(
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell appops get "$APP_PACKAGE" WRITE_SMS 2>/dev/null \
        | tr -d '\r'
)" || ! rg --quiet 'WRITE_SMS: allow([;[:space:]]|$)' <<<"$WRITE_SMS_STATE"; then
    printf 'The owned overlay did not grant the default-SMS provider write operation.\n' >&2
    exit 1
fi

if [[ "$JOURNEY" == "notification-denied" ]]; then
    notification_permission_is_granted || {
        printf 'The fresh API 36 SMS role did not grant notification permission.\n' >&2
        exit 1
    }
    ensure_awake_and_unlocked || {
        printf 'Owned API 36 emulator was not ready for the real Settings denial.\n' >&2
        exit 1
    }
    if ! LAUNCH_OUTPUT="$(launch_main_activity)" || \
        ! rg --fixed-strings --quiet 'Status: ok' <<<"$LAUNCH_OUTPUT"; then
        printf 'AuroraSMS did not launch before the real Settings denial.\n' >&2
        exit 1
    fi
    wait_for_granted_empty_inbox || {
        printf 'The role-granted empty Inbox baseline was not exact.\n' >&2
        exit 1
    }
    if [[ "$(app_notification_count)" != "0" ]]; then
        printf 'AuroraSMS posted a system notification before the denial decision.\n' >&2
        exit 1
    fi
    if ! SETTINGS_LAUNCH_OUTPUT="$(launch_notification_settings)" || \
        ! rg --fixed-strings --quiet 'Status: ok' <<<"$SETTINGS_LAUNCH_OUTPUT"; then
        printf 'The real AuroraSMS notification Settings page did not launch.\n' >&2
        exit 1
    fi
    wait_for_notification_settings_page true || {
        printf 'The role-granted notification master switch was not exactly enabled.\n' >&2
        exit 1
    }
    tap_unique_resource "$SETTINGS_PACKAGE" "$SETTINGS_MAIN_SWITCH_RESOURCE" || {
        printf 'The real AuroraSMS notification master switch could not be disabled.\n' >&2
        exit 1
    }
    wait_for_notification_settings_page false || {
        printf 'The real AuroraSMS notification master switch did not remain disabled.\n' >&2
        exit 1
    }
    notification_permission_is_user_denied || {
        printf 'The real Settings switch did not produce a USER_SET denial.\n' >&2
        exit 1
    }
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || {
        printf 'The initial notification Settings denial could not return to AuroraSMS.\n' >&2
        exit 1
    }
    wait_for_notification_notice_inbox false || {
        printf 'The usable Inbox did not expose the notification-denial explanation.\n' >&2
        exit 1
    }
    tap_unique_resource "$APP_PACKAGE" "$APP_NOTIFICATION_ACTION_RESOURCE" || {
        printf 'The exact Aurora notification recovery action could not be tapped.\n' >&2
        exit 1
    }
    wait_for_permission_dialog || {
        printf 'The API 36 recovery request did not show the real final-denial dialog.\n' >&2
        exit 1
    }
    tap_unique_resource "$PERMISSION_CONTROLLER_PACKAGE" "$PERMISSION_DENY_RESOURCE" || {
        printf 'The real final notification denial action could not be tapped.\n' >&2
        exit 1
    }
    wait_for_notification_notice_inbox false || {
        printf 'The Inbox or missed-alert explanation did not survive final denial.\n' >&2
        exit 1
    }
    notification_permission_is_user_fixed_denied || {
        printf 'POST_NOTIFICATIONS was not proven USER_FIXED after the real dialog.\n' >&2
        exit 1
    }
    if [[ "$(app_notification_count)" != "0" ]]; then
        printf 'AuroraSMS posted a system notification during the denial decision.\n' >&2
        exit 1
    fi
    tap_unique_resource "$APP_PACKAGE" "$APP_NOTIFICATION_ACTION_RESOURCE" || {
        printf 'The post-denial notification Settings action could not be tapped.\n' >&2
        exit 1
    }
    wait_for_notification_settings_page false || {
        printf 'Explicit denial did not route to AuroraSMS notification settings.\n' >&2
        exit 1
    }
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || {
        printf 'The notification settings recovery route could not return to AuroraSMS.\n' >&2
        exit 1
    }
    wait_for_notification_notice_inbox false || {
        printf 'The denied Inbox notice did not survive the Settings recovery round trip.\n' >&2
        exit 1
    }
    notification_permission_is_user_fixed_denied || {
        printf 'The Settings recovery round trip changed the denied permission unexpectedly.\n' >&2
        exit 1
    }
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    establish_notification_denied_cold_boundary || {
        printf 'The denied UI could not reach a taskless, processless, non-stopped boundary.\n' >&2
        exit 1
    }
fi

if [[ "$JOURNEY" != "notification-denied" ]]; then
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null
fi
if [[ "$(journal_entry_count)" != "0" || "$(app_notification_count)" != "0" ]]; then
    printf 'Fresh-overlay delivery and notification state was not empty.\n' >&2
    exit 1
fi

RECOVERY_REQUIRED=1
CLEANUP_COMPLETE=0
run_phase prepare "$WORK/prepare.txt"
if [[ "$(journal_entry_count)" != "0" || "$(app_notification_count)" != "0" ]]; then
    printf 'Prepare did not preserve the empty delivery and notification baseline.\n' >&2
    exit 1
fi
if [[ "$JOURNEY" == "notification-denied" ]]; then
    test_receive_sms_is_granted || {
        printf 'The independent SMS observer lost RECEIVE_SMS before arming.\n' >&2
        exit 1
    }
    arm_test_pdu_capture || {
        printf 'The independent exact-PDU observer could not be armed durably.\n' >&2
        exit 1
    }
fi

target_cold_boundary_is_exact || {
    printf 'The target was not stably cold, taskless, and non-stopped at injection.\n' >&2
    exit 1
}
if [[ "$(default_sms_state)" != "$APP_PACKAGE" ]]; then
    printf 'AuroraSMS lost the sole default-SMS role before modem injection.\n' >&2
    exit 1
fi
if ! GRANTED_PERMISSIONS="$(permission_state)" || \
    rg --fixed-strings --quiet '=false' <<<"$GRANTED_PERMISSIONS"; then
    printf 'A required SMS permission changed before modem injection.\n' >&2
    exit 1
fi
console_sms_modem_is_ready || {
    printf 'The owned GSM/SMS modem was not ready immediately before injection.\n' >&2
    exit 1
}
if [[ "$JOURNEY" == "notification-denied" ]]; then
    notification_permission_is_user_fixed_denied || {
        printf 'POST_NOTIFICATIONS lost its final user-denied state before injection.\n' >&2
        exit 1
    }
    test_receive_sms_is_granted || {
        printf 'The independent SMS observer lost RECEIVE_SMS before injection.\n' >&2
        exit 1
    }
    [[ "$(package_stopped_state "$TEST_PACKAGE")" == "false" ]] || {
        printf 'The independent SMS observer became stopped before injection.\n' >&2
        exit 1
    }
fi
if [[ "$JOURNEY" != "notification-denied" ]]; then
    if [[ "$JOURNEY" == "multiple-message" ]]; then
        printf 'Injecting the first of two exact GSM PDUs through the owned emulator modem.\n'
    else
        printf 'Injecting one exact GSM PDU through the owned emulator modem.\n'
    fi
    if ! INJECTION_RESULT="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" emu sms pdu "$INCOMING_PDU" 2>/dev/null \
            | tr -d '\r'
    )"; then
        printf 'Exact modem PDU injection had an unknown outcome and will not be retried.\n' >&2
        exit 1
    fi
    if [[ "$INJECTION_RESULT" != "OK" ]]; then
        printf 'Exact modem PDU injection was not acknowledged once.\n' >&2
        exit 1
    fi
else
    test_pdu_capture_is_armed_empty || {
        printf 'The exact-PDU observer was not empty immediately before injection.\n' >&2
        exit 1
    }
    clear_process_start_event_window || {
        printf 'The receiver process-start evidence window could not be cleared.\n' >&2
        exit 1
    }
    INJECTION_NOT_BEFORE_SECONDS="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell date +%s 2>/dev/null | tr -d '\r'
    )" || {
        printf 'The owned device injection time could not be read.\n' >&2
        exit 1
    }
    [[ "$INJECTION_NOT_BEFORE_SECONDS" =~ ^[0-9]+$ ]] || {
        printf 'The owned device injection time was invalid.\n' >&2
        exit 1
    }
    INJECTION_NOT_BEFORE_MILLIS=$((INJECTION_NOT_BEFORE_SECONDS * 1000))
    printf 'Injecting one documented incoming SMS through the owned API 36 modem.\n'
    if ! INJECTION_RESULT="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" emu sms send "$INCOMING_MODEM_SENDER" "$INCOMING_BODY" \
            2>/dev/null | tr -d '\r'
    )"; then
        printf 'Documented modem injection had an unknown outcome and will not be retried.\n' >&2
        exit 1
    fi
    if [[ "$INJECTION_RESULT" != "OK" ]]; then
        printf 'Documented modem injection was not acknowledged exactly once.\n' >&2
        exit 1
    fi
fi

RECEIVER_PID=""
if [[ "$JOURNEY" != "notification-denied" ]]; then
    RECEIVER_PID="$(wait_for_single_target_pid)" || {
        printf 'The cold SMS receiver did not start one target process.\n' >&2
        exit 1
    }
    wait_for_complete_journal || {
        printf 'The production incoming-SMS journal did not reach COMPLETE in time.\n' >&2
        exit 1
    }
    if [[ "$JOURNEY" == "multiple-message" ]]; then
        FIRST_PROVIDER_ID="$PROVIDER_ID"
        FIRST_CONVERSATION_ID="$CONVERSATION_ID"
        EXPECTED_NOTIFICATION_TAG="aurora-conversation:$CONVERSATION_ID"
        EXPECTED_NOTIFICATION_ID="$(notification_id_for_conversation "$CONVERSATION_ID")" || {
            printf 'The first production notification ID could not be derived.\n' >&2
            exit 1
        }
        wait_for_exact_notification_state active 3 || {
            printf 'The first exact conversation notification did not stabilize.\n' >&2
            exit 1
        }
        active_notification_contract_is_exact \
            "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID" || {
            printf 'The first conversation notification contract was not exact.\n' >&2
            exit 1
        }
        FIRST_NOTIFICATION_UPDATE_TIME="$(
            active_notification_update_time \
                "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID"
        )" || {
            printf 'The first conversation notification update generation was unavailable.\n' >&2
            exit 1
        }
        if ! FIRST_BACKGROUND_FOCUS="$(focused_package)" || \
            ! app_task_is_absent || [[ "$FIRST_BACKGROUND_FOCUS" == "$APP_PACKAGE" ]]; then
            printf 'The first delivery was not proven background and taskless.\n' >&2
            exit 1
        fi
        [[ "$(single_package_pid "$APP_PACKAGE")" == "$RECEIVER_PID" ]] || {
            printf 'The first delivery process changed before the second injection.\n' >&2
            exit 1
        }
        console_sms_modem_is_ready || {
            printf 'The owned modem was not ready before the second exact injection.\n' >&2
            exit 1
        }
        printf 'Injecting the second distinct exact GSM PDU once.\n'
        if ! SECOND_INJECTION_RESULT="$(
            timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
                "${ADB[@]}" emu sms pdu "$SECOND_INCOMING_PDU" 2>/dev/null \
                | tr -d '\r'
        )"; then
            printf 'Second exact modem PDU injection had an unknown outcome and will not be retried.\n' >&2
            exit 1
        fi
        if [[ "$SECOND_INJECTION_RESULT" != "OK" ]]; then
            printf 'Second exact modem PDU injection was not acknowledged once.\n' >&2
            exit 1
        fi
        wait_for_multiple_complete_journals || {
            printf 'The two production incoming-SMS journals did not both reach COMPLETE.\n' >&2
            exit 1
        }
        wait_for_notification_update_after "$FIRST_NOTIFICATION_UPDATE_TIME" || {
            printf 'The second delivery did not advance the conversation notification generation.\n' >&2
            exit 1
        }
        [[ "$CONVERSATION_ID" == "$FIRST_CONVERSATION_ID" ]] || {
            printf 'The two exact deliveries did not converge on one provider conversation.\n' >&2
            exit 1
        }
        [[ "$(single_package_pid "$APP_PACKAGE")" == "$RECEIVER_PID" ]] || {
            printf 'The two deliveries did not remain in one unambiguous background process.\n' >&2
            exit 1
        }
        wait_for_exact_notification_state active 3 || {
            printf 'The single conversation notification did not stabilize after two deliveries.\n' >&2
            exit 1
        }
        active_notification_contract_is_exact \
            "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID" || {
            printf 'The updated single conversation notification contract was not exact.\n' >&2
            exit 1
        }
    fi
else
    wait_for_complete_journal || {
        printf 'The notification-denied incoming-SMS journal did not reach COMPLETE in time.\n' >&2
        exit 1
    }
    RECEIVER_PID="$(receiver_process_start_pid)" || {
        printf 'The exact SMS_DELIVER receiver process start was not proven once.\n' >&2
        exit 1
    }
    EMITTED_PDU_HEX="$(wait_for_single_test_pdu_capture)" || {
        printf 'The independent observer did not capture one exact delivered SMS PDU.\n' >&2
        exit 1
    }
    TARGET_PIDS="$(package_pids "$APP_PACKAGE")" || {
        printf 'The denied-delivery target process state could not be read.\n' >&2
        exit 1
    }
    [[ -z "$TARGET_PIDS" || "$TARGET_PIDS" == "$RECEIVER_PID" ]] || {
        printf 'Denied delivery left an ambiguous target process generation.\n' >&2
        exit 1
    }
fi
if ! BACKGROUND_FOCUS="$(focused_package)"; then
    printf 'The background focus owner could not be proven after modem delivery.\n' >&2
    exit 1
fi
if ! app_task_is_absent || [[ "$BACKGROUND_FOCUS" == "$APP_PACKAGE" ]]; then
    printf 'The delivered message was not proven background and taskless.\n' >&2
    exit 1
fi
if [[ "$JOURNEY" != "notification-denied" && -n "$RECEIVER_PID" && \
    "$(single_package_pid "$APP_PACKAGE")" != "$RECEIVER_PID" ]]; then
    printf 'The observed receiver process identity changed before inspection.\n' >&2
    exit 1
fi
if [[ "$(package_stopped_state "$APP_PACKAGE")" != "false" ]]; then
    printf 'The delivered message unexpectedly used stopped-package semantics.\n' >&2
    exit 1
fi

if [[ "$JOURNEY" != "notification-denied" ]]; then
    EXPECTED_NOTIFICATION_TAG="aurora-conversation:$CONVERSATION_ID"
    EXPECTED_NOTIFICATION_ID="$(notification_id_for_conversation "$CONVERSATION_ID")" || {
        printf 'The production notification ID could not be derived from the provider thread.\n' >&2
        exit 1
    }
    NOTIFICATION_DEADLINE=$((SECONDS + DELIVERY_TIMEOUT_SECONDS))
    NOTIFICATION_FOUND=0
    while (( SECONDS < NOTIFICATION_DEADLINE )); do
        if OBSERVED_NOTIFICATION_ID="$(
            active_notification_id_for_tag "$EXPECTED_NOTIFICATION_TAG"
        )"; then
            if [[ "$OBSERVED_NOTIFICATION_ID" != "$EXPECTED_NOTIFICATION_ID" ]]; then
                printf 'The active notification ID did not match the production conversation fold.\n' >&2
                exit 1
            fi
            NOTIFICATION_FOUND=1
            break
        fi
        sleep 0.2
    done
    if [[ "$NOTIFICATION_FOUND" -ne 1 ]] || \
        ! wait_for_exact_notification_state active 3; then
        printf 'The exact provider-backed production notification did not stabilize.\n' >&2
        exit 1
    fi
    active_notification_contract_is_exact \
        "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID" || {
        printf 'The active production notification did not match the exact privacy and route contract.\n' >&2
        exit 1
    }

    kill_receiver_process_without_stopping || {
        printf 'The completed background receiver process could not be killed safely.\n' >&2
        exit 1
    }
    app_task_is_absent || {
        printf 'AuroraSMS gained a task before notification interaction.\n' >&2
        exit 1
    }
    wait_for_exact_notification_state active 3 || {
        printf 'The exact notification did not survive background process death.\n' >&2
        exit 1
    }
    active_notification_contract_is_exact \
        "$EXPECTED_NOTIFICATION_TAG" "$EXPECTED_NOTIFICATION_ID" || {
        printf 'The exact production notification contract changed after process death.\n' >&2
        exit 1
    }

    ensure_awake_and_unlocked || {
        printf 'Owned emulator was not awake and unlocked before shade interaction.\n' >&2
        exit 1
    }
    open_shade_and_tap_exact_row || {
        printf 'The unique generic API 26 SystemUI notification row could not be proven and tapped.\n' >&2
        exit 1
    }
    wait_for_cold_route || {
        printf 'The notification tap did not cold-launch the exact provider-backed Thread.\n' >&2
        exit 1
    }
    if [[ "$ROUTE_PID" == "$RECEIVER_PID" || \
        "$(device_pid_state "$RECEIVER_PID")" != "gone" ]]; then
        printf 'The notification route did not use a distinct cold target process.\n' >&2
        exit 1
    fi
    wait_for_exact_notification_state inactive 3 || {
        printf 'The exact notification was not stably auto-cancelled after routing.\n' >&2
        exit 1
    }
else
    notification_permission_is_user_denied || {
        printf 'POST_NOTIFICATIONS did not remain user-denied during modem delivery.\n' >&2
        exit 1
    }
    wait_for_zero_app_notifications 5 || {
        printf 'Notification-denied modem delivery unexpectedly posted an Aurora SBN.\n' >&2
        exit 1
    }
    quiesce_denied_delivery_process_without_stopping || {
        printf 'Denied delivery could not converge on the exact cold boundary.\n' >&2
        exit 1
    }
    app_task_is_absent || {
        printf 'AuroraSMS gained a task before the denied-delivery cold launch.\n' >&2
        exit 1
    }
    wait_for_zero_app_notifications 5 || {
        printf 'An Aurora SBN appeared after denied-delivery process death.\n' >&2
        exit 1
    }
    ensure_awake_and_unlocked || {
        printf 'Owned API 36 emulator was not ready for the denied-delivery cold launch.\n' >&2
        exit 1
    }
    target_cold_boundary_is_exact || {
        printf 'Denied delivery lost its exact cold boundary before UI launch.\n' >&2
        exit 1
    }
    if ! LAUNCH_OUTPUT="$(launch_main_activity)" || \
        ! rg --fixed-strings --quiet 'Status: ok' <<<"$LAUNCH_OUTPUT"; then
        printf 'AuroraSMS did not cold-launch after denied modem delivery.\n' >&2
        exit 1
    fi
    ROUTE_PID="$(wait_for_single_target_pid)" || {
        printf 'Denied-delivery cold launch did not create one target process.\n' >&2
        exit 1
    }
    wait_for_notification_notice_inbox true || {
        printf 'The cold Inbox did not expose both the controlled message and missed-alert notice.\n' >&2
        exit 1
    }
    tap_unique_resource "$APP_PACKAGE" "$APP_INBOX_ROW_RESOURCE" || {
        printf 'The sole controlled Inbox conversation could not be tapped.\n' >&2
        exit 1
    }
    wait_for_thread_with_notice || {
        printf 'The provider-backed Thread was not readable with notifications denied.\n' >&2
        exit 1
    }
    wait_for_zero_app_notifications 5 || {
        printf 'Opening the denied-delivery Thread unexpectedly posted an Aurora SBN.\n' >&2
        exit 1
    }
fi

timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
quiesce_for_recovery || {
    printf 'The routed target could not be quiesced before verification.\n' >&2
    exit 1
}
run_phase verify "$WORK/verify.txt"
if [[ "$JOURNEY" == "notification-denied" ]]; then
    delete_test_pdu_capture || {
        printf 'The independent exact-PDU observer record was not cleaned.\n' >&2
        exit 1
    }
fi
CLEANUP_COMPLETE=1
RECOVERY_REQUIRED=0

if [[ "$(journal_entry_count)" != "0" || "$(app_notification_count)" != "0" ]]; then
    printf 'Exact cleanup did not restore empty delivery and notification state.\n' >&2
    exit 1
fi

case "$JOURNEY" in
    cold-notification)
        printf 'API 26 modem delivery, receiver/provider/orchestrator notification, process death, cold Thread route, verification, and exact cleanup passed.\n'
        ;;
    multiple-message)
        printf 'API 26 two-message modem delivery, one conversation notification, process death, cold Thread route, verification, and exact cleanup passed.\n'
        ;;
    notification-denied)
        printf 'API 36 real notification denial, zero-SBN modem delivery, cold readable Thread, verification, and exact cleanup passed.\n'
        ;;
esac
