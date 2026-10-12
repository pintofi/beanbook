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
- **Licence.** GPL-2.0-only with no additional permissions: the dormant Play Billing exception
  was withdrawn on 2026-10-12 (nothing is sold, no build links the library), so there is
  nothing for F-Droid maintainers to ask about.
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

## Draft recipe

`fdroid/io.github.pintofi.beanbook.yml` is the fdroiddata build recipe, written for the Play
code path (no native package installs, no code fetched from the network), which is the
edition F-Droid is least likely to object to. It is a draft: the `init`/`prebuild` steps that
build the cross-built wheels have not been run on F-Droid's infrastructure, and `<owner>/<repo>`
is a placeholder until the public repository is named. Note the Play edition's application ID
(`io.github.pintofi.beanbook`) is the same as Google Play's, so a user cannot update from one
store to the other (different signing keys); the Expert edition uses `.expert`.
