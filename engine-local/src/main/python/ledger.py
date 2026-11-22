"""Reading a ledger: parse, summarise, and report errors precisely.

This module is deliberately platform-neutral — the very same file runs on the device via
Chaquopy and on a desktop via tools/corpus/differential.py. That is what makes the
differential test meaningful: if the two disagree, it is the platform that differs, not
the code.

Nothing here writes. Mutation is M4's business.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import threading
import time
import time as time_module
from pathlib import Path
from typing import Any


def _decimal_str(value: Any) -> str | None:
    """Amounts cross the boundary as strings, never as floats (invariant 1)."""
    return None if value is None else str(value)


def _with_posting_prices(entries: Any, options_map: Any) -> list:
    """The entries plus the rates their own postings state, for reading values only.

    Only a posting with a price and **no cost** counts (`2000 SAR @ 75 PKR`): that is a
    conversion the ledger states outright. A cost (`{500 USD}`) is what something was bought
    for, not what it is worth now, and stays out -- the unpriced-holding rule in
    test_ledger_valuation still holds.
    """
    from beancount.core import amount as amount_lib
    from beancount.core import data

    derived = list(entries)
    seen = set()
    for entry in entries:
        if not isinstance(entry, data.Transaction):
            continue
        for posting in entry.postings:
            if posting.units is None or posting.price is None or posting.cost is not None:
                continue
            base, quote = posting.units.currency, posting.price.currency
            if base == quote or posting.price.number is None:
                continue
            key = (entry.date, base, quote)
            if key in seen:
                continue
            seen.add(key)
            derived.append(
                data.Price(
                    entry.meta, entry.date, base,
                    amount_lib.Amount(posting.price.number, quote),
                )
            )
    return derived


def _commodity_attributes(options_map: Any) -> list:
    """The metadata keys `beancount.plugins.commodity_attr` demands on every commodity.

    The ledger's own plugin line names them -- `plugin "beancount.plugins.commodity_attr"
    "{'name': None, 'precision': None}"` -- and the plugin reports an error for each commodity
    directive that lacks one. The Add form needs the same list to ask for them.
    """
    import ast

    for entry in options_map.get("plugin") or []:
        name, config = entry[0], entry[1] if len(entry) > 1 else None
        if name != "beancount.plugins.commodity_attr" or not config:
            continue
        try:
            parsed = ast.literal_eval(config)
        except (ValueError, SyntaxError, RecursionError, MemoryError):
            # The text is the ledger's own and can be anything: a deeply nested one raises
            # RecursionError, and that must not be a ledger that will not open.
            return []
        if isinstance(parsed, dict):
            return [str(key) for key in parsed]
    return []


def _extension_health(entries: Any) -> tuple:
    """Whether each Fava extension the ledger names loaded, as Fava itself found.

    Returns (health, complete). The modules are the `fava-extension` custom directives. The
    answer comes from what Fava reported when it loaded the ledger (`extension_state`), and
    **nothing is imported here**: the first version imported each extension inside the load and
    then evicted it, and when Fava later imported numpy again the second initialisation of its
    C extension crashed the whole engine process (SIGSEGV), taking Reports down with it.
    `complete` is False until Fava has finished loading, so a caller does not keep an answer
    that was only "not known yet".
    """
    from beancount.core import data as _data

    modules = sorted(
        {
            str(entry.values[0].value)
            for entry in entries
            if isinstance(entry, _data.Custom)
            and entry.type == "fava-extension"
            and entry.values
        }
    )
    if not modules:
        return {}, True
    import extension_state

    # Fava is loading the same ledger in the same process, at the same time as this call: wait
    # for it (bounded) rather than reporting "not known yet" and having that cached.
    waited = 0.0
    while extension_state.starting and not extension_state.ready and waited < 40.0:
        time.sleep(0.25)
        waited += 0.25
    if not extension_state.ready:
        return {}, False
    return {m: extension_state.errors.get(m) for m in modules}, True


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


def _posting_dict(posting: Any) -> dict[str, Any]:
    """One posting, including what it was held or exchanged at.

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
    """The Fava options a ledger sets for itself.

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


def _load(ledger_path: str, plugin_dirs: str, allow_bridge: bool):
    """Load a ledger the way every read of it does: user plugins made findable for the load only.

    Returns (entries, errors, options_map, plugin_failure, load_ms). Shared by `inspect` and
    `matching_transactions` so a filter is applied to exactly the entries the summary was built from.
    """
    from beancount import loader

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
    # A cache made for a plugin set that is no longer the one in use will never be read again.
    plugins.discard_other_loader_caches(
        ledger_path,
        plugins.loader_cache_pattern(plugins._module_names("" if plugin_failure else plugin_dirs)),
    )

    return entries, errors, options_map, plugin_failure, load_ms


def _content_hash(files) -> str:
    """SHA-256 over the content of the files a load read, in path order.

    Beancount's own `compute_input_hash` is keyed on modification time and size. That is right for
    its load cache and wrong here: the app caches a snapshot by content, so a file that was only
    touched (a sync tool does that) would give a new hash for the same journal, and an answer would
    never match the snapshot it was made for.
    """
    digest = hashlib.sha256()
    for name in sorted(str(f) for f in files):
        digest.update(name.encode("utf-8") + b"\0")
        try:
            digest.update(Path(name).read_bytes())
        except OSError:
            digest.update(b"\0missing")
    return digest.hexdigest()


def _date_cut(entries, date_from: str, date_to: str):
    # A date range, applied the way Fava applies its time filter: the *entries* are narrowed,
    # and everything computed below -- balances, the journal, which accounts exist -- follows
    # from the narrowed set. Prices are the exception and are built from the whole file
    # further down, because a rate published before the range still values a holding inside
    # it, and dropping it would report an asset at cost and call it market value.
    #
    # An unparseable bound is ignored rather than treated as the start of time: a half-typed
    # date in a filter box must not silently empty the screen.
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
    return entries


def _market_of(pos, operating, price_map, as_of):
    """The market value of one position, the way Fava's "Market Value" reads it.

    At the latest price on or before [as_of]; in the operating currency when the ledger declares one,
    otherwise in whatever the price is quoted in. Where no price connects the position, it is shown
    **at cost** if it was bought at one, and in its own units if it was not -- so a purchase price is
    never silently dropped, and a holding nobody priced is never given a value nobody stated.
    """
    from beancount.core import convert

    if operating is not None:
        value = convert.convert_position(pos, operating, price_map, as_of)
        if value.currency == operating:
            return value
    else:
        value = convert.get_value(pos, price_map, as_of)
        if value.currency != pos.units.currency:
            return value
    if pos.cost is not None:
        cost = convert.get_cost(pos)
        if operating is not None and cost.currency != operating:
            return convert.convert_amount(cost, operating, price_map, as_of)
        return cost
    return value


def _account_rows(entries, all_entries, options_map, as_of=None):
    """Every account's balances, as `inspect` reports them, for the [entries] given.

    Prices come from [all_entries] (the whole file) and the valuation date is the last of [entries], so a
    filtered or clamped set is valued at the end of what it describes. Shared by `inspect` and `view`, so a
    filtered figure is worked out by exactly the code that works out the unfiltered one.

    Returns (account names, account rows, the latest rate of each priced pair).
    """
    from beancount.core import data, getters

    # Account balances, computed the way Beancount does it rather than by hand.
    from beancount.core import convert
    from beancount.core import inventory as inventory_lib
    from beancount.core import prices as prices_lib

    # add_position, not add_amount: the latter keeps only the units and throws the cost
    # basis away before anything can ask for it, so ten shares could be reported but not
    # what they cost.
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
    #
    # One exception: a posting that carries its own rate (`2000 SAR @ 75 PKR`) is the
    # ledger saying what that commodity was worth on that day. Without it, a ledger that
    # never wrote a `price` directive showed PKR and SAR as two unrelated figures. These are
    # what Beancount's own `implicit_prices` plugin derives, used here only to read figures
    # and never written back to a file.
    price_map = prices_lib.build_price_map(_with_posting_prices(all_entries, options_map))
    # The end of the range being shown, not the end of the file: valuing a filtered ledger at
    # today's price would put figures on the screen that the chosen period never held.
    if as_of is None:
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

    def _market(pos):
        return _market_of(pos, operating, price_map, as_of)

    def _valued(inventory):
        if inventory is None:
            return []
        converted = inventory_lib.Inventory()
        for pos in inventory:
            converted.add_amount(_market(pos))
        return [
            {
                # Exact, not rounded. A converted balance has as many places as its rate --
                # 1850.00 EUR at 1.085 is "2007.250000 USD" -- and the app rounds that for
                # display, once, to the places the ledger writes the currency to (see
                # `display` below). Rounding here, per account, and letting the app add the
                # rounded parts up put a total one cent from Fava's, which adds exact values
                # and rounds the sum (#60). No converted number is written to a ledger.
                "number": _decimal_str(pos.units.number),
                "currency": pos.units.currency,
            }
            for pos in converted
        ]

    def _holdings(inventory):
        if inventory is None:
            return []
        by_currency = {}
        for pos in inventory:
            by_currency.setdefault(pos.units.currency, inventory_lib.Inventory()).add_position(pos)
        return [
            {
                "currency": currency,
                "units": _amounts(part, convert.get_units),
                "at_cost": _amounts(part, convert.get_cost),
                "at_value": _valued(part),
            }
            for currency, part in sorted(by_currency.items())
        ]

    def _latest_rates():
        """Each priced pair at its latest price, so the app can convert a posting itself."""
        rates = []
        for base, quote in sorted(price_map.keys()):
            if isinstance(base, str) and isinstance(quote, str):
                _date, rate = prices_lib.get_price(price_map, (base, quote), as_of)
                if rate is not None:
                    rates.append({"base": base, "quote": quote, "rate": _decimal_str(rate)})
        return rates

    accounts = sorted(getters.get_accounts(entries))
    open_close = getters.get_account_open_close(entries)
    account_rows = []
    for account in accounts:
        inventory = balances.get(account)
        opened, closed = open_close.get(account, (None, None))
        account_rows.append(
            {
                "name": account,
                # The day an Open directive opened it and a Close closed it, and the currencies
                # the Open restricts it to. An account used with no Open (auto_accounts) has none.
                "opened": str(opened.date) if opened else None,
                "closed": str(closed.date) if closed else None,
                "allowed": list(opened.currencies or []) if opened else [],
                # Where the Open directive is written and what metadata it carries, so the
                # account's details can be shown and rewritten in place.
                "open_file": opened.meta.get("filename") if opened else None,
                "open_line": opened.meta.get("lineno") if opened else None,
                "metadata": {
                    key: _meta_str(value)
                    for key, value in (opened.meta or {}).items()
                    if key not in ("filename", "lineno")
                }
                if opened
                else {},
                "positions": _amounts(inventory, convert.get_units),
                "at_cost": _amounts(inventory, convert.get_cost),
                # Market value. `at_operating` is the same figure, kept so a cached
                # snapshot and an older reader keep working.
                "at_value": _valued(inventory),
                "at_operating": _valued(inventory),
                # One entry for each commodity the account holds, with that holding's units and
                # what it comes to at cost and at market value. A widget showing "the PKR in
                # Cash" and not "the SAR in Cash" has to be able to value them apart.
                "holdings": _holdings(inventory),
            }
        )

    return accounts, account_rows, _latest_rates()


def _journal_row(entry) -> dict[str, Any]:
    """One transaction as the journal reports it."""
    return {
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
        # receipt, which is what makes offering to open one possible at all.
        "metadata": {
            key: _meta_str(value)
            for key, value in (entry.meta or {}).items()
            if key not in ("filename", "lineno") and not key.startswith("__")
        },
        "postings": [_posting_dict(posting) for posting in entry.postings],
    }


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
    from beancount.core import data, getters

    entries, errors, options_map, plugin_failure, load_ms = _load(ledger_path, plugin_dirs, allow_bridge)
    all_entries = entries
    entries = _date_cut(entries, date_from, date_to)

    counts: dict[str, int] = {}
    for entry in entries:
        name = type(entry).__name__
        counts[name] = counts.get(name, 0) + 1

    accounts, account_rows, rates = _account_rows(entries, all_entries, options_map)

    # 0 means every transaction. The window used to be 200 so the JSON stayed small and the
    # cold-start budget held, but 200 is also all the Journal screen could ever show,
    # which is not what a ledger is for. Measured on the 25k corpus: the whole journal costs
    # +0.55 s in here and takes the JSON from 0.08 MB to 10.03 MB. The result crosses to the
    # app as a file, not over the Binder, so size is a parse cost rather than a hard limit.
    journal = []
    window = entries if journal_limit <= 0 else entries[-journal_limit:]
    for entry in window:
        if not isinstance(entry, data.Transaction):
            continue
        journal.append(_journal_row(entry))

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
                # True when Beancount made this entry itself, from a dated file sitting in an
                # account folder under `option "documents"`, rather than from a line somebody
                # wrote. `find_documents` stamps those with line 0, and the parser numbers
                # real directives from 1, so the two can be told apart -- which the Organize
                # screen needs: unlinking removes the written directive, and the automatic
                # one comes straight back on the next load.
                "auto": entry.meta.get("lineno") == 0,
                "tags": sorted(entry.tags or []),
                "links": sorted(entry.links or []),
            }
            for entry in entries
            if isinstance(entry, data.Document)
        ),
        key=lambda row: (row["date"], row["path"]),
    )

    # How this ledger asks to be shown. Beancount works these out itself -- the
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

    # An operating currency is one a person keeps books in, so it is always shown to some number of
    # places: its own where the ledger writes it, two where it appears only as the result of a
    # conversion. The values above are exact, and the app rounds them with this.
    for currency in options_map.get("operating_currency") or []:
        display.setdefault(currency, 2)

    tolerances = {
        currency: _decimal_str(value)
        for currency, value in (options_map.get("inferred_tolerance_default") or {}).items()
    }
    # The two other options that change the balance rule the entry form checks with: how much of
    # the last decimal place counts as close enough (default 0.5), and whether a cost or a price
    # widens the tolerance of the currency it is in (default off).
    tolerance_multiplier = _decimal_str(options_map.get("tolerance_multiplier"))
    infer_tolerance_from_cost = bool(options_map.get("infer_tolerance_from_cost"))

    _health = _extension_health(all_entries)
    payload = {
        "ok": True,
        "render_commas": bool(options_map.get("render_commas")),
        # What the file asks Fava for. Reported rather than applied here: the app decides how
        # to render, and it cannot do that without knowing what was asked.
        "fava_options": _fava_options(all_entries),
        # What this file calls its five trees, and where it keeps documents. Beancount
        # lets a ledger rename the roots -- `option "name_expenses" "Ausgaben"` -- and an app
        # that hardcodes the English five shows such a ledger five empty headings.
        "account_names": {
            "assets": options_map.get("name_assets"),
            "liabilities": options_map.get("name_liabilities"),
            "equity": options_map.get("name_equity"),
            "income": options_map.get("name_income"),
            "expenses": options_map.get("name_expenses"),
        },
        "document_dirs": [str(d) for d in (options_map.get("documents") or [])],
        "display_precision": dict(sorted(display.items())),
        "tolerance_multiplier": tolerance_multiplier,
        "infer_tolerance_from_cost": infer_tolerance_from_cost,
        "tolerances": dict(sorted(tolerances.items())),
        "documents": documents,
        "commodity_attributes": _commodity_attributes(options_map),
        "extension_health": _health[0],
        "extension_health_complete": _health[1],
        "plugins": [
            {"name": str(entry[0]), "config": None if len(entry) < 2 else entry[1]}
            for entry in (options_map.get("plugin") or [])
        ],
        "rates": rates,
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
        # A fingerprint of the files' content. A filter answer carries the same value, so the app can
        # tell that the two describe the same ledger before it applies one to the other.
        "input_hash": _content_hash(options_map.get("include", [])),
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
            # Without where each account's open directive is written: that is a path, which differs
            # between desktop and device. Its line and its metadata are accounting facts and stay.
            "accounts": [
                {key: value for key, value in account.items() if key != "open_file"}
                for account in payload["accounts"]
            ],
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


_FILTER_LOCK = threading.Lock()

# The entries of the last ledger a filter was applied to, so the next filter on the same ledger
# does not load it again (a load is most of what a filter costs). One ledger at a time: it holds
# tens of megabytes on a large one. See `_load_for_filter` for when it may be used.
_ENTRY_CACHE = None
_ENTRY_CACHE_LOCK = threading.Lock()
# A file changed this recently when a load started may have changed while it was read, so what was
# loaded is not kept. Larger than the coarsest timestamps a phone's filesystems give.
_RACY_NS = 2_000_000_000


def drop_entry_cache() -> None:
    """Forget the cached entries. Called when the system asks for memory, and on a new ledger."""
    global _ENTRY_CACHE
    with _ENTRY_CACHE_LOCK:
        _ENTRY_CACHE = None


def _load_for_filter(ledger_path: str, plugin_dirs: str, allow_bridge: bool):
    """`_load`, from the cache when the ledger's files have not changed since it was filled.

    Same result as `_load` (entries, errors, options_map, plugin_failure, load_ms) plus whether it
    came from the cache. A hit is checked against the files' content, not their times, so a file that
    was only touched still hits and one that was edited never does. A load is kept only when every
    file it read was already old when the load began, so an edit made during the load cannot leave
    old entries stored under the new content.
    """
    global _ENTRY_CACHE
    key = (ledger_path, plugin_dirs, bool(allow_bridge))
    with _ENTRY_CACHE_LOCK:
        cached = _ENTRY_CACHE
    if cached is not None and cached["key"] == key and _content_hash(cached["files"]) == cached["hash"]:
        return cached["loaded"] + (True,)
    started_ns = time.time_ns()
    loaded = _load(ledger_path, plugin_dirs, allow_bridge)
    files = [str(f) for f in loaded[2].get("include", [])] or [ledger_path]
    try:
        settled = all(Path(f).stat().st_mtime_ns < started_ns - _RACY_NS for f in files)
    except OSError:
        settled = False
    with _ENTRY_CACHE_LOCK:
        _ENTRY_CACHE = (
            {"key": key, "files": files, "hash": _content_hash(files), "loaded": loaded} if settled else None
        )
    return loaded + (False,)


def matching_transactions(
    ledger_path: str,
    fava_filter: str,
    plugin_dirs: str = "",
    allow_bridge: bool = True,
    date_from: str = "",
    date_to: str = "",
) -> str:
    """Which transactions Fava's own filter keeps, as positions in the journal `inspect` returns.

    The filter is Fava's `AdvancedFilter`, not a reimplementation: the same grammar (`#tag`, `^link`,
    `-`, `,`, parentheses, `any()`, `key:"regex"`, plain words), the same results and the same error
    text. Nothing is computed from the kept entries here. The app already holds the journal, so the
    answer is only the positions, which keeps it small (a few hundred KB at 25,000 transactions
    instead of the whole journal).

    A position counts transactions in the entries' own order after the same date cut `inspect`
    applies, oldest first. `input_hash` and `transaction_count` let the caller refuse an answer that
    describes a different ledger than the journal it holds.

    JSON: {"ok": true, "positions": [...], "transaction_count": n, "input_hash": "...",
    "load_ms": ..., "filter_ms": ...} or {"ok": false, "filter_error": "<Fava's message>"}.
    """
    from beancount.core import data
    from fava.core.filters import AdvancedFilter, FilterError

    # Parsed before anything is loaded: a filter that cannot be read answers at once, with
    # Fava's message, and never falls through to showing the whole ledger.
    try:
        advanced = AdvancedFilter(fava_filter.strip())
    except FilterError as error:
        return json.dumps({"ok": False, "filter_error": str(error)}, ensure_ascii=False)

    entries, _errors, options_map, _plugin_failure, load_ms, cached = _load_for_filter(
        ledger_path, plugin_dirs, allow_bridge,
    )
    cut = _date_cut(entries, date_from, date_to)
    started = time.monotonic()
    try:
        # One filter at a time: Fava's parser is module-level state, and this runs on a binder thread
        # beside the threads that serve Fava's own pages.
        with _FILTER_LOCK:
            kept = {id(entry) for entry in advanced.apply(cut)}
    except FilterError as error:
        return json.dumps({"ok": False, "filter_error": str(error)}, ensure_ascii=False)
    transactions = [entry for entry in cut if isinstance(entry, data.Transaction)]
    return json.dumps(
        {
            "ok": True,
            "positions": [i for i, entry in enumerate(transactions) if id(entry) in kept],
            "transaction_count": len(transactions),
            "input_hash": _content_hash(options_map.get("include", [])),
            "load_ms": 0.0 if cached else round(load_ms, 1),
            "cached": cached,
            "filter_ms": round((time.monotonic() - started) * 1000, 1),
        }
    )


def _refused(field: str, message: str) -> str:
    return json.dumps({"ok": False, "field": field, "message": message}, ensure_ascii=False)


def _amount_matcher(basis, low, high, price_map, operating, as_of):
    """Entries with a posting at least [low] and a posting at most [high] on the chosen basis.

    Each bound is read over any posting, as Fava reads `>=100 <=500`, so the units basis behaves exactly as
    Fava's own amount filter does. The cost and market bases are the same test on another figure: a
    posting's units weighed at their cost, or valued at the latest price on or before [as_of], in the
    operating currency when the ledger has one. A posting nobody priced is compared at cost, then in its own
    units, as the balances show it.
    """
    from beancount.core import convert

    def figure(posting):
        if posting.units is None:
            return None
        if basis == "cost":
            return abs(convert.get_cost(posting).number)
        return abs(_market_of(posting, operating, price_map, as_of).number)

    def match(entry) -> bool:
        postings = getattr(entry, "postings", None)
        if not postings:
            return False
        figures = [value for value in map(figure, postings) if value is not None]
        return (low is None or any(value >= low for value in figures)) and (
            high is None or any(value <= high for value in figures)
        )

    return match


def view(
    ledger_path: str,
    time: str = "",
    text: str = "",
    tags: str = "",
    amount_min: str = "",
    amount_max: str = "",
    amount_basis: str = "units",
    plugin_dirs: str = "",
    allow_bridge: bool = True,
) -> str:
    """The ledger as Fava shows it through a time period, a filter and an amount range.

    Fava's own classes do the work, in Fava's order: the advanced filter (and the amount range, on the units
    basis), then the time filter, which carries earlier history into opening balances as Fava does. The
    balances are worked out by the same code `inspect` uses, over the entries that are left.

    Answers {"ok": true, ...} with the kept transactions as positions in the whole ledger's transactions
    (oldest first, as `matching_transactions` counts), the rows Fava makes up for a period (opening balances,
    which have no file or line), every account's balances and the latest rates; or {"ok": false, "field",
    "message"}, naming the input that cannot be used and saying why in Fava's words where Fava has them.
    """
    from decimal import Decimal, InvalidOperation

    from beancount.core import data
    from beancount.core import prices as prices_lib
    from fava.core.fava_options import parse_options
    from fava.core.filters import AdvancedFilter, FilterError, TimeFilter

    if amount_basis not in ("units", "cost", "market"):
        return _refused("amount", f'"{amount_basis}" is not a basis: use units, cost or market.')
    bounds = []
    for raw in (amount_min, amount_max):
        raw = raw.strip()
        try:
            number = Decimal(raw) if raw else None
        except InvalidOperation:
            return _refused("amount", f'"{raw}" is not a number.')
        if number is not None and (not number.is_finite() or number < 0):
            return _refused("amount", f'"{raw}" is not an amount: use a positive number.')
        bounds.append(number)
    low, high = bounds
    if low is not None and high is not None and low > high:
        return _refused("amount", "The minimum is more than the maximum.")

    # Each part is read on its own first, so a mistake is attributed to the field it was typed in, and
    # then they are joined as AND terms, each in parentheses: `rent, tea #trip` must not read as
    # `rent, (tea #trip)`. Fava's own filter bar has one box, so this is how two fields make one filter.
    parts = []
    for field, value in (("text", text), ("tags", tags)):
        value = value.strip()
        if not value:
            continue
        try:
            AdvancedFilter(value)
        except FilterError as error:
            return _refused(field, str(error))
        parts.append(f"({value})")
    # On the units basis the amount range joins them as two more terms of the same filter.
    if amount_basis == "units":
        parts += [f">={format(n, 'f')}" for n in (low,) if n is not None]
        parts += [f"<={format(n, 'f')}" for n in (high,) if n is not None]
    try:
        advanced = AdvancedFilter(" ".join(parts)) if parts else None
    except FilterError as error:
        return _refused("tags", str(error))

    entries, _errors, options_map, _failure, load_ms, cached = _load_for_filter(
        ledger_path, plugin_dirs, allow_bridge,
    )
    started = time_module.monotonic()
    time_text = time.strip()
    time_filter = None
    if time_text:
        custom = [e for e in entries if isinstance(e, data.Custom) and e.type == "fava-option"]
        fava_options, _ = parse_options(custom)
        try:
            time_filter = TimeFilter(options_map, fava_options, time_text)
        except FilterError as error:
            return _refused("time", str(error))
        except (ValueError, OverflowError):
            # Fava lets an impossible date such as 2020-02-30 escape as an exception; here it is a message.
            return _refused("time", f"Failed to parse date: {time_text}")
    end = time_filter.date_range.end_inclusive if time_filter else None
    as_of = end or max((entry.date for entry in entries), default=None)

    with _FILTER_LOCK:
        working = list(entries)
        if advanced is not None:
            working = list(advanced.apply(working))
        if amount_basis != "units" and (low is not None or high is not None):
            price_map = prices_lib.build_price_map(_with_posting_prices(entries, options_map))
            operating = (options_map.get("operating_currency") or [None])[0]
            matcher = _amount_matcher(amount_basis, low, high, price_map, operating, as_of)
            working = [entry for entry in working if matcher(entry)]
        if time_filter is not None:
            working = list(time_filter.apply(working))
    filter_ms = (time_module.monotonic() - started) * 1000

    index = {
        id(entry): position
        for position, entry in enumerate(e for e in entries if isinstance(e, data.Transaction))
    }
    positions, made_up = [], []
    for entry in working:
        if isinstance(entry, data.Transaction):
            position = index.get(id(entry))
            if position is None:
                made_up.append(_journal_row(entry))
            else:
                positions.append(position)
    _accounts, rows, rates = _account_rows(working, entries, options_map, as_of)
    return json.dumps(
        {
            "ok": True,
            "positions": sorted(positions),
            "summary_rows": made_up,
            "transaction_count": len(index),
            "input_hash": _content_hash(options_map.get("include", [])),
            "begin": str(time_filter.date_range.begin) if time_filter else None,
            "end": str(end) if end else None,
            "accounts": rows,
            "rates": rates,
            "cached": cached,
            "load_ms": 0.0 if cached else round(load_ms, 1),
            "filter_ms": round(filter_ms, 1),
        },
        ensure_ascii=False,
    )
