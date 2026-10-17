"""Writes the Python lockfile from the packages that actually ended up in an APK.

The project requires pinned versions, and only the two direct dependencies were pinned:
`fava==1.30.15` and `beancount==3.2.3`. Every transitive dependency floated. Two clean builds
of the same commit resolved **click 8.4.2 and click 8.5.0**, which is both a reproducibility
failure and a supply-chain one — a build can pick up a version nobody reviewed.

Generated from a built APK rather than from `pip freeze` on the host, because the host's
resolution is not the one that ships: Chaquopy resolves for Android platform tags against a
local wheel directory, and the answer differs.

Each pin carries the SHA-256 of every artefact pip could legitimately choose for that
version, so a published release cannot be altered underneath the pin. Three sources feed it:

* **PyPI** publishes digests in its JSON API, so those are read, not computed.
* **Chaquopy's index** is a plain directory listing with no digests at all, so its wheels are
  downloaded and hashed here. That is trust-on-first-use: it fixes what every later build
  gets, but the first recording is only as good as the connection that made it.
* **The two wheels we cross-build ourselves** are hashed from the local wheel directory,
  because they are not reproducible byte for byte. Their
  supply-chain pin is the sdist hash in `engine-local/wheels.lock`; the hash here only
  describes the binary this machine produced, and `tools/build-wheels.sh` rewrites it.

    ./gradlew :app:assembleExpertRelease
    python3 tools/lock-python.py --apk <the apk> --out engine-local/requirements.lock
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# Chaquopy's own support packages, pulled in by lxml's Android wheel. They were left out of
# the lockfile as "not on PyPI", which was true and did not matter until hashes arrived:
# --require-hashes covers the whole dependency graph, and pip refuses the build if anything
# reachable is unpinned. They are on Chaquopy's index, so they are pinned there.
CHAQUOPY_PROVIDED: set[str] = set()

# Chaquopy serves its own prebuilt Android wheels from here. The path carries the plugin's
# major version, so it moves when Chaquopy is upgraded — which is a change of its own.
CHAQUOPY_INDEX = "https://chaquo.com/pypi-13.1"

# Built by tools/build-wheels.sh rather than downloaded. See the module docstring.
LOCALLY_BUILT = {"beancount", "watchfiles"}


def fetch(url: str, timeout: int = 120) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


def pypi_hashes(name: str, version: str) -> list[str]:
    """Every artefact PyPI holds for this version, by digest. Read, not computed."""
    body = json.loads(fetch(f"https://pypi.org/pypi/{name}/{version}/json"))
    return sorted({f["digests"]["sha256"] for f in body["urls"]})


def chaquopy_hashes(name: str, version: str) -> list[str]:
    """Chaquopy's index publishes no digests, so its wheels are downloaded and hashed."""
    # The index path spells names with hyphens; the wheel files inside it use underscores.
    listing = ""
    for candidate in {name, name.replace("_", "-"), name.replace("-", "_")}:
        try:
            listing = fetch(f"{CHAQUOPY_INDEX}/{candidate}/").decode("utf-8", "replace")
            name = candidate
            break
        except urllib.error.HTTPError as problem:
            if problem.code != 404:
                raise
    if not listing:
        return []

    stem = re.escape(name).replace("_", "[_-]").replace(r"\-", "[_-]")
    wheels = sorted(set(re.findall(rf'href="({stem}-{re.escape(version)}-[^"]+\.whl)"', listing)))
    digests = []
    for wheel in wheels:
        digests.append(hashlib.sha256(fetch(f"{CHAQUOPY_INDEX}/{name}/{wheel}")).hexdigest())
    return sorted(set(digests))


def local_hashes(name: str, version: str, wheel_dir: Path) -> list[str]:
    """The wheels this repository cross-builds, hashed where they were put."""
    return sorted(
        hashlib.sha256(wheel.read_bytes()).hexdigest()
        for wheel in sorted(wheel_dir.glob(f"{name}-{version}-*.whl"))
    )


def hashes_for(name: str, version: str, wheel_dir: Path) -> tuple[list[str], str]:
    key = name.lower().replace("-", "_")
    if key in LOCALLY_BUILT:
        return local_hashes(key, version, wheel_dir), "built here"
    found = chaquopy_hashes(name, version)
    if found:
        return found, "chaquopy"
    return pypi_hashes(name, version), "pypi"


def packages(apk: Path) -> list[tuple[str, str]]:
    found: dict[str, str] = {}
    with zipfile.ZipFile(apk) as outer:
        for name in outer.namelist():
            if not name.startswith("assets/chaquopy/requirements"):
                continue
            with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                for entry in inner.namelist():
                    match = re.match(r"([A-Za-z0-9_.\-]+)-([0-9][^/]*)\.dist-info/", entry)
                    if match:
                        found[match.group(1)] = match.group(2)
    return sorted(found.items(), key=lambda item: item[0].lower())


def refresh_local(lockfile: Path, wheel_dir: Path) -> int:
    """Write the pins for the wheels we cross-build, into their own generated file.

    These cannot live in the committed lockfile. Their bytes differ on every build — a GNU
    build-id and a build directory — so a hash recorded on one
    machine can never match a wheel built on another, and CI would fail on its own artefacts
    every time the cache missed. Committing them would make the lockfile a record of whose
    laptop last ran the build.

    So the committed lockfile pins what is *downloaded*, where a hash means something across
    machines, and this file pins what is *built here*. Its supply-chain pin is the sdist hash
    in engine-local/wheels.lock, which is committed and does hold across machines.
    """
    lines = [
        "# The two wheels this machine cross-built. GENERATED — not in version control.",
        "#",
        "# Written by tools/build-wheels.sh. Their hashes describe the binaries in",
        f"#   {wheel_dir}",
        "# and would not match a wheel built anywhere else, which is why they are here and",
        "# not in requirements.lock.",
        "",
    ]
    versions = pinned_versions(lockfile)
    for name in sorted(LOCALLY_BUILT):
        version = versions.get(name)
        if version is None:
            print(f"::error::{name} has no version recorded in {lockfile}")
            return 1
        digests = local_hashes(name, version, wheel_dir)
        if not digests:
            print(f"::error::no {name}-{version} wheels in {wheel_dir}")
            return 1
        lines.append(f"{name}=={version} \\")
        lines += [f"    --hash=sha256:{d} \\" for d in digests[:-1]]
        lines.append(f"    --hash=sha256:{digests[-1]}")
        print(f"{name}=={version}: {len(digests)} hashes")

    generated = lockfile.with_name("requirements-local.lock")
    generated.write_text("\n".join(lines) + "\n")
    print(f"written to {generated}")
    return 0


def pinned_versions(lockfile: Path) -> dict[str, str]:
    """The versions recorded in the committed lockfile, including its commented-out ones."""
    text = lockfile.read_text()
    return {
        name.lower().replace("-", "_"): version
        for name, version in re.findall(r"^#?\s*([A-Za-z0-9_.\-]+)==(\S+?)(?: \\)?$", text, re.M)
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", help="required unless --refresh-local")
    parser.add_argument("--out", default="engine-local/requirements.lock")
    parser.add_argument(
        "--refresh-local",
        action="store_true",
        help="rewrite only the cross-built wheels' hashes in an existing lockfile, "
             "leaving every downloaded pin alone; used by tools/build-wheels.sh",
    )
    parser.add_argument(
        "--wheel-dir",
        default=str(Path.home() / "tools" / "wheels"),
        help="where tools/build-wheels.sh put the two cross-built wheels",
    )
    args = parser.parse_args()

    if args.refresh_local:
        return refresh_local(Path(REPO / args.out), Path(args.wheel_dir))

    found = packages(Path(args.apk))
    if not found:
        print("no dist-info found in the APK — is it a Chaquopy build?")
        return 1

    lines = [
        "# Python dependencies, pinned and hashed.",
        "#",
        "# Generated by tools/lock-python.py from a built APK. Do not edit by hand.",
        "#",
        "# Only fava and beancount were pinned before this file existed; every transitive",
        "# dependency floated, and two clean builds of the same commit resolved click 8.4.2",
        "# and click 8.5.0.",
        "#",
        "# A hash on any line puts pip into --require-hashes mode for the whole file, so",
        "# every requirement must carry one and nothing can be installed that was not",
        "# written down here. Digests come from PyPI's API where it publishes them, and are",
        "# computed by downloading the file where it does not — Chaquopy's index publishes",
        "# none.",
        "#",
        "# beancount and watchfiles are not here: they are cross-built, not reproducible",
        "# byte for byte, and a hash recorded on one machine cannot match a wheel built on",
        "# another. They are pinned in the generated requirements-local.lock instead, and",
        "# their supply-chain pin is the sdist hash in wheels.lock, which does hold across",
        "# machines.",
        "",
    ]
    skipped = []
    wheel_dir = Path(args.wheel_dir)
    sources: dict[str, int] = {}
    for name, version in found:
        key = name.lower().replace("-", "_")
        if key in CHAQUOPY_PROVIDED:
            skipped.append(f"{name}=={version}")
            continue

        if key in LOCALLY_BUILT:
            # Pinned in the generated requirements-local.lock instead; see refresh_local.
            skipped.append(f"{name}=={version}")
            continue

        digests, source = hashes_for(name, version, wheel_dir)
        if not digests:
            print(f"::error::no artefact found for {name}=={version} ({source})")
            return 1
        sources[source] = sources.get(source, 0) + 1

        lines.append(f"{name}=={version} \\")
        lines += [f"    --hash=sha256:{digest} \\" for digest in digests[:-1]]
        lines.append(f"    --hash=sha256:{digests[-1]}")

    if skipped:
        lines += [
            "",
            "# Cross-built by tools/build-wheels.sh, and pinned by hash in the generated",
            "# requirements-local.lock. The version is recorded here so that file can be",
            "# rewritten without an APK to read it out of:",
        ]
        lines += [f"#   {entry}" for entry in skipped]

    out = REPO / args.out
    out.write_text("\n".join(lines) + "\n")
    print(f"{len(found) - len(skipped)} packages pinned, {len(skipped)} provided by Chaquopy")
    for source, count in sorted(sources.items()):
        print(f"  {count:3} from {source}")
    print(f"written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
