package dev.beancount.engine.remote

import com.sun.net.httpserver.HttpServer
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.engine.OriginAllowlist
import dev.beancount.core.engine.ReportPath
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RemoteEngineClientTest {

    private lateinit var server: HttpServer
    private var helpStatus = 200

    private fun start(): RemoteAddress {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/" -> {
                    exchange.responseHeaders.add("Location", "/live/income_statement/")
                    exchange.sendResponseHeaders(302, -1)
                }
                path.endsWith("/api/help") -> {
                    val body = if (helpStatus == 200) {
                        """{"data":{"html":"<p>You are running Beancount version """ +
                            """<code>3.2.3</code> and Fava <code>1.30.15</code></p>"}}"""
                    } else {
                        """{"error":"no help"}"""
                    }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(helpStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
        return RemoteAddress.parse("https://127.0.0.1:${server.address.port}").getOrThrow()
    }

    @AfterTest
    fun stop() {
        // Two tests never need the fixture — one builds a client for an address it never
        // calls, the other brings its own server.
        if (::server.isInitialized) server.stop(0)
    }

    private fun clientFor(address: RemoteAddress) =
        RemoteEngineClient(address, transport = PlainTransportForTests())

    @Test
    fun `connecting discovers the ledger slug from the redirect`() = runTest {
        val client = clientFor(start())

        val status = client.start("ignored")

        val serving = assertIs<EngineStatus.Serving>(status)
        assertEquals("live", serving.ledgerSlug)
        assertEquals("live", client.capabilities()?.slug)
    }

    @Test
    fun `versions are read from the help page when the server offers them`() = runTest {
        val client = clientFor(start())
        client.start("ignored")

        val capabilities = client.capabilities()!!

        assertEquals("1.30.15", capabilities.favaVersion)
        assertEquals("3.2.3", capabilities.beancountVersion)
        assertTrue(capabilities.describe().contains("Fava 1.30.15"), capabilities.describe())
    }

    @Test
    fun `a server that will not say its version is used anyway, and said to be unknown`() = runTest {
        val client = clientFor(start())
        helpStatus = 500

        client.start("ignored")
        val capabilities = client.capabilities()!!

        assertEquals(null, capabilities.favaVersion)
        assertTrue(
            capabilities.describe().contains("version unknown"),
            "an unknown version must be stated, not implied: ${capabilities.describe()}",
        )
    }

    @Test
    fun `report URLs are built from the discovered slug, never guessed`() = runTest {
        val address = start()
        val client = clientFor(address)
        client.start("ignored")

        assertEquals(
            "${address.base}/live/income_statement/",
            client.urlFor(ReportPath.INCOME_STATEMENT),
        )
    }

    @Test
    fun `asking for a URL before connecting fails loudly rather than guessing a slug`() = runTest {
        val client = clientFor(start())

        assertFailsWith<IllegalStateException> { client.urlFor(ReportPath.JOURNAL) }
    }

    @Test
    fun `the allowlist is the server's origin and nothing else`() = runTest {
        val address = RemoteAddress.parse("https://fava.example.com/some/base").getOrThrow()
        val client = RemoteEngineClient(address, transport = PlainTransportForTests())

        // The base path is not part of it: an allowlist is about authority. Including the
        // path would let https://fava.example.com/anything-else load just as happily.
        assertEquals(setOf("https://fava.example.com:443"), client.allowedOrigins())
    }

    @Test
    fun `the allowlist actually admits the server's own pages`() = runTest {
        // The earlier test compared strings, which is why it did not notice that
        // allowedOrigins() said "https://host" while OriginAllowlist normalises to
        // "https://host:443" — so every server on the default port had its own reports
        // blocked. Assert against the real allowlist, not against a string.
        val address = RemoteAddress.parse("https://fava.example.com").getOrThrow()
        val allowlist = OriginAllowlist(
            RemoteEngineClient(address, transport = PlainTransportForTests()).allowedOrigins(),
        )

        assertTrue(allowlist.isAllowed("https://fava.example.com/live/journal/"))
        assertTrue(allowlist.isAllowed("https://fava.example.com:443/static/app.js"))
        assertFalse(allowlist.isAllowed("https://fava.example.com:8443/live/journal/"))
        assertFalse(allowlist.isAllowed("https://elsewhere.example.com/live/journal/"))
        assertFalse(allowlist.isAllowed("http://fava.example.com/live/journal/"))
    }

    @Test
    fun `an explicit port stays explicit and does not gain a second one`() = runTest {
        val address = RemoteAddress.parse("https://fava.example.com:8443").getOrThrow()
        val allowlist = OriginAllowlist(
            RemoteEngineClient(address, transport = PlainTransportForTests()).allowedOrigins(),
        )

        assertTrue(allowlist.isAllowed("https://fava.example.com:8443/live/journal/"))
        assertFalse(allowlist.isAllowed("https://fava.example.com/live/journal/"))
    }

    @Test
    fun `stopping forgets the ledger, so a later URL cannot be built from stale state`() = runTest {
        val client = clientFor(start())
        client.start("ignored")

        client.stop()

        assertEquals(EngineStatus.Stopped, client.status.value)
        assertFailsWith<IllegalStateException> { client.urlFor(ReportPath.JOURNAL) }
    }

    @Test
    fun `an address that is not a Fava server is reported as such`() = runTest {
        val plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        plain.createContext("/") { exchange ->
            val body = "<html>not fava</html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        plain.start()

        val client = RemoteEngineClient(
            RemoteAddress.parse("https://127.0.0.1:${plain.address.port}").getOrThrow(),
            transport = PlainTransportForTests(),
        )
        val status = client.start("ignored")
        plain.stop(0)

        val failed = assertIs<EngineStatus.Failed>(status)
        assertTrue(failed.message.contains("did not look like a Fava server"), failed.message)
    }
}
