# F-Droid and fastlane

## The `fastlane/` folder

`fastlane/metadata/android/en-US/` holds the store text in the layout both F-Droid and Google
Play (through fastlane `supply`) read: `title.txt`, `short_description.txt` (80 characters at
most), `full_description.txt`, `changelogs/<versionCode>.txt` and `images/`. It is the one
source for listing text; write to it, not to a store console. A new release adds
`changelogs/<versionCode>.txt` for its `versionCode`.

**Still to add, at the end of the release work** (they need the final UI): `images/phoneScreenshots/`
(1.png, 2.png, …), optionally `images/featureGraphic.png` (1024x500), `sevenInchScreenshots/`
and `tenInchScreenshots/`. `images/icon.png` is in place.

## Is F-Droid possible? Not yet submitted, and not verified

What is true today (2026-10-11):

- **No proprietary libraries.** Billing and ML Kit are gone; nothing from Google Play services
  is linked.
- **Licence.** GPL-2.0-only. [LICENSE-EXCEPTION.md](../LICENSE-EXCEPTION.md) is an exception for
  the Play Billing Library, which no longer ships (B81) and is explained there; F-Droid
  maintainers may ask why it is still present, so be ready to answer.
- **Source build of the Python layer.** The embedded engine needs `beancount` and `watchfiles`
  cross-built for Android (`tools/build-wheels.sh`, pinned sdists and backends) and Chaquopy's
  Gradle plugin. F-Droid builds from source on its own servers, so its recipe has to run that
  script and fetch the pinned Chaquopy wheels; none of this has been tried on F-Droid's build
  server. Expect this to be the hard part.
- **Edition.** Submit the **Play-flavour code path** (no native package installs, no network
  code fetching) or the Expert edition with the right anti-feature labels; the Expert edition
  fetches Python packages from the network only when the user asks, and runs user scripts.
  Which one F-Droid accepts is for its maintainers to say. `docs/distribution.md` §5 had this
  as "revisit after v1".
- **Reproducible build / signing.** The app signing key is offline (release-checklist.md).
  F-Droid can sign with its own key, or verify a reproducible build against ours; the build
  is not yet shown to be reproducible.

Next step when the owner wants it: open an F-Droid "Request for Packaging" with the metadata
file below and answer the maintainers' questions.

```yaml
Categories:
  - Money
License: GPL-2.0-only
SourceCode: https://github.com/<owner>/<repo>
IssueTracker: https://github.com/<owner>/<repo>/issues
AutoName: Beanbook
RepoType: git
Repo: https://github.com/<owner>/<repo>.git
```
