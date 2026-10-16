"""Cases found reading the Python layer file by file."""

import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402
import plugins  # noqa: E402


class DisplayRoundingTest(unittest.TestCase):
    """A converted figure is rounded the way Beancount and Fava round: half to even."""

    def test_an_exact_half_goes_to_the_even_neighbour(self):
        self.assertEqual(Decimal("2.34"), ledger._display_round(Decimal("2.345")))
        self.assertEqual(Decimal("2.36"), ledger._display_round(Decimal("2.355")))
        self.assertEqual(Decimal("0.12"), ledger._display_round(Decimal("0.125")))

    def test_everything_else_rounds_to_the_nearest(self):
        self.assertEqual(Decimal("2007.25"), ledger._display_round(Decimal("2007.250000")))
        self.assertEqual(Decimal("0.01"), ledger._display_round(Decimal("0.0051")))
        self.assertEqual(Decimal("-2.35"), ledger._display_round(Decimal("-2.3500001")))


class CommodityAttributesTest(unittest.TestCase):
    def options(self, config):
        return {"plugin": [("beancount.plugins.commodity_attr", config)]}

    def test_the_keys_the_plugin_demands_are_read(self):
        self.assertEqual(["name", "precision"], ledger._commodity_attributes(self.options("{'name': None, 'precision': None}")))

    def test_a_deeply_nested_config_is_not_a_ledger_that_will_not_open(self):
        hostile = "(" * 5000 + "1" + ")" * 5000
        self.assertEqual([], ledger._commodity_attributes(self.options(hostile)))

    def test_text_that_is_not_python_is_no_attributes(self):
        self.assertEqual([], ledger._commodity_attributes(self.options("not python at all (")))


class LiveOutputTest(unittest.TestCase):
    def test_a_handle_cannot_name_a_path(self):
        live = plugins._LiveOutput("../../escape")
        try:
            path = live._path
        finally:
            live.finish()
        self.assertIsNotNone(path)
        self.assertNotIn("..", Path(path).name)
        self.assertEqual(Path(path).parent, Path(path).resolve().parent)


if __name__ == "__main__":
    unittest.main()
