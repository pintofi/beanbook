#!/usr/bin/env bash
# Runs every `run:` step of a workflow, locally, in order.
#
# Parsing a workflow proves it loads. It does not prove a step works: a task name can be
# wrong, a file can be missing, a grep can be malformed. This executes them, which is the
# only way to find that out short of pushing a tag.
#
# It is not a GitHub runner. Steps that need `actions/*` or secrets do not run at all, and
# the gap between "passes here" and "passes there" stays visible. What it does model is
# `$GITHUB_ENV`: a step that exports a variable through it is visible to later steps in the
# same job, as on a runner, and not to other jobs. Without that, a workflow that is correct
# on GitHub fails here for a reason that does not exist there — which is worse than useless,
# because the obvious fix is to damage the workflow.
#
# Three steps of verify.yml cannot pass on a developer machine, and fail in a way that reads
# like a repo defect rather than a missing tool. Checked 2026-08-30; none is a reason to
# change the workflow:
#
#   [wheels] Rust with the Android targets  -- needs rustup, which a distro `cargo` is not.
#   [wheels] Build                          -- watchfiles is a Rust extension, so it cascades.
#   [reproducible] Build twice and compare  -- $RUNNER_TEMP is provided by GitHub, not by us.
#
# So the useful local result is "21 passed, 3 failed, and those 3". A clean run is 24.
#
#   tools/run-ci-locally.sh .github/workflows/verify.yml
set -uo pipefail

WORKFLOW="${1:-.github/workflows/verify.yml}"
PASS=0; FAIL=0; SKIP=0
FAILED_STEPS=()
STATE="$(mktemp -d)"
trap 'rm -rf "$STATE"' EXIT
CURRENT_JOB=""

while IFS=$'\t' read -r job name script; do
    if [ -z "$script" ]; then
        continue
    fi
    # Each job starts with an empty environment file, as a fresh runner would.
    if [ "$job" != "$CURRENT_JOB" ]; then
        CURRENT_JOB="$job"
        : > "$STATE/env"
    fi

    printf '\n=== [%s] %s\n' "$job" "$name"
    decoded="${script//$'\001'/$'\n'}"
    if env GITHUB_ENV="$STATE/env" bash -c '
            set -a; . "$1"; set +a
            shift
            eval "$1"
        ' _ "$STATE/env" "$decoded" > "$STATE/step.log" 2>&1; then
        echo "    PASS"
        PASS=$((PASS + 1))
    else
        echo "    FAIL (exit $?)"
        tail -6 "$STATE/step.log" | sed 's/^/    | /'
        FAIL=$((FAIL + 1))
        FAILED_STEPS+=("[$job] $name")
    fi
done < <(python3 - "$WORKFLOW" <<'PY'
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1]))
for job, spec in doc["jobs"].items():
    if "uses" in spec:
        continue
    for step in spec.get("steps", []):
        if "run" not in step:
            continue
        name = step.get("name", "(unnamed)")
        script = step["run"]
        # Tabs separate the fields, so a newline inside a script would break the read loop.
        # Encoded here and decoded by the shell.
        print(f"{job}\t{name}\t" + script.replace("\n", "\u0001"))
PY
)

echo
echo "----------------------------------------"
echo "$PASS passed, $FAIL failed"
for step in "${FAILED_STEPS[@]:-}"; do [ -n "$step" ] && echo "  failed: $step"; done
exit $((FAIL > 0))
