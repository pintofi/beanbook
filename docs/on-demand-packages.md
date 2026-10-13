# Fetching packages on demand

**Expert edition only.** The Play edition cannot do this and the build proves it.

## Why it exists

The app bundles what Beancount and Fava need and nothing more. A few things a user might
genuinely want cost far more than that:

| feature | needs | download | on disk |
|---|---|---|---|
| beangrow returns reports | numpy, pandas, matplotlib, protobuf, and their companions | 37 MB | **~143 MB** |
| `fava_portfolio_returns` | the same | — | — |
| `fava-envelope` | pandas | — | — |
| `fava-dashboards` | PyYAML | — | — |

Note the second column against the third. Wheels are compressed archives, so **the download
figure is not the storage figure** — beangrow's closure downloads 37 MB and occupies about
143 MB unpacked. Quoting only the download would understate what this costs on a phone by
nearly four times, and storage is the scarcer of the two.

Putting that in every download, for a feature most people will not use, is the wrong trade.
So the expert edition fetches it when asked, once, and keeps it.

## How a user reaches it

**More → Plugins → Extra Python packages.** Type a name, and the app resolves the closure and
stops: it shows how many packages, which ones, what could not be found for this device, and
that they will take noticeably more room unpacked than they do to download. Only then is there
a **Download** button. Backing out is **Not now**, and it leaves the device exactly as it was.

Fetched packages are removed all at once rather than one at a time — they were resolved as a
closure, so removing one would leave the others importable and broken.

They land in the app's private storage outside any project, so they are never exported with a
ledger and never reach a backup, and the directory is handed to the engine alongside the
enabled plugins. Under safe mode nothing is handed over, these included: safe mode means load
nothing of mine, and a library that shadowed something would make that promise false.

The Play edition shows the section as a sentence explaining that it installs nothing beyond
what is built in. Hiding the control is not the enforcement — its `WheelFetcher` refusing is.

## Why not Termux's repositories

Asked directly, and worth recording because the answer is not obvious: Termux publishes a
full APT archive of Android-native builds -- `python` 3.14, `python-numpy` 2.4, and hundreds
more -- and it would appear to solve the native-code problem this document is about.

It cannot be used here, and the reason is in the packages themselves. Unpacking
`python-numpy_2.4.4-1_aarch64.deb` from `packages.termux.dev`:

- every file lands under `/data/data/com.termux/files/usr/...`, which is **another app's
  private directory**; this app cannot read it, and the packages are built with that prefix
  baked in rather than relocated at install time;
- the extension modules are tagged `cpython-314` and `NEEDED: libpython3.14.so`, while this
  app embeds **CPython 3.13** through Chaquopy -- a different ABI, so they would not import
  even if they were readable;
- `RUNPATH: /data/data/com.termux/files/usr/lib`, with `NEEDED: libopenblas.so` and
  `libc++_shared.so`, neither of which is in this app.

Using them would mean rebuilding every package against this app's interpreter and prefix,
which is being a distribution maintainer rather than consuming one. Chaquopy's index already
does exactly that job for the interpreter this app ships, which is why the fetcher uses it for
native packages and PyPI for pure-Python ones.

Their *pure-Python* packages are simply PyPI's, repackaged; fetching those from PyPI directly
is what already happens, with hashes, and adds no dependency on a third-party archive.

**Beancount plugins and scripts are not affected either way.** Termux has no Beancount plugin
repository -- a plugin here is a Python file a user installs, and the packages it may import
are the subject above.

## Why not pip

A wheel is a zip. Resolving one, checking a digest and unpacking it is a few hundred lines;
bundling pip is several megabytes of machinery for the same result on a device that will run
it perhaps twice. What is given up is pip's resolver, so `WheelFetcher.resolve` walks the
metadata itself and the caller is told exactly what will be fetched **before** anything
crosses the network — which is the difference between a download the user chose and one that
happened to them.

## What it will and will not fetch

- **Pure-Python wheels from PyPI.**
- **Android wheels from PyPI**, since PEP 738 lets projects publish them: taken only when
  built for CPython 3.13 or the stable ABI, for API 29 or older, and for this device's
  architecture. Until 2026-09-14 the fetcher took only `py3-none-any` from PyPI, on the belief
  that PyPI had no Android wheels -- no longer true.
- **Android wheels from Chaquopy's index**, for packages with native code, built against the
  exact interpreter this app embeds.
- **Never a package already in the APK.** Fetching `beancount` would put a second copy ahead
  of the tested one on `sys.path`, which is a good way to make a working app stop working.

A dependency with no Android build is **recorded, not fatal**. beangrow declares `beanprice`,
which it uses only in its price-downloading commands, and `beanprice` needs a native package
with no Android build. Refusing the whole install over a dependency the user's actual feature
never touches would be tidy and useless — so the closure is installed, what could not be
fetched is shown, and anything that does reach for it fails at import with its name.

## Safety

- **Every wheel's digest is checked before it is unpacked.** PyPI publishes digests;
  Chaquopy's index publishes none, so the digest is computed from what was received — which
  pins nothing about the index and everything about this install.
- **Archive members that escape the directory are refused.** A wheel is an archive from the
  network, and `../../something` is the oldest trick there is.
- **It is not a security boundary.** Installed code runs with the app's privileges, exactly
  like a plugin the user supplies as a file. The app says so.

## Overlays

`assets/overlays/<package>-<version>/` holds files that replace a fetched package's own,
applied **only when the installed version matches exactly**. A newer release is left alone —
an overlay that applies to whatever happens to be there is a way to break a package quietly.

Two exist today, and both are the same change. beangrow imports SciPy for a single call, a
one-dimensional root find for the internal rate of return, and SciPy has no CPython 3.13 build
for Android. The overlay replaces that file with one whose solver agrees with
`scipy.optimize.fsolve` to 3.2e-13. The same change is in `patches/` as a diff, for sending
upstream. `fava-portfolio-returns` vendors its own copy of beangrow, so it carries the same
replacement for the vendored file.

Dependencies an overlay makes unnecessary are declared in its own `REMOVES.txt`, beside the
file that justifies them, rather than as a list inside the resolver — an exclusion that lives
away from its reason is one nobody can check.

**Both install routes apply them.** A package fetched as a dependency is overlaid by
`WheelFetcher`; a plugin installed from the catalogue brings its own archive, and
`CatalogueOverlay` overlays that after the code is unpacked and drops the removed requirements
before anything is fetched. Until this was added, installing `fava-portfolio-returns` ended
with "Parts that need scipy could not be installed" and a vendored file that still imported
SciPy — for a file the APK already carried a replacement for.

## Why the Play edition cannot

Retrieving executable code from anywhere other than the store is what the Device and Network
Abuse policy prohibits, and a pure-Python wheel is still executable code. `allowsPackageDownload`
is `false` there, `:verifyPlayFlavorBoundaries` requires the Play sources to **say** so rather
than merely not contradict it, and the Play edition's `WheelFetcher` is a different file that
refuses. Checked in both directions: flipping the flag fails the build.

## Packages with no Android build: curl_cffi, and what can be done

Reported 2026-09-14: a script needs `curl_cffi`, and the Plugins page said there was no
Android-compatible wheel. In Termux, installing `python-cryptography` with `pkg` fixed it.

**What was found (2026-09-14):**

- `curl_cffi` 0.16.3 **does publish an Android wheel on PyPI**:
  `curl_cffi-0.16.3-cp313-cp313-android_24_arm64_v8a.whl` -- CPython 3.13, arm64, exactly this
  app's interpreter on a phone. The fetcher refused it only because it took nothing but
  pure-Python wheels from PyPI. **Fixed**: the Expert edition now takes it on an arm64 device.
  There is no x86_64 Android wheel, so it cannot be installed on the x86_64 emulator, and **it
  has not yet been tried on a phone**.
- It declares `cffi>=2.0.0` and `certifi`. `certifi` is pure Python. `cffi` has **no** Android
  wheel on PyPI, and Chaquopy's index has `cffi` **1.17.1**. The fetcher does not enforce
  version ranges, so it installs 1.17.1; whether `curl_cffi`'s compiled module loads against
  that older `_cffi_backend` is **unproven** and is the first thing to check on the phone.
- `cryptography`: Chaquopy's index has **42.0.8** for CPython 3.13 on both architectures, and the
  fetcher takes it (PyPI's 50.x has no Android wheel). That is this app's equivalent of Termux's
  `pkg install python-cryptography`; add it under **Extra Python packages**.

**Why Termux differs.** Termux runs its own CPython 3.14 and builds packages from source with
its own toolchain and prefix; its packages cannot be used by this app's CPython 3.13 (above).

**The options, in the order worth trying:**

1. **Fetch it** (done): PyPI Android wheels plus Chaquopy's index cover more than the old fetcher
   knew. On an arm64 phone: Plugins → Extra Python packages → `curl_cffi`, then restart the
   engine and run the script. If `cffi` 1.17.1 is too old, that surfaces as an import error
   naming `_cffi_backend`.
2. **Build the missing wheel ourselves** for CPython 3.13 on Android, with `cibuildwheel`'s
   Android platform or Chaquopy's build-wheel tool, and publish it with a hash the fetcher pins.
   This is how a `cffi` 2.x build would arrive if (1) fails. Expert only: Play may not install
   native code. Days of work for the first one, plus upkeep per release.
3. **A pure-Python fallback in the script**, where the site does not need a browser's TLS
   fingerprint: `curl_cffi.requests` is largely call-compatible with `requests`, which is pure
   Python. Not a fix for sites that do need impersonation, which is what `curl_cffi` is for.
4. **Run it in Termux** from the Expert edition, through Termux's `RUN_COMMAND` intent, against
   the mirror folder. Everything Termux can install becomes available, at the cost of a second
   app, a permission the user grants to it, and the script working on a copy of the ledger.

5. **Run it elsewhere**, against remote mode's Fava.

**Next step:** try (1) on the phone and send the script or its imports if it fails, so the
failure can be read rather than guessed at.
