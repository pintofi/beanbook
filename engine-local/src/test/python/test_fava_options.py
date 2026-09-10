"""What the file asks Fava for, reported so the app can follow it (B67).

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

Fava keeps its own settings in the Beancount file, as `custom "fava-option"` directives.
They are ordinary Custom directives to Beancount, which is why a plain load can read exactly
what Fava would read -- and why the app had no excuse for ignoring them.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

GERMAN = """
option "render_commas" "TRUE"
2026-01-01 custom "fava-option" "locale" "de_DE"
2026-01-01 custom "fava-option" "invert-income-liabilities-equity" "true"
2026-01-01 custom "fava-option" "currency-column" "80"
2026-01-01 custom "fava-option" "indent" "4"

2026-01-01 open Assets:Cash EUR
2026-01-01 open Income:Salary EUR

2026-01-02 * "pay"
  Assets:Cash    1234.56 EUR
  Income:Salary
"""

PLAIN = """
2026-01-01 open Assets:Cash USD
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash    10.00 USD
  Equity:Opening
"""

UNKNOWN = """
2026-01-01 custom "fava-option" "sidebar-show-queries" "9"
2026-01-01 custom "budget" "Expenses:Food" "monthly" 100.00 USD

2026-01-01 open Assets:Cash USD
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash    10.00 USD
  Equity:Opening
"""


RENAMED = """
option "name_assets" "Aktiva"
option "name_liabilities" "Passiva"
option "name_equity" "Eigenkapital"
option "name_income" "Einnahmen"
option "name_expenses" "Ausgaben"
option "documents" "belege"

2026-01-01 open Aktiva:Kasse EUR
2026-01-01 open Einnahmen:Lohn EUR

2026-01-02 * "Lohn"
  Aktiva:Kasse    300.00 EUR
  Einnahmen:Lohn
"""


class FavaOptionsTest(unittest.TestCase):

    def inspect(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(text, encoding="utf-8")
            return json.loads(ledger.inspect(str(path)))

    def test_the_options_the_app_can_honour_are_reported(self):
        options = self.inspect(GERMAN)["fava_options"]

        self.assertEqual("de_DE", options["locale"])
        self.assertEqual("true", options["invert_income_liabilities_equity"])
        self.assertEqual("80", options["currency_column"])
        self.assertEqual("4", options["indent"])

    def test_hyphens_and_underscores_are_the_same_option(self):
        # Fava's own option object uses underscores; the file is written with hyphens.
        options = self.inspect(GERMAN)["fava_options"]
        self.assertIn("invert_income_liabilities_equity", options)

    def test_a_file_that_asks_for_nothing_reports_nothing(self):
        self.assertEqual({}, self.inspect(PLAIN)["fava_options"])

    def test_options_the_app_cannot_honour_are_left_alone(self):
        # Reporting an option the app ignores would invite the app to half-honour it.
        # `custom "budget"` is another tool's directive entirely and must not be read here.
        self.assertEqual({}, self.inspect(UNKNOWN)["fava_options"])

    def test_the_ledger_still_loads_without_an_error(self):
        self.assertEqual([], self.inspect(GERMAN)["errors"])


class AccountNamesTest(unittest.TestCase):
    """What this file calls its five trees, and where it keeps documents (B70)."""

    def inspect(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(text, encoding="utf-8")
            # Beancount reports a missing document root as an error, so the fixture makes the
            # folder it names -- the point here is the option, not the error.
            (Path(directory) / "belege").mkdir(exist_ok=True)
            return json.loads(ledger.inspect(str(path)))

    def test_renamed_roots_are_reported(self):
        names = self.inspect(RENAMED)["account_names"]

        self.assertEqual("Aktiva", names["assets"])
        self.assertEqual("Passiva", names["liabilities"])
        self.assertEqual("Eigenkapital", names["equity"])
        self.assertEqual("Einnahmen", names["income"])
        self.assertEqual("Ausgaben", names["expenses"])

    def test_a_file_that_renames_nothing_reports_beancounts_own(self):
        names = self.inspect(PLAIN)["account_names"]

        self.assertEqual(
            ["Assets", "Equity", "Expenses", "Income", "Liabilities"],
            sorted(names.values()),
        )

    def test_the_document_directories_are_reported(self):
        self.assertEqual(["belege"], self.inspect(RENAMED)["document_dirs"])

    def test_a_file_with_no_document_option_reports_none(self):
        self.assertEqual([], self.inspect(PLAIN)["document_dirs"])

    def test_the_renamed_ledger_still_loads_without_an_error(self):
        self.assertEqual([], self.inspect(RENAMED)["errors"])
