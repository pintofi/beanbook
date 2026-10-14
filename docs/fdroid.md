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
- **Reproducible build / signing.** The app signing key is a repository secret used by the release workflow, with an offline backup.
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

`fdroid/io.github.pintofi.beanbook.expert.yml` is the fdroiddata build recipe for the **Expert
edition** (application ID `io.github.pintofi.beanbook.expert`). It points `Binaries` at the signed
APK on the GitHub release and lists the project's signing certificate in
`AllowedAPKSigningKeys`, so F-Droid can verify its own build against ours and publish the
signed APK. It is a draft: the `init`/`prebuild` steps that cross-build the wheels have not been
run on F-Droid's infrastructure, and the build is not yet shown to be reproducible. Expert can
install Python packages and run user scripts when the user asks; say so to the maintainers.

### Building the wheels on F-Droid's server

F-Droid's SDK has no Google `cmdline-tools`, which is what the Android CPython toolchain uses
to install the NDK. `tools/build-wheels.sh` therefore also accepts an NDK 27 supplied through
`ANDROID_NDK_HOME` (the recipe's `ndk:` line) and links it into a private SDK directory. The
recipe installs `rustup` and runs `rustup default stable` so the Android standard library can be
added for `watchfiles`. The NDK path was checked locally with `beancount`; the Rust half, and the
whole recipe, are unproven on F-Droid until its build runs. The fix is in the build script, so it
is in the v1.0.0 tag, which the recipe pins by commit.
