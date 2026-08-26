"""Checks that every GitHub workflow is valid YAML and declares what it claims to.

This exists because `verify.yml` was **malformed from the day it was written** and nobody
noticed for thirteen milestones. A `run:` step contained `echo "TODO(M13): ..."`, and a plain
YAML scalar cannot contain `": "` — so the file never parsed, GitHub never loaded it, and CI
never ran. Every gate in it (the trustAll scan, the escaped-template scan, the SSL-proceed
check, the process-separation check) had only ever run because someone invoked it by hand.

A gate that has never executed is not a gate. This is the check that would have caught it,
and it runs in CI and locally:

    python3 tools/check-workflows.py
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    print("pyyaml is required: pip install pyyaml", file=sys.stderr)
    raise SystemExit(2)

REPO = Path(__file__).resolve().parents[1]
WORKFLOWS = REPO / ".github/workflows"


def main() -> int:
    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not files:
        print("no workflows found", file=sys.stderr)
        return 1

    problems: list[str] = []
    for path in files:
        try:
            document = yaml.safe_load(path.read_text())
        except yaml.YAMLError as error:
            first = str(error).splitlines()[0]
            problems.append(f"{path.name}: does not parse — {first}")
            continue

        if not isinstance(document, dict):
            problems.append(f"{path.name}: is not a mapping")
            continue
        # `on` is parsed by YAML 1.1 as the boolean True, which is a well-known trap and
        # another way a workflow can look fine and do nothing.
        if "on" not in document and True not in document:
            problems.append(f"{path.name}: has no triggers")
        jobs = document.get("jobs")
        if not isinstance(jobs, dict) or not jobs:
            problems.append(f"{path.name}: declares no jobs")
            continue

        for name, job in jobs.items():
            if not isinstance(job, dict):
                problems.append(f"{path.name}: job '{name}' is not a mapping")
                continue
            if "uses" in job:
                continue  # a reusable-workflow call needs no runner or steps
            if "runs-on" not in job:
                problems.append(f"{path.name}: job '{name}' has no runs-on")
            if not job.get("steps"):
                problems.append(f"{path.name}: job '{name}' has no steps")

    for problem in problems:
        print(f"::error::{problem}")
        print(problem, file=sys.stderr)

    if problems:
        return 1
    print(f"{len(files)} workflows parse and declare jobs with steps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
