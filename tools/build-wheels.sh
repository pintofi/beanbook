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
echo "cibuildwheel: $("$cbw" --version 2>&1 | tail -1)"

# cibuildwheel installs the NDK through the SDK's own sdkmanager, so it needs a real SDK
# to install it into. A GitHub runner image already has one; a laptop may not, and the
# failure without this check is an exit-127 from a generated shell script four levels down.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "::error::no Android SDK command-line tools at \${ANDROID_HOME}/cmdline-tools/latest" >&2
    echo "  ANDROID_HOME=${sdk:-(unset)}" >&2
    echo "  cibuildwheel installs the NDK with sdkmanager and cannot proceed without it." >&2
    exit 1
fi
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
echo "android sdk: $sdk"

# watchfiles is PyO3/maturin. A distribution rust package ships only the host standard
# library; the Android `std` comes from `rustup target add` and nothing else (s3).
if command -v rustup >/dev/null 2>&1; then
    rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
    echo "rust: $(rustc --version)"
else
    echo "warning: no rustup on PATH — the watchfiles build will fail" >&2
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

# --- build -------------------------------------------------------------------------
built=0
while read -r name version want; do
    case "$name" in ''|\#*) continue ;; esac
    fetch "$name" "$version" "$want"

    say "cross-building $name $version for $archs"
    ( cd "$work/$name-$version"
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

[ "$status" -eq 0 ] && say "wheels are in $out" || say "FAILED"
exit "$status"
