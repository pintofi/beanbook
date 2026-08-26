"""Runs the fetched catalogue through the harness and records what happened.

Plugin module names are **discovered, not guessed**: a Beancount plugin is any module
defining `__plugins__`, and a Fava extension is any module defining a subclass of
`FavaExtensionBase`. There is no machine-readable link between a catalog id and an import
path, and a hand-written mapping would rot silently.

    python3 tools/plugin-compat/fetch.py --out build/plugin-sources
    python3 tools/plugin-compat/run_catalog.py --sources build/plugin-sources
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ENGINE_PYTHON = REPO / "engine-local" / "src" / "main" / "python"
CATALOG = REPO / "catalog" / "plugins.json"

PROBE = """\
2026-01-01 open Assets:Bank:Checking
2026-01-01 open Expenses:Food:Groceries
2026-01-01 open Income:Salary

2026-02-01 * "Small shop" "under the line"
  Expenses:Food:Groceries    12.00 USD
  Assets:Bank:Checking

2026-02-02 * "Big shop" "over the line"
  Expenses:Food:Groceries   250.00 USD
  Assets:Bank:Checking

2026-02-03 note Assets:Bank:Checking "a note that must survive"
"""


def package_root(extracted: Path, import_root: str) -> Path | None:
    """The directory to put on `sys.path` so `import <import_root>` works."""
    if not extracted.exists():
        return None
    for candidate in [extracted, extracted / "src", *extracted.glob("*")]:
        if (candidate / import_root / "__init__.py").is_file():
            return candidate
        if (candidate / f"{import_root}.py").is_file():
            return candidate
    for init in extracted.rglob(f"{import_root}/__init__.py"):
        return init.parent.parent
    return None


def discover_modules(root: Path, import_root: str) -> dict:
    """Every Beancount plugin and Fava extension the package provides."""
    plugins, extensions = [], []
    package = root / import_root
    files = package.rglob("*.py") if package.is_dir() else [root / f"{import_root}.py"]
    for path in files:
        if not path.is_file():
            continue
        try:
            text = path.read_text(errors="replace")
        except OSError:
            continue
        dotted = ".".join(path.relative_to(root).with_suffix("").parts)
        dotted = dotted[: -len(".__init__")] if dotted.endswith(".__init__") else dotted
        if re.search(r"^__plugins__\s*=", text, re.M):
            plugins.append(dotted)
        if re.search(r"FavaExtensionBase\b", text) and "class " in text:
            extensions.append(dotted)
    return {"plugins": sorted(set(plugins)), "extensions": sorted(set(extensions))}


def run_one(root: Path, module: str, work: Path, config: str | None = None) -> dict:
    """Loads a probe ledger with `plugin "<module>"` in a child interpreter."""
    ledger = work / "probe.beancount"
    directive = f'plugin "{module}"' if config is None else f'plugin "{module}" "{config}"'
    ledger.write_text(directive + "\n\n" + PROBE)

    script = textwrap.dedent(
        f"""
        import json, sys
        sys.path.insert(0, {str(ENGINE_PYTHON)!r})
        import plugins
        from beancount import loader

        def _trim(text):
            # Keep the head *and* the tail. A traceback truncated from the front loses the
            # exception line, which is the only part that says what actually went wrong.
            lines = [l for l in text.splitlines() if l.strip()]
            if len(lines) <= 4:
                return text[:400]
            # chr(10), not a backslash escape: this text is inside an f-string, where
            # a backslash escape would become a real newline and break the script.
            return chr(10).join(lines[:1] + ["  ..."] + lines[-3:])[:600]

        def counts(entries):
            out = {{}}
            for e in entries:
                out[type(e).__name__] = out.get(type(e).__name__, 0) + 1
            return out

        try:
            with plugins.activated({str(root)!r}, True):
                entries, errors, _ = loader.load_file({str(ledger)!r})
            payload = {{
                "counts": counts(entries),
                "errors": [_trim(str(getattr(e, "message", e))) for e in errors],
            }}
        except BaseException as exc:
            payload = {{"crashed": f"{{type(exc).__name__}}: {{exc}}"[:300]}}

        print("@@R@@" + json.dumps(payload))
        """
    )
    try:
        done = subprocess.run(
            [sys.executable, "-c", script], capture_output=True, text=True, timeout=240
        )
    except subprocess.TimeoutExpired:
        return {"crashed": "timed out after 240s"}
    for line in done.stdout.splitlines():
        if line.startswith("@@R@@"):
            return json.loads(line[5:])
    return {"crashed": (done.stderr or "no result")[-300:]}


# Two shapes of the same thing: Beancount calls a plugin with no config argument, and the
# plugin says so. reds.* raise TypeError from the call itself; autobean raises a ValueError
# of its own. Matching only the first mislabelled autobean.share as broken when it merely
# wanted configuring.
NEEDS_CONFIG = re.compile(
    r"missing 1 required positional argument: 'config'"
    r"|expects an argument"
    r"|takes exactly 2 arguments"
)


def sanitise(text: str) -> str:
    """Strips machine-specific paths out of anything destined for the repository.

    A traceback carries the absolute path of every frame, which on a developer machine means
    a home directory and a username. The catalog is committed and published; it has no
    business carrying either.
    """
    cleaned = text.replace(str(REPO), "<repo>")
    cleaned = re.sub(r"/home/[^/\s\"']+", "<home>", cleaned)
    cleaned = re.sub(r"/Users/[^/\s\"']+", "<home>", cleaned)
    return re.sub(r"[A-Za-z]:\\\\Users\\\\[^\\\\\s]+", "<home>", cleaned)


def classify(result: dict) -> tuple[str, str]:
    """A catalog status and the reason, from one load."""
    if "crashed" in result:
        return "unsupported", result["crashed"]

    errors = result.get("errors", [])
    importing = [e for e in errors if "Error importing" in e]
    if importing:
        missing = re.search(r"ModuleNotFoundError: No module named '([\w.]+)'", importing[0])
        if missing:
            return "unsupported", f"needs a dependency the app does not ship: {missing.group(1)}"
        return "unsupported", importing[0][:200]

    # Nothing may vanish (data invariant 4).
    counts = result.get("counts", {})
    if counts.get("Note", 0) < 1 or counts.get("Transaction", 0) < 2:
        return "partial", f"directives disappeared: {counts}"
    if errors:
        return "partial", errors[0][:200]
    return "supported", "imports, executes and preserves every directive"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", default="build/plugin-sources")
    parser.add_argument("--write-catalog", action="store_true")
    args = parser.parse_args()

    sources = REPO / args.sources
    fetched = json.loads((sources / "fetched.json").read_text())
    catalog = json.loads(CATALOG.read_text())
    work = sources / "_work"
    work.mkdir(parents=True, exist_ok=True)

    results = {}
    for entry in catalog["entries"]:
        record = fetched.get(entry["id"], {})
        if record.get("error"):
            results[entry["id"]] = {
                "status": "unavailable",
                "reason": sanitise(f"could not be fetched: {record['error']}"),
            }
            continue

        # Older fetched.json files may carry a null import root; the distribution name is
        # the right fallback and costs nothing to apply.
        import_root = record.get("import_root") or record["distribution"].replace("-", "_")
        root = package_root(Path(record["root"]), import_root)
        if root is None:
            results[entry["id"]] = {
                "status": "unavailable",
                "reason": sanitise(f"no importable '{import_root}' in the download"),
            }
            continue

        found = discover_modules(root, import_root)
        leaf = entry["id"].split(".")[-1].replace("-", "_")
        candidates = [m for m in found["plugins"] if m.split(".")[-1] == leaf] or found["plugins"]

        if entry["kind"] == "fava-extension":
            results[entry["id"]] = {
                "status": "partial" if found["extensions"] else "unavailable",
                "reason": sanitise(
                    "a Fava extension: importable here, but its UI needs a WebView and is "
                    f"not exercised ({', '.join(found['extensions'][:3])})"
                    if found["extensions"]
                    else "no FavaExtensionBase subclass found in the download"
                ),
                "modules": found["extensions"],
                "version": record.get("version"),
                "license": record.get("license"),
            }
            continue

        if not candidates:
            results[entry["id"]] = {
                "status": "unavailable",
                "reason": "no module defining __plugins__ found in the download",
                "version": record.get("version"),
                "license": record.get("license"),
            }
            continue

        module = candidates[0]
        outcome = run_one(root, module, work)
        status, reason = classify(outcome)

        # Plenty of plugins take a configuration string in the directive itself, and refuse
        # to run without one. That is a plugin that works and needs configuring, not a plugin
        # that is broken — reporting it as a failure would be wrong in a way a user would
        # act on. Retry with an empty config to find out which it is.
        if NEEDS_CONFIG.search(json.dumps(outcome)):
            retried = run_one(root, module, work, config="")
            retried_status, retried_reason = classify(retried)
            if retried_status == "supported":
                status = "needs-config"
                reason = (
                    "works, but the plugin directive must carry a configuration string; "
                    "loaded cleanly once given one"
                )
            elif "SyntaxError" in json.dumps(retried) or "literal_eval" in json.dumps(retried):
                # The plugin parses its config as a Python literal, and "" is not one. That
                # says nothing about the plugin beyond "it needs a real configuration".
                status = "needs-config"
                reason = (
                    "works, but the plugin directive must carry a real configuration — it "
                    "parses the argument as a Python literal, so an empty one is rejected"
                )
            else:
                status, reason = retried_status, "with an empty config: " + retried_reason
        results[entry["id"]] = {
            "status": status,
            "reason": sanitise(reason),
            "module": module,
            "version": record.get("version"),
            "license": record.get("license"),
        }

    for entry in catalog["entries"]:
        result = results[entry["id"]]
        print(f"{result['status']:<12} {entry['id']:<38} {result['reason'][:90]}")

    tally: dict[str, int] = {}
    for result in results.values():
        tally[result["status"]] = tally.get(result["status"], 0) + 1
    print("\n" + ", ".join(f"{count} {status}" for status, count in sorted(tally.items())))

    (sources / "results.json").write_text(json.dumps(results, indent=2) + "\n")

    if args.write_catalog:
        stamp = subprocess.run(
            ["date", "-u", "+%Y-%m-%d"], capture_output=True, text=True
        ).stdout.strip()
        for entry in catalog["entries"]:
            result = results[entry["id"]]
            entry["status"] = result["status"]
            entry["tested"] = {
                "on": stamp,
                "how": "tools/plugin-compat/run_catalog.py, desktop CPython + Beancount 3.2.3",
                "reason": result["reason"],
                "version": result.get("version"),
            }
            if result.get("license") and result["license"] != "unstated":
                entry["license"] = result["license"]
        CATALOG.write_text(json.dumps(catalog, indent=2) + "\n")
        print(f"catalog updated: {CATALOG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
