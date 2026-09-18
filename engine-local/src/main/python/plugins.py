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
import hashlib
import importlib
import importlib.util
import io
import os
import sys
import time
import traceback
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from pathlib import Path

# Top-level packages that are the bridge, or a way to it.
BRIDGE_MODULES = ("java", "javax", "jnius", "android", "com.chaquo.python", "chaquopy")


class BridgeBlocked(ImportError):
    """Raised in place of importing the Java/Android bridge."""


class BridgeGuard:
    """Withholds the Java/Android bridge while user code is imported.

    Chaquopy's bridge has two halves, and they are reached differently.

    `java` is an **ordinary Python package**, shipped in Chaquopy's `bootstrap.imy` and loaded
    by a plain `zipimporter`. Java *classes* — `com.chaquo.python.Python`, `androidx.core`,
    `dalvik.system` — come from `java.chaquopy.import_override`, which Chaquopy installs over
    `builtins.__import__` at startup. `sys.meta_path` is not involved in either.

    So the guard replaces `builtins.__import__` with a wrapper that **delegates to Chaquopy's
    override** and refuses two things: names on [BRIDGE_MODULES], which gets `import java` a
    message that explains itself; and any name Python itself cannot resolve but the import
    succeeded for anyway, which can only mean Java provided it. That second rule needs no
    list and covers the whole Java namespace.

    An earlier version did two things this one does not: it delegated to
    `importlib.__import__` rather than to Chaquopy's override, and it removed
    `ChaquopyPathFinder` from `sys.meta_path`. On the play flavor — where the guard is on for
    every ledger load — Beancount's parser then failed `assert isinstance(number, Decimal)`
    on an object whose repr was `Decimal('12.00')`, meaning two `decimal` modules existed at
    once. Every ledger load with a plugin directory active was corrupted.

    **The exact cause was not established.** Restoring the old delegation reproduced nothing;
    nor did restoring the meta_path removal; nor did both together. Something else in the
    rework — most likely evicting plugin modules after each load, which stops a module from a
    previous, deleted directory lingering — also mattered. What is certain is that the fault
    was real, that it is gone, and that `PluginCompatibilityTest` is what found it and would
    find it again. Guessing at a root cause in a comment would only make the next person
    trust the guess.

    The rule that follows from it anyway: hand ordinary imports back to whatever
    `builtins.__import__` already was, and leave `sys.meta_path` alone. Chaquopy knows how to
    load Chaquopy's packages; this code does not need to.

    Nothing is evicted from `sys.modules`: `java` is Chaquopy's own and the app depends on
    it. A cached module does not get past the wrapper anyway, because the wrapper runs before
    the cache is ever consulted.

    None of this is a security boundary. User code runs in this process and can restore
    `builtins.__import__` in one line. It is Play policy compliance, testable on a device
    rather than asserted in a document (SPEC.md Q8).
    """

    def __init__(self, blocked=BRIDGE_MODULES) -> None:
        self.blocked = tuple(blocked)
        self.refusals: list[str] = []
        self._replaced_import = None
        self._replaced_import_module = None

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
        # Deliberately no sys.modules eviction. `java` is Chaquopy's own module and the app
        # needs it; dropping it would give the process two copies of the bridge. A cached
        # module cannot slip past the wrapper below, because the wrapper is consulted before
        # the module cache is.
        if self not in sys.meta_path:
            # Covers importlib.import_module(), which goes through the finders rather than
            # through builtins.__import__.
            sys.meta_path.insert(0, self)

        if self._replaced_import is None:
            self._replaced_import = builtins.__import__
            builtins.__import__ = self._guarded_import

        if self._replaced_import_module is None:
            # `importlib.import_module` is a separate door into the same room, and it checks
            # `sys.modules` before it consults a single finder — so with `java` already
            # imported by the app (it always is), the meta_path finder never even runs and
            # the call succeeds. Evicting `java` would fix that and break Chaquopy, so the
            # door gets its own lock.
            self._replaced_import_module = importlib.import_module
            importlib.import_module = self._guarded_import_module
        return self

    def _guarded_import_module(self, name, package=None):
        if not (name or "").startswith(".") and self._is_blocked(name):
            self._refuse(name)
        return self._replaced_import_module(name, package)

    @staticmethod
    def _resolvable_by_python(root: str) -> bool:
        """Whether ordinary Python could provide this top-level name."""
        if root in sys.modules:
            return True
        try:
            return importlib.util.find_spec(root) is not None
        except BaseException:
            # A parent package that refuses to be inspected is not evidence either way;
            # assume Python could, so an unrelated failure is never reported as the bridge.
            return True

    def _guarded_import(self, name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and self._is_blocked(name):
            self._refuse(name)

        root = name.split(".")[0] if level == 0 else ""
        resolvable = self._resolvable_by_python(root) if root else True

        # Chaquopy's own override, so every ordinary import takes exactly the path it always
        # takes and no module is ever imported twice by two different mechanisms.
        module = self._replaced_import(name, globals, locals, fromlist, level)

        if not resolvable:
            # Python had no way to provide this and yet it imported. Only Java could have.
            sys.modules.pop(root, None)
            self._refuse(name)
        return module

    def remove(self) -> None:
        if self._replaced_import_module is not None:
            importlib.import_module = self._replaced_import_module
            self._replaced_import_module = None
        if self._replaced_import is not None:
            builtins.__import__ = self._replaced_import
            self._replaced_import = None
        if self in sys.meta_path:
            sys.meta_path.remove(self)


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
    plugin_dirs: str = "",
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
        # The same directories a ledger load gets (B60): the plugins the user turned on and
        # the packages they fetched. Without this a script could only import what the app
        # bundles, so fetching the module it asked for changed nothing and the install looked
        # broken.
        with activated(plugin_dirs, allow_bridge):
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


# --- making installed plugins importable by Beancount ------------------------------------

@contextmanager
def activated(plugin_dirs, allow_bridge: bool):
    """Makes installed plugins importable for the duration of a ledger load.

    Beancount imports a plugin itself, when it reaches a `plugin "..."` directive — this app
    never imports it on Beancount's behalf. So the only thing to arrange is that the module
    can be found, and that the edition's rules are in force while it is found.

    The guard is installed **only when there is something to guard**: no plugin directories,
    or an edition that allows the bridge, and this does nothing at all. That matters for more
    than tidiness — the guard routes every import through `importlib.__import__`, which is
    the pure-Python implementation, and a ledger load imports a great deal of Beancount. Not
    paying for that when no user code is involved keeps the common case at full speed
    (cold start is already over budget: docs/pending-verification.md V3).
    """
    directories = _module_names(plugin_dirs)
    # Beancount's own load cache must not answer for a different set of plugins (B82). It
    # keys the pickle on the ledger files' modification times alone, so a load that failed to
    # import a plugin -- "No module named beancount_interpolate" -- was served again, error
    # and all, after the plugin had been installed. The cache file is named for the plugins
    # in reach, so installing, removing or changing one is a different cache.
    use_loader_cache(directories)
    guard = None if (allow_bridge or not directories) else BridgeGuard().install()
    added = []
    for directory in directories:
        resolved = str(Path(directory).resolve())
        if resolved not in sys.path:
            sys.path.insert(0, resolved)
            added.append(resolved)
    # FileFinder caches what is in a directory. A plugin installed since the last load would
    # not be found without this.
    importlib.invalidate_caches()

    try:
        yield
    finally:
        # Evicting is not tidiness, it is correctness. Beancount imports a plugin with
        # `importlib.import_module`, which returns a cached module without consulting
        # `sys.path` at all — so once a plugin has been imported, *disabling or uninstalling
        # it does not stop it running* for the life of the process. Found by the
        # compatibility harness: a ledger loaded with no plugin directories at all still had
        # its directives dropped by a plugin from the previous load.
        #
        # It also means an edited plugin is picked up on the next load instead of being
        # stale, which is what anyone editing one would expect.
        use_loader_cache([])
        _evict_modules_under(added)
        for directory in added:
            if directory in sys.path:
                sys.path.remove(directory)
        if guard is not None:
            guard.remove()


def loader_cache_pattern(directories) -> str:
    """The name Beancount's pickle cache is given while `directories` are importable.

    Beancount's own name when there are none, so a ledger loaded without plugins shares its
    cache with desktop Beancount. Otherwise the name carries a digest of each directory and
    what is directly in it, with sizes and modification times: installing, removing or
    replacing a plugin changes one of those, and so changes the cache. Only one level deep,
    because a site folder can hold a hundred megabytes of numpy, and walking it on every load
    would cost more than the cache saves.
    """
    from beancount import loader

    if not directories:
        return loader.PICKLE_CACHE_FILENAME
    digest = hashlib.sha256()
    for directory in sorted(str(Path(d).resolve()) for d in directories):
        digest.update(directory.encode())
        try:
            children = sorted(os.scandir(directory), key=lambda item: item.name)
        except OSError:
            continue
        for child in children:
            try:
                stat = child.stat()
            except OSError:
                continue
            digest.update(f"{child.name}:{stat.st_size}:{stat.st_mtime_ns}".encode())
    return ".{filename}." + digest.hexdigest()[:16] + ".picklecache"


def loader_cache_root():
    """Where the load cache lives, when it must not sit beside the ledger (B89).

    Beancount writes `.main.beancount.picklecache` next to the file it loads. In the app that
    is the project folder -- the folder the user imported, mirrors to a synced folder, and backs
    up -- so the cache showed up there, and because it changes on every load the mirror found
    a new conflict each time. On Android the cache goes to the app's own cache directory
    instead. Elsewhere (the desktop tests) Beancount's placement is kept, unless
    BEANBOOK_LOADER_CACHE_DIR says otherwise.
    """
    configured = os.environ.get("BEANBOOK_LOADER_CACHE_DIR")
    if configured:
        return configured
    if hasattr(sys, "getandroidapilevel"):
        import tempfile

        return os.path.join(tempfile.gettempdir(), "loader-cache")
    return None


def _ledger_tag(filename) -> str:
    """Tells apart two projects whose main files have the same name."""
    folder = os.path.dirname(os.path.abspath(filename))
    return hashlib.sha256(folder.encode()).hexdigest()[:12]


def _cache_filename(pattern: str, filename: str) -> str:
    """Beancount's `get_cache_filename`, placed in [loader_cache_root] when there is one."""
    root = loader_cache_root()
    if root is None or os.path.isabs(pattern):
        return _BEANCOUNT_CACHE_FILENAME(pattern, filename)
    os.makedirs(root, exist_ok=True)
    name = pattern.format(filename=os.path.basename(filename)).lstrip(".")
    return os.path.join(root, f"{_ledger_tag(filename)}-{name}")


_BEANCOUNT_CACHE_FILENAME = None


def use_loader_cache(directories) -> str:
    """Points Beancount's load cache at the file for `directories`, and returns its pattern."""
    global _BEANCOUNT_CACHE_FILENAME
    from beancount import loader

    # `initialize` binds `get_cache_filename` by name each time it runs, so replacing the
    # module's function here places every cache it makes from now on.
    if _BEANCOUNT_CACHE_FILENAME is None:
        _BEANCOUNT_CACHE_FILENAME = loader.get_cache_filename
        loader.get_cache_filename = _cache_filename
    pattern = loader_cache_pattern(directories)
    loader.initialize(os.getenv("BEANCOUNT_DISABLE_LOAD_CACHE") is None, pattern)
    return pattern


def discard_other_loader_caches(ledger_path, keep_pattern: str) -> list:
    """Deletes this ledger's pickle caches made for other plugin sets, keeping one.

    Where the cache has moved out of the project, every cache still beside the ledger is left
    over from before and is deleted too, so an imported or mirrored folder is clean again.
    """
    ledger = Path(ledger_path)
    keep = keep_pattern.format(filename=ledger.name)
    root = loader_cache_root()
    removed = []
    beside = list(ledger.parent.glob(f".{ledger.name}.*picklecache"))
    for candidate in beside:
        if root is not None or candidate.name != keep:
            try:
                candidate.unlink()
                removed.append(candidate.name)
            except OSError:
                pass
    if root is not None and os.path.isdir(root):
        tag = _ledger_tag(str(ledger))
        keep_here = f"{tag}-{keep.lstrip('.')}"
        for candidate in Path(root).glob(f"{tag}-{ledger.name}.*picklecache"):
            if candidate.name != keep_here:
                try:
                    candidate.unlink()
                    removed.append(candidate.name)
                except OSError:
                    pass
    return removed


def _evict_modules_under(directories) -> list:
    """Drops every imported module whose file lives under one of `directories`."""
    if not directories:
        return []
    evicted = []
    for name, module in list(sys.modules.items()):
        origin = getattr(module, "__file__", None) or ""
        if any(origin.startswith(directory + os.sep) for directory in directories):
            del sys.modules[name]
            evicted.append(name)
    return evicted
