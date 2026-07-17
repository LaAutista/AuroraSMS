#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    printf 'Usage: %s [--port EVEN_PORT]\n' "$0" >&2
}

DEFAULT_PORT=5580
PORT="$DEFAULT_PORT"
if [[ $# -ne 0 ]]; then
    if [[ $# -ne 2 || "${1:-}" != "--port" || -z "${2:-}" ]]; then
        usage
        exit 2
    fi
    PORT="$2"
fi
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 5554 || PORT > 5584 || PORT % 2 != 0 )); then
    printf 'The owned emulator port must be even and between 5554 and 5584.\n' >&2
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
AVD_NAME="AuroraSMS_SMSRX_API26"
EMULATOR_BIN="/home/kek/Desktop/FLUORINE/android-sdk/emulator/emulator"
ADB_BIN="/home/kek/Desktop/FLUORINE/android-sdk/platform-tools/adb"
DEVICE_SERIAL="emulator-$PORT"
APP_PACKAGE="org.aurorasms.app"
TEST_PACKAGE="org.aurorasms.app.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.aurorasms.app.message.IncomingSmsColdNotificationSmokeTest"
TEST_METHOD="realModemSmsTraversesReceiverProviderOrchestratorAndColdNotificationRoute"
TEST_TARGET="$TEST_CLASS#$TEST_METHOD"
GATE_ARGUMENT="auroraEmulatorIncomingSmsColdNotification"
PHASE_ARGUMENT="auroraEmulatorIncomingSmsColdNotificationPhase"
SENTINEL_VALUE="pass"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
INCOMING_PDU="00040B915155210310F70000627071220400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BDD00"
INCOMING_SENDER="15551230017"
INCOMING_BODY="AuroraSMS modem delivery 900017"
JOURNAL_PATH="shared_prefs/aurora_sms_delivery_journal_v1.xml"
SYSTEM_UI_PACKAGE="com.android.systemui"
SYSTEM_UI_PANEL_RESOURCE="$SYSTEM_UI_PACKAGE:id/notification_panel"
SYSTEM_UI_STACK_RESOURCE="$SYSTEM_UI_PACKAGE:id/notification_stack_scroller"
GENERIC_NOTIFICATION_TITLE="AuroraSMS"
GENERIC_NOTIFICATION_BODY="New message"
APP_THREAD_RESOURCE="aurora-thread-screen"
APP_INBOX_RESOURCE="aurora-inbox-screen"
APP_MESSAGE_RESOURCE="aurora-message-bubble"
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
if [[ "$(exact_ini_value "$AVD_POINTER" target)" != "android-26" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" target)" != "android-26" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" image.sysdir.1)" != \
        "system-images/android-26/default/x86_64/" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" tag.id)" != "default" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" abi.type)" != "x86_64" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" PlayStore.enabled)" != "no" ]] || \
    [[ "$(exact_ini_value "$AVD_CONFIG" hw.gsmModem)" != "yes" ]]; then
    printf 'The dedicated receive-test AVD is not the canonical non-Play API 26 GSM image.\n' >&2
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
CONVERSATION_ID=""
DELIVERY_SUBSCRIPTION_ID=""
RECEIVER_PID=""
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

app_task_is_absent() {
    local activities recents
    activities="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    recents="$(
        timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
            "${ADB[@]}" shell dumpsys activity recents 2>/dev/null \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER ACTIVITIES' <<<"$activities" || return 1
    rg --fixed-strings --quiet 'ACTIVITY MANAGER RECENT TASKS' <<<"$recents" || return 1
    ! rg --fixed-strings --quiet "$APP_PACKAGE/" <<<"$activities" && \
        ! rg --fixed-strings --quiet "$APP_PACKAGE/" <<<"$recents"
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
    rg --quiet \
        "mCurrentFocus=.* u0 $APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
        <<<"$window_dump" && \
        rg --quiet \
            "mResumedActivity:.* $APP_PACKAGE/(\\.?|$APP_PACKAGE\\.)MainActivity([}[:space:]]|$)" \
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
    rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$power_state" && \
        rg --fixed-strings --quiet 'isStatusBarKeyguard=false' <<<"$keyguard_state" && \
        rg --fixed-strings --quiet 'mIsShowing=false' <<<"$keyguard_state"
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

default_sms_state() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell settings get secure sms_default_application 2>/dev/null \
        | tr -d '\r' \
        | sed '/^[[:space:]]*$/d'
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
    case "$phase" in
        prepare)
            expected_code=46
            expected_key="auroraIncomingSmsPrepareResult"
            ;;
        verify)
            expected_code=47
            expected_key="auroraIncomingSmsVerifyResult"
            ;;
        cleanup)
            expected_code=48
            expected_key="auroraIncomingSmsCleanupResult"
            ;;
        *)
            return 2
            ;;
    esac
    printf 'Running owned incoming-SMS instrumentation phase: %s\n' "$phase"
    set +e
    timeout --foreground --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am instrument -w -r \
        -e class "$TEST_TARGET" \
        -e "$GATE_ARGUMENT" true \
        -e "$PHASE_ARGUMENT" "$phase" \
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
    local panel_count stack_count body_count title_count private_count sender_count
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
    sender_count="$(
        xpath_value "$xml" \
            "count(//node[contains(@text,'$INCOMING_SENDER') or contains(@content-desc,'$INCOMING_SENDER')])"
    )" || return 1
    [[ "$panel_count" == "1" && "$stack_count" == "1" && "$body_count" == "1" ]] || return 1
    [[ "$title_count" == "1" && "$private_count" == "0" && "$sender_count" == "0" ]] || return 1
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
    local xml="$1" screen_count inbox_count bubble_count body_count
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
    [[ "$screen_count" == "1" && "$inbox_count" == "0" ]] || return 1
    [[ "$bubble_count" == "1" && "$body_count" == "1" ]]
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

quiesce_for_recovery() {
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || return 1
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || return 1
    wait_for_all_target_processes_absent
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
    local status=$? cleanup_ok=1
    trap - EXIT INT TERM
    set +e
    if [[ "$DEVICE_READY" -eq 1 ]]; then
        collapse_shade_best_effort || cleanup_ok=0
        if [[ "$RECOVERY_REQUIRED" -eq 1 && "$CLEANUP_COMPLETE" -eq 0 && \
            "$TEST_INSTALLED" -eq 1 ]]; then
            quiesce_for_recovery || cleanup_ok=0
            if run_phase cleanup "$WORK/trap-cleanup.txt"; then
                CLEANUP_COMPLETE=1
                RECOVERY_REQUIRED=0
            else
                cleanup_ok=0
            fi
        fi
    fi
    stop_owned_emulator || cleanup_ok=0
    if [[ "$cleanup_ok" -ne 1 ]]; then
        status=1
    fi
    if [[ "$status" -eq 0 ]]; then
        rm -rf "$WORK"
        printf 'Owned read-only API 26 overlay was discarded after exact cleanup.\n'
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
    </dev/null >/dev/null 2>&1 &
EMULATOR_PID=$!
EMULATOR_OWNED=1

if ! timeout --foreground --kill-after=5s "${EMULATOR_BOOT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" wait-for-device >/dev/null 2>&1; then
    printf 'Owned API 26 emulator did not become reachable in time.\n' >&2
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
    printf 'Owned API 26 emulator did not finish booting in time.\n' >&2
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
    "$SDK_LEVEL" != "26" ]]; then
    printf 'Owned emulator identity did not match exact API 26 AOSP requirements.\n' >&2
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
    printf 'Owned API 26 emulator could not be made awake and unlocked.\n' >&2
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

grant_legacy_default_sms_via_dialog || {
    printf 'The exact API 26 default-SMS confirmation dialog did not complete.\n' >&2
    exit 1
}
timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell appops set "$APP_PACKAGE" WRITE_SMS allow >/dev/null
for permission in "${PERMISSIONS[@]}"; do
    timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
        "${ADB[@]}" shell pm grant "$APP_PACKAGE" "$permission" >/dev/null
done
if [[ "$(default_sms_state)" != "$APP_PACKAGE" ]]; then
    printf 'AuroraSMS did not become the legacy default SMS app inside the overlay.\n' >&2
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

timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null
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

printf 'Injecting one exact GSM PDU through the owned emulator modem.\n'
target_cold_boundary_is_exact || {
    printf 'The target was not stably cold, taskless, and non-stopped at injection.\n' >&2
    exit 1
}
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

RECEIVER_PID="$(wait_for_single_target_pid)" || {
    printf 'The cold SMS receiver did not start one target process.\n' >&2
    exit 1
}
wait_for_complete_journal || {
    printf 'The production incoming-SMS journal did not reach COMPLETE in time.\n' >&2
    exit 1
}
if ! BACKGROUND_FOCUS="$(focused_package)"; then
    printf 'The background focus owner could not be proven after modem delivery.\n' >&2
    exit 1
fi
if [[ "$(single_package_pid "$APP_PACKAGE")" != "$RECEIVER_PID" ]] || \
    ! app_task_is_absent || [[ "$BACKGROUND_FOCUS" == "$APP_PACKAGE" ]]; then
    printf 'The receiver-created target process was not proven background and taskless.\n' >&2
    exit 1
fi
if [[ "$(package_stopped_state "$APP_PACKAGE")" != "false" ]]; then
    printf 'The receiver-created process unexpectedly used stopped-package semantics.\n' >&2
    exit 1
fi

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
if [[ "$ROUTE_PID" == "$RECEIVER_PID" || "$(device_pid_state "$RECEIVER_PID")" != "gone" ]]; then
    printf 'The notification route did not use a distinct cold target process.\n' >&2
    exit 1
fi
wait_for_exact_notification_state inactive 3 || {
    printf 'The exact notification was not stably auto-cancelled after routing.\n' >&2
    exit 1
}

timeout --foreground --kill-after=2s "${ADB_SHORT_TIMEOUT_SECONDS}s" \
    "${ADB[@]}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
quiesce_for_recovery || {
    printf 'The routed target could not be quiesced before verification.\n' >&2
    exit 1
}
run_phase verify "$WORK/verify.txt"
CLEANUP_COMPLETE=1
RECOVERY_REQUIRED=0

if [[ "$(journal_entry_count)" != "0" || "$(app_notification_count)" != "0" ]]; then
    printf 'Exact cleanup did not restore empty delivery and notification state.\n' >&2
    exit 1
fi

printf 'API 26 modem delivery, receiver/provider/orchestrator notification, process death, cold Thread route, verification, and exact cleanup passed.\n'
