"""Sharing Reports with other devices on the same network, behind a sign-in.

Off unless the user turns it on, and a separate server from the loopback one: the app's own
WebView keeps talking to 127.0.0.1 with its per-process token, exactly as before, and this
serves the same Fava application on the Wi-Fi address for as long as sharing is on.

What protects it, in the order a request meets it:

  * **Only private addresses.** A client whose address is not on a private, link-local or
    loopback network is refused before anything else, so a phone on a carrier network or a
    port forwarded by mistake does not expose the sign-in page to the internet.
  * **The Host header** must be the address actually bound, or a name that only a private
    network can resolve (``phone.home.arpa``, ``phone.local``, ``phone.lan`` or a plain
    ``phone``), on the port actually bound. A web page on the internet can only reach this
    server through a name it controls, which has a public domain and is refused: that is what
    stops DNS rebinding. Which address a private name resolves to is the network's business, not
    this server's.
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

# Names under these endings are the network's own: they are not delegated in the public DNS, so a
# page on the internet cannot own one. ``.home.arpa`` is the home-network domain (RFC 8375), ``.local``
# is multicast DNS (RFC 6762), ``.internal`` is reserved for private use, and the rest are what
# routers and offices commonly hand out. A name with no dot at all is the same kind of name.
PRIVATE_NAME_ENDINGS = (
    ".home.arpa", ".local", ".lan", ".internal", ".localdomain", ".home", ".corp", ".intranet",
)
_NAME = __import__("re").compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")


def private_name(host: str) -> bool:
    """Whether [host] (no port) is a name only a private network resolves, and not an address."""
    host = host.lower().rstrip(".")
    if not host or len(host) > 253 or not _NAME.match(host):
        return False
    try:
        ipaddress.ip_address(host)
        return False
    except ValueError:
        pass
    return "." not in host or host.endswith(PRIVATE_NAME_ENDINGS)


SESSION_COOKIE = "bb_session"
LOGIN_PATH = "/__beanbook/login"
LOGOUT_PATH = "/__beanbook/logout"

# The only methods a signed-in browser may use unless editing was allowed. Everything else could
# change the ledger.
READ_METHODS = ("GET", "HEAD", "OPTIONS")

# With "Can edit", the only writes a signed-in browser may make, by method and path under the
# ledger's name. Everything else Fava can write (uploads, moving and deleting documents, extension
# endpoints) stays off the network. Each of these is committed by the app through its write
# protocol; see source_saves.
EDIT_ROUTES = (
    ("PUT", "api/source"),
    ("PUT", "api/format_source"),
    ("PUT", "api/source_slice"),
    ("DELETE", "api/source_slice"),
    ("PUT", "api/add_entries"),
)
# A session that can write is shorter than one that only reads, and is tied to the address it
# signed in from.
EDIT_SESSION_IDLE_SECONDS = 30 * 60
EDIT_SESSION_MAX_SECONDS = 8 * 60 * 60
MAX_WRITE_BYTES = 16 * 1024 * 1024

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

    def __init__(
        self, app, username: str, password_hash: str, now=time.monotonic,
        allow_edits: bool = False, slug: str = "",
    ) -> None:
        self._app = app
        self.allow_edits = allow_edits
        self._slug = slug
        self._username = username
        self._password_hash = password_hash
        self._now = now
        self._lock = threading.Lock()
        # token -> (last used, address it signed in from, when it signed in)
        self._sessions: dict[str, tuple[float, str, float]] = {}
        self._failures: dict[str, tuple[int, float, int]] = {}
        self.expected_hosts: set[str] = set()
        self.signed_in = 0
        self.refused = 0

    # -- WSGI ---------------------------------------------------------------------------

    def __call__(self, environ, start_response):
        client = environ.get("REMOTE_ADDR", "")
        if not _is_private(client):
            return self._plain(start_response, "403 Forbidden", "This server only answers its own network.")
        if not self._host_ok(environ.get("HTTP_HOST", "")):
            return self._plain(start_response, "403 Forbidden", "Wrong address.")

        path = environ.get("PATH_INFO", "")
        if path == LOGIN_PATH:
            return self._login(environ, start_response, client)
        if path == LOGOUT_PATH:
            self._end_session(environ)
            return self._redirect(start_response, LOGIN_PATH, clear=True)
        if self._session(environ, client):
            if path.rstrip("/").endswith("/api/extract"):
                # It runs the importers the ledger configures: code, not a view of the ledger.
                self.refused += 1
                return self._plain(
                    start_response, "403 Forbidden", "This is not available on a shared page."
                )
            method = environ.get("REQUEST_METHOD", "GET")
            if method not in READ_METHODS:
                # A write reaches the file through Fava's code, so it is only let through when
                # editing was allowed, only on the routes in EDIT_ROUTES, and only from the page
                # itself. The app commits it through its write protocol (snapshot, re-parse, Undo).
                refusal = self._write_refusal(environ, method, path)
                if refusal is not None:
                    self.refused += 1
                    return self._plain(start_response, *refusal)
                environ["beanbook.via"] = "share"
            return self._app(environ, start_response)
        self.refused += 1
        if environ.get("REQUEST_METHOD", "GET") == "GET" and "text/html" in environ.get("HTTP_ACCEPT", ""):
            return self._redirect(start_response, LOGIN_PATH)
        return self._plain(start_response, "401 Unauthorized", "Sign in first.")

    def _write_refusal(self, environ, method: str, path: str):
        """None to let a write through, else ``(status, text[, headers])`` to answer with."""
        read_only = (
            "405 Method Not Allowed",
            "This page is shared read-only. Make changes in the app.",
            [("Allow", "GET, HEAD, OPTIONS")],
        )
        if not self.allow_edits:
            return read_only
        if not self._slug or (method, path) not in {(m, f"/{self._slug}/{p}") for m, p in EDIT_ROUTES}:
            return ("405 Method Not Allowed", "This is not available on a shared page.")
        # A page on another site can make a browser send a request, but not with this Origin.
        if environ.get("HTTP_ORIGIN", "") != "http://" + environ.get("HTTP_HOST", ""):
            return ("403 Forbidden", "This request did not come from the shared page.")
        if environ.get("HTTP_SEC_FETCH_SITE", "same-origin") != "same-origin":
            return ("403 Forbidden", "This request did not come from the shared page.")
        if method == "DELETE":
            # Fava sends a delete with its arguments in the address and no body.
            if environ.get("CONTENT_LENGTH") not in (None, "", "0") or environ.get("HTTP_TRANSFER_ENCODING"):
                return ("413 Payload Too Large", "A delete has no body.")
            return None
        if environ.get("CONTENT_TYPE", "").split(";")[0].strip().lower() != "application/json":
            return ("415 Unsupported Media Type", "A change is sent as JSON.")
        if environ.get("HTTP_TRANSFER_ENCODING"):
            return ("413 Payload Too Large", "The size of the change must be stated.")
        try:
            length = int(environ.get("CONTENT_LENGTH") or "")
        except ValueError:
            return ("413 Payload Too Large", "The size of the change must be stated.")
        if length < 0 or length > MAX_WRITE_BYTES:
            return ("413 Payload Too Large", "That change is too large to send this way.")
        return None

    def _host_ok(self, header: str) -> bool:
        """The address that was bound, or a private name, on the port that was bound."""
        if header in self.expected_hosts:
            return True
        name, _, port = header.rpartition(":")
        if not name or port not in {h.rpartition(":")[2] for h in self.expected_hosts}:
            return False
        return private_name(name)

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
            # Never negative: `read(-1)` reads everything, and this runs before anyone has signed in.
            length = max(0, min(int(environ.get("CONTENT_LENGTH") or 0), 4096))
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
                oldest = min(self._sessions, key=lambda t: self._sessions[t][0])
                del self._sessions[oldest]
            session = secrets.token_urlsafe(32)
            now = self._now()
            self._sessions[session] = (now, client, now)
        self.signed_in += 1
        cookie = f"{SESSION_COOKIE}={session}; Path=/; HttpOnly; SameSite=Strict"
        start_response("303 See Other", [("Location", "/"), ("Set-Cookie", cookie), ("Content-Length", "0")])
        return [b""]

    def _session(self, environ, client: str = "") -> bool:
        token = self._cookie(environ)
        if token is None:
            return False
        with self._lock:
            self._expire()
            for known, (_, address, started) in self._sessions.items():
                if hmac.compare_digest(known, token):
                    # A session that can write only works from the address that signed in.
                    if self.allow_edits and address != client:
                        return False
                    self._sessions[known] = (self._now(), address, started)
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
        now = self._now()
        idle = EDIT_SESSION_IDLE_SECONDS if self.allow_edits else SESSION_IDLE_SECONDS
        for token in [
            t for t, (seen, _, started) in self._sessions.items()
            if seen < now - idle or (self.allow_edits and started < now - EDIT_SESSION_MAX_SECONDS)
        ]:
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
    def _plain(start_response, status, text, extra=()):
        body = text.encode("utf-8")
        start_response(
            status,
            [("Content-Type", "text/plain; charset=utf-8"), ("Content-Length", str(len(body))), *extra],
        )
        return [body]

    @staticmethod
    def _redirect(start_response, where, clear=False):
        headers = [("Location", where), ("Content-Length", "0")]
        if clear:
            headers.append(("Set-Cookie", f"{SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
        start_response("303 See Other", headers)
        return [b""]

    def _page(self, start_response, status, problem):
        note = f'<p class="problem" role="alert">{html.escape(problem)}</p>' if problem else ""
        editing = " This share allows editing." if self.allow_edits else ""
        body = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Sign in to Beanbook</title>
<style>
:root{{color-scheme:light dark;--paper:#f3f5f2;--band:#edf1ec;--folio:#ffffff;--ink:#16201e;--quiet:#44534e;--rule:#0b5f5b;--on-rule:#ffffff;--line:#c0c9c4;--problem:#ba1a1a}}
@media (prefers-color-scheme:dark){{:root{{--paper:#0d1211;--band:#101615;--folio:#1b2120;--ink:#e2e8e5;--quiet:#bfc9c4;--rule:#6fd3ca;--on-rule:#003734;--line:#3f4946;--problem:#ffb4ab}}}}
*{{box-sizing:border-box}}
body{{font-family:system-ui,sans-serif;background:var(--paper);color:var(--ink);margin:0;display:grid;place-items:center;min-height:100vh;padding:16px}}
form{{background:var(--folio);border-radius:20px;border-top:3px solid var(--rule);padding:28px 24px 24px;max-width:360px;width:100%;box-shadow:0 1px 3px rgba(0,0,0,.12)}}
h1{{color:var(--ink);letter-spacing:-.01em}}
label{{display:block;margin-top:16px;font-size:14px;font-weight:500;color:var(--quiet)}}
input{{width:100%;padding:12px 14px;margin-top:6px;font-size:16px;color:var(--ink);background:var(--band);border:1px solid var(--line);border-radius:10px;min-height:48px}}
input:focus{{outline:2px solid var(--rule);outline-offset:1px;border-color:transparent}}
button{{margin-top:24px;width:100%;min-height:48px;padding:12px 16px;font-size:16px;font-weight:600;background:var(--rule);color:var(--on-rule);border:0;border-radius:999px;cursor:pointer}}
button:focus-visible{{outline:2px solid var(--ink);outline-offset:2px}}
.problem{{color:var(--problem);font-weight:500}} .note{{font-size:13px;line-height:1.45;color:var(--quiet);margin:8px 0 0}}
</style></head><body>
<form method="post" action="{LOGIN_PATH}">
<h1 style="font-size:20px;margin:0">Beanbook reports</h1>
<p class="note">Shared from a phone on this network. The connection is not encrypted.{editing}</p>
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


def start(
    app, host: str, port: int, username: str, password_hash: str,
    allow_edits: bool = False, slug: str = "",
) -> str:
    """Serves [app] on host:port behind a sign-in. Returns ``host=… port=…``.

    With [allow_edits] a signed-in browser may also save in Fava's editor (see EDIT_ROUTES); [slug]
    is the ledger's name in Fava's URLs. Editing is never the default.
    """
    global _server, _thread, _gate
    stop()
    if not _is_private(host):
        raise ValueError(f"{host} is not a private network address")
    if not username or not password_hash.startswith("pbkdf2_sha256$"):
        raise ValueError("a username and a password are required")

    from cheroot.wsgi import Server

    gate = LoginGate(app, username, password_hash, allow_edits=allow_edits, slug=slug)
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
    access = "edit" if _gate.allow_edits else "read"
    return f"share=on access={access} signed_in={_gate.signed_in} refused={_gate.refused}"
