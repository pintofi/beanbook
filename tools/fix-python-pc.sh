#!/usr/bin/env bash
# Makes Chaquopy's target CPython describable by pkg-config, before a wheel is built.
#
# `python-3.13.pc` in that prefix says
#
#     Libs: -L${libdir} $(BLDLIBRARY)
#
# where `$(BLDLIBRARY)` is an unexpanded Makefile variable. pkg-config drops it, so nothing
# asking pkg-config how to link Python gets `-lpython3.13`, and on Android that is not
# optional: the Bionic linker resolves nothing at load time, so an extension module that does
# not link libpython fails at link with every Python symbol undefined.
#
# The same directory ships `python-3.13-embed.pc`, which is the identical file with a correct
# `Libs:` line. This copies it over. Run by cibuildwheel via CIBW_BEFORE_BUILD, once per ABI,
# inside the build environment where the prefix exists.
set -euo pipefail

# The prefix is not passed in, but cibuildwheel puts it in LDFLAGS as the only -L.
prefix=""
for flag in ${LDFLAGS:-}; do
    case "$flag" in
        -L*/python/prefix/lib) prefix="${flag#-L}" ;;
    esac
done

if [ -z "$prefix" ]; then
    echo "::error::no python prefix in LDFLAGS: ${LDFLAGS:-<unset>}" >&2
    exit 1
fi

pkgconfig="$prefix/pkgconfig"
for version in 3.13; do
    embed="$pkgconfig/python-$version-embed.pc"
    plain="$pkgconfig/python-$version.pc"
    if [ ! -f "$embed" ]; then
        echo "::error::$embed is missing; the prefix layout has changed" >&2
        exit 1
    fi
    cp "$embed" "$plain"
    echo "python-$version.pc now links libpython: $(grep '^Libs:' "$plain")"
done
