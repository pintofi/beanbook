"""Sends the save of Fava's file editor to the app, which writes it through the write protocol.

Fava's editor saves with ``PUT /<ledger>/api/source``, and Fava writes the file itself. That skips
everything ``ProjectWriter`` does (the lock, the snapshot, the journal, the re-parse, the Undo
entry) and can race with a write the app is making at the same moment. This process never writes
to a project, so the write is handed to the app instead: Fava's own method is replaced by one that
checks what Fava checks, builds the bytes Fava would have written, and asks the app to commit them.

Without a sink the save is refused. It is never written here as a fallback.
"""
import hashlib
import os
import tempfile

_sink = None


def set_sink(sink) -> None:
    """``sink.save(file_path, content_path, expected_sha)`` returns ``"ok"`` or ``"error: ..."``."""
    global _sink
    _sink = sink


def install() -> None:
    """Replaces ``FileModule.set_source``. Safe to call again."""
    from fava.core import file as fava_file
    from fava.helpers import FavaAPIError

    if getattr(fava_file.FileModule, "_beanbook_routed", False):
        return

    class SaveRefused(FavaAPIError):
        """The app did not commit the save."""

        def __init__(self, reason: str) -> None:
            super().__init__(reason)

    def set_source(self, path, source, sha256sum):
        sink = _sink
        if sink is None:
            raise SaveRefused("Saving is not available right now. Make the change in the app.")
        with self._lock:
            _, original_sha256sum = self.get_source(path)
            if original_sha256sum != sha256sum:
                raise fava_file.ExternallyChangedError(path)
            newline = fava_file._file_newline_character(path)
            data = source.replace("\n", newline).encode("utf-8")
            expected = hashlib.sha256(path.read_bytes()).hexdigest()
            descriptor, content_path = tempfile.mkstemp(prefix="editor-save-", suffix=".bin")
            try:
                with os.fdopen(descriptor, "wb") as handle:
                    handle.write(data)
                answer = str(sink.save(str(path), content_path, expected))
            finally:
                try:
                    os.unlink(content_path)
                except OSError:
                    pass
            if answer != "ok":
                raise SaveRefused(answer.removeprefix("error: "))
            self.ledger.watcher.notify(path)
            self.ledger.extensions.after_write_source(str(path), source)
            self.ledger.load_file()
            return fava_file._sha256_str(source)

    fava_file.FileModule.set_source = set_source
    fava_file.FileModule._beanbook_routed = True
