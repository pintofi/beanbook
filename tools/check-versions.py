#!/usr/bin/env python3
"""Fails when a version written in the README, the website or the docs is not the one the app uses.

    python3 tools/check-versions.py

The versions people read (Beancount 3.2.3 on the home page, a Fava badge, the Android level in the
README) are typed by hand, so they go stale the day a dependency is bumped. This reads what the
build actually pins and compares each claim to it:

  * CPython's minor version, Chaquopy, minSdk and compileSdk: gradle/libs.versions.toml
  * Beancount and watchfiles (cross-built): engine-local/wheels.lock
  * Fava, beanquery, beangulp and the rest of the Python closure: engine-local/requirements.lock
  * Gradle: gradle/wrapper/gradle-wrapper.properties; the JDK: jvmToolchain(...) in the build files

Where a claim is checked:
  site/index.html, site/download.html          the versions list, the Android level, the structured data
  README.md, docs/assets/badges/*.svg          badge alt text and the title and label inside each badge
  CONTRIBUTING.md                              JDK, Gradle, compileSdk, host CPython
  docs/architecture.md, docs/on-demand-packages.md   the pinned-versions table and the CPython mentions
  app/src/main/assets/licences.json            every Python component's version against the locks

If a sentence is reworded so a check can no longer find it, that is reported too, so a check cannot
quietly stop checking. Packages the Expert edition downloads on demand (protobuf, numpy and the like)
are not pinned in the app and are not compared. The text drawn inside a badge image is outlines, not text, and cannot be read
here: only its title and label are compared. Historical records (compatibility runs, the changelog)
state the versions they were made with and are left alone.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
errors: list[str] = []


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


# --- what the app uses ---------------------------------------------------------------------------

def toml(key: str) -> str:
    match = re.search(rf'^{key}\s*=\s*"([^"]+)"', read("gradle/libs.versions.toml"), re.M)
    if not match:
        sys.exit(f"gradle/libs.versions.toml has no {key}")
    return match.group(1)


pins: dict[str, str] = {}
for line in read("engine-local/requirements.lock").splitlines():
    m = re.match(r"^([A-Za-z0-9_.-]+)==(\S+)", line)
    if m:
        pins[norm(m.group(1))] = m.group(2)
for line in read("engine-local/wheels.lock").splitlines():
    m = re.match(r"^([A-Za-z0-9_.-]+)\s+(\S+)\s+[0-9a-f]{64}\s*$", line)
    if m:
        pins[norm(m.group(1))] = m.group(2)

python_minor = toml("python")
chaquopy = toml("chaquopy")
min_sdk = int(toml("minSdk"))
compile_sdk = toml("compileSdk")
gradle = re.search(r"gradle-([0-9.]+)-bin", read("gradle/wrapper/gradle-wrapper.properties")).group(1)
jdks = set()
for build in ROOT.glob("**/build.gradle.kts"):
    if "/build/" in str(build):
        continue
    jdks.update(re.findall(r"jvmToolchain\((\d+)\)", build.read_text(encoding="utf-8")))
if len(jdks) != 1:
    sys.exit(f"the build files name more than one JDK toolchain: {sorted(jdks)}")
jdk = jdks.pop()

ANDROID = {29: "10", 30: "11", 31: "12", 32: "12", 33: "13", 34: "14", 35: "15", 36: "16", 37: "17"}
if min_sdk not in ANDROID:
    sys.exit(f"minSdk {min_sdk} has no Android release name in tools/check-versions.py; add it")
android_release = ANDROID[min_sdk]


# --- the claims ----------------------------------------------------------------------------------

def expect(path: str, pattern: str, want: str, what: str, source: str, group: int = 1) -> None:
    found = list(re.finditer(pattern, read(path), re.M | re.S))
    if not found:
        errors.append(f"{path}: cannot find the {what} (pattern {pattern!r}); if the sentence was reworded, update tools/check-versions.py")
        return
    for m in found:
        if m.group(group) != want:
            errors.append(f"{path}: {what} says {m.group(group)}, the app uses {want} ({source})")


def pin(name: str) -> str:
    if name not in pins:
        sys.exit(f"{name} is not in engine-local/requirements.lock or wheels.lock")
    return pins[name]


LIBS = "gradle/libs.versions.toml"
REQ = "engine-local lock files"

for label, name in (("Beancount", "beancount"), ("Fava", "fava"), ("beanquery", "beanquery"), ("beangulp", "beangulp")):
    expect("site/index.html", rf"<dt>{label}</dt><dd>([^<]+)</dd>", pin(name), f"{label} in the home page's versions list", REQ)
expect("site/index.html", r"<dt>CPython</dt><dd>([^<]+)</dd>", python_minor, "CPython in the home page's versions list", LIBS)
expect("site/index.html", r"API (\d+) or later", str(min_sdk), "the API level", LIBS)
expect("site/index.html", r'<span data-roll>(\d+)</span>\+</strong>\s*<span class="figure-unit">Android', android_release, "the Android release", f"minSdk {min_sdk}")
expect("site/index.html", r'"operatingSystem": "Android (\d+) or later"', android_release, "the structured data's Android release", f"minSdk {min_sdk}")
expect("site/download.html", r"Android (\d+) \(API \d+\) or later", android_release, "the Android release", f"minSdk {min_sdk}")
expect("site/download.html", r"Android \d+ \(API (\d+)\) or later", str(min_sdk), "the API level", LIBS)
expect("site/download.html", r'<span data-roll>(\d+)</span>\+', android_release, "the Android release figure", f"minSdk {min_sdk}")
expect("site/download.html", r"needs Android (\d+) or later", android_release, "the Android release in the description", f"minSdk {min_sdk}")

expect("README.md", r'alt="Beancount ([\d.]+)"', pin("beancount"), "the Beancount badge", REQ)
expect("README.md", r'alt="Fava ([\d.]+)"', pin("fava"), "the Fava badge", REQ)
expect("README.md", r'alt="CPython ([\d.]+)"', python_minor, "the CPython badge", LIBS)
expect("README.md", r'alt="Android (\d+) or later \(API \d+\)"', android_release, "the Android badge", f"minSdk {min_sdk}")
expect("README.md", r'alt="Android \d+ or later \(API (\d+)\)"', str(min_sdk), "the Android badge's API level", LIBS)
expect("README.md", r"host CPython (3\.\d+)", python_minor, "the host CPython", LIBS)

for badge, pattern, want, source in (
    ("beancount", r"<title>Beancount: ([\d.]+)</title>", pin("beancount"), REQ),
    ("fava", r"<title>Fava: ([\d.]+)</title>", pin("fava"), REQ),
    ("python", r"<title>CPython: ([\d.]+)</title>", python_minor, LIBS),
    ("android", r"<title>Android: (\d+)\+ \(API \d+\)</title>", android_release, f"minSdk {min_sdk}"),
    ("android", r"<title>Android: \d+\+ \(API (\d+)\)</title>", str(min_sdk), LIBS),
):
    path = f"docs/assets/badges/{badge}.svg"
    expect(path, pattern, want, f"the {badge} badge's title", source)
    label = pattern.replace("<title>", 'aria-label="').replace("</title>", '"')
    expect(path, label, want, f"the {badge} badge's label", source)

expect("CONTRIBUTING.md", r"host CPython (3\.\d+)", python_minor, "the host CPython", LIBS)
expect("CONTRIBUTING.md", r"\*\*JDK (\d+)\.\*\*", jdk, "the JDK", "jvmToolchain in the build files")
expect("CONTRIBUTING.md", r"\*\*Gradle ([0-9.]+)\*\*", gradle, "Gradle", "gradle-wrapper.properties")
expect("CONTRIBUTING.md", r"`compileSdk` (\d+)", compile_sdk, "compileSdk", LIBS)
expect("CONTRIBUTING.md", r"platforms;android-(\d+)\.0", compile_sdk, "the platform package", LIBS)

ARCH = "docs/architecture.md"
expect(ARCH, r"^\|\s*Chaquopy\s*\|\s*([0-9.]+)", chaquopy, "Chaquopy in the pinned-versions table", LIBS)
expect(ARCH, r"^\|\s*CPython\s*\|\s*(3\.\d+)", python_minor, "CPython in the pinned-versions table", LIBS)
expect(ARCH, r"CPython (3\.\d+) \(Chaquopy\)", python_minor, "CPython in the diagram", LIBS)
for label, name in (("Beancount", "beancount"), ("Fava", "fava"), ("Beanquery", "beanquery"), ("Beangulp", "beangulp"),
                    ("watchfiles", "watchfiles"), ("cheroot", "cheroot"), ("simplejson", "simplejson")):
    expect(ARCH, rf"^\|\s*{label}\s*\|\s*([0-9][0-9.]*)", pin(name), f"{label} in the pinned-versions table", REQ)
expect("docs/on-demand-packages.md", r"embeds \*\*CPython (3\.\d+)\*\*", python_minor, "the embedded CPython", LIBS)

licences = json.loads(read("app/src/main/assets/licences.json"))
for component in licences["components"]:
    if component.get("kind") != "python":
        continue
    name = norm(component["name"])
    if name in pins and component.get("version") != pins[name]:
        errors.append(
            f"app/src/main/assets/licences.json: {component['name']} is {component.get('version')}, "
            f"the app uses {pins[name]}; regenerate with tools/licences.py"
        )

if errors:
    print("\n".join(f"versions: {e}" for e in errors))
    sys.exit(1)
print(
    f"versions agree: CPython {python_minor}, Chaquopy {chaquopy}, Beancount {pin('beancount')}, Fava {pin('fava')}, "
    f"beanquery {pin('beanquery')}, beangulp {pin('beangulp')}, Android {android_release} (API {min_sdk}), "
    f"Gradle {gradle}, JDK {jdk}"
)
