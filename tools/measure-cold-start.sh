#!/usr/bin/env bash
# Times the three phases of a cold engine start, separately (SPEC §6, V3).
#
# Not an instrumentation test, deliberately. The numbers only mean anything in a process that
# has not already started Python, and a JUnit suite cannot promise that: run inside the full
# suite, the same code measured a warm start and asserted against a cold-start threshold, which
# is exactly the order-dependence that made KeySecrecyTest report a phantom key leak
# (docs/milestones/m14-part17.md). A measurement that needs a fresh process is a measurement,
# not an invariant, and belongs here.
#
#   tools/measure-cold-start.sh [serial]
#
# M3 measured 7,718 ms end to end on a Galaxy A16 against an 8 s budget and concluded the cost
# was module import. This separates interpreter start-up from import from parsing, which is
# what that conclusion needed and never had.
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"
[ -n "$SERIAL" ] || { echo "usage: $0 <adb-serial>" >&2; exit 2; }
ADB=(adb -s "$SERIAL")

PKG=io.github.pintofi.beanbook.expert.debug
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

echo "=== clearing app data so the interpreter is unpacked from cold"
"${ADB[@]}" shell pm clear "$PKG" >/dev/null
"${ADB[@]}" logcat -c

# One class, on its own, so nothing else has started Python first.
"${ADB[@]}" shell am instrument -w \
    -e class io.github.pintofi.beanbook.app.ColdStartTimingTest \
    "$RUNNER" >/dev/null 2>&1 || true

echo "=== phases"
"${ADB[@]}" logcat -d | grep -E "ColdStart:" || {
    echo "no timings in the log — is the measurement build installed?" >&2
    exit 1
}
