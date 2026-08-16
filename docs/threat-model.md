# Threat model

Scope: the Android application, its embedded Python engine, its storage, and its
network interactions. Written before implementation; revised at each milestone.

---

## 1. Assets

| Asset | Why it matters |
|---|---|
| A1 Ledger files | The user's complete financial history. Loss or corruption is the worst outcome in this product. |
| A2 Documents | Receipts, invoices, statements — often contain names, addresses, card fragments |
| A3 Remote credentials | Access to a self-hosted Fava, potentially the whole ledger |
| A4 Entitlement keys | Revenue integrity (not user security) |
| A5 Backup archives | A1+A2 in one portable, exfiltratable file |
| A6 App integrity | A tampered APK could exfiltrate everything above |

---

## 2. Adversaries

| # | Adversary | Capability |
|---|---|---|
| T1 | Malicious ledger file | Arbitrary text the parser must survive; crafted `include` paths, huge inputs |
| T2 | Malicious plugin / script (expert) | **Arbitrary code with full app privileges** |
| T3 | Malicious archive | Zip bomb, traversal, symlink, case collision |
| T4 | Malicious PDF / image | Parser exploits in system components |
| T5 | Malicious or compromised remote server | Hostile HTML/JS in the WebView, hostile API responses |
| T6 | Other app on the device | Reaching the loopback port; reading world-readable files; intent injection |
| T7 | Network attacker | TLS interception, downgrade, DNS spoofing |
| T8 | Supply chain | Compromised PyPI/Maven dependency, compromised build machine, compromised signing key |
| T9 | Device thief | Physical access to an unlocked or locked device |
| T10 | The developer's own mistakes | Data-destroying bugs — statistically the most likely threat to A1 |

---

## 3. Attack surfaces and mitigations

### 3.1 Ledger and file parsing (T1)

Beancount's own parser handles malformed input by producing errors, not crashes; we
surface those errors. Parsing runs off the main thread with a size guard and a cancellable
timeout. `include` targets that escape the project root are reported, never followed
silently. Files are read as bytes and decoded explicitly; encoding failures are errors,
not silent replacements.

### 3.2 Expert plugin execution (T2) — accepted residual risk

User Python runs **in the app's process, with the app's permissions**. It can read and
destroy the ledger, read anything the app can read, and use the network if the app has
that permission. Android offers no mechanism that both isolates it and keeps in-process
Fava integration.

We therefore do not claim safety. We provide:

- An unambiguous warning at enable time: this is executable code you are trusting.
- Per-project enable/disable, off by default.
- Provenance display: source path, import date, SHA-256, and a change alert when the file
  changes.
- Execution logs with timestamps, arguments, working directory, exit status, and captured
  output — with secret redaction.
- **Safe mode:** relaunch with all third-party code disabled and the ledger read-only.
- **Recovery mode:** reachable even when a plugin crashes the engine at start.
- Timeouts and cancellation for scripts; a script that hangs cannot wedge the app.
- Clear messaging that in-process Python has no subprocess/shell semantics, and that
  unsupported native imports fail at import time with an explanation.

The `play` edition removes this surface entirely.

### 3.3 Loopback service (T6)

- Bound to `127.0.0.1` only, ephemeral port, never a LAN interface.
- 256-bit per-process token required on every request; missing/incorrect → `403`.
- `Origin`/`Referer` validated on state-changing requests (localhost request forgery).
- Server stopped when not needed; never kept alive for widgets.
- No `INTERNET`-independent assumption: local mode functions with the network off, which
  is also the strongest privacy demonstration we can offer.

### 3.4 WebView (T5)

`allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`,
`allowUniversalAccessFromFileURLs` all `false`; `mixedContentMode = NEVER_ALLOW`; no
JavaScript bridge into the Fava WebView; `setWebContentsDebuggingEnabled` only in debug
builds; navigation restricted to an origin allowlist (loopback + configured remote host),
with external links requiring confirmation and opening in the browser. Downloads inside
the WebView are intercepted and routed through the app's own document handling.

### 3.5 Remote mode (T5, T7)

HTTPS only. Certificate errors are **fatal** — there is no "trust anyway" affordance in
any build, and no `trustAll` code may exist in the repository (enforced by a secret/pattern
scan in CI). Cleartext traffic is disabled in the release manifest. Credentials and tokens
are stored via `EncryptedSharedPreferences`/Keystore. Write operations carry an
idempotency key so a retry after a timeout cannot duplicate an entry. Server version is
detected and mismatches produce an understandable message rather than silent breakage.

### 3.6 Archives, PDFs, images (T3, T4)

See [storage-and-backup.md](storage-and-backup.md) §4 for archive validation. Documents
are rendered in a sandboxed viewer, shared only via `FileProvider` with per-URI grants,
and never handed to a component with elevated privileges.

### 3.7 Supply chain (T8)

Exact version pins with lockfiles and checksums for Maven and Python wheels; a generated
SBOM and license inventory per release; wheels we cross-build ourselves are built from
verified upstream sdists with recorded hashes and reproducible build scripts; CI runs
dependency and secret scanning; release signing keys live outside the repository; the
entitlement signing key lives in a separate private repo's protected environment with
per-run approval and `kid`-based rotation.

### 3.8 Device loss (T9)

All project data lives in app-private storage, protected by the platform's
full-disk/file-based encryption and the device lock. Optional app-lock (biometric) gates
app entry. Widgets can redact amounts. Screenshots of financial surfaces can be blocked
via `FLAG_SECURE` as a user setting. Backups may be passphrase-encrypted before leaving
the device.

### 3.9 Our own bugs (T10)

Treated as a first-class threat, because it is the most probable one: journaled atomic
writes, pre-write snapshots, staged restores, byte-diff round-trip tests on a golden
corpus, differential testing against upstream `bean-check`/`bean-query`, and the rule that
no success is reported before a durable, re-parsed write.

---

## 4. Privacy posture

- Local mode makes **zero** network requests. Verifiable by the user with airplane mode.
- No analytics, telemetry, crash reporting, ad SDKs, or attribution SDKs in any edition.
- Entitlement verification is offline; no license server, no phone-home.
- Update checks (expert only) are opt-in, off by default, and disclosed.
- The diagnostics bundle is redacted by default: no ledger content, no amounts, no
  account names, no credentials, no keys — and it is shown to the user before sharing.
- Permissions requested: `CAMERA` (Tier 2, at point of use), `INTERNET` (remote mode and
  expert update checks; local mode never uses it), `POST_NOTIFICATIONS` (backup/script
  results). No storage permissions — SAF only. No location, contacts, or account access.

---

## 5. Accepted residual risks

| # | Risk | Why accepted |
|---|---|---|
| R1 | Expert plugins are unsandboxed | No Android mechanism achieves isolation with in-process Fava; disclosed, controllable, and absent from `play` |
| R2 | Entitlement keys can be shared, and the app can be rebuilt without the gate | The app is GPL-2.0; a contribution model is honest about this |
| R3 | Signing key transits GitHub Actions during issuance | Protected environment, manual approval, rotation via `kid`; loss is revenue, not user data |
| R4 | Loopback port is visible to other apps on the device | Mitigated by the per-process token and origin checks; documented |
| R5 | A forgotten backup passphrase is unrecoverable | Correct cryptographic behaviour; warned about explicitly, and encryption is off by default |
| R6 | ML Kit (future OCR) is proprietary and GPL-incompatible without a linking exception | Tracked as a licensing task before OCR ships; no OCR in v1 |
