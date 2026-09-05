"""Builds the licence notices the app shows, from what is actually in the APK.

The app is GPL-2.0-only and bundles 36 Python packages under MIT, BSD, X11, LGPL-2.1 and
PSF, plus AndroidX and friends under Apache-2.0. Every one of those licences requires the
notice to travel with the software, and the GPL requires the terms be offered to the user.
Until this existed, 34 licence texts shipped inside a zip inside `assets/`, where nobody
could read them, and two shipped nowhere at all (docs/milestones/m14-part11.md).

The text comes from the packages themselves wherever they carry it, so this cannot drift
from what is installed. Where a package ships none, a canonical copy of its licence is used
from `licences/`, and that substitution is stated in the output rather than hidden — a
notice that silently invents its own provenance is worse than no notice.

    ./gradlew :app:assembleExpertRelease
    python3 tools/licences.py --apk <apk>          # writes app/src/main/assets/licences.json

`--check` regenerates and compares instead of writing, which is what CI runs: a generated
file that is committed is a file that goes stale.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "app/src/main/assets/licences.json"

LICENCE_FILENAMES = re.compile(
    r"^(COPYING|COPYING\.txt|LICEN[CS]E|LICEN[CS]E\.(txt|md|rst)|NOTICE)$", re.I
)

# Packages that ship no licence text of their own, with the licence they declare and why the
# substitution is honest. beancount's sdist has COPYING; upstream declares no `license-files`,
# so neither its own PyPI wheel nor the one we cross-build carries it.
SUBSTITUTES = {
    "beancount": ("GPL-2.0-only", "upstream declares no license-files, so no wheel carries it"),
    "ply": ("BSD-3-Clause", "upstream ships no licence file in the wheel"),
}


def python_notices(apk: Path) -> list[dict]:
    """One entry per Python package in the APK, with its own licence text where it has one."""
    versions: dict[str, str] = {}
    texts: dict[str, str] = {}
    with zipfile.ZipFile(apk) as outer:
        for name in outer.namelist():
            if not name.startswith("assets/chaquopy/requirements"):
                continue
            with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                for entry in inner.namelist():
                    match = re.match(r"([A-Za-z0-9_.\-]+)-([0-9][^/]*)\.dist-info/(.+)$", entry)
                    if not match:
                        continue
                    key = match.group(1).lower().replace("-", "_")
                    versions[key] = match.group(2)
                    tail = match.group(3)
                    if LICENCE_FILENAMES.match(tail.rsplit("/", 1)[-1]) and key not in texts:
                        texts[key] = inner.read(entry).decode("utf-8", "replace").strip()

    out = []
    for name in sorted(versions):
        entry = {"name": name, "version": versions[name], "kind": "python"}
        if name in texts:
            entry["text"] = texts[name]
            entry["source"] = "shipped with the package"
        else:
            licence, why = SUBSTITUTES.get(name, (None, None))
            if licence is None:
                print(f"::error::{name} ships no licence text and has no substitute", file=sys.stderr)
                raise SystemExit(1)
            entry["text"] = canonical(licence)
            entry["source"] = f"{licence}, supplied here: {why}"
        out.append(entry)
    return out


def canonical(licence: str) -> str:
    path = REPO / "licences" / f"{licence}.txt"
    if not path.is_file():
        print(f"::error::no canonical text for {licence} at {path}", file=sys.stderr)
        raise SystemExit(1)
    return path.read_text(encoding="utf-8").strip()


def android_notices() -> list[dict]:
    """The Android and JVM dependencies, grouped by licence rather than listed one by one.

    Their licence texts are not in the build the way a Python package's are, and there is no
    honest way to extract per-artifact notices from a Gradle module list. So they are named,
    with the full text of the licence they are under, which is what Apache-2.0 §4 and the
    BSD/MIT notice clauses actually require.
    """
    sbom = (REPO / "docs/sbom.md").read_text(encoding="utf-8")
    section = sbom.split("## Android and JVM dependencies", 1)[1].split("\n## ", 1)[0]
    groups: dict[str, list[str]] = {}
    for coordinate, version, licence in re.findall(
        r"^\| `([^`]+)` \| (\S+) \| ([^|]+?) \|", section, re.M
    ):
        groups.setdefault(licence.strip(), []).append(f"{coordinate} {version}")

    return [
        {
            "name": licence,
            "version": f"{len(members)} dependencies",
            "kind": "android",
            "members": sorted(members),
            "text": canonical(licence),
            "source": "canonical licence text",
        }
        for licence, members in sorted(groups.items())
    ]


def build(apk: Path) -> dict:
    return {
        "app": {
            "name": "Beanbook",
            "licence": "GPL-2.0-only",
            "text": (REPO / "COPYING").read_text(encoding="utf-8").strip(),
            "note": (
                "This app is free software under the GNU General Public License, version 2, "
                "and that version only, because Beancount is licensed that way and "
                "explicitly not \"or later\". You may use, study, share and change it, and "
                "anything you distribute that is built from it must come with the same "
                "freedoms and its source."
            ),
        },
        "components": python_notices(apk) + android_notices(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--check", action="store_true", help="compare instead of writing")
    args = parser.parse_args()

    data = build(Path(args.apk))
    rendered = json.dumps(data, indent=1, ensure_ascii=False, sort_keys=True) + "\n"

    if args.check:
        if not OUT.is_file():
            print(f"::error::{OUT} does not exist; run tools/licences.py", file=sys.stderr)
            return 1
        if OUT.read_text(encoding="utf-8") != rendered:
            print(f"::error::{OUT.relative_to(REPO)} is stale; regenerate it with "
                  "tools/licences.py --apk <apk>", file=sys.stderr)
            return 1
        print(f"licences.json matches the APK: {len(data['components'])} components")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(rendered, encoding="utf-8")
    kinds = {}
    for component in data["components"]:
        kinds[component["kind"]] = kinds.get(component["kind"], 0) + 1
    print(f"{OUT.relative_to(REPO)}: {kinds} ({len(rendered):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
