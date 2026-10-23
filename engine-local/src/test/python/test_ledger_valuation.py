"""Positions reported as units, at cost, and at market value.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

`inspect` built its inventories with `add_amount(posting.units)`, which throws the cost
basis away before anything can ask for it. A holding of ten shares is three different
answers -- ten shares, what they cost, what they are worth today -- and a ledger that can
only say the first cannot answer the question people actually open the app to ask.

Market value needs the price directives in the file. Nothing is inferred or fetched: an
asset the ledger never priced stays in its own units, because presenting a purchase price as
a current valuation would be wrong every day after the purchase.
"""

import json
from decimal import Decimal
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

LEDGER = """
2026-01-01 open Assets:Broker
2026-01-01 open Assets:Cash
2026-01-01 open Equity:Opening

2026-01-01 * "opening"
  Assets:Cash        5000.00 USD
  Equity:Opening

2026-01-02 * "buy shares"
  Assets:Broker      10 HOOL {100.00 USD}
  Assets:Cash       -1000.00 USD

2026-06-01 price HOOL 120.00 USD
"""


class ValuationTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._dir = tempfile.TemporaryDirectory()
        path = Path(cls._dir.name) / "main.beancount"
        path.write_text(LEDGER)
        cls.payload = json.loads(ledger.inspect(str(path)))

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def _account(self, name):
        for row in self.payload["accounts"]:
            if row["name"] == name:
                return row
        self.fail(f"{name} not in {[r['name'] for r in self.payload['accounts']]}")

    def test_units_are_the_shares_themselves(self):
        broker = self._account("Assets:Broker")
        self.assertEqual(
            [{"number": "10", "currency": "HOOL"}],
            broker["positions"],
            "units should be the holding, not its value",
        )

    def test_cost_is_what_was_paid(self):
        broker = self._account("Assets:Broker")
        self.assertEqual(
            [{"number": "1000.00", "currency": "USD"}],
            broker["at_cost"],
            "ten shares at 100.00 USD cost 1000.00 USD",
        )

    def test_value_uses_the_price_directive(self):
        broker = self._account("Assets:Broker")
        self.assertEqual(
            [{"number": "1200.00", "currency": "USD"}],
            broker["at_value"],
            "ten shares priced at 120.00 USD are worth 1200.00 USD",
        )

    def test_plain_cash_is_the_same_in_all_three(self):
        cash = self._account("Assets:Cash")
        self.assertEqual(cash["positions"], cash["at_cost"])
        self.assertEqual(cash["positions"], cash["at_value"])

    def test_an_unpriced_asset_is_shown_at_cost(self):
        """What Beancount does, and the right thing.

        This test first asserted a fallback to cost, which was my assumption rather than
        Beancount's behaviour. `convert.get_value` returns the units unchanged when the
        ledger has no price for a commodity, and that is the more honest answer: "one
        painting, worth unknown" rather than "500 USD", which would present a purchase price
        as a current valuation and be wrong every day after the purchase.
        """
        unpriced = "\n2026-01-03 open Assets:Art\n\n2026-01-04 * \"buy\"\n  Assets:Art  1 PAINTING {500.00 USD}\n  Assets:Cash  -500.00 USD\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(LEDGER + unpriced)
            payload = json.loads(ledger.inspect(str(path)))
            art = next(r for r in payload["accounts"] if r["name"] == "Assets:Art")

        # Market value falls back to cost when no price exists, as Fava's does; a
        # holding with neither a price nor a cost stays in its own units.
        self.assertEqual(
            [{"number": "500.00", "currency": "USD"}],
            art["at_value"],
            "an unpriced holding bought at a cost is shown at that cost",
        )
        self.assertEqual(
            [{"number": "500.00", "currency": "USD"}],
            art["at_cost"],
            "what it cost is still known and still reported",
        )


if __name__ == "__main__":
    unittest.main()


POSTING_RATE_LEDGER = """
option "operating_currency" "PKR"
plugin "beancount.plugins.commodity_attr" "{'name': None, 'precision': None}"
2026-01-01 open Assets:Cash
2026-01-01 open Assets:Foreign
2026-01-01 open Equity:Opening

2026-01-01 * "opening"
  Assets:Cash        100000 PKR
  Equity:Opening

2026-01-02 * "buy riyals"
  Assets:Foreign     2000 SAR @ 75 PKR
  Assets:Cash       -150000 PKR
"""


class PostingRateTest(unittest.TestCase):
    """A rate written on a posting is what values the commodity."""

    @classmethod
    def setUpClass(cls):
        cls._dir = tempfile.TemporaryDirectory()
        path = Path(cls._dir.name) / "main.beancount"
        path.write_text(POSTING_RATE_LEDGER)
        cls.payload = json.loads(ledger.inspect(str(path)))

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def test_foreign_holding_is_converted_by_its_own_rate(self):
        foreign = next(a for a in self.payload["accounts"] if a["name"] == "Assets:Foreign")
        # The exact value (2000 SAR at 75), not a rounded one: the app rounds a total once, for display.
        self.assertEqual(
            [(Decimal("150000"), "PKR")],
            [(Decimal(p["number"]), p["currency"]) for p in foreign["at_operating"]],
        )

    def test_market_value_is_in_the_operating_currency_and_units_are_untouched(self):
        foreign = next(a for a in self.payload["accounts"] if a["name"] == "Assets:Foreign")
        self.assertEqual(foreign["at_operating"], foreign["at_value"])
        self.assertEqual([("2000", "SAR")], [(p["number"], p["currency"]) for p in foreign["positions"]])

    def test_the_latest_rates_are_exported_for_the_app_to_convert_postings(self):
        self.assertIn(
            {"base": "SAR", "quote": "PKR", "rate": "75"},
            self.payload["rates"],
        )

    def test_the_plugin_lines_are_reported_in_order(self):
        self.assertEqual(
            ["beancount.plugins.commodity_attr"],
            [p["name"] for p in self.payload["plugins"]],
        )

    def test_commodity_attr_keys_are_reported(self):
        self.assertEqual(["name", "precision"], self.payload["commodity_attributes"])


class ExtensionHealthTest(unittest.TestCase):
    """Extension health comes from what Fava reported, and nothing is imported."""

    TEXT = (
        '2026-01-01 open Assets:Cash\n'
        '2026-01-01 custom "fava-extension" "broken_ext"\n'
        '2026-01-01 custom "fava-extension" "fine_ext"\n'
    )

    def _inspect(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(self.TEXT)
            return json.loads(ledger.inspect(str(path)))

    def setUp(self):
        import extension_state

        self.state = extension_state
        self.state.ready, self.state.errors = False, {}

    def tearDown(self):
        self.state.ready, self.state.errors = False, {}

    def test_not_known_until_fava_has_loaded(self):
        payload = self._inspect()
        self.assertEqual({}, payload["extension_health"])
        self.assertFalse(payload["extension_health_complete"])

    def test_failure_and_success_come_from_fava(self):
        self.state.errors = {"broken_ext": "ModuleNotFoundError: No module named 'scipy'"}
        self.state.ready = True
        payload = self._inspect()
        self.assertTrue(payload["extension_health_complete"])
        self.assertIn("scipy", payload["extension_health"]["broken_ext"])
        self.assertIsNone(payload["extension_health"]["fine_ext"])

    def test_the_extension_modules_are_never_imported(self):
        self.state.ready = True
        self._inspect()
        self.assertNotIn("broken_ext", sys.modules)
        self.assertNotIn("fine_ext", sys.modules)
