# F-Droid and fastlane

## The `fastlane/` folder

`fastlane/metadata/android/en-US/` holds the store text in the layout both F-Droid and Google
Play (through fastlane `supply`) read: `title.txt`, `short_description.txt` (80 characters at
most), `full_description.txt`, `changelogs/<versionCode>.txt` and `images/`. It is the one
source for listing text; write to it, not to a store console. A new release adds
`changelogs/<versionCode>.txt` for its `versionCode`.

`images/` holds the icon, the feature graphic and eight phone screenshots. Seven-inch and
ten-inch screenshots are optional and not present.

## Status

The Expert edition's recipe is in a merge request against `fdroiddata`. Its pipeline passed on
2026-10-15, including the build job, which cross-built the wheels, built the APK from source
and checked it against the signed APK on the GitHub release
([pipeline](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2952392596)). Whether and when
the listing appears is for F-Droid's maintainers to say.

What F-Droid sees:

- **No proprietary libraries.** Billing and ML Kit are gone; nothing from Google Play services
  is linked.
- **Licence.** GPL-2.0-only with no additional permissions.
- **Source build of the Python layer.** The embedded engine needs `beancount` and `watchfiles`
  cross-built for Android (`tools/build-wheels.sh`, pinned sdists and backends) and Chaquopy's
  Gradle plugin; the recipe runs that script and fetches the pinned Chaquopy wheels.
- **Edition.** The Expert edition, which fetches Python packages from the network only when
  the user asks, and runs user scripts. The Play edition is not submitted here.
- **Signing.** The app signing key is a repository secret used by the release workflow, with an
  offline backup. The recipe lists the certificate in `AllowedAPKSigningKeys`, so F-Droid
  verifies its own build against ours and publishes the signed APK.

## The recipe

`fdroid/io.github.pintofi.beanbook.expert.yml` is the fdroiddata build recipe for the **Expert
edition** (application ID `io.github.pintofi.beanbook.expert`). It points `Binaries` at the signed
APK on the GitHub release and lists the project's signing certificate in
`AllowedAPKSigningKeys`, so F-Droid can verify its own build against ours and publish the
signed APK. Expert can install Python packages and run user scripts when the user asks; the
maintainers have been told.

### Building the wheels on F-Droid's server

F-Droid's SDK has no Google `cmdline-tools`, which is what the Android CPython toolchain uses
to install the NDK. `tools/build-wheels.sh` therefore also accepts an NDK 27 supplied through
`ANDROID_NDK_HOME` (the recipe's `ndk:` line) and links it into a private SDK directory. The
recipe installs `rustup` and runs `rustup default stable` so the Android standard library can be
added for `watchfiles`. The recipe asks for NDK r27d; F-Droid's build server does not have it
preinstalled, so its log says `Android NDK version 'r27d' could not be found!` and then
downloads and installs it as 27.3.13750724. That warning is expected. The recipe pins the
v1.0.0 tag by commit.

### Reproducible wheels

The wheels are byte-for-byte reproducible, which is what F-Droid's `Binaries` check needs.
`tools/build-wheels.sh` fixes `SOURCE_DATE_EPOCH`, makes cibuildwheel's temporary directory names
count instead of being random, drops the GNU build-id from beancount's extension (a patch), and
pins the Rust compiler. The NDK is r27d (27.3.13750724), the same as the release build. Checked
2026-10-15: two builds on one machine, and a build on a second machine against the wheels
GitHub's runner built, are identical for all four wheels. An unsigned universal APK built from
those wheels in two different checkout paths is identical too. F-Droid's own build of the
Expert edition was compared with the signed APK in the pipeline above ("compared built
binary to supplied reference binary successfully").
