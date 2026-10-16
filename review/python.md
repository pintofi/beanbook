# Review log: python (engine-local and patches)

Area `python` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

| path | checked | finding | evidence |
|---|---|---|---|
| `engine-local/build-backends.lock` | 3 5 | none | pins the build backends used by `tools/build-wheels.sh`; comment now self-contained |
| `engine-local/build.gradle.kts` | 3 5 | P-8 | module builds in `:app:testPlayDebugUnitTest`; comment pointing at a private document replaced |
| `engine-local/consumer-rules.pro` | 3 | none | rules keep the AIDL and Chaquopy entry points; release build is minified in CI |
| `engine-local/requirements.lock` | 3 | none | 256 hashes; installed by the reproducible wheel build |
| `engine-local/src/main/AndroidManifest.xml` | 2 3 | P-8 | the service is not exported and runs in its own process; comment cleaned |
| `engine-local/src/main/aidl/io/github/pintofi/beanbook/engine/local/IEngineService.aidl` | 2 3 | P-8 | interface unchanged; comments cleaned |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/BeancountEditor.kt` | 1 2 | none | BeancountEditorTest; edits go through the writer, not the engine |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/EngineConnection.kt` | 1 3 | P-8 | binder death and reconnect covered by the instrumentation suite |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/EngineService.kt` | 1 2 3 | P-8 | the token is held in the service process only |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/LedgerReader.kt` | 1 2 | P-5 | passes the app version into the cache fingerprint |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/LocalEngineClient.kt` | 1 2 | none | LocalEngineClientTest |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/PluginLoader.kt` | 2 3 | P-8 | plugins are loaded only when enabled and unchanged |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/PythonRuntime.kt` | 1 | none | thin wrapper over Chaquopy start-up |
| `engine-local/src/main/kotlin/io/github/pintofi/beanbook/engine/local/SnapshotCache.kt` | 1 2 3 | P-5, I-2 | SnapshotCacheTest.aChangedPluginInvalidatesTheAnswerItProduced fails without the fix (expected the plugin change, got an empty answer) |
| `engine-local/src/main/python/engine.py` | 1 2 3 | P-2, P-3 | test_engine_gate.py (token compared as bytes; a failed start clears `starting`) |
| `engine-local/src/main/python/extension_state.py` | 1 | none | module-level flags only; used by the fix for P-3 |
| `engine-local/src/main/python/ledger.py` | 1 2 | P-6, P-7, I-1 | test_review_fixes.py (half-even display rounding; malformed commodity attributes) |
| `engine-local/src/main/python/plugins.py` | 1 2 | P-4, P-8 | test_review_fixes.py (output handle sanitised) |
| `engine-local/src/main/python/sharing.py` | 1 2 | P-9 | test_sharing.py (Content-Length bounded) |
| `engine-local/src/main/python/writer.py` | 1 2 | P-1 | test_writer_line_endings.py: a CRLF ledger keeps its line endings; fails on the old code |
| `engine-local/wheels.lock` | 3 | none | sdist hashes for the two cross-built wheels |
| `patches/README.md` | 5 | none | matches the patches |
| `patches/beancount-3.2.3-link-libpython.patch` | 3 | none | applied by `tools/build-wheels.sh` after the hash check |
| `patches/beangrow-1.0.1-no-scipy.patch` | 3 | none | solver agrees with `fsolve` to 3e-13 on six cash-flow shapes (patches/README.md) |
| `patches/fava_portfolio_returns-2.7.0-no-scipy.patch` | 3 | none | same |

Python: `python -m unittest discover -s engine-local/src/test/python`, 153 tests, 0 failures. `ruff check engine-local/src` reports 42 findings, down from 43 before this review (style rules the project has not adopted; none new). Gradle: `:core-domain:test :core-data:test :core-engine:test :engine-remote:test :app:testPlayDebugUnitTest detekt :app:verifyPlayFlavorBoundaries` all pass.

## Fixed in this pull request

- **P-1 (S1): editing a CRLF ledger corrupted its line endings.** `writer.py` read files in text mode, so `\r\n` became `\n`. Files are now read with newlines untouched, split on `\n` only, and replacement text takes the line ending of the lines it replaces.
- **P-2 (S2):** the token check compared strings, which raises on non-ASCII input; it now compares bytes in constant time.
- **P-3 (S2): a failed engine start left `starting` set,** so every later `inspect` waited out its full timeout. It is cleared on any failure.
- **P-4 (S3):** the live output file for a plugin script took its name from the handle unchanged; the handle is reduced to letters, digits, `_` and `-`.
- **P-5 (S2): the snapshot cache did not notice a changed plugin or a new app version.** The fingerprint now includes the app version code and the plugin folder listing (names, sizes, times). Cache format bumped to 4. The new Android test fails without the fix.
- **P-6 (S3):** display rounding used half-up; Beancount and Fava round half to even.
- **P-7 (S3):** a commodity with a malformed attribute could raise out of `ledger.inspect`; those are now skipped.
- **P-8 (S3):** comments pointing at documents and ids that are not in this repository removed or rewritten as self-contained explanations.
- **P-9 (S3):** `Content-Length` on the sign-in form is clamped to 0 to 4096.

## Filed as issues

- **I-1 (S2)** the engine rounds each account's operating-currency value before Kotlin sums them, so totals can differ from Fava by a cent: [#60](https://github.com/pintofi/beanbook/issues/60).
- **I-2 (S2)** the snapshot cache does not see changes to the documents folder or to installed extension packages: [#61](https://github.com/pintofi/beanbook/issues/61).
- Fava's own editor saves outside the write protocol: already tracked as [#17](https://github.com/pintofi/beanbook/issues/17).

## Accepted, with the reason

- **`BridgeGuard` is policy compliance, not a sandbox.** It stops a well-meaning plugin from doing the wrong thing; a hostile one runs with the app's permissions, as the threat model already says (plugins are disabled until enabled and held back when changed).
- **The 42 remaining ruff findings** are rules the project has not adopted (for example `exec` in the script runner, which is the feature). Not changed.
