#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later

"""Apply and verify AuroraSMS's non-root AndroidX Benchmark compatibility patch."""

from __future__ import annotations

import argparse
import copy
import hashlib
import struct
import sys
import zipfile
import zlib
from pathlib import Path


ANDROIDX_ROOT_PROBE = b"su root id"
NON_ROOT_PROBE = b"su root<&-"


def _dex_entries(apk: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    return [
        entry
        for entry in apk.infolist()
        if entry.filename.startswith("classes") and entry.filename.endswith(".dex")
    ]


def _validate_dex_header(name: str, data: bytes) -> None:
    if len(data) < 32 or not data.startswith(b"dex\n"):
        raise ValueError(f"{name}: invalid DEX header")
    expected_signature = hashlib.sha1(data[32:]).digest()
    if data[12:32] != expected_signature:
        raise ValueError(f"{name}: invalid DEX SHA-1 signature")
    expected_checksum = zlib.adler32(data[12:]) & 0xFFFFFFFF
    if data[8:12] != struct.pack("<I", expected_checksum):
        raise ValueError(f"{name}: invalid DEX Adler-32 checksum")


def _read_dex_string(data: bytes, string_data_offset: int) -> bytes:
    cursor = string_data_offset
    while True:
        if cursor >= len(data):
            raise ValueError("DEX string length extends beyond the file")
        current = data[cursor]
        cursor += 1
        if current & 0x80 == 0:
            break
    terminator = data.find(b"\0", cursor)
    if terminator < 0:
        raise ValueError("DEX string is missing its terminator")
    return data[cursor:terminator]


def _dex_string_neighbors(data: bytes, target: bytes) -> tuple[bytes | None, bytes | None]:
    string_count, string_ids_offset = struct.unpack_from("<II", data, 56)
    if string_ids_offset + string_count * 4 > len(data):
        raise ValueError("DEX string ID table extends beyond the file")
    strings = [
        _read_dex_string(data, struct.unpack_from("<I", data, string_ids_offset + index * 4)[0])
        for index in range(string_count)
    ]
    matches = [index for index, value in enumerate(strings) if value == target]
    if len(matches) != 1:
        raise ValueError(
            f"Expected one DEX string-table match for {target!r}; found {len(matches)}"
        )
    index = matches[0]
    previous = strings[index - 1] if index > 0 else None
    following = strings[index + 1] if index + 1 < len(strings) else None
    return previous, following


def _validate_replacement_order(data: bytes, target: bytes) -> None:
    previous, following = _dex_string_neighbors(data, target)
    if previous is not None and not previous < target:
        raise ValueError(
            f"DEX replacement {target!r} is not ordered after {previous!r}"
        )
    if following is not None and not target < following:
        raise ValueError(
            f"DEX replacement {target!r} is not ordered before {following!r}"
        )


def _patch_dex(name: str, data: bytes) -> tuple[bytes, int]:
    _validate_dex_header(name, data)
    old_count = data.count(ANDROIDX_ROOT_PROBE)
    new_count = data.count(NON_ROOT_PROBE)
    if new_count:
        raise ValueError(f"{name}: non-root probe already exists in unpatched input")
    if not old_count:
        return data, 0

    _validate_replacement_order(data, ANDROIDX_ROOT_PROBE)
    patched = bytearray(data.replace(ANDROIDX_ROOT_PROBE, NON_ROOT_PROBE))
    _validate_replacement_order(patched, NON_ROOT_PROBE)
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    patched[8:12] = struct.pack("<I", zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    _validate_dex_header(name, patched)
    return bytes(patched), old_count


def _is_v1_signature_entry(name: str) -> bool:
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    return upper == "META-INF/MANIFEST.MF" or upper.endswith(
        (".SF", ".RSA", ".DSA", ".EC")
    )


def patch_apk(source: Path, destination: Path) -> None:
    if source.resolve() == destination.resolve():
        raise ValueError("Source and destination APKs must differ")
    destination.parent.mkdir(parents=True, exist_ok=True)

    total_replacements = 0
    with zipfile.ZipFile(source, "r") as input_apk, zipfile.ZipFile(
        destination,
        "w",
        allowZip64=True,
    ) as output_apk:
        dex_names = {entry.filename for entry in _dex_entries(input_apk)}
        if not dex_names:
            raise ValueError("Input APK contains no DEX entries")

        for entry in input_apk.infolist():
            if _is_v1_signature_entry(entry.filename):
                continue
            data = input_apk.read(entry)
            if entry.filename in dex_names:
                data, replacements = _patch_dex(entry.filename, data)
                total_replacements += replacements

            output_entry = copy.copy(entry)
            output_entry.extra = b""
            output_apk.writestr(
                output_entry,
                data,
                compress_type=entry.compress_type,
                compresslevel=9,
            )

    if total_replacements != 1:
        destination.unlink(missing_ok=True)
        raise ValueError(
            "Expected exactly one AndroidX root probe; "
            f"patched {total_replacements} occurrences"
        )


def verify_apk(apk_path: Path) -> None:
    old_count = 0
    new_count = 0
    with zipfile.ZipFile(apk_path, "r") as apk:
        dex_entries = _dex_entries(apk)
        if not dex_entries:
            raise ValueError("APK contains no DEX entries")
        for entry in dex_entries:
            data = apk.read(entry)
            _validate_dex_header(entry.filename, data)
            old_count += data.count(ANDROIDX_ROOT_PROBE)
            new_count += data.count(NON_ROOT_PROBE)
            if NON_ROOT_PROBE in data:
                _validate_replacement_order(data, NON_ROOT_PROBE)

    if old_count != 0 or new_count != 1:
        raise ValueError(
            "Non-root probe verification failed: "
            f"root_probe={old_count}, non_root_probe={new_count}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    patch_parser = subparsers.add_parser("patch")
    patch_parser.add_argument("source", type=Path)
    patch_parser.add_argument("destination", type=Path)

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("apk", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "patch":
            patch_apk(args.source, args.destination)
            print("Patched exactly one AndroidX root probe to the non-root path.")
        else:
            verify_apk(args.apk)
            print("Verified one non-root probe and valid DEX headers.")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Benchmark APK patch failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
