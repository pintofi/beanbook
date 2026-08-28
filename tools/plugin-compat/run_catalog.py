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


SKIP_DIRS = {".git", "tests", "test", "docs", "examples", "build", "dist", "__pycache__"}


def _import_root_for(path: Path) -> Path:
    """The directory to put on `sys.path` so `path` is importable by a dotted name.

    Walk up while the directory is a package. The directory where that stops is the root:
    for `src/fava_envelope/x.py` it is `src`, and for `autobean-0.2.3/autobean/share.py` it
    is `autobean-0.2.3`.

    One extra step for namespace packages. `beancount_plugins_metadata_spray` has no
    `__init__.py` of its own but holds `plugins/`, which has one, so the plain walk would
    stop inside it and produce the module name `plugins.metadata_spray` — importable only by
    accident and wrong. When the directory the walk stopped at looks like a package that
    simply omits its `__init__.py`, go up one more.
    """
    root = path.parent
    while (root / "__init__.py").is_file() and root.parent != root:
        root = root.parent
    # `src` is a path convention, not a package: `src/fava_envelope/x.py` imports as
    # `fava_envelope.x`, never `src.fava_envelope.x`. Promoting it produced module names
    # that were wrong in a way the harness would not have noticed, because it only ever
    # printed them.
    conventional = {"src", "lib", "source", "python"}
    if (
        root.name.isidentifier()
        and root.name not in conventional
        and any(root.glob("*/__init__.py"))
        and root.parent != root
    ):
        root = root.parent
    return root


def discover_all(extracted: Path) -> dict:
    """Every Beancount plugin and Fava extension anywhere in a download.

    Scanning beats guessing an import root. The previous version derived one name from the
    catalog id and gave up when it was not there, which is why a single `mortgage.py` at a
    repository root, a `src/` layout and a namespace package were all recorded as
    impossible to install. What a user does — point the app at the file — has no such
    requirement.
    """
    plugins, extensions = [], []
    if not extracted.exists():
        return {"plugins": [], "extensions": [], "roots": {}}
    roots: dict[str, str] = {}
    for path in sorted(extracted.rglob("*.py")):
        parts = set(path.relative_to(extracted).parts)
        if parts & SKIP_DIRS or path.name.endswith(("_test.py", "test_.py")):
            continue
        if path.name.startswith("test_") or path.name == "setup.py":
            continue
        try:
            text = path.read_text(errors="replace")
        except OSError:
            continue
        is_plugin = re.search(r"^__plugins__\s*=", text, re.M)
        is_ext = re.search(r"FavaExtensionBase\b", text) and "class " in text
        if not (is_plugin or is_ext):
            continue
        root = _import_root_for(path)
        try:
            rel = path.relative_to(root)
        except ValueError:
            continue
        dotted = ".".join(rel.with_suffix("").parts)
        dotted = dotted[: -len(".__init__")] if dotted.endswith(".__init__") else dotted
        roots[dotted] = str(root)
        (plugins if is_plugin else extensions).append(dotted)
    return {
        "plugins": sorted(set(plugins)),
        "extensions": sorted(set(extensions)),
        "roots": roots,
    }


def import_only(extracted: Path, entry: dict, import_hint: str | None) -> dict:
    """Can this be imported at all? The question for a library or an importer."""
    # The module the id names, not the package that contains it. Importing `autobean` and
    # calling the result evidence for `autobean.truelayer` is the same error as running one
    # plugin and filing it under another: the top package imports fine, and the importer
    # underneath it reaches for `beancount.ingest`, which Beancount 3 removed.
    top = import_hint or entry["id"].split(".")[0].replace("-", "_")
    name = f"{top}.{entry['id'].split('.', 1)[1]}" if "." in entry["id"] else top

    # Some of these are a repository of loose scripts rather than a package — hoostus's
    # portfolio-returns is `irr.py` and `cashflows.py` at the top level, run directly. There
    # is no `portfolio_returns` to import and never was, so the guessed name failing says
    # nothing about the project. Fall back to what the download actually contains.
    # Only when the named module is genuinely absent. If it is there and fails, that failure
    # is the result — falling through to some other module in the same download and
    # reporting success is how `autobean.truelayer` came to be called supported twice: once
    # from a run of `autobean.include`, and once from an import of `autobean/__init__.py`.
    leaf = name.split(".")[-1]
    present = any(
        c.stem == leaf or (c.is_dir() and c.name == leaf)
        for c in extracted.rglob("*")
        if c.suffix in ("", ".py")
    )
    extra = []
    if not present:
        for base in (extracted, *(p for p in extracted.glob("*") if p.is_dir())):
            for module in sorted(base.glob("*.py")):
                if module.stem in ("setup", "conftest", "__init__") or module.stem.startswith("test"):
                    continue
                extra.append(module.stem)
    candidates = [name] + [e for e in dict.fromkeys(extra) if e != name]
    roots = [extracted, extracted / "src", *[p for p in extracted.glob("*") if p.is_dir()]]
    roots += [p / "src" for p in extracted.glob("*") if (p / "src").is_dir()]
    script = textwrap.dedent(
        f"""
        import importlib, json, sys
        sys.path[:0] = {json.dumps([str(r) for r in roots])}
        first = None
        for candidate in {candidates!r}:
            try:
                importlib.import_module(candidate)
                print(json.dumps({{"ok": True, "module": candidate}}))
                break
            except Exception as exc:
                if first is None:
                    first = f"{{type(exc).__name__}}: {{exc}}"
        else:
            print(json.dumps({{"ok": False, "error": first}}))
        """
    )
    proc = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=180)
    try:
        got = json.loads(proc.stdout.strip().splitlines()[-1])
    except Exception:
        return {"status": "unsupported", "reason": f"could not be imported: {proc.stderr[-300:]}"}
    kind = "importer" if entry["kind"] == "beancount-importer" else "library"
    if got.get("ok"):
        return {
            "status": "supported",
            "reason": f"imports cleanly as a {kind} ({got.get('module')}); it is not loaded by "
                      f"a plugin directive, so there is no ledger round-trip to report",
        }
    return {"status": "unsupported", "reason": f"as a {kind} it fails to import: {got.get('error')}"}


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
        found = discover_all(Path(record["root"]))

        # Not everything in the catalogue is loaded by a `plugin "..."` directive. beangrow
        # computes returns as a library; hoostus's portfolio-returns is a command-line
        # script; autobean.truelayer is an importer. Judging those by whether they define
        # `__plugins__` asks the wrong question and answers it "unavailable", which reads as
        # "you cannot have this" rather than "this is not that kind of thing".
        if entry["kind"] in ("library", "beancount-importer"):
            top = sorted({m.split(".")[0] for m in found["plugins"] + found["extensions"]})
            root_dir = Path(record["root"])
            outcome = import_only(root_dir, entry, import_hint=record.get("import_root"))
            results[entry["id"]] = {
                "status": outcome["status"],
                "reason": sanitise(outcome["reason"]),
                "version": record.get("version"),
                "license": record.get("license"),
            }
            continue

        if not found["plugins"] and not found["extensions"]:
            results[entry["id"]] = {
                "status": "unavailable",
                "reason": "the download defines no Beancount plugin and no Fava extension",
                "version": record.get("version"),
                "license": record.get("license"),
            }
            continue
        leaf = entry["id"].split(".")[-1].replace("-", "_")
        # The leaf may name the module (`zerosum` -> `...zerosum.zerosum`) or the package
        # that holds it (`capital_gains_classifier` -> `...capital_gains_classifier.gain_loss`
        # and `.long_short`). Matching only the final component missed the second shape and
        # reported a plugin that ships two working modules as having none.
        exact = [
            m for m in found["plugins"]
            if m.split(".")[-1] == leaf or leaf in m.split(".")[:-1]
        ]

        # A namespaced id — `autobean.truelayer`, `reds.zerosum` — names one plugin inside a
        # package that holds many. If that exact module is not there, running some other
        # module from the same package and filing the result under this id is not a weaker
        # result, it is a wrong one: `autobean.truelayer` was recorded as supported on the
        # strength of a run of `autobean.include`, and `reds.capital_gains_classifier` on a
        # run of `autoclose_tree`.
        #
        # A bare id — `beancount-balexpr` — names a project, and the single plugin it ships
        # may legitimately sit at `beancount_balexpr.balexpr`. There the fallback is right.
        if "." in entry["id"]:
            candidates = exact
        else:
            candidates = exact or found["plugins"]

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
            missing = (
                f"the download has no module '{leaf}' defining __plugins__; it ships "
                f"{', '.join(found['plugins'][:4]) or 'no plugin modules'}"
                if "." in entry["id"]
                else "no module defining __plugins__ found in the download"
            )
            results[entry["id"]] = {
                "status": "unsupported" if "." in entry["id"] else "unavailable",
                "reason": sanitise(missing),
                "version": record.get("version"),
                "license": record.get("license"),
            }
            continue

        module = candidates[0]
        root = Path(found["roots"][module])
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

    # What the device run needs: for every entry the desktop could load, the package
    # directory to push and the module name to name in a `plugin` directive. The device test
    # reads this rather than carrying a hand-written list, so a plugin that starts working
    # here cannot be silently left out of the run that matters.
    manifest = []
    for entry in catalog["entries"]:
        result = results[entry["id"]]
        # Fava extensions too, and `partial` with them. `partial` is what this harness says
        # about every extension — it can import one but cannot drive its UI — so filtering to
        # supported would push no extension at all. That is not hypothetical: doing exactly
        # that removed `fava_investor` from the device and FavaExtensionTest went from
        # passing to skipping, which reads identically in a console summary.
        module = result.get("module") or next(iter(result.get("modules") or []), None)
        if not module:
            continue
        if result["status"] not in ("supported", "needs-config", "partial"):
            continue
        record = fetched.get(entry["id"], {})
        found = discover_all(Path(record["root"]))
        root = found["roots"].get(module)
        if not root:
            continue
        # A package directory, or a single module file. The app installs both — a plugin
        # can be one `.py` — and `beancount-mortgage` is exactly that, so requiring a
        # directory would have dropped it from the device run for no reason.
        package = Path(root) / module.split(".")[0]
        if not package.is_dir():
            single = Path(root) / (module.replace(".", "/") + ".py")
            if not single.is_file():
                continue
            package = single
        manifest.append({
            "id": entry["id"],
            "kind": entry["kind"],
            "module": module,
            "package": str(package),
            "package_name": package.name,
            "single_file": package.is_file(),
            "desktop_status": result["status"],
        })
    (sources / "device-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"device manifest: {len(manifest)} entries -> {sources / 'device-manifest.json'}")

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
