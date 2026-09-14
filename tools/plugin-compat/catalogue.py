"""Keeps catalog/plugins.json in step with awesome-beancount, and pins how each entry installs.

    python3 tools/plugin-compat/catalogue.py check   # new or dropped plugins upstream; exit 1 if any
    python3 tools/plugin-compat/catalogue.py pin     # write an `install` block for every entry

**check** is the release step that "monitors" the list (B82). The app's curated catalogue is
a copy of the Plugins section of https://awesome-beancount.com, which is generated from the
README of github.com/siddhantgoel/awesome-beancount. A plugin added there is not in the app
until someone runs this, tests the plugin and adds it -- which is the point: a catalogue entry
is a recommendation with a compatibility result attached, and a list copied blindly is not.

**pin** writes, for every entry, exactly what the Expert edition downloads when a person taps
Install: one archive URL, its SHA-256, which directories or files inside it are the importable
code, and which Python packages it needs beside it. Pinned here, on a desktop, so the phone
never resolves a moving branch: a GitHub source is fixed to the commit that was current when
this ran, and a PyPI source to one pure-Python wheel. The app refuses an archive whose digest
does not match.

Entries are matched by repository URL. Import names and plugin modules come from the harness's
own discovery (`build/plugin-sources/results.json` and `device-manifest.json`, written by
`run_catalog.py`), so they describe what actually loaded rather than what a README says.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import tomllib
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "catalog" / "plugins.json"
SOURCES = REPO / "build" / "plugin-sources"
AWESOME_README = "https://raw.githubusercontent.com/siddhantgoel/awesome-beancount/main/README.md"
USER_AGENT = "beanbook-catalogue/1.0"

# Packages the app already bundles (engine-local/requirements.lock), and packaging-time
# requirements some projects wrongly declare as runtime ones. Never fetched beside a plugin.
NOT_FETCHED = {
    "beancount", "fava", "beanquery", "beangulp", "flask", "werkzeug", "jinja2", "click",
    "markupsafe", "itsdangerous", "babel", "cheroot", "simplejson", "watchfiles", "ply",
    "python-dateutil", "pytest", "setuptools", "wheel", "pip", "mypy", "black", "ruff",
    "pandas-stubs", "matplotlib-stubs", "types-pyyaml", "flask-babel", "markdown2",
}

# Where the harness's import names need help. Keyed by catalogue id.
IMPORT_OVERRIDES = {
    # The extension lives at the repository root; it is installed under a real import name.
    "fava-portfolio-summary": {"paths": [""], "import_name": "fava_portfolio_summary",
                               "modules": ["fava_portfolio_summary"]},
    # Not on awesome-beancount and not yet through the harness; its plugin modules, from the wheel.
    "beancount-interpolate": {"modules": ["beancount_interpolate.recur", "beancount_interpolate.split",
                                          "beancount_interpolate.spread", "beancount_interpolate.depreciate"]},
}

# Distributions whose PyPI name differs from the catalogue id (tools/plugin-compat/fetch.py
# explains each; `lazy` on PyPI is an unrelated project).
PYPI = {
    "autobean.": "autobean", "reds.": "beancount-reds-plugins", "beangrow": "beangrow",
    "fava_investor": "fava-investor", "fava-portfolio-returns": "fava-portfolio-returns",
    "metadata-spray": "beancount-plugins-metadata-spray",
    "portfolio_allocation": "beancount_portfolio_allocation",
    "beancount-zakat": "beancount-zakat", "fava-envelope": "fava-envelope",
    "fava-dashboards": "fava-dashboards", "beancount-balexpr": "beancount-balexpr",
    "beancount-interpolate": "beancount-interpolate",
}


def get(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def slug(repo: str) -> str:
    text = repo.strip().rstrip("/")
    if "github.com/" in text:
        text = text.split("github.com/", 1)[1]
    parts = [p for p in re.split(r"/(?:tree|blob)/", text, maxsplit=1)[0].split("/") if p]
    return "/".join(parts[:2]).lower()


def awesome_plugins() -> list[dict]:
    """Every link in the README's Plugins section, with its sub-heading."""
    text = get(AWESOME_README).decode()
    section = text.split("\n## Plugins", 1)[1].split("\n## ", 1)[0]
    found, heading = [], None
    for line in section.splitlines():
        if line.startswith("### "):
            heading = line[4:].strip()
        match = re.match(r"\s*-\s*\[([^\]]+)\]\(([^)]+)\)", line)
        if match:
            found.append({"name": match.group(1), "url": match.group(2), "section": heading})
    return found


def check() -> int:
    catalog = json.loads(CATALOG.read_text())
    upstream = awesome_plugins()
    ours = {(e["name"].lower(), slug(e["repo"])) for e in catalog["entries"] if e.get("upstream", True)}
    ours_names = {name for name, _ in ours}
    theirs = {(p["name"].lower(), slug(p["url"])) for p in upstream}
    new = [p for p in upstream if p["name"].lower() not in ours_names]
    dropped = sorted(name for name, _ in ours - theirs if name not in {n for n, _ in theirs})
    print(f"awesome-beancount lists {len(upstream)} plugins; the catalogue has {len(ours)} of theirs")
    for p in new:
        print(f"  NEW      {p['name']}  {p['url']}  ({p['section']})")
    for name in dropped:
        print(f"  DROPPED  {name} (no longer listed upstream)")
    if not new and not dropped:
        print("  in step")
    return 1 if new or dropped else 0


def pypi_name(entry: dict) -> str | None:
    for prefix, name in PYPI.items():
        if entry["id"].startswith(prefix):
            return name
    return None


def requirements(requires: list[str] | None) -> list[str]:
    out = []
    for requirement in requires or []:
        if ";" in requirement and "extra" in requirement.split(";", 1)[1]:
            continue
        name = re.split(r"[\s<>=!~;\[(]", requirement.strip(), maxsplit=1)[0]
        normal = name.lower().replace("_", "-")
        if normal and normal not in NOT_FETCHED and normal not in out:
            out.append(normal)
    return out


def from_pypi(name: str) -> dict | None:
    meta = json.loads(get(f"https://pypi.org/pypi/{name}/json"))
    version = meta["info"]["version"]
    wheel = next((f for f in meta["releases"].get(version, [])
                  if f["filename"].endswith("-none-any.whl")), None)
    if wheel is None:
        return None
    payload = get(wheel["url"], timeout=180)
    digest = hashlib.sha256(payload).hexdigest()
    if digest != wheel["digests"]["sha256"]:
        raise SystemExit(f"{name}: PyPI digest does not match the download")
    names = zipfile.ZipFile(io.BytesIO(payload)).namelist()
    # Importable code only: package directories and top-level modules, not a README the wheel
    # happens to carry at its root.
    tops = sorted({n.split("/", 1)[0] for n in names
                   if ("/" in n or n.endswith(".py"))
                   and not n.split("/", 1)[0].endswith((".dist-info", ".data"))})
    return {
        "source": "pypi", "archive": wheel["url"], "sha256": digest, "version": version,
        "paths": tops, "requires": requirements(meta["info"].get("requires_dist")),
    }


def from_github(repo: str, package: str, single_file: bool) -> dict:
    commit = json.loads(get(f"https://api.github.com/repos/{repo}/commits/HEAD"))["sha"]
    url = f"https://codeload.github.com/{repo}/zip/{commit}"
    payload = get(url, timeout=180)
    archive = zipfile.ZipFile(io.BytesIO(payload))
    names = archive.namelist()
    prefix = names[0].split("/", 1)[0] + "/"
    wanted = f"{package}" if single_file else f"{package}/__init__.py"
    candidates = sorted((n for n in names if n.endswith("/" + wanted) or n == prefix + wanted),
                        key=lambda n: (n.count("/"), "test" in n))
    if package and not candidates:
        raise SystemExit(f"{repo}: no {wanted} in the archive")
    if package:
        found = candidates[0][len(prefix):]
        path = found if single_file else found[: -len("/__init__.py")]
    else:
        path = ""
    requires: list[str] = []
    pyproject = prefix + "pyproject.toml"
    if pyproject in names:
        project = tomllib.loads(archive.read(pyproject).decode()).get("project", {})
        requires = requirements(project.get("dependencies"))
    elif prefix + "requirements.txt" in names:
        requires = requirements([line for line in archive.read(prefix + "requirements.txt").decode().splitlines()
                                 if line.strip() and not line.startswith(("#", "-"))])
    return {
        "source": "github", "archive": url, "sha256": hashlib.sha256(payload).hexdigest(),
        "version": commit[:12], "strip": prefix, "paths": [path], "requires": requires,
    }


def pin(only: list[str]) -> int:
    catalog = json.loads(CATALOG.read_text())
    results = json.loads((SOURCES / "results.json").read_text())
    manifest = {e["id"]: e for e in json.loads((SOURCES / "device-manifest.json").read_text())}
    for entry in catalog["entries"]:
        if only and entry["id"] not in only:
            continue
        found = results.get(entry["id"], {})
        modules = [found["module"]] if found.get("module") else list(found.get("modules") or [])
        override = IMPORT_OVERRIDES.get(entry["id"], {})
        try:
            install = None
            name = pypi_name(entry)
            if name:
                install = from_pypi(name)
            if install is None:
                listed = manifest.get(entry["id"], {})
                package = listed.get("package_name") or (modules[0].split(".")[0] if modules else "")
                if "paths" in override:
                    package = ""
                install = from_github(slug(entry["repo"]), package, bool(listed.get("single_file")))
            if "import_name" in override:
                install["import_name"] = override["import_name"]
            install["modules"] = override.get("modules", modules)
            entry["install"] = install
            print(f"{entry['id']:<34} {install['source']:<6} {install['version']:<14} "
                  f"{install['paths']} needs {install['requires']}")
        except (urllib.error.URLError, SystemExit) as problem:
            print(f"{entry['id']:<34} NOT PINNED: {problem}", file=sys.stderr)
    CATALOG.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["check", "pin"])
    parser.add_argument("--only", action="append", default=[])
    args = parser.parse_args()
    return check() if args.command == "check" else pin(args.only)


if __name__ == "__main__":
    raise SystemExit(main())
