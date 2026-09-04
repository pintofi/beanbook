# Additional permission for the Google Play Billing Library

Beanbook is licensed under the GNU General Public License, version 2 only, the text of which
is in [COPYING](COPYING). The following additional permission is granted by the copyright
holders listed in [AUTHORS](AUTHORS).

## The exception

> As a special exception, the copyright holders of Beanbook give you permission to combine
> Beanbook with the Google Play Billing Library and the Google Play services client libraries
> that it requires (together, the "Google Play Libraries"), and to convey the resulting work.
>
> You must comply with the GNU General Public License version 2 in all respects for all of
> the code used other than the Google Play Libraries.
>
> If you modify Beanbook, you may extend this exception to your version, but you are not
> obliged to do so. If you do not wish to do so, delete this exception statement from your
> version.

## What this covers, and what it does not

**It covers only code whose copyright is held by this project.** That is the app itself: the
Kotlin sources in `app/`, `core-domain`, `core-data`, `core-engine`, `core-entitlement` and
`engine-remote`. Those are the parts that are combined with the Google Play Libraries in the
Play edition.

**It does not cover, and could not cover, Beancount, beanquery, beangulp or Fava.** They are
GPL-2.0-only and their copyright belongs to their own authors, so nobody here is in a position
to grant permission for them. Upstream was asked about a broader exception in 2026 and
declined; with no contributor licence agreement in those projects, even a positive answer from
one maintainer would have covered only that maintainer's code.

**No exception is needed for them either.** Beancount, beanquery, Fava and the whole CPython
runtime execute in the separate `:engine` process and are reached over a binder rather than
linked (see [docs/architecture.md](docs/architecture.md) §7.1). Nothing in that process is
combined with the Google Play Libraries, and `:verifyPlayFlavorBoundaries` fails the build if
anything compiled into the app process so much as names the interpreter.

**The Expert edition contains no billing library at all.** The dependency is declared
`playImplementation`, and the boundary gate checks that it never reaches the expert classpath.
So this exception is only ever engaged by the Play build.

## Why it exists

Google Play's Payments policy requires Google Play's billing system for unlocking app
functionality on Play. The billing library is proprietary, under Google's Android Software
Development Kit licence, and GPL-2.0-only does not by itself permit distributing a combined
work with it. This exception supplies that permission for the project's own code, which is the
only code it can speak for.

## A caveat worth stating plainly

This is a licensing statement written by the project, not legal advice, and it has not been
reviewed by a lawyer. If Beanbook is ever distributed commercially at scale, that review is
worth getting. The scope above is deliberately narrow so that it says as little as possible
beyond what Play Billing actually requires.
