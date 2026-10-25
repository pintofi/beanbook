#!/usr/bin/env bash
# Runs the instrumentation tests the weekly workflow runs, on the emulator that is already up,
# then copies the screenshots the tests wrote to the device into ./screenshots.
#
#   tools/weekly-instrumentation.sh [true|false]     # true: draw the screenshots again, not compare
#
# The tests' exit status is the script's, so a failure still fails the job after the copy.
#
# Three tests are left out because they are run by hand: one drives the system document picker,
# and two need a file pushed to the device first. Each fails on purpose when its set-up is missing.
set -u
update="${1:-false}"
skip=io.github.pintofi.beanbook.app.SafExportTest,io.github.pintofi.beanbook.app.ScriptWithMissingModuleTest,io.github.pintofi.beanbook.app.ZakatExtensionTest

adb shell rm -rf /sdcard/Download/beanbook-screenshots
./gradlew :app:connectedExpertDebugAndroidTest \
  -Pbeancount.wheelDir="${BCA_WHEEL_DIR:?}" \
  -Pandroid.testInstrumentationRunnerArguments.updateScreenshots="$update" \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$skip"
status=$?

mkdir -p screenshots
adb pull /sdcard/Download/beanbook-screenshots/. screenshots/ || true
exit "$status"
