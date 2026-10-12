"""Deleting an entry takes the blank line that set it apart (B99)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import writer  # noqa: E402


def delete(text, start, end):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "t.beancount"
        path.write_text(text)
        return json.loads(writer.delete_lines(str(path), start, end))["content"]


class DeleteLinesTest(unittest.TestCase):
    def test_entry_written_by_the_app_goes_with_the_blank_line_after_it(self):
        text = "2026-01-01 open Assets:Cash\n\n2026-01-02 *\n  A  1 USD\n  B\n\n"
        self.assertEqual("2026-01-01 open Assets:Cash\n\n", delete(text, 3, 5))

    def test_between_two_entries_takes_one_blank_line(self):
        self.assertEqual("a\n\nb\n", delete("a\n\nENTRY\n  p\n\nb\n", 3, 4))

    def test_last_entry_with_no_blank_after_takes_the_one_before(self):
        self.assertEqual("a\n", delete("a\n\nENTRY\n  p\n", 3, 4))

    def test_glued_entry_takes_nothing_extra(self):
        self.assertEqual("a\nb\n", delete("a\nENTRY\nb\n", 2, 2))


if __name__ == "__main__":
    unittest.main()
