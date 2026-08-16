# Plugin and script compatibility

Applies to the **`expert` edition only**. The `play` edition ships no third-party Python.

---

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

## 4. Compatibility matrix (to be populated by testing)

| Plugin / extension | Version | Import | Execute | UI | Round-trip | Status |
|---|---|:--:|:--:|:--:|:--:|---|
| `beancount.plugins.*` (bundled) | 3.2.3 | — | — | — | — | untested |
| `fava.ext.portfolio_list` (bundled) | 1.30.15 | — | — | — | — | untested |
| `fava-portfolio-returns` | v2.7.0 | — | — | — | — | untested (Spike 9) |
| `beangrow` | latest | — | — | — | — | untested (Spike 9) |
| `fava-investor` | latest | — | — | — | — | untested |

Every row is filled by the harness in §5, with the ABI, engine versions, date, and log
path recorded. No row is marked ✅ from reading code.

---

## 5. Test harness

`tools/plugin-compat/` runs on a connected device or emulator per ABI:

1. Install the plugin into a scratch project's `plugins/`.
2. Pre-flight scan → record findings.
3. Import → record success/traceback.
4. Execute against the golden corpus → record errors and wall time.
5. For Fava extensions: load the extension's report page in the WebView and screenshot it.
6. Byte-diff the corpus afterwards → any unexpected mutation is a **fail**, not a warning.
7. Emit a matrix row (JSON) plus logs into `docs/feasibility/plugin-compat/<date>/`.

Runs in CI on the emulator matrix and manually on the physical arm64 device before each
release.

---

## 6. Risk model and controls

Plugins are **not sandboxed** — see [threat-model.md](threat-model.md) §3.2. Controls:

- Off by default, per project.
- Enable requires an explicit warning acknowledgement stating the code can read and
  destroy the ledger and use the network.
- Provenance card: origin path, import date, SHA-256, size; a changed hash raises an
  alert and re-disables the plugin until re-confirmed.
- Execution log per invocation: timestamp, working directory, arguments, environment,
  duration, exit status, stdout/stderr — with secret redaction.
- Path guard: writes outside the project and the app-private runtime are refused unless
  the user granted access through a supported Android mechanism.
- **Safe mode** disables all third-party code and opens ledgers read-only.
- **Recovery mode** is reachable when a plugin breaks engine startup.
- Timeouts and cancellation for scripts; scheduled work uses `WorkManager` and states
  plainly that Android does not guarantee exact timing.

---

## 7. Catalog format (designed, not shipped)

A future vetted catalog would be a signed JSON document:

```json
{
  "schema": 1,
  "entries": [{
    "id": "fava-portfolio-returns",
    "version": "2.7.0",
    "license": "GPL-2.0",
    "sha256": "…",
    "requires": {"fava": ">=1.30,<1.31", "beancount": ">=3.2,<4"},
    "tested": {"abis": ["arm64-v8a"], "engine": "py3.13/bc3.2.3/fava1.30.15"},
    "status": "supported"
  }],
  "signature": "…"
}
```

It is **not shipped in v1** in either edition. In `play` it would constitute interpreted
code delivered outside review and needs an explicit policy decision first.

---

## 8. Known limitations (published with the app)

- No `subprocess`, no shell, no arbitrary native binaries.
- Only packages with Android wheels (Chaquopy repo, pure-Python, or wheels we build).
- `matplotlib` only with the `Agg` backend.
- 32-bit ABIs unsupported in v1.
- Background execution is subject to Android limits; exact scheduling is impossible.
- Plugins run with the app's privileges and are not isolated.
