# Contributing to Beanbook

Bug reports, patches, and plugin compatibility results are welcome.

This is not a plain Android checkout. The app embeds a CPython runtime and two Python packages
that have no upstream Android builds, so a first build needs a little setting up. That is most
of what this file is about.

## Licence, before anything else

The app is **GPL-2.0-only**. Beancount, beanquery, and beangulp are GPL-2.0-*only*, explicitly
not "or later", and they run in-process, so the combined work carries the same terms.

- **GPLv3 code cannot go in.** There is no exception to this.
- Apache-2.0 is not GPL-2.0-compatible for linked code. It is used for build-time tooling and
  for AndroidX, which an Android app cannot avoid; see
  [docs/architecture.md](docs/architecture.md) §7.1 for how that is reconciled.
- Any new dependency needs a licence check before it is added, not after.

## What you need

- **JDK 21.** The Kotlin toolchain is pinned to 21; the app compiles against Java 17 bytecode.
- **Android SDK** with `compileSdk` 37. That platform package is named `platforms;android-37.0`
  rather than `android-37`, which trips people up.
- **Gradle 9.8.1**, via the wrapper in this repository. Use `./gradlew`.
- **A host CPython 3.13.** Chaquopy runs pip with it at build time. It is a build tool, not the
  interpreter that ships in the APK.
- **The two cross-built wheels**, `beancount` and `watchfiles`. Neither publishes an Android
  build upstream, so this repository builds them itself.

Two Gradle properties point at the last two:

```
-Pbeancount.buildPython=/path/to/python3.13
-Pbeancount.wheelDir=/path/to/wheels
```

Both default to locations under `~/tools`. If your setup matches that, you can leave them out.

Build the wheels once:

```bash
tools/build-wheels.sh --out ~/tools/wheels
```

That also writes `engine-local/requirements-local.lock`, which pins those two wheels for your
machine. The other Python dependencies are pinned with hashes in
`engine-local/requirements.lock`, so pip runs in `--require-hashes` mode and any mismatch stops
the build.

Changing a wheel means rebuilding `:engine-local`'s Python assets. Cleaning only `app/build`
leaves the previous `.so` inside the APK while the tests still pass, which is a confusing hour
if you do not know to expect it.

## Building

```bash
./gradlew :app:assemblePlayDebug :app:assembleExpertDebug
./gradlew :app:assemblePlayRelease :app:assembleExpertRelease
```

Release signing material is not in this repository and never will be. A build without it
succeeds and produces an **unsigned** APK, so that anyone can build without being able to sign
as the project.

### Gradle dependency checksums

`gradle/verification-metadata.xml` holds the SHA-256 of every library and plugin the build
downloads, and Gradle refuses one that does not match. A dependency change therefore also
changes that file, in the same pull request. Regenerate it by running the tasks CI runs with
`--write-verification-metadata sha256`, then review the diff: only the artifacts you meant to add
or change should appear.

```bash
./gradlew --write-verification-metadata sha256 -Pbeancount.wheelDir="$HOME/tools/wheels" \
  :app:assembleExpertRelease :app:assemblePlayRelease :app:assembleExpertDebugAndroidTest \
  :app:assemblePlayDebugAndroidTest :app:lintPlayDebug :app:lintExpertDebug \
  :app:testPlayDebugUnitTest :core-domain:test :core-data:test :core-engine:test detekt
```

The checksums are recorded the first time an artifact is seen, so they catch a later change to
an artifact, not one that was already wrong when first downloaded. Signatures are not checked.

## Testing

Kotlin unit tests, which is what CI runs:

```bash
./gradlew :core-domain:test :core-data:test :core-engine:test \
          :core-entitlement:test :engine-remote:test
```

Static analysis. This gates CI and is attached to no other task, so it is easy to forget until
a pull request goes red:

```bash
./gradlew detekt
```

Every rule that is switched off is named in `config/detekt.yml` with a reason.

The versions of Beancount, Fava, CPython and the rest that the README, the website and the docs
state are checked against what the build pins, including the version drawn inside each README
badge. A dependency bump that leaves one of them behind fails CI. Run the check yourself and fix what
it names; `python3 tools/badges.py` redraws the badges (both need `pip install fonttools brotli`):

```bash
python3 tools/check-versions.py --require-badges
```

The Play edition's policy boundaries, which is a build gate rather than a lint:

```bash
./gradlew :app:verifyPlayFlavorBoundaries
```

Android lint, both flavors:

```bash
./gradlew :app:lintPlayDebug :app:lintExpertDebug
```

Instrumentation tests need a device or emulator. They also run every Monday on API 29 and 36
(`.github/workflows/weekly.yml`), outside the required checks; that run can be started by hand from
the Actions tab. Gradle refuses to run with several attached,
and Wi-Fi ADB changes port on reconnect, so pin the serial:

```bash
export ANDROID_SERIAL=<serial from adb devices>
./gradlew :app:connectedExpertDebugAndroidTest
```

`ScreenshotTest` draws the main screens (Home, Journal, Balances, Documents, More, Add entry) in
light and dark at normal and double text size and compares them with the pictures in
`app/src/androidTest/assets/screenshots`. It only runs on API 36, where the pictures are drawn, and
the weekly job reports a difference as a failure, not a block. After a change to how a screen
looks that you meant to make, start the weekly workflow by hand with "Draw the screenshots again"
ticked, download the `screenshots` folder from the API 36 artifact into that assets folder, and
commit it: the pictures change in the same pull request as the code, so a reviewer sees them.
A failing run keeps what was drawn and a picture with the differing pixels in red.

The Python layer uses the standard library runner, but the interpreter has to have `beancount`
importable, because a couple of cases load a ledger:

```bash
<python-with-beancount> -m unittest discover -s engine-local/src/test/python
```

The golden corpus checks the app's parsing against upstream Beancount. Generate the fixtures,
then verify:

```bash
<python-with-beancount> tools/corpus/generate.py --out corpus
<python-with-beancount> tools/corpus/differential.py verify
```

A few instrumentation tests need something the suite cannot provide on its own. They are
written to fail with the reason rather than pass quietly:

- `WheelFetcherTest`, `BeangrowOnDeviceTest`, and one case in `FavaExtensionTest` need network
  access, because they exercise the on-demand package installer and there is no other way to
  test it. `BeangrowOnDeviceTest` fetches 37 MB the first time and reuses it afterwards.
- `SafExportTest` needs a folder granted to the app once per device, because it writes through
  a real `DocumentsProvider`. The grant is persistable, so only the first run opens the system
  picker. Android refuses to grant `Download` and the other shared collections, so create a
  folder in the picker and grant that one.

You can run the CI workflow locally, step by step:

```bash
./tools/run-ci-locally.sh .github/workflows/verify.yml
```

## Constraints a change has to keep

These are the things most likely to make an otherwise good patch unmergeable.

**Money is `Decimal`.** Binary floating point must not touch an amount at any layer.

**Nothing reports success before it is durable.** A write is locked, snapshotted, journalled,
written to a temporary file and fsynced, atomically renamed, and re-parsed. Only then does the
app say it worked. On failure the snapshot is restored.
[docs/storage-and-backup.md](docs/storage-and-backup.md) §3 has the protocol.

**A failed operation leaves the previous valid project recoverable**, and a retried write does
not create a duplicate entry.

**Unknown syntax survives.** Directives the app has no editor for, along with metadata,
comments, whitespace outside an edited region, `include` directives, and relative document
paths, all have to come through editing, export, and backup intact.

**The edition split is a build gate.** `:app:verifyPlayFlavorBoundaries` fails the build if the
Play edition gains the ability to install native code, download code from a network source,
expose the Android bridge to user Python, or self-update. Plugin loading is shared code
parameterised by a policy type rather than two implementations. Note that these are Play policy
compliance, not a security sandbox, and the UI must not suggest otherwise.

**Security rules that are not negotiable.** The loopback Fava binds `127.0.0.1` on an ephemeral
port with a per-process token, never a LAN interface. The Fava WebView keeps file and content
access off, universal file access off, `mixedContentMode = NEVER_ALLOW`, an origin allowlist,
and no JavaScript bridge. Certificate errors on an `https://` address are fatal in every build,
and no `trustAll` TLS code may exist anywhere in the repository, which CI scans for. No
telemetry, analytics, crash uploads, or ads, in any edition. No secrets in source, logs,
backups, screenshots, or diagnostics.

## Project layout

Seven Gradle modules, with the dependency direction described in
[docs/architecture.md](docs/architecture.md) §6:

| Module | Contents |
|---|---|
| `core-domain` | Pure Kotlin. Holds the data invariants. |
| `core-data` | Workspace layout and project files |
| `core-engine` | The engine boundary and the flavor policy types |
| `engine-local` | Chaquopy, Beancount and Fava, the loopback server |
| `engine-remote` | Hardened client for a self-hosted Fava |
| `app` | Compose shell, with the `play` and `expert` flavors |

The engine runs in its own process. That is deliberate and load-bearing for the licence
position as well as for stability; `docs/architecture.md` §5 and §7.1 explain why.

Patches to upstream Python packages live in `patches/`, one file per patch, documented and
rebasable. The project does not fork upstream.

## Releasing

A release is a tag, but the text is written before it. For version `x.y.z`:

1. Raise `versionName` and `versionCode` in `app/build.gradle.kts` (the code by one).
2. Move the `[Unreleased]` entries of `CHANGELOG.md` under a new `## [x.y.z] - YYYY-MM-DD`
   heading, newest version first, and leave `[Unreleased]` empty at the top. The file follows
   [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and Semantic Versioning. Group
   entries under `### Added`, `### Changed`, `### Deprecated`, `### Removed`, `### Fixed` and
   `### Security`, and leave out a section with nothing in it. List every deprecation, removal and
   breaking change, even a small one. A release that had to be pulled keeps its heading with
   ` [YANKED]` after the date. Write for the people who use the app, not as a commit log. Every
   entry ends with the pull request or issue it came from, written as `([#93], fixes [#69])`. Add
   one `fastlane/metadata/android/en-US/changelogs/<code>.txt` for each of the three codes (`<code>`
   is the release number followed by 0, 1 and 2). Then run `tools/changelog-links.py`, which writes
   the link definitions at the bottom, so each `[x.y.z]`, `[Unreleased]` and `[#N]` is a link. CI runs
   the same script with `--check` and fails on a missing date, an unknown or empty section, an entry
   with no reference, or a stale link; it runs again on the tag.
3. Write the summary that opens the release in `release-notes/x.y.z.md`: a few plain sentences
   on what the release is for, whatever its size. The rest of the release text is built by the
   release workflow from the version's section in `CHANGELOG.md` (its types as headings, `[#93]`
   turned into a link GitHub shows), followed by the same Download table with each file's checksum,
   Verify section and compare link every release has. The workflow stops, before the build, if the
   summary is missing, and stops if the changelog section is missing or empty.
4. Update `ROADMAP.md` and the README checklist, and the builds in
   `fdroid/io.github.pintofi.beanbook.expert.yml`.
5. Tag `vx.y.z`. The workflow builds and signs the APKs and drafts the release from
   `.github/release-notes-template.md`: your notes, a download table, the certificate fingerprint,
   the checksums and a link to the changes since the previous release. A person reads the draft and publishes it.

## Pull requests

Everyone taking part in issues, pull requests and discussions is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

- Keep commits small and descriptive.
- Versions are pinned. Upgrades are their own change, with the golden corpus re-run, rather
  than a drive-by bump inside a feature.
- Do not add placeholder implementations presented as finished, disabled or skipped tests,
  swallowed exceptions, or blanket lint suppressions.
- New user-facing surfaces need accessibility semantics. There is an automated gate at normal
  and 200% font size, and it carries a negative control that fails if the checks stop biting.
- Say what you actually ran. Commands and their results are more useful than an assurance that
  something was checked.

If you are reporting a plugin or Fava extension result,
[docs/plugin-compatibility.md](docs/plugin-compatibility.md) describes what a useful report
contains.

## Further reading

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, trust boundaries, process model, dependency direction |
| [docs/storage-and-backup.md](docs/storage-and-backup.md) | Workspace layout, atomic writes, SAF import and export, archives |
| [docs/threat-model.md](docs/threat-model.md) | Assets, adversaries, mitigations, accepted risk |
| [docs/distribution.md](docs/distribution.md) | Edition matrix, update channels, the Play payment rules |
