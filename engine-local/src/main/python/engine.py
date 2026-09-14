"""The embedded Fava engine: in-process, loopback-only, token-gated.

Security properties (docs/architecture.md 4, docs/threat-model.md 3.3):
  * binds 127.0.0.1 on an ephemeral port, never 0.0.0.0 and never a LAN interface
  * a 256-bit per-process token, supplied by the Kotlin side, is required on every request
  * the Host header must match the address we actually bound
  * requests failing either check get 403, whatever they ask for

Nothing here creates or modifies ledger content: it serves a project that already exists.
"""

from __future__ import annotations

import hmac
import importlib
import sys
import threading
import time
from http.cookies import SimpleCookie
from pathlib import Path

TOKEN_COOKIE = "bca_token"

_server = None
_thread = None
_state: dict[str, object] = {}


class TokenGate:
    """WSGI middleware. No valid token, no access."""

    def __init__(self, app, token: str) -> None:
        self._app = app
        self._token = token
        self.expected_hosts: set[str] = set()
        self.allowed = 0
        self.rejected = 0

    def __call__(self, environ, start_response):
        if not self._authorised(environ):
            self.rejected += 1
            body = b"403 forbidden"
            start_response(
                "403 Forbidden",
                [
                    ("Content-Type", "text/plain; charset=utf-8"),
                    ("Content-Length", str(len(body))),
                ],
            )
            return [body]
        self.allowed += 1
        return self._app(environ, start_response)

    def _authorised(self, environ) -> bool:
        if environ.get("HTTP_HOST", "") not in self.expected_hosts:
            return False
        raw = environ.get("HTTP_COOKIE", "")
        if not raw:
            return False
        try:
            cookie = SimpleCookie()
            cookie.load(raw)
        except Exception:  # noqa: BLE001 - malformed cookie is simply unauthorised
            return False
        morsel = cookie.get(TOKEN_COOKIE)
        if morsel is None:
            return False
        return hmac.compare_digest(morsel.value, self._token)


def start(
    ledger_path: str,
    token: str,
    poll_watcher: bool = True,
    plugin_dirs: str = "",
) -> str:
    """Serve ``ledger_path`` on loopback.

    Returns a space-separated diagnostic string (``port=… slug=… total_ms=…``) rather
    than a structure, because it crosses the Chaquopy boundary and is also logged.

    ``poll_watcher`` defaults to True: both watchers work on Android, and polling avoids
    inotify's battery cost and per-app watch limits (docs/feasibility/s3-fava-closure.md).
    """
    global _server, _thread

    if _server is not None:
        raise RuntimeError("engine already started; call stop() first")

    path = Path(ledger_path)
    if not path.is_file():
        raise FileNotFoundError(f"no such ledger file: {ledger_path}")

    started = time.monotonic()

    # Fava imports an extension by module name when it loads the ledger, so anything the user
    # installed has to be importable *before* create_app. The parse path has always done this
    # (see plugins.activated); the serving path never did, so every Fava extension installed
    # through the app failed with "No module named …" and its route 404ed
    # (docs/milestones/m14-part26.md).
    for directory in filter(None, plugin_dirs.split(",")):
        if directory not in sys.path:
            sys.path.insert(0, directory)
    # Required, not defensive: a plugin directory is created while this process is already
    # running, and Python caches each path entry's directory listing. Without invalidating
    # that cache the import fails with "No module named …" even though the directory is on
    # `sys.path` and the package is plainly in it — which is exactly what it did, and what
    # sent this looking at the path for an hour. `plugins.activated` has always done this;
    # the serving path had no reason to until now.
    importlib.invalidate_caches()

    # Fava's loads read Beancount's pickle cache too; name it for these plugins (B82).
    import plugins

    plugins.use_loader_cache(list(filter(None, plugin_dirs.split(","))))

    from fava.application import create_app

    flask_app = create_app([path], load=True, poll_watcher=poll_watcher)
    app_ready = time.monotonic()

    gate = TokenGate(flask_app, token)

    from cheroot.wsgi import Server

    server = Server(("127.0.0.1", 0), gate, numthreads=4)
    server.prepare()
    port = server.bind_addr[1]
    gate.expected_hosts = {f"127.0.0.1:{port}"}

    thread = threading.Thread(target=server.serve, name="fava-loopback", daemon=True)
    thread.start()

    _server, _thread = server, thread
    _state.update({"gate": gate, "port": port, "ledger": str(path)})

    slug = _discover_slug(flask_app, path)
    serving = time.monotonic()

    return (
        f"port={port} slug={slug} "
        f"fava_app_ms={(app_ready - started) * 1000:.0f} "
        f"total_ms={(serving - started) * 1000:.0f}"
    )


def _discover_slug(flask_app, path: Path) -> str:
    """Fava namespaces every route by ledger slug. Ask, never guess (S4 finding)."""
    ledgers = flask_app.config.get("LEDGERS")
    if ledgers:
        for slug, ledger in ledgers.items():
            if Path(getattr(ledger, "beancount_file_path", "")) == path:
                return slug
        return next(iter(ledgers))
    return path.stem


def stop() -> str:
    """Shut the server down.

    Required, not optional: cheroot's worker threads are not daemon threads, so an engine
    that is never stopped keeps the process alive (Phase 0 finding C7).
    """
    global _server, _thread

    if _server is None:
        return "stop=noop"
    started = time.monotonic()
    _server.stop()
    if _thread is not None:
        _thread.join(timeout=10)
    alive = _thread.is_alive() if _thread is not None else False
    _server, _thread = None, None
    _state.clear()
    return f"stop=ok ms={(time.monotonic() - started) * 1000:.0f} thread_alive={alive}"


def versions() -> str:
    """What is actually running, for the backup manifest and diagnostics.

    Read from the installed packages rather than hard-coded, so an archive records the
    engine that wrote it even after an upgrade changes the pins.
    """
    import platform

    parts = [f"python={platform.python_version()}"]
    for name in ("beancount", "fava", "beanquery"):
        try:
            module = __import__(name)
            parts.append(f"{name}={getattr(module, '__version__', 'unknown')}")
        except Exception:  # noqa: BLE001 - a missing package is information, not a failure
            parts.append(f"{name}=absent")
    return " ".join(parts)


def stats() -> str:
    """Gate counters, for diagnostics. Never includes the token itself."""
    gate = _state.get("gate")
    if gate is None:
        return "engine=stopped"
    return f"engine=serving port={_state['port']} allowed={gate.allowed} rejected={gate.rejected}"
