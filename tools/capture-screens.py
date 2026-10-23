#!/usr/bin/env python3
"""Captures the website's screenshots from the app on an emulator, in both themes, in one command.

    python3 tools/capture-screens.py [--out DIR] [--themes light,dark] [--only home,journal]
                                     [--serial SERIAL] [--package ID] [--dry-run]

It replaces the ten-step recipe in fixtures/site-screenshots/README.md. What it does:

  1. Puts the emulator in the state the set was made in: 1080 x 1920 at 420 dpi, font scale 1,
     the system status bar in demo mode at 09:41, and the clock on 2026-09-18, the day after the
     invented `household` ledger's last entry. Without a fixed clock "This month" on Home would
     show an empty month. Setting the clock needs a rootable image (a `google_apis` emulator, not
     a Play one); on any other device it stops with that reason.
  2. Clears the app's data, copies the ledger in, makes it the open project, and writes the
     appearance for the theme being captured.
  3. Drives the app by what is written on the screen (uiautomator), never by pixel positions, so
     a layout change does not break it, and a renamed label stops it with a clear message.
  4. Takes each screen, and writes `<id>-<theme>-1080.webp` and `<id>-<theme>-540.webp`.
  5. Records what it used in the `capture` block of `manifest.json`.

The screens to capture are the ones listed in `manifest.json` (its `screens` hold the captions and
alt text people wrote; this script never edits them). `--only` captures any screen the script knows,
whether or not the manifest lists it, and leaves the manifest alone.

Needs `adb`, ImageMagick's `magick`, a running emulator with the Expert debug build installed
(`./gradlew :app:installExpertDebug`), and a rootable image. Nothing leaves the machine.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "fixtures" / "site-screenshots" / "household"
DEFAULT_OUT = ROOT / "site" / "assets" / "screenshots"
PACKAGE = "io.github.pintofi.beanbook.expert.debug"
ACTIVITY = "io.github.pintofi.beanbook.app.MainActivity"
# The day the screens are taken: after the household ledger's last entry (2026-09-17), so "This month"
# on Home holds that month's figures. Fixed, so a screenshot taken in any real month looks the same.
CLOCK = "2026-09-18"
STATUS_BAR_TIME = "0941"
SIZE, DENSITY = "1080x1920", "420"


class Stop(Exception):
    """Something the script cannot get past; the message says what to do."""


class Device:
    def __init__(self, serial=None):
        self.prefix = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args, check=True, binary=False):
        result = subprocess.run(self.prefix + list(args), capture_output=True)
        if check and result.returncode != 0:
            raise Stop(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command, check=True):
        return self.run("shell", command, check=check)

    # --- what is on the screen ---------------------------------------------------------------
    def nodes(self):
        self.shell("uiautomator dump /sdcard/capture-ui.xml >/dev/null")
        xml = self.run("exec-out", "cat", "/sdcard/capture-ui.xml", binary=True)
        found = []
        for node in ET.fromstring(xml).iter("node"):
            bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if not bounds:
                continue
            x1, y1, x2, y2 = map(int, bounds.groups())
            found.append((node.get("text", "").strip(), node.get("content-desc", "").strip(),
                          ((x1 + x2) // 2, (y1 + y2) // 2)))
        return found

    def locate(self, label, prefix=False):
        nodes = self.nodes()
        # A freshly booted emulator often shows "System UI isn't responding" over the app. It is not the
        # app's, and it hides what is being photographed, so it is answered the way a person would.
        for text, desc, centre in nodes:
            if text == "Wait" and any(t.startswith("System UI isn't responding") or t.endswith("isn't responding")
                                      for t, _, _ in nodes):
                self.shell(f"input tap {centre[0]} {centre[1]}")
                time.sleep(1.0)
                return self.locate(label, prefix)
        for text, desc, centre in nodes:
            for have in (text, desc):
                if have and (have.startswith(label) if prefix else have == label):
                    return centre
        return None

    def wait_for(self, label, timeout=25, prefix=False):
        end = time.time() + timeout
        while time.time() < end:
            centre = self.locate(label, prefix)
            if centre:
                return centre
            time.sleep(0.7)
        raise Stop(f'"{label}" never appeared on the screen within {timeout} s; the app text may have '
                   "changed, so update the step in tools/capture-screens.py")

    def tap(self, label, prefix=False, timeout=25):
        x, y = self.wait_for(label, timeout, prefix)
        self.shell(f"input tap {x} {y}")
        time.sleep(1.0)

    def scroll_to(self, label, prefix=False, swipes=10):
        """Scrolls a list down until `label` is on screen."""
        for _ in range(swipes):
            if self.locate(label, prefix):
                return
            self.swipe_up()
        raise Stop(f'"{label}" was not found after scrolling')

    def swipe_up(self):
        self.shell("input swipe 540 1300 540 450 350")
        time.sleep(0.8)

    def png(self):
        return self.run("exec-out", "screencap", "-p", binary=True)


# --- preparing the device ----------------------------------------------------------------------

def prepare(dev, package):
    if dev.run("get-state", check=False).strip() != "device":
        raise Stop("no emulator is running; start one (AVD s1_api36) and install the Expert debug build")
    if f"package:{package}" not in dev.shell("pm list packages " + package):
        raise Stop(f"{package} is not installed; run ./gradlew :app:installExpertDebug")
    root = dev.run("root", check=False)
    if "cannot" in root or dev.shell("id").strip().find("uid=0") < 0:
        raise Stop("this device cannot be rooted, so its clock cannot be set; use a google_apis emulator image "
                   "(one without Play), which the capture needs so that 'This month' is the same every time")
    time.sleep(2)
    dev.run("wait-for-device")
    dev.shell("settings put global auto_time 0; settings put global auto_time_zone 0")
    y, m, d = CLOCK.split("-")
    dev.shell(f"date {m}{d}0941{y}.00")
    dev.shell("am broadcast -a android.intent.action.TIME_SET >/dev/null")
    dev.shell(f"wm size {SIZE}; wm density {DENSITY}; settings put system font_scale 1.0")
    dev.shell("settings put global sysui_demo_allowed 1")
    for command in (
        "command enter",
        f"command clock -e hhmm {STATUS_BAR_TIME}",
        "command battery -e level 100 -e plugged false",
        "command network -e wifi show -e level 4 -e fully true",
        "command network -e mobile hide",
        "command notifications -e visible false",
    ):
        dev.shell(f"am broadcast -a com.android.systemui.demo -e {command} >/dev/null")
    date = dev.shell("date +%Y-%m-%d").strip()
    if date != CLOCK:
        raise Stop(f"the device clock is {date}, not {CLOCK}")


def reset_app(dev, package, theme):
    dev.shell(f"am force-stop {package}")
    dev.shell(f"pm clear {package} >/dev/null")
    dev.shell(f"pm grant {package} android.permission.POST_NOTIFICATIONS", check=False)
    dev.shell("rm -rf /data/local/tmp/household")
    dev.run("push", str(FIXTURE), "/data/local/tmp/")
    dev.shell("chmod -R a+rX /data/local/tmp/household")  # pushed as root, read by the app's own user
    dev.shell(
        f"run-as {package} sh -c 'mkdir -p files/projects files/state && cp -r /data/local/tmp/household "
        f"files/projects/ && printf household > files/state/active-project && "
        f"printf \"{theme.upper()}\\nfalse\\n\" > files/state/appearance'"
    )
    dev.shell("rm -rf /data/local/tmp/household")


def start(dev, package):
    dev.shell(f"am start -n {package}/{ACTIVITY} >/dev/null")
    dev.wait_for("Net worth", timeout=60, prefix=True)
    time.sleep(2.5)  # the figures settle


# --- the screens -------------------------------------------------------------------------------
# Each step leaves the app on the screen to photograph. Several build on the one before, so they run
# in this order; `--only` runs the ones asked for in this order too.

def home(dev):
    dev.tap("Home")
    dev.wait_for("Net worth", prefix=True)
    time.sleep(1.5)


def journal(dev):
    dev.tap("Journal")
    dev.wait_for("Search entries", prefix=True)
    time.sleep(1.5)


def entry(dev):
    # An existing entry, opened and cloned: the form as it looks with something in it.
    dev.tap("Journal")
    dev.tap("2026-09-12, Corner grocer", prefix=True)
    dev.tap("Clone")
    dev.wait_for("Save entry")
    time.sleep(1.0)


def preview(dev):
    if not dev.locate("Save entry"):
        entry(dev)
    for _ in range(10):
        if dev.locate("Will be written as", prefix=True):
            break
        dev.swipe_up()
    else:
        raise Stop('"Will be written as" was not found below the entry form')
    time.sleep(0.8)


def close_form(dev):
    """Leaves the cloned entry. It holds typed text, so the app asks whether to discard it."""
    dev.tap("Close the entry form")
    end = time.time() + 3
    while time.time() < end:
        centre = dev.locate("Discard")  # the button; the dialog's title also starts with the word
        if centre:
            dev.shell(f"input tap {centre[0]} {centre[1]}")
            time.sleep(1.0)
            return
        time.sleep(0.5)


def balances(dev):
    dev.tap("Balances")
    dev.wait_for("Filter accounts", prefix=True)
    time.sleep(1.5)


def reports(dev):
    dev.tap("Reports")
    time.sleep(35)  # Fava draws its charts after the page loads; there is nothing on screen to wait for


def documents(dev):
    dev.tap("More")
    dev.tap("Documents", prefix=True)
    dev.tap("Organize")
    time.sleep(1.5)


def more(dev):
    dev.tap("More")
    dev.wait_for("Projects", prefix=True)
    time.sleep(1.0)


def plugins(dev):
    dev.tap("More")
    dev.scroll_to("Plugins", prefix=True)
    dev.tap("Plugins", prefix=True)
    time.sleep(1.5)


# id -> (step, what to do afterwards to get back to a known place): the form is closed after the two
# screens that leave it open.
SCREENS = {
    "home": (home, None),
    "journal": (journal, None),
    "entry": (entry, close_form),
    "preview": (preview, close_form),
    "balances": (balances, None),
    "reports": (reports, None),
    "documents": (documents, None),
    "more": (more, None),
    "plugins": (plugins, None),
}
ORDER = list(SCREENS)


# --- output ------------------------------------------------------------------------------------

def encode(png, out_dir, name):
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as handle:
        handle.write(png)
        path = handle.name
    try:
        subprocess.run(["magick", path, "-strip", "-quality", "82", str(out_dir / f"{name}-1080.webp")], check=True)
        subprocess.run(["magick", path, "-strip", "-resize", "540x960", "-quality", "80",
                        str(out_dir / f"{name}-540.webp")], check=True)
    finally:
        os.unlink(path)


def app_version():
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    return re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--themes", default="light,dark")
    parser.add_argument("--only", help="comma-separated screen ids; any the script knows")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--dry-run", action="store_true", help="check the manifest and the tools, touch no device")
    args = parser.parse_args()

    manifest_path = DEFAULT_OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"screens": []}
    listed = [s["id"] for s in manifest["screens"]]
    wanted = args.only.split(",") if args.only else listed
    if not wanted:
        print("capture: manifest.json lists no screens; add some, or name them with --only", file=sys.stderr)
        return 1
    unknown = [s for s in wanted if s not in SCREENS]
    if unknown:
        print(f"capture: no step for {unknown}; the script knows {ORDER}", file=sys.stderr)
        return 1
    if not shutil.which("magick") or not shutil.which("adb"):
        print("capture: needs adb and ImageMagick's magick on the PATH", file=sys.stderr)
        return 1
    themes = args.themes.split(",")
    order = [s for s in ORDER if s in wanted]
    if args.dry_run:
        print(f"would capture {order} in {themes} into {args.out}")
        return 0

    args.out.mkdir(parents=True, exist_ok=True)
    dev = Device(args.serial)
    try:
        prepare(dev, args.package)
        for theme in themes:
            reset_app(dev, args.package, theme)
            start(dev, args.package)
            for screen in order:
                step, after = SCREENS[screen]
                step(dev)
                encode(dev.png(), args.out, f"{screen}-{theme}")
                print(f"captured {screen}-{theme}")
                if after:
                    after(dev)
    except Stop as stop:
        print(f"capture: {stop}", file=sys.stderr)
        return 1
    finally:
        dev.shell("am broadcast -a com.android.systemui.demo -e command exit >/dev/null", check=False)
        dev.shell("wm size reset; wm density reset", check=False)

    if not args.only:
        manifest["capture"] = {
            "on": time.strftime("%Y-%m-%d"),
            "app": app_version(),
            "commit": git("rev-parse", "HEAD"),
            "device": f"Android emulator, {SIZE} at {DENSITY} dpi, font scale 1.0, en-US, portrait",
            "fixture": "fixtures/site-screenshots/household",
            "clock": CLOCK,
            "themes": themes,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
