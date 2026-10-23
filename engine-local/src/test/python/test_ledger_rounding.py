"""A total is rounded once, at the end, not once per account (#60).

`inspect` used to round each account's operating-currency value to two places, and the app then
added the rounded parts up. With three accounts each worth 1.005 USD, the parts read 1.00, 1.00
and 1.00 and the total 3.00, while Fava, which adds exact values and rounds the sum, says 3.02
(3.015 to two places, half to even).

Now the engine reports the exact value of every account, and the display rounds the sum once,
to the number of places the ledger writes that currency to.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v
"""

import json
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

LEDGER = """
option "operating_currency" "USD"

2026-01-01 open Assets:A
2026-01-01 open Assets:B
2026-01-01 open Assets:C
2026-01-01 open Equity:Opening
2026-01-01 open Assets:Cash USD

2026-01-01 * "opening"
  Assets:A        1.00 EUR
  Assets:B        1.00 EUR
  Assets:C        1.00 EUR
  Assets:Cash   100.00 USD
  Equity:Opening

2026-06-01 price EUR 1.005 USD
"""


class RoundingTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._dir = tempfile.TemporaryDirectory()
        path = Path(cls._dir.name) / "main.beancount"
        path.write_text(LEDGER)
        cls.payload = json.loads(ledger.inspect(str(path)))

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def _value(self, account):
        row = next(r for r in self.payload["accounts"] if r["name"] == account)
        usd = [p for p in row["at_value"] if p["currency"] == "USD"]
        self.assertEqual(1, len(usd), f"{account}: {row['at_value']}")
        return Decimal(usd[0]["number"])

    def test_an_accounts_value_is_reported_exactly(self):
        for name in ("Assets:A", "Assets:B", "Assets:C"):
            self.assertEqual(Decimal("1.005"), self._value(name), name)

    def test_the_parts_add_up_to_the_exact_total_that_fava_rounds_once(self):
        total = sum(self._value(n) for n in ("Assets:A", "Assets:B", "Assets:C"))
        self.assertEqual(Decimal("3.015"), total)
        self.assertEqual(Decimal("3.02"), total.quantize(Decimal("0.01")), "rounded once, half to even")

    def test_the_operating_currency_always_has_a_display_precision(self):
        # The display used to rely on the engine rounding to two places. With exact values the
        # app rounds, so it must be told how many places the currency is shown to.
        self.assertIn("USD", self.payload["display_precision"])
        self.assertGreaterEqual(self.payload["display_precision"]["USD"], 0)

    def test_an_operating_currency_the_ledger_never_writes_is_shown_to_two_places(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(
                'option "operating_currency" "CHF"\n'
                "2026-01-01 open Assets:A\n2026-01-01 open Equity:O\n"
                '2026-01-01 * "x"\n  Assets:A 10 EUR\n  Equity:O\n',
            )
            payload = json.loads(ledger.inspect(str(path)))
        self.assertEqual(2, payload["display_precision"]["CHF"])

    def test_a_currency_the_ledger_writes_to_other_places_keeps_them(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(
                'option "operating_currency" "JPY"\n'
                "2026-01-01 open Assets:A\n2026-01-01 open Equity:O\n"
                '2026-01-01 * "x"\n  Assets:A 1500 JPY\n  Equity:O\n',
            )
            payload = json.loads(ledger.inspect(str(path)))
        self.assertEqual(0, payload["display_precision"]["JPY"])


if __name__ == "__main__":
    unittest.main()
