"""Sends the save of Fava's file editor to the app, which writes it through the write protocol.

Fava's editor saves with ``PUT /<ledger>/api/source``, and Fava writes the file itself. That skips
everything ``ProjectWriter`` does (the lock, the snapshot, the journal, the re-parse, the Undo
entry) and can race with a write the app is making at the same moment. This process never writes
to a project, so the write is handed to the app instead: Fava's own method is replaced by one that
checks what Fava checks, builds the bytes Fava would have written, and asks the app to commit them.

Without a sink the save is refused. It is never written here as a fallback.
"""
import hashlib
import io
import os
import pathlib
import re
import tempfile
import threading
from collections import Counter

_sink = None

# Lines that point Fava at files or code rather than describing money. A browser that can write
# ledger text must not be able to add one: a `document` line names a file Fava will then serve
# (the sharing password hash, another project), and `include`, `plugin`, `option` and Fava's
# own settings can pull in files or run code. Changing them needs the app.
CONTROL_LINE = re.compile(r'^(?:(?:include|plugin|option)\b|\S+\s+document\s|\S+\s+custom\s+"fava-)')
GUARD_REFUSAL = (
    "This change adds or alters include, plugin, option, document or Fava settings lines. "
    "Make that change in the app."
)


def control_lines(text: str) -> Counter:
    return Counter(
        line.rstrip() for line in text.splitlines() if CONTROL_LINE.match(line)
    )


_local = threading.local()

# Fava's own methods before they were routed, so a test can run both and compare the bytes.
ORIGINALS: dict = {}
_original_open = pathlib.Path.open


class _RoutedWrite(io.StringIO):
    """What ``Path.open("w")`` returns while a routed Fava method runs: the file is not touched.

    Fava's own code builds the new text (it knows how to splice an entry into a file, which line to
    insert at, which newline the file uses). When it closes the file, the bytes it would have written
    are handed to the app instead, and the file on disk is only ever changed by the app. If the app
    refuses, closing raises, so Fava stops before it records a change that did not happen.
    """

    def __init__(self, path, encoding, newline, operation, via_share) -> None:
        super().__init__()
        self._path, self._encoding, self._newline = pathlib.Path(path), encoding or "utf-8", newline
        self._operation, self._via_share = operation, via_share
        try:
            self._before = self._path.read_bytes()
        except FileNotFoundError:
            self._before = None

    def __exit__(self, exc_type, exc, traceback):
        text = self.getvalue()
        self.close()
        if exc_type is not None:
            return False
        if self._newline in ("\r\n", "\r"):
            text = text.replace("\n", self._newline)
        if self._via_share and self._before is not None:
            before = self._before.decode(self._encoding, "replace")
            if control_lines(before) != control_lines(text.replace("\r\n", "\n")):
                raise _refusal(GUARD_REFUSAL)
        expected = hashlib.sha256(self._before).hexdigest() if self._before is not None else ""
        _commit(self._path, text.encode(self._encoding), expected, self._operation, self._via_share)
        return False


def _refusal(reason: str):
    from fava.helpers import FavaAPIError

    class SaveRefused(FavaAPIError):
        """The app did not commit the write."""

        def __init__(self, text: str) -> None:
            super().__init__(text)

    return SaveRefused(reason)


def _commit(path, data: bytes, expected: str, operation: str, via_share: bool) -> None:
    sink = _sink
    if sink is None:
        raise _refusal("Saving is not available right now. Make the change in the app.")
    descriptor, content_path = tempfile.mkstemp(prefix="fava-write-", suffix=".bin")
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        answer = str(sink.commit(str(path), content_path, expected, operation, via_share))
    finally:
        try:
            os.unlink(content_path)
        except OSError:
            pass
    if answer != "ok":
        raise _refusal(answer.removeprefix("error: "))


def _open(self, mode="r", *args, **kwargs):
    route = getattr(_local, "route", None)
    if route is not None and mode.startswith("w") and "b" not in mode:
        # Path.open(mode, buffering, encoding, errors, newline)
        names = ("buffering", "encoding", "errors", "newline")
        given = {**dict(zip(names, args)), **kwargs}
        return _RoutedWrite(self, given.get("encoding"), given.get("newline"), *route)
    return _original_open(self, mode, *args, **kwargs)


def _routed(original, operation):
    def method(self, *args, **kwargs):
        if _sink is None:
            raise _refusal("Saving is not available right now. Make the change in the app.")
        previous = getattr(_local, "route", None)
        _local.route = (operation, _via_share())
        try:
            return original(self, *args, **kwargs)
        finally:
            _local.route = previous

    method.__name__ = original.__name__
    return method


def _via_share() -> bool:
    """Whether this request came over Share on Wi-Fi rather than the app's own page."""
    try:
        from flask import has_request_context, request
    except ImportError:
        return False
    return has_request_context() and request.environ.get("beanbook.via") == "share"


def set_sink(sink) -> None:
    """``sink.commit(file_path, content_path, expected_sha, operation, via_share)`` returns ``"ok"`` or ``"error: ..."``."""
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
            via_share = _via_share()
            if via_share:
                before = path.read_text("utf-8")
                if control_lines(before) != control_lines(source):
                    raise SaveRefused(GUARD_REFUSAL)
            newline = fava_file._file_newline_character(path)
            data = source.replace("\n", newline).encode("utf-8")
            expected = hashlib.sha256(path.read_bytes()).hexdigest()
            descriptor, content_path = tempfile.mkstemp(prefix="editor-save-", suffix=".bin")
            try:
                with os.fdopen(descriptor, "wb") as handle:
                    handle.write(data)
                answer = str(sink.commit(str(path), content_path, expected, "editor-save", via_share))
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
    # The rest of Fava's writes to a ledger file. Each runs Fava's own code; its write goes to the
    # app (see _RoutedWrite) under the operation named here, which the app maps to an error policy.
    for name, operation in (
        ("save_entry_slice", "entry-edit"),
        ("delete_entry_slice", "entry-delete"),
        ("insert_entries", "add-entries"),
        ("insert_metadata", "attach-document"),
    ):
        ORIGINALS[name] = getattr(fava_file.FileModule, name)
        setattr(fava_file.FileModule, name, _routed(ORIGINALS[name], operation))
    # Only a thread inside one of those methods is redirected; every other open is untouched.
    pathlib.Path.open = _open
    fava_file.FileModule._beanbook_routed = True
