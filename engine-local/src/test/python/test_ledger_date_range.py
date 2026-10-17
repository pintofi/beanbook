"""A date range narrows what is computed, the way Fava's time filter does.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

Balances is a filter box in Fava and was a whole-file view here, so a person asking "what
did I spend in March" had to read a year's figures and subtract. The range is applied to the
entries before anything is computed from them -- balances, the journal, and which accounts
exist all follow from the same narrowed set, because a balance for one period beside a
journal for another is two answers presented as one.

Prices are the exception, and the case worth a test of its own: a rate published before the
range still values a holding inside it, so the price map is built from the whole file.
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
2026-01-01 open Equity:Opening

2026-01-05 * "opening"
  Assets:Cash        1000.00 USD
  Equity:Opening

2026-01-10 price HOOL 100.00 USD

2026-02-10 * "january-ish groceries"
  Expenses:Food        10.00 USD
  Assets:Cash

2026-03-10 * "march groceries"
  Expenses:Food        25.00 USD
  Assets:Cash

2026-03-11 * "buy shares"
  Assets:Broker           2 HOOL {100.00 USD}
  Assets:Cash
"""


class DateRangeTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "main.beancount"
        self.path.write_text(LEDGER, encoding="utf-8")

    def tearDown(self):
        self.dir.cleanup()

    def inspect(self, date_from="", date_to=""):
        return json.loads(
            ledger.inspect(str(self.path), 0, "", True, date_from, date_to)
        )

    def balance(self, data, account):
        for row in data["accounts"]:
            if row["name"] == account:
                return row["positions"]
        return None

    def test_without_a_range_every_entry_counts(self):
        data = self.inspect()
        self.assertEqual([{"number": "35.00", "currency": "USD"}], self.balance(data, "Expenses:Food"))

    def test_a_range_narrows_the_balances(self):
        data = self.inspect("2026-03-01", "2026-03-31")
        self.assertEqual([{"number": "25.00", "currency": "USD"}], self.balance(data, "Expenses:Food"))

    def test_the_journal_covers_the_same_period_as_the_balances(self):
        data = self.inspect("2026-03-01", "2026-03-31")
        dates = sorted({entry["date"] for entry in data["journal"]})
        self.assertEqual(["2026-03-10", "2026-03-11"], dates)

    def test_a_price_published_before_the_range_still_values_a_holding_inside_it(self):
        # The share was bought inside the range; its only price directive is outside it.
        # Dropping that price would report the holding at cost and label it market value.
        data = self.inspect("2026-03-01", "2026-03-31")
        broker = None
        for row in data["accounts"]:
            if row["name"] == "Assets:Broker":
                broker = row
        self.assertIsNotNone(broker)
        self.assertEqual([{"number": "200.00", "currency": "USD"}], broker["at_value"])

    def test_an_open_bound_means_unbounded_on_that_side(self):
        upto = self.inspect("", "2026-02-28")
        self.assertEqual([{"number": "10.00", "currency": "USD"}], self.balance(upto, "Expenses:Food"))
        since = self.inspect("2026-03-01", "")
        self.assertEqual([{"number": "25.00", "currency": "USD"}], self.balance(since, "Expenses:Food"))

    def test_a_half_typed_date_is_ignored_rather_than_emptying_the_screen(self):
        # What a filter box holds between keystrokes. Treating "2026-03" as the start of time
        # would blank the balances while somebody is still typing the day.
        data = self.inspect("2026-03", "")
        self.assertEqual([{"number": "35.00", "currency": "USD"}], self.balance(data, "Expenses:Food"))
