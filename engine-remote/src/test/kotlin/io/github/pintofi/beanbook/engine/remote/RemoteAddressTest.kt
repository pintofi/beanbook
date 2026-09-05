package io.github.pintofi.beanbook.engine.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteAddressTest {

    private fun problemOf(input: String): RemoteAddress.Companion.Problem =
        (RemoteAddress.parse(input).exceptionOrNull() as RemoteAddressRejected).problem

    @Test
    fun `an https address is accepted and normalised`() {
        val address = RemoteAddress.parse("https://fava.example.com/").getOrThrow()

        assertEquals("https://fava.example.com", address.base)
        assertEquals("https://fava.example.com/probe/api/query", address.resolve("/probe/api/query"))
    }

    @Test
    fun `a bare host is assumed to be https, never cleartext`() {
        // Typing "fava.example.com" is common. Assuming HTTPS can only fail the handshake;
        // assuming HTTP would silently send a ledger in the clear because someone left six
        // characters off. Cleartext has to be asked for.
        assertEquals("https://fava.example.com", RemoteAddress.parse("fava.example.com").getOrThrow().base)
    }

    @Test
    fun `http is accepted, and the address knows it is cleartext`() {
        // Allowed for LAN and VPN hosting, where a certificate for 192.168.1.5 is not a
        // realistic ask. Accepting it is not the same as hiding it: every surface that shows
        // an address can ask whether it is cleartext, so nothing has to infer it from a
        // string.
        val address = RemoteAddress.parse("http://192.168.1.5:5000").getOrThrow()

        assertEquals("http://192.168.1.5:5000", address.base)
        assertTrue(address.isCleartext)
        assertEquals("http", address.scheme)
    }

    @Test
    fun `an https address is not cleartext`() {
        val address = RemoteAddress.parse("https://fava.example.com").getOrThrow()

        assertFalse(address.isCleartext)
        assertEquals("https", address.scheme)
    }

    @Test
    fun `the allowlist origin carries the right default port for each scheme`() {
        assertEquals(
            "http://192.168.1.5:80",
            RemoteAddress.parse("http://192.168.1.5").getOrThrow().allowlistOrigin,
        )
        assertEquals(
            "https://fava.example.com:443",
            RemoteAddress.parse("https://fava.example.com").getOrThrow().allowlistOrigin,
        )
        assertEquals(
            "http://192.168.1.5:5000",
            RemoteAddress.parse("http://192.168.1.5:5000").getOrThrow().allowlistOrigin,
        )
    }

    @Test
    fun `every scheme that is not http or https is still refused`() {
        listOf("ftp://host", "file:///etc/passwd", "ws://host", "javascript:alert(1)")
            .forEach { hostile ->
                assertTrue(
                    RemoteAddress.parse(hostile).isFailure,
                    "accepted \"$hostile\"",
                )
            }
        assertEquals(
            RemoteAddress.Companion.Problem.UnsupportedScheme("ftp"),
            problemOf("ftp://host"),
        )
    }

    @Test
    fun `a port and a base path survive`() {
        val address = RemoteAddress.parse("https://host.example:8443/fava/").getOrThrow()

        assertEquals("https://host.example:8443/fava", address.base)
        assertEquals("https://host.example:8443/fava/probe/api/query", address.resolve("probe/api/query"))
    }

    @Test
    fun `an empty address asks for one rather than failing obscurely`() {
        assertEquals(RemoteAddress.Companion.Problem.Empty, problemOf("   "))
        assertTrue(problemOf("").describe().contains("https://fava.example.com"))
    }

    @Test
    fun `an address with no host is refused`() {
        assertIs<RemoteAddress.Companion.Problem.NoHost>(problemOf("https:///path"))
    }

    @Test
    fun `two spellings of the same address are equal`() {
        assertEquals(
            RemoteAddress.parse("https://fava.example.com").getOrThrow(),
            RemoteAddress.parse("fava.example.com/").getOrThrow(),
        )
    }
}
