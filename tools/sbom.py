"""Generates the SBOM and licence inventory from what is actually in the build.

Read from the version catalog, the Chaquopy pip declarations, and the Python packages that
ended up in the APK — not from a hand-kept list, which is a list that silently stops being
true. Anything it cannot determine is reported as `unverified` rather than guessed: the
licence position of this app is load-bearing (GPL-2.0-only, see docs/architecture.md §7.1)
and a plausible wrong answer is worse than an admitted gap.

    python3 tools/sbom.py --apk app/build/outputs/apk/expert/release/<apk> --out docs/sbom.md
"""

from __future__ import annotations

import argparse
import io
import re
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# Licences that cannot be resolved from metadata are looked up here, with the reason. Each
# entry is a statement someone checked, not a guess.
KNOWN_LICENCES = {
    "androidx": ("Apache-2.0", "AndroidX is Apache-2.0 throughout; see architecture.md §7.1"),
    "org.jetbrains.kotlin": ("Apache-2.0", "Kotlin stdlib and compiler runtime"),
    "org.jetbrains.kotlinx": ("Apache-2.0", "kotlinx.coroutines"),
    "com.google.android.material": ("Apache-2.0", "Material Components"),
    "com.lambdapioneer.argon2kt": ("Apache-2.0", "argon2kt"),
    "me.saket.telephoto": ("Apache-2.0", "Telephoto, zoom and pan for Compose; licence in its POM, checked 2026-09-14"),
    "sh.calvin.reorderable": ("Apache-2.0", "Reorderable, drag to reorder in Compose lists; licence in its POM, checked 2026-09-14"),
    "org.opencv": ("Apache-2.0", "OpenCV is Apache-2.0 from 4.5.0; the document scanner's edge detection"),
    "com.chaquo.python": ("MIT", "Chaquopy runtime; the Gradle plugin is separately licensed"),
    "junit": ("EPL-1.0", "test only, not shipped"),
}

PYTHON_LICENCES = {
    "beancount": ("GPL-2.0-only", "explicitly not 'or later' — the reason this app is GPL-2.0-only"),
    "beanquery": ("GPL-2.0-only", "same upstream as beancount"),
    "beangulp": ("GPL-2.0-only", "same upstream as beancount"),
    "fava": ("MIT", ""),
    "babel": ("BSD-3-Clause", ""),
    "flask": ("BSD-3-Clause", ""),
    "werkzeug": ("BSD-3-Clause", ""),
    "jinja2": ("BSD-3-Clause", ""),
    "markupsafe": ("BSD-3-Clause", ""),
    "click": ("BSD-3-Clause", ""),
    "watchfiles": ("MIT", ""),
    "lxml": ("BSD-3-Clause", ""),
    "pytz": ("MIT", ""),
    "chardet": ("LGPL-2.1", "used by python-magic/beangulp for encoding detection"),
    "regex": ("Apache-2.0", ""),
    "ply": ("BSD-3-Clause", ""),
    "python_magic": ("MIT", ""),
    "typing_extensions": ("PSF-2.0", ""),
    "simplecountingsort": ("MIT", ""),
    # Chaquopy's repackages of the C libraries carry no `License:` metadata, but they do ship
    # the licence text itself in `dist-info/COPYING`, which is where these were read from
    # (docs/milestones/m14-part11.md). Both are GPL-2.0-compatible, which is what the app
    # needs; libxslt's is the X11 variant, with the clause about not using the author's name
    # in advertising, and the FSF lists that as compatible too.
    "chaquopy_libxml2": ("MIT", "dist-info/COPYING: the Expat text, (C) 1998-2012 Daniel Veillard"),
    "chaquopy_libxslt": (
        "X11",
        "dist-info/COPYING: MIT/X Consortium text for libxslt and libexslt, with the "
        "no-advertising clause",
    ),
}


def gradle_libraries() -> list[tuple[str, str]]:
    """(coordinate, version) for every library in the version catalog."""
    toml = (REPO / "gradle/libs.versions.toml").read_text()
    versions = dict(re.findall(r'^\s*([\w-]+)\s*=\s*"([^"]+)"', toml, re.M))
    out = []
    for name, body in re.findall(r"^\s*([\w-]+)\s*=\s*\{([^}]*)\}", toml, re.M):
        module = re.search(r'module\s*=\s*"([^"]+)"', body)
        if not module:
            continue
        ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
        literal = re.search(r'version\s*=\s*"([^"]+)"', body)
        version = versions.get(ref.group(1), "?") if ref else (
            literal.group(1) if literal else "?"
        )
        out.append((module.group(1), version))
    return sorted(set(out))


# Filenames a package uses for the licence text itself, as opposed to a declaration of which
# licence it is. MIT, BSD and X11 all require the notice to travel with the software, so
# whether this file is present is a distribution question, not a bookkeeping one.
LICENCE_FILENAMES = re.compile(
    r"^(COPYING|COPYING\.txt|LICEN[CS]E|LICEN[CS]E\.(txt|md|rst)|NOTICE|AUTHORS)$", re.I
)


def python_packages(apk: Path) -> list[tuple[str, str, str, bool]]:
    """(package, version, licence, ships its own licence text) for everything in the APK.

    The licence is read from each package's own `dist-info/METADATA`, which is already in
    the APK. Reading it beats maintaining a table: the table is what goes stale when a
    transitive dependency changes underneath a version bump.
    """
    found: dict[str, tuple[str, str]] = {}
    texts: set[str] = set()
    with zipfile.ZipFile(apk) as outer:
        for name in outer.namelist():
            if not name.startswith("assets/chaquopy/requirements"):
                continue
            with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                for entry in inner.namelist():
                    stem = re.match(r"([A-Za-z0-9_.\-]+)-([0-9][^/]*)\.dist-info/(.+)$", entry)
                    if not stem:
                        continue
                    key = stem.group(1).lower().replace("-", "_")
                    tail = stem.group(3)
                    if tail == "METADATA":
                        metadata = inner.read(entry).decode("utf-8", "replace")
                        found[key] = (stem.group(2), licence_from_metadata(metadata))
                    elif LICENCE_FILENAMES.match(tail.rsplit("/", 1)[-1]):
                        texts.add(key)
    return sorted((name, v, lic, name in texts) for name, (v, lic) in found.items())


def licence_from_metadata(metadata: str) -> str:
    """The licence a Python package declares about itself."""
    # A classifier is the more reliable of the two: `License:` is free text and is sometimes
    # a whole paragraph, or the word "see LICENSE".
    classifiers = re.findall(r"^Classifier: License :: (.+)$", metadata, re.M)
    for classifier in classifiers:
        if "OSI Approved ::" in classifier:
            return classifier.split("::")[-1].strip()
    if classifiers:
        return classifiers[0].strip()
    expression = re.search(r"^License-Expression: (.+)$", metadata, re.M)
    if expression:
        return expression.group(1).strip()
    declared = re.search(r"^License: (.+)$", metadata, re.M)
    if declared and len(declared.group(1)) < 60:
        return declared.group(1).strip()
    return "unverified"


def licence_for(coordinate: str, table: dict) -> tuple[str, str]:
    for prefix, value in table.items():
        if coordinate.startswith(prefix) or coordinate == prefix:
            return value
    return ("unverified", "not resolved from metadata; check before release")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", default="docs/sbom.md")
    args = parser.parse_args()

    apk = Path(args.apk)
    gradle = gradle_libraries()
    python = python_packages(apk)

    lines = [
        "# Software bill of materials",
        "",
        "Generated by `tools/sbom.py` from the version catalog and the packages actually",
        "present in the release APK. Do not edit by hand — a hand-kept inventory is one that",
        "silently stops being true.",
        "",
        f"Source APK: `{apk.name}`",
        "",
        "## Why this matters here",
        "",
        "The app is **GPL-2.0-only**, because Beancount, beanquery and beangulp are",
        "GPL-2.0-only and explicitly not \"or later\". Apache-2.0 is not compatible with",
        "GPL-2.0-only, and AndroidX — which is most of this table — is Apache-2.0. That",
        "tension is unresolved and is tracked as risk R9 in",
        "[architecture.md](architecture.md) §7.1. This file is the inventory it will have to",
        "be decided against; it is not a claim that the position is settled.",
        "",
        "## Android and JVM dependencies",
        "",
        "| Coordinate | Version | Licence | Note |",
        "|---|---|---|---|",
    ]
    unverified = 0
    for coordinate, version in gradle:
        licence, note = licence_for(coordinate, KNOWN_LICENCES)
        if licence == "unverified":
            unverified += 1
        lines.append(f"| `{coordinate}` | {version} | {licence} | {note} |")

    lines += [
        "",
        "## Python packages in the APK",
        "",
        "| Package | Version | Licence | Text in APK | Note |",
        "|---|---|---|---|---|",
    ]
    no_text = []
    for package, version, declared, has_text in python:
        # A checked statement beats a self-declaration where we have one — beancount's
        # "GPL-2.0-only, explicitly not or-later" is the fact this whole app is built around
        # and is not something to re-derive from a classifier each time.
        known, note = licence_for(package, PYTHON_LICENCES)
        licence = known if known != "unverified" else declared
        if licence == "unverified":
            unverified += 1
        if not has_text:
            no_text.append(package)
        lines.append(
            f"| `{package}` | {version} | {licence} | {'yes' if has_text else '**no**'} | {note} |"
        )

    lines += [
        "",
        "## Summary",
        "",
        f"- {len(gradle)} Android/JVM dependencies",
        f"- {len(python)} Python packages shipped in the APK",
        f"- **{unverified} unverified licences** — each must be resolved before release",
        f"- {len(python) - len(no_text)} of {len(python)} Python packages carry their own",
        "  licence text inside the APK",
        "",
        "MIT, BSD and X11 all require the notice to travel with the software, so \"is the",
        "text in the APK\" is a distribution question rather than bookkeeping. It is *not* the",
        "whole obligation: the texts sit inside nested archives in `assets/`, where no user",
        "can read them, and the app has no licences screen. Tracked as N17.",
        "",]
    if no_text:
        lines += [
            "Shipping no licence text of their own, so their notice has to come from"
            " somewhere else:",
            "",
        ] + [f"- `{package}`" for package in no_text] + [""]
    lines += ["",
        "Unverified means this tool could not determine the licence from metadata it trusts.",
        "It is reported rather than guessed, because a plausible wrong answer about a licence",
        "is worse than an admitted gap.",
    ]

    out = REPO / args.out
    out.write_text("\n".join(lines) + "\n")
    print(f"{len(gradle)} Android deps, {len(python)} Python packages, {unverified} unverified")
    print(f"written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
