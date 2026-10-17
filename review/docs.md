# Review log: docs (documentation)

Area `docs` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** Documentation is checked against code that earlier areas already reviewed. `threat-model.md`, `privacy.md`, `storage-and-backup.md` and `fdroid.md` were read in full and each claim about behaviour was checked against the source, the built APK's manifest (`aapt2 dump permissions`) or the F-Droid pipeline log. `architecture.md`, `distribution.md` and `known-limitations.md` were read where they make claims about the code. The other pages, `README.md`, `ROADMAP.md`, `CHANGELOG.md` and the images were scanned for private references, stale statements and broken relative links (none broken); they were not re-verified line by line.

| path | checked | finding | evidence |
|---|---|---|---|
| `CHANGELOG.md` | 5 | none | scanned for stale claims and private references; links checked |
| `CONTRIBUTING.md` | 5 | none | scanned for stale claims and private references; links checked |
| `README.md` | 5 | none | scanned for stale claims and private references; links checked |
| `ROADMAP.md` | 5 | none | scanned for stale claims and private references; links checked |
| `SECURITY.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/architecture.md` | 1 5 | D-2 | read in the parts that make claims about code |
| `docs/assets/FONTS.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/assets/badges/android.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/beancount.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/fava.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/get-it-on-github.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/kotlin.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/licence.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/python.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/badges/telemetry.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/banner-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/banner-light.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/cover-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/cover.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/kofi-cover-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/kofi-cover-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/kofi-cover.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/kofi-cover.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/ledger-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/ledger-light.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/rule-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/rule-light.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/balances-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/balances-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/documents-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/documents-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/entry-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/entry-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/form4-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/form4-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/journal-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/journal-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/more2-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/more2-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/phones-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/phones-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/plugins-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/plugins-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/reports-dark.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/reports-light.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/screenshots/widget-home.webp` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/social-preview-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/social-preview.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/tour-dark.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/assets/tour-light.svg` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/distribution.md` | 1 5 | D-2 | read in the parts that make claims about code |
| `docs/editions.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/fdroid.md` | 1 3 5 | D-2 | read in full against the code |
| `docs/getting-started.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/icon.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/known-limitations.md` | 1 5 | D-3 | read in the parts that make claims about code |
| `docs/on-demand-packages.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/plugin-compatibility.md` | 5 | D-4 | scanned for stale claims and private references; links checked |
| `docs/privacy.md` | 1 3 5 | D-1 | read in full against the code |
| `docs/sbom.md` | 5 | none | scanned for stale claims and private references; links checked |
| `docs/screenshots/2026-09-16-b87/after/appearance-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/balances-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/crop.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/documents-organize-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/home-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/home-stamp-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/after/scanner.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/add-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/balances-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/balances-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/crop-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/documents-organize-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/editor-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/home-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/home-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/home-wallpaper-colours.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/journal-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/journal-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/plugins-light.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/screenshots/2026-09-16-b87/before/scanner-dark.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/storage-and-backup.md` | 1 3 5 | none | read in full against the code |
| `docs/store/play-icon-512.png` | 3 | none | image: file type and, for SVG, its text searched for names and figures; not inspected pixel by pixel |
| `docs/threat-model.md` | 1 3 5 | D-1 | read in full against the code |
| `docs/ux.md` | 5 | none | scanned for stale claims and private references; links checked |
| `review/README.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/app-core.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/app-ui.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/build-ci.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/clients.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/data-sets.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/data.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/domain.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/python.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/resources.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/scanner.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/tests.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/tools.md` | 5 | none | written for this review; checked by `tools/review-files.py` |
| `review/docs.md` | 5 | none | this file |

## Fixed in this pull request

- **D-1 (S1): the threat model and the privacy page described things the app does not do.**
  - *Not built, said to exist:* an optional biometric app-lock, `FLAG_SECURE` screenshot blocking, update checks in the Expert edition (the updater was removed), a permission to install packages (neither APK declares it: checked with `aapt2`), and downloads from the WebView routed through the app (there is no download handler).
  - *Not true of the code:* `Origin`/`Referer` checks on the loopback server (it checks the token cookie, which is `SameSite=Strict`, and the `Host` header), a statement that release builds forbid cleartext (the manifest and network security config allow it, deliberately, as section 3.5 itself says), user Python running "in the app's process" (it runs in `:engine`, with the app's permissions), a safe mode that makes the ledger read-only (it withholds plugins), and "dependency scanning" and Maven checksums in CI (there is a notifier and no Maven verification; see #82).
  - *Incomplete:* the permission list (now the nine the APK declares) and the download hosts (PyPI, Chaquopy's index and GitHub, each checked against a recorded hash).
  All of these now say what the code does. Links out of a report really do ask first now (fixed in the `app-core` review).
- **D-2 (S2):** `architecture.md` drew OkHttp and Glance, neither of which the app uses (closes #63), and claimed `Origin` checks; `distribution.md` listed an in-app update check that no longer exists and said F-Droid was blocked on a library that is gone; `fdroid.md` said the recipe was an untried draft, although its pipeline passed and the built binary was compared with the signed APK. It now records that, and that the "NDK r27d could not be found" line in the log is F-Droid installing it.
- **D-3 (S2):** `known-limitations.md` gains the gaps found by this review, each with its issue: cent differences against Fava (#60), the remote retry lost on exit (#65), unencrypted scheduled backups (#68), irreversible document deletion (#72), and Fava's own editor (#17).
- **D-4 (S3):** milestone labels and references to documents that are not in the repository removed.

## Accepted, with the reason

- **Some claims are not checkable from the repository:** that an offline backup of the signing key exists, and which settings the GitHub branch protection has beyond blocking force-pushes and deletion (checked through the API). The pages say what is verifiable.
- **Pages not re-verified line by line** (getting-started, on-demand-packages, plugin-compatibility, sbom, editions, ux, icon, the README). The README's versions match the lockfile and the built APK. Any statement found wrong later is a bug in the page.
