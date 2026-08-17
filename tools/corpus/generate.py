#!/usr/bin/env python3
"""Generate the golden corpus.

Deterministic: the same seed always produces byte-identical files, so a diff in a corpus
run means the *engine* changed, never the fixtures. Small cases are committed; the large
one is generated on demand because it is a few MB.

Usage:
    tools/corpus/generate.py [--out corpus] [--large-transactions 25000]
"""

from __future__ import annotations

import argparse
import random
from datetime import date, timedelta
from pathlib import Path

CURRENCIES = ["USD", "EUR", "GBP"]
PAYEES = [
    "Grocer", "Bakery", "Landlord", "Employer", "Transit Authority",
    "Café Öffentlich", "Bücherei", "Pharmacy", "Hardware Store", "Insurer",
]
EXPENSE_ACCOUNTS = [
    "Expenses:Food:Groceries", "Expenses:Food:Restaurant", "Expenses:Home:Rent",
    "Expenses:Transport:Transit", "Expenses:Health:Pharmacy", "Expenses:Home:Utilities",
]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def case_minimal(root: Path) -> None:
    """The smallest thing that is still a real ledger."""
    write(root / "main.beancount", """\
option "title" "Minimal"
option "operating_currency" "USD"

2024-01-01 open Assets:Bank:Checking USD
2024-01-01 open Expenses:Food:Groceries USD
2024-01-01 open Equity:Opening-Balances USD

2024-01-01 * "Opening" "Initial balance"
  Assets:Bank:Checking      1000.00 USD
  Equity:Opening-Balances

2024-01-05 * "Grocer" "Weekly shop"
  Expenses:Food:Groceries      42.30 USD
  Assets:Bank:Checking

2024-01-31 balance Assets:Bank:Checking   957.70 USD
""")


def case_multi_currency(root: Path) -> None:
    """Several currencies, a cost basis, and price directives."""
    write(root / "main.beancount", """\
option "title" "Multi-currency"
option "operating_currency" "USD"

2024-01-01 commodity USD
2024-01-01 commodity EUR
2024-01-01 commodity ACME

2024-01-01 open Assets:Bank:USD        USD
2024-01-01 open Assets:Bank:EUR        EUR
2024-01-01 open Assets:Invest:ACME     ACME
2024-01-01 open Expenses:Travel        USD,EUR
2024-01-01 open Income:Salary          USD
2024-01-01 open Equity:Opening-Balances

2024-01-01 * "Opening"
  Assets:Bank:USD        5000.00 USD
  Assets:Bank:EUR        2000.00 EUR
  Equity:Opening-Balances

2024-02-01 price EUR  1.0850 USD
2024-02-01 price ACME   12.50 USD

2024-02-02 * "Broker" "Buy shares at cost"
  Assets:Invest:ACME        10 ACME {12.50 USD}
  Assets:Bank:USD      -125.00 USD

2024-02-10 * "Hotel" "Trip"
  Expenses:Travel         150.00 EUR
  Assets:Bank:EUR

2024-03-01 price ACME   13.75 USD
""")


def case_multi_file(root: Path) -> None:
    """Includes nested three levels deep, with relative paths throughout."""
    write(root / "main.beancount", """\
option "title" "Multi-file"
option "operating_currency" "USD"

include "files/accounts.beancount"
include "files/2024/transactions.beancount"
""")
    write(root / "files/accounts.beancount", """\
2024-01-01 open Assets:Bank:Checking      USD
2024-01-01 open Expenses:Food:Groceries   USD
2024-01-01 open Expenses:Home:Rent        USD
2024-01-01 open Equity:Opening-Balances   USD
""")
    write(root / "files/2024/transactions.beancount", """\
include "quarters/q1.beancount"

2024-01-01 * "Opening"
  Assets:Bank:Checking     3000.00 USD
  Equity:Opening-Balances
""")
    write(root / "files/2024/quarters/q1.beancount", """\
2024-02-01 * "Landlord" "February rent"
  Expenses:Home:Rent       1200.00 USD
  Assets:Bank:Checking

2024-03-05 * "Grocer" "Shop"
  Expenses:Food:Groceries    55.20 USD
  Assets:Bank:Checking
""")


def case_custom_directives(root: Path) -> None:
    """Everything the app must never silently rewrite (invariant 4)."""
    write(root / "main.beancount", """\
; ============================================================
; A comment block. Invariant 4: this must survive every edit.
; ============================================================
option "title" "Custom directives"
option "operating_currency" "USD"

plugin "beancount.plugins.auto_accounts"

; A custom directive Beancount does not interpret.
2024-01-01 custom "budget" Expenses:Food "monthly"  400.00 USD
2024-01-01 custom "fava-option" "language" "en"

2024-01-01 open Assets:Bank:Checking USD
  institution: "Example Bank"
  account-number: "0001"

2024-01-02 * "Öffentlicher Café" "Non-ASCII payee — em dash, ümlauts"
  ; a comment inside a transaction
  invoice: "documents/2024-01-02.pdf"
  Expenses:Food:Restaurant      12.50 USD
    posting-note: "with a colleague"
  Assets:Bank:Checking

2024-01-03 ! "Flagged" "Needs review"
  Expenses:Misc                 5.00 USD
  Assets:Bank:Checking

2024-01-04 note Assets:Bank:Checking "a note with \\"quotes\\" inside"
2024-01-05 event "location" "Berlin"
2024-01-06 query "recent" "SELECT date, narration WHERE date >= 2024-01-01"

2024-01-06 * "Tabs" "Tab-indented posting and comment"
	; a tab-indented comment inside a transaction is legal
	Expenses:Misc		3.00 USD
	Assets:Bank:Checking

2024-01-07 pad Assets:Bank:Checking Equity:Opening-Balances
2024-01-08 balance Assets:Bank:Checking   1000.00 USD

; trailing comment, no newline issues please
""")


def case_documents(root: Path) -> None:
    """Document directives plus the files they point at (invariant 5)."""
    write(root / "main.beancount", """\
option "title" "Documents"
option "operating_currency" "USD"
option "documents" "documents"

2024-01-01 open Assets:Bank:Checking USD
2024-01-01 open Expenses:Food:Groceries USD
2024-01-01 open Equity:Opening-Balances USD

2024-01-01 * "Opening"
  Assets:Bank:Checking     500.00 USD
  Equity:Opening-Balances

2024-01-05 document Assets:Bank:Checking "documents/statement-2024-01.pdf"

2024-01-06 * "Grocer" "Shop with receipt"
  document: "documents/receipt-2024-01-06.txt"
  Expenses:Food:Groceries   23.45 USD
  Assets:Bank:Checking
""")
    write(root / "documents/statement-2024-01.pdf", "%PDF-1.4\n% placeholder fixture\n")
    write(root / "documents/receipt-2024-01-06.txt", "Grocer receipt fixture\n")


def case_invalid(root: Path) -> None:
    """Errors at known places. A ledger with errors is a normal state, not a crash."""
    write(root / "main.beancount", """\
option "title" "Invalid"
option "operating_currency" "USD"

2024-01-01 open Assets:Bank:Checking USD
2024-01-01 open Expenses:Food:Groceries USD

; line 8: transaction does not balance
2024-01-05 * "Grocer" "Does not balance"
  Expenses:Food:Groceries      42.30 USD
  Assets:Bank:Checking        -40.00 USD

; line 13: account was never opened
2024-01-06 * "Mystery" "Unopened account"
  Expenses:Unopened:Account     5.00 USD
  Assets:Bank:Checking

; line 18: balance assertion that will fail
2024-01-31 balance Assets:Bank:Checking   999999.00 USD
""")


def case_large(root: Path, count: int, seed: int = 20260817) -> None:
    """A realistic multi-year ledger, for the performance target in SPEC.md 6."""
    rng = random.Random(seed)
    start = date(2015, 1, 1)

    accounts = ["2015-01-01 open Assets:Bank:Checking USD",
                "2015-01-01 open Assets:Cash USD",
                "2015-01-01 open Income:Salary USD",
                "2015-01-01 open Equity:Opening-Balances USD"]
    accounts += [f"2015-01-01 open {a} USD" for a in EXPENSE_ACCOUNTS]

    write(root / "main.beancount", 'option "title" "Large"\n'
                                   'option "operating_currency" "USD"\n\n'
                                   'include "files/accounts.beancount"\n'
                                   'include "files/transactions.beancount"\n')
    write(root / "files/accounts.beancount", "\n".join(accounts) + "\n")

    lines = ["2015-01-01 * \"Opening\" \"Initial balance\"",
             "  Assets:Bank:Checking    100000.00 USD",
             "  Equity:Opening-Balances", ""]
    for i in range(count):
        day = start + timedelta(days=i // 8)
        payee = rng.choice(PAYEES)
        account = rng.choice(EXPENSE_ACCOUNTS)
        amount = f"{rng.randint(100, 25000) / 100:.2f}"
        tag = " #recurring" if i % 17 == 0 else ""
        lines.append(f'{day} * "{payee}" "Entry {i}"{tag}')
        lines.append(f"  {account}   {amount} USD")
        lines.append("  Assets:Bank:Checking")
        lines.append("")
        if i % 500 == 0:
            lines.append(f'{day} * "Employer" "Salary"')
            lines.append("  Assets:Bank:Checking     3500.00 USD")
            lines.append("  Income:Salary")
            lines.append("")
    write(root / "files/transactions.beancount", "\n".join(lines))


CASES = {
    "minimal": case_minimal,
    "multi-currency": case_multi_currency,
    "multi-file": case_multi_file,
    "custom-directives": case_custom_directives,
    "documents": case_documents,
    "invalid": case_invalid,
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("corpus"))
    ap.add_argument("--large-transactions", type=int, default=25_000)
    args = ap.parse_args()

    for name, builder in CASES.items():
        target = args.out / name
        builder(target)
        print(f"{name:20} -> {target}")

    large = args.out / "large"
    case_large(large, args.large_transactions)
    size = sum(f.stat().st_size for f in large.rglob("*") if f.is_file())
    print(f"{'large':20} -> {large}  ({args.large_transactions} txns, {size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
