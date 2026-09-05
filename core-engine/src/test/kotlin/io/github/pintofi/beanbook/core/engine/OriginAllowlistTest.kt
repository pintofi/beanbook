package io.github.pintofi.beanbook.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Closes Phase 0 risk R3 at the unit level: the allowlist is tested for what it *refuses*,
 * not only for what it permits. The WebView-level instrumentation test is M2's job.
 */
class OriginAllowlistTest {

    private val loopback = OriginAllowlist.loopback(41234)
    private val remote = OriginAllowlist(setOf("https://fava.example.com:443"))

    @Test
    fun `allows the exact loopback origin it was given`() {
        assertTrue(loopback.isAllowed("http://127.0.0.1:41234/"))
        assertTrue(loopback.isAllowed("http://127.0.0.1:41234/spike-ledger/journal/"))
    }

    @Test
    fun `refuses a different port on the same host`() {
        assertFalse(loopback.isAllowed("http://127.0.0.1:41235/"))
        assertFalse(loopback.isAllowed("http://127.0.0.1/"))
    }

    @Test
    fun `refuses any other host`() {
        assertFalse(loopback.isAllowed("http://example.com:41234/"))
        assertFalse(loopback.isAllowed("http://192.168.1.5:41234/"))
        assertFalse(loopback.isAllowed("http://localhost:41234/"))
    }

    @Test
    fun `refuses userinfo tricks that look like the allowed host`() {
        assertFalse(loopback.isAllowed("http://127.0.0.1:41234@evil.example.com/"))
        assertFalse(remote.isAllowed("https://fava.example.com@evil.example.com/"))
    }

    @Test
    fun `refuses non-http schemes outright`() {
        assertFalse(loopback.isAllowed("file:///etc/passwd"))
        assertFalse(loopback.isAllowed("content://media/external/images/1"))
        assertFalse(loopback.isAllowed("javascript:alert(1)"))
        assertFalse(loopback.isAllowed("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `remote origins must be https - cleartext is never allowed`() {
        assertTrue(remote.isAllowed("https://fava.example.com/income_statement/"))
        assertFalse(remote.isAllowed("http://fava.example.com/"))
    }

    @Test
    fun `an https allowlist cannot be satisfied by the cleartext version of the same host`() {
        // Remote mode accepts cleartext addresses now, so this is no longer enforced by a
        // rule about schemes — it is enforced by the origins being different strings. That
        // is the property worth testing, because it is the one a downgrade would need.
        val secure = OriginAllowlist(setOf("https://fava.example.com:443"))

        assertTrue(secure.isAllowed("https://fava.example.com/journal/"))
        assertFalse(secure.isAllowed("http://fava.example.com/journal/"))
        assertFalse(secure.isAllowed("http://fava.example.com:443/journal/"))
    }

    @Test
    fun `a cleartext origin the user chose is allowed, and only that origin`() {
        val lan = OriginAllowlist(setOf("http://192.168.1.5:5000"))

        assertTrue(lan.isAllowed("http://192.168.1.5:5000/live/journal/"))
        assertFalse(lan.isAllowed("http://192.168.1.6:5000/live/journal/"), )
        assertFalse(lan.isAllowed("http://192.168.1.5:5001/live/journal/"))
        assertFalse(lan.isAllowed("https://192.168.1.5:5000/live/journal/"))
    }

    @Test
    fun `schemes that are not http or https are refused whatever the allowlist says`() {
        val anything = OriginAllowlist(
            setOf("http://127.0.0.1:41234", "https://fava.example.com:443"),
        )

        listOf(
            "file:///data/data/io.github.pintofi.beanbook.app/files/projects/main.beancount",
            "content://media/external/images/1",
            "javascript:alert(document.cookie)",
            "intent://evil/#Intent;scheme=http;end",
            "ftp://127.0.0.1:41234/",
        ).forEach { assertFalse(anything.isAllowed(it), "let through: $it") }
    }

    @Test
    fun `malformed input is refused rather than throwing`() {
        assertFalse(loopback.isAllowed(""))
        assertFalse(loopback.isAllowed("not a url"))
        assertFalse(loopback.isAllowed("http://"))
    }

    // allowsHost decides whether an HTTP auth challenge gets answered, so a wrong answer
    // either hands a password to a stranger or makes every protected server unusable.

    @Test
    fun `allowsHost recognises the host of an allowed origin, whatever its scheme`() {
        assertTrue(loopback.allowsHost("127.0.0.1"))
        assertTrue(OriginAllowlist(setOf("https://fava.example.com")).allowsHost("fava.example.com"))
        assertTrue(OriginAllowlist(setOf("https://fava.example.com:8443")).allowsHost("FAVA.example.com"))
    }

    @Test
    fun `allowsHost refuses a host that is merely similar`() {
        val remote = OriginAllowlist(setOf("https://fava.example.com"))
        assertFalse(remote.allowsHost("evil.example.com"))
        assertFalse(remote.allowsHost("fava.example.com.evil.test"))
        assertFalse(remote.allowsHost("example.com"))
        assertFalse(remote.allowsHost(""))
        assertFalse(OriginAllowlist(emptySet()).allowsHost("fava.example.com"))
    }
}
