"""Compares two builds of the same commit, entry by entry.

A byte-identical rebuild is what lets somebody else confirm that a published APK was built
from the source it claims to be. Without it, "this is the app you can read the source of" is
a promise nobody can check.

The interesting output is not "they differ" — that is easy and useless. It is **which entries
differ and how**, because the causes have different fixes: a zip timestamp is a packaging
setting, a `.dex` that differs is a compiler or ordering problem, and a Python `.pyc` that
differs is an embedded build path or mtime.

    python3 tools/check-reproducible.py first.apk second.apk
"""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entries(path: Path) -> dict[str, zipfile.ZipInfo]:
    with zipfile.ZipFile(path) as archive:
        return {info.filename: info for info in archive.infolist()}


def content_of(path: Path, name: str) -> bytes:
    with zipfile.ZipFile(path) as archive:
        return archive.read(name)


def pyc_header_only(left: bytes, right: bytes) -> bool:
    """Whether two `.pyc` files differ only in their source-mtime header.

    CPython writes a 16-byte header: magic, flags, then either the source mtime and size
    (flags 0) or a source hash (flags 2). With timestamp invalidation, reinstalling identical
    source produces byte-identical *code* under a different header — so the compiled output
    is reproducible and the container is not.

    Worth separating from a real difference: one means "nothing about this build changed",
    the other means something did.
    """
    if len(left) != len(right) or len(left) < 16:
        return False
    return left[16:] == right[16:]


def nested_report(left: bytes, right: bytes) -> str | None:
    """Looks inside a Chaquopy `.imy` bundle, which is itself a zip."""
    try:
        a = zipfile.ZipFile(io.BytesIO(left))
        b = zipfile.ZipFile(io.BytesIO(right))
    except zipfile.BadZipFile:
        return None

    names_a = {i.filename for i in a.infolist()}
    names_b = {i.filename for i in b.infolist()}
    added = sorted(names_b - names_a)
    removed = sorted(names_a - names_b)

    header_only = 0
    real: list[str] = []
    for name in sorted(names_a & names_b):
        first, second = a.read(name), b.read(name)
        if first == second:
            continue
        if name.endswith(".pyc") and pyc_header_only(first, second):
            header_only += 1
        else:
            real.append(name)

    parts = []
    if added or removed:
        # This is the serious one: the set of packages changed between builds.
        parts.append(f"{len(added)} added, {len(removed)} removed")
        for name in (added + removed)[:6]:
            parts.append(f"      {name}")
    if header_only:
        parts.append(f"{header_only} .pyc differ only in their mtime header (code identical)")
    if real:
        parts.append(f"{len(real)} differ in content: {real[:4]}")
    return "; ".join(parts) if parts else None


def classify(name: str) -> str:
    if name.endswith(".dex"):
        return "compiled code"
    if name.endswith(".so"):
        return "native library"
    if name.endswith(".imy"):
        return "python bundle"
    if name.startswith("META-INF/"):
        return "signature or manifest"
    if name.endswith(".arsc") or name.startswith("res/"):
        return "resources"
    return "other"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first")
    parser.add_argument("second")
    parser.add_argument("--max-report", type=int, default=25)
    args = parser.parse_args()

    first, second = Path(args.first), Path(args.second)
    for path in (first, second):
        if not path.is_file():
            print(f"missing: {path}", file=sys.stderr)
            return 2

    a, b = sha256(first), sha256(second)
    print(f"{a}  {first.name}")
    print(f"{b}  {second.name}")
    if a == b:
        print("\nIdentical. The build is reproducible.")
        return 0

    print("\nThe APKs differ. Comparing entries.\n")
    left, right = entries(first), entries(second)

    only_left = sorted(set(left) - set(right))
    only_right = sorted(set(right) - set(left))
    for name in only_left[: args.max_report]:
        print(f"  only in {first.name}: {name}")
    for name in only_right[: args.max_report]:
        print(f"  only in {second.name}: {name}")

    differing: list[tuple[str, str]] = []
    timestamps_only: list[str] = []
    for name in sorted(set(left) & set(right)):
        same_bytes = content_of(first, name) == content_of(second, name)
        same_stamp = left[name].date_time == right[name].date_time
        if same_bytes and same_stamp:
            continue
        if same_bytes:
            # Same content, different recorded time: a packaging detail, not a build one.
            timestamps_only.append(name)
        else:
            differing.append((name, classify(name)))

    if timestamps_only:
        print(f"\n{len(timestamps_only)} entries differ only in their zip timestamp:")
        for name in timestamps_only[: args.max_report]:
            print(f"  {name}")

    if differing:
        print(f"\n{len(differing)} entries differ in content:")
        by_kind: dict[str, int] = {}
        for name, kind in differing:
            by_kind[kind] = by_kind.get(kind, 0) + 1
        for kind, count in sorted(by_kind.items(), key=lambda item: -item[1]):
            print(f"  {count:5d}  {kind}")
        print("\n  first few:")
        for name, kind in differing[: args.max_report]:
            size_a = left[name].file_size
            size_b = right[name].file_size
            note = "" if size_a == size_b else f" (sizes {size_a} vs {size_b})"
            print(f"    [{kind}] {name}{note}")
            inner = nested_report(content_of(first, name), content_of(second, name))
            if inner:
                print(f"        {inner}")

    print("\nNot reproducible.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
