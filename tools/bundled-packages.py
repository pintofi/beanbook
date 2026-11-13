#!/usr/bin/env python3
"""Writes, or checks, the list of Python packages the APK already carries.

    python3 tools/bundled-packages.py           rewrite app/src/expert/assets/packages/bundled.txt
    python3 tools/bundled-packages.py --check   exit 1 if the file is not what the locks say

The Expert edition fetches Python packages on request and checks them for updates. It must never fetch
or update one the app ships (Beancount, Fava and everything they need): a second copy ahead of the
tested one on sys.path is a way to make a working app stop working. The names come from the two files
that pin what the APK bundles, engine-local/requirements.lock and engine-local/wheels.lock, so the
list cannot drift from them.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/expert/assets/packages/bundled.txt"


def names() -> list[str]:
    found: set[str] = set()
    for line in (ROOT / "engine-local/requirements.lock").read_text().splitlines():
        m = re.match(r"^([A-Za-z0-9][A-Za-z0-9._-]*)==", line)
        if m:
            found.add(re.sub(r"[-_.]+", "-", m.group(1)).lower())
    for line in (ROOT / "engine-local/wheels.lock").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            found.add(re.sub(r"[-_.]+", "-", line.split()[0]).lower())
    return sorted(found)


def main() -> int:
    wanted = "\n".join(names()) + "\n"
    if "--check" in sys.argv[1:]:
        if not OUT.is_file() or OUT.read_text() != wanted:
            print(f"bundled: {OUT.relative_to(ROOT)} is not what the locks say; run tools/bundled-packages.py",
                  file=sys.stderr)
            return 1
        print(f"bundled: {len(names())} packages, the list agrees with the locks")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(wanted)
    print(f"bundled: wrote {len(names())} names to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
