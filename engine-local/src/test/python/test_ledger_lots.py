"""Held at cost, sold at a price: what a posting was worth, not just how much of it.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

The journal reported units alone, so `10 ACME {12.50 USD}` and `10 ACME` crossed to the app
as the same posting, and a mutual-fund conversion -- a sale out of a named lot at a price and
a purchase into a dated one -- read as four bare numbers.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

# The user's own conversion, with the opening lot it reduces.
CONVERSION = """
option "operating_currency" "PKR"

2020-01-01 open Assets:Non-Current:Investments:Mutual-Funds:Equity:Example-Index-Fund
2020-01-01 open Assets:Non-Current:Investments:Mutual-Funds:Debt:Example-Cash-Fund
2020-01-01 open Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Sale
2020-01-01 open Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Cost
2020-01-01 open Assets:Bank

2025-01-01 * "UBL Funds" "Purchase"
  Assets:Non-Current:Investments:Mutual-Funds:Equity:Example-Index-Fund  150.00 EIF {"lot-a", 400.00 PKR}
  Assets:Bank

2026-09-03 * "UBL Funds" "Coversion from EIF to AICF"
  Assets:Non-Current:Investments:Mutual-Funds:Equity:Example-Index-Fund  -150.00 EIF {"lot-a"} @ 120.0500 PKR
  Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Sale  -150.00*120.0500 PKR
  Income:Capital-Gains:Mutual-Funds:Equity:Example-Index-Fund:Cost
  Assets:Non-Current:Investments:Mutual-Funds:Debt:Example-Cash-Fund  710.47 AICF {103.2642 PKR, 2026-09-03}
"""

TOTAL_PRICE = """
2020-01-01 open Assets:A
2020-01-01 open Assets:B

2025-01-01 * "exchange"
  Assets:A  -100.00 EUR @@ 110.00 USD
  Assets:B   110.00 USD
"""


class PostingLotsTest(unittest.TestCase):

    def inspect(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(text, encoding="utf-8")
            return json.loads(ledger.inspect(str(path)))

    def conversion(self):
        data = self.inspect(CONVERSION)
        self.assertEqual([], data["errors"])
        return data["journal"][-1]["postings"]

    def test_a_reduction_carries_the_lot_it_matched(self):
        sale = self.conversion()[0]
        self.assertEqual("-150.00", sale["number"])
        self.assertEqual("EIF", sale["currency"])
        # The file says `{"lot-a"}`; Beancount books that label against the lot, and the
        # whole cost is what the app has to show -- the label alone says nothing about value.
        self.assertEqual("400.00", sale["cost"]["number"])
        self.assertEqual("PKR", sale["cost"]["currency"])
        self.assertEqual("2025-01-01", sale["cost"]["date"])
        self.assertEqual("lot-a", sale["cost"]["label"])
        self.assertEqual({"number": "120.0500", "currency": "PKR"}, sale["price"])

    def test_an_acquisition_carries_the_lot_it_creates(self):
        purchase = self.conversion()[3]
        self.assertEqual("710.47", purchase["number"])
        self.assertEqual("103.2642", purchase["cost"]["number"])
        self.assertEqual("2026-09-03", purchase["cost"]["date"])
        self.assertIsNone(purchase["cost"]["label"])
        self.assertIsNone(purchase["price"])

    def test_arithmetic_arrives_as_the_number_it_evaluates_to(self):
        # `-150.00*120.0500 PKR` is arithmetic Beancount evaluates while parsing.
        self.assertEqual("-18007.500000", self.conversion()[1]["number"])

    def test_an_interpolated_posting_carries_no_cost_of_its_own(self):
        residual = self.conversion()[2]
        self.assertEqual("4641.383826", residual["number"])
        self.assertIsNone(residual["cost"])

    def test_a_total_price_arrives_per_unit(self):
        # `@@ 110.00 USD` over 100 units. Beancount normalises it, and reporting the total
        # as though it were the unit price would overstate every one of them by a hundred.
        posting = self.inspect(TOTAL_PRICE)["journal"][-1]["postings"][0]
        self.assertEqual({"number": "1.1", "currency": "USD"}, posting["price"])

    def test_an_ordinary_posting_says_so(self):
        posting = self.inspect(TOTAL_PRICE)["journal"][-1]["postings"][1]
        self.assertIsNone(posting["cost"])
        self.assertIsNone(posting["price"])


if __name__ == "__main__":
    unittest.main()
