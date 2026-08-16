# Distribution, editions, and monetization

---

## 1. Edition feature matrix

| Capability | `play` | `expert` |
|---|:--:|:--:|
| Local embedded engine (Beancount + Fava) | ✅ | ✅ |
| Remote mode (hardened WebView + quick-add) | ✅ | ✅ |
| All Fava built-in reports, charts, BQL queries | ✅ | ✅ |
| Guided entry, raw editor, atomic writes, rollback | ✅ | ✅ |
| SAF import / export / auto-mirror | ✅ | ✅ |
| Backups (manual + scheduled), encryption | ✅ | ✅ |
| Documents, camera, PDF assembly, widgets | ✅ | ✅ |
| **Bundled Fava extensions** | ❌ | ✅ |
| **User Beancount plugins** | ❌ | ✅ |
| **User Fava extensions** | ❌ | ✅ |
| **Beangulp statement importers** | ❌ | ✅ |
| **Python script runner** | ❌ | ✅ |
| **beangrow / fava-portfolio-returns** | ❌ | ✅ on-demand |
| Safe mode / recovery mode | ✅ | ✅ (also disables all third-party code) |
| In-app update check + user-confirmed install | ❌ (Play handles it) | ✅ |
| Purchase keys in-app | Play Billing | PayPal / Payoneer link |
| "Enter key" (key bought elsewhere) | ✅ | ✅ |

The `play` build ships **no third-party Python at all** — not even bundled Fava
extensions. This is the strictest reading of the
[Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379)
and removes any argument about interpreted-code delivery outside review.

**File formats are identical between editions.** A project created in `play` opens
unchanged in `expert` and on desktop Beancount.

### 1.1 Build-time enforcement

Plugin, extension, importer, and script-runner code lives in an `expert`-only source set
and an `expert`-only Gradle module. A verification task (`:verifyPlayFlavorBoundaries`)
runs in CI and in the release pipeline, failing the `play` build if:

- any `play` variant classpath resolves the expert engine module,
- the `play` Python asset bundle contains anything outside the pinned wheel manifest,
- `play` sources reference the script runner or plugin loader APIs.

This is a **release gate**, not a lint warning.

---

## 2. Update channels

| Edition | Mechanism |
|---|---|
| `play` | Google Play releases only. No self-update, no `.dex`/`.so`/APK fetching, no runtime code download. Play Feature Delivery may later deliver optional on-device components if justified. |
| `expert` | Opt-in periodic check (user-configurable frequency, default off) against GitHub Releases over TLS → shows release notes → **explicit user action** downloads the APK → signature and SHA-256 verified against the release manifest → `PackageInstaller` invoked → user confirms in the system dialog. Never silent, never automatic. |

Downgrades are rejected unless the user explicitly enables recovery-downgrade. The
runtime is never replaced while executing: an update that changes the Python runtime
requires an engine stop and a ledger-compatibility snapshot first.

---

## 3. Monetization

### 3.1 Model

A **contribution model** modelled on My Expenses: cumulative one-time keys, no
subscription. The app is GPL-2.0-only, so anyone can rebuild it without the gate; the UI
states this openly and asks for support rather than pretending to enforce it.

| Tier | Price (USD) | Unlocks |
|---|---|---|
| — | Free | Add/edit/delete/duplicate all directives · journal · **account balances list** · raw editing of every file with highlighting, search, validation · first import of an existing project · **all export** · manual backup archive · safe mode · diagnostics |
| **T1 Reports** | $9.99 | Full Fava report surface: balance sheet, income statement, trial balance, holdings, commodities, account/payee/tag/link views, charts and graphs, **BQL queries** |
| **T2 Capture** | $14.99 | T1 + external documents, camera, PDF assembly, OCR (when it ships), home-screen widgets |
| **T3 Power** | $19.99 | T2 + auto-mirror to SAF, scheduled backups, remote mode, statement importers, scripts/plugins/Fava extensions |

Upgrade SKUs priced at the difference: T1→T2 $5.00, T2→T3 $5.00, T1→T3 $10.00.

**Data-out is never paywalled.** Export and manual backup creation are free in every
edition, forever. This is a product principle, not a pricing decision.

**Tier 3 across editions:** plugins, extensions, and importers are functional only in the
Expert build. The same key validates in both editions, so a Play T3 buyer installs the
GitHub build to use them. The Play store listing and the in-app purchase screen must both
state this before purchase.

### 3.2 Payment routes

| Edition | Route | Policy basis |
|---|---|---|
| `play` | Google Play Billing (one-time products + upgrade SKUs) | Default compliant path |
| `expert` | PayPal / Payoneer link on the project site | Off-Play, unconstrained |
| both | "Enter key" for keys purchased directly or via GitHub Sponsors | No purchase flow is initiated in-app, so no Play payment policy is engaged |

The Play build must **not** link out to PayPal by default. Google's post-Epic changes
(settlement March 2026; decoupled fees and external-link programs from 2026-06-30, with
transaction reporting and service fees from 2026-10-01) permit external links only for
enrolled developers and currently only for US/UK/EEA users. Enrolling is a later business
decision; the architecture keeps it a one-line configuration change behind the
`PurchaseRoute` interface.

### 3.3 Entitlement mechanics

A key is a compact signed token:

```
BCA1.<base64url(payload)>.<base64url(ed25519_sig)>

payload = {
  "v": 1,            // format version
  "kid": "k1",       // signing key id (enables rotation)
  "tier": 3,         // 1 | 2 | 3
  "email": "buyer@example.com",
  "order": "PP-8FJ2...",
  "iat": "2026-08-16"
}
```

- Verified **fully offline** against Ed25519 public keys compiled into the app. The app
  ships several trusted public keys so a compromised signing key is a rotation, not a
  catastrophe.
- **Soft binding to email, unlimited devices.** The buyer's email is shown in Settings
  (mild social deterrent against resharing). No device lock, no activation counter, no
  reissue support burden, no network call, no telemetry.
- Entitlement is cached in app-private storage; the raw key is retained so the user can
  re-display and re-enter it. Keys never appear in logs, backups, screenshots, widget
  previews, or diagnostics bundles.
- Play Billing purchases are converted to the same internal entitlement, so exactly one
  gating code path exists (`:core-entitlement`), with no Play dependency in it.

### 3.4 Key issuance runbook

**The signing private key never enters the application repository.** GitHub has no
per-branch visibility control — branch visibility follows repository visibility, so a
"hidden branch" does not exist and cannot be used to hide secrets.

Setup:

1. A **separate private repository** (`beancount-android-keys`) holds the issuing CLI and
   the buyer ledger. The application repo holds only public keys.
2. The Ed25519 private key is stored as a GitHub Actions **environment secret** in a
   protected environment requiring manual approval per run.
3. Issuance is a `workflow_dispatch` job: inputs `email`, `tier`, `order`; output is the
   signed key, mailed to the buyer and appended to an issuance log.
4. An offline fallback path exists: the same CLI runs locally from a hardware-backed
   backup of the key, so issuance survives GitHub being unavailable.
5. Rotation: increment `kid`, add the new public key to the app, keep verifying old
   `kid`s so previously issued keys keep working forever.

**Developer self-unlock:** you hold the private key and issue yourself a Tier 3 key like
any other buyer. There is no hidden backdoor, no debug-only bypass in release builds, and
therefore no way to lock yourself out. Debug builds may additionally default to Tier 3
for development convenience; release builds must not.

Residual risk accepted: the signing key lives on GitHub's infrastructure while the
workflow runs. Mitigated by the protected environment, per-run approval, `kid` rotation,
and the fact that a leaked key mints entitlements for a GPL app that anyone could rebuild
anyway — the loss is revenue, not user security.

---

## 4. Channels not supported in v1

**F-Droid** is excluded because the shared core would need to be free of Play Billing and
ML Kit, which would require a third flavor and a separate build pipeline. Revisit after
v1; the `PurchaseRoute` and `OcrEngine` interfaces already make it a flavor problem
rather than a rewrite.

**Amazon Appstore** and others: deferred, no architectural blocker.
