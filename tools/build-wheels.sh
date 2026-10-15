#!/usr/bin/env bash
# Cross-build the two Android wheels the app cannot get from PyPI.
#
# `beancount` (C++) and `watchfiles` (Rust) have no upstream Android wheels. Until this
# script existed they were built by hand on one laptop and read out of ~/tools/wheels,
# which meant no other machine — including a CI runner — could build the app at all
# (docs/pending-verification.md N16).
#
# Everything here is fetched from the network with a pinned hash. Nothing is taken from
# the developer's machine.
#
#   tools/build-wheels.sh [--out DIR] [--work DIR]
#
# Needs: python3 with venv, and a rustup toolchain for watchfiles. cibuildwheel installs
# the NDK and downloads the target CPython from Chaquopy's Maven artifacts itself, so the
# wheels are ABI-matched to the runtime the app ships.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$here/engine-local/wheels.lock"
out="${BCA_WHEEL_DIR:-$HOME/tools/wheels}"
# Deliberately not under $HOME: absolute build paths end up inside the artefacts
# (Rust panic metadata, the CycloneDX SBOM), and those artefacts ship.
work="/tmp/bca-wheels"
archs="arm64_v8a,x86_64"

while [ $# -gt 0 ]; do
    case "$1" in
        --out) out="$2"; shift 2 ;;
        --work) work="$2"; shift 2 ;;
        --archs) archs="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

mkdir -p "$out" "$work"
out="$(cd "$out" && pwd)"
work="$(cd "$work" && pwd)"

say() { printf '\n=== %s\n' "$*"; }

# --- the host this runs on ----------------------------------------------------------
# Everything the build needs from the machine itself, checked together, so a missing package
# is reported once with all of its friends instead of one failure per attempt. Debian package
# names are given because the F-Droid build server is Debian; other systems have equivalents.
missing=()
need() { command -v "$1" >/dev/null 2>&1 || missing+=("$2 (provides '$1')"); }

need python3 python3
python3 -c 'import venv, ensurepip' >/dev/null 2>&1 || missing+=("python3-venv (python3 -m venv does not work)")
need m4 m4                      # the bison and flex wheels shell out to it
need cc gcc                     # Rust compiles its build scripts for this machine
need rustup rustup              # watchfiles: the Android standard library comes from rustup

# A C compiler is not enough: linking a program needs the C library's start files and libc.
if command -v cc >/dev/null 2>&1; then
    probe="$(mktemp -d)"
    printf 'int main(void){return 0;}\n' > "$probe/p.c"
    cc "$probe/p.c" -o "$probe/p" >/dev/null 2>&1 || missing+=("libc6-dev (cc cannot link a C program: Scrt1.o, crti.o, -lc)")
    rm -rf "$probe"
fi

if [ "${#missing[@]}" -gt 0 ]; then
    echo "::error::this machine lacks what the wheel build needs:" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi

# --- the build tools ---------------------------------------------------------------
# cibuildwheel goes in its own venv so this never depends on what happens to be
# installed system-wide, which is the whole point of the exercise.
venv="$work/cibuildwheel-venv"
if [ ! -x "$venv/bin/cibuildwheel" ]; then
    say "installing cibuildwheel into $venv"
    python3 -m venv "$venv"
    "$venv/bin/pip" install --quiet --upgrade pip
    "$venv/bin/pip" install --quiet 'cibuildwheel==4.2.0'
fi
cbw="$venv/bin/cibuildwheel"
echo "cibuildwheel: $("$venv/bin/pip" show cibuildwheel | awk '/^Version:/{print $2}')"

# cibuildwheel's Android support sources CPython's android-env.sh, which wants the NDK at
# $ANDROID_HOME/ndk/<ndk_version> and, if it is missing, installs it with Google's
# cmdline-tools sdkmanager. Three situations, in the order they are tried:
#   1. the NDK is already at that path (a developer machine, a GitHub runner image);
#   2. there is a real sdkmanager, which will install it;
#   3. neither, but an NDK is supplied through ANDROID_NDK_HOME / ANDROID_NDK_ROOT /
#      ANDROID_NDK. That is the F-Droid build server, whose SDK has no cmdline-tools but which
#      installs the NDK the recipe asks for. It is linked into a private SDK directory, because
#      the system one may not be writable and nothing here should modify it.
ndk_version="27.3.13750724"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$sdk" ] && [ -d "$sdk/ndk/$ndk_version" ]; then
    :
elif [ -n "$sdk" ] && [ -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    :
else
    ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}}"
    if [ -z "$ndk_home" ] || [ ! -d "$ndk_home/toolchains/llvm/prebuilt" ]; then
        echo "::error::no Android NDK, and no sdkmanager to install one" >&2
        echo "  ANDROID_HOME=${sdk:-(unset)}" >&2
        echo "  Either have the NDK at \${ANDROID_HOME}/ndk/$ndk_version, install the SDK" >&2
        echo "  command-line tools at \${ANDROID_HOME}/cmdline-tools/latest, or point" >&2
        echo "  ANDROID_NDK_HOME at an NDK 27." >&2
        exit 1
    fi
    ndk_rev="$(sed -n 's/^Pkg.Revision *= *//p' "$ndk_home/source.properties" 2>/dev/null || true)"
    case "$ndk_rev" in
        27.*) ;;
        *) echo "::error::the NDK at $ndk_home is revision '${ndk_rev:-unknown}'; the Android CPython toolchain is for NDK 27" >&2
           exit 1 ;;
    esac
    if [ "$ndk_rev" != "$ndk_version" ]; then
        echo "note: NDK $ndk_rev used where CPython's android-env.sh names $ndk_version"
    fi
    sdk="$work/android-sdk"
    rm -rf "$sdk"
    mkdir -p "$sdk/ndk"
    ln -s "$ndk_home" "$sdk/ndk/$ndk_version"
    echo "using the NDK at $ndk_home through a private SDK directory"
fi
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
echo "android sdk: $sdk"

# watchfiles is PyO3/maturin. A distribution rust package ships only the host standard
# library; the Android `std` comes from `rustup target add` and nothing else (s3).
#
# The compiler version is pinned, because a different rustc produces a different binary and
# the wheels are meant to be byte-for-byte reproducible (docs/fdroid.md). Whatever "stable" is
# on the day would make two builds of the same sources differ.
rust_version="1.99.0"
if command -v rustup >/dev/null 2>&1; then
    rustup toolchain install "$rust_version" --profile minimal \
        --target aarch64-linux-android --target x86_64-linux-android >/dev/null
    export RUSTUP_TOOLCHAIN="$rust_version"
    echo "rust: $(rustc --version)"
else
    echo "warning: no rustup on PATH — the watchfiles build will fail" >&2
fi

# `actions/setup-python` exports PKG_CONFIG_PATH pointing at the *host* interpreter's
# pkgconfig directory, and pkg-config searches PKG_CONFIG_PATH **before** the
# PKG_CONFIG_LIBDIR that cibuildwheel sets to the Android prefix. So on a GitHub runner meson
# resolved Python against an x86_64 libpython while cross-compiling for aarch64, and on a
# laptop — where the variable is unset — it resolved the Android one and everything worked.
# That single line of difference produced two entirely different failures
# (docs/milestones/m14-part12.md).
if [ -n "${PKG_CONFIG_PATH:-}" ]; then
    echo "ignoring PKG_CONFIG_PATH=$PKG_CONFIG_PATH: it points at the host Python"
    unset PKG_CONFIG_PATH
fi

# --- pinning what the build itself uses ---------------------------------------------
backends="$here/engine-local/build-backends.lock"
export PIP_CONSTRAINT="$backends"
echo "build backends pinned by $backends"

# Rust records the absolute path of every source file it compiles, for panic messages.
# Without remapping, the shipped .so names the build machine's home directory — which in
# a privacy-focused app is an identity leak in the artefact, not a cosmetic detail.
export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo --remap-path-prefix=$work=/src ${RUSTFLAGS:-}"
export CIBW_ENVIRONMENT="RUSTFLAGS='$RUSTFLAGS'"

# --- the sources -------------------------------------------------------------------
fetch() {
    local name="$1" version="$2" want="$3"
    local tarball="$work/$name-$version.tar.gz"

    if [ ! -f "$tarball" ]; then
        say "downloading $name $version"
        # Fetched straight from PyPI rather than via `pip download`, which insists on
        # preparing the sdist's metadata and so needs the build backend installed before
        # it will hand over the file. We only want the bytes; cibuildwheel does the rest.
        python3 - "$name" "$version" "$tarball" <<'FETCH'
import json, sys, urllib.request
name, version, dest = sys.argv[1:4]
with urllib.request.urlopen(f"https://pypi.org/pypi/{name}/{version}/json", timeout=120) as r:
    release = json.load(r)["urls"]
url = next(u["url"] for u in release if u["packagetype"] == "sdist")
urllib.request.urlretrieve(url, dest)
print(f"  {url}")
FETCH
    fi

    local got
    got="$(sha256sum "$tarball" | cut -d' ' -f1)"
    if [ "$got" != "$want" ]; then
        echo "::error::$name $version sdist hash mismatch" >&2
        echo "  expected $want" >&2
        echo "  got      $got" >&2
        exit 1
    fi
    echo "$name $version sdist verified: $got"

    rm -rf "$work/$name-$version"
    tar -xzf "$tarball" -C "$work"
}

# --- making two builds of the same sources produce the same bytes ----------------------
# What differed between two builds, traced byte by byte, was: the timestamps in the wheel's zip
# entries and in the SBOM maturin writes, the SBOM's random serial number, the GNU build-id of
# beancount's extension module, and the random eight characters of cibuildwheel's temporary
# directory, which the extension records in its RUNPATH. No compiled code differed.
#
# - A fixed SOURCE_DATE_EPOCH, not the commit time: the wheels depend on the pinned sources
#   and tools, not on which commit happens to ask for them.
# - Temporary directory names that count rather than roll dice, for cibuildwheel and
#   everything it starts (a sitecustomize on PYTHONPATH).
# - The patch to beancount's meson.build drops the build-id.
wheel_epoch=1767225600
determinism="$work/determinism"
rm -rf "$determinism"
mkdir -p "$determinism"
cat > "$determinism/sitecustomize.py" <<'PY'
import itertools
import tempfile

_counter = itertools.count(1)


class _Names:
    def __iter__(self):
        return self

    def __next__(self):
        return "%08x" % next(_counter)


tempfile._name_sequence = _Names()
PY

# --- build -------------------------------------------------------------------------
built=0
while read -r name version want; do
    case "$name" in ''|\#*) continue ;; esac
    fetch "$name" "$version" "$want"

    # Android is not Linux here: an extension module must link libpython explicitly, because
    # the Bionic linker resolves nothing at load time. Chaquopy's `python-3.13.pc` cannot
    # supply it — its `Libs:` line is `-L${libdir} $(BLDLIBRARY)`, an unexpanded Makefile
    # variable — so whether `-lpython3.13` reaches the link is left to what meson infers, and
    # that inference differed between a laptop and a GitHub runner on identical toolchains
    # (docs/milestones/m14-part12.md).
    #
    # It has to be said through meson, not the environment: cibuildwheel's own Android setup
    # rewrites LDFLAGS after CIBW_ENVIRONMENT is applied, so anything added there is silently
    # dropped. `c_link_args` survives, and shows up in meson's "User defined options" where it
    # can be checked rather than assumed.
    # Patches live in patches/, are applied after the hash check, and never fork upstream.
    for patch in "$here/patches/$name-$version-"*.patch; do
        [ -e "$patch" ] || continue
        say "applying $(basename "$patch")"
        # --forward so a re-run is not mistaken for a conflict; any other failure is fatal,
        # because a patch that no longer applies means the build would quietly produce
        # something different from what was reviewed.
        if ! ( cd "$work/$name-$version" && patch -p1 --forward --silent < "$patch" ); then
            echo "::error::$(basename "$patch") does not apply to $name $version" >&2
            exit 1
        fi
    done

    say "cross-building $name $version for $archs"
    ( cd "$work/$name-$version"
      SOURCE_DATE_EPOCH="$wheel_epoch" PYTHONPATH="$determinism" \
      CIBW_BUILD="cp313-*" "$cbw" --platform android \
          --archs "$archs" --output-dir "$out" )
    built=$((built + 1))
done < "$lock"

# --- check what came out -----------------------------------------------------------
# A wheel that builds but carries no extension module for the ABI is the failure mode
# worth catching here: it installs cleanly and then fails at import on the device.
say "verifying $built packages"
status=0
while read -r name version _; do
    case "$name" in ''|\#*) continue ;; esac
    for arch in ${archs//,/ }; do
        wheel="$out/${name}-${version}-cp313-cp313-android_"*"_${arch}.whl"
        # shellcheck disable=SC2086  # the glob is the point
        set -- $wheel
        if [ ! -f "$1" ]; then
            echo "MISSING  $name $arch"; status=1; continue
        fi
        sos="$(python3 -c "import sys,zipfile;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('.so')))" "$1")"
        if [ "$sos" -eq 0 ]; then
            echo "NO NATIVE CODE  $(basename "$1") — would fail at import on device"; status=1; continue
        fi
        printf 'ok  %-58s %s  %s .so\n' "$(basename "$1")" "$(sha256sum "$1" | cut -c1-16)" "$sos"
    done
done < "$lock"

# Nothing in a shipped wheel may name the machine that built it.
say "checking for build-machine paths"
if ! python3 "$here/tools/check-build-paths.py" "$out"/*.whl; then
    status=1
fi

# The lockfile pins these two by hash like everything else, but their bytes change on every
# rebuild, so leaving it alone would make the very next build fail with a tampering warning.
if [ "$status" -eq 0 ]; then
    say "refreshing the cross-built hashes in engine-local/requirements.lock"
    python3 "$here/tools/lock-python.py" --refresh-local --wheel-dir "$out" || status=1
fi

[ "$status" -eq 0 ] && say "wheels are in $out" || say "FAILED"
exit "$status"
