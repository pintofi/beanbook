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
    def test_append_then_delete_is_the_original_file(self):
        original = "2026-01-01 open Assets:Cash\n"
        appended = original + "\n2026-01-02 *\n  A  1 USD\n  B\n"
        self.assertEqual(original, delete(appended, 3, 5))

    def test_between_two_entries_takes_one_blank_line(self):
        self.assertEqual("a\n\nb\n", delete("a\n\nENTRY\n  p\n\nb\n", 3, 4))

    def test_first_entry_takes_the_blank_line_after(self):
        self.assertEqual("b\n", delete("ENTRY\n  p\n\nb\n", 1, 2))

    def test_glued_entry_takes_nothing_extra(self):
        self.assertEqual("a\nb\n", delete("a\nENTRY\nb\n", 2, 2))


if __name__ == "__main__":
    unittest.main()
