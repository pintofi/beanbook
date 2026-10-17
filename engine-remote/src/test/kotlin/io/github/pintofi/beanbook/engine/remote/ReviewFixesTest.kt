package io.github.pintofi.beanbook.engine.remote

import com.sun.net.httpserver.HttpServer
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.OriginAllowlist
import io.github.pintofi.beanbook.core.engine.ReportPath
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Fixes from the baseline review of the client modules. */
class ReviewFixesTest {

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun serverRedirectingTo(location: String): RemoteAddress {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            if (exchange.requestURI.path.endsWith("/")) {
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        s.start()
        server = s
        return RemoteAddress.parse("https://127.0.0.1:${s.address.port}").getOrThrow()
    }

    @Test
    fun `a host typed in capitals is still admitted by the allowlist`() {
        val address = RemoteAddress.parse("https://Fava.Example.COM").getOrThrow()

        assertEquals("https://fava.example.com", address.base)
        assertTrue(OriginAllowlist(setOf(address.allowlistOrigin)).isAllowed("https://fava.example.com/live/"))
    }

    @Test
    fun `an IPv6 literal without a port gets the default port`() {
        val address = RemoteAddress.parse("https://[::1]").getOrThrow()

        assertEquals("https://[::1]:443", address.allowlistOrigin)
        assertEquals("https://[::1]:8443", RemoteAddress.parse("https://[::1]:8443").getOrThrow().allowlistOrigin)
    }

    @Test
    fun `an encoded space in the base path stays encoded`() {
        val address = RemoteAddress.parse("https://host.example/my%20fava/").getOrThrow()

        assertEquals("https://host.example/my%20fava", address.base)
    }

    @Test
    fun `the slug is found behind a base path`() = runTest {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/fava/") {
                exchange.responseHeaders.add("Location", "/fava/live/income_statement/")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        s.start()
        server = s
        val address = RemoteAddress.parse("https://127.0.0.1:${s.address.port}/fava").getOrThrow()
        val client = RemoteEngineClient(address, transport = PlainTransportForTests())

        val status = client.start("ignored")

        assertEquals("live", assertIs<EngineStatus.Serving>(status).ledgerSlug)
    }

    @Test
    fun `a slug that is not a plain path segment is not a Fava server`() = runTest {
        listOf("/a%20b c/x/", "/../x/", "/ /x/").forEach { location ->
            server?.stop(0)
            val client = RemoteEngineClient(serverRedirectingTo(location), transport = PlainTransportForTests())

            val status = client.start("ignored")

            assertIs<EngineStatus.Failed>(status, "accepted $location")
        }
    }

    @Test
    fun `the default report URL has no doubled slash`() = runTest {
        val address = serverRedirectingTo("/live/income_statement/")
        val client = RemoteEngineClient(address, transport = PlainTransportForTests())
        client.start("ignored")

        assertEquals("${address.base}/live/", client.urlFor(ReportPath.LEDGER_DEFAULT))
    }

    @Test
    fun `a failed reconnect forgets the earlier slug`() = runTest {
        val address = serverRedirectingTo("/live/income_statement/")
        val client = RemoteEngineClient(address, transport = PlainTransportForTests())
        client.start("ignored")
        server?.stop(0)
        server = null

        val status = client.start("ignored")

        assertIs<EngineStatus.Failed>(status)
        assertNull(client.capabilities())
    }
}
