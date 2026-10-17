"""A ledger's own display rules, reported rather than guessed.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

Beancount builds a display context from the numbers actually in the file, and
`render_commas` and `inferred_tolerance_default` are the user's own options. The app used
none of them, so a currency written to three places with separators was shown at two places
without them, and the app's own idea of "close enough to balance" overrode the file's.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

THREE_PLACES = """
option "operating_currency" "PKR"
option "render_commas" "TRUE"
option "inferred_tolerance_default" "PKR:0.005"

2026-01-01 open Assets:Cash PKR
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash    1234567.891 PKR
  Equity:Opening
"""

PLAIN = """
2026-01-01 open Assets:Cash USD
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash    10.00 USD
  Equity:Opening
"""


class DisplayOptionsTest(unittest.TestCase):

    def inspect(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "main.beancount"
            path.write_text(text, encoding="utf-8")
            return json.loads(ledger.inspect(str(path)))

    def test_the_places_a_currency_is_written_to_are_reported(self):
        data = self.inspect(THREE_PLACES)
        self.assertEqual(3, data["display_precision"]["PKR"])

    def test_render_commas_is_reported(self):
        self.assertTrue(self.inspect(THREE_PLACES)["render_commas"])
        self.assertFalse(self.inspect(PLAIN)["render_commas"])

    def test_the_files_own_tolerance_is_reported(self):
        self.assertEqual({"PKR": "0.005"}, self.inspect(THREE_PLACES)["tolerances"])

    def test_a_file_that_says_nothing_reports_nothing(self):
        data = self.inspect(PLAIN)
        self.assertEqual({}, data["tolerances"])
        self.assertFalse(data["render_commas"])
        # Two places, because that is how the file writes them -- inferred, not assumed.
        self.assertEqual(2, data["display_precision"]["USD"])
