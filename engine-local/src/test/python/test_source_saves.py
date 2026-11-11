"""Fava's file editor saves through the app's write protocol, not by writing the file itself."""

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import engine  # noqa: E402
import source_saves  # noqa: E402

LEDGER = (
    '2024-01-01 open Assets:Cash\n2024-01-01 open Expenses:Food\n\n'
    '2024-01-02 * "Lunch"\n  Expenses:Food  5.00 USD\n  Assets:Cash\n'
)


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Sink:
    """Stands in for the app: commits the bytes, or answers with a refusal."""

    def __init__(self, answer="ok"):
        self.answer = answer
        self.calls = []

    def save(self, file_path, content_path, expected_sha):
        self.calls.append((file_path, Path(content_path).read_bytes(), expected_sha))
        if self.answer == "ok":
            Path(file_path).write_bytes(self.calls[-1][1])
        return self.answer


class EditorSaveTest(unittest.TestCase):
    def setUp(self):
        from fava.application import create_app

        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.main = Path(self.dir.name) / "main.beancount"
        self.main.write_text(LEDGER, encoding="utf-8")
        source_saves.install()
        app = create_app([self.main], load=True, poll_watcher=False)
        self.client = app.test_client()
        self.slug = engine._discover_slug(app, self.main)
        self.addCleanup(source_saves.set_sink, None)

    def put(self, text, sha256sum=None):
        return self.client.put(
            f"/{self.slug}/api/source",
            json={"file_path": str(self.main), "source": text, "sha256sum": sha256sum or sha(LEDGER)},
        )

    def test_the_save_goes_to_the_app_and_comes_back_in_fava_s_shape(self):
        sink = Sink()
        source_saves.set_sink(sink)
        edited = LEDGER + '\n2024-01-03 * "Tea"\n  Expenses:Food  2.00 USD\n  Assets:Cash\n'
        response = self.put(edited)
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        self.assertEqual(sha(edited), response.get_json()["data"])
        self.assertEqual([(str(self.main), edited.encode("utf-8"), sha(LEDGER))], sink.calls)

    def test_a_refusal_from_the_app_is_an_error_and_the_file_is_unchanged(self):
        source_saves.set_sink(Sink("error: The file changed since it was read."))
        response = self.put(LEDGER + "\n; more\n")
        self.assertNotEqual(200, response.status_code)
        self.assertIn("changed since it was read", response.get_json()["error"])
        self.assertEqual(LEDGER, self.main.read_text(encoding="utf-8"))

    def test_without_the_app_nothing_is_written(self):
        source_saves.set_sink(None)
        response = self.put(LEDGER + "\n; more\n")
        self.assertNotEqual(200, response.status_code)
        self.assertEqual(LEDGER, self.main.read_text(encoding="utf-8"))

    def test_a_stale_hash_is_refused_before_the_app_is_asked(self):
        sink = Sink()
        source_saves.set_sink(sink)
        response = self.put(LEDGER + "\n; more\n", sha256sum="0" * 64)
        self.assertNotEqual(200, response.status_code)
        self.assertEqual([], sink.calls)

    def test_a_file_that_is_not_a_source_file_is_refused_before_the_app_is_asked(self):
        sink = Sink()
        source_saves.set_sink(sink)
        other = Path(self.dir.name) / "other.beancount"
        other.write_text("", encoding="utf-8")
        response = self.client.put(
            f"/{self.slug}/api/source",
            json={"file_path": str(other), "source": "x", "sha256sum": sha("")},
        )
        self.assertNotEqual(200, response.status_code)
        self.assertEqual([], sink.calls)

    def test_a_file_with_windows_line_endings_is_written_with_them(self):
        crlf = LEDGER.replace("\n", "\r\n")
        self.main.write_bytes(crlf.encode("utf-8"))
        sink = Sink()
        source_saves.set_sink(sink)
        response = self.client.put(
            f"/{self.slug}/api/source",
            json={"file_path": str(self.main), "source": LEDGER + "; x\n", "sha256sum": sha(self.main.read_text(encoding="utf-8"))},
        )
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        self.assertEqual((LEDGER + "; x\n").replace("\n", "\r\n").encode("utf-8"), sink.calls[0][1])


if __name__ == "__main__":
    unittest.main()
