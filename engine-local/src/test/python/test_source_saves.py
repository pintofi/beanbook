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
        self.operations = []

    def commit(self, file_path, content_path, expected_sha, operation="editor-save", via_share=False):
        self.operations.append(operation)
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

    def shared_put(self, text):
        return self.client.put(
            f"/{self.slug}/api/source",
            json={"file_path": str(self.main), "source": text, "sha256sum": sha(LEDGER)},
            environ_overrides={"beanbook.via": "share"},
        )

    def test_an_ordinary_edit_from_a_shared_page_is_committed_and_marked_as_shared(self):
        sink = Sink()
        source_saves.set_sink(sink)
        response = self.shared_put(LEDGER + '\n2024-01-03 * "Tea"\n  Expenses:Food  2.00 USD\n  Assets:Cash\n')
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        self.assertEqual(1, len(sink.calls))

    def test_a_shared_page_cannot_add_lines_that_point_fava_at_files_or_code(self):
        sink = Sink()
        source_saves.set_sink(sink)
        for line in [
            '2020-01-01 document Assets:Cash "/data/user/0/app/files/state/sharing"',
            'include "/data/user/0/app/files/projects/other/main.beancount"',
            'plugin "evil.module"',
            'option "operating_currency" "EUR"',
            '2020-01-01 custom "fava-extension" "evil"',
            '2020-01-01 custom "fava-option" "import-config" "/x.py"',
        ]:
            response = self.shared_put(LEDGER + "\n" + line + "\n")
            self.assertNotEqual(200, response.status_code, line)
            self.assertIn("in the app", response.get_json()["error"])
        self.assertEqual([], sink.calls)
        self.assertEqual(LEDGER, self.main.read_text(encoding="utf-8"))

    def test_the_same_lines_are_the_apps_own_to_change(self):
        sink = Sink()
        source_saves.set_sink(sink)
        response = self.put(LEDGER + '\ninclude "other.beancount"\n')
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))


class DifferentialTest(unittest.TestCase):
    """Each routed Fava write leaves the same bytes as Fava's own, and only the app writes them."""

    def fresh(self, newline="\n"):
        from beancount.core.compare import hash_entry
        from beancount.core.data import Transaction
        from fava.application import create_app

        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        main = Path(directory.name) / "main.beancount"
        main.write_bytes(LEDGER.replace("\n", newline).encode("utf-8"))
        source_saves.install()
        app = create_app([main], load=True, poll_watcher=False)
        slug = engine._discover_slug(app, main)
        ledger = app.config["LEDGERS"][slug]
        entry = next(e for e in ledger.all_entries if isinstance(e, Transaction))
        return app.test_client(), slug, main, hash_entry(entry), ledger

    def run_pair(self, newline, request):
        """Runs [request] on Fava's own methods and on the routed ones; returns both files' bytes."""
        from fava.core import file as fava_file

        self.addCleanup(source_saves.set_sink, None)
        names = list(source_saves.ORIGINALS)
        routed = {name: getattr(fava_file.FileModule, name) for name in names}
        original_client, slug, original_main, original_hash, _ = self.fresh(newline)
        for name in names:
            setattr(fava_file.FileModule, name, source_saves.ORIGINALS[name])
        try:
            request(original_client, slug, original_main, original_hash)
        finally:
            for name in names:
                setattr(fava_file.FileModule, name, routed[name])
        client, slug, main, entry_hash, _ = self.fresh(newline)
        sink = Sink()
        source_saves.set_sink(sink)
        response = request(client, slug, main, entry_hash)
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        self.assertEqual(1, len(sink.calls))
        return original_main.read_bytes(), main.read_bytes(), sink

    def edit_slice(self, client, slug, main, entry_hash):
        slice_sha = sha('2024-01-02 * "Lunch"\n  Expenses:Food  5.00 USD\n  Assets:Cash')
        return client.put(
            f"/{slug}/api/source_slice",
            json={"entry_hash": entry_hash, "source": '2024-01-02 * "Lunch (edited)"\n  Expenses:Food  5.00 USD\n  Assets:Cash', "sha256sum": slice_sha},
        )

    def delete_slice(self, client, slug, main, entry_hash):
        slice_sha = sha('2024-01-02 * "Lunch"\n  Expenses:Food  5.00 USD\n  Assets:Cash')
        return client.delete(f"/{slug}/api/source_slice", query_string={"entry_hash": entry_hash, "sha256sum": slice_sha})

    def add_entries(self, client, slug, main, entry_hash):
        entry = {
            "t": "Transaction", "date": "2024-01-04", "flag": "*", "payee": "", "narration": "Tea", "tags": [],
            "links": [], "meta": {},
            "postings": [{"account": "Expenses:Food", "amount": "2.00 USD"}, {"account": "Assets:Cash", "amount": ""}],
        }
        return client.put(f"/{slug}/api/add_entries", json={"entries": [entry]})

    def attach(self, client, slug, main, entry_hash):
        return client.put(f"/{slug}/api/attach_document", json={"filename": "receipt.pdf", "entry_hash": entry_hash})

    def test_each_write_matches_fava_s_own_bytes_for_unix_and_windows_files(self):
        for newline in ("\n", "\r\n"):
            for request in (self.edit_slice, self.delete_slice, self.add_entries, self.attach):
                with self.subTest(newline=newline, request=request.__name__):
                    expected, got, sink = self.run_pair(newline, request)
                    self.assertEqual(expected, got)

    def test_the_operation_says_what_the_write_is(self):
        for request, operation in (
            (self.edit_slice, "entry-edit"), (self.delete_slice, "entry-delete"),
            (self.add_entries, "add-entries"), (self.attach, "attach-document"),
        ):
            _, _, sink = self.run_pair("\n", request)
            self.assertEqual(operation, sink.operations[-1], request.__name__)

    def test_a_refused_write_leaves_the_file_alone_whatever_the_operation(self):
        for request in (self.edit_slice, self.delete_slice, self.add_entries, self.attach):
            client, slug, main, entry_hash, _ = self.fresh()
            source_saves.set_sink(Sink("error: no"))
            self.addCleanup(source_saves.set_sink, None)
            before = main.read_bytes()
            response = request(client, slug, main, entry_hash)
            self.assertNotEqual(200, response.status_code, request.__name__)
            self.assertEqual(before, main.read_bytes())

    def test_without_the_app_none_of_them_writes(self):
        for request in (self.edit_slice, self.delete_slice, self.add_entries, self.attach):
            client, slug, main, entry_hash, _ = self.fresh()
            source_saves.set_sink(None)
            before = main.read_bytes()
            self.assertNotEqual(200, request(client, slug, main, entry_hash).status_code, request.__name__)
            self.assertEqual(before, main.read_bytes())

    def test_an_edit_that_is_stale_is_refused_by_fava_before_the_app_is_asked(self):
        client, slug, main, entry_hash, _ = self.fresh()
        sink = Sink()
        source_saves.set_sink(sink)
        self.addCleanup(source_saves.set_sink, None)
        response = client.put(
            f"/{slug}/api/source_slice",
            json={"entry_hash": entry_hash, "source": "x", "sha256sum": "0" * 64},
        )
        self.assertNotEqual(200, response.status_code)
        self.assertEqual([], sink.calls)

    def test_a_shared_page_cannot_use_an_entry_edit_to_add_a_document_line(self):
        client, slug, main, entry_hash, _ = self.fresh()
        sink = Sink()
        source_saves.set_sink(sink)
        self.addCleanup(source_saves.set_sink, None)
        slice_sha = sha('2024-01-02 * "Lunch"\n  Expenses:Food  5.00 USD\n  Assets:Cash')
        text = '2024-01-02 * "Lunch"\n  Expenses:Food  5.00 USD\n  Assets:Cash\n\n2020-01-01 document Assets:Cash "/etc/passwd"'
        response = client.put(
            f"/{slug}/api/source_slice",
            json={"entry_hash": entry_hash, "source": text, "sha256sum": slice_sha},
            environ_overrides={"beanbook.via": "share"},
        )
        self.assertNotEqual(200, response.status_code)
        self.assertEqual([], sink.calls)


if __name__ == "__main__":
    unittest.main()
