"""A plugin that silently discards directives it does not recognise.

Exists to be caught. This is data invariant 4 — nothing may be silently dropped — and it is
the failure a user would never notice, because the ledger file is untouched and only what
the app *shows* is wrong.
"""
from beancount.core import data

__plugins__ = ["keep_only_transactions"]


def keep_only_transactions(entries, options_map):
    return [e for e in entries if isinstance(e, data.Transaction)], []
