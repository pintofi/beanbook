"""Reading a ledger: parse, summarise, and report errors precisely.

This module is deliberately platform-neutral — the very same file runs on the device via
Chaquopy and on a desktop via tools/corpus/differential.py. That is what makes the
differential test meaningful: if the two disagree, it is the platform that differs, not
the code.

Nothing here writes. Mutation is M4's business.
"""

from __future__ import annotations

import datetime
import json
import time
from pathlib import Path
from typing import Any


def _decimal_str(value: Any) -> str | None:
    """Amounts cross the boundary as strings, never as floats (invariant 1)."""
    return None if value is None else str(value)


def _error_dict(error: Any) -> dict[str, Any]:
    """Beancount errors carry their own source position; surface it verbatim."""
    meta = getattr(error, "source", None) or {}
    return {
        "message": str(getattr(error, "message", error)),
        "file": meta.get("filename"),
        "line": meta.get("lineno"),
        "type": type(error).__name__,
    }


def _errors_with_plugin_failure(errors: Any, plugin_failure) -> list:
    """Serialises Beancount's errors, plus the one Beancount could not catch itself."""
    out = [_error_dict(e) for e in errors]
    if plugin_failure:
        out.append(
            {
                "message": (
                    "A plugin stopped the ledger from loading, so it was loaded without "
                    f"user plugins: {plugin_failure}"
                ),
                "file": None,
                "line": None,
                "type": "PluginFailure",
            }
        )
    return out


def _display_round(value):
    """Two decimal places, for a figure that exists only to be read.

    Used for operating-currency conversions. An operating currency is a unit of account a
    person keeps books in, so two places is what they expect to see; the unrounded value is
    still what Beancount computed, and nothing here feeds a write.
    """
    from decimal import Decimal, ROUND_HALF_UP

    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _posting_dict(posting: Any) -> dict[str, Any]:
    """One posting, including what it was held or exchanged at (B63).

    Units alone cannot describe a holding. `10 ACME {12.50 USD}` and `10 ACME` are different
    facts about the books, and two lots of the same commodity bought at different prices are
    indistinguishable without their cost. The app showed units only, so a sale at a price and
    a purchase into a named lot both read as a bare number of shares.

    Beancount has already done the work by the time a posting is here: a total price (`@@`)
    is normalised to a per-unit one, and a cost written as a label or a partial spec is booked
    against the lot it matched, so the cost reported here is the full one -- number, currency,
    acquisition date and label -- rather than what the file happens to say.
    """
    cost = posting.cost
    price = posting.price
    # A `CostSpec` rather than a `Cost` reaches here only when booking did not run or did not
    # resolve -- an unbooked posting in a file that also produced an error. It names its
    # number differently, so read that rather than reporting a cost with no figure in it.
    cost_number = getattr(cost, "number", None)
    if cost_number is None:
        cost_number = getattr(cost, "number_per", None)
    return {
        "account": posting.account,
        "number": _decimal_str(posting.units.number if posting.units else None),
        "currency": posting.units.currency if posting.units else None,
        "cost": None if cost is None else {
            "number": _decimal_str(cost_number),
            "currency": getattr(cost, "currency", None),
            "date": None if getattr(cost, "date", None) is None else str(cost.date),
            "label": getattr(cost, "label", None),
        },
        "price": None if price is None else {
            "number": _decimal_str(price.number),
            "currency": price.currency,
        },
    }


def _fava_options(entries: Any) -> dict[str, str]:
    """The Fava options a ledger sets for itself (B67).

    Fava reads its own settings out of the Beancount file, as
    `custom "fava-option" "<name>" "<value>"` directives -- they are ordinary Custom
    directives to Beancount, which is why they survive a plain load and why the app can read
    exactly what Fava would read. Only the ones the app can honour are reported; the rest are
    Fava's business and are left alone rather than half-implemented.

    Names are normalised to the underscore form Fava's own option object uses, so
    `invert-income-liabilities-equity` and `invert_income_liabilities_equity` are one option.
    """
    from beancount.core import data as _data

    wanted = {
        "locale",
        "invert_income_liabilities_equity",
        "currency_column",
        "indent",
    }
    found: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, _data.Custom) or entry.type != "fava-option":
            continue
        values = list(entry.values or [])
        if not values:
            continue
        name = str(values[0].value).strip().replace("-", "_")
        if name not in wanted:
            continue
        # A boolean option may be written with no value at all, which Fava reads as true.
        found[name] = str(values[1].value).strip() if len(values) > 1 else "true"
    return found


def _meta_str(value) -> str:
    """A metadata value as text.

    Beancount keeps these typed -- a value can be a Decimal, a date, an Amount or an account
    string -- and the app shows them rather than computing with them, so one text form here
    beats every reader guessing at the type across the Binder boundary.
    """
    return str(value)


def inspect(
    ledger_path: str,
    journal_limit: int = 0,
    plugin_dirs: str = "",
    allow_bridge: bool = True,
    date_from: str = "",
    date_to: str = "",
) -> str:
    """Parse a ledger and return a JSON summary.

    JSON rather than a Python object because this crosses the Chaquopy boundary and is
    also written to disk by the differential runner.
    """
    from beancount import loader
    from beancount.core import data, getters

    import plugins

    started = time.monotonic()
    plugin_failure = None
    # User plugins are imported by Beancount itself, when it reaches a `plugin` directive.
    # All this does is make them findable, under the edition's rules.
    try:
        with plugins.activated(plugin_dirs, allow_bridge):
            entries, errors, options_map = loader.load_file(ledger_path)
    except BaseException as exc:
        # Beancount catches an ImportError from a plugin and records it as an error. Anything
        # else — a RuntimeError at module level, a SystemExit, an assertion — comes straight
        # out of load_file and would take the engine down with it. Found with a plugin whose
        # first line was `raise RuntimeError(...)`.
        #
        # A plugin must not be able to make a ledger unreadable. Load it again without user
        # plugins, so the user still has their data, and report what broke.
        plugin_failure = f"{type(exc).__name__}: {exc}"
        with plugins.activated("", allow_bridge):
            entries, errors, options_map = loader.load_file(ledger_path)
    load_ms = (time.monotonic() - started) * 1000

    # A date range, applied the way Fava applies its time filter: the *entries* are narrowed,
    # and everything computed below -- balances, the journal, which accounts exist -- follows
    # from the narrowed set (B53). Prices are the exception and are built from the whole file
    # further down, because a rate published before the range still values a holding inside
    # it, and dropping it would report an asset at cost and call it market value.
    #
    # An unparseable bound is ignored rather than treated as the start of time: a half-typed
    # date in a filter box must not silently empty the screen.
    all_entries = entries
    bounds = []
    for raw in (date_from, date_to):
        try:
            bounds.append(datetime.date.fromisoformat(raw) if raw else None)
        except ValueError:
            bounds.append(None)
    since, until = bounds
    if since is not None or until is not None:
        entries = [
            entry
            for entry in entries
            if (since is None or entry.date >= since) and (until is None or entry.date <= until)
        ]

    counts: dict[str, int] = {}
    for entry in entries:
        name = type(entry).__name__
        counts[name] = counts.get(name, 0) + 1

    # Account balances, computed the way Beancount does it rather than by hand.
    from beancount.core import convert
    from beancount.core import inventory as inventory_lib
    from beancount.core import prices as prices_lib

    # add_position, not add_amount: the latter keeps only the units and throws the cost
    # basis away before anything can ask for it, so ten shares could be reported but not
    # what they cost (B14).
    balances: dict[str, inventory_lib.Inventory] = {}
    for entry in entries:
        if isinstance(entry, data.Transaction):
            for posting in entry.postings:
                if posting.units is None:
                    continue
                balances.setdefault(
                    posting.account, inventory_lib.Inventory()
                ).add_position(posting)

    # Prices come from the file's own `price` directives. Nothing is fetched and nothing is
    # inferred: an asset the ledger never priced is reported at cost by Beancount's own
    # get_value, which is the honest answer rather than an invented one.
    price_map = prices_lib.build_price_map(all_entries)
    # The end of the range being shown, not the end of the file: valuing a filtered ledger at
    # today's price would put figures on the screen that the chosen period never held.
    as_of = max((entry.date for entry in entries), default=None)

    def _amounts(inventory, reducer):
        if inventory is None:
            return []
        return [
            {"number": _decimal_str(pos.units.number), "currency": pos.units.currency}
            for pos in inventory.reduce(reducer)
        ]

    # The ledger's own operating currency, when it declares one. Everything on the home
    # surface and in the widgets is a single headline figure, and a headline that reads
    # "1,200.00 USD 340.00 EUR 0.5 BTC" answers nothing -- so those surfaces want one number
    # in one currency. This is the currency to put it in.
    operating = (options_map.get("operating_currency") or [None])[0]

    def _in_operating(inventory):
        """An inventory converted to the operating currency, or [] if it cannot be.

        `convert_position` falls back to the position's own currency when no price connects
        it to the target, exactly as `get_value` falls back to cost. So a holding the ledger
        never priced is reported in its own currency rather than silently counted as though
        it were operating-currency units, and the sum below keeps it as a separate figure
        instead of adding pounds to dollars.
        """
        if inventory is None or operating is None:
            return []
        converted = inventory_lib.Inventory()
        for pos in inventory:
            converted.add_amount(
                convert.convert_position(pos, operating, price_map, as_of)
            )
        return [
            {
                # Quantised for display. Multiplying a balance by a rate gives as many
                # decimal places as the rate has -- 1850.00 EUR at 1.085 came out as
                # "2007.250000 USD" on the home screen. This rounds the *presentation* of a
                # converted figure and nothing else: no converted number is ever written to a
                # ledger, and the units, cost and market-value figures beside it are
                # untouched, so invariant 1 still holds where it is about the books.
                "number": _decimal_str(_display_round(pos.units.number)),
                "currency": pos.units.currency,
            }
            for pos in converted
        ]

    accounts = sorted(getters.get_accounts(entries))
    account_rows = []
    for account in accounts:
        inventory = balances.get(account)
        account_rows.append(
            {
                "name": account,
                "positions": _amounts(inventory, convert.get_units),
                "at_cost": _amounts(inventory, convert.get_cost),
                "at_value": _amounts(
                    inventory, lambda pos: convert.get_value(pos, price_map, as_of)
                ),
                "at_operating": _in_operating(inventory),
            }
        )

    # 0 means every transaction. The window used to be 200 so the JSON stayed small and the
    # cold-start budget held (B21), but 200 is also all the Journal screen could ever show,
    # which is not what a ledger is for. Measured on the 25k corpus: the whole journal costs
    # +0.55 s in here and takes the JSON from 0.08 MB to 10.03 MB. The result crosses to the
    # app as a file, not over the Binder, so size is a parse cost rather than a hard limit.
    journal = []
    window = entries if journal_limit <= 0 else entries[-journal_limit:]
    for entry in window:
        if not isinstance(entry, data.Transaction):
            continue
        journal.append(
            {
                "date": str(entry.date),
                "flag": entry.flag,
                "payee": entry.payee,
                "narration": entry.narration,
                "tags": sorted(entry.tags or []),
                "links": sorted(entry.links or []),
                "file": entry.meta.get("filename"),
                "line": entry.meta.get("lineno"),
                # The user's own keys only. Beancount keeps its bookkeeping in the same dict
                # -- filename, lineno, and anything it prefixes with two underscores -- and
                # showing "__automatic__" to somebody as though they had written it would be
                # wrong. This is also where `fava.plugins.link_documents` records a linked
                # receipt, which is what makes offering to open one possible at all (B17).
                "metadata": {
                    key: _meta_str(value)
                    for key, value in (entry.meta or {}).items()
                    if key not in ("filename", "lineno") and not key.startswith("__")
                },
                "postings": [_posting_dict(posting) for posting in entry.postings],
            }
        )

    # The `document` directives, so the app can say what a document is filed under and tell
    # one the ledger knows about from a file merely sitting in the folder. Beancount has
    # already resolved each path against the directory of the file declaring it, so these are
    # absolute; relativising them needs the project root, which only the caller knows.
    documents = sorted(
        (
            {
                "path": entry.filename,
                "account": entry.account,
                "date": str(entry.date),
                "file": entry.meta.get("filename"),
                "line": entry.meta.get("lineno"),
                "tags": sorted(entry.tags or []),
                "links": sorted(entry.links or []),
            }
            for entry in entries
            if isinstance(entry, data.Document)
        ),
        key=lambda row: (row["date"], row["path"]),
    )

    # How this ledger asks to be shown (B59). Beancount works these out itself -- the
    # display context is built from the numbers actually in the file, and `render_commas` is
    # the user's own option -- and the app used neither, so a ledger written to three places
    # with thousands separators was displayed to two places without them.
    display = {}
    dcontext = options_map.get("dcontext")
    if dcontext is not None:
        for currency, ccontext in getattr(dcontext, "ccontexts", {}).items():
            if currency == "__default__":
                continue
            # `get_fractional_digits_common` is what Beancount itself renders with: the
            # number of places most amounts in this currency are written to. The maximum is
            # the fallback, for a file where one figure is more precise than the rest.
            fractional = None
            for reader in ("get_fractional_digits_common", "get_fractional_digits_max"):
                method = getattr(ccontext, reader, None)
                if method is None:
                    continue
                try:
                    fractional = method()
                except Exception:  # noqa: BLE001 - an unusable context is simply no answer
                    fractional = None
                if isinstance(fractional, int) and fractional >= 0:
                    break
            if isinstance(fractional, int) and fractional >= 0:
                display[currency] = fractional

    tolerances = {
        currency: _decimal_str(value)
        for currency, value in (options_map.get("inferred_tolerance_default") or {}).items()
    }

    payload = {
        "ok": True,
        "render_commas": bool(options_map.get("render_commas")),
        # What the file asks Fava for. Reported rather than applied here: the app decides how
        # to render, and it cannot do that without knowing what was asked (B67).
        "fava_options": _fava_options(all_entries),
        "display_precision": dict(sorted(display.items())),
        "tolerances": dict(sorted(tolerances.items())),
        "documents": documents,
        "load_ms": round(load_ms, 1),
        "title": options_map.get("title"),
        "operating_currency": list(options_map.get("operating_currency") or []),
        "entry_count": len(entries),
        "entry_counts": dict(sorted(counts.items())),
        "error_count": len(errors),
        "errors": _errors_with_plugin_failure(errors[:50], plugin_failure),
        "account_count": len(accounts),
        "accounts": account_rows,
        "included_files": sorted(str(f) for f in options_map.get("include", [])),
        "commodities": sorted(getters.get_commodity_directives(entries).keys()),
        "price_count": counts.get("Price", 0),
        "journal": journal,
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)


def digest(ledger_path: str) -> str:
    """A compact, comparable summary for differential testing.

    Excludes anything machine-specific: absolute paths, timings, and the journal's file
    positions all vary between desktop and device, so only accounting facts remain.
    """
    payload = json.loads(inspect(ledger_path))
    return json.dumps(
        {
            "title": payload["title"],
            "operating_currency": payload["operating_currency"],
            "entry_count": payload["entry_count"],
            "entry_counts": payload["entry_counts"],
            "error_count": payload["error_count"],
            # Messages, not paths: the ledger lives at a different absolute path on each
            # platform, but the wording and ordering of errors must match exactly.
            "error_messages": [e["message"] for e in payload["errors"]],
            "error_lines": [e["line"] for e in payload["errors"]],
            "account_count": payload["account_count"],
            "accounts": payload["accounts"],
            "commodities": payload["commodities"],
            # Names, not paths, for the same reason as the errors above: a document resolves
            # to a different absolute path on desktop and on device, but which account it is
            # filed under is an accounting fact and must match exactly.
            "documents": [
                {"name": Path(d["path"]).name, "account": d["account"], "date": d["date"]}
                for d in payload["documents"]
            ],
            "included_file_names": [Path(f).name for f in payload["included_files"]],
        },
        ensure_ascii=False,
        sort_keys=True,
        indent=2,
    )
