"""Fava's own filter decides which entries a filter keeps; the engine only reports positions.

Run from the repository root:

    <python-with-beancount-and-fava> -m unittest discover -s engine-local/src/test/python -v

The filter box in Home, Journal and Balances takes Fava's filter grammar (`#tag`, `^link`, `-`,
`,`, parentheses, `any()`, `key:"regex"`, plain words). It must not be a second implementation, so
these tests compare the engine's answer with Fava's own `FavaLedger.get_filtered`, which is what
Fava's web pages use, and check that the positions line up with the journal `inspect` returns.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

LEDGER = """
option "operating_currency" "USD"

2026-01-01 open Assets:Cash
2026-01-01 open Assets:Broker
2026-01-01 open Expenses:Food
2026-01-01 open Expenses:Hotel
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash        1000.00 USD
  Equity:Opening

2026-01-05 * "Hotel Aurora" "conference" #trip #work ^inv-1
  Expenses:Hotel      200.00 USD
  Assets:Cash

2026-01-06 * "Cafe" "lunch on the trip" #trip
  Expenses:Food        30.00 USD
  Assets:Cash

2026-02-01 * "Grocer" "weekly shop" ^inv-1
  Expenses:Food        12.00 USD
  Assets:Cash

2026-03-01 * "Landlord" "rent" #work
  Expenses:Hotel      900.00 USD
  Assets:Cash

2026-03-09 note Expenses:Food "mentions #trip only in the comment"
"""


class MatchingTransactionsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = str(Path(self.dir.name) / "main.beancount")
        Path(self.path).write_text(LEDGER, encoding="utf-8")
        from fava.core import FavaLedger

        self.fava = FavaLedger(self.path)

    def journal(self, **range_):
        return json.loads(ledger.inspect(self.path, **range_))["journal"]

    def kept(self, expression, **range_):
        answer = json.loads(ledger.matching_transactions(self.path, expression, **range_))
        self.assertTrue(answer["ok"], answer)
        journal = self.journal(**range_)
        self.assertEqual(answer["transaction_count"], len(journal))
        return [journal[i]["narration"] for i in answer["positions"]]

    def fava_kept(self, expression):
        from beancount.core import data

        entries = self.fava.get_filtered(filter=expression).entries
        return [e.narration for e in entries if isinstance(e, data.Transaction)]

    def test_positions_agree_with_favas_own_filtered_ledger(self):
        for expression in [
            "#trip",
            "-#trip",
            "- #trip",
            "--#trip",
            "#trip -#trip",
            "#trip, ^inv-1",
            "(#trip, ^inv-1) -#work",
            "-(#trip ^inv-1)",
            "^inv-1",
            "-^inv-1",
            "Grocer",
            "lunch",
            "#Trip",
            "any(account:Food)",
            "payee:Cafe",
        ]:
            with self.subTest(expression):
                self.assertEqual(self.kept(expression), self.fava_kept(expression))

    def test_a_minus_leaves_entries_out_and_a_plain_word_is_a_text_search(self):
        self.assertEqual(self.kept("-#trip"), ["opening", "weekly shop", "rent"])
        self.assertEqual(self.kept("#trip"), ["conference", "lunch on the trip"])
        # As in Fava, a bare word searches payee and narration, it is not a tag.
        self.assertEqual(self.kept("trip"), ["lunch on the trip"])

    def test_commas_and_parentheses_work_as_they_do_in_fava(self):
        self.assertEqual(self.kept("#trip, ^inv-1"), ["conference", "lunch on the trip", "weekly shop"])
        self.assertEqual(self.kept("(#trip, ^inv-1) -#work"), ["lunch on the trip", "weekly shop"])

    def test_a_filter_that_cannot_be_read_is_an_error_with_favas_message(self):
        for expression in ["#trip,", "#", "-", "()", "(#trip", "#trip)"]:
            with self.subTest(expression):
                answer = json.loads(ledger.matching_transactions(self.path, expression))
                self.assertFalse(answer["ok"])
                self.assertNotIn("positions", answer)
                self.assertTrue(answer["filter_error"])

    def test_the_answer_describes_the_ledger_the_summary_came_from(self):
        summary = json.loads(ledger.inspect(self.path))
        answer = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertEqual(answer["input_hash"], summary["input_hash"])
        self.assertEqual(answer["transaction_count"], len(summary["journal"]))
        # Only touched: the same ledger, so the same fingerprint.
        import os

        os.utime(self.path, (1, 1))
        touched = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertEqual(touched["input_hash"], summary["input_hash"])
        # Changed: a different ledger.
        Path(self.path).write_text(LEDGER + "\n; edited\n", encoding="utf-8")
        changed = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertNotEqual(changed["input_hash"], summary["input_hash"])

    def test_positions_follow_the_same_date_cut_as_the_journal(self):
        window = {"date_from": "2026-02-01", "date_to": "2026-03-31"}
        self.assertEqual(self.kept("-#work", **window), ["weekly shop"])
        answer = json.loads(ledger.matching_transactions(self.path, "#trip", **window))
        self.assertEqual(answer["positions"], [])
        self.assertEqual(answer["transaction_count"], 2)


if __name__ == "__main__":
    unittest.main()
