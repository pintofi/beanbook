# Plugin and script compatibility

Applies to **both editions**. The `play` edition accepts pure-Python plugins and
Fava extensions with the Java/Android bridge withheld; the `expert` edition accepts native
wheels and exposes the bridge. See [distribution.md](distribution.md) §2.1 for why.

---

## Licences, and one combination this project will not recommend quietly

Every entry records a licence, and one group needs saying out loud: the five `reds.*` plugins,
`fava_investor` and `beancount-interpolate` are **GPL-3.0** (checked against PyPI on
2026-09-14; the earlier text named only the `reds.*` five), and this app is **GPL-2.0-only**
because Beancount is, explicitly not "or later". Those two licences are incompatible.

What that does and does not mean:

- **It is not a problem with the plugin.** They are good plugins and widely used.
- **It is not something this project distributes.** No plugin source is in this repository or
  in the APK. The Expert edition downloads one from its own upstream when the user taps
  Install (§7), which is the user fetching it, not the project shipping it.
- **A user combining them privately is fine.** The GPL restricts distribution, not what
  someone runs on their own device.
- **What could not be done is redistribute the combination**: an APK with those plugins
  bundled, or a device image, could not be shipped by anyone.

So they stay in the catalogue, marked, with `licence_note` on each entry. Recommending them
without saying this would be leaving a user to discover it at the point where it costs them
something.

The same test applies to anything added later: a catalogue is a recommendation, and a
recommendation carries the licence question with it.


## 1. What "compatible" means

"Supports all plugins" is not a claim any Android app can honestly make. Our definition:

> A plugin/script is **compatible** when, on a supported Android ABI, it (1) installs or
> loads, (2) imports without missing native modules, (3) executes against the golden
> corpus, (4) renders its UI if it has one, and (5) round-trips ledger data without loss.
> Anything short of all five is **unsupported** or **partially supported**, and is
> recorded as such with the failing step.

Nothing is labelled compatible on inspection. Only test results promote an entry.

---

## 2. Categories

| Category | Definition | Loading mechanism |
|---|---|---|
| Beancount plugin | `plugin "name"` directive in the ledger | Python import path extended to `<project>/plugins/` |
| Fava extension | `2000-01-01 custom "fava-extension" "name"` | Same, resolved by Fava |
| Beangulp importer | Python importer config for statements | Invoked by the import workflow |
| User script | Arbitrary `.py` run on demand | Script runner |
| Python module | Support code imported by the above | Ordinary import |

---

## 3. Why plugins fail on Android (expected failure modes)

| Failure | Cause | Detectable before run? |
|---|---|---|
| `ModuleNotFoundError` for a native package | No Android wheel exists (e.g. Rust/C extensions not in the Chaquopy repo) | ✅ import scan |
| `subprocess` / shell invocation | Android apps cannot exec arbitrary binaries; there is no shell contract | ✅ static scan (warn) |
| Desktop path assumptions (`~/`, `/usr/share`) | No such paths | ✅ static scan (warn) |
| GUI/desktop deps (tkinter, Qt, matplotlib GUI backends) | Not available; only `Agg` works | ✅ import scan |
| Network access when the app has no connectivity | Offline device | ❌ runtime |
| Long-running/blocking work | Android background limits | ❌ runtime (timeout) |
| Writes outside the project | Sandbox | ✅ runtime (path guard) |

The loader performs an **import and static pre-flight scan** before enabling a plugin and
explains exactly which of these applies, rather than surfacing a raw traceback.

---

## 4. Compatibility results

**The results live in [`catalog/plugins.json`](../catalog/plugins.json)**, one entry per plugin,
with the step that failed where one did. This section used to be a five-row table marked
"untested" throughout, while the catalogue beside it already held real runs; it is no longer
duplicated here.

As of 2026-09-14, for the 35 plugins on awesome-beancount plus `beancount-interpolate`:

| Status | Count | Meaning |
|---|---:|---|
| supported | 15 | install, import, execute and round-trip passed |
| needs-config | 5 | works with a configuration string (the `reds.*` plugins) |
| partial | 14 | some steps passed; mostly Fava extensions whose UI is not exercised, or a dependency to fetch |
| unsupported | 2 | `autobean.truelayer` (an importer), `fava-classy-portfolio` |

Runs were on desktop CPython with Beancount 3.2.3 (2026-08-28; `beancount-interpolate` 2026-09-14, which also loaded `the sample ledger` with no errors on the API 36 emulator after installing from the catalogue), and the `supported` and
`needs-config` entries were loaded again on Android by `CatalogManifestTest` and
`CatalogPluginTest`. **beangrow** is `supported` on a device through the SciPy overlay
([on-demand-packages.md](on-demand-packages.md), m14-part28); the old "untested (Spike 9)" rows
for it and for `fava-portfolio-returns` were out of date.

---

## 5. Test harness

`tools/plugin-compat/` runs on a connected device or emulator per ABI:

1. Install the plugin into a scratch project's `plugins/`.
2. Pre-flight scan → record findings.
3. Import → record success/traceback.
4. Execute against the golden corpus → record errors and wall time.
5. For Fava extensions: load the extension's report page in the WebView and screenshot it.
6. Byte-diff the corpus afterwards → any unexpected mutation is a **fail**, not a warning.
7. Emit a matrix row (JSON) plus logs into a dated folder under `build/`.

Run by hand before a release (`tools/plugin-compat/run_catalog.py` on a desktop,
`push-to-device.sh` then `CatalogManifestTest` on a device). It is not in CI: most entries are
fetched from the network, and CI's verify job is offline by design.

---

## 6. Risk model and controls

Plugins are **not sandboxed**, see [threat-model.md](threat-model.md) §3.2. Controls:

- Off by default, per project.
- Enable requires an explicit warning acknowledgement stating the code can read and
  destroy the ledger and use the network.
- Provenance card: origin path, import date, SHA-256, size; a changed hash raises an
  alert and re-disables the plugin until re-confirmed.
- Execution log per invocation: timestamp, working directory, arguments, environment,
  duration, exit status, stdout/stderr, with secret redaction.
- Path guard: writes outside the project and the app-private runtime are refused unless
  the user granted access through a supported Android mechanism.
- **Safe mode** disables all third-party code and opens ledgers read-only.
- **Recovery mode** is reachable when a plugin breaks engine startup.
- **`play` additionally:** pure-Python plugins only (no `.so`), no code fetched from any
  network source, and the Java/Android bridge withheld from the user import context. These
  are Play-policy compliance measures; they are *not* a security sandbox, and the UI must
  not imply otherwise.
- Timeouts and cancellation for scripts; scheduled work uses `WorkManager` and states
  plainly that Android does not guarantee exact timing.

---

## 7. The catalogue in the app

**More → Plugins → Plugins you can install** lists every catalogue entry: what it is, how it
tested, its licence and any licence note.

- **Expert edition: Install and Remove, one plugin at a time.** Install shows what it will
  download -- one archive from PyPI or GitHub, pinned to a version or commit and checked against
  the SHA-256 in the catalogue -- and which Python packages come with it, then fetches only after
  the person agrees. Each plugin and its packages go into a folder of their own
  (`files/catalogue/<id>/site`), so removing one never breaks another. Once installed, the same
  row turns the plugin on for the open ledger by writing its `plugin` or `fava-extension`
  directive.
- **Play edition: listed, not installed.** Downloading code is what Play forbids. The row
  says to add the plugin from a file, or to use the Expert edition.
- **Keeping it current.** `tools/plugin-compat/catalogue.py check` compares the catalogue with
  the Plugins section of awesome-beancount's README and exits non-zero on anything new or
  dropped; run it for every app release, test what it reports, and add it. `catalogue.py pin`
  refreshes each entry's download pin. The catalogue is copied into the APK at build time.
- **Beancount's load cache follows the plugins.** Beancount pickles a load keyed on the ledger
  files alone, so a load that failed to import a missing plugin was served again after the
  plugin was installed. The cache file is now named for the plugin folders in reach.

---

## 8. Known limitations (published with the app)

- No `subprocess`, no shell, no arbitrary native binaries.
- Only packages with Android wheels: pure Python, Chaquopy's index, or a PEP 738 Android wheel a
  project publishes on PyPI itself (Expert edition).
- `matplotlib` only with the `Agg` backend.
- 32-bit ABIs unsupported in v1.
- Background execution is subject to Android limits; exact scheduling is impossible.
- Plugins run with the app's privileges and are not isolated.
