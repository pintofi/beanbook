#!/usr/bin/env python3
"""Writes the text of a GitHub release from the template.

Usage: tools/release-notes.py <version> <checksums-file>

The changes come straight from the version's section of CHANGELOG.md, so the file is the one place
they are written: its `###` headings (Added, Changed, Fixed, Security and so on) and its entries,
with the `[#123]` references turned into plain #123, which GitHub links in a release.

A minor or major release may add a short summary above them, in release-notes/<version>.md. A
patch release has none, and the file is not needed.

The certificate fingerprint is read from the F-Droid recipe, so it lives in one place. The output
goes to stdout. A version with no section, or an empty one, in the changelog stops the release.
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version, checksums = sys.argv[1], Path(sys.argv[2])

changelog = (root / "CHANGELOG.md").read_text()
section = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|^\[Unreleased\]:|\Z)", changelog, re.M | re.S)
if not section or "- " not in section.group(1):
    sys.exit(f"CHANGELOG.md has no entries under [{version}]")
changes = re.sub(r"\[#(\d+)\]", r"#\1", section.group(1)).strip()

summary_file = root / "release-notes" / f"{version}.md"
summary = summary_file.read_text().strip() + "\n\n" if summary_file.is_file() else ""

recipe = (root / "fdroid" / "io.github.pintofi.beanbook.expert.yml").read_text()
cert = re.search(r"^AllowedAPKSigningKeys:\s*([0-9a-f]{64})\s*$", recipe, re.M)
if not cert:
    sys.exit("no signing certificate fingerprint in the F-Droid recipe")

versions = re.findall(r"^## \[(\d+\.\d+\.\d+)\]", changelog, re.M)
if version not in versions:
    sys.exit(f"CHANGELOG.md has no [{version}] section")
later = versions[versions.index(version) + 1:]
previous = later[0] if later else None

text = (root / ".github" / "release-notes-template.md").read_text()
if previous is None:
    text = "\n".join(line for line in text.split("\n") if "{{PREVIOUS}}" not in line)
text = text.replace("{{PREVIOUS}}", previous or "").replace("{{VERSION}}", version)
text = text.replace("{{CHANGES}}", summary + changes)
text = text.replace("{{CERT_SHA256}}", cert.group(1))

# One row per APK: the file name links to the release asset, and the column beside it holds its
# checksum. The link is the release-download address, which is the same before and after the
# draft is published.
ORDER = ["arm64-v8a", "x86_64", "universal"]
base = f"https://github.com/pintofi/beanbook/releases/download/v{version}"
sums = {}
for line in checksums.read_text().splitlines():
    if line.strip():
        digest, name = line.split(None, 1)
        sums[name.strip().lstrip("*")] = digest


def rank(name):
    return next((i for i, abi in enumerate(ORDER) if abi in name), len(ORDER))


rows = ["| File | SHA-256 |", "|---|---|"]
for name in sorted(sums, key=lambda n: (rank(n), n)):
    rows.append(f"| [{name}]({base}/{name}) | `{sums[name]}` |")
text = text.replace("{{DOWNLOADS}}", "\n".join(rows))
sys.stdout.write(text)
