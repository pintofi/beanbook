#!/usr/bin/env python3
"""Checks the website's screenshots against site/assets/screenshots/manifest.json.

    python3 tools/check-site-screens.py

Fails when a listed screen has no image, an image has the wrong size, or a screen has no alt text.
Warns (without failing) when the app's UI changed after the commit the screenshots were captured
from; recapture with tools/capture-screens.py when the change is visible.
"""
import json, struct, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "site" / "assets" / "screenshots"
SIZES = {1080: (1080, 1920), 540: (540, 960)}


def webp_size(path):
    d = path.read_bytes()[:40]
    if d[:4] != b"RIFF" or d[8:12] != b"WEBP":
        return None
    kind = d[12:16]
    if kind == b"VP8 ":
        w, h = struct.unpack("<HH", d[26:30])
        return w & 0x3FFF, h & 0x3FFF
    if kind == b"VP8L":
        b = struct.unpack("<I", d[21:25])[0]
        return (b & 0x3FFF) + 1, ((b >> 14) & 0x3FFF) + 1
    if kind == b"VP8X":
        return int.from_bytes(d[24:27], "little") + 1, int.from_bytes(d[27:30], "little") + 1
    return None


def main():
    manifest = json.loads((DIR / "manifest.json").read_text(encoding="utf-8"))
    cap = manifest.get("capture", {})
    themes = cap.get("themes", ["light", "dark"])
    errors = []
    if not manifest.get("screens"):
        errors.append("manifest.json lists no screens")
    for s in manifest.get("screens", []):
        if not s.get("alt", "").strip():
            errors.append(f"{s['id']}: no alt text")
        for theme in themes:
            for width, size in SIZES.items():
                f = DIR / f"{s['id']}-{theme}-{width}.webp"
                if not f.exists():
                    errors.append(f"{f.name}: missing")
                elif webp_size(f) != size:
                    errors.append(f"{f.name}: is {webp_size(f)}, expected {size}")
    for e in errors:
        print(f"check-site-screens: {e}", file=sys.stderr)
    commit = cap.get("commit")
    if commit and not errors:
        r = subprocess.run(["git", "log", "--oneline", f"{commit}..HEAD", "--", "app/src/main/java", "app/src/main/res"],
                           cwd=ROOT, capture_output=True, text=True)
        n = len(r.stdout.splitlines()) if r.returncode == 0 else 0
        if n:
            print(f"check-site-screens: warning: {n} commit(s) touched the app UI since the screenshots "
                  f"({commit}); recapture if the change shows.")
    if errors:
        return 1
    print(f"check-site-screens: {len(manifest['screens'])} screens ok")
    return 0


sys.exit(main())
