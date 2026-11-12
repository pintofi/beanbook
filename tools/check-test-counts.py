#!/usr/bin/env python3
"""Fails when a number in the README's "Testing and reliability" section is not the repository's.

    python3 tools/check-test-counts.py            check; exit 1 on any difference
    python3 tools/check-test-counts.py --write    rewrite the marked numbers in README.md

The numbers are typed between markers, `<!-- count:KEY -->1,129<!-- /count -->`, and counted here
from the source tree (tracked files plus new ones not yet ignored), without running Gradle:

  jvm-tests, instrumentation-tests   `@Test` functions under <module>/src/test*, src/androidTest*
  python-tests                       `test*` methods of unittest.TestCase classes in engine-local
  balance-cases, display-cases,
  price-cases                        cases in the Beancount-generated fixtures in core-domain
  corpus-ledgers                     directories under corpus/ with a main.beancount
  verify-jobs                        jobs in .github/workflows/verify.yml

These are test functions and cases written in the source, not tests that ran on a given day. A
Kotlin test is a `@Test` (not `@TestOnly`) followed by a function; commented-out, quoted and
`@Ignore`d ones do not count. `jvm-tests` and `instrumentation-tests` change in most pull requests,
so the README states them as a floor, "more than 1,100"; the rule lives here, so editing the README
cannot loosen it. The others are exact.

Every source set that holds tests must be listed in SOURCE_SETS with the command that runs it, and
that command must still appear in the workflow or script named beside it, so a module dropped from
CI cannot go on being claimed. The counting rules are first run on small samples whose answer is
known, so the script cannot quietly count nothing.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
MARKER = re.compile(r"<!-- count:([a-z-]+) -->([0-9,]+)<!-- /count -->")

# source set -> (key, command that runs it, file that must mention the command)
SOURCE_SETS = {
    "core-domain/src/test": ("jvm-tests", ":core-domain:test", ".github/workflows/verify.yml"),
    "core-data/src/test": ("jvm-tests", ":core-data:test", ".github/workflows/verify.yml"),
    "core-engine/src/test": ("jvm-tests", ":core-engine:test", ".github/workflows/verify.yml"),
    "engine-remote/src/test": ("jvm-tests", ":engine-remote:test", ".github/workflows/verify.yml"),
    "app/src/test": ("jvm-tests", ":app:testPlayDebugUnitTest", ".github/workflows/verify.yml"),
    "app/src/androidTest": ("instrumentation-tests", ":app:connectedExpertDebugAndroidTest",
                            "tools/weekly-instrumentation.sh"),
    "app/src/androidTestExpert": ("instrumentation-tests", ":app:connectedExpertDebugAndroidTest",
                                  "tools/weekly-instrumentation.sh"),
    "scanner/src/androidTest": (None, None, None),  # not run in CI; says so in the README
}
# keys stated as "more than N": N is the count rounded down to a multiple of this, strictly below it
FLOORS = {"jvm-tests": 100, "instrumentation-tests": 100}
FIXTURES = {
    "balance-cases": ("beancount-balance-cases.txt", "case "),
    "display-cases": ("beancount-display-cases.txt", "display "),
    "price-cases": ("beancount-price-cases.txt", "price "),
}
FIXTURE_DIR = "core-domain/src/test/resources"

TEST_TOKEN = re.compile(r"(?<![\w.@])@(?:(?:org\.junit|kotlin\.test)\.)?Test(?!\w)")
SKIP_TOKEN = re.compile(r"(?<![\w.@])@(?:\w+\.)*(?:Ignore|Disabled)(?!\w)")
FUN_AFTER = re.compile(r"\s*(?:@[\w.]+(?:\([^)]*\))?\s*|(?:public|private|internal|protected|open|override|"
                       r"suspend|final|static|inline)\s+)*(?:fun\s|void\s)")


def files() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=ROOT, capture_output=True, check=True).stdout.decode()
    return [f for f in out.split("\0") if f and (ROOT / f).is_file()]


def strip_kotlin(text: str) -> str:
    """Blank comments, strings and char literals, keeping every offset and newline."""
    out, i, n = [], 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text[j:j + 2] == "/*": depth += 1; j += 2
                elif text[j:j + 2] == "*/": depth -= 1; j += 2
                else: j += 1
            out.append(re.sub(r"[^\n]", " ", text[i:j])); i = j
        elif text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(re.sub(r"[^\n]", " ", text[i:j])); i = j
        elif text[i] in "\"'":
            q, j = text[i], i + 1
            while j < n and text[j] != q and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i)); i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def count_kotlin(text: str, where: str, notes: list[str]) -> int:
    code = strip_kotlin(text)
    total = 0
    if re.search(SKIP_TOKEN.pattern + r"[^{};]*\bclass\b", code):
        raise SystemExit(f"counts: {where} disables a whole class; teach tools/check-test-counts.py to count it")
    for m in TEST_TOKEN.finditer(code):
        rest = code[m.end():m.end() + 400]
        if not FUN_AFTER.match(rest):
            raise SystemExit(f"counts: cannot place @Test at {where}:{code[:m.start()].count(chr(10)) + 1}")
        run_start = max(code.rfind(c, 0, m.start()) for c in ("\n\n", "{", "}", ";"))
        if SKIP_TOKEN.search(code[run_start:m.start()]) or SKIP_TOKEN.search(rest.split("fun ")[0]):
            notes.append(f"{where} has a disabled test"); continue
        total += 1
    return total


def count_python(text: str) -> int:
    import ast
    tree = ast.parse(text)
    classes = {c.name: c for c in tree.body if isinstance(c, ast.ClassDef)}
    roots = {"TestCase", "IsolatedAsyncioTestCase"}

    def base_names(c):
        return [b.id if isinstance(b, ast.Name) else getattr(b, "attr", "") for b in c.bases]

    def is_case(c, seen=()):
        return any(b in roots or (b in classes and b not in seen and is_case(classes[b], seen + (b,)))
                   for b in base_names(c))

    def methods(c, seen=()):
        found = {}
        for b in base_names(c):
            if b in classes and b not in seen:
                found.update(methods(classes[b], seen + (b,)))
        for f in c.body:
            if isinstance(f, (ast.FunctionDef, ast.AsyncFunctionDef)) and f.name.startswith("test"):
                skipped = any(isinstance(d, ast.Attribute) and d.attr == "skip"
                              or isinstance(d, ast.Call) and getattr(d.func, "attr", "") == "skip"
                              for d in f.decorator_list)
                if skipped: found.pop(f.name, None)
                else: found[f.name] = 1
        return found

    return sum(len(methods(c)) for c in classes.values() if is_case(c))


def self_check() -> None:
    kotlin = '''
    // @Test
    /* @Test /* nested */ @Test */
    val s = "@Test"
    @TestOnly fun helper() {}
    @Ignore @Test fun off() {}
    @Test fun one() {}
    @Test
    @Throws(Exception::class)
    fun two() {}
    '''
    if count_kotlin(kotlin, "self-check", []) != 2:
        raise SystemExit("counts: the Kotlin counting rule failed its own sample")
    python = ("import unittest\nclass A(unittest.TestCase):\n    def test_a(self): pass\n"
              "    @unittest.skip('x')\n    def test_b(self): pass\n    def helper(self): pass\n"
              "class B(A):\n    def test_c(self): pass\nclass C:\n    def test_d(self): pass\n")
    if count_python(python) != 3:  # A: a. B: a and c. C is not a TestCase
        raise SystemExit("counts: the Python counting rule failed its own sample")


def count_all() -> tuple[dict[str, int], list[str]]:
    counts: dict[str, int] = {}
    errors: list[str] = []
    notes: list[str] = []
    tracked = files()
    per_set: dict[str, int] = {}
    for f in tracked:
        m = re.match(r"^([^/]+)/src/(test|androidTest[A-Za-z]*)/.+\.(kt|java)$", f)
        if not m:
            continue
        n = count_kotlin((ROOT / f).read_text(errors="replace"), f, notes)
        if n:
            key = f"{m.group(1)}/src/{m.group(2)}"
            per_set[key] = per_set.get(key, 0) + n
    for source_set, n in sorted(per_set.items()):
        if source_set not in SOURCE_SETS:
            errors.append(f"counts: {source_set} has {n} test functions and no line in SOURCE_SETS "
                          f"of tools/check-test-counts.py says where it runs")
            continue
        key, command, runner = SOURCE_SETS[source_set]
        if key is None:
            continue
        if command not in (ROOT / runner).read_text():
            errors.append(f"counts: {source_set} is said to run by {command}, but {runner} no longer has it")
        counts[key] = counts.get(key, 0) + n
    counts["python-tests"] = sum(
        count_python((ROOT / f).read_text())
        for f in tracked if re.match(r"^engine-local/src/test/python/test[^/]*\.py$", f))
    for key, (name, prefix) in FIXTURES.items():
        lines = (ROOT / FIXTURE_DIR / name).read_text().splitlines()
        counts[key] = sum(1 for line in lines if line.startswith(prefix))
    counts["corpus-ledgers"] = sum(1 for f in tracked if re.match(r"^corpus/[^/]+/main\.beancount$", f))
    try:
        import yaml
    except ImportError:
        raise SystemExit("pyyaml is required: pip install pyyaml")
    counts["verify-jobs"] = len(yaml.safe_load((ROOT / ".github/workflows/verify.yml").read_text())["jobs"])
    if notes:
        print("counts: left out, disabled: " + "; ".join(notes))
    return counts, errors


def stated(key: str, actual: int) -> tuple[int, str]:
    """The number the README should carry for this key, and the words that must precede it."""
    step = FLOORS.get(key)
    if step is None:
        return actual, ""
    return ((actual - 1) // step) * step, "more than "


def main() -> int:
    write = "--write" in sys.argv[1:]
    self_check()
    counts, errors = count_all()
    text = README.read_text()
    seen: set[str] = set()
    changes: list[str] = []

    def fix(m: re.Match) -> str:
        key, old = m.group(1), m.group(2)
        line = text.count("\n", 0, m.start()) + 1
        if key not in counts:
            errors.append(f"counts: README.md:{line} uses the unknown key {key}")
            return m.group(0)
        seen.add(key)
        want, lead = stated(key, counts[key])
        new = f"{want:,}"
        if lead and not text[:m.start()].endswith(lead):
            errors.append(f"counts: README.md:{line} {key} is a floor and must be written \"{lead}{old}\"")
        if new != old:
            changes.append(f"README.md:{line} {key} {old} -> {new}")
            return m.group(0).replace(f">{old}<", f">{new}<")
        return m.group(0)

    fixed = MARKER.sub(fix, text)
    for key in counts:
        if key not in seen:
            errors.append(f"counts: README.md has no <!-- count:{key} --> marker; if the sentence was "
                          f"reworded, put the marker back or update tools/check-test-counts.py")
    if write and fixed != text:
        README.write_text(fixed)
    if write:
        for change in changes:
            print(change)
        errors = [e for e in errors if "differs" in e]
        stale = []
    else:
        stale = [f"counts: {c}; run tools/check-test-counts.py --write" for c in changes]
    for e in errors + stale:
        print(e, file=sys.stderr)
    if errors or (stale and not write):
        return 1
    print("counts: README numbers agree with the repository: "
          + ", ".join(f"{k} {counts[k]:,}" for k in sorted(counts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
