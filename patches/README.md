# Patches to upstream Python packages

One file per patch, documented here, applied by `tools/build-wheels.sh` after the sdist is
extracted and its hash verified. Upstream is not forked: each patch is a `-p1` diff that
must rebase cleanly onto the next release, and a build fails loudly if one no longer applies
rather than quietly building something different.

## `beancount-3.2.3-link-libpython.patch`

`py.extension_module('_parser', …)` declares no dependencies, so whether the module links
`libpython3.13.so` is left to meson's own inference. On Linux that inference is right —
extensions there resolve Python's symbols from the interpreter at load time and must *not*
link libpython. On Android the Bionic linker resolves nothing at load time, so the library
has to be linked, and every extension Chaquopy itself ships does link it.

Meson's inference gave different answers on two machines with identical NDK, CPython, meson,
meson-python, cibuildwheel and pkgconf: a laptop linked libpython by absolute path, a GitHub
runner emitted no library and the build failed with every Python symbol undefined. The patch
says what the module needs instead of relying on the guess. It is the same dependency
upstream already uses a few lines below, for its test executable.

Not fixed in the environment instead, because neither route works: cibuildwheel's Android
setup rewrites `LDFLAGS` after `CIBW_ENVIRONMENT` is applied, and meson's `c_link_args`
*replaces* the link flags rather than adding to them — which drops
`-Wl,-z,max-page-size=16384`, and that one matters on 16 KB-page devices.

Worth sending upstream: it is arguably a latent bug that the extension links libpython by
luck. See [docs/milestones/m14-part12.md](../docs/milestones/m14-part12.md).
