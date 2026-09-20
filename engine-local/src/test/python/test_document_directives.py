"""The `document` directives a ledger declares (B18).

Run from the repository root, with an interpreter that has beancount:

    ~/tools/bc-desktop/bin/python3 -m unittest discover -s engine-local/src/test/python -v

The app listed the files in the project's document folder and nothing else, so it could not
say which account a document was filed under, nor tell a document the ledger knows about
from one merely sitting on disk. Both facts live in the `document` directives, which
`inspect` did not report.

Beancount resolves a document's path against the directory of the file declaring it, so
what comes back is absolute. Relativising it is the caller's job: only the caller knows the
project root, and a document may legitimately live outside it.
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

2026-02-01 document Assets:Bank "documents/statement.pdf"
  note: "February"

2026-02-03 document Expenses:Food "documents/receipt.pdf" #scanned

2026-02-02 * "Shop" "coffee"
  document: "documents/receipt.pdf"
  Expenses:Food   12.00 USD
  Assets:Bank
"""


class DocumentDirectiveTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._dir = tempfile.TemporaryDirectory()
        cls.root = Path(cls._dir.name)
        (cls.root / "documents").mkdir()
        for name in ("statement.pdf", "receipt.pdf"):
            (cls.root / "documents" / name).write_bytes(b"%PDF-1.4\n")
        path = cls.root / "main.beancount"
        path.write_text(LEDGER)
        cls.payload = json.loads(ledger.inspect(str(path)))

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def test_reports_every_document_directive(self):
        paths = [Path(d["path"]).name for d in self.payload["documents"]]
        self.assertEqual(["receipt.pdf", "statement.pdf"], sorted(paths))

    def test_reports_the_account_each_is_filed_under(self):
        filed = {Path(d["path"]).name: d["account"] for d in self.payload["documents"]}
        self.assertEqual("Assets:Bank", filed["statement.pdf"])
        self.assertEqual("Expenses:Food", filed["receipt.pdf"])

    def test_reports_the_date_and_source_location(self):
        statement = next(
            d for d in self.payload["documents"] if d["path"].endswith("statement.pdf")
        )
        self.assertEqual("2026-02-01", statement["date"])
        self.assertTrue(statement["file"].endswith("main.beancount"))
        self.assertIsInstance(statement["line"], int)

    def test_the_path_is_absolute_as_beancount_resolved_it(self):
        # Relative in the source, absolute here. A caller that assumed otherwise would
        # compare "documents/x.pdf" against an absolute path and find no document linked.
        for document in self.payload["documents"]:
            self.assertTrue(Path(document["path"]).is_absolute(), document["path"])

    def test_a_transactions_document_metadata_is_not_a_directive(self):
        # The transaction links receipt.pdf through metadata. That is the B17 convention and
        # a different thing: exactly one `document` directive names receipt.pdf.
        naming = [d for d in self.payload["documents"] if d["path"].endswith("receipt.pdf")]
        self.assertEqual(1, len(naming))

    def test_carries_the_directives_own_tags(self):
        receipt = next(
            d for d in self.payload["documents"] if d["path"].endswith("receipt.pdf")
        )
        self.assertEqual(["scanned"], receipt["tags"])

    def test_a_written_directive_is_not_marked_automatic(self):
        for document in self.payload["documents"]:
            self.assertFalse(document["auto"], document["path"])

    def test_a_document_found_by_the_documents_option_is_marked_automatic(self):
        """B92: the app has to tell a line somebody wrote from one the folder produced.

        Unlinking removes the written line. The automatic one comes back on the next load, so
        a screen that could not tell them apart went on saying "Filed under ..." about a
        document nothing in the ledger referred to any more.
        """
        with tempfile.TemporaryDirectory() as found:
            root = Path(found)
            folder = root / "documents" / "Expenses" / "Food"
            folder.mkdir(parents=True)
            (folder / "2026-02-04.receipt.pdf").write_bytes(b"%PDF-1.4\n")
            path = root / "main.beancount"
            path.write_text(
                'option "documents" "documents"\n'
                "2026-01-01 open Expenses:Food\n"
            )

            documents = json.loads(ledger.inspect(str(path)))["documents"]

            self.assertEqual(1, len(documents))
            self.assertTrue(documents[0]["auto"])
            self.assertEqual("Expenses:Food", documents[0]["account"])

    def test_a_ledger_with_no_documents_reports_an_empty_list(self):
        with tempfile.TemporaryDirectory() as empty:
            path = Path(empty) / "main.beancount"
            path.write_text("2026-01-01 open Assets:Bank\n")
            self.assertEqual([], json.loads(ledger.inspect(str(path)))["documents"])


if __name__ == "__main__":
    unittest.main()
