"""Each account reports the day it was opened, the day it was closed, and what it accepts.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

The Accounts screen has to tell an open account from a closed one, and an account the entry
form offers must not be one Beancount would refuse a posting to.
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

2026-01-01 open Assets:Cash USD,EUR
2026-01-01 open Assets:Old
2026-01-01 open Equity:Opening
2026-03-01 close Assets:Old

2026-01-05 * "opening"
  Assets:Cash        10.00 USD
  Equity:Opening
"""


class AccountInfo(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        path = Path(self.dir.name) / "main.beancount"
        path.write_text(LEDGER)
        payload = json.loads(ledger.inspect(str(path)))
        self.rows = {row["name"]: row for row in payload["accounts"]}

    def tearDown(self):
        self.dir.cleanup()

    def test_open_account_has_its_day_and_currencies(self):
        cash = self.rows["Assets:Cash"]
        self.assertEqual(cash["opened"], "2026-01-01")
        self.assertIsNone(cash["closed"])
        self.assertEqual(cash["allowed"], ["USD", "EUR"])

    def test_closed_account_has_its_closing_day(self):
        old = self.rows["Assets:Old"]
        self.assertEqual(old["opened"], "2026-01-01")
        self.assertEqual(old["closed"], "2026-03-01")
        self.assertEqual(old["allowed"], [])


if __name__ == "__main__":
    unittest.main()
