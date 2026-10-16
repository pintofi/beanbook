#!/usr/bin/env python3
"""Which area of the baseline review does each tracked file belong to?

The review (see review/README.md) goes through the repository one area at a time. Every tracked
file must belong to exactly one area, and an area is finished when its log in review/ names every
one of its files. This script makes both statements checkable:

    tools/review-files.py                 # every file has an area; summary per area
    tools/review-files.py list domain     # the files of one area
    tools/review-files.py check domain    # the area's log covers all its files, and no others
    tools/review-files.py status          # one line per area: files and files logged

Areas are matched in order and a file takes the first one that matches, so the specific rules
come first.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (id, title, patterns). `**` crosses directories, `*` does not.
AREAS = [
    ("tests", "Tests: JVM, Python and instrumentation", [
        "*/src/test/**", "*/src/androidTest*/**", "app/src/debug/**",
    ]),
    ("domain", "core-domain: money, entries, drafts, templates, plugin preflight", ["core-domain/**"]),
    ("data", "core-data: write protocol, snapshots, journal, recovery, archives, SAF, mirror, documents", ["core-data/**"]),
    ("clients", "core-engine and engine-remote: the engine client and the remote Fava client", ["core-engine/**", "engine-remote/**"]),
    ("python", "engine-local (Python layer) and patches/", ["engine-local/**", "patches/**"]),
    ("scanner", "scanner: document scanning", ["scanner/**"]),
    ("resources", "Resources: strings, drawables, icons, widget layouts, bundled assets", [
        "app/src/*/res/**", "app/src/main/assets/**",
    ]),
    ("app-ui", "app: screens, theme, shared components, accessibility semantics", ["app/src/main/kotlin/**/ui/**"]),
    ("app-core", "app: engine and services, sharing, widgets, WebView hardening, manifest, permissions, editions", [
        "app/src/**",
    ]),
    ("tools", "tools/ and build scripts", ["tools/**"]),
    ("build-ci", "Gradle configuration, version catalogue, CI workflows, Dependabot, static-analysis config", [
        "*.gradle.kts", "app/build.gradle.kts", "*/build.gradle.kts", "gradle/**", "gradle.properties",
        "gradlew", "gradlew.bat", "app/proguard-rules.pro", ".github/workflows/**", ".github/dependabot.yml", "config/**",
    ]),
    ("data-sets", "corpus/, fixtures/ and catalog/ (the plugin catalogue)", ["corpus/**", "fixtures/**", "catalog/**"]),
    ("docs", "Documentation: docs/, README, CONTRIBUTING, SECURITY, CHANGELOG, ROADMAP, review/", [
        "docs/**", "README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", "ROADMAP.md", "review/**",
    ]),
    ("site", "site/: pages, scripts, styles, accessibility, metadata", ["site/**"]),
    ("repo-files", "fastlane/, fdroid/, licences/, COPYING, AUTHORS and other repository files", ["**"]),
]


def regex(pattern):
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"; i += 3
        elif pattern.startswith("**", i):
            out += ".*"; i += 2
        elif pattern[i] == "*":
            out += "[^/]*"; i += 1
        else:
            out += re.escape(pattern[i]); i += 1
    return re.compile("^" + out + "$")


COMPILED = [(a, t, [regex(p) for p in ps]) for a, t, ps in AREAS]


def tracked():
    out = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True)
    return [f for f in out.splitlines() if f]


def area_of(path):
    for area, _, patterns in COMPILED:
        if any(p.match(path) for p in patterns):
            return area
    return None


def logged(area):
    log = ROOT / "review" / f"{area}.md"
    if not log.exists():
        return set()
    found = set()
    for line in log.read_text().splitlines():
        m = re.match(r"^\|\s*`([^`]+)`\s*\|", line)
        if m:
            found.add(m.group(1))
    return found


def main(argv):
    files = tracked()
    by_area = {a: [] for a, _, _ in AREAS}
    unassigned = []
    for f in files:
        a = area_of(f)
        (by_area[a] if a else unassigned).append(f)
    if unassigned:
        print("files with no area:", *unassigned, sep="\n  ")
        return 1
    cmd = argv[1] if len(argv) > 1 else "summary"
    if cmd == "summary":
        for a, t, _ in AREAS:
            print(f"{a:10} {len(by_area[a]):4}  {t}")
        print(f"{'':10} {len(files):4}  total")
        return 0
    if cmd == "status":
        for a, t, _ in AREAS:
            done = logged(a) & set(by_area[a])
            print(f"{a:10} {len(done):4}/{len(by_area[a]):<4}  {t}")
        return 0
    area = argv[2] if len(argv) > 2 else ""
    if area not in by_area:
        print("unknown area; choose from:", ", ".join(by_area), file=sys.stderr)
        return 2
    if cmd == "list":
        print("\n".join(by_area[area]))
        return 0
    if cmd == "check":
        want, have = set(by_area[area]), logged(area)
        missing, extra = sorted(want - have), sorted(have - want)
        for f in missing:
            print("not logged:", f)
        for f in extra:
            print("logged but not in this area (moved or deleted?):", f)
        print(f"{area}: {len(want) - len(missing)}/{len(want)} files logged")
        return 1 if missing or extra else 0
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
