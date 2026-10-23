"""The two options that change Beancount's balance tolerance are reported to the app (#50).

`tolerance_multiplier` scales the tolerance inferred from the last decimal place (default 0.5),
and `infer_tolerance_from_cost` widens it by the cost and price terms (default off). The entry form
checks balance with the same rule, so it needs both; the ledger already reported
`inferred_tolerance_default`.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

BODY = """
2026-01-01 open Assets:A
2026-01-01 open Equity:O
2026-01-02 * "x"
  Assets:A  10.00 USD
  Equity:O
"""


def inspect(text):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "main.beancount"
        path.write_text(text)
        return json.loads(ledger.inspect(str(path)))


class ToleranceOptionsTest(unittest.TestCase):

    def test_the_defaults_are_reported_as_beancount_has_them(self):
        payload = inspect(BODY)
        self.assertEqual("0.5", payload["tolerance_multiplier"])
        self.assertIs(False, payload["infer_tolerance_from_cost"])

    def test_a_ledger_that_sets_them_is_reported_as_set(self):
        payload = inspect(
            'option "tolerance_multiplier" "1.0"\n'
            'option "infer_tolerance_from_cost" "TRUE"\n' + BODY
        )
        self.assertEqual("1.0", payload["tolerance_multiplier"])
        self.assertIs(True, payload["infer_tolerance_from_cost"])


if __name__ == "__main__":
    unittest.main()
