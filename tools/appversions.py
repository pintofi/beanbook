"""What the app actually uses, read from the build files. Shared by check-versions.py and badges.py.

  * CPython's minor version, Chaquopy, minSdk and compileSdk: gradle/libs.versions.toml
  * Beancount and watchfiles (cross-built): engine-local/wheels.lock
  * Fava, beanquery, beangulp and the rest of the Python closure: engine-local/requirements.lock
  * Gradle: gradle/wrapper/gradle-wrapper.properties; the JDK: jvmToolchain(...) in the build files
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The Android release a minimum API level is. Extend it when minSdk moves past the end.
ANDROID = {29: "10", 30: "11", 31: "12", 32: "12", 33: "13", 34: "14", 35: "15", 36: "16", 37: "17"}


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _toml(key: str) -> str:
    match = re.search(rf'^{key}\s*=\s*"([^"]+)"', read("gradle/libs.versions.toml"), re.M)
    if not match:
        sys.exit(f"gradle/libs.versions.toml has no {key}")
    return match.group(1)


def _pins() -> dict:
    pins = {}
    for line in read("engine-local/requirements.lock").splitlines():
        m = re.match(r"^([A-Za-z0-9_.-]+)==(\S+)", line)
        if m:
            pins[norm(m.group(1))] = m.group(2)
    for line in read("engine-local/wheels.lock").splitlines():
        m = re.match(r"^([A-Za-z0-9_.-]+)\s+(\S+)\s+[0-9a-f]{64}\s*$", line)
        if m:
            pins[norm(m.group(1))] = m.group(2)
    return pins


def _jdk() -> str:
    jdks = set()
    for build in ROOT.glob("**/build.gradle.kts"):
        if "/build/" in str(build):
            continue
        jdks.update(re.findall(r"jvmToolchain\((\d+)\)", build.read_text(encoding="utf-8")))
    if len(jdks) != 1:
        sys.exit(f"the build files name more than one JDK toolchain: {sorted(jdks)}")
    return jdks.pop()


pins = _pins()
python_minor = _toml("python")
chaquopy = _toml("chaquopy")
min_sdk = int(_toml("minSdk"))
compile_sdk = _toml("compileSdk")
gradle = re.search(r"gradle-([0-9.]+)-bin", read("gradle/wrapper/gradle-wrapper.properties")).group(1)
jdk = _jdk()
if min_sdk not in ANDROID:
    sys.exit(f"minSdk {min_sdk} has no Android release name in tools/appversions.py; add it")
android_release = ANDROID[min_sdk]


def pin(name: str) -> str:
    if name not in pins:
        sys.exit(f"{name} is not in engine-local/requirements.lock or wheels.lock")
    return pins[name]
