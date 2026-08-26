#!/usr/bin/env bash
# Runs every `run:` step of a workflow, locally, in order.
#
# Parsing a workflow proves it loads. It does not prove a step works: a task name can be
# wrong, a file can be missing, a grep can be malformed. This executes them, which is the
# only way to find that out short of pushing a tag.
#
# It is not a GitHub runner. Steps that need `actions/*`, secrets, or the network are skipped
# and named, so the gap between "passes here" and "passes there" stays visible.
#
#   tools/run-ci-locally.sh .github/workflows/verify.yml
set -uo pipefail

WORKFLOW="${1:-.github/workflows/verify.yml}"
PASS=0; FAIL=0; SKIP=0
FAILED_STEPS=()

while IFS=$'\t' read -r job name script; do
    if [ -z "$script" ]; then
        continue
    fi
    printf '\n=== [%s] %s\n' "$job" "$name"
    decoded="${script//$'\001'/$'\n'}"
    if bash -c "$decoded" > /tmp/ci-step.log 2>&1; then
        echo "    PASS"
        PASS=$((PASS + 1))
    else
        echo "    FAIL (exit $?)"
        tail -6 /tmp/ci-step.log | sed 's/^/    | /'
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
