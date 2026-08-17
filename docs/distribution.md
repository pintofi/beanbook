# Distribution, editions, and monetization

Policy positions below were verified against Google's published policies on 2026-08-16
and are re-verified before each release. Sources are cited inline.

---

## 1. Design principle: parity by default

The two editions ship the **same features and the same contribution tiers**. Differences
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
| **User Fava extensions (Tier 3)** | ✅ pure-Python only | ✅ |
| **User Beancount plugins (Tier 3)** | ✅ pure-Python only | ✅ |
| **Beangulp statement importers (Tier 3)** | ✅ pure-Python only | ✅ |
| **Python script runner (Tier 3)** | ✅ restricted context | ✅ full |
| Java/Android API bridge exposed to user Python | ❌ withheld | ✅ available |
| Installing packages that contain native code (`.so`) | ❌ | ✅ |
| beangrow / fava-portfolio-returns | ⏳ deferred (Play Feature Delivery) | ✅ on-demand |
| Safe mode / recovery mode | ✅ | ✅ |
| In-app update check + user-confirmed install | ❌ Play handles updates | ✅ |
| Contribution purchase flow inside the app | ❌ none | ❌ none |
| "Enter key" | ✅ | ✅ |

**File formats are identical.** A project created in `play` opens unchanged in `expert`
and on desktop Beancount.

### 2.1 The three real differences, and why

| # | Difference | Policy basis |
|---|---|---|
| D1 | Play user-plugins must be **pure Python**; no wheels containing `.so` | [DNA policy](https://support.google.com/googleplay/android-developer/answer/9888379): "an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play." Native code for Play must arrive via the APK/AAB or Play Feature Delivery. |
| D2 | Play user-plugins run with the **Java/Android bridge withheld** | The DNA exception covers interpreters providing *indirect* access to Android APIs. Chaquopy's bridge is direct access, so the Play build withholds it from user code to stay inside the exception. |
| D3 | No self-update in `play` | Play apps update through Play. |

Everything else — including Fava extensions in Tier 3 — is identical across editions.

### 2.2 Why loading user plugins is permitted on Play

The DNA prohibition targets *downloading* executable code. This app **never downloads
code**: the user selects Python files already on their own device, and the app imports
them into an embedded interpreter. The policy further states that "apps... with
interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time... must not allow
potential violations of Google Play policies" — a compliance obligation on the loaded
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
- any purchase-flow or payment-SDK dependency is on the `play` classpath,
- the Python asset bundle deviates from the pinned wheel manifest.

---

## 3. Update channels

| Edition | Mechanism |
|---|---|
| `play` | Google Play releases only. No self-update, no APK/`.so`/`dex` fetching. Play Feature Delivery may later deliver optional on-device components (the beangrow stack) — from Play, therefore compliant. |
| `expert` | Opt-in check (default **off**, user-configurable frequency) against GitHub Releases over TLS → release notes → **explicit user action** downloads the APK → signature and SHA-256 verified against the release manifest → `PackageInstaller` → user confirms in the system dialog. Never silent, never automatic. |

Downgrades are rejected unless the user explicitly enables recovery-downgrade. The Python
runtime is never replaced while executing; an update that changes it requires an engine
stop and a ledger-compatibility snapshot first.

---

## 4. Monetization: external contributions + offline keys

### 4.1 The model

**There is no billing SDK in this application — in either edition.** No Play Billing, no
PayPal SDK, no Payoneer SDK, no payment code of any kind. The app can verify a key. That
is the entire payment-related surface.

- Keys are purchased on the project website (PayPal / Payoneer / GitHub Sponsors),
  entirely outside the app, in an ordinary browser.
- Both editions offer **"Enter key"** and nothing else.
- The `play` build contains **no purchase flow and no link-out** — it does not direct
  users to any payment method.
- Verification is offline Ed25519 signature checking. No licence server, no network call,
  no telemetry.

### 4.2 Why this is Play-compliant

The [Payments policy](https://support.google.com/googleplay/android-developer/answer/10281818)
requires Google Play's billing system for purchases of "app functionality or content"
**made inside the app**, and states that **"Apps without in-app purchases aren't
affected."** It further permits developers to communicate with users about alternative
purchase options *outside* the app. The Play build makes no purchase, initiates no
payment, and links to no payment method — so the billing requirement is not engaged. This
is the long-standing licence-key pattern used by My Expenses, Tasker, and Titanium Backup
on Play.

Anti-steering rules restrict *directing* users to alternative payment inside the app. We
do not. The website, GitHub, and email are outside-the-app channels the policy explicitly
allows.

**Residual risk, stated honestly:** Google could take the view that unlocking app
functionality via an externally-purchased key circumvents the billing requirement. It is
not the plain reading of the policy and there is a long precedent of such apps on Play,
but review outcomes are not guaranteed. **Mitigation:** the entitlement layer sits behind
a `PurchaseRoute` interface with exactly one implementation today (`EnterKeyOnly`). If
Google objects, adding a Play Billing implementation is a contained change in
`:app-play`, not an architectural one — and `:core-entitlement` never changes.

**Upside available later:** the US court order, EEA DMA, India and South Korea programs
allow alternative billing or external links for enrolled developers. Enrolling is a
business decision, not an architectural one.

### 4.3 Consequences of dropping Play Billing

- One fewer proprietary, **GPL-2.0-incompatible** dependency — the linking-exception
  question narrows to future OCR only.
- No SKU management, no upgrade-SKU matrix, no purchase-acknowledgement or refund
  edge cases, no Play Billing library version churn.
- One entitlement code path for both editions, testable without a store connection.
- Genuine Play/Expert parity in the contribution tiers.

### 4.4 Tiers (identical in both editions)

### 4.4.1 The 30-day trial

Every fresh install begins a **30-day trial with all three tiers unlocked** — the model My
Expenses uses. It needs no account, no network call and no card: the app records the
install date locally and counts down.

When it ends, **nothing is taken away except the paid features**:

- the ledger is untouched, and every file stays exactly where it was;
- adding, editing, deleting and duplicating entries keeps working;
- the journal, the account balances list and raw file editing keep working;
- **all manual import, export, backup and restore keep working** — data-in, data-out and
  recovery are never gated, during the trial or after it;
- a contribution key entered later applies immediately and behaves exactly as documented
  below.

The trial and any key are resolved to the **better** of the two, so an expiring trial can
never downgrade someone who has already contributed.

Clock changes: winding the device clock backwards does not extend the trial (the app
records the furthest time it has seen). Winding it forwards ends the trial early, which is
the user's own doing and not worth fighting. A reinstall starts a new trial — there is no
device fingerprinting to prevent that, and adding some would contradict the privacy
position in [threat-model.md](threat-model.md) §4.

| Tier | Price (USD) | Unlocks |
|---|---|---|
| — | Free | Add/edit/delete/duplicate all directives · journal · **account balances list** · raw editing of every file with highlighting, search, validation · multiple projects · **all manual import, export, backup and restore** · safe mode · diagnostics |
| **T1 Insight** | $9.99 | Full Fava report surface: balance sheet, income statement, trial balance, holdings, commodities, account/payee/tag/link views, charts and graphs, **BQL queries** |
| **T2 Workflow** | $14.99 | T1 + external documents, camera, PDF assembly, OCR (when it ships), home-screen widgets, **auto-mirror to a SAF folder, scheduled backups** |
| **T3 Power** | $19.99 | T2 + remote mode, statement importers (beangulp), scripts, **Beancount plugins and Fava extensions** |

The organising principle: **free is fully functional but manual; paid is automated and
analytical.** A free user can do every accounting operation, edit every file by hand, and
move data in and out as often as they like — it simply takes more taps.

Upgrades are handled by issuing a replacement key at the price difference — a manual
step on the website, with no in-app mechanism at all.

**Data-out is never paywalled, and neither is data-in or recovery.** Manual import, export,
backup creation and restore are free in every edition, forever, with no count limits.

Two reasons this is not merely generosity. First, the free tier includes the raw editor, so
a determined user can already copy ledger text out by hand — gating export would be a weak
lock that mostly inconveniences honest users while contradicting the plain-text promise.
Second, an earlier draft gated "imports after the first", which is unworkable: *first* per
project, per install, or per reinstall? It also placed a paywall at the exact moment a new
user is judging whether the app is worth anything.

Tier 3 behaves identically in both editions except for D1/D2 above: Play users can load
pure-Python plugins and extensions without the Android bridge; Expert users can load
anything.

### 4.5 Key format and verification

```
BCA1.<base64url(payload)>.<base64url(ed25519_sig)>

payload = { "v":1, "kid":"k1", "tier":3,
            "email":"buyer@example.com", "order":"PP-8FJ2…", "iat":"2026-08-16" }
```

- Verified offline against Ed25519 public keys compiled into the app; several trusted keys
  ship so a compromised signing key is a rotation (`kid`), not a catastrophe. Old `kid`s
  keep verifying forever, so issued keys never stop working.
- **Soft binding to email, unlimited devices.** The email is shown in Settings as a mild
  social deterrent. No device lock, no activation counter, no reissue support burden.
- Stored in app-private storage; the raw key is retained so the user can re-display it.
  Keys never appear in logs, backups, screenshots, widget previews, or diagnostics.

### 4.6 Issuance runbook

**The signing private key never enters this repository.** GitHub has no per-branch
visibility control — branch visibility follows repository visibility, so "a hidden branch"
does not exist and cannot be used to hide a secret.

1. A separate **private repository** (`beancount-android-keys`) holds the issuing CLI and
   the buyer ledger. This repo holds only public keys.
2. The Ed25519 private key is a GitHub Actions **environment secret** in a protected
   environment requiring manual approval per run.
3. Issuance is a `workflow_dispatch` job: inputs `email`, `tier`, `order`; output is the
   signed key, mailed to the buyer and appended to an issuance log.
4. Offline fallback: the same CLI runs locally from a hardware-backed key backup, so
   issuance survives GitHub being unavailable.
5. Rotation: bump `kid`, add the new public key to the app, keep the old ones trusted.

**Developer self-unlock:** you hold the private key and issue yourself a Tier 3 key like
any other buyer. No hidden backdoor exists, release builds contain no bypass, and you
cannot be locked out of your own app. Debug builds may default to Tier 3 for convenience;
release builds must not.

Residual risk accepted: the signing key is present in GitHub's infrastructure while the
workflow runs. Mitigated by the protected environment, per-run approval, and `kid`
rotation — and a leaked key mints entitlements for a GPL app anyone could rebuild anyway.
The loss would be revenue, never user security.

### 4.7 Honest framing in the UI

The Contribute screen states that the app is open source under GPL-2.0, that anyone can
build it without a key, and that a key is a contribution rather than a technical
restriction. No dark patterns, no nagging modals, no countdowns.

---

## 5. Channels not supported in v1

**F-Droid**: now much closer to viable, since Play Billing is gone. The remaining blocker
is future ML Kit OCR (proprietary) — kept behind the `OcrEngine` interface so F-Droid
becomes a flavor decision rather than a rewrite. Revisit after v1.

**Amazon Appstore** and others: deferred, no architectural blocker.
