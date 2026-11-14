#!/usr/bin/env python3
"""Writes core-domain/src/test/resources/beancount-link-cases.txt from real Beancount and Python.

    python3 tools/gen-link-cases.py            rewrite the fixture
    python3 tools/gen-link-cases.py --check    exit 1 if it is not what this script produces now

The import follows an `include` glob and an `option "documents"` folder without running Beancount,
so those two rules are written again in Kotlin (LinkGlob, LedgerLinks). This builds one folder tree,
asks Python's glob (what Beancount calls for an include) and Beancount's own find_documents (what its
documents plugin reads) what they find in it, and records the answers. LedgerLinksCasesTest rebuilds
the same tree, runs the Kotlin rules, and requires the same answers, so the two cannot drift apart.

Needs `pip install beancount==3.2.3` (the version the app bundles).
"""
import glob
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core-domain/src/test/resources/beancount-link-cases.txt"

TREE = [
    "main.bean", "a.bean", "B.bean", ".hidden.bean", "notes.txt",
    "2024/jan.bean", "2024/feb.bean", "2024/.dot.bean", "2024/q1/mar.bean", "2024/q1/deep/x.bean",
    "2025/jan.bean", "shared/accounts.bean", "shared/.git/config", ".git/HEAD",
    "data[1]/x.bean", "v1.bean", "v2.bean", "v10.bean", "vA.bean",
    "docs/Assets/Bank/2024-01-05.receipt.pdf", "docs/Assets/Bank/2024-01-06 statement.pdf",
    "docs/Assets/Bank/notes.pdf", "docs/Assets/Bank/2024-01-05", "docs/Assets/Bank/2024-01-07x.txt",
    "docs/Assets/Bank/Savings/2024-02-01.slip.pdf", "docs/Expenses/Food/2024-03-01.menu.jpg",
    "docs/Expenses/2024-03-02.loose.pdf", "docs/Assets/2024-03-03.top.pdf",
    "docs/lowercase/Bank/2024-03-04.bad.pdf", "docs/Assets/Bank/.hidden/2024-04-01.h.pdf",
    "docs/Liabilities/Card/2024-05-01.a.pdf",
]

GLOBS = [
    "*.bean", "2024/*.bean", "2024/**/*.bean", "**/*.bean", "**", "2024/**", "*/*.bean", "v?.bean",
    "v[0-9].bean", "v[!0-9].bean", "v*.bean", "[ab].bean", "[aB].bean", "data[[]1]/*.bean",
    ".*.bean", "*/.dot.bean", "2024/q1/**/x.bean", "nothing/*.bean", "*.pdf", "docs/**/*.pdf",
    "shared/*", "./a.bean", "2024/../a.bean", "2024/q1/../*.bean", "**/q1/*.bean",
]

DOCUMENT_ROOTS = ["docs", "docs/Assets", "docs/Expenses", "nothing"]


def build(base: Path) -> None:
    for rel in TREE:
        target = base / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("x")


def render() -> str:
    from beancount.ops import documents

    lines = ["# Written by tools/gen-link-cases.py from Python's glob and Beancount's find_documents."]
    lines += [f"file {rel}" for rel in TREE]
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        build(base)
        for pattern in GLOBS:
            found = sorted(
                p for p in glob.glob(pattern, root_dir=str(base), recursive=True)
                if (base / p).is_file()
            )
            lines.append(f"glob {pattern} => " + ",".join(os.path.normpath(p) for p in found))
        for root in DOCUMENT_ROOTS:
            entries, _ = documents.find_documents(root, str(base / "main.bean"))
            found = sorted(os.path.relpath(e.filename, base) for e in entries)
            lines.append(f"documents {root} => " + ",".join(found))
    return "\n".join(lines) + "\n"


def main() -> int:
    wanted = render()
    if "--check" in sys.argv[1:]:
        if not OUT.is_file() or OUT.read_text() != wanted:
            print(f"link cases: {OUT.relative_to(ROOT)} is not what real Beancount gives; run tools/gen-link-cases.py",
                  file=sys.stderr)
            return 1
        print("link cases: the fixture agrees with Python's glob and Beancount")
        return 0
    OUT.write_text(wanted)
    print(f"link cases: wrote {len(wanted.splitlines())} lines to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
