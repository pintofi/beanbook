Files that replace a fetched package's own, after it is installed.

Each directory is named <package>-<version>, and the overlay is applied only when the
installed version matches exactly. A newer release is left alone and reported, rather than
silently overwritten with a file written against an older one — an overlay that applies to
"whatever is there" is a way to break a package quietly.

beangrow-1.0.1/beangrow/returns.py
    The patch in patches/beangrow-1.0.1-no-scipy.patch, as a whole file. beangrow imports
    scipy for exactly one call, a one-dimensional root find; scipy has no CPython 3.13 build
    for Android. The replacement agrees with scipy.optimize.fsolve to 3.2e-13.

fava_portfolio_returns-2.7.0/fava_portfolio_returns/_vendor/beangrow/returns.py
    The same change, for the copy of beangrow that fava-portfolio-returns vendors. The two
    have diverged — the vendored call site clamps the result at -100% — so this is a separate
    file rather than the same one twice, and patches/ carries a separate diff to match.
