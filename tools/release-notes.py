#!/usr/bin/env python3
"""Writes the text of a GitHub release from the template.

Usage: tools/release-notes.py <version> <checksums-file>

The notes come from release-notes/<version>.md, which a person writes for each release. They are
sections (### Fixed, ### New, ### Changed, ### Maintenance) with a pull request or issue number at
the end of each line. A minor or major release may start with a short summary paragraph above the
first heading; a patch release usually does not.
The certificate fingerprint is read from the F-Droid recipe, so it lives in one place. The
output goes to stdout. A missing highlights file stops the release rather than publishing a
draft with nothing but checksums.
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version, checksums = sys.argv[1], Path(sys.argv[2])

highlights_file = root / "release-notes" / f"{version}.md"
if not highlights_file.is_file():
    sys.exit(f"missing {highlights_file.relative_to(root)}: write the release highlights first")

recipe = (root / "fdroid" / "io.github.pintofi.beanbook.expert.yml").read_text()
cert = re.search(r"^AllowedAPKSigningKeys:\s*([0-9a-f]{64})\s*$", recipe, re.M)
if not cert:
    sys.exit("no signing certificate fingerprint in the F-Droid recipe")

changelog = (root / "CHANGELOG.md").read_text()
versions = re.findall(r"^## \[(\d+\.\d+\.\d+)\]", changelog, re.M)
if version not in versions:
    sys.exit(f"CHANGELOG.md has no [{version}] section")
later = versions[versions.index(version) + 1:]
previous = later[0] if later else None

text = (root / ".github" / "release-notes-template.md").read_text()
if previous is None:
    text = "\n".join(line for line in text.split("\n") if "{{PREVIOUS}}" not in line)
text = text.replace("{{PREVIOUS}}", previous or "").replace("{{VERSION}}", version)
text = text.replace("{{HIGHLIGHTS}}", highlights_file.read_text().strip())
text = text.replace("{{CERT_SHA256}}", cert.group(1))
text = text.replace("{{CHECKSUMS}}", checksums.read_text().strip())
sys.stdout.write(text)
