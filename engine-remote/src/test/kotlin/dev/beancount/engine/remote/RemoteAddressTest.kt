package dev.beancount.engine.remote

import kotlin.test.Test
import kotlin.test.assertEquals
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
        // assuming HTTP would silently send a ledger in the clear.
        assertEquals("https://fava.example.com", RemoteAddress.parse("fava.example.com").getOrThrow().base)
    }

    @Test
    fun `http is refused, and the message says there is no way around it`() {
        val problem = problemOf("http://fava.example.com")

        assertEquals(RemoteAddress.Companion.Problem.NotHttps("http"), problem)
        assertTrue(
            problem.describe().contains("no option to connect anyway"),
            problem.describe(),
        )
    }

    @Test
    fun `every other scheme is refused too`() {
        listOf("ftp://host", "file:///etc/passwd", "ws://host", "javascript:alert(1)")
            .forEach { hostile ->
                assertTrue(
                    RemoteAddress.parse(hostile).isFailure,
                    "accepted \"$hostile\"",
                )
            }
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
