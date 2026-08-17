#!/usr/bin/env python3
"""Verify that no bundled native library needs a symbol newer than minSdk.

Every .so in an APK is checked against the NDK's stub libraries for the target API
level. A *strong* undefined symbol that the platform does not provide at that API means
the library fails to load on those devices — usually only discovered on a user's phone.
Weak undefined symbols are allowed: the dynamic linker leaves them null and the code is
expected to check at runtime (CPython references memfd_create this way).

Usage:
    tools/check-minsdk-symbols.py <apk> --api 28 [--abi arm64-v8a] [--ndk <path>]

Exit status is non-zero if any strong symbol is unsatisfied, so this can run as a
release gate in CI.
"""

from __future__ import annotations

import argparse
import collections
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ABI_TO_TRIPLE = {
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
    "armeabi-v7a": "arm-linux-androideabi",
    "x86": "i686-linux-android",
}


def dyn_syms(readelf: str, path: Path) -> tuple[set[str], set[str], set[str]]:
    """Return (defined, undefined_strong, undefined_weak) for one shared object."""
    out = subprocess.run(
        [readelf, "--dyn-syms", "--wide", str(path)],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    defined: set[str] = set()
    und_strong: set[str] = set()
    und_weak: set[str] = set()
    for line in out.splitlines()[3:]:
        parts = line.split()
        if len(parts) < 8:
            continue
        bind, ndx, name = parts[4], parts[6], parts[7].split("@")[0]
        if ndx == "UND":
            (und_weak if bind == "WEAK" else und_strong).add(name)
        else:
            defined.add(name)
    return defined, und_strong, und_weak


def extract_native_libs(apk: Path, abi: str, dest: Path) -> list[Path]:
    """Pull every .so out of the APK, including those inside Chaquopy's .imy archives."""
    dest.mkdir(parents=True, exist_ok=True)
    found: list[Path] = []
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            name = info.filename
            if name.startswith(f"lib/{abi}/") and name.endswith(".so"):
                target = dest / Path(name).name
                target.write_bytes(z.read(name))
                found.append(target)
            elif name.endswith(".imy") and (abi in name or "common" in name):
                inner_dir = dest / (Path(name).stem + "_imy")
                inner_dir.mkdir(exist_ok=True)
                try:
                    with zipfile.ZipFile(
                        __import__("io").BytesIO(z.read(name))
                    ) as inner:
                        for entry in inner.infolist():
                            if entry.filename.endswith(".so"):
                                target = dest / Path(entry.filename).name
                                target.write_bytes(inner.read(entry.filename))
                                found.append(target)
                except zipfile.BadZipFile:
                    continue
    return found


def find_ndk() -> Path | None:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None
    ndk_root = Path(home) / "ndk"
    if not ndk_root.is_dir():
        return None
    versions = sorted(ndk_root.iterdir(), reverse=True)
    return versions[0] if versions else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    ap.add_argument("--api", type=int, required=True, help="minSdk to verify against")
    ap.add_argument("--abi", default="arm64-v8a", choices=sorted(ABI_TO_TRIPLE))
    ap.add_argument("--ndk", type=Path, default=None)
    args = ap.parse_args()

    ndk = args.ndk or find_ndk()
    if ndk is None:
        print("error: NDK not found; pass --ndk or set ANDROID_HOME", file=sys.stderr)
        return 2

    prebuilt = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    readelf = prebuilt / "bin/llvm-readelf"
    stubs = prebuilt / f"sysroot/usr/lib/{ABI_TO_TRIPLE[args.abi]}/{args.api}"
    if not readelf.exists() or not stubs.is_dir():
        print(f"error: missing {readelf} or {stubs}", file=sys.stderr)
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="minsdk-check-"))
    try:
        libs = extract_native_libs(args.apk, args.abi, tmp / "so")
        if not libs:
            print(f"error: no {args.abi} native libraries found in {args.apk}", file=sys.stderr)
            return 2

        platform: set[str] = set()
        for stub in stubs.glob("*.so"):
            platform |= dyn_syms(str(readelf), stub)[0]

        provided: set[str] = set()
        for lib in libs:
            provided |= dyn_syms(str(readelf), lib)[0]

        unsatisfied: dict[str, set[str]] = collections.defaultdict(set)
        weak_gaps: collections.Counter[str] = collections.Counter()
        for lib in libs:
            _defined, strong, weak = dyn_syms(str(readelf), lib)
            for sym in strong:
                if sym not in platform and sym not in provided:
                    unsatisfied[lib.name].add(sym)
            for sym in weak:
                if sym not in platform and sym not in provided:
                    weak_gaps[sym] += 1

        print(f"apk            : {args.apk.name}")
        print(f"abi / api      : {args.abi} / {args.api}")
        print(f"libraries      : {len(libs)}")
        print(f"platform syms  : {len(platform)}")
        print(f"weak gaps      : {sum(weak_gaps.values())} refs "
              f"({', '.join(weak_gaps) or 'none'}) — allowed, runtime-guarded")

        if unsatisfied:
            print(f"FAIL: {sum(len(v) for v in unsatisfied.values())} strong symbols "
                  f"unavailable at API {args.api}:")
            for lib, syms in sorted(unsatisfied.items()):
                print(f"  {lib}: {', '.join(sorted(syms))}")
            return 1

        print(f"PASS: every strong symbol is satisfied at API {args.api}")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
