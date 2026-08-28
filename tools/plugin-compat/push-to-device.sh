#!/usr/bin/env bash
# Pushes the fetched catalogue to the device for CatalogPluginTest.
#
#     python3 tools/plugin-compat/fetch.py --out build/plugin-sources
#     python3 tools/plugin-compat/run_catalog.py --sources build/plugin-sources
#     ANDROID_SERIAL=... tools/plugin-compat/push-to-device.sh
#
# Everything the desktop run could load, not one hand-named directory. The device test reads
# the manifest and runs whatever is there, so a plugin that starts working on the desktop
# reaches the device run without anyone remembering to add it.
#
# Not bundled into the test APK on purpose: the repository does not carry a copy of somebody
# else's source, and the licence of each catalogued plugin is recorded rather than assumed.
set -euo pipefail

SOURCES="${1:-build/plugin-sources}"
MANIFEST="$SOURCES/device-manifest.json"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

if [ ! -f "$MANIFEST" ]; then
    echo "no manifest at $MANIFEST — run fetch.py then run_catalog.py first" >&2
    exit 1
fi

# /data/local/tmp survives the app being reinstalled. The Gradle test task uninstalls the
# app when it finishes, so anything pushed into the app's own storage beforehand is gone
# before the test ever looks for it.
DEST="/data/local/tmp/bca-catalog"
"${ADB[@]}" shell "rm -rf $DEST && mkdir -p $DEST"

python3 - "$MANIFEST" <<'PY' | while IFS= read -r path; do
import json, sys
seen = set()
for entry in json.load(open(sys.argv[1])):
    if entry["package"] not in seen:
        seen.add(entry["package"])
        print(entry["package"])
PY
    "${ADB[@]}" push "$path" "$DEST/" >/dev/null
    echo "  pushed $(basename "$path")"
done

"${ADB[@]}" push "$MANIFEST" "$DEST/manifest.json" >/dev/null
"${ADB[@]}" shell "chmod -R a+rX $DEST"
echo "pushed $(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))))" "$MANIFEST") catalogue entries to $DEST"
