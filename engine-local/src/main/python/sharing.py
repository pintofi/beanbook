"""Sharing Reports with other devices on the same network, behind a sign-in (B93).

Off unless the user turns it on, and a separate server from the loopback one: the app's own
WebView keeps talking to 127.0.0.1 with its per-process token, exactly as before, and this
serves the same Fava application on the Wi-Fi address for as long as sharing is on.

What protects it, in the order a request meets it:

  * **Only private addresses.** A client whose address is not on a private, link-local or
    loopback network is refused before anything else, so a phone on a carrier network or a
    port forwarded by mistake does not expose the sign-in page to the internet.
  * **The Host header** must name the address actually bound, which is what stops a
    malicious web page from reaching this server through DNS rebinding.
  * **A sign-in.** The username and password are checked against a PBKDF2-SHA256 hash the
    app made; the password itself never reaches this process. A successful sign-in gets a
    random session id in an HttpOnly, SameSite=Strict cookie, and every other request needs
    it. SameSite=Strict is what keeps another site from making a signed-in browser act here.
  * **Lockout.** Repeated failures from one address lock that address out for a while, and
    the wait doubles each time, so guessing a password at network speed is not practical.

What it does not do, and the app says so on screen: the connection is plain HTTP. Anyone who
can watch this network can read the pages and the session cookie. It is meant for a home or
office network the user trusts, or a VPN.
"""

from __future__ import annotations

import hashlib
import hmac
import html
import ipaddress
import secrets
import threading
import time
from http.cookies import SimpleCookie
from urllib.parse import parse_qs

SESSION_COOKIE = "bb_session"
LOGIN_PATH = "/__beanbook/login"
LOGOUT_PATH = "/__beanbook/logout"

# How long a sign-in lasts without being used, and how many can be open at once.
SESSION_IDLE_SECONDS = 12 * 60 * 60
MAX_SESSIONS = 32

# Failures allowed from one address before it is locked out, and the first lockout. Each
# further lockout doubles, up to a day.
FAILURES_BEFORE_LOCKOUT = 5
FIRST_LOCKOUT_SECONDS = 60
MAX_LOCKOUT_SECONDS = 24 * 60 * 60

_server = None
_thread = None
_gate = None


def hash_password(password: str, salt: bytes | None = None, iterations: int = 200_000) -> str:
    """The stored form of a password: ``pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>``.

    Here for tests and for symmetry with the app, which computes the same thing in Kotlin so
    that the password never crosses into this process.
    """
    salt = salt if salt is not None else secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return f"pbkdf2_sha256${iterations}${salt.hex()}${digest.hex()}"


def check_password(password: str, stored: str) -> bool:
    try:
        scheme, iterations, salt_hex, digest_hex = stored.split("$")
        if scheme != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), int(iterations)
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(digest.hex(), digest_hex)


def _is_private(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_private or ip.is_link_local or ip.is_loopback


class LoginGate:
    """WSGI middleware: private network, right Host, signed in -- or nothing."""

    def __init__(self, app, username: str, password_hash: str, now=time.monotonic) -> None:
        self._app = app
        self._username = username
        self._password_hash = password_hash
        self._now = now
        self._lock = threading.Lock()
        self._sessions: dict[str, float] = {}
        self._failures: dict[str, tuple[int, float, int]] = {}
        self.expected_hosts: set[str] = set()
        self.signed_in = 0
        self.refused = 0

    # -- WSGI ---------------------------------------------------------------------------

    def __call__(self, environ, start_response):
        client = environ.get("REMOTE_ADDR", "")
        if not _is_private(client):
            return self._plain(start_response, "403 Forbidden", "This server only answers its own network.")
        if environ.get("HTTP_HOST", "") not in self.expected_hosts:
            return self._plain(start_response, "403 Forbidden", "Wrong address.")

        path = environ.get("PATH_INFO", "")
        if path == LOGIN_PATH:
            return self._login(environ, start_response, client)
        if path == LOGOUT_PATH:
            self._end_session(environ)
            return self._redirect(start_response, LOGIN_PATH, clear=True)
        if self._session(environ):
            return self._app(environ, start_response)
        self.refused += 1
        if environ.get("REQUEST_METHOD", "GET") == "GET" and "text/html" in environ.get("HTTP_ACCEPT", ""):
            return self._redirect(start_response, LOGIN_PATH)
        return self._plain(start_response, "401 Unauthorized", "Sign in first.")

    # -- sign-in ------------------------------------------------------------------------

    def _login(self, environ, start_response, client):
        if environ.get("REQUEST_METHOD") != "POST":
            return self._page(start_response, "200 OK", "")
        locked_for = self._locked_for(client)
        if locked_for > 0:
            return self._page(
                start_response,
                "429 Too Many Requests",
                f"Too many wrong attempts. Try again in {int(locked_for) + 1} seconds.",
            )
        try:
            length = min(int(environ.get("CONTENT_LENGTH") or 0), 4096)
        except ValueError:
            length = 0
        fields = parse_qs(environ["wsgi.input"].read(length).decode("utf-8", "replace"))
        username = (fields.get("username") or [""])[0]
        password = (fields.get("password") or [""])[0]

        # Both compared every time, so a wrong username costs as long as a wrong password.
        name_ok = hmac.compare_digest(username.encode("utf-8"), self._username.encode("utf-8"))
        password_ok = check_password(password, self._password_hash)
        if not (name_ok and password_ok):
            self._failed(client)
            return self._page(start_response, "401 Unauthorized", "Wrong username or password.")

        with self._lock:
            self._failures.pop(client, None)
            self._expire()
            if len(self._sessions) >= MAX_SESSIONS:
                oldest = min(self._sessions, key=self._sessions.get)
                del self._sessions[oldest]
            session = secrets.token_urlsafe(32)
            self._sessions[session] = self._now()
        self.signed_in += 1
        cookie = f"{SESSION_COOKIE}={session}; Path=/; HttpOnly; SameSite=Strict"
        start_response("303 See Other", [("Location", "/"), ("Set-Cookie", cookie), ("Content-Length", "0")])
        return [b""]

    def _session(self, environ) -> bool:
        token = self._cookie(environ)
        if token is None:
            return False
        with self._lock:
            self._expire()
            for known in self._sessions:
                if hmac.compare_digest(known, token):
                    self._sessions[known] = self._now()
                    return True
        return False

    def _end_session(self, environ) -> None:
        token = self._cookie(environ)
        if token is None:
            return
        with self._lock:
            self._sessions.pop(token, None)

    def end_all_sessions(self) -> None:
        with self._lock:
            self._sessions.clear()

    def _expire(self) -> None:
        cutoff = self._now() - SESSION_IDLE_SECONDS
        for token in [t for t, seen in self._sessions.items() if seen < cutoff]:
            del self._sessions[token]

    @staticmethod
    def _cookie(environ):
        raw = environ.get("HTTP_COOKIE", "")
        if not raw:
            return None
        try:
            cookie = SimpleCookie()
            cookie.load(raw)
        except Exception:  # noqa: BLE001 - a malformed cookie is simply not signed in
            return None
        morsel = cookie.get(SESSION_COOKIE)
        return morsel.value if morsel is not None else None

    # -- lockout ------------------------------------------------------------------------

    def _locked_for(self, client) -> float:
        with self._lock:
            count, until, _ = self._failures.get(client, (0, 0.0, 0))
        return max(0.0, until - self._now())

    def _failed(self, client) -> None:
        with self._lock:
            count, until, lockouts = self._failures.get(client, (0, 0.0, 0))
            count += 1
            if count >= FAILURES_BEFORE_LOCKOUT:
                wait = min(FIRST_LOCKOUT_SECONDS * (2 ** lockouts), MAX_LOCKOUT_SECONDS)
                self._failures[client] = (0, self._now() + wait, lockouts + 1)
            else:
                self._failures[client] = (count, until, lockouts)

    # -- responses ----------------------------------------------------------------------

    @staticmethod
    def _plain(start_response, status, text):
        body = text.encode("utf-8")
        start_response(status, [("Content-Type", "text/plain; charset=utf-8"), ("Content-Length", str(len(body)))])
        return [body]

    @staticmethod
    def _redirect(start_response, where, clear=False):
        headers = [("Location", where), ("Content-Length", "0")]
        if clear:
            headers.append(("Set-Cookie", f"{SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
        start_response("303 See Other", headers)
        return [b""]

    @staticmethod
    def _page(start_response, status, problem):
        note = f'<p class="problem" role="alert">{html.escape(problem)}</p>' if problem else ""
        body = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Sign in to Beanbook</title>
<style>
body{{font-family:system-ui,sans-serif;background:#f3f5f2;color:#16201e;margin:0;display:grid;place-items:center;min-height:100vh}}
form{{background:#fff;border-left:3px solid #0b5f5b;padding:24px;max-width:320px;width:calc(100% - 48px)}}
label{{display:block;margin-top:12px;font-size:14px}}
input{{width:100%;box-sizing:border-box;padding:10px;margin-top:4px;font-size:16px}}
button{{margin-top:20px;padding:10px 16px;font-size:16px;background:#0b5f5b;color:#fff;border:0}}
.problem{{color:#a02a20}} .note{{font-size:13px;color:#44534e}}
</style></head><body>
<form method="post" action="{LOGIN_PATH}">
<h1 style="font-size:20px;margin:0">Beanbook reports</h1>
<p class="note">Shared from a phone on this network. The connection is not encrypted.</p>
{note}
<label>Username<input name="username" autocomplete="username" required></label>
<label>Password<input name="password" type="password" autocomplete="current-password" required></label>
<button type="submit">Sign in</button>
</form></body></html>""".encode("utf-8")
        start_response(
            status,
            [
                ("Content-Type", "text/html; charset=utf-8"),
                ("Content-Length", str(len(body))),
                ("Cache-Control", "no-store"),
                ("X-Frame-Options", "DENY"),
            ],
        )
        return [body]


def start(app, host: str, port: int, username: str, password_hash: str) -> str:
    """Serves [app] on host:port behind a sign-in. Returns ``host=… port=…``."""
    global _server, _thread, _gate
    stop()
    if not _is_private(host):
        raise ValueError(f"{host} is not a private network address")
    if not username or not password_hash.startswith("pbkdf2_sha256$"):
        raise ValueError("a username and a password are required")

    from cheroot.wsgi import Server

    gate = LoginGate(app, username, password_hash)
    server = None
    for candidate in (port, 0):
        try:
            server = Server((host, candidate), gate, numthreads=4)
            server.prepare()
            break
        except OSError:
            server = None
    if server is None:
        raise OSError(f"could not listen on {host}")
    bound = server.bind_addr[1]
    gate.expected_hosts = {f"{host}:{bound}"}

    thread = threading.Thread(target=server.serve, name="fava-share", daemon=True)
    thread.start()
    _server, _thread, _gate = server, thread, gate
    return f"host={host} port={bound}"


def stop() -> str:
    """Stops sharing and signs every device out."""
    global _server, _thread, _gate
    if _server is None:
        return "share=off"
    if _gate is not None:
        _gate.end_all_sessions()
    _server.stop()
    if _thread is not None:
        _thread.join(timeout=10)
    _server, _thread, _gate = None, None, None
    return "share=stopped"


def stats() -> str:
    if _gate is None:
        return "share=off"
    return f"share=on signed_in={_gate.signed_in} refused={_gate.refused}"
