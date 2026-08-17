package dev.beancount.core.engine

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
    fun `a remote host may not use the loopback exemption`() {
        val mixed = OriginAllowlist(
            setOf("http://127.0.0.1:41234", "https://fava.example.com:443")
        )
        assertTrue(mixed.isAllowed("http://127.0.0.1:41234/"))
        assertFalse(mixed.isAllowed("http://fava.example.com/"))
    }

    @Test
    fun `malformed input is refused rather than throwing`() {
        assertFalse(loopback.isAllowed(""))
        assertFalse(loopback.isAllowed("not a url"))
        assertFalse(loopback.isAllowed("http://"))
    }
}
