#!/usr/bin/env python3
"""Keeps the link definitions at the bottom of CHANGELOG.md in step with its version headings.

Keep a Changelog headings such as `## [1.0.1] - 2026-10-18` only become links when the file ends
with a definition for each one. This script writes those definitions from the headings alone:

    [Unreleased]: .../compare/v<newest>...HEAD
    [<version>]:  .../compare/v<previous>...v<version>     (the oldest: .../releases/tag/v<version>)

It also checks that every entry under [Unreleased] and under 1.0.1 or later ends with the pull
request or issue it came from, such as `([#93], fixes [#69])`; the definitions of those [#N] links are written below the version ones. 1.0.0 predates pull requests and is
exempt.

Usage:
    tools/changelog-links.py           rewrite the definitions in place
    tools/changelog-links.py --check   exit 1 if they are missing or stale (used by CI, which also
                                       runs on every release tag, so a release cannot go out with
                                       a heading that points to nothing)
"""
import re
import sys
from pathlib import Path

REPO = "https://github.com/pintofi/beanbook"
FIRST_REFERENCED = (1, 0, 1)
path = Path(__file__).resolve().parent.parent / "CHANGELOG.md"
text = path.read_text()

HEADING = re.compile(r"^## \[(Unreleased|\d+\.\d+\.\d+)\]", re.M)
DEFINITION = re.compile(r"^\[(Unreleased|\d+\.\d+\.\d+|#\d+)\]: \S+\s*$")
REFERENCE = re.compile(r"\[#(\d+)\]")

headings = HEADING.findall(text)
if "Unreleased" not in headings:
    sys.exit("CHANGELOG.md has no [Unreleased] heading")
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

# GitHub only links a bare #123 in issues, pull requests and release text, not in a file. Entries
# cite them as [#123], and each one is defined here. /issues/N also opens a pull request.
numbers = sorted({int(n) for n in REFERENCE.findall(body)})
wanted += [f"[#{n}]: {REPO}/issues/{n}" for n in numbers]
expected = body + "\n\n" + "\n".join(wanted) + "\n"

# Entries that name no pull request or issue, in the sections that must have them.
missing = []
section = None
for line in text.split("\n"):
    heading = HEADING.match(line)
    if heading:
        name = heading.group(1)
        section = name if name == "Unreleased" or tuple(map(int, name.split("."))) >= FIRST_REFERENCED else None
    elif section and line.startswith("- ") and not REFERENCE.search(line):
        missing.append(f"[{section}] {line[:90]}")

if "--check" in sys.argv[1:]:
    if missing:
        print("::error file=CHANGELOG.md::entries with no pull request or issue number:")
        print("\n".join(missing))
        sys.exit(1)
    if text != expected:
        print("::error file=CHANGELOG.md::the link definitions at the bottom do not match the "
              "version headings. Run tools/changelog-links.py and commit the result.")
        print("expected the file to end with:\n" + "\n".join(wanted))
        sys.exit(1)
    print(f"CHANGELOG.md: {len(versions) + 1} headings and {len(numbers)} references, all linked")
else:
    path.write_text(expected)
    print("\n".join(wanted))
