"""Editing a ledger must not change a byte outside what was edited, line endings included.

A ledger synced from a Windows desktop has CRLF line endings. Reading it as text translated them
to LF, so replacing or deleting one entry came back as a whole file with every line ending
rewritten, which is whitespace discarded without a word. Beancount also counts lines by `\\n`
only, while `str.splitlines` splits on a dozen other characters (form feed, U+2028, NEL...), so
one of those inside a narration shifted every line number after it.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import writer  # noqa: E402


def run(fn, data: bytes, *args):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "t.beancount"
        path.write_bytes(data)
        reply = json.loads(fn(str(path), *args))
        assert reply["ok"], reply
        return reply["content"].encode("utf-8")


CRLF = (
    b"2026-01-01 open Assets:Cash\r\n"
    b"\r\n"
    b'2026-01-02 * "old"\r\n'
    b"  Assets:Cash  1 USD\r\n"
    b"  Equity:Opening\r\n"
    b"\r\n"
    b"2026-01-03 close Assets:Cash\r\n"
)


class LineEndingsTest(unittest.TestCase):
    def test_replacing_an_entry_keeps_every_other_line_ending(self):
        out = run(writer.replace_lines, CRLF, 3, 5, '2026-01-02 * "new"\n  Assets:Cash  2 USD\n  Equity:Opening')
        self.assertEqual(
            b"2026-01-01 open Assets:Cash\r\n\r\n"
            b'2026-01-02 * "new"\r\n  Assets:Cash  2 USD\r\n  Equity:Opening\r\n'
            b"\r\n2026-01-03 close Assets:Cash\r\n",
            out,
        )

    def test_deleting_an_entry_keeps_every_other_line_ending(self):
        out = run(writer.delete_lines, CRLF, 3, 5)
        self.assertEqual(b"2026-01-01 open Assets:Cash\r\n\r\n2026-01-03 close Assets:Cash\r\n", out)

    def test_an_lf_file_stays_lf(self):
        lf = CRLF.replace(b"\r\n", b"\n")
        out = run(writer.replace_lines, lf, 3, 5, '2026-01-02 * "new"\n  A  1 USD\n  B')
        self.assertNotIn(b"\r", out)

    def test_a_character_beancount_does_not_count_as_a_line_break_does_not_shift_the_lines(self):
        # U+2028, form feed and NEL are line breaks to str.splitlines and nothing to Beancount.
        text = (
            "2026-01-01 open Assets:Cash\n"
            '2026-01-02 * "pasted from\x0ca\x85page"\n'
            "  Assets:Cash  1 USD\n"
            "  Equity:Opening\n"
            "2026-01-03 close Assets:Cash\n"
        ).encode("utf-8")
        out = run(writer.delete_lines, text, 2, 4)
        self.assertEqual(b"2026-01-01 open Assets:Cash\n2026-01-03 close Assets:Cash\n", out)

    def test_the_slice_of_such_an_entry_counts_its_lines_as_beancount_does(self):
        # entry_slice reports an end line from the text it was given.
        source = '2026-01-02 * "pasted x"\n  Assets:Cash  1 USD\n  Equity:Opening\n'
        self.assertEqual(3, len(writer.split_lines(source)))


if __name__ == "__main__":
    unittest.main()
