#!/usr/bin/env python3
"""Writes the text of a GitHub release from the template.

Usage: tools/release-notes.py <version> <checksums-file>

The highlights come from release-notes/<version>.md, which a person writes for each release.
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

text = (root / ".github" / "release-notes-template.md").read_text()
text = text.replace("{{HIGHLIGHTS}}", highlights_file.read_text().strip())
text = text.replace("{{CERT_SHA256}}", cert.group(1))
text = text.replace("{{CHECKSUMS}}", checksums.read_text().strip())
sys.stdout.write(text)
