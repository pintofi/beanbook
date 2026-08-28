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

## Why not pip

A wheel is a zip. Resolving one, checking a digest and unpacking it is a few hundred lines;
bundling pip is several megabytes of machinery for the same result on a device that will run
it perhaps twice. What is given up is pip's resolver, so `WheelFetcher.resolve` walks the
metadata itself and the caller is told exactly what will be fetched **before** anything
crosses the network — which is the difference between a download the user chose and one that
happened to them.

## What it will and will not fetch

- **Pure-Python wheels from PyPI.** PyPI has no Android wheels for anything with a C
  extension, so a `py3-none-any` wheel is the only thing there that can run here.
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

One exists today. beangrow imports SciPy for a single call, a one-dimensional root find for
the internal rate of return, and SciPy has no CPython 3.13 build for Android. The overlay
replaces that file with one whose solver agrees with `scipy.optimize.fsolve` to 3.2e-13. The
same change is in `patches/` as a diff, for sending upstream.

Dependencies an overlay makes unnecessary are declared in its own `REMOVES.txt`, beside the
file that justifies them, rather than as a list inside the resolver — an exclusion that lives
away from its reason is one nobody can check.

## Why the Play edition cannot

Retrieving executable code from anywhere other than the store is what the Device and Network
Abuse policy prohibits, and a pure-Python wheel is still executable code. `allowsPackageDownload`
is `false` there, `:verifyPlayFlavorBoundaries` requires the Play sources to **say** so rather
than merely not contradict it, and the Play edition's `WheelFetcher` is a different file that
refuses. Checked in both directions: flipping the flag fails the build.
