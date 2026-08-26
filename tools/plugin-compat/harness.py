"""The plugin compatibility harness.

Runs the five steps [plugin-compatibility.md](../../docs/plugin-compatibility.md) §1 defines,
against a real Beancount, and reports which one failed. Nothing here decides a plugin is
compatible by reading it: a step passes because it ran.

Two steps cannot be answered on a desktop and are reported as `skipped` here, with the reason
recorded rather than quietly counted as passes:

  * `installs` — the edition policy check is Kotlin (`PluginPreflight`), and its answer
    depends on the flavor. The device harness covers it.
  * `renders` — a Fava extension's UI needs the WebView.

Usage:
    python3 tools/plugin-compat/harness.py --case tools/plugin-compat/cases/tagger.json
    python3 tools/plugin-compat/harness.py --all --json results.json
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import textwrap
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ENGINE_PYTHON = REPO / "engine-local" / "src" / "main" / "python"

STEPS = ("installs", "imports", "executes", "renders", "preserves")


def _run_in_child(plugin_dir: str, ledger: str, allow_bridge: bool) -> dict:
    """Loads the ledger in a separate interpreter.

    A plugin is arbitrary code. Running it in this process would let one bad case take the
    whole harness down, and would leave whatever it imported behind to affect the next case.
    """
    script = textwrap.dedent(
        f"""
        import json, sys, time
        sys.path.insert(0, {str(ENGINE_PYTHON)!r})
        import plugins
        from beancount import loader
        from beancount.core import data

        def load(dirs):
            with plugins.activated(dirs, {allow_bridge!r}):
                started = time.monotonic()
                entries, errors, options = loader.load_file({ledger!r})
                return entries, errors, (time.monotonic() - started) * 1000

        try:
            without, base_errors, _ = load("")
        except BaseException as exc:
            without, base_errors = [], [exc]

        entries, errors, ms = load({plugin_dir!r})
        counts = {{}}
        for entry in entries:
            counts[type(entry).__name__] = counts.get(type(entry).__name__, 0) + 1

        print("@@RESULT@@" + json.dumps({{
            "entries": len(entries),
            "counts": counts,
            "errors": [str(getattr(e, "message", e))[:400] for e in errors],
            "duration_ms": round(ms, 1),
        }}))
        """
    )
    finished = subprocess.run(
        [sys.executable, "-c", script], capture_output=True, text=True, timeout=180
    )
    for line in finished.stdout.splitlines():
        if line.startswith("@@RESULT@@"):
            return json.loads(line[len("@@RESULT@@"):])
    return {
        "crashed": True,
        "returncode": finished.returncode,
        "stderr": finished.stderr[-1500:],
    }


def _baseline(ledger: str) -> dict:
    """The same ledger with no plugin directives, so losses can be attributed."""
    plain = Path(ledger).read_text()
    stripped = "\n".join(
        line for line in plain.splitlines() if not line.startswith("plugin ")
    )
    with tempfile.NamedTemporaryFile("w", suffix=".beancount", delete=False) as handle:
        handle.write(stripped)
        baseline_path = handle.name
    try:
        return _run_in_child("", baseline_path, allow_bridge=True)
    finally:
        Path(baseline_path).unlink(missing_ok=True)


def check(case: dict) -> dict:
    """Runs one case and returns a per-step verdict."""
    plugin_dir = str((REPO / case["plugin_dir"]).resolve())
    ledger = str((REPO / case["ledger"]).resolve())
    allow_bridge = case.get("allow_bridge", True)

    result = {
        "id": case["id"],
        "steps": {step: "skipped" for step in STEPS},
        "notes": {
            "installs": "edition policy is Kotlin; covered by the device harness",
            "renders": "no UI in this case" if not case.get("has_ui") else "needs a WebView",
        },
    }

    started = time.monotonic()
    loaded = _run_in_child(plugin_dir, ledger, allow_bridge)
    result["duration_ms"] = round((time.monotonic() - started) * 1000, 1)

    if loaded.get("crashed"):
        result["steps"]["imports"] = "failed"
        result["notes"]["imports"] = "the interpreter died: " + loaded.get("stderr", "")[-300:]
        return _with_expectation(result, case)

    import_errors = [e for e in loaded["errors"] if "Error importing" in e]
    if import_errors:
        result["steps"]["imports"] = "failed"
        result["notes"]["imports"] = import_errors[0]
        return _with_expectation(result, case)
    result["steps"]["imports"] = "passed"

    if loaded["errors"]:
        result["steps"]["executes"] = "failed"
        result["notes"]["executes"] = loaded["errors"][0]
    else:
        result["steps"]["executes"] = "passed"

    # Nothing may vanish. A plugin is allowed to add and to change; silently dropping a
    # directive is data invariant 4, and is the failure a user would never notice.
    baseline = _baseline(ledger)
    if baseline.get("crashed"):
        result["notes"]["preserves"] = "the baseline ledger would not load at all"
    else:
        lost = {
            name: baseline["counts"].get(name, 0) - loaded["counts"].get(name, 0)
            for name in baseline["counts"]
            if baseline["counts"].get(name, 0) > loaded["counts"].get(name, 0)
        }
        if lost:
            result["steps"]["preserves"] = "failed"
            result["notes"]["preserves"] = f"directives disappeared: {lost}"
        else:
            result["steps"]["preserves"] = "passed"
        result["notes"]["counts"] = {
            "baseline": baseline["counts"],
            "with_plugin": loaded["counts"],
        }

    return _with_expectation(result, case)


def _with_expectation(result: dict, case: dict) -> dict:
    """Compares a result against what the case says should happen.

    In one place, because it was in two and the early returns skipped it — so the cases that
    existed precisely to check a *failure* were the ones whose expectation was never
    evaluated, and they reported nothing while looking like they had passed.
    """
    expected = case.get("expect")
    if not expected:
        return result
    result["expected"] = expected
    result["matched_expectation"] = all(
        result["steps"].get(step) == wanted for step, wanted in expected.items()
    )
    return result


def verdict(result: dict) -> str:
    """The single word plugin-compatibility.md 1 defines."""
    steps = result["steps"]
    if any(v == "failed" for v in steps.values()):
        return "unsupported" if steps.get("imports") == "failed" else "partial"
    if all(v == "passed" for v in steps.values()):
        return "supported"
    return "partial"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--json")
    args = parser.parse_args()

    case_files = [Path(c) for c in args.case]
    if args.all or not case_files:
        case_files = sorted((REPO / "tools/plugin-compat/cases").glob("*.json"))

    results = []
    failures = 0
    for case_file in case_files:
        case = json.loads(case_file.read_text())
        result = check(case)
        result["verdict"] = verdict(result)
        results.append(result)

        line = f"{result['verdict']:<12} {result['id']:<42} "
        if "matched_expectation" in result:
            ok = result["matched_expectation"]
            line += "expected" if ok else "UNEXPECTED"
            if not ok:
                failures += 1
        print(line)
        for step, note in result["notes"].items():
            if result["steps"].get(step) == "failed":
                print(f"    {step}: {str(note)[:200]}")

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2) + "\n")

    print()
    print(f"{len(results)} cases, {failures} not matching their expectation")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
