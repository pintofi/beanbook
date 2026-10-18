#!/usr/bin/env python3
"""Keeps CHANGELOG.md in the Keep a Changelog 1.1.0 format, and writes its link definitions.

Keep a Changelog headings such as `## [1.0.1] - 2026-10-18` are only links when the file ends with
a definition for each one. This script writes those definitions from the headings alone:

    [Unreleased]: .../compare/v<newest>...HEAD
    [<version>]:  .../compare/v<previous>...v<version>     (the oldest: .../releases/tag/v<version>)
    [#123]:       .../issues/123                           (one for each [#123] cited in an entry)

GitHub only links a bare #123 in issues, pull requests and release text, not in a file, so entries
cite them as [#123]. /issues/N also opens a pull request.

Usage:
    tools/changelog-links.py           rewrite the definitions in place
    tools/changelog-links.py --check   exit 1 if the file breaks the rules below (used by CI, which
                                       also runs on every release tag, so a release cannot go out
                                       with a heading that points to nothing)

Rules checked, from https://keepachangelog.com/en/1.1.0/:
  * newest version first, each heading `## [x.y.z] - YYYY-MM-DD`, with ` [YANKED]` after the date
    for a release that was pulled;
  * sections are only Added, Changed, Deprecated, Removed, Fixed and Security, and none is empty;
  * [Unreleased] is at the top;
and one rule of this project: every entry under [Unreleased] and under 1.0.1 or later ends with the
pull request or issue it came from, such as `([#93], fixes [#69])`. 1.0.0 predates pull requests.
"""
import re
import sys
from pathlib import Path

REPO = "https://github.com/pintofi/beanbook"
FIRST_REFERENCED = (1, 0, 1)
TYPES = ("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")

path = Path(__file__).resolve().parent.parent / "CHANGELOG.md"
text = path.read_text()

HEADING = re.compile(r"^## \[(Unreleased|\d+\.\d+\.\d+)\]", re.M)
RELEASE_HEADING = re.compile(r"^## \[(\d+\.\d+\.\d+)\] - (\d{4}-\d{2}-\d{2})( \[YANKED\])?$")
DEFINITION = re.compile(r"^\[(Unreleased|\d+\.\d+\.\d+|#\d+)\]: \S+\s*$")
REFERENCE = re.compile(r"\[#(\d+)\]")

headings = HEADING.findall(text)
if not headings or headings[0] != "Unreleased":
    sys.exit("CHANGELOG.md must start its entries with an [Unreleased] heading")
versions = [h for h in headings if h != "Unreleased"]
if not versions:
    sys.exit("CHANGELOG.md has no released version")

wanted = [f"[Unreleased]: {REPO}/compare/v{versions[0]}...HEAD"]
for i, version in enumerate(versions):
    if i + 1 < len(versions):
        wanted.append(f"[{version}]: {REPO}/compare/v{versions[i + 1]}...v{version}")
    else:
        wanted.append(f"[{version}]: {REPO}/releases/tag/v{version}")

lines = text.rstrip("\n").split("\n")
while lines and (DEFINITION.match(lines[-1]) or not lines[-1].strip()):
    lines.pop()
body = "\n".join(lines)
numbers = sorted({int(n) for n in REFERENCE.findall(body)})
wanted += [f"[#{n}]: {REPO}/issues/{n}" for n in numbers]
expected = body + "\n\n" + "\n".join(wanted) + "\n"


def problems():
    found = []
    previous = None
    section = None       # the version whose entries are being read
    kind = None          # the ### heading being read
    entries = 0
    referenced = False   # whether this version's entries must cite a pull request or issue

    def close_kind():
        if kind is not None and entries == 0:
            found.append(f"[{section}] the empty section ### {kind}: leave it out")

    for line in body.split("\n"):
        if line.startswith("## "):
            close_kind()
            kind, entries = None, 0
            heading = HEADING.match(line)
            if not heading:
                found.append(f"not a version heading: {line[:80]}")
                section = None
                continue
            name = heading.group(1)
            section = name
            if name == "Unreleased":
                referenced = True
            else:
                if not RELEASE_HEADING.match(line):
                    found.append(f"{line[:80]!r}: write it as `## [x.y.z] - YYYY-MM-DD`")
                number = tuple(map(int, name.split(".")))
                if previous is not None and number >= previous:
                    found.append(f"[{name}] is not older than the version above it")
                previous = number
                referenced = number >= FIRST_REFERENCED
        elif line.startswith("### "):
            close_kind()
            kind, entries = line[4:].strip(), 0
            if kind not in TYPES:
                found.append(f"[{section}] ### {kind}: use one of {', '.join(TYPES)}")
        elif line.startswith("- ") and section:
            entries += 1
            if referenced and not REFERENCE.search(line):
                found.append(f"[{section}] no pull request or issue: {line[:80]}")
    close_kind()
    return found


if "--check" in sys.argv[1:]:
    bad = problems()
    if bad:
        print("::error file=CHANGELOG.md::CHANGELOG.md breaks the Keep a Changelog rules:")
        print("\n".join(bad))
        sys.exit(1)
    if text != expected:
        print("::error file=CHANGELOG.md::the link definitions at the bottom do not match the "
              "version headings and references. Run tools/changelog-links.py and commit the result.")
        print("expected the file to end with:\n" + "\n".join(wanted))
        sys.exit(1)
    print(f"CHANGELOG.md: {len(versions) + 1} headings and {len(numbers)} references, all linked")
else:
    path.write_text(expected)
    print("\n".join(wanted))
