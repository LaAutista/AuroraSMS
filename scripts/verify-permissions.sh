#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
MANIFEST_ROOT="$ROOT/app/build/intermediates/merged_manifests"
MACRO_MANIFEST_ROOT="$ROOT/macrobenchmark/build/intermediates/packaged_manifests/benchmark"
MANIFESTS=()
MACRO_MANIFESTS=()
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

if [[ -d "$MACRO_MANIFEST_ROOT" ]]; then
    while IFS= read -r -d '' manifest; do
        MACRO_MANIFESTS+=("$manifest")
    done < <(find "$MACRO_MANIFEST_ROOT" -type f -name AndroidManifest.xml -print0 | sort -z)
fi

for apk_root in "$ROOT/app/build/outputs/apk" "$ROOT/macrobenchmark/build/outputs/apk/benchmark"; do
    [[ -d "$apk_root" ]] || continue
    while IFS= read -r -d '' apk; do
        APKS+=("$apk")
    done < <(
        find "$apk_root" -type f -name '*.apk' \
            ! -path '*/androidTest/*' -print0 2>/dev/null | sort -z
    )
done

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk[.]dir=//p' "$ROOT/local.properties" | tail -1 | sed 's/\\:/:/g; s/\\ / /g')"
fi

AAPT2=''
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    AAPT2="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
fi

args=(--app-manifests "${MANIFESTS[@]}" --macro-manifests "${MACRO_MANIFESTS[@]}")
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
MACRO_ID = "org.aurorasms.macrobenchmark"
CONTROL_PERMISSION = f"{APP_ID}.permission.BENCHMARK_CONTROL"
MACRO_DYNAMIC_RECEIVER_PERMISSION = f"{MACRO_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

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
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.WRITE_EXTERNAL_STORAGE",
}

arguments = sys.argv[1:]
aapt2 = None
apks: list[Path] = []
if not arguments or arguments[0] != "--app-manifests" or "--macro-manifests" not in arguments:
    raise SystemExit("Malformed internal verify-permissions invocation")
macro_split = arguments.index("--macro-manifests")
aapt_split = arguments.index("--aapt2") if "--aapt2" in arguments else len(arguments)
manifests = [Path(item) for item in arguments[1:macro_split]]
macro_manifests = [Path(item) for item in arguments[macro_split + 1 : aapt_split]]
if aapt_split < len(arguments):
    aapt2 = arguments[aapt_split + 1]
    if arguments[aapt_split + 2] != "--apk":
        raise SystemExit("Malformed internal verify-permissions invocation")
    apks = [Path(item) for item in arguments[aapt_split + 3 :]]


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
    is_benchmark = "benchmark" in path.parts
    has_profile_installer = is_benchmark or "release" in path.parts
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

    control_declarations = [
        item for item in root.findall("permission") if attr(item, "name") == CONTROL_PERMISSION
    ]
    profileable = application.findall("profileable")
    fixture_providers = [
        item
        for item in application.findall("provider")
        if attr(item, "name") in {
            ".benchmark.BenchmarkFixtureProvider",
            f"{APP_ID}.benchmark.BenchmarkFixtureProvider",
        }
        or attr(item, "authorities") == f"{APP_ID}.benchmark.fixture"
    ]
    startup_providers = [
        item
        for item in application.findall("provider")
        if attr(item, "name") == "androidx.startup.InitializationProvider"
    ]
    profile_receivers = [
        item
        for item in application.findall("receiver")
        if attr(item, "name") == "androidx.profileinstaller.ProfileInstallReceiver"
    ]
    if is_benchmark:
        if len(control_declarations) != 1 or attr(control_declarations[0], "protectionLevel") != "signature":
            raise AssertionError(f"{path}: benchmark control permission must be signature-only")
        if len(profileable) != 1 or attr(profileable[0], "shell") != "true":
            raise AssertionError(f"{path}: benchmark target must be profileable by shell")
        if len(fixture_providers) != 1:
            raise AssertionError(f"{path}: benchmark target must expose exactly one fixture provider")
        fixture = fixture_providers[0]
        if (
            attr(fixture, "exported") != "true"
            or attr(fixture, "permission") != CONTROL_PERMISSION
            or attr(fixture, "authorities") != f"{APP_ID}.benchmark.fixture"
        ):
            raise AssertionError(f"{path}: benchmark fixture provider boundary is invalid")
    elif control_declarations or profileable or fixture_providers:
        raise AssertionError(f"{path}: benchmark control surface leaked into a normal app variant")

    if has_profile_installer:
        if len(startup_providers) != 1 or attr(startup_providers[0], "exported") != "false":
            raise AssertionError(f"{path}: profile build requires one non-exported Startup provider")
        initializers = {
            attr(item, "name")
            for item in startup_providers[0].findall("meta-data")
            if attr(item, "value") == "androidx.startup"
        }
        if "androidx.profileinstaller.ProfileInstallerInitializer" not in initializers:
            raise AssertionError(f"{path}: ProfileInstallerInitializer metadata is missing")
        if len(profile_receivers) != 1:
            raise AssertionError(f"{path}: profile build requires ProfileInstallReceiver")
        receiver = profile_receivers[0]
        if attr(receiver, "exported") != "true" or attr(receiver, "permission") != "android.permission.DUMP":
            raise AssertionError(f"{path}: ProfileInstallReceiver must be shell-guarded")
    elif startup_providers or profile_receivers:
        raise AssertionError(f"{path}: profile installer leaked into a non-profile build")

    forbidden_services = {
        "androidx.room.MultiInstanceInvalidationService",
    }
    packaged_services = {
        attr(service, "name")
        for service in application.findall("service")
        if attr(service, "name")
    }
    unexpected_services = packaged_services & forbidden_services
    if unexpected_services:
        raise AssertionError(
            f"{path}: unused dependency services were not removed: "
            f"{sorted(unexpected_services)}"
        )

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
    require_component(
        application,
        "receiver",
        "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED",
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
    if has_profile_installer:
        allowed_exported_actions.update(
            {
                "androidx.profileinstaller.action.BENCHMARK_OPERATION",
                "androidx.profileinstaller.action.INSTALL_PROFILE",
                "androidx.profileinstaller.action.SAVE_PROFILE",
                "androidx.profileinstaller.action.SKIP_FILE",
            }
        )
    for tag in ("activity", "activity-alias", "receiver", "service", "provider"):
        for component in application.findall(tag):
            if attr(component, "exported") != "true":
                continue
            if component in fixture_providers:
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


def verify_macro_manifest(path: Path) -> None:
    root = ET.parse(path).getroot()
    if root.get("package") != MACRO_ID:
        raise AssertionError(f"{path}: unexpected macrobenchmark package")
    permissions = {
        attr(item, "name")
        for tag in ("uses-permission", "uses-permission-sdk-23")
        for item in root.findall(tag)
        if attr(item, "name")
    }
    expected_permissions = {
        CONTROL_PERMISSION,
        MACRO_DYNAMIC_RECEIVER_PERMISSION,
        "android.permission.INTERNET",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.REORDER_TASKS",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    }
    if permissions != expected_permissions:
        raise AssertionError(
            f"{path}: macrobenchmark permissions changed: {sorted(permissions ^ expected_permissions)}"
        )
    dynamic_declarations = [
        item
        for item in root.findall("permission")
        if attr(item, "name") == MACRO_DYNAMIC_RECEIVER_PERMISSION
    ]
    if (
        len(dynamic_declarations) != 1
        or attr(dynamic_declarations[0], "protectionLevel") != "signature"
    ):
        raise AssertionError(f"{path}: macrobenchmark dynamic receiver permission changed")
    instrumentations = root.findall("instrumentation")
    if len(instrumentations) != 1:
        raise AssertionError(f"{path}: expected exactly one instrumentation entry")
    instrumentation = instrumentations[0]
    if (
        attr(instrumentation, "name") != "androidx.test.runner.AndroidJUnitRunner"
        or attr(instrumentation, "targetPackage") != MACRO_ID
    ):
        raise AssertionError(f"{path}: macrobenchmark instrumentation target changed")
    queried_providers = {
        attr(item, "authorities")
        for queries in root.findall("queries")
        for item in queries.findall("provider")
        if attr(item, "authorities")
    }
    if f"{APP_ID}.benchmark.fixture" not in queried_providers:
        raise AssertionError(f"{path}: macrobenchmark fixture query is missing")
    application = root.find("application")
    if application is None or attr(application, "debuggable") != "true":
        raise AssertionError(f"{path}: macrobenchmark test process must be debuggable")
    if any(attr(item, "authorities") == f"{APP_ID}.benchmark.fixture" for item in application.findall("provider")):
        raise AssertionError(f"{path}: fixture provider must live only in the target APK")


for manifest in manifests:
    verify_manifest(manifest)
    print(f"Permission ledger passed: {manifest}")

for manifest in macro_manifests:
    verify_macro_manifest(manifest)
    print(f"Macrobenchmark permission boundary passed: {manifest}")

if aapt2:
    for apk in apks:
        badging = subprocess.check_output(
            [aapt2, "dump", "badging", str(apk)], text=True, stderr=subprocess.STDOUT
        )
        package_match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
        package_name = package_match.group(1) if package_match else None
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
        expected = allowed_permissions
        if package_name == MACRO_ID:
            expected = {
                CONTROL_PERMISSION,
                MACRO_DYNAMIC_RECEIVER_PERMISSION,
                "android.permission.INTERNET",
                "android.permission.QUERY_ALL_PACKAGES",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.REORDER_TASKS",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            }
        elif package_name != APP_ID:
            raise AssertionError(f"{apk}: unexpected APK package {package_name}")
        unexpected = apk_permissions - expected
        if unexpected:
            raise AssertionError(f"{apk}: unexpected packaged permissions: {sorted(unexpected)}")
        if package_name == APP_ID and apk_permissions & forbidden_permissions:
            raise AssertionError(f"{apk}: packaged a forbidden permission")
        print(f"Packaged permission ledger passed: {apk}")
PY
