#!/usr/bin/env python3
"""Draws the README's version badges from the versions the app uses, and checks the drawing.

    python3 tools/badges.py           rewrite docs/assets/badges/{beancount,fava,python,android}.svg
    python3 tools/badges.py --check   exit 1 if a badge is not what would be drawn now

The text inside a badge is outlines of font glyphs, not text, so nothing can read a version out of
the file: the only way to know the picture says what its title says is to draw it again and compare.
This does that, from the same Roboto Medium (label) and Roboto Mono (value) files the website ships,
at 13.5 px, so a version bump is `python3 tools/badges.py` and a badge left behind fails CI.

Needs fontTools and brotli (`pip install fonttools brotli`). kotlin.svg, licence.svg and
telemetry.svg state no version and are not drawn here.
"""
import sys
from pathlib import Path

import appversions as app

try:
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.ttLib import TTFont
except ImportError:
    sys.exit("tools/badges.py needs fontTools and brotli: pip install fonttools brotli")

FONTS = app.ROOT / "site" / "assets" / "fonts"
OUT = app.ROOT / "docs" / "assets" / "badges"
SIZE = 13.5
SCALE = SIZE / 2048  # Roboto's em is 2048 units
PAD = 10
IDS = "0123456789abcdefghijklmnopqrstuvwxyz"


class Face:
    def __init__(self, name):
        self.font = TTFont(FONTS / name)
        self.glyphs = self.font.getGlyphSet()
        self.cmap = self.font.getBestCmap()
        self.hmtx = self.font["hmtx"]

    def advance(self, ch):
        return self.hmtx[self.cmap[ord(ch)]][0] * SCALE

    def outline(self, ch):
        pen = SVGPathPen(self.glyphs, ntos=lambda v: str(int(round(v))))
        self.glyphs[self.cmap[ord(ch)]].draw(pen)
        return pen.getCommands()

    def width(self, text):
        return sum(self.advance(c) for c in text)


def one(x):
    return f"{x:.1f}"


def render(label, value, face_label, face_value):
    aria = f"{label}: {value}"
    label_w = PAD + face_label.width(label) + PAD
    value_w = PAD + face_value.width(value) + PAD
    total = label_w + value_w
    glyphs = {}
    for face, text in ((face_label, label), (face_value, value)):
        for c in text:
            if c != " " and (id(face), c) not in glyphs:
                glyphs[(id(face), c)] = len(glyphs)

    def name(face, c):
        return "k" + IDS[glyphs[(id(face), c)]]

    # one definition per glyph, in order of first use
    seen, defs = set(), ""
    for face, text in ((face_label, label), (face_value, value)):
        for c in text:
            if c != " " and (id(face), c) not in seen:
                seen.add((id(face), c))
                defs += f'<path id="{name(face, c)}" d="{face.outline(c)}"/>'

    def run(text, x, face):
        out = ""
        for c in text:
            if c != " ":
                out += f'<use href="#{name(face, c)}" transform="matrix({SCALE:.5f} 0 0 -{SCALE:.5f} {one(x)} 18.6)"/>'
            x += face.advance(c)
        return out

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{round(total)}" height="28" viewBox="0 0 {one(total)} 28" '
        f'role="img" aria-label="{aria}"><title>{aria}</title>'
        f'<defs>{defs}<clipPath id="r"><rect width="{one(total)}" height="28" rx="6"/></clipPath></defs>'
        f'<g clip-path="url(#r)"><rect width="{one(label_w)}" height="28" fill="#1c2724"/>'
        f'<rect x="{one(label_w)}" width="{one(value_w)}" height="28" fill="#0b5f5b"/>'
        f'<rect x="{one(label_w)}" y="25" width="{one(value_w)}" height="1" fill="#ffffff" opacity=".35"/></g>'
        f'<g fill="#e8f0ed">{run(label, PAD, face_label)}</g>'
        f'<g fill="#ffffff">{run(value, label_w + PAD, face_value)}</g></svg>'
    )


def badges():
    """name -> (label, value): the four badges that state a version."""
    return {
        "beancount": ("Beancount", app.pin("beancount")),
        "fava": ("Fava", app.pin("fava")),
        "python": ("CPython", app.python_minor),
        "android": ("Android", f"{app.android_release}+ (API {app.min_sdk})"),
    }


def drawn():
    label_face, value_face = Face("roboto-medium.woff2"), Face("roboto-mono-regular.woff2")
    return {name: render(label, value, label_face, value_face) for name, (label, value) in badges().items()}


def check():
    problems = []
    for name, svg in drawn().items():
        path = OUT / f"{name}.svg"
        have = path.read_text(encoding="utf-8") if path.exists() else None
        if have != svg:
            problems.append(f"docs/assets/badges/{name}.svg is not what would be drawn from the versions the app uses; run python3 tools/badges.py")
    return problems


if __name__ == "__main__":
    if "--check" in sys.argv[1:]:
        found = check()
        print("\n".join(f"badges: {p}" for p in found) or "badges agree")
        sys.exit(1 if found else 0)
    for name, svg in drawn().items():
        (OUT / f"{name}.svg").write_text(svg, encoding="utf-8")
        print(f"wrote {OUT / (name + '.svg')}")
