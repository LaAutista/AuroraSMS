#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
MANIFEST_ROOT="$ROOT/app/build/intermediates/merged_manifests"
MANIFESTS=()
APKS=()

if [[ -d "$MANIFEST_ROOT" ]]; then
    while IFS= read -r -d '' manifest; do
        MANIFESTS+=("$manifest")
    done < <(
        find "$MANIFEST_ROOT" -type f -name AndroidManifest.xml \
            ! -path '*AndroidTest*' ! -path '*androidTest*' -print0 | sort -z
    )
fi

if ((${#MANIFESTS[@]} == 0)); then
    MANIFESTS+=("$ROOT/app/src/main/AndroidManifest.xml")
fi

while IFS= read -r -d '' apk; do
    APKS+=("$apk")
done < <(
    find "$ROOT/app/build/outputs/apk" -type f -name '*.apk' \
        ! -path '*/androidTest/*' -print0 2>/dev/null | sort -z
)

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | tail -1 | sed 's/\\:/:/g; s/\\ / /g')"
fi

AAPT2=''
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    AAPT2="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
fi

args=("${MANIFESTS[@]}")
if ((${#APKS[@]} > 0)); then
    if [[ -z "$AAPT2" ]]; then
        printf 'Built APKs exist, but aapt2 was not found under the configured Android SDK.\n' >&2
        exit 1
    fi
    args+=(--aapt2 "$AAPT2" --apk "${APKS[@]}")
fi

python3 - "${args[@]}" <<'PY'
from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
APP_ID = "org.aurorasms.app"

allowed_permissions = {
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_MMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.SEND_SMS",
    "android.permission.VIBRATE",
    f"{APP_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
forbidden_permissions = {
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.INTERNET",
}

arguments = sys.argv[1:]
aapt2 = None
apks: list[Path] = []
if "--aapt2" in arguments:
    split = arguments.index("--aapt2")
    manifests = [Path(item) for item in arguments[:split]]
    aapt2 = arguments[split + 1]
    if arguments[split + 2] != "--apk":
        raise SystemExit("Malformed internal verify-permissions invocation")
    apks = [Path(item) for item in arguments[split + 3 :]]
else:
    manifests = [Path(item) for item in arguments]


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def filters_with_action(component: ET.Element, action_name: str) -> list[ET.Element]:
    matches = []
    for intent_filter in component.findall("intent-filter"):
        actions = {attr(action, "name") for action in intent_filter.findall("action")}
        if action_name in actions:
            matches.append(intent_filter)
    return matches


def require_component(
    application: ET.Element,
    tag: str,
    action_name: str,
    permission: str | None = None,
    schemes: set[str] | None = None,
    mime_type: str | None = None,
) -> None:
    candidates: list[tuple[ET.Element, list[ET.Element]]] = []
    for component in application.findall(tag):
        matching_filters = filters_with_action(component, action_name)
        if matching_filters:
            candidates.append((component, matching_filters))
    if len(candidates) != 1:
        raise AssertionError(
            f"Expected exactly one {tag} for {action_name}; found {len(candidates)}"
        )
    component, matching_filters = candidates[0]
    if attr(component, "exported") != "true":
        raise AssertionError(f"{action_name} component must be explicitly exported=true")
    if permission is not None and attr(component, "permission") != permission:
        raise AssertionError(f"{action_name} component must use guard {permission}")
    data = [item for intent_filter in matching_filters for item in intent_filter.findall("data")]
    if schemes is not None:
        actual_schemes = {attr(item, "scheme") for item in data if attr(item, "scheme")}
        if actual_schemes != schemes:
            raise AssertionError(
                f"{action_name} schemes are {sorted(actual_schemes)}; "
                f"expected {sorted(schemes)}"
            )
    if mime_type is not None:
        actual_mime_types = {attr(item, "mimeType") for item in data if attr(item, "mimeType")}
        if mime_type not in actual_mime_types:
            raise AssertionError(f"{action_name} must declare MIME type {mime_type}")


def verify_manifest(path: Path) -> None:
    root = ET.parse(path).getroot()
    permissions = {
        attr(item, "name")
        for tag in ("uses-permission", "uses-permission-sdk-23")
        for item in root.findall(tag)
        if attr(item, "name")
    }
    unexpected = permissions - allowed_permissions
    if unexpected:
        raise AssertionError(f"{path}: unexpected permissions: {sorted(unexpected)}")
    denied = permissions & forbidden_permissions
    if denied:
        raise AssertionError(f"{path}: forbidden permissions: {sorted(denied)}")

    telephony_features = [
        feature
        for feature in root.findall("uses-feature")
        if attr(feature, "name") == "android.hardware.telephony"
    ]
    if len(telephony_features) != 1 or attr(telephony_features[0], "required") != "true":
        raise AssertionError(f"{path}: android.hardware.telephony must be required=true")

    application = root.find("application")
    if application is None:
        raise AssertionError(f"{path}: missing application element")
    if attr(application, "allowBackup") != "false":
        raise AssertionError(f"{path}: application must set allowBackup=false")

    schemes = {"sms", "smsto", "mms", "mmsto"}
    require_component(application, "activity", "android.intent.action.SENDTO", schemes=schemes)
    require_component(
        application,
        "service",
        "android.intent.action.RESPOND_VIA_MESSAGE",
        permission="android.permission.SEND_RESPOND_VIA_MESSAGE",
        schemes=schemes,
    )
    require_component(
        application,
        "receiver",
        "android.provider.Telephony.SMS_DELIVER",
        permission="android.permission.BROADCAST_SMS",
    )
    require_component(
        application,
        "receiver",
        "android.provider.Telephony.WAP_PUSH_DELIVER",
        permission="android.permission.BROADCAST_WAP_PUSH",
        mime_type="application/vnd.wap.mms-message",
    )

    allowed_exported_actions = {
        "android.intent.action.MAIN",
        "android.intent.action.RESPOND_VIA_MESSAGE",
        "android.intent.action.SENDTO",
        "android.provider.Telephony.SMS_DELIVER",
        "android.provider.Telephony.WAP_PUSH_DELIVER",
        "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED",
        "android.provider.action.EXTERNAL_PROVIDER_CHANGE",
    }
    for tag in ("activity", "activity-alias", "receiver", "service", "provider"):
        for component in application.findall(tag):
            if attr(component, "exported") != "true":
                continue
            actions = {
                attr(action, "name")
                for intent_filter in component.findall("intent-filter")
                for action in intent_filter.findall("action")
                if attr(action, "name")
            }
            if not actions or not actions.issubset(allowed_exported_actions):
                raise AssertionError(
                    f"{path}: unledgered exported {tag} {attr(component, 'name')}: "
                    f"{sorted(actions)}"
                )


for manifest in manifests:
    verify_manifest(manifest)
    print(f"Permission ledger passed: {manifest}")

if aapt2:
    for apk in apks:
        output = subprocess.check_output(
            [aapt2, "dump", "permissions", str(apk)], text=True, stderr=subprocess.STDOUT
        )
        apk_permissions = {
            match.group(1)
            for line in output.splitlines()
            if line.lstrip().startswith("uses-permission")
            for match in [re.search(r"name='([^']+)'", line)]
            if match
        }
        unexpected = apk_permissions - allowed_permissions
        if unexpected:
            raise AssertionError(f"{apk}: unexpected packaged permissions: {sorted(unexpected)}")
        if apk_permissions & forbidden_permissions:
            raise AssertionError(f"{apk}: packaged a forbidden network permission")
        print(f"Packaged permission ledger passed: {apk}")
PY
