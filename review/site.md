# Review log: site (pages, scripts, styles, accessibility, metadata)

Area `site` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** The text of every page was extracted and read against the app, `docs/` and the permissions of the built APK. `site.js` and the inline scripts were read for network use, storage and injection. Every relative link, image and in-page anchor was checked to exist, and the HTML tags were checked to nest. Pages were not rendered in a browser here, and the screenshots and fonts were not inspected beyond file type and licence.

| path | checked | finding | evidence |
|---|---|---|---|
| `site/assets/fonts/LICENSE.txt` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/OFL-Archivo.txt` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/archivo-variable.woff2` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/roboto-bold.woff2` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/roboto-medium.woff2` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/roboto-mono-regular.woff2` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/fonts/roboto-regular.woff2` | 3 | none | self-hosted font with its licence text beside it; no font is loaded from a third party |
| `site/assets/get-it-on-github.png` | 3 | none | static asset; no external references |
| `site/assets/icon.svg` | 3 | none | static asset; no external references |
| `site/assets/screenshots/documents-dark-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/documents-dark-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/documents-light-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/documents-light-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/entry-dark-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/entry-dark-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/entry-light-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/entry-light-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/home-dark-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/home-dark-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/home-light-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/home-light-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/journal-dark-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/journal-dark-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/journal-light-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/journal-light-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/reports-dark-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/reports-dark-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/reports-light-1080.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/screenshots/reports-light-540.webp` | 3 | none | captured from the invented `household` ledger (fixtures/site-screenshots/README.md); not inspected pixel by pixel |
| `site/assets/social-preview.png` | 3 | none | static asset; no external references |
| `site/download.html` | 1 5 | S-3 | read in full for claims; source comments removed |
| `site/features.html` | 1 5 | S-3 | read in full; the scheduled-backup sentence corrected; source comments removed |
| `site/help.html` | 1 5 | S-3 | read in full for claims; source comments removed |
| `site/index.html` | 1 5 | S-3 | read in full for claims; source comments removed |
| `site/keys.html` | 5 | none | redirect page for old links |
| `site/plugins.html` | 1 5 | none | read in full; matches the catalogue and the plugin warning in the app |
| `site/privacy.html` | 1 3 5 | S-1 | read in full against the app and docs/privacy.md; permission list, download hosts and diagnostics statement corrected; date updated |
| `site/site.js` | 3 | none | read for network, storage and injection: no network calls, no innerHTML or eval; localStorage holds the theme only, wrapped in try/catch |
| `site/style.css` | 5 | none | stylesheet; no external URLs |
| `site/support.html` | 3 5 | S-2 | a Ko-fi image that was fetched from Ko-fi's servers when the page opened is now a plain link |

## Fixed in this pull request

- **S-1 (S1): the privacy policy, the page Google Play asks for by address, understated or overstated several things.** It said diagnostics exclude account names and amounts (a report is a log; it can contain file names and engine messages, and the page now says to read it first), listed three permissions where the APK declares nine, named GitHub and PyPI as download sources but not Chaquopy's index, and said credentials are kept out of screenshots (the app does not block screenshots). All now match `docs/privacy.md` and the manifest. The "last updated" date is changed.
- **S-2 (S2): the Support page loaded an image from Ko-fi's servers whenever it was opened,** which contradicts the site's own claim that it keeps to itself. It is now a link that contacts Ko-fi only when clicked.
- **S-3 (S3):** one sentence said a backup "can be protected with a passphrase" without saying that scheduled backups cannot; and 16 HTML comments naming private source documents (an internal project guide, "site brief", a missing `docs/support-model.md`) were removed from the published pages.

## Accepted, with the reason

- **The site uses `localStorage` for the theme choice.** It holds `light` or `dark` and nothing else, is read in a `try`, and no cookie is set. The privacy page does not need to mention it.
- **Open Graph and Twitter metadata point at `pintofi.github.io`,** the site's own address; nothing third-party is requested by them until someone shares a link.
