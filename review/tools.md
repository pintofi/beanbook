# Review log: tools (tools/ and build scripts)

Area `tools` of [#16](https://github.com/pintofi/beanbook/issues/16). Method and rubric: [README.md](README.md). Findings are described under the table.

**How this area was read.** Developer and CI scripts, 3,800 lines. `build-wheels.sh`, `plugin-compat/fetch.py`, `corpus/differential.py` and `run-ci-locally.sh` were read in the parts that matter for safety and correctness; the rest was checked with `bash -n`, `shellcheck`, `python -m py_compile` and `ruff`, and by what runs them in CI. The `checked` column says which numbers were applied.

| path | checked | finding | evidence |
|---|---|---|---|
| `tools/a11y-audit.py` | 1 5 | U-1 | unused import removed |
| `tools/build-wheels.sh` | 1 2 5 | U-2 | reproducible wheel build; shellcheck clean except an info note; the verification step now copes with a build directory containing a space |
| `tools/check-build-paths.py` | 3 5 | none | compiles; ruff clean; run by build-wheels.sh and CI |
| `tools/check-minsdk-symbols.py` | 3 5 | none | compiles; needs a built APK (run in CI) |
| `tools/check-reproducible.py` | 3 5 | none | compiles; compares two builds (run in CI) |
| `tools/check-workflows.py` | 3 5 | none | run in this review: passes |
| `tools/corpus/differential.py` | 1 5 | U-3 | dead placeholder variable and an inline import removed |
| `tools/corpus/generate.py` | 5 | none | compiles; generates the large corpus (#79) |
| `tools/licences.py` | 3 5 | none | compiles; regenerates licences.json from an APK and checks drift in CI |
| `tools/lock-python.py` | 3 5 | none | compiles; writes engine-local/requirements.lock with hashes |
| `tools/measure-cold-start.sh` | 5 | U-3 | bash -n; shellcheck clean; comment corrected |
| `tools/plugin-compat/cases/bridged.json` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/broken.json` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/dropper.json` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/bridged/bridged.py` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/bridged/ledger.beancount` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/broken/broken.py` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/broken/ledger.beancount` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/dropper/dropper.py` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/dropper/ledger.beancount` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/probe.beancount` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/tagger/ledger.beancount` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/fixtures/tagger/tagger.py` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/cases/tagger.json` | 5 | none | fixture for the plugin compatibility harness |
| `tools/plugin-compat/catalogue.py` | 5 | none | compiles; ruff clean |
| `tools/plugin-compat/fetch.py` | 1 3 | U-3 | read in full: downloads only from pypi.org and codeload.github.com; tar extraction uses the `data` filter; unused imports removed |
| `tools/plugin-compat/harness.py` | 5 | none | compiles; compiles; ruff notes `subprocess.run` without `check` |
| `tools/plugin-compat/push-to-device.sh` | 5 | none | bash -n; shellcheck clean |
| `tools/plugin-compat/run_catalog.py` | 5 | none | compiles; broad excepts are deliberate (one bad plugin must not stop a catalogue run) |
| `tools/review-files.py` | 1 5 | none | used by every area of this review |
| `tools/run-ci-locally.sh` | 3 5 | U-3 | runs the workflow's own steps with `eval`, by design, on the developer's machine; unused counter removed |
| `tools/sbom.py` | 3 5 | none | compiles; generates the SBOM in CI |

`python3 tools/check-workflows.py` passes. `shellcheck` on the shell scripts reports one info note (the single-quoted `bash -c` in `run-ci-locally.sh` is intended). `ruff` reports 23 findings, all style rules the project has not adopted (regex flag aliases, `subprocess.run` without `check`, broad excepts).

## Fixed in this pull request

- **U-1 (S3):** unused imports removed from `a11y-audit.py` and `plugin-compat/fetch.py`; one import block sorted.
- **U-2 (S2): the wheel verification step broke on a build directory with a space in its name.** It expanded the wheel's glob unquoted, so the machine this project is developed on, whose path contains a space, would have reported every wheel missing. It uses a bash array. The build itself is unchanged, so the wheels are the same bytes. The new lines were exercised on their own against a directory with a space, with a wheel present and with none; a full wheel build was not rerun. Also `A && B || C` became an `if`.
- **U-3 (S3):** a dead variable and an inline import removed from `corpus/differential.py`; an unused counter from `run-ci-locally.sh`; private ids and references to documents that are not in the repository removed from comments in nine scripts.

## Accepted, with the reason

- **`run-ci-locally.sh` evaluates the workflow's own steps.** That is its purpose, and it runs on the developer's machine against a file the developer controls.
- **Broad `except Exception` in `plugin-compat/run_catalog.py`.** One misbehaving plugin must not end a run over the whole catalogue; each is recorded as a failure.
- **Most scripts were not read line by line.** They have no effect on the shipped app, and most run in CI, which is their test.
