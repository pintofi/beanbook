"""Locating and validating, so Kotlin can write.

Deliberately **read-only**. Finding an entry's extent in the source needs Beancount's own
metadata and Fava's slice logic, but the write itself belongs to ProjectWriter, which owns the
journal, the snapshot and the atomic rename. Splitting it this way keeps one place responsible
for durability.
"""

from __future__ import annotations

import json
from pathlib import Path


def read_text(file_path: str) -> str:
    """A file's text exactly as it is on disk.

    `Path.read_text` translates `\\r\\n` and a lone `\\r` to `\\n` on the way in, so a ledger synced
    from a Windows desktop came back with every line ending rewritten and the edit was written
    over the whole file. `newline=""` leaves the terminators alone.
    """
    with open(file_path, encoding="utf-8", newline="") as handle:
        return handle.read()


def split_lines(text: str) -> list[str]:
    """Lines as Beancount counts them: broken after `\\n` and nowhere else, terminators kept.

    Not `str.splitlines`, which also breaks at form feed, vertical tab, NEL, U+2028 and U+2029.
    Text pasted into a payee or narration can carry any of those, and Beancount's line numbers
    do not move for them, so every line number after one would be wrong.
    """
    parts = text.split("\n")
    lines = [part + "\n" for part in parts[:-1]]
    if parts[-1]:
        lines.append(parts[-1])
    return lines


def validate(main_file: str) -> str:
    """Re-parse a project. Returns JSON: {"ok": bool, "errors": [...]}.

    `ok` is False only when the ledger could not be loaded at all. A ledger that loads
    with errors is `ok` with a non-empty `errors` list — errors are a normal state, and
    the caller decides what to do about them.
    """
    try:
        from beancount import loader

        _entries, errors, _options = loader.load_file(main_file)
    except Exception as exc:  # noqa: BLE001 - the failure itself is the answer
        return json.dumps({"ok": False, "errors": [f"{type(exc).__name__}: {exc}"]})

    return json.dumps(
        {
            "ok": True,
            "errors": [
                {
                    "message": str(getattr(e, "message", e)),
                    "file": (getattr(e, "source", None) or {}).get("filename"),
                    "line": (getattr(e, "source", None) or {}).get("lineno"),
                }
                for e in errors
            ],
        }
    )


def entry_slice(main_file: str, target_file: str, lineno: int) -> str:
    """Return the source extent of the entry that starts at ``lineno``.

    JSON: {"found": bool, "start_line": int, "end_line": int, "text": str,
           "sha256": str, "file": str}

    ``sha256`` is Fava's hash **of the slice**, which is what upstream's optimistic
    concurrency uses; ProjectWriter additionally hashes the whole file, so a change
    anywhere in the file is caught too.
    """
    from beancount import loader
    from fava.core.file import get_entry_slice

    entries, _errors, _options = loader.load_file(main_file)
    target = str(Path(target_file).resolve())

    for entry in entries:
        meta = entry.meta or {}
        if meta.get("lineno") != lineno:
            continue
        if str(Path(meta.get("filename", "")).resolve()) != target:
            continue

        source_slice, sha = get_entry_slice(entry)
        line_count = len(split_lines(source_slice))
        return json.dumps(
            {
                "found": True,
                "start_line": lineno,
                "end_line": lineno + line_count - 1,
                "text": source_slice,
                "sha256": sha,
                "file": meta.get("filename"),
            }
        )

    return json.dumps({"found": False})


def replace_lines(file_path: str, start_line: int, end_line: int, new_text: str) -> str:
    """Compute — but do not write — the file content with a line range replaced.

    Line-based rather than offset-based because that is how Beancount reports positions,
    and the surrounding bytes are copied verbatim so nothing outside the range can move
    (invariant 4).
    """
    lines = split_lines(read_text(file_path))

    if start_line < 1 or end_line > len(lines) or start_line > end_line:
        return json.dumps({"ok": False, "error": "line range outside the file"})

    # The new text is written with the line ending of the lines it replaces, so a file that uses
    # CRLF does not end up with a few LF lines in the middle of it.
    eol = "\r\n" if lines[start_line - 1].endswith("\r\n") else "\n"
    normalised = new_text.replace("\r\n", "\n")
    replacement = normalised if normalised.endswith("\n") or not normalised else normalised + "\n"
    if eol == "\r\n":
        replacement = replacement.replace("\n", "\r\n")
    updated = lines[: start_line - 1] + [replacement] + lines[end_line:]
    return json.dumps({"ok": True, "content": "".join(updated)})


def delete_lines(file_path: str, start_line: int, end_line: int) -> str:
    """Compute — but do not write — the file content with a line range removed."""
    lines = split_lines(read_text(file_path))

    if start_line < 1 or end_line > len(lines) or start_line > end_line:
        return json.dumps({"ok": False, "error": "line range outside the file"})

    # The blank line that set the entry apart goes with it, so adding an entry and deleting it
    # again leaves no stray blank line. Same rule as BeancountText.removeEntryLines and as
    # Fava: every entry is followed by one blank line, and that line goes with it; an entry with
    # none after it takes the one before. Nothing else is touched.
    first, last = start_line - 1, end_line - 1
    before = first > 0 and lines[first - 1].strip() == ""
    after = last + 1 < len(lines) and lines[last + 1].strip() == ""
    updated = lines[:first] + lines[last + 1 :]
    if after:
        del updated[first]
    elif before:
        del updated[first - 1]
    return json.dumps({"ok": True, "content": "".join(updated)})
