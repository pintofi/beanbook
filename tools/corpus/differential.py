#!/usr/bin/env python3
"""Differential testing against upstream Beancount.

Runs the *same* `ledger.digest` used on the device (engine-local/src/main/python) over
each corpus case on the desktop, and records the result as the expected baseline. The
instrumentation test then produces the same digests on Android and compares byte for
byte.

Also cross-checks the error count against upstream's own `bean-check` command, so the
baseline is not just "our code agreeing with itself".

Usage:
    tools/corpus/differential.py record        # write corpus/expected/*.json
    tools/corpus/differential.py verify        # re-run and diff against the baseline
"""

from __future__ import annotations

import argparse
import difflib
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "engine-local/src/main/python"))

import ledger


def cases(corpus: Path) -> list[Path]:
    return sorted(
        p for p in corpus.iterdir()
        if p.is_dir() and p.name != "expected" and (p / "main.beancount").is_file()
    )


def bean_check_errors(main: Path) -> int | None:
    """Upstream's own opinion on the file, for cross-checking our digest."""
    bean_check = Path(sys.executable).with_name("bean-check")
    if not bean_check.exists():
        return None
    result = subprocess.run(
        [str(bean_check), str(main)], capture_output=True, text=True, check=False
    )
    # bean-check prints one line per error to stderr and exits non-zero when it finds any.
    return len([line for line in result.stderr.splitlines() if line.strip()])


def run(corpus: Path, out: Path, record: bool) -> int:
    out.mkdir(parents=True, exist_ok=True)
    failures = 0

    for case in cases(corpus):
        main = case / "main.beancount"
        started = time.monotonic()
        digest = ledger.digest(str(main))
        elapsed_ms = (time.monotonic() - started) * 1000

        baseline = out / f"{case.name}.json"
        checked = bean_check_errors(main)

        our_error_count = json.loads(digest)["error_count"]

        note = ""
        if checked is not None:
            # bean-check counts error *lines*, which is not always one line per error;
            # what matters is that both agree on whether the file has errors at all.
            agree = (checked > 0) == (our_error_count > 0)
            note = f" bean-check_lines={checked} agree={agree}"
            if not agree:
                failures += 1
                print(f"  MISMATCH vs bean-check for {case.name}")

        if record:
            baseline.write_text(digest, encoding="utf-8")
            print(f"recorded {case.name:20} errors={our_error_count} {elapsed_ms:8.1f} ms{note}")
        else:
            if not baseline.exists():
                print(f"MISSING baseline for {case.name}")
                failures += 1
                continue
            expected = baseline.read_text(encoding="utf-8")
            if expected != digest:
                failures += 1
                print(f"DIFFERS  {case.name}")
                for line in list(
                    difflib.unified_diff(
                        expected.splitlines(), digest.splitlines(),
                        "expected", "actual", lineterm="", n=1,
                    )
                )[:20]:
                    print("   " + line)
            else:
                print(f"ok       {case.name:20} errors={our_error_count} {elapsed_ms:8.1f} ms{note}")

    return failures


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["record", "verify"])
    ap.add_argument("--corpus", type=Path, default=REPO / "corpus")
    args = ap.parse_args()

    failures = run(args.corpus, args.corpus / "expected", record=args.action == "record")
    if failures:
        print(f"\n{failures} failure(s)")
        return 1
    print("\nall cases agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
