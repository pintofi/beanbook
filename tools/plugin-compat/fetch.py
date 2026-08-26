"""Fetches catalogued plugins so the harness has something real to run.

Tries PyPI first, because that is how a user would most often get one, and falls back to a
GitHub tarball for the entries that are only published as source. Nothing is `pip install`ed:
the app loads plugin *files*, so the harness works from extracted source on `sys.path` for
the same reason. A plugin that needs a dependency the app does not ship will fail to import
here exactly as it would on a phone, and that is a real result, not a harness artefact.

    python3 tools/plugin-compat/fetch.py --out build/plugin-sources
"""

from __future__ import annotations

import argparse
import io
import json
import re
import subprocess
import sys
import tarfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "catalog" / "plugins.json"
USER_AGENT = "beancount-android-plugin-compat/1.0"

# Catalog ids are display names. These are the distributions and import names they
# correspond to, worked out by hand — there is no machine-readable link between them.
DISTRIBUTIONS = {
    "autobean.": ("autobean", "autobean"),
    "reds.": ("beancount-reds-plugins", "beancount_reds_plugins"),
    "lazy.": ("beancount-lazy-plugins", "beancount_lazy_plugins"),
    "beangrow": ("beangrow", "beangrow"),
    "portfolio-returns": ("fava-portfolio-returns", "fava_portfolio_returns"),
    "fava_investor": ("fava-investor", "fava_investor"),
    "fava-portfolio-returns": ("fava-portfolio-returns", "fava_portfolio_returns"),
}


def distribution_for(entry: dict) -> tuple[str, str | None]:
    """The PyPI distribution and import root for a catalog entry."""
    for prefix, mapping in DISTRIBUTIONS.items():
        if entry["id"].startswith(prefix):
            return mapping
    name = entry["id"].split(".")[0]
    return name, name.replace("-", "_")


def _get(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def from_pypi(distribution: str, into: Path) -> dict | None:
    """Downloads the newest sdist or wheel and extracts it."""
    try:
        meta = json.loads(_get(f"https://pypi.org/pypi/{distribution}/json"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
        return {"error": f"pypi: {exc}"}

    version = meta["info"]["version"]
    files = meta["releases"].get(version, [])
    chosen = next((f for f in files if f["packagetype"] == "sdist"), None) or next(
        (f for f in files if f["packagetype"] == "bdist_wheel"), None
    )
    if not chosen:
        return {"error": f"pypi: no downloadable file for {distribution} {version}"}

    payload = _get(chosen["url"], timeout=180)
    into.mkdir(parents=True, exist_ok=True)
    if chosen["filename"].endswith(".whl"):
        zipfile.ZipFile(io.BytesIO(payload)).extractall(into)
        root = into
    else:
        with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
            tar.extractall(into, filter="data")
        inner = [p for p in into.iterdir() if p.is_dir()]
        root = inner[0] if len(inner) == 1 else into

    return {
        "source": "pypi",
        "distribution": distribution,
        "version": version,
        "license": meta["info"].get("license") or _classifier_license(meta),
        "root": str(root),
        "url": chosen["url"],
    }


def _classifier_license(meta: dict) -> str:
    for classifier in meta["info"].get("classifiers", []):
        if classifier.startswith("License ::"):
            return classifier.split("::")[-1].strip()
    return "unstated"


def from_github(repo: str, into: Path) -> dict | None:
    for branch in ("main", "master"):
        try:
            payload = _get(
                f"https://codeload.github.com/{repo}/tar.gz/refs/heads/{branch}", timeout=180
            )
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
            continue
        into.mkdir(parents=True, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
            tar.extractall(into, filter="data")
        inner = [p for p in into.iterdir() if p.is_dir()]
        root = inner[0] if len(inner) == 1 else into
        return {
            "source": "github",
            "distribution": repo,
            "version": branch,
            "license": _license_file(root),
            "root": str(root),
            "url": f"https://github.com/{repo}",
        }
    return {"error": f"github: {repo} has no main or master branch"}


def _license_file(root: Path) -> str:
    for candidate in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING"):
        path = root / candidate
        if path.is_file():
            head = path.read_text(errors="replace")[:400]
            for name, pattern in (
                ("GPL-2.0", r"GNU GENERAL PUBLIC LICENSE.*Version 2"),
                ("GPL-3.0", r"GNU GENERAL PUBLIC LICENSE.*Version 3"),
                ("MIT", r"\bMIT License\b"),
                ("Apache-2.0", r"Apache License.*Version 2\.0"),
                ("BSD", r"\bBSD\b"),
            ):
                if re.search(pattern, head, re.S | re.I):
                    return name
            return "present, unrecognised"
    return "unstated"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="build/plugin-sources")
    parser.add_argument("--only", action="append", default=[])
    args = parser.parse_args()

    out = REPO / args.out
    out.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(CATALOG.read_text())

    fetched: dict[str, dict] = {}
    seen: dict[str, dict] = {}
    for entry in catalog["entries"]:
        if args.only and entry["id"] not in args.only:
            continue
        distribution, import_root = distribution_for(entry)

        if distribution in seen:
            result = dict(seen[distribution])
        else:
            target = out / distribution
            result = from_pypi(distribution, target)
            if result and result.get("error") and entry.get("repo"):
                result = from_github(entry["repo"], target)
            seen[distribution] = result

        result["import_root"] = import_root
        fetched[entry["id"]] = result
        status = result.get("error") or f"{result['source']} {result['version']}"
        print(f"{entry['id']:<38} {status}")

    (out / "fetched.json").write_text(json.dumps(fetched, indent=2) + "\n")
    failures = sum(1 for r in fetched.values() if r.get("error"))
    print(f"\n{len(fetched)} entries, {failures} could not be fetched")
    print(f"written to {out / 'fetched.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
