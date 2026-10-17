# Distribution, editions, and support

Policy positions below were verified against Google's published policies on 2026-08-16
and are re-verified before each release. Sources are cited inline.

---

## 1. Design principle: parity by default

The two editions ship the **same features, all of them free**. Differences
exist only where Google Play policy makes parity impossible, and each one is named and
justified below. There is no artificial crippling of the Play build.

## 2. Edition feature matrix

| Capability | `play` | `expert` |
|---|:--:|:--:|
| Local embedded engine (Beancount + Fava) | ✅ | ✅ |
| Remote mode (hardened WebView + quick-add) | ✅ | ✅ |
| All Fava built-in reports, charts, BQL queries | ✅ | ✅ |
| Guided entry, raw editor, atomic writes, rollback | ✅ | ✅ |
| SAF import / export / auto-mirror | ✅ | ✅ |
| Backups (manual + scheduled), encryption | ✅ | ✅ |
| Documents, camera, PDF assembly, widgets | ✅ | ✅ |
| **Bundled Fava extensions** | ✅ | ✅ |
| **User Fava extensions** | ✅ pure-Python only | ✅ |
| **User Beancount plugins** | ✅ pure-Python only | ✅ |
| **Beangulp statement importers** | ✅ pure-Python only | ✅ |
| **Python script runner** | ✅ restricted context | ✅ full |
| Java/Android API bridge exposed to user Python | ❌ withheld | ✅ available |
| Installing packages that contain native code (`.so`) | ❌ | ✅ |
| beangrow / fava-portfolio-returns | ⏳ deferred (Play Feature Delivery) | ✅ on-demand |
| Safe mode / recovery mode | ✅ | ✅ |
| Anything sold, any trial or key | ❌ none | ❌ none |
| Mentions donations (Ko-fi), once, after a week | ❌ by policy (4.2) | ✅ |
| Describes the other edition | ✅ | — |

**File formats are identical.** A project created in `play` opens unchanged in `expert`
and on desktop Beancount.

### 2.1 The three real differences, and why

| # | Difference | Policy basis |
|---|---|---|
| D1 | Play user-plugins must be **pure Python**; no wheels containing `.so` | [DNA policy](https://support.google.com/googleplay/android-developer/answer/9888379): "an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play." Native code for Play must arrive via the APK/AAB or Play Feature Delivery. |
| D2 | Play user-plugins run with the **Java/Android bridge withheld** | The DNA exception covers interpreters providing *indirect* access to Android APIs. Chaquopy's bridge is direct access, so the Play build withholds it from user code to stay inside the exception. |
| D3 | No self-update or installer code in `play` | Play apps update through Play. Neither edition has any now, but the gate stays: it is what stops one being reintroduced. |

Everything else, including Fava extensions, is identical across editions. The one other
difference, the donation notice, is about money rather than features (4.2).

### 2.2 Why loading user plugins is permitted on Play

The DNA prohibition targets *downloading* executable code. This app **never downloads
code**: the user selects Python files already on their own device, and the app imports
them into an embedded interpreter. The policy further states that "apps... with
interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time... must not allow
potential violations of Google Play policies", a compliance obligation on the loaded
code's behaviour, not a prohibition on loading. General-purpose Python interpreters
(Pydroid 3, QPython) ship on Play on this basis.

Obligations we therefore accept in the `play` build:

- No fetching of code from any network source, by us or by a plugin. Plugin network
  access is off by default and requires explicit per-project consent.
- No mechanism to install wheels containing native code.
- The Java/Android bridge is not importable from user plugin code.
- Safe mode and a plugin-free relaunch are always reachable.

### 2.3 Build-time enforcement

`:verifyPlayFlavorBoundaries` is a **release gate**, not a lint warning. The `play` build
fails if:

- any code path can install a wheel containing native binaries,
- the Java bridge is reachable from the user-plugin import context,
- self-update or APK-installer code is on the `play` classpath,
- any billing or payment-SDK dependency is on either classpath, or a donation page is named in
  anything compiled into `play`,
- the Python asset bundle deviates from the pinned wheel manifest.

---

## 3. Update channels

| Edition | Mechanism |
|---|---|
| `play` | Google Play releases only. No self-update, no APK/`.so`/`dex` fetching. Play Feature Delivery may later deliver optional on-device components (the beangrow stack) from Play, and so compliant. |
| `expert` | None in the app. It is installed from a file, so it updates like any sideloaded app: the user downloads the new APK themselves and opens it, and Android refuses one not signed with the key that signed the installed build. The About section says which build is installed and where a newer one comes from; both editions have it. |

Android handles both halves of that: it refuses a package signed with a different key, and it
refuses one whose version code is lower than the installed build's. Neither is something this
app implements or can weaken.

---

## 4. Money: everything free, donations welcome

Decided 2026-09-14, replacing the tiers, the 30-day trial, Play Billing and the offline
contribution keys.

### 4.1 The model

- **Every feature is free in both editions.** No trial, no tiers, no keys, no purchase flow, no
  licence check, nothing that changes on a date.
- **Donations are voluntary, through Ko-fi**, and unlock nothing. They are a way to support
  the project, not a price.
- **The app mentions it once**, after a week of use, in the Expert edition: one dismissible
  notice on Home. Nothing else asks. A banner in About is planned.
- **The Play edition names no donation page** (4.2).

### 4.2 Why the Play edition has no Ko-fi link

Google Play's [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738),
read 2026-09-14:

- "apps may not lead users to a payment method other than Google Play's billing system. This
  prohibition includes, but is not limited to, leading users to other payment methods via: An
  app's listing in Google Play; ... In-app webviews, buttons, links, messaging, advertisements,
  or other calls to action".
- The payments that must *not* use Play billing include "peer-to-peer payments, online auctions,
  and tax exempt donations".

A Ko-fi tip to an individual developer is not a tax-exempt donation, and it is not an in-app
purchase either -- it buys nothing. The policy does not say in words how such a tip is treated,
so the safe reading is the one that cannot be wrong: **the Play build links to no payment page
of any kind**, and `:verifyPlayFlavorBoundaries` fails if a Ko-fi, Buy Me a Coffee, Patreon,
Liberapay, PayPal or GitHub Sponsors address appears in anything compiled into it.

If support through Play is wanted later, the compliant route is a "support the project"
one-time product through Google Play's own billing. That is a decision, not a default.

### 4.3 What was removed

`:core-entitlement` (trial clock, Ed25519 key verification, feature gating), the Contribute
screen, the trial welcome dialog and More's trial card, every locked-feature screen, the
widgets' "part of the Workflow tier" state, Play Billing and its products, the `eddsa` and
`androidx.fragment` pins that existed for them, and the key-secrecy and entitlement tests. On
first launch after upgrading, the app deletes the stored key (which carried the email it was
issued to) and the old welcome marker.

### 4.4 Telling Play users about the Expert edition

The Play build describes the Expert edition at the top of More: what it adds (native Python
packages, on-demand package fetching, the Java bridge) and a link to
[editions.md](editions.md) in the public repository. It does not download or install anything
-- the [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379)
forbids an app updating itself or fetching executable code outside Play, and a link to a
document is neither -- and it is not a payment page, since both editions are free.

---

## 5. Channels not supported in v1

**F-Droid**: the Expert edition has a recipe in a merge request against `fdroiddata`, and its
pipeline passed; see [fdroid.md](fdroid.md). No billing library or proprietary component
remains in either edition.

**Amazon Appstore** and others: deferred, no architectural blocker.
