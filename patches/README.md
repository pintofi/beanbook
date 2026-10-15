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
says what the module needs instead of relying on the guess. It also passes `-Wl,--build-id=none`: the GNU build-id is a hash of the output that
differs on every build, and dropping it is what lets two builds produce the same bytes. It is the same dependency
upstream already uses a few lines below, for its test executable.

Not fixed in the environment instead, because neither route works: cibuildwheel's Android
setup rewrites `LDFLAGS` after `CIBW_ENVIRONMENT` is applied, and meson's `c_link_args`
*replaces* the link flags rather than adding to them — which drops
`-Wl,-z,max-page-size=16384`, and that one matters on 16 KB-page devices.

Worth sending upstream: it is arguably a latent bug that the extension links libpython by
luck.

## `beangrow-1.0.1-no-scipy.patch` and `fava_portfolio_returns-2.7.0-no-scipy.patch`

Both packages import SciPy exactly once, for exactly one call:

```python
from scipy.optimize import fsolve
...
irr, *_ = fsolve(net_present_value, x0=estimated_irr, args=(cash_flows, years), ...)
```

That is a one-dimensional root find: the discount rate at which net present value is zero.
SciPy has no build for CPython 3.13 on Android — 20 wheels on Chaquopy's index, none for
cp313 — and it is an enormous dependency for one call, so these patches replace it with a
bracketing solver in about forty lines.

Arguably the better tool for the job, not merely the available one: NPV changes sign exactly
once across the region that matters, so bracketing the root converges by construction, where
a derivative-based method can walk away from a poor initial guess.

**Measured against `fsolve`** on six cash-flow shapes — simple gain and loss, twelve monthly
contributions, a ten-year horizon, a near-zero return, and a 5× gain:

```
worst disagreement: 3.2e-13
```

Verified end to end with SciPy *actively blocked* by an import guard, not merely absent, so
the check cannot pass by accidentally finding it.

`fava-portfolio-returns` vendors its own copy of beangrow, and the two have diverged — its
call site clamps the result at −100% — so this is two patches rather than one applied twice.

Worth sending upstream in the same form: it removes a heavyweight dependency from a package
that only wanted a root finder.
