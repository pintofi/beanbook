# Screenshots for the website

`household/` is an invented ledger: every name, amount and document in it is made up. It
exists only to be photographed for the home page's gallery (`site/assets/screenshots/`).
The two PDFs under `household/documents/` are generated placeholders, not real receipts.

## Recapturing

The published set was captured on 2026-10-13 from the Expert debug build
on the `s1_api36` emulator: 1080 x 1920, 420 dpi, font scale 1.0, en-US, portrait.

1. Install the Expert debug build and clear its data (`adb shell pm clear <package>`).
2. Push `household/` and make it the open project:
   ```bash
   P=io.github.pintofi.beanbook.expert.debug
   adb push fixtures/site-screenshots/household /data/local/tmp/
   adb shell "run-as $P sh -c 'mkdir -p files/projects files/state && cp -r /data/local/tmp/household files/projects/ && printf household > files/state/active-project'"
   ```
3. Fix the status bar with System UI demo mode: clock 09:41, full battery, Wi-Fi, no
   notifications.
4. For each theme write `files/state/appearance` as `LIGHT\nfalse\n` or `DARK\nfalse\n` (the
   app's own palette, dynamic colour off), start the app, and capture in this order:
   - **home**: Home, at the top, after the figures settle.
   - **journal**: Journal, at the top.
   - **entry**: Journal, open "Corner grocer · Weekly shop" (2026-09-12), then Clone. The
     date is the capture day; keep both themes on the same day.
   - **reports**: Reports, after Fava has drawn (about 30 s).
   - **documents**: More, Documents, Organize.
5. Encode with `magick <png> -strip -quality 82 <name>-<theme>-1080.webp` and
   `magick <png> -strip -resize 540x960 -quality 80 <name>-<theme>-540.webp`.

The raw PNGs are not kept in the repository; the WebP files are what the site serves.
