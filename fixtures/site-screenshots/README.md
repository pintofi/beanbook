# Screenshots for the website

`household/` is an invented ledger: every name, amount and document in it is made up. It
exists only to be photographed for the home page's gallery (`site/assets/screenshots/`).
The two PDFs under `household/documents/` are generated placeholders, not real receipts.

## Recapturing

`tools/capture-screens.py` does all of it on an emulator you have started (a Google APIs
`userdebug` image, because it needs `adb root`): it fixes the clock to 2026-09-18, the screen to
1080 x 1920 at 420 dpi, font scale 1.0 and the status bar to 09:41, clears the Expert debug
build's data, loads `household/`, walks the screens in both themes and writes the WebP files.

```bash
python3 tools/capture-screens.py --out /tmp/shots            # the screens in manifest.json
python3 tools/capture-screens.py --out /tmp/shots --only home,entry
python3 tools/capture-screens.py --dry-run
```

`site/assets/screenshots/manifest.json` holds each screen's alt text and, under `capture`, what
the published set came from. A full run rewrites `capture`; `--only` leaves it alone. Besides the
five screens on the home page the script can capture `preview`, `balances`, `more` and `plugins`.
`tools/check-site-screens.py` (run in CI) checks the files exist at 1080 x 1920 and 540 x 960 and
warns when the app's UI has changed since the recorded commit.

The published set was captured by hand on 2026-10-13 with the same settings. The site's
annotated notes (`site/assets/site.js`) depend on where things sit in those images, so check them
before replacing any file.

The raw PNGs are not kept in the repository; the WebP files are what the site serves.
