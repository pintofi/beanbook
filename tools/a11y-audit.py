"""Audits the accessibility tree a screen reader actually consumes.

`AccessibilityTest` checks the Compose semantics tree from inside the app. This reads the
**platform** accessibility node tree from outside, with TalkBack running — the same tree
TalkBack walks — and checks the things a semantics-tree test structurally cannot see:

* **state**, because a switch that is not `checkable` is announced as a label with no on/off,
  and the user cannot tell what it is currently set to;
* **double announcements**, where a container carries a contentDescription *and* exposes its
  children's text, so everything is read twice;
* **unlabelled focusable nodes**, which are announced as their class name or as nothing.

    python3 tools/a11y-audit.py --serial emulator-5554 --screen "Appearance"
"""

from __future__ import annotations

import argparse
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

WORK = Path("/tmp")


def dump(serial: str) -> ET.Element:
    adb = ["adb"] + (["-s", serial] if serial else [])
    subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/a11y.xml"],
                   capture_output=True, check=False)
    local = WORK / "a11y.xml"
    subprocess.run(adb + ["pull", "/sdcard/a11y.xml", str(local)], capture_output=True)
    return ET.parse(local).getroot()


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    return tuple(int(v) for v in re.findall(r"-?\d+", node.get("bounds", "0,0,0,0")))


def spoken(node: ET.Element) -> str:
    """What a screen reader would read for this node itself."""
    return (node.get("content-desc") or node.get("text") or "").strip()


def announced(node: ET.Element) -> str:
    """What a screen reader would read when this node takes focus.

    Descendants included, because the platform composes a focusable node's description from
    its children. `OutlinedButton { Text("Choose folder") }` exports a clickable `View` whose
    own text is empty and whose child `TextView` carries the label — checking only the node
    itself reported every button in the app as unlabelled, which was wrong and would have
    made this tool useless within a day.
    """
    own = spoken(node)
    if own:
        return own
    return " ".join(
        part for part in (spoken(child) for child in node.iter("node") if child is not node)
        if part
    ).strip()


def is_leaf(node: ET.Element) -> bool:
    return not list(node.iter("node"))[1:]


def audit(root: ET.Element) -> dict[str, list[str]]:
    findings: dict[str, list[str]] = {
        "unlabelled": [], "state": [], "duplicate": [],
    }
    nodes = list(root.iter("node"))

    focusable = [n for n in nodes if n.get("clickable") == "true"
                 or n.get("focusable") == "true" and spoken(n)]

    for node in focusable:
        label = announced(node)
        klass = node.get("class", "?")
        if node.get("clickable") == "true" and not label:
            findings["unlabelled"].append(f"{klass} at {node.get('bounds')}")

        # A toggle that is not checkable is read as a label with no state. The user is told
        # what the control is and not what it is set to.
        looks_like_toggle = "Switch" in klass or "CheckBox" in klass or "RadioButton" in klass
        if looks_like_toggle and node.get("checkable") != "true":
            findings["state"].append(f"{klass} '{label}' is not checkable")

    # A container that both describes itself and exposes readable children is read twice.
    for node in nodes:
        described = (node.get("content-desc") or "").strip()
        if not described:
            continue
        child_text = " ".join(
            (c.get("text") or "").strip() for c in node.iter("node") if c is not node
        ).strip()
        # No length guard. It was `len(child_text) > 12`, which let "Reports" / "Reports"
        # through — and short duplicates are the common case, because they come from setting
        # a contentDescription on a control that already has a visible label.
        if child_text and child_text in described:
            findings["duplicate"].append(
                f"'{described[:60]}…' repeats its children's text"
            )

    # Reading order: top to bottom, left to right. A node that jumps back up the screen after
    # a lower one is read out of order.
    # There is deliberately no reading-order rule.
    #
    # An earlier version had one, and it was measuring the wrong thing: `uiautomator dump`
    # emits the node tree in **hierarchy** order, and TalkBack computes its own traversal
    # order, largely geometric, from `traversalBefore`/`traversalAfter` relations the dump
    # does not expose. The rule confidently reported the top app bar as "read last" on every
    # screen, and two attempts to fix a defect that may not exist changed nothing — because
    # the measurement never reflected what a screen reader does.
    #
    # Checking traversal order properly needs an AccessibilityService that records focus as
    # it moves. Recorded as N14 rather than approximated here: a rule that reports something
    # it cannot measure is worse than no rule.

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="")
    parser.add_argument("--screen", default="(current screen)")
    args = parser.parse_args()

    findings = audit(dump(args.serial))
    total = sum(len(v) for v in findings.values())

    print(f"== {args.screen}")
    titles = {
        "unlabelled": "Clickable with nothing to announce",
        "state": "Toggle whose state is not exposed",
        "duplicate": "Announced twice",
    }
    for key, items in findings.items():
        if not items:
            continue
        print(f"  {titles[key]}:")
        for item in dict.fromkeys(items):
            print(f"    - {item}")
    if total == 0:
        print("  nothing found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
