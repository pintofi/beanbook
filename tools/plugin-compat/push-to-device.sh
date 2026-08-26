#!/usr/bin/env bash
# Pushes a fetched catalogued plugin to the device for CatalogPluginTest.
#
#     python3 tools/plugin-compat/fetch.py --out build/plugin-sources
#     ANDROID_SERIAL=... tools/plugin-compat/push-to-device.sh
#
# Not bundled into the test APK on purpose: the repository does not carry a copy of somebody
# else's source, and the licence of each catalogued plugin is recorded rather than assumed.
set -euo pipefail

SOURCE="${1:-build/plugin-sources/autobean/autobean-0.2.3/autobean}"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

if [ ! -d "$SOURCE" ]; then
    echo "no plugin source at $SOURCE — run tools/plugin-compat/fetch.py first" >&2
    exit 1
fi

# /data/local/tmp survives the app being reinstalled. The Gradle test task uninstalls the
# app when it finishes, so anything pushed into the app's own storage beforehand is gone
# before the test ever looks for it.
DEST="/data/local/tmp/bca-catalog"
"${ADB[@]}" shell "rm -rf $DEST && mkdir -p $DEST"
"${ADB[@]}" push "$SOURCE" "$DEST/"
"${ADB[@]}" shell "chmod -R a+rX $DEST"
echo "pushed $(basename "$SOURCE") to $DEST"
