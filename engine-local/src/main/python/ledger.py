"""Reading a ledger: parse, summarise, and report errors precisely.

This module is deliberately platform-neutral — the very same file runs on the device via
Chaquopy and on a desktop via tools/corpus/differential.py. That is what makes the
differential test meaningful: if the two disagree, it is the platform that differs, not
the code.

Nothing here writes. Mutation is M4's business.
"""

from __future__ import annotations

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


def inspect(
    ledger_path: str,
    journal_limit: int = 200,
    plugin_dirs: str = "",
    allow_bridge: bool = True,
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

    counts: dict[str, int] = {}
    for entry in entries:
        name = type(entry).__name__
        counts[name] = counts.get(name, 0) + 1

    # Account balances, computed the way Beancount does it rather than by hand.
    from beancount.core import inventory as inventory_lib

    balances: dict[str, inventory_lib.Inventory] = {}
    for entry in entries:
        if isinstance(entry, data.Transaction):
            for posting in entry.postings:
                if posting.units is None:
                    continue
                balances.setdefault(
                    posting.account, inventory_lib.Inventory()
                ).add_amount(posting.units)

    accounts = sorted(getters.get_accounts(entries))
    account_rows = []
    for account in accounts:
        inventory = balances.get(account)
        positions = (
            [
                {"number": _decimal_str(pos.units.number), "currency": pos.units.currency}
                for pos in inventory.reduce(lambda p: p.units)
            ]
            if inventory is not None
            else []
        )
        account_rows.append({"name": account, "positions": positions})

    journal = []
    for entry in entries[-journal_limit:]:
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
                "postings": [
                    {
                        "account": posting.account,
                        "number": _decimal_str(
                            posting.units.number if posting.units else None
                        ),
                        "currency": posting.units.currency if posting.units else None,
                    }
                    for posting in entry.postings
                ],
            }
        )

    payload = {
        "ok": True,
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
            "included_file_names": [Path(f).name for f in payload["included_files"]],
        },
        ensure_ascii=False,
        sort_keys=True,
        indent=2,
    )
