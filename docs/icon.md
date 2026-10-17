# The app icon

**Balanced Columns**, chosen from the design lab's finalists.

![the icon](store/play-icon-512.png)

Two brackets facing each other across a pair of equal rules. The brackets suggest a
plain-text ledger without drawing a literal document, and the two rules of identical length
say *double entry*, which is what this app is actually about, rather than "a list", which is
what the previous mark said and what most finance icons say.

The mark it replaced was three ruled lines with figures on the right. It was fine and it was
generic: at 48 px it read as a to-do app.

## What is in the project

| File | Purpose |
|---|---|
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | the mark, in white and the palette's light teal |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | the same geometry in one colour, for themed launchers |
| `app/src/main/res/values/ic_launcher_background.xml` | `#006A6A`, fixed |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` | the adaptive icon binding the three |
| `docs/store/play-icon-512.png` | the Play listing icon, 512×512, fully opaque |

The design sources live outside the repository, in the lab that produced them.

**No raster launcher icons ship.** They would be needed only below API 26, and minSdk is 29,
one of the few things the API 28 finding made simpler rather than harder.

## The one correction worth knowing about

The designer's geometry is for a finished 512 tile, where the mark spans **50.8%** of the
square. An adaptive foreground is not a finished tile: a launcher shows only the inner 72 of
the 108 viewport, so the same coordinates used directly come out **exactly 1.5× too large**.

That version passed every check (it was well inside the safe zone and nothing was cropped),
and it was visibly zoomed in against the design it was chosen from. It is corrected by a group
transform of 72/108 about the centre, rather than by rewriting the path data, so the numbers
stay the designer's and the correction stays legible as a correction.

The lesson generalises: "inside the safe zone" and "matches the design" are different
questions, and only the first one is automatable.

## What is checked

`LauncherIconTest` asks `PackageManager` for the icon a launcher would get, draws it through
`AdaptiveIconDrawable`, which applies the **device's own mask**, and looks at the pixels:

- all three layers are present, including monochrome, so a themed launcher does not fall back
  to the full-colour icon;
- the mark is visible against its background, by counting safe-zone pixels that differ from
  `#006A6A`;
- **nothing of the mark reaches the icon's edge**, where a circular mask would slice the
  brackets.

Verified on a Galaxy A16 (arm64, API 36) under Samsung's squircle mask, and on an API 36
emulator. The render is written to the app's cache so a person can look at it, which is still
the only way to judge whether an icon is any good.
