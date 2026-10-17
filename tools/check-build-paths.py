#!/usr/bin/env python3
"""Fail if an artefact names the machine that built it.

A release APK once carried 25 distinct strings under the developer's home
directory, from Rust panic metadata compiled into `watchfiles`' extension module and from
the CycloneDX SBOM maturin writes beside it. None of it is secret, and none of it is
load-bearing, but an app whose entire pitch is that your ledger stays on your device
should not ship the maintainer's username inside it — and a path that appears in a panic
message is a path a user can be shown.

Upstream's own build paths are a different matter: Chaquopy's prebuilt wheels are compiled
on its maintainer's machine and there is nothing this repository can do about that, so
they are reported and allowed. What is refused is *our* build leaking *our* paths.

    tools/check-build-paths.py <apk|whl|imy> ...
"""
import io
import os
import re
import sys
import zipfile

# Absolute paths under a home directory, or the temporary directory cibuildwheel builds in.
PATH = re.compile(rb"/home/[a-z0-9_][a-z0-9_.-]*[/\w.-]*|/Users/[A-Za-z0-9_][\w.-]*[/\w.-]*")

# Compiled into dependencies we merely consume, on machines we do not control.
UPSTREAM = (
    b"/home/runner/work/",              # GitHub CI, for Chaquopy and Fava's own releases
    b"/Users/msmith/",                  # Chaquopy's maintainer, in the CPython build
    b"/Users/test/",                    # a placeholder in Chaquopy's prebuilt stdlib
    b"/home/smith/git/chaquo",          # Chaquopy's maintainer
    b"/home/users/.cache",              # a literal in watchfiles' default ignore list
    b"/home/tables/",                   # a sample path in Fava's bundled source map
    b"/home/ci/build/o4a",              # OpenCV's own release builder, in libopencv_java4.so
    b"/home/ci/opencv",                 # the same builder's source tree, in the same library
)

# The OpenCV entries are the only ones added since the scanner was written, and they are the
# same case as Chaquopy's: `libopencv_java4.so` comes from the published `org.opencv:opencv`
# artefact, is not built here, and carries its builder's paths in assertion strings. Checked
# before allowing rather than assumed -- every unallowed hit in an expert APK was inside that
# one library, and no other file in either edition produced one.

# What is deliberately *not* checked: `/tmp/cibw-run-<random>` in beancount's `_parser.so`.
# meson records the build prefix as a DT_RUNPATH, and the directory is a per-run temporary
# one — it names no user, no machine and no project, and it is gone before the build
# finishes. `patchelf --remove-rpath` drops the entry but leaves the string in `.dynstr`,
# so the only way to clear the text is to overwrite bytes in a shipped binary, which is a
# worse trade than a meaningless path. Recorded in docs/pending-verification.md.


def entries(data, name):
    """Yield (path, bytes) for a zip, descending into nested zips (Chaquopy's .imy)."""
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        yield name, data
        return
    for member in archive.namelist():
        try:
            inner = archive.read(member)
        except (RuntimeError, zipfile.BadZipFile, OSError):
            continue
        if member.endswith((".imy", ".zip", ".whl", ".apk")):
            yield from entries(inner, f"{name}!{member}")
        else:
            yield f"{name}!{member}", inner


def main(paths):
    ours, theirs = {}, set()
    for path in paths:
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError as problem:
            print(f"::error::cannot read {path}: {problem}", file=sys.stderr)
            return 1
        for where, blob in entries(data, os.path.basename(path)):
            for hit in set(PATH.findall(blob)):
                if hit.startswith(UPSTREAM):
                    theirs.add(hit)
                else:
                    ours.setdefault(hit, set()).add(where)

    if theirs:
        print(f"{len(theirs)} build paths from upstream dependencies, allowed:")
        for hit in sorted(theirs)[:6]:
            print(f"    {hit.decode(errors='replace')}")

    if not ours:
        print(f"no build-machine paths of our own in {len(paths)} artefact(s)")
        return 0

    print(f"::error::{len(ours)} build-machine paths in artefacts we build:", file=sys.stderr)
    for hit, where in sorted(ours.items()):
        print(f"  {hit.decode(errors='replace')}", file=sys.stderr)
        for one in sorted(where)[:2]:
            print(f"      {one}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
