#!/usr/bin/env python3
"""Writes core-domain/src/test/resources/beancount-balance-cases.txt: random entries and what Beancount says.

Each case is a handful of postings and a set of precision/tolerance options. Beancount (the version
named below) is asked two things about it, by loading it: does the entry balance, and, when one posting
is left blank, what number does it put there. The Kotlin test `BeancountBalanceCasesTest` asks the
app's own rules the same and fails on any difference, so the app is held to Beancount's answers and not
to a second copy of its reading of them.

Run it with an environment that has beancount installed, and commit the file it writes:

    python3 -m venv /tmp/bcv && /tmp/bcv/bin/pip install beancount==3.2.3
    /tmp/bcv/bin/python tools/gen-balance-cases.py

The seed is fixed, so the file only changes when Beancount's answers do.
"""
import random
import sys
from pathlib import Path

import beancount
from beancount import loader

OUT = Path(__file__).resolve().parent.parent / "core-domain/src/test/resources/beancount-balance-cases.txt"
CASES = 900
SEED = 20261103

OPTION_SETS = [
    {},
    {"inferred_tolerance_default": ["*:0.005"]},
    {"inferred_tolerance_default": ["*:0.01"]},
    {"inferred_tolerance_default": ["USD:0.01", "EUR:0.001"]},
    {"inferred_tolerance_default": ["*:0.005", "JPY:1"]},
    {"tolerance_multiplier": ["1"]},
    {"tolerance_multiplier": ["0.25"]},
    {"tolerance_multiplier": ["2"], "inferred_tolerance_default": ["*:0.005"]},
    {"infer_tolerance_from_cost": ["TRUE"]},
    {"infer_tolerance_from_cost": ["TRUE"], "tolerance_multiplier": ["1"]},
]
CURRENCIES = ["USD", "EUR", "JPY", "BTC"]


def number(rng, places, low=1, high=2000):
    whole = rng.randint(low, high)
    if places == 0:
        return str(whole)
    frac = "".join(rng.choice("0123456789") for _ in range(places))
    return f"{whole}.{frac}"


def neg(text):
    return text[1:] if text.startswith("-") else "-" + text


def make_case(rng):
    options = rng.choice(OPTION_SETS)
    kind = rng.choice(["plain", "plain", "plain", "priced", "cost"])
    a, b = rng.sample(CURRENCIES, 2)
    postings = []  # (account, units text or None, details text)
    if kind == "plain":
        places = rng.choice([0, 1, 2, 2, 2, 3, 4])
        first = number(rng, places)
        # the other side: the same figure, nudged in the last place or two, sometimes written with other places
        nudge = rng.choice([0, 0, 1, -1, 2, 5, 10])
        other_places = rng.choice([places, places, 0, max(places - 1, 0), places + 1, 2])
        from decimal import Decimal
        value = Decimal(first) + Decimal(nudge) * (Decimal(10) ** -places)
        other = f"{value:.{other_places}f}" if other_places >= 0 else str(value)
        postings = [("Assets:A", f"{first} {a}", ""), ("Assets:B", f"-{other} {a}", "")]
        if rng.random() < 0.3:
            third = number(rng, rng.choice([0, 2, 3]), 1, 50)
            postings = [("Assets:A", f"{first} {a}", ""), ("Assets:B", f"-{other} {a}", ""),
                        ("Assets:C", f"{third} {a}", ""), ("Assets:D", f"-{third} {a}", "")]
    elif kind == "priced":
        units_places = rng.choice([0, 1, 2, 3])
        price_places = rng.choice([0, 1, 2, 3, 4])
        units = number(rng, units_places, 1, 500)
        price = number(rng, price_places, 1, 9)
        from decimal import Decimal
        exact = Decimal(units) * Decimal(price)
        other_places = rng.choice([0, 2, 2, 3])
        nudge = rng.choice([0, 0, 1, -1, 3])
        shown = f"{exact + Decimal(nudge) * (Decimal(10) ** -other_places):.{other_places}f}"
        postings = [("Assets:A", f"{units} {a}", f"@ {price} {b}"), ("Assets:B", f"-{shown} {b}", "")]
    else:
        units_places = rng.choice([0, 1, 2, 3])
        cost_places = rng.choice([0, 1, 2])
        units = number(rng, units_places, 1, 200)
        cost = number(rng, cost_places, 1, 99)
        from decimal import Decimal
        exact = Decimal(units) * Decimal(cost)
        other_places = rng.choice([0, 2, 2])
        nudge = rng.choice([0, 0, 1, -1, 2])
        shown = f"{exact + Decimal(nudge) * (Decimal(10) ** -other_places):.{other_places}f}"
        postings = [("Assets:A", f"{units} {a}", f"{{{cost} {b}}}"), ("Assets:B", f"-{shown} {b}", "")]
    blank = rng.random() < 0.45 and kind != "cost"
    if blank:
        postings.append(("Assets:Z", None, ""))
    return options, postings


def ledger_text(options, postings):
    lines = []
    for name, values in options.items():
        for value in values:
            lines.append(f'option "{name}" "{value}"')
    accounts = sorted({p[0] for p in postings})
    for account in accounts:
        lines.append(f"2024-01-01 open {account}")
    lines.append('2024-02-01 * "case"')
    for account, units, details in postings:
        lines.append(f"  {account}  {units or ''} {details}".rstrip())
    return "\n".join(lines) + "\n"


def verdict(options, postings):
    entries, errors, _ = loader.load_string(ledger_text(options, postings))
    messages = [e.message for e in errors]
    if any("does not balance" in m for m in messages):
        return "unbalanced", None, messages
    if messages:
        return None, None, messages
    inferred = None
    for entry in entries:
        if getattr(entry, "narration", None) == "case":
            for posting in entry.postings:
                if posting.account == "Assets:Z":
                    inferred = f"{posting.units.number} {posting.units.currency}"
    return "balanced", inferred, messages


def main():
    rng = random.Random(SEED)
    out = [f"# Generated by tools/gen-balance-cases.py with Beancount {beancount.__version__}. Do not edit."]
    kept = unbalanced = blanks = 0
    index = 0
    while kept < CASES:
        options, postings = make_case(rng)
        result, inferred, messages = verdict(options, postings)
        if result is None:
            continue
        index += 1
        kept += 1
        unbalanced += result == "unbalanced"
        out.append(f"case {index}")
        for name, values in options.items():
            for value in values:
                out.append(f"option {name} {value}")
        for account, units, details in postings:
            out.append(f"posting {account} | {units or ''} | {details}")
        out.append(f"verdict {result}")
        if inferred is not None:
            blanks += 1
            out.append(f"inferred {inferred}")
        out.append("end")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n")
    print(f"{kept} cases, {unbalanced} unbalanced, {blanks} with a blank posting -> {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
