#!/usr/bin/env python3
"""Check APK native library layout without depending on host ELF utilities."""

import argparse
import hashlib
from pathlib import Path
import struct
import sys
import zipfile


def check(apk_path, aab=False, prebuilt=None):
    count = 0
    machines = {"arm64-v8a": 183, "x86_64": 62}
    name = "libcompositor_android.so"
    found = set()
    with zipfile.ZipFile(apk_path) as apk, open(apk_path, "rb") as raw:
        for entry in apk.infolist():
            parts = entry.filename.split("/")
            native_path = (entry.filename.startswith("lib/") if not aab else
                           len(parts) >= 4 and parts[-3] == "lib")
            if not native_path or not entry.filename.endswith(".so"):
                continue
            if not aab:
                if entry.compress_type != zipfile.ZIP_STORED:
                    raise ValueError(f"{entry.filename}: native library must be stored uncompressed")
                raw.seek(entry.header_offset)
                header = raw.read(30)
                name_length, extra_length = struct.unpack_from("<HH", header, 26)
                data_offset = entry.header_offset + 30 + name_length + extra_length
                if data_offset % 16384:
                    raise ValueError(f"{entry.filename}: APK library offset is not aligned to 16 KiB")
            elf = apk.read(entry)
            if elf[:6] != b"\x7fELF\x02\x01" or len(elf) < 64:
                raise ValueError(f"{entry.filename}: expected a 64-bit little-endian ELF library")
            if prebuilt is not None and parts[-1] == name:
                abi = parts[-2]
                expected_path = f"{'base/' if aab else ''}lib/{abi}/{name}"
                if entry.filename != expected_path or abi not in machines or abi in found:
                    raise ValueError(f"{entry.filename}: unexpected or duplicate compositor library")
                if struct.unpack_from("<H", elf, 18)[0] != machines[abi]:
                    raise ValueError(f"{entry.filename}: ELF machine does not match {abi}")
                expected = (prebuilt / abi / name).read_bytes()
                if hashlib.sha256(elf).digest() != hashlib.sha256(expected).digest():
                    raise ValueError(f"{entry.filename}: SHA-256 differs from the repository prebuilt library")
                found.add(abi)
            offset = struct.unpack_from("<Q", elf, 32)[0]
            entry_size, entries = struct.unpack_from("<HH", elf, 54)
            if entry_size < 56 or offset + entry_size * entries > len(elf):
                raise ValueError(f"{entry.filename}: invalid ELF program headers")
            loads = 0
            for index in range(entries):
                segment = struct.unpack_from("<IIQQQQQQ", elf, offset + index * entry_size)
                if segment[0] != 1:
                    continue
                loads += 1
                file_offset, address, alignment = segment[2], segment[3], segment[7]
                if alignment < 16384 or alignment & (alignment - 1) or file_offset % alignment != address % alignment:
                    raise ValueError(f"{entry.filename}: ELF LOAD segment lacks 16 KiB page compatibility")
            if not loads:
                raise ValueError(f"{entry.filename}: no ELF LOAD segments")
            count += 1
    if not count:
        raise ValueError("artifact contains no native libraries")
    if prebuilt is not None and found != machines.keys():
        missing = ", ".join(sorted(machines.keys() - found))
        raise ValueError(f"artifact is missing compositor libraries for: {missing}")
    if aab:
        print(f"App Bundle passed: {count} native libraries have ELF LOAD segments aligned for 16 KiB pages")
    else:
        print(f"Native packaging passed: {count} libraries, ELF and APK offsets aligned for 16 KiB pages")
    if prebuilt is not None:
        print("Both compositor ABIs match the repository libraries by ELF machine and SHA-256")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aab", action="store_true", help="check an Android App Bundle")
    parser.add_argument("--prebuilt", action="store_true", help="require both repository compositor libraries")
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    prebuilt = Path(__file__).resolve().parents[1] / "apps/android/app/src/main/jniLibs"
    try:
        check(args.artifact, aab=args.aab, prebuilt=prebuilt if args.prebuilt else None)
    except (ValueError, OSError, zipfile.BadZipFile, struct.error) as error:
        sys.exit(str(error))
