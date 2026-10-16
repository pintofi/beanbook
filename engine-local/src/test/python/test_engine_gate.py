"""The loopback gate and the engine's start-up bookkeeping."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import engine  # noqa: E402
import extension_state  # noqa: E402


def ok_app(environ, start_response):
    start_response("200 OK", [("Content-Type", "text/plain")])
    return [b"the ledger"]


def call(gate, cookie, host="127.0.0.1:9"):
    seen = {}

    def start_response(status, headers):
        seen["status"] = status

    environ = {"HTTP_HOST": host, "HTTP_COOKIE": cookie}
    body = b"".join(gate(environ, start_response))
    return seen["status"], body


class TokenGateTest(unittest.TestCase):
    def setUp(self):
        self.gate = engine.TokenGate(ok_app, "s3cret-token")
        self.gate.expected_hosts = {"127.0.0.1:9"}

    def test_the_right_token_is_let_in(self):
        status, body = call(self.gate, "bca_token=s3cret-token")
        self.assertTrue(status.startswith("200"))
        self.assertEqual(b"the ledger", body)

    def test_a_wrong_token_is_refused(self):
        status, _ = call(self.gate, "bca_token=nope")
        self.assertTrue(status.startswith("403"))

    def test_a_cookie_with_a_non_ascii_value_is_refused_not_a_server_error(self):
        status, _ = call(self.gate, 'bca_token="s3crét"')
        self.assertTrue(status.startswith("403"), status)

    def test_the_wrong_host_is_refused_even_with_the_token(self):
        status, _ = call(self.gate, "bca_token=s3cret-token", host="evil.example:9")
        self.assertTrue(status.startswith("403"))


class StartFailureTest(unittest.TestCase):
    def test_a_start_that_fails_does_not_leave_the_engine_waiting_for_a_load(self):
        extension_state.starting = False
        with self.assertRaises(FileNotFoundError):
            engine.start("/no/such/ledger.beancount", "token")
        # inspect() waits while this is set, for Fava to finish loading; nothing is loading.
        self.assertFalse(extension_state.starting)


if __name__ == "__main__":
    unittest.main()
