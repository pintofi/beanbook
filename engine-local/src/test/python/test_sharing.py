"""Sharing Reports on the network, behind a sign-in (B93).

Run from the repository root:

    ~/tools/bc-desktop/bin/python3 -m unittest discover -s engine-local/src/test/python -v

The gate is tested as WSGI, with no socket: every property that keeps other people out is a
decision it makes about a request, and a request is a dictionary.
"""

import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import sharing  # noqa: E402

HOST = "192.168.1.20:8467"


def app(environ, start_response):
    start_response("200 OK", [("Content-Type", "text/plain")])
    return [b"the ledger"]


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class LoginGateTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.gate = sharing.LoginGate(
            app, "hmw", sharing.hash_password("correct horse", iterations=1000), now=self.clock
        )
        self.gate.expected_hosts = {HOST}

    def call(self, path="/", method="GET", client="192.168.1.30", host=HOST, cookie="", body=b"", accept="text/html"):
        environ = {
            "REQUEST_METHOD": method,
            "PATH_INFO": path,
            "REMOTE_ADDR": client,
            "HTTP_HOST": host,
            "HTTP_ACCEPT": accept,
            "HTTP_COOKIE": cookie,
            "CONTENT_LENGTH": str(len(body)),
            "wsgi.input": io.BytesIO(body),
        }
        seen = {}

        def start_response(status, headers):
            seen["status"] = status
            seen["headers"] = dict(headers)

        payload = b"".join(self.gate(environ, start_response))
        return seen["status"], seen["headers"], payload

    def sign_in(self, password="correct horse", username="hmw", client="192.168.1.30"):
        body = f"username={username}&password={password.replace(' ', '+')}".encode()
        return self.call(sharing.LOGIN_PATH, method="POST", body=body, client=client)

    def session_cookie(self):
        status, headers, _ = self.sign_in()
        self.assertTrue(status.startswith("303"), status)
        return headers["Set-Cookie"].split(";")[0]

    def test_nothing_without_signing_in(self):
        status, headers, body = self.call()
        self.assertTrue(status.startswith("303"))
        self.assertEqual(sharing.LOGIN_PATH, headers["Location"])
        self.assertNotIn(b"the ledger", body)

    def test_an_api_call_without_signing_in_is_refused(self):
        status, _, body = self.call("/ledger/api/changed", accept="application/json")
        self.assertTrue(status.startswith("401"))
        self.assertNotIn(b"the ledger", body)

    def test_signing_in_gives_a_session_that_opens_the_ledger(self):
        cookie = self.session_cookie()
        status, _, body = self.call(cookie=cookie)
        self.assertTrue(status.startswith("200"))
        self.assertEqual(b"the ledger", body)

    def test_a_signed_in_browser_can_read_but_not_write(self):
        cookie = self.session_cookie()
        for method in ("POST", "PUT", "DELETE", "PATCH"):
            status, headers, body = self.call("/ledger/api/source", method=method, cookie=cookie, body=b"{}")
            self.assertTrue(status.startswith("405"), (method, status))
            self.assertEqual("GET, HEAD, OPTIONS", headers["Allow"])
            self.assertNotIn(b"the ledger", body)
        for method in ("GET", "HEAD", "OPTIONS"):
            status, _, _ = self.call("/ledger/api/changed", method=method, cookie=cookie)
            self.assertTrue(status.startswith("200"), (method, status))

    def test_the_session_cookie_is_http_only_and_same_site_strict(self):
        _, headers, _ = self.sign_in()
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])

    def test_a_wrong_password_is_refused(self):
        status, headers, _ = self.sign_in(password="wrong")
        self.assertTrue(status.startswith("401"))
        self.assertNotIn("Set-Cookie", headers)

    def test_a_wrong_username_is_refused(self):
        status, _, _ = self.sign_in(username="someone")
        self.assertTrue(status.startswith("401"))

    def test_a_made_up_session_is_refused(self):
        status, _, _ = self.call(cookie=f"{sharing.SESSION_COOKIE}=guessed")
        self.assertTrue(status.startswith("303"))

    def test_a_public_address_is_refused_even_with_a_session(self):
        cookie = self.session_cookie()
        status, _, body = self.call(cookie=cookie, client="8.8.8.8")
        self.assertTrue(status.startswith("403"))
        self.assertNotIn(b"the ledger", body)

    def test_a_different_host_header_is_refused(self):
        # DNS rebinding: a page on evil.example resolving to this address.
        cookie = self.session_cookie()
        status, _, _ = self.call(cookie=cookie, host="evil.example:8467")
        self.assertTrue(status.startswith("403"))

    def test_repeated_failures_lock_the_address_out(self):
        for _ in range(sharing.FAILURES_BEFORE_LOCKOUT):
            self.sign_in(password="wrong")
        status, _, _ = self.sign_in()  # right password, but locked
        self.assertTrue(status.startswith("429"), status)
        # Another device is not punished for this one.
        status, _, _ = self.sign_in(client="192.168.1.31")
        self.assertTrue(status.startswith("303"), status)

    def test_the_lockout_ends_and_then_doubles(self):
        for _ in range(sharing.FAILURES_BEFORE_LOCKOUT):
            self.sign_in(password="wrong")
        self.clock.now += sharing.FIRST_LOCKOUT_SECONDS + 1
        for _ in range(sharing.FAILURES_BEFORE_LOCKOUT):
            self.sign_in(password="wrong")
        self.clock.now += sharing.FIRST_LOCKOUT_SECONDS + 1
        status, _, _ = self.sign_in()
        self.assertTrue(status.startswith("429"), "the second lockout did not last longer")

    def test_signing_out_ends_the_session(self):
        cookie = self.session_cookie()
        self.call(sharing.LOGOUT_PATH, cookie=cookie)
        status, _, _ = self.call(cookie=cookie)
        self.assertTrue(status.startswith("303"))

    def test_an_idle_session_expires(self):
        cookie = self.session_cookie()
        self.clock.now += sharing.SESSION_IDLE_SECONDS + 1
        status, _, _ = self.call(cookie=cookie)
        self.assertTrue(status.startswith("303"))

    def test_stopping_signs_everyone_out(self):
        cookie = self.session_cookie()
        self.gate.end_all_sessions()
        status, _, _ = self.call(cookie=cookie)
        self.assertTrue(status.startswith("303"))


class PasswordHashTest(unittest.TestCase):
    def test_round_trip(self):
        stored = sharing.hash_password("s3cret pass", iterations=1000)
        self.assertTrue(sharing.check_password("s3cret pass", stored))
        self.assertFalse(sharing.check_password("s3cret pas", stored))

    def test_a_malformed_hash_never_matches(self):
        self.assertFalse(sharing.check_password("", "nonsense"))
        self.assertFalse(sharing.check_password("", "md5$1$00$00"))

    def test_matches_the_kotlin_format(self):
        # PBKDF2-HMAC-SHA256, 1000 rounds, salt 00..0f, password "pw": the value the app's
        # SharingSettings computes, fixed here so the two sides cannot drift apart.
        stored = sharing.hash_password("pw", salt=bytes(range(16)), iterations=1000)
        self.assertEqual(
            stored.split("$")[:3], ["pbkdf2_sha256", "1000", bytes(range(16)).hex()]
        )
        self.assertTrue(sharing.check_password("pw", stored))


class StartTest(unittest.TestCase):
    def test_refuses_a_public_address(self):
        with self.assertRaises(ValueError):
            sharing.start(app, "8.8.8.8", 0, "u", sharing.hash_password("p", iterations=1000))

    def test_refuses_without_a_password(self):
        with self.assertRaises(ValueError):
            sharing.start(app, "127.0.0.1", 0, "u", "")


if __name__ == "__main__":
    unittest.main()
