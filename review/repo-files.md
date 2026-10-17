# Review log: repo-files (root files, licences, templates, store metadata)

Area `repo-files` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** Every text file was read in full. The licence texts were compared with the standard wording, and `COPYING` with `licences/GPL-2.0-only.txt`. The F-Droid recipe was compared with the one that passed the pipeline. All eight store screenshots, the icon and the feature graphic were viewed.

| path | checked | finding | evidence |
|---|---|---|---|
| `.github/CODEOWNERS` | 1 | none | read in full; accurate |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | 1 | none | read in full; accurate |
| `.github/ISSUE_TEMPLATE/config.yml` | 1 | none | read in full; accurate |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | 1 | none | read in full; accurate |
| `.github/pull_request_template.md` | 1 | none | read in full; accurate |
| `.github/release.yml` | 1 | none | read in full; accurate |
| `.gitignore` | 1 5 | R-1 | private document names removed from the published ignore list |
| `AUTHORS` | 1 | none | read in full; accurate |
| `COPYING` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `fastlane/metadata/android/en-US/changelogs/1.txt` | 1 5 | R-2 | Expert-only wording for scripts corrected; both editions have them |
| `fastlane/metadata/android/en-US/full_description.txt` | 1 5 | R-2 | Expert-only wording for scripts corrected; both editions have them |
| `fastlane/metadata/android/en-US/images/featureGraphic.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/icon.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/2.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/3.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/4.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/5.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/6.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/7.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/8.png` | 3 | none | viewed; invented "Household" ledger in EUR, no personal data |
| `fastlane/metadata/android/en-US/short_description.txt` | 1 | none | read in full; accurate |
| `fastlane/metadata/android/en-US/title.txt` | 1 | none | read in full; accurate |
| `fdroid/io.github.pintofi.beanbook.expert.yml` | 1 4 | none | matches the recipe that passed the F-Droid pipeline |
| `licences/Apache-2.0.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/BSD-3-Clause.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/CC0-1.0.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/EPL-1.0.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/GPL-2.0-only.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/LGPL-2.1.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/MIT.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/PSF-2.0.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |
| `licences/X11.txt` | 3 | none | standard licence text; COPYING is identical to licences/GPL-2.0-only.txt |

## Fixed in this pull request

- **R-1 (S3): `.gitignore` listed the names of private working documents** (and a comment pointed at one). Those entries now live in the local exclude file; the published list keeps only build, IDE and secret patterns.
- **R-2 (S3): the store description and the first changelog said scripts are Expert-only.** Scripts and plugins loaded from a file exist in both editions; the Expert edition adds packages with native code. Both texts now say so.

## Accepted, with the reason

- **Old `.gitignore` content remains in history.** It names documents only, not their contents; rewriting published history is not worth it.
