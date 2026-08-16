# Architecture

Companion to [SPEC.md](../SPEC.md). Verified against upstream on **2026-08-16**.

---

## 1. Verified upstream facts

| Component | Pinned version | License | Android status |
|---|---|---|---|
| Chaquopy | 17.0.0 (2025-12-01) | MIT | Python 3.10–3.14, minSdk 24, AGP 7.3–9.2 |
| CPython | 3.13 (via Chaquopy) | PSF | Runtime supplied by Chaquopy |
| Beancount | 3.2.3 | **GPL-2.0-only** | ❌ No Android wheel — **must cross-build** (meson-python, bison ≥3.8, flex ≥2.6.4, C++) |
| Fava | 1.30.15 | MIT | ✅ pure-Python `any` wheel |
| Beanquery | 0.2.0 | GPL-2.0 | ✅ `any` wheel (Fava requires `<0.3`) |
| Beangulp | 0.2.0 | GPL-2.0 | ✅ `any` wheel |
| watchfiles | 1.2.0 | MIT | ❌ Rust/PyO3 — **must cross-build** or shim |
| cheroot | 11.1.2 | BSD | ✅ `any` wheel |
| simplejson | 4.1.1 | MIT/AFL-2.1 | ✅ `any` wheel (pure fallback) |
| protobuf | 7.35.1 | BSD-3 | ✅ `any` wheel |
| numpy / pandas / scipy / matplotlib | Chaquopy repo | BSD-family | ✅ prebuilt Android wheels |
| beangrow | latest | GPL-2.0 | Expert-only, on-demand (spike 9) |

Sources: [Chaquopy versions](https://chaquo.com/chaquopy/doc/current/versions.html),
[Chaquopy wheel index](https://chaquo.com/pypi-13.1/), PyPI JSON API,
[Beancount COPYING](https://github.com/beancount/beancount/blob/master/COPYING),
[Fava pyproject](https://github.com/beancount/fava/blob/main/pyproject.toml),
[Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878).

### 1.1 Licensing conclusion

Beancount's `COPYING` states GPL **v2 only**, explicitly *not* "v2 or any later version".
Beanquery and Beangulp are also GPL-2.0. The engine runs in-process with the app, so the
distributed APK is a combined work: **the application is licensed GPL-2.0-only.** GPLv3
is incompatible and must not be introduced. All third-party Android dependencies must be
GPL-2.0-compatible (Apache-2.0 is *not* GPL-2.0-compatible — see §7.1 for the
consequence, which is real and constrains dependency choice).

### 1.2 The two hard build problems

Everything else installs as an ordinary pure-Python wheel. The entire native risk of this
project is:

1. **Beancount's C++ parser** — meson-python build backend, bison/flex-generated sources,
   cross-compiled for `aarch64-linux-android21+` and `x86_64-linux-android21+` via
   Chaquopy's `build-wheel` tool. This is Spike 2 and it is architecture-defining.
2. **watchfiles' Rust extension** — cross-compiled with maturin against the NDK.
   *Mitigation already identified:* `fava.core.watcher` provides a pure-Python polling
   `Watcher`, selected by `FavaLedger(poll_watcher=True)`. `watchfiles` is only imported
   at module scope, so a ~30-line import shim providing `Change`, `DefaultFilter`, and
   `watch` removes the Rust dependency entirely if the cross-build proves unmaintainable.
   Polling is also the *better* choice on Android, where inotify on app-private storage is
   cheap and battery matters.

---

## 2. Component map

```
┌──────────────────────────── Android process ─────────────────────────────┐
│                                                                          │
│  ┌────────────── Kotlin / Compose (Material 3) ──────────────┐           │
│  │ Onboarding · Project picker · Guided entry · Raw editor   │           │
│  │ Documents · Backup/Restore · Settings · Safe mode         │           │
│  │ Widgets (Glance) · Diagnostics · Entitlement UI           │           │
│  └───────────────┬───────────────────────────┬───────────────┘           │
│                  │                           │                            │
│        ┌─────────▼─────────┐       ┌─────────▼──────────┐                │
│        │  LedgerRepository │       │  Hardened WebView  │                │
│        │  (domain layer)   │       │  origin allowlist  │                │
│        └─────────┬─────────┘       └─────────┬──────────┘                │
│                  │                           │ http://127.0.0.1:<port>   │
│        ┌─────────▼───────────────────────────▼──────────┐                │
│        │            EngineClient (interface)            │                │
│        │   LocalEngineClient  │  RemoteEngineClient     │                │
│        └─────────┬────────────┴───────────┬─────────────┘                │
│                  │ Chaquopy JNI            │ OkHttp + strict TLS          │
│  ┌───────────────▼──────────────┐          │                             │
│  │  CPython 3.13 (Chaquopy)     │          │                             │
│  │  Fava (cheroot, loopback)    │          │                             │
│  │  Beancount · Beanquery       │          │                             │
│  │  [expert] user plugins       │          │                             │
│  └───────────────┬──────────────┘          │                             │
└──────────────────┼──────────────────────────┼─────────────────────────────┘
                   │ POSIX paths              │ HTTPS
      ┌────────────▼─────────────┐   ┌────────▼──────────┐
      │ App-private workspace    │   │ Self-hosted Fava  │
      │ (canonical ledger)       │   └───────────────────┘
      └────────────┬─────────────┘
                   │ explicit import/export/mirror
            ┌──────▼───────┐
            │ SAF (content://) user folder │
            └──────────────┘
```

---

## 3. Trust boundaries

| # | Boundary | Direction | Control |
|---|---|---|---|
| B1 | User ↔ app | in | Android permission model; per-use rationale |
| B2 | SAF folder ↔ private workspace | in | Path traversal, symlink, zip-bomb, case-collision validation on every import |
| B3 | Kotlin ↔ CPython | both | Typed `EngineClient` calls only; no `eval` of UI-supplied strings |
| B4 | WebView ↔ loopback Fava | both | Origin allowlist, per-process bearer token, no file access, no JS bridge |
| B5 | App ↔ remote Fava | both | HTTPS only, strict cert validation, no bypass path in any build |
| B6 | Engine ↔ user plugin code (expert) | in | **Not a security boundary** — same process, same privileges. Documented as such. |
| B7 | Update artifact ↔ installer (expert) | in | Signature + checksum verification before `PackageInstaller`; never silent |

Detailed adversary analysis: [threat-model.md](threat-model.md).

---

## 4. The loopback Fava service

- Binds **only** to `127.0.0.1` on an ephemeral port obtained by binding port `0`. Never
  `0.0.0.0`, never a LAN interface.
- A 256-bit per-process token is generated at start and required on every request via a
  cookie set from the initial load URL; requests lacking it get `403`. This defends
  against other apps on the device reaching the port (Android's loopback is not
  app-isolated below API 31 for all cases) and against localhost request forgery.
- `Referer`/`Origin` are checked on state-changing requests.
- The server is started lazily, stopped when the app is backgrounded beyond a grace
  period, and never runs solely to refresh widgets.
- WebView configuration: `javaScriptEnabled = true` (Fava requires it),
  `allowFileAccess = false`, `allowContentAccess = false`,
  `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`,
  `mixedContentMode = NEVER_ALLOW`, no `addJavascriptInterface` in the Fava WebView,
  `setWebContentsDebuggingEnabled` only in debug builds, and a
  `WebViewClient` that hard-blocks any navigation outside the allowlisted origin
  (loopback, or the configured remote host) — external links open in the browser after a
  confirmation.

---

## 5. Process and lifecycle model

- The engine lives in a **foreground-less bound `Service`** in the main process (Chaquopy
  initialises one interpreter per process; a second process would double memory and
  complicate wheel loading). Engine state is authoritative on disk, never in memory.
- **Process death is assumed, not exceptional.** Every mutation goes through a
  write-ahead journal in app-private storage:
  `snapshot → write temp → fsync → atomic rename → re-parse → commit journal entry`.
  On start, an uncommitted journal entry triggers recovery: either roll forward (the
  rename completed) or roll back to the snapshot.
- Unsaved editor buffers are persisted as drafts on every `onStop`, keyed by project and
  file, and restored on relaunch with a visible "restored draft" banner.
- Rotation and low-memory recreation are ViewModel/SavedStateHandle concerns and never
  touch the engine.
- Widgets read a compact Room/Proto cache written after a successful parse. They never
  start the engine.

---

## 6. Dependency direction

```
:app-play  :app-expert          ← flavors, thin; no business logic
        └──> :feature-*         ← Compose UI per feature area
                └──> :core-domain      ← entities, use cases, invariants (pure Kotlin)
                        ├──> :core-data      ← workspace, SAF, journal, backup
                        ├──> :core-engine    ← EngineClient interface
                        │       ├──> :engine-local   (Chaquopy, Python assets)
                        │       └──> :engine-remote  (OkHttp, TLS)
                        └──> :core-entitlement ← Ed25519 verification (no billing dep)
```

Rules:
- `:core-domain` depends on nothing Android-specific and holds every invariant.
- **No billing or payment SDK exists in any module.** `:core-entitlement` verifies signed
  keys offline and has exactly one route implementation (`EnterKeyOnly`) in both flavors.
- Plugin/script loading is **shared** by both flavors (parity), but the loader takes a
  `PluginPolicy` supplied per flavor:
  `PlayPluginPolicy` — pure-Python only, no native wheel installation, Java/Android bridge
  withheld from the user import context; `ExpertPluginPolicy` — unrestricted.
  A Gradle verification task fails the `play` build if native-wheel installation, the
  bridge, self-update, or any payment dependency is reachable. This is the build-time
  proof required by Play policy.

---

## 7. Consequences we accept

### 7.1 GPL-2.0 and Apache-2.0

Apache-2.0 is incompatible with GPL-2.0-only. This constrains dependency choice
(notably: `tesseract4android` is Apache-2.0 and would be a problem; ML Kit is proprietary).
Because monetization uses **external contribution keys with no billing SDK**, the Play
Billing library — previously the main proprietary dependency — is not present at all, and
the licensing question narrows to future OCR only. The resolution there, if needed, is an
explicit **GPL linking exception** granted by us as copyright holder of our own code,
leaving Beancount's terms untouched. Tracked as a licensing task before OCR ships; it does
not block v1, which has no OCR.

### 7.2 Expert plugins are not sandboxed

User Python runs in the app's process with the app's permissions. There is no Android
mechanism that makes this safe while keeping in-process Fava integration. We document the
residual risk plainly, offer per-project enable/disable, provenance and checksums,
execution logs, safe mode, and a plugin-free relaunch — and we never call it secure.

### 7.3 Size

Two ABIs × (CPython + numpy-class wheels for Expert) is large. Play AAB splits deliver
one ABI per device; the Expert APK is a universal or per-ABI download. Real numbers come
from Spike 10 and will be published, not estimated.
