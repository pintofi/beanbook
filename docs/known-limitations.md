# Known limitations

Required by [SPEC.md](../SPEC.md) §7 for a v1 release candidate, alongside the
[plugin compatibility matrix](plugin-compatibility.md).

This is what the app does not do, does not do well, or does differently from the desktop —
written for someone deciding whether to rely on it, not as a defect list. Anything unfinished
in an engineering sense lives in [pending-verification.md](pending-verification.md); anything
here is a limitation a user can actually meet.

Nothing on this page is a surprise to us. That is the point of writing it down.

---

## The ledger

**Your files are the database.** There is no import step and no internal copy: the app reads
and writes the same Beancount text you would edit on a desktop. That is the design, and it
means the app inherits Beancount's rules exactly — including that a file which does not parse
cannot be shown, only reported on with a file, line and column.

**Scale.** Built and measured against roughly 25,000 transactions in about 10 MB across
several files. Larger ledgers are not refused: a ledger of a little over 100,000 entries loads
correctly. What it costs is the first load after a change, which runs to tens of seconds at
that size. Opening the app again is quick, because the figures the native screens need are
cached and re-checked against the contents of your files. Fava reports and BQL queries still do
a full load every time, so those stay slow on a large ledger.

**Unknown directives are preserved, not understood.** Anything the app does not have an
editor for — including directives from plugins it has never heard of — survives editing,
export and backup byte-for-byte, but is shown as raw text rather than a form.

**No Git.** Not in v1. If your ledger is in a repository, the app edits the working tree and
leaves version control to you.

---

## Two modes, and what remote does not do

**Remote mode is view and quick-add only.** It is a hardened client for a Fava *you* host. It
keeps no copy of a remote ledger, works only while the server is reachable, and cannot edit
existing entries — the ledger stays on the server, which is the point of self-hosting it.
There is no offline cache and no two-way sync, deliberately: a sync engine that resolves
conflicts in a ledger is a way to lose money silently.

**`https` is strict.** A certificate that does not verify ends the connection, in every build,
with no way to continue past it. `http://` is accepted for a server on your own network or a
VPN, is never inferred or substituted, and is named as unencrypted on screen before you
connect.

**Local mode makes no network requests at all.** Fava is bound to `127.0.0.1` on an ephemeral
port behind a per-process token.

---

## Editions

Both editions have the same features, including Tier 3 plugins and Fava extensions. Three
differences exist, all forced by Play policy:

| | Play | Expert |
|---|---|---|
| Plugins with native code (`.so`) | refused | allowed |
| Java/Android bridge inside user plugin code | withheld | available |
| Self-update | none | signed, user-initiated |

**beangrow works, in the Expert edition, with its dependencies fetched on demand.** It needs
numpy, pandas, matplotlib and protobuf — a 37 MB download that occupies about 143 MB once unpacked, which does not belong in every install — so
the Expert edition fetches them the first time you open a returns report, once. Verified on a
device: the same internal rate of return SciPy computes on a desktop.

SciPy itself is not installed and is not needed. beangrow used it for one call, a root find,
and `patches/` replaces that with a solver agreeing to thirteen decimal places — SciPy has no
Android build and is an enormous dependency for a root finder.

**The Play edition cannot do this**, because fetching executable code from outside the store
is against its policy. beangrow is Expert-only for that reason, not because of size.

**The plugin restrictions are policy compliance, not a security boundary.** User Python runs
unsandboxed in both editions. Code you install can read anything the app can read, and in
Expert can call Android APIs. The app says this before it installs anything.

---

## Money and correctness

**Decimal only, everywhere.** No binary floating point touches an amount, at any layer.

**Nothing reports success before it is durable.** A write is locked, snapshotted, journalled,
written to a temporary file, fsynced, atomically renamed and re-parsed before you are told it
worked. A failure leaves the previous valid ledger recoverable.

**Undo is backed by snapshots on disk**, not by UI state, so it survives the app being killed.

---

## Plugins and Fava extensions

**Plugins you supply as files are not resolved for you.** You give the app a plugin's source
and it loads it; if that plugin imports something the app does not bundle, it fails at import
with the missing module named.

**The Expert edition can fetch what a plugin needs, on request** — see
[on-demand-packages.md](on-demand-packages.md). The Play edition cannot, because retrieving
executable code from outside the store is against its policy.

In practice that means most **Beancount plugins** work out of the box — they import Beancount
and the standard library — while every catalogued **Fava extension** needs something the app
does not bundle: `pandas`, `PyYAML` or `packaging`. The app names the missing module rather
than failing silently, so the reason is visible.

On the Expert edition those three are fetchable, and a fetched extension does load. Measured
on 2026-08-28: `fava_investor` installed through the app, `packaging` fetched on the device,
and Fava mounted the extension's route — HTTP 200, against 404 for the same extension without
the fetch. On the Play edition, which cannot fetch, none of the four loads.

So this is a limit of what can be *bundled*, and on Expert it is one the user can lift per
extension. It is not a bug in the extensions.

---

## Importing statements

**File types are recognised by extension, not by content.** `libmagic` is not built for
Android, so beangulp — which uses it optionally — falls back to matching the filename. Bank
downloads arrive as `.csv`, `.ofx`, `.qfx` or `.pdf`, so in practice this is the same answer;
a statement saved with the wrong extension, or none, will not be identified.

---

## Devices

**arm64-v8a and x86_64 only.** No 32-bit ABIs in v1. A 32-bit-only device cannot install it.

**minSdk 29 (Android 10).** Android 9 is not supported, and the reason is worth stating: the embedded Python runtime is killed by Android 9's seccomp filter before it finishes starting, so the app would install and then fail to open any ledger. Verified working on API 29, 30 and 36.

**Each per-ABI APK carries both ABIs' Python assets** — about 4 MB of waste per download,
because the ABI split does not split Chaquopy's assets. Fixing it means per-ABI builds or an
App Bundle, which is a release-pipeline change (D3).

**`babel` ships all 1084 locales**, about 33 MB uncompressed. Trimming it would break Fava for
anyone not using English, because `babel.Locale.parse` raises on a locale that is not present.
Deliberate (D1).

---

## Accessibility

Every screen carries TalkBack semantics and is checked at 200% font on phone and tablet
widths, with a negative control that fails if the checks stop biting. Two honest gaps:

- **Screen-reader traversal order is not measured.** TalkBack computes its own order,
  largely geometric, from relations that `uiautomator` does not expose. Reading order is
  checked by hand, not by a gate (N14).
- **No blind user has used this app.** The audit is a sighted developer driving TalkBack,
  which finds missing labels and finds nothing about whether the app is *pleasant* to use
  without sight (N5).

---

## Privacy

No telemetry, analytics, ads or crash uploads, in any edition, ever. Nothing is uploaded
anywhere. Diagnostics stay on the device, and secrets are kept out of logs, backups,
screenshots and widget previews by design and by test.

**Contribution keys are verified offline** with a public key compiled into the app. There is
no billing SDK and no purchase flow in either edition; the only route is entering a key.

---

## Not in v1

Offline cache for remote mode · two-way sync · OCR (camera and PDF ship first; OCR follows
behind an interface with its own consent) · plugin marketplace or vetted catalog · 32-bit ABIs
· Git integration · localization beyond English (the UI is built i18n-ready, and no
translations exist yet) · F-Droid publication, which would need a third flavor.

---

## Where the unfinished work is written down

[pending-verification.md](pending-verification.md) is the single list of everything unproven,
deliberately deferred, or not yet built. It is reviewed at every milestone, and nothing may be
called verified in a release note or a store listing unless it appears there with evidence or
does not appear there at all.
