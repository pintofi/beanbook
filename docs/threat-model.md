# Threat model

Scope: the Android application, its embedded Python engine, its storage, and its
network interactions. Revised as the app changes.

---

## 1. Assets

| Asset | Why it matters |
|---|---|
| A1 Ledger files | The user's complete financial history. Loss or corruption is the worst outcome in this product. |
| A2 Documents | Receipts, invoices, statements — often contain names, addresses, card fragments |
| A3 Remote credentials | Access to a self-hosted Fava, potentially the whole ledger |
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

### 3.1 Ledger and file parsing

Beancount's own parser handles malformed input by producing errors, not crashes; we
surface those errors. Parsing runs off the main thread with a size guard and a cancellable
timeout. `include` targets that escape the project root are reported, never followed
silently. Files are read as bytes and decoded explicitly; encoding failures are errors,
not silent replacements.

### 3.2 Expert plugin execution — accepted residual risk

User Python runs **in the engine process (`:engine`), with the app's permissions**: the same
user id, so it can read and destroy the ledger, read anything the app can read, and use the
network. A separate process protects the UI from a crash, not the data from the code.
Android offers no mechanism that both isolates it and keeps Fava's integration.

We therefore do not claim safety. We provide:

- A warning above the list of plugins: this is executable code you are trusting.
- Per-project enable/disable, off by default.
- Provenance display: source path, import date, SHA-256, and a change alert when the file
  changes.
- Execution logs with timestamps, arguments, working directory, exit status, and captured
  output — with secret redaction.
- **Safe mode:** no plugin or fetched package is loaded at all, and your choices are kept.
- **Recovery:** a plugin that was loading when the app last died is turned off before the
  next start, and you are told.
- Cancellation for scripts, with a deadline if one is set; a script that hangs can be
  stopped.
- Native imports that cannot work fail at import time with an explanation.

The `play` edition removes this surface entirely.

### 3.3 Loopback service (T6)

- Bound to `127.0.0.1` only, ephemeral port, never a LAN interface. (Sharing on Wi-Fi,
  §3.3a, is a separate server the user turns on.)
- 256-bit per-process token required on every request; missing/incorrect → `403`.
- `Host` must name the bound address (DNS rebinding). The token is an `HttpOnly`,
  `SameSite=Strict` cookie, so another site cannot make the browser send it.
- Server stopped when not needed; never kept alive for widgets.
- No `INTERNET`-independent assumption: local mode functions with the network off, which
  is also the strongest privacy demonstration we can offer.

### 3.3a Sharing on Wi-Fi

The one exception to "never a LAN interface", added on 2026-09-27.
Off until the user turns it on, in both editions, and a separate server from the loopback
one, which is unchanged.

- Binds the device's own private IPv4 address on Wi-Fi or Ethernet, never `0.0.0.0`, and
  refuses to start on an address that is not private.
- Answers only clients on private, link-local or loopback addresses; anything else is `403`
  before the sign-in page.
- `Host` must name the bound address (DNS rebinding).
- A username and password the user sets. Only a salted PBKDF2-SHA256 hash (150,000
  rounds) is stored, in the app's private state folder, excluded from Android backup, never
  in a project, backup or mirror; the password never reaches the engine process.
- A signed-in browser holds a random 256-bit session id in an `HttpOnly`,
  `SameSite=Strict` cookie; sessions expire after 12 idle hours, are capped at 32, and all
  end when sharing stops. `SameSite=Strict` is the CSRF defence.
- Read-only: a signed-in browser may use `GET`, `HEAD` and `OPTIONS` only. Fava's saves are
  `PUT`, `POST` and `DELETE`, and are refused with `405`, so the shared pages cannot change
  the ledger.
- Five wrong attempts from one address lock that address out for a minute, doubling each
  time up to a day.
- A foreground service with a notification naming the address and offering Stop runs for
  exactly as long as sharing is on; the engine is kept running in the background only then.

**Residual risk, accepted and said on screen:** the connection is plain HTTP. Anyone who can
observe the network can read the pages and replay the session cookie while it is valid (to
read; the shared pages cannot write). The
screen says to use it only on a trusted network and to turn it off after. HTTPS would need a
certificate the other device trusts, which a phone on a home network cannot obtain without
the user installing one; that is not built.

### 3.4 WebView (T5)

`allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`,
`allowUniversalAccessFromFileURLs` all `false`; `mixedContentMode = NEVER_ALLOW`; no
JavaScript bridge into the Fava WebView; `setWebContentsDebuggingEnabled` only in debug
builds; navigation restricted to an origin allowlist (loopback + configured remote host),
with external links opening in the browser only after a confirmation, and only if they are
`http` or `https`. The WebView has no download handler, so a download started from a
report does nothing.

### 3.5 Remote mode (T5, T7)

`https` and `http` are both accepted. Certificate errors are **fatal** — there is no "trust
anyway" affordance in any build, and no `trustAll` code may exist in the repository
(enforced by a secret/pattern scan in CI).

**Cleartext (revised).** `http` was once refused outright. The refusal was
well-intentioned and, for a self-hoster, frequently unworkable: Fava on a home LAN or behind
a VPN has no name a public CA will certify, and `192.168.1.5` cannot be given a certificate
at all without running a private CA and installing it on the device. In practice the rule did
not prevent cleartext, it pushed people towards worse arrangements, and the app has no way to
tell a home LAN from a hotel network anyway.

So cleartext is now the user's decision, made explicitly, with these properties:

- It is never inferred. A bare hostname is still assumed to be `https`; typing `http://` is
  the only way to get it.
- It is never a fallback. Nothing retries an `https` address over `http` after a failure, and
  the transport contains no code that can rewrite a scheme (asserted in
  `CertificateIsFatalTest` and scanned in CI).
- It is never silent. The remote screen shows what an `http` address means, in terms of the
  user's ledger, while they are still typing — and says so more sharply when a password has
  been entered, because that would go in the clear on every request.
- The WebView origin allowlist still matches origins **exactly**, so an `https` allowlist
  cannot be satisfied by the `http` version of the same host.

The residual risk is real and accepted: on a hostile network an `http` remote is readable and
modifiable, including the entries the app writes. We do not claim otherwise. Credentials and
tokens are stored via Keystore. Write operations carry an
idempotency key so a retry after a timeout cannot duplicate an entry. The server's Fava and
Beancount versions are shown when it reports them.

### 3.6 Archives, PDFs, images (T3, T4)

See [storage-and-backup.md](storage-and-backup.md) §4 for archive validation. Documents
are rendered by the platform's own PDF and image decoders, shared only via `FileProvider`
with per-URI grants, and never handed to a component with elevated privileges.

### 3.7 Supply chain (T8)

Exact version pins everywhere, with hashes for Python wheels (Maven checksums are not verified yet; see the issue tracker); a generated SBOM and licence inventory per release; wheels we cross-build ourselves are built from verified upstream sdists with recorded hashes and reproducible build scripts; CI scans for TLS bypasses, key material and unhashed pins, and Dependabot reports new versions; the release signing key is stored as encrypted repository secrets and used only by the tag-triggered release workflow, whose actions are pinned to commit hashes; force-pushes to and deletion of the main branch are blocked. Anyone who can change a workflow file could use the key, which is why the workflow, its actions and the branch are the things to protect.

### 3.8 Device loss (T9)

All project data lives in app-private storage, protected by the platform's
full-disk/file-based encryption and the device lock. The app has no lock of its own and does
not block screenshots. Widgets can hide amounts, and always do on the lock screen. Backups
may be passphrase-encrypted before leaving the device.

### 3.9 Our own bugs (T10)

Treated as a first-class threat, because it is the most probable one: journaled atomic
writes, pre-write snapshots, staged restores, byte-diff round-trip tests on a golden
corpus, differential testing against upstream `bean-check`/`bean-query`, and the rule that
no success is reported before a durable, re-parsed write.

---

## 4. Privacy posture

- Local mode makes **zero** network requests. Verifiable by the user with airplane mode.
- No analytics, telemetry, crash reporting, ad SDKs, or attribution SDKs in any edition.
- Nothing is sold, so there is no licence check of any kind.
- Neither edition checks for updates or installs packages.
- The diagnostics report is built only when the user asks, redacts credentials, tokens and
  cookies, and is shown on screen before it can be shared. It is a log of what the app did,
  so it can still contain file names and messages from the engine; read it before sending.
- Permissions requested: `CAMERA` (at point of use), `INTERNET`, `POST_NOTIFICATIONS`
  (the notice while work or sharing continues), `FOREGROUND_SERVICE` (with the data-sync and
  special-use types), `WAKE_LOCK` (held with a timeout while a save or install finishes),
  `ACCESS_NETWORK_STATE` (to find the Wi-Fi address when sharing) and `RECEIVE_BOOT_COMPLETED`
  (added by the scheduler library for backups). No storage permissions — SAF only. No
  location, contacts, or account access.

**About `INTERNET`, stated plainly.** Android refuses socket creation without it —
including a bind to `127.0.0.1` — so the app must declare `INTERNET` even in local-only
mode, where it makes no outbound connections at all
(measured). The permission list therefore
overstates what the app does, and it is our job to close that gap rather than let users
infer it: the store listing, Play Data Safety declaration, and in-app privacy screen must
say that local mode contacts nothing, that the permission exists solely to run the
on-device Fava server on loopback, and that the claim is verifiable by using the app in
airplane mode. Cleartext is permitted by the network security config, because the embedded
Fava is on `http://127.0.0.1` and a self-hoster may choose an `http://` address; section 3.5
says how that is kept deliberate.

---

## 5. Accepted residual risks

| # | Risk | Why accepted |
|---|---|---|
| R1 | Expert plugins are unsandboxed | No Android mechanism achieves isolation with in-process Fava; disclosed, controllable, and absent from `play` |
| R4 | Loopback port is visible to other apps on the device | Mitigated by the per-process token and the `Host` check; documented |
| R5 | A forgotten backup passphrase is unrecoverable | Correct cryptographic behaviour; warned about explicitly, and encryption is off by default |
| R6 | ML Kit (future OCR) is proprietary and GPL-incompatible without a linking exception | Tracked as a licensing task before OCR ships; no OCR in v1 |
