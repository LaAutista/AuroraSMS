#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
    cat >&2 <<EOF
Usage:
  $0 --self-test
  $0 --device SERIAL --acknowledge-owner-visible-provider-read [--timeout-seconds SECONDS]

The physical run never installs an APK, changes the SMS role or permissions,
queries provider rows through adb, captures a screen, reads logs, or sends a
message. The owner must first select AuroraSMS through normal Android UI and
keep the unlocked app foreground-readable for the duration of the run.
EOF
}

canonical_nonnegative_decimal() {
    [[ "$1" =~ ^(0|[1-9][0-9]*)$ ]]
}

canonical_positive_decimal() {
    [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

parse_snapshot() {
    local snapshot="$1"
    local -a lines=()
    local kind exhausted committed verified value line_number

    SNAPSHOT_GENERATION_ID=""
    SNAPSHOT_STATE=""
    SNAPSHOT_GENERATION_COMMITTED=""
    SNAPSHOT_PENDING=""
    SNAPSHOT_COMPLETED=""
    SNAPSHOT_FAILURE=""
    SNAPSHOT_UPDATED_AT=""
    SNAPSHOT_SMS_EXHAUSTED=""
    SNAPSHOT_SMS_COMMITTED=""
    SNAPSHOT_SMS_VERIFIED=""
    SNAPSHOT_MMS_EXHAUSTED=""
    SNAPSHOT_MMS_COMMITTED=""
    SNAPSHOT_MMS_VERIFIED=""
    SNAPSHOT_INDEXED_ROWS=""
    SNAPSHOT_DISTINCT_THREADS=""
    SNAPSHOT_CONVERSATIONS=""
    SNAPSHOT_SUMMARIZED_ROWS=""
    SNAPSHOT_UNREAD_ROWS=""
    SNAPSHOT_SUMMARIZED_UNREAD=""
    SNAPSHOT_INVALID_LATEST=""
    SNAPSHOT_INVALID_PARTICIPANTS=""
    SNAPSHOT_FTS_ROWS=""

    mapfile -t lines <<<"$snapshot"
    [[ ${#lines[@]} -eq 4 ]] || return 1

    if [[ "${lines[0]}" =~ ^GEN\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]*)\|([^|]+)$ ]]; then
        SNAPSHOT_GENERATION_ID="${BASH_REMATCH[1]}"
        SNAPSHOT_STATE="${BASH_REMATCH[2]}"
        SNAPSHOT_GENERATION_COMMITTED="${BASH_REMATCH[3]}"
        SNAPSHOT_PENDING="${BASH_REMATCH[4]}"
        SNAPSHOT_COMPLETED="${BASH_REMATCH[5]}"
        SNAPSHOT_FAILURE="${BASH_REMATCH[6]}"
        SNAPSHOT_UPDATED_AT="${BASH_REMATCH[7]}"
    else
        return 1
    fi
    canonical_positive_decimal "$SNAPSHOT_GENERATION_ID" || return 1
    [[ "$SNAPSHOT_STATE" =~ ^[1-5]$ ]] || return 1
    canonical_nonnegative_decimal "$SNAPSHOT_GENERATION_COMMITTED" || return 1
    [[ "$SNAPSHOT_PENDING" =~ ^[01]$ ]] || return 1
    [[ "$SNAPSHOT_COMPLETED" =~ ^[01]$ ]] || return 1
    [[ -z "$SNAPSHOT_FAILURE" || "$SNAPSHOT_FAILURE" =~ ^[1-7]$ ]] || return 1
    canonical_nonnegative_decimal "$SNAPSHOT_UPDATED_AT" || return 1

    for line_number in 1 2; do
        if [[ "${lines[$line_number]}" =~ ^CP\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]*)$ ]]; then
            kind="${BASH_REMATCH[1]}"
            exhausted="${BASH_REMATCH[2]}"
            committed="${BASH_REMATCH[3]}"
            verified="${BASH_REMATCH[4]}"
        else
            return 1
        fi
        [[ "$kind" =~ ^[12]$ && "$exhausted" =~ ^[01]$ ]] || return 1
        canonical_nonnegative_decimal "$committed" || return 1
        [[ -z "$verified" ]] || canonical_nonnegative_decimal "$verified" || return 1
        case "$kind" in
            1)
                [[ -z "$SNAPSHOT_SMS_EXHAUSTED" ]] || return 1
                SNAPSHOT_SMS_EXHAUSTED="$exhausted"
                SNAPSHOT_SMS_COMMITTED="$committed"
                SNAPSHOT_SMS_VERIFIED="$verified"
                ;;
            2)
                [[ -z "$SNAPSHOT_MMS_EXHAUSTED" ]] || return 1
                SNAPSHOT_MMS_EXHAUSTED="$exhausted"
                SNAPSHOT_MMS_COMMITTED="$committed"
                SNAPSHOT_MMS_VERIFIED="$verified"
                ;;
        esac
    done
    [[ -n "$SNAPSHOT_SMS_EXHAUSTED" && -n "$SNAPSHOT_MMS_EXHAUSTED" ]] || return 1

    if [[ "${lines[3]}" =~ ^IDX\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)\|([^|]+)$ ]]; then
        SNAPSHOT_INDEXED_ROWS="${BASH_REMATCH[1]}"
        SNAPSHOT_DISTINCT_THREADS="${BASH_REMATCH[2]}"
        SNAPSHOT_CONVERSATIONS="${BASH_REMATCH[3]}"
        SNAPSHOT_SUMMARIZED_ROWS="${BASH_REMATCH[4]}"
        SNAPSHOT_UNREAD_ROWS="${BASH_REMATCH[5]}"
        SNAPSHOT_SUMMARIZED_UNREAD="${BASH_REMATCH[6]}"
        SNAPSHOT_INVALID_LATEST="${BASH_REMATCH[7]}"
        SNAPSHOT_INVALID_PARTICIPANTS="${BASH_REMATCH[8]}"
        SNAPSHOT_FTS_ROWS="${BASH_REMATCH[9]}"
    else
        return 1
    fi
    for value in \
        "$SNAPSHOT_INDEXED_ROWS" \
        "$SNAPSHOT_DISTINCT_THREADS" \
        "$SNAPSHOT_CONVERSATIONS" \
        "$SNAPSHOT_SUMMARIZED_ROWS" \
        "$SNAPSHOT_UNREAD_ROWS" \
        "$SNAPSHOT_SUMMARIZED_UNREAD" \
        "$SNAPSHOT_INVALID_LATEST" \
        "$SNAPSHOT_INVALID_PARTICIPANTS" \
        "$SNAPSHOT_FTS_ROWS"; do
        canonical_nonnegative_decimal "$value" || return 1
    done
}

snapshot_is_verified_complete() {
    local snapshot="$1"
    parse_snapshot "$snapshot" || return 1

    [[ "$SNAPSHOT_STATE" == "3" ]] || return 1
    [[ "$SNAPSHOT_PENDING" == "0" && "$SNAPSHOT_COMPLETED" == "1" ]] || return 1
    [[ -z "$SNAPSHOT_FAILURE" ]] || return 1
    [[ "$SNAPSHOT_SMS_EXHAUSTED" == "1" && "$SNAPSHOT_MMS_EXHAUSTED" == "1" ]] || return 1
    [[ -n "$SNAPSHOT_SMS_VERIFIED" && -n "$SNAPSHOT_MMS_VERIFIED" ]] || return 1
    ((SNAPSHOT_GENERATION_COMMITTED > 0)) || return 1
    ((SNAPSHOT_SMS_VERIFIED == SNAPSHOT_SMS_COMMITTED)) || return 1
    ((SNAPSHOT_MMS_VERIFIED == SNAPSHOT_MMS_COMMITTED)) || return 1
    ((SNAPSHOT_SMS_COMMITTED + SNAPSHOT_MMS_COMMITTED == SNAPSHOT_GENERATION_COMMITTED)) || return 1
    ((SNAPSHOT_INDEXED_ROWS == SNAPSHOT_GENERATION_COMMITTED)) || return 1
    ((SNAPSHOT_DISTINCT_THREADS == SNAPSHOT_CONVERSATIONS)) || return 1
    ((SNAPSHOT_SUMMARIZED_ROWS == SNAPSHOT_INDEXED_ROWS)) || return 1
    ((SNAPSHOT_SUMMARIZED_UNREAD == SNAPSHOT_UNREAD_ROWS)) || return 1
    ((SNAPSHOT_INVALID_LATEST == 0)) || return 1
    ((SNAPSHOT_INVALID_PARTICIPANTS == 0)) || return 1
    ((SNAPSHOT_FTS_ROWS == SNAPSHOT_INDEXED_ROWS)) || return 1
}

self_test() {
    local valid scanning missing_checkpoint inconsistent excess_provider_count
    valid=$'GEN|9|3|150|0|1||7000\nCP|1|1|100|100\nCP|2|1|50|50\nIDX|150|12|12|150|7|7|0|0|150'
    scanning=$'GEN|9|1|100|0|0||6000\nCP|1|1|100|\nCP|2|0|0|\nIDX|100|10|10|100|5|5|0|0|100'
    missing_checkpoint=$'GEN|9|3|150|0|1||7000\nCP|1|1|100|100\nCP|1|1|50|50\nIDX|150|12|12|150|7|7|0|0|150'
    inconsistent=$'GEN|9|3|150|0|1||7000\nCP|1|1|100|100\nCP|2|1|50|50\nIDX|149|12|12|149|7|7|0|0|149'
    excess_provider_count=$'GEN|9|3|150|0|1||7000\nCP|1|1|100|101\nCP|2|1|50|50\nIDX|150|12|12|150|7|7|0|0|150'

    snapshot_is_verified_complete "$valid" || {
        printf 'Physical provider protocol rejected its valid aggregate fixture.\n' >&2
        exit 1
    }
    if snapshot_is_verified_complete "$scanning"; then
        printf 'Physical provider protocol accepted a scanning generation.\n' >&2
        exit 1
    fi
    if parse_snapshot "$missing_checkpoint"; then
        printf 'Physical provider protocol accepted duplicate SMS checkpoints.\n' >&2
        exit 1
    fi
    if snapshot_is_verified_complete "$inconsistent"; then
        printf 'Physical provider protocol accepted inconsistent aggregate counts.\n' >&2
        exit 1
    fi
    if snapshot_is_verified_complete "$excess_provider_count"; then
        printf 'Physical provider protocol accepted an unindexed eligible provider row.\n' >&2
        exit 1
    fi
    printf 'Physical provider completion parser self-test passed.\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
    [[ $# -eq 1 ]] || {
        usage
        exit 2
    }
    self_test
    exit 0
fi

DEVICE_SERIAL=""
ACKNOWLEDGED=false
TIMEOUT_SECONDS=1800
while (($#)); do
    case "$1" in
        --device)
            [[ $# -ge 2 && -n "$2" ]] || {
                usage
                exit 2
            }
            DEVICE_SERIAL="$2"
            shift 2
            ;;
        --acknowledge-owner-visible-provider-read)
            ACKNOWLEDGED=true
            shift
            ;;
        --timeout-seconds)
            [[ $# -ge 2 ]] || {
                usage
                exit 2
            }
            TIMEOUT_SECONDS="$2"
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [[ -z "$DEVICE_SERIAL" || "$ACKNOWLEDGED" != true ]]; then
    usage
    exit 2
fi
if ! canonical_nonnegative_decimal "$TIMEOUT_SECONDS" || \
    ((TIMEOUT_SECONDS < 60 || TIMEOUT_SECONDS > 3600)); then
    printf 'Timeout must be a canonical integer from 60 through 3600 seconds.\n' >&2
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
APP_PACKAGE="org.aurorasms.app"
APP_COMPONENT="$APP_PACKAGE/.MainActivity"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
INDEX_DATABASE="databases/aurora_index.db"
ADB=(adb -s "$DEVICE_SERIAL")
PERMISSIONS=(
    android.permission.READ_SMS
    android.permission.SEND_SMS
    android.permission.RECEIVE_SMS
    android.permission.RECEIVE_MMS
    android.permission.RECEIVE_WAP_PUSH
    android.permission.READ_PHONE_STATE
    android.permission.READ_CONTACTS
    android.permission.POST_NOTIFICATIONS
)

SNAPSHOT_SQL="$(cat <<'SQL'
PRAGMA query_only=ON;
SELECT 'GEN|' || generation_id || '|' || state || '|' || committed_count || '|' ||
       pending_changes || '|' || CASE WHEN completed_at_ms IS NULL THEN 0 ELSE 1 END || '|' ||
       COALESCE(failure_code, '') || '|' || updated_at_ms
FROM index_generations
ORDER BY generation_id DESC
LIMIT 1;
SELECT 'CP|' || provider_kind || '|' || exhausted || '|' || committed_count || '|' ||
       COALESCE(verified_provider_count, '')
FROM index_checkpoints
WHERE generation_id = (SELECT MAX(generation_id) FROM index_generations)
ORDER BY provider_kind ASC;
SELECT 'IDX|' ||
       (SELECT COUNT(*) FROM indexed_messages) || '|' ||
       (SELECT COUNT(DISTINCT provider_thread_id) FROM indexed_messages) || '|' ||
       (SELECT COUNT(*) FROM indexed_conversations) || '|' ||
       (SELECT COALESCE(SUM(indexed_message_count), 0) FROM indexed_conversations) || '|' ||
       (SELECT COUNT(*) FROM indexed_messages WHERE is_read = 0) || '|' ||
       (SELECT COALESCE(SUM(indexed_unread_count), 0) FROM indexed_conversations) || '|' ||
       (SELECT COUNT(*) FROM indexed_conversations AS c
        LEFT JOIN indexed_messages AS m ON m.row_id = c.latest_row_id
        WHERE m.row_id IS NULL OR
              m.last_seen_generation != c.last_seen_generation OR
              m.provider_thread_id != c.provider_thread_id OR
              m.provider_kind != c.latest_provider_kind OR
              m.provider_id != c.latest_provider_id OR
              m.timestamp_ms != c.latest_timestamp_ms) || '|' ||
       (SELECT COUNT(*) FROM indexed_conversations AS c
        WHERE c.indexed_participant_count !=
              (SELECT COUNT(*) FROM indexed_conversation_participants AS p
               WHERE p.provider_thread_id = c.provider_thread_id AND
                     p.last_seen_generation = c.last_seen_generation)) || '|' ||
       (SELECT COUNT(*) FROM indexed_messages_fts);
SQL
)"

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ! printf '%s\n' "${DEVICES[@]}" \
    | rg --fixed-strings --line-regexp -- "$DEVICE_SERIAL" >/dev/null; then
    printf 'Requested physical provider device is not authorized: %s\n' "$DEVICE_SERIAL" >&2
    exit 1
fi

IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$DEVICE_SERIAL" == emulator-* || "$IS_EMULATOR" == "1" ]]; then
    printf 'The physical provider completion protocol refuses emulator: %s\n' \
        "$DEVICE_SERIAL" >&2
    exit 1
fi

DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
if [[ "$DEVICE_MODEL" != "Pixel 8" ]]; then
    printf 'The release protocol requires the reviewed Pixel 8; found: %s\n' \
        "$DEVICE_MODEL" >&2
    exit 1
fi

SDK_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$SDK_LEVEL" =~ ^[0-9]+$ || "$SDK_LEVEL" -lt 33 ]]; then
    printf 'The Pixel provider completion protocol requires API 33 or newer.\n' >&2
    exit 1
fi

content_free_device_preflight() {
    local audio_modes call_states locked_values wakefulness
    audio_modes="$(
        "${ADB[@]}" shell "dumpsys audio | grep -E 'Actual mode = MODE_'" \
            | tr -d '\r'
    )"
    if rg --quiet 'MODE_(IN_CALL|IN_COMMUNICATION|RINGTONE)' <<<"$audio_modes"; then
        printf 'The provider protocol refuses to run while call/ringtone audio is active.\n' >&2
        return 1
    fi
    call_states="$(
        "${ADB[@]}" shell \
            "dumpsys telephony.registry | grep -Eo 'mCallState=[0-9]+' | cut -d= -f2 | sort -u" \
            | tr -d '\r'
    )"
    if [[ -z "$call_states" ]] || rg --quiet '^[12]$' <<<"$call_states"; then
        printf 'The provider protocol requires a content-free idle telephony state.\n' >&2
        return 1
    fi
    wakefulness="$(
        "${ADB[@]}" shell "dumpsys power | grep -Eo 'mWakefulness=[A-Za-z]+'" \
            | tr -d '\r'
    )"
    if ! rg --fixed-strings --quiet 'mWakefulness=Awake' <<<"$wakefulness"; then
        printf 'Wake the Pixel before running the provider protocol.\n' >&2
        return 1
    fi
    locked_values="$(
        "${ADB[@]}" shell "dumpsys trust | grep -Eo 'deviceLocked=[01]' | sort -u" \
            | tr -d '\r'
    )"
    if [[ "$locked_values" != "deviceLocked=0" ]]; then
        printf 'Unlock the Pixel before running the provider protocol.\n' >&2
        return 1
    fi
}

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

target_is_foreground() {
    local foreground
    foreground="$(
        "${ADB[@]}" shell \
            "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 4; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4" \
            | tr -d '\r'
    )" || return 1
    rg --fixed-strings --quiet "$APP_COMPONENT" <<<"$foreground"
}

target_matches_build() {
    local installed_path local_sha256 device_sha256
    installed_path="$(target_path)"
    [[ -n "$installed_path" ]] || return 1
    local_sha256="$(sha256sum "$APP_APK" | awk '{print $1}')"
    device_sha256="$("${ADB[@]}" shell sha256sum "$installed_path" | awk '{print $1}')"
    [[ "$device_sha256" == "$local_sha256" ]]
}

query_content_free_snapshot() {
    local result
    if ! result="$(
        printf '%s\n' "$SNAPSHOT_SQL" \
            | "${ADB[@]}" shell run-as "$APP_PACKAGE" sqlite3 "$INDEX_DATABASE" 2>/dev/null \
            | tr -d '\r'
    )"; then
        return 1
    fi
    [[ -n "$result" ]] || return 1
    printf '%s\n' "$result"
}

content_free_device_preflight

BEFORE_ROLE="$(role_state)"
if [[ "$BEFORE_ROLE" != "$APP_PACKAGE" ]]; then
    printf '%s must already be the sole owner-selected SMS role holder.\n' \
        "$APP_PACKAGE" >&2
    printf 'Select it through normal Android UI, keep it foreground-readable, then rerun.\n' >&2
    exit 1
fi

if ! BEFORE_PERMISSIONS="$(permission_state)"; then
    printf 'AuroraSMS permission state could not be captured safely.\n' >&2
    exit 1
fi
if ! rg --fixed-strings --line-regexp \
    'android.permission.READ_SMS=true' <<<"$BEFORE_PERMISSIONS" >/dev/null; then
    printf 'The owner-selected role must grant AuroraSMS READ_SMS before this run.\n' >&2
    exit 1
fi

"$ROOT/gradlew" :app:assembleDebug --offline --no-daemon --no-parallel --console=plain
if ! target_matches_build; then
    printf 'The installed Pixel APK does not match the reviewed debug build.\n' >&2
    printf 'Update it separately, confirm preserved app state, select AuroraSMS again, and rerun.\n' >&2
    exit 1
fi
if ! "${ADB[@]}" shell run-as "$APP_PACKAGE" id >/dev/null 2>&1; then
    printf 'The installed target does not expose the required debug run-as boundary.\n' >&2
    exit 1
fi
if ! "${ADB[@]}" shell which sqlite3 >/dev/null 2>&1; then
    printf 'The reviewed Pixel image does not expose sqlite3 for aggregate verification.\n' >&2
    exit 1
fi

if [[ "$(role_state)" != "$BEFORE_ROLE" || "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'Role or permission state changed during preflight; refusing provider access.\n' >&2
    exit 1
fi
content_free_device_preflight
if ! target_is_foreground; then
    printf 'Keep AuroraSMS foreground before the protocol cold start.\n' >&2
    exit 1
fi

initial_updated_at=0
if initial_snapshot="$(query_content_free_snapshot)" && parse_snapshot "$initial_snapshot"; then
    initial_updated_at="$SNAPSHOT_UPDATED_AT"
fi

printf 'Cold-starting only AuroraSMS so this run cannot accept a stale complete snapshot.\n'
"${ADB[@]}" shell am force-stop "$APP_PACKAGE"
if "${ADB[@]}" shell pidof "$APP_PACKAGE" >/dev/null 2>&1; then
    printf 'AuroraSMS remained alive after the exact preflight force-stop.\n' >&2
    exit 1
fi
if [[ "$(role_state)" != "$BEFORE_ROLE" || "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'Role or permission state changed during the exact preflight force-stop.\n' >&2
    exit 1
fi

printf 'Launching AuroraSMS for the explicitly acknowledged owner-visible provider read.\n'
"${ADB[@]}" shell am start -W -n "$APP_COMPONENT" >/dev/null

deadline=$((SECONDS + TIMEOUT_SECONDS))
last_snapshot=""
stable_snapshot=""
stable_count=0
while ((SECONDS < deadline)); do
    content_free_device_preflight
    if ! target_is_foreground || ! "${ADB[@]}" shell pidof "$APP_PACKAGE" >/dev/null 2>&1; then
        printf 'AuroraSMS must remain alive and foreground during provider completion.\n' >&2
        exit 1
    fi
    if [[ "$(role_state)" != "$BEFORE_ROLE" ]]; then
        printf 'AuroraSMS lost the owner-selected SMS role during provider completion.\n' >&2
        exit 1
    fi
    if [[ "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
        printf 'AuroraSMS permission state changed during provider completion.\n' >&2
        exit 1
    fi

    if snapshot="$(query_content_free_snapshot)" && parse_snapshot "$snapshot"; then
        if [[ "$snapshot" != "$last_snapshot" ]]; then
            printf 'Provider completion progress: generation=%s state=%s indexed=%s pending=%s sms=%s/%s mms=%s/%s\n' \
                "$SNAPSHOT_GENERATION_ID" \
                "$SNAPSHOT_STATE" \
                "$SNAPSHOT_INDEXED_ROWS" \
                "$SNAPSHOT_PENDING" \
                "$SNAPSHOT_SMS_COMMITTED" \
                "${SNAPSHOT_SMS_VERIFIED:-unverified}" \
                "$SNAPSHOT_MMS_COMMITTED" \
                "${SNAPSHOT_MMS_VERIFIED:-unverified}"
            last_snapshot="$snapshot"
        fi
        if [[ "$SNAPSHOT_STATE" == "5" ]]; then
            printf 'Provider completion entered failure code %s.\n' \
                "${SNAPSHOT_FAILURE:-unknown}" >&2
            exit 1
        fi
        if snapshot_is_verified_complete "$snapshot" && \
            ((SNAPSHOT_UPDATED_AT > initial_updated_at)); then
            if [[ "$snapshot" == "$stable_snapshot" ]]; then
                stable_count=$((stable_count + 1))
            else
                stable_snapshot="$snapshot"
                stable_count=1
            fi
            if ((stable_count >= 3)); then
                break
            fi
        else
            stable_snapshot=""
            stable_count=0
        fi
    fi
    sleep 2
done

if ((stable_count < 3)); then
    printf 'Provider completion did not reach three identical verified snapshots within %s seconds.\n' \
        "$TIMEOUT_SECONDS" >&2
    exit 1
fi
parse_snapshot "$stable_snapshot"
if ((SNAPSHOT_UPDATED_AT <= initial_updated_at)); then
    printf 'The verified snapshot was not refreshed after the protocol cold start.\n' >&2
    exit 1
fi
if ! target_matches_build || ! target_is_foreground || \
    [[ "$(role_state)" != "$BEFORE_ROLE" ]] || \
    [[ "$(permission_state)" != "$BEFORE_PERMISSIONS" ]]; then
    printf 'The target APK, role, or permission state changed before final acceptance.\n' >&2
    exit 1
fi

printf 'Physical provider completion passed: generation=%s indexed=%s conversations=%s sms=%s/%s mms=%s/%s.\n' \
    "$SNAPSHOT_GENERATION_ID" \
    "$SNAPSHOT_INDEXED_ROWS" \
    "$SNAPSHOT_CONVERSATIONS" \
    "$SNAPSHOT_SMS_COMMITTED" \
    "$SNAPSHOT_SMS_VERIFIED" \
    "$SNAPSHOT_MMS_COMMITTED" \
    "$SNAPSHOT_MMS_VERIFIED"
printf 'AuroraSMS remains foreground and the owner-selected SMS app for private UI review.\n'
printf 'The runner captured aggregate counts only and made no role, permission, provider-write, or carrier change.\n'
