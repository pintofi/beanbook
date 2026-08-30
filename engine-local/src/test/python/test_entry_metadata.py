"""Metadata on journal entries, and the document links carried in it (B17).

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

`inspect` read `entry.meta` for the filename and line number and reported nothing else, so
an entry that links a receipt -- which `fava.plugins.link_documents` records as a `document`
metadata key -- looked identical to one that does not. The app cannot offer to open a
document it has never been told about.

Beancount puts its own bookkeeping in the same dict: `filename`, `lineno`, and keys it
prefixes with two underscores. Those are not the user's metadata and reporting them would
put "__automatic__" in front of somebody as though they had written it.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

LEDGER = """
2026-01-01 open Assets:Bank
2026-01-01 open Expenses:Food
2026-01-01 open Equity:Opening

2026-01-01 * "opening"
  Assets:Bank    100.00 USD
  Equity:Opening

2026-02-01 * "Shop" "coffee"
  document: "documents/receipt.pdf"
  invoice: "A-1"
  Expenses:Food   12.00 USD
  Assets:Bank

2026-02-02 * "Plain" "nothing attached"
  Expenses:Food   3.00 USD
  Assets:Bank
"""


class EntryMetadataTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._dir = tempfile.TemporaryDirectory()
        path = Path(cls._dir.name) / "main.beancount"
        path.write_text(LEDGER)
        cls.journal = json.loads(ledger.inspect(str(path)))["journal"]

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def _entry(self, narration):
        for row in self.journal:
            if row["narration"] == narration:
                return row
        self.fail(f"{narration!r} not in {[r['narration'] for r in self.journal]}")

    def test_user_metadata_is_reported(self):
        entry = self._entry("coffee")

        self.assertEqual("documents/receipt.pdf", entry["metadata"]["document"])
        self.assertEqual("A-1", entry["metadata"]["invoice"])

    def test_beancounts_own_bookkeeping_is_not_reported_as_the_users(self):
        entry = self._entry("coffee")

        for key in entry["metadata"]:
            self.assertFalse(
                key in ("filename", "lineno") or key.startswith("__"),
                f"{key!r} is Beancount's, not the user's: {entry['metadata']}",
            )

    def test_an_entry_without_metadata_reports_an_empty_map(self):
        self.assertEqual({}, self._entry("nothing attached")["metadata"])

    def test_the_file_and_line_are_still_reported_separately(self):
        entry = self._entry("coffee")

        self.assertTrue(entry["file"].endswith("main.beancount"))
        self.assertIsInstance(entry["line"], int)


if __name__ == "__main__":
    unittest.main()
