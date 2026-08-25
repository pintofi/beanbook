"""Loading user Python, under an edition's policy.

Two things this module is careful to be honest about.

**It is not a sandbox.** User plugins run in this process with this app's permissions. They
can read and destroy the ledger and use the network. Android offers nothing that both
isolates them and keeps in-process Fava integration, so the app never claims safety
(docs/threat-model.md 3.2).

**The bridge guard is policy compliance, not security.** `BridgeGuard` removes the
Java/Android bridge from the import system so that plugin code in the Play build cannot
reach Android APIs indirectly, which is what the Device and Network Abuse policy's
interpreter exception is written around. Code running in this process could put it back; the
point is that the app does not offer it, and that the restriction is visible and testable
rather than a claim in a document.
"""

from __future__ import annotations

import builtins
import importlib
import io
import os
import sys
import time
import traceback
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

# Top-level packages that are the bridge, or a way to it.
BRIDGE_MODULES = ("java", "javax", "jnius", "android", "com.chaquo.python", "chaquopy")


class BridgeBlocked(ImportError):
    """Raised in place of importing the Java/Android bridge."""


def _loaded_by_java(module) -> bool:
    """Whether an already-imported module came from Chaquopy's Java importer.

    Checked against the *loader*, not the module object: a Java-backed module is an ordinary
    module object, so its own type says nothing. What identifies it is what imported it.
    """
    spec = getattr(module, "__spec__", None)
    for candidate in (getattr(spec, "loader", None), getattr(module, "__loader__", None)):
        if candidate is not None and _is_java_finder(candidate):
            return True
    return False


def _is_java_finder(finder) -> bool:
    """Whether a `sys.meta_path` entry is Chaquopy's Java importer."""
    cls = type(finder)
    module = (getattr(cls, "__module__", "") or "").lower()
    return "chaquo" in module or "chaquo" in cls.__name__.lower() or module.split(".")[0] == "java"


class BridgeGuard:
    """Withholds the Java/Android bridge while user code is imported.

    **Chaquopy does not use `sys.meta_path` for Java.** It replaces `builtins.__import__`
    with its own `java.chaquopy.import_override`, and that function resolves Java packages
    itself without consulting any finder. Measured on a device: with a meta_path finder
    installed, `importlib.import_module("java")` was refused while the statement
    `from java.lang import System` sailed straight through, because the statement form
    compiles to a call to `builtins.__import__`. A meta_path finder alone cannot withhold
    this bridge, and a version of this class that only did that looked like it worked.

    So the guard does three things:

    1. Replaces `builtins.__import__` with a wrapper that delegates to
       `importlib.__import__` — the standard implementation — instead of to Chaquopy's
       override. Every `import` statement goes through this, and the standard implementation
       has no idea how to resolve a Java package, so none of them resolve. This is the part
       that actually works, and it needs no list of names to be complete.
    2. Refuses `java`, `android` and friends **by name**, so the common cases get an error
       that explains itself rather than `ModuleNotFoundError`.
    3. Evicts anything already imported from Java, since a cached module is returned without
       consulting anything.

    All of it is undone by `remove()`, so the app's own use of Chaquopy is unaffected.

    None of this is a security boundary: user code runs in this process and can put
    `builtins.__import__` back in one line. It is Play policy compliance, and the point is
    that the app does not offer the bridge and that this is testable on a device rather than
    asserted in a document (SPEC.md Q8).
    """

    def __init__(self, blocked=BRIDGE_MODULES) -> None:
        self.blocked = tuple(blocked)
        self.refusals: list[str] = []
        self._detached: list[tuple[int, object]] = []
        self._replaced_import = None

    def _is_blocked(self, name: str) -> bool:
        return any(name == mod or name.startswith(mod + ".") for mod in self.blocked)

    def find_module(self, fullname, path=None):  # legacy protocol, still consulted
        if self._is_blocked(fullname):
            self._refuse(fullname)
        return None

    def find_spec(self, fullname, path=None, target=None):
        if self._is_blocked(fullname):
            self._refuse(fullname)
        return None

    def _refuse(self, fullname: str):
        self.refusals.append(fullname)
        raise BridgeBlocked(
            f"'{fullname}' is not available to plugins in this edition of the app. "
            "The Play build does not give plugin code access to Android APIs."
        )

    def install(self) -> "BridgeGuard":
        # Drop anything already imported, or an `import java` would be served from the
        # module cache without consulting any finder at all.
        for name, module in list(sys.modules.items()):
            if self._is_blocked(name) or _loaded_by_java(module):
                del sys.modules[name]

        # Take Chaquopy's Java importer out of the chain. Recorded with its position so the
        # import system is put back exactly as it was, not merely approximately.
        self._detached = [
            (index, finder)
            for index, finder in enumerate(sys.meta_path)
            if _is_java_finder(finder)
        ]
        for _, finder in self._detached:
            sys.meta_path.remove(finder)

        if self not in sys.meta_path:
            sys.meta_path.insert(0, self)

        # The part that actually closes the door. See the class docstring.
        if self._replaced_import is None:
            self._replaced_import = builtins.__import__
            builtins.__import__ = self._guarded_import
        return self

    def _guarded_import(self, name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and self._is_blocked(name):
            self._refuse(name)
        # importlib.__import__ deliberately, never the function we just replaced: that one
        # is Chaquopy's, and it is the thing being withheld. The standard implementation
        # still resolves everything else through sys.meta_path and sys.path, so the app's
        # own packages and the plugin's own imports are untouched.
        return importlib.__import__(name, globals, locals, fromlist, level)

    def remove(self) -> None:
        if self._replaced_import is not None:
            builtins.__import__ = self._replaced_import
            self._replaced_import = None
        if self in sys.meta_path:
            sys.meta_path.remove(self)
        for index, finder in self._detached:
            if finder not in sys.meta_path:
                sys.meta_path.insert(min(index, len(sys.meta_path)), finder)
        self._detached = []


class PluginRun:
    """What happened when one plugin was imported."""

    def __init__(self, module_name: str) -> None:
        self.module = module_name
        self.ok = False
        self.duration_ms = 0.0
        self.output = ""
        self.error = ""
        self.error_type = ""

    def as_dict(self) -> dict:
        return {
            "module": self.module,
            "ok": self.ok,
            "duration_ms": round(self.duration_ms, 1),
            "output": self.output,
            "error": self.error,
            "error_type": self.error_type,
        }


def redact(text: str, secrets=()) -> str:
    """Removes things an execution log must not carry into a bug report.

    Absolute paths under the app's data directory identify the device's user; anything the
    caller names as a secret is replaced outright. This is applied to plugin output, which
    is user code and can print whatever it likes.
    """
    cleaned = text
    for secret in secrets:
        if secret:
            cleaned = cleaned.replace(secret, "…")
    return cleaned


def _module_names(modules) -> list:
    """Accepts either a Python iterable or a comma-separated string.

    The string form is what crosses the Java bridge: a Kotlin `List` arrives here as a Java
    object, which Python will not iterate, and discovering that at runtime is not the kind of
    surprise an interface should have. Module names cannot contain commas, so splitting on
    one is unambiguous.
    """
    if isinstance(modules, str):
        return [name.strip() for name in modules.split(",") if name.strip()]
    return [str(name) for name in modules]


def _mark_loading(journal_path, module_name: str, label: str = "") -> None:
    """Records which plugin is about to be imported, durably.

    A plugin that raises is caught and reported. A plugin that calls `os._exit`, aborts, or
    segfaults in a C extension takes the whole process with it, and nothing in this function
    runs afterwards to say what happened. The marker is written *before* the import and
    flushed to disk, so after such a crash it is still there, naming the culprit — which is
    the only reason the app can disable the right plugin instead of all of them.
    """
    if not journal_path:
        return
    with open(journal_path, "w") as handle:
        # Two lines: the plugin the app can disable, then the module that was being
        # imported. The first is what the recovery acts on, the second is what it tells the
        # user. Deriving one from the other after the fact needs a mapping that the crashed
        # process took with it.
        handle.write(label + "\n" + module_name)
        handle.flush()
        os.fsync(handle.fileno())


def _clear_loading(journal_path) -> None:
    if journal_path and os.path.exists(journal_path):
        os.remove(journal_path)


def load_plugins(
    plugin_dir: str,
    modules,
    allow_bridge: bool,
    secrets=(),
    journal_path=None,
    journal_label: str = "",
) -> dict:
    """Imports each module in `modules` from `plugin_dir`, one at a time.

    `modules` is a comma-separated string when called from Kotlin, or any iterable of names
    when called from Python.

    One plugin failing does not stop the others: a broken plugin should cost its own feature
    and nothing else. Every import is timed and its output captured, so the app can show a
    real execution log rather than "something went wrong".
    """
    guard = None if allow_bridge else BridgeGuard().install()
    root = str(Path(plugin_dir).resolve())
    added = root not in sys.path
    if added:
        sys.path.insert(0, root)

    runs = []
    try:
        for module_name in _module_names(modules):
            run = PluginRun(module_name)
            _mark_loading(journal_path, module_name, journal_label)
            captured = io.StringIO()
            started = time.monotonic()
            try:
                with redirect_stdout(captured), redirect_stderr(captured):
                    sys.modules.pop(module_name, None)
                    importlib.import_module(module_name)
                run.ok = True
            except BaseException as exc:  # a plugin may raise anything at all
                run.error_type = type(exc).__name__
                run.error = redact(
                    "".join(traceback.format_exception_only(type(exc), exc)).strip(),
                    secrets,
                )
            finally:
                run.duration_ms = (time.monotonic() - started) * 1000
                run.output = redact(captured.getvalue(), secrets)[:4000]
            runs.append(run.as_dict())
        # Only once every plugin has been through: reaching here at all means the process
        # survived the whole set.
        _clear_loading(journal_path)
    finally:
        if added and root in sys.path:
            sys.path.remove(root)
        if guard is not None:
            guard.remove()

    return {
        "runs": runs,
        "loaded": sum(1 for r in runs if r["ok"]),
        "failed": sum(1 for r in runs if not r["ok"]),
        "bridge_refusals": list(guard.refusals) if guard else [],
    }


def probe_bridge(allow_bridge: bool) -> dict:
    """Tries to import the bridge exactly as a plugin would, and reports what happened.

    This exists so the boundary can be tested on a device instead of asserted in a document
    (SPEC.md Q8): the Play build must fail this, and the Expert build must pass it.
    """
    guard = None if allow_bridge else BridgeGuard().install()
    results = {}
    try:
        # com.chaquo.python is the one a denylist of "java"-ish names misses, which is why
        # it is probed by name here.
        for name in ("java", "jnius", "android", "com.chaquo.python", "androidx.core"):
            try:
                importlib.import_module(name)
                results[name] = "imported"
            except BridgeBlocked as exc:
                results[name] = f"blocked: {exc}"
            except ImportError as exc:
                results[name] = f"absent: {exc}"
    finally:
        if guard is not None:
            guard.remove()
    return results


# --- running a script on demand ---------------------------------------------------------

class ScriptStopped(BaseException):
    """Raised inside a running script to stop it.

    Deliberately a `BaseException` and not an `Exception`. Scripts are full of
    `try: ... except Exception:` and a stop that could be swallowed by ordinary error
    handling is not a stop — the script would carry on and the user would be told it had
    ended.
    """


class ScriptTimedOut(ScriptStopped):
    pass


_CANCELLED: set = set()


def cancel_script(handle: str) -> None:
    """Asks a running script to stop. Safe to call from another thread."""
    _CANCELLED.add(str(handle))


def run_script(
    script_path: str,
    ledger_path: str = "",
    timeout_seconds: float = 30.0,
    allow_bridge: bool = False,
    secrets=(),
    handle: str = "",
) -> dict:
    """Runs one user script with a deadline, and a way to stop it early.

    Stopping works by installing a trace function that raises between bytecode lines. That
    covers the case this is really for — a loop that will not end — but it has a limit worth
    being honest about: **a script blocked inside a single C call cannot be interrupted**.
    `time.sleep(600)`, a socket read, or a pathological regular expression will run to
    completion and the deadline will only be noticed afterwards. `timed_out` says what
    happened; `overran_by_ms` says by how much, so the app can tell the user the script
    ignored the deadline rather than pretending it did not.

    There is no way to do better in-process. Killing a Python thread is not something CPython
    supports, and the alternative — a separate process — cannot share the loaded ledger,
    which is the entire reason a script is worth running here.
    """
    handle = str(handle)
    _CANCELLED.discard(handle)

    started = time.monotonic()
    deadline = started + float(timeout_seconds)
    guard = None if allow_bridge else BridgeGuard().install()
    captured = io.StringIO()

    result = {
        "ok": False,
        "output": "",
        "error": "",
        "error_type": "",
        "cancelled": False,
        "timed_out": False,
        "duration_ms": 0.0,
        "overran_by_ms": 0.0,
    }

    def tracer(frame, event, arg):
        if handle in _CANCELLED:
            raise ScriptStopped("stopped at your request")
        if time.monotonic() > deadline:
            raise ScriptTimedOut(f"stopped after {timeout_seconds:g} seconds")
        return tracer

    scope = {
        "__name__": "__beancount_script__",
        "__file__": script_path,
        "ledger_path": ledger_path,
    }

    try:
        source = Path(script_path).read_text()
        code = compile(source, script_path, "exec")
        with redirect_stdout(captured), redirect_stderr(captured):
            sys.settrace(tracer)
            try:
                exec(code, scope)
            finally:
                sys.settrace(None)
        result["ok"] = True
    except ScriptTimedOut as stop:
        result["timed_out"] = True
        result["error_type"] = "ScriptTimedOut"
        result["error"] = str(stop)
    except ScriptStopped as stop:
        result["cancelled"] = True
        result["error_type"] = "ScriptStopped"
        result["error"] = str(stop)
    except BaseException as exc:
        result["error_type"] = type(exc).__name__
        result["error"] = redact(
            "".join(traceback.format_exception_only(type(exc), exc)).strip(), secrets
        )
    finally:
        sys.settrace(None)
        _CANCELLED.discard(handle)
        if guard is not None:
            guard.remove()
        elapsed = (time.monotonic() - started) * 1000
        result["duration_ms"] = round(elapsed, 1)
        result["overran_by_ms"] = round(max(0.0, elapsed - timeout_seconds * 1000), 1)
        result["output"] = redact(captured.getvalue(), secrets)[:4000]

    return result
