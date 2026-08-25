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


def load_plugins(plugin_dir: str, modules, allow_bridge: bool, secrets=()) -> dict:
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
