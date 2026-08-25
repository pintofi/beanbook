package dev.beancount.engine.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.PostingDraft
import dev.beancount.core.domain.TransactionDraft
import java.math.BigDecimal
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A retried submit cannot create a duplicate (SPEC.md, J4).
 *
 * The fake server here **behaves like the real one**: it stores whatever it is sent, every
 * time, with no deduplication of its own — because that is what Fava 1.30.15 actually does
 * (docs/feasibility/s12-fava-api.md). If the client's protection is wrong, these tests show
 * two entries where there should be one.
 *
 * It speaks plain HTTP on loopback so the test exercises request shaping and the retry rule;
 * TLS is proven separately in `CertificateIsFatalTest` against a real handshake.
 */
class IdempotencyTest {

    private lateinit var server: HttpServer

    /** Everything the server has "stored", in order. */
    private val stored = mutableListOf<String>()

    /** Requests seen, so a test can assert that nothing was sent at all. */
    private val requests = mutableListOf<String>()

    private var addEntriesStatus = 200
    private var queryStatus = 200

    private fun start(): RemoteAddress {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            when {
                exchange.requestURI.path.endsWith("/api/add_entries") -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    if (addEntriesStatus == 200) stored += body
                    respond(exchange, addEntriesStatus, """{"data":"Stored 1 entries."}""")
                }

                exchange.requestURI.path.endsWith("/api/query") -> {
                    if (queryStatus != 200) {
                        respond(exchange, queryStatus, """{"error":"no"}""")
                        return@createContext
                    }
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    val key = KEY_IN_QUERY.find(java.net.URLDecoder.decode(query, "UTF-8"))
                        ?.groupValues?.get(1)
                    val found = key != null && stored.any { it.contains("\"$key\"") }
                    val rows = if (found) """["match"]""" else ""
                    respond(exchange, 200, """{"data":{"rows":[$rows],"t":"table"}}""")
                }

                exchange.requestURI.path == "/" ->
                    respond(exchange, 302, "", location = "/probe/income_statement/")

                else -> respond(exchange, 200, """{"data":{}}""")
            }
        }
        server.start()

        // A perfectly valid https:// address; the loopback test double is what speaks plain
        // HTTP to it, so nothing in production has to bend.
        return RemoteAddress.parse("https://127.0.0.1:${server.address.port}").getOrThrow()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, location: String? = null) {
        location?.let { exchange.responseHeaders.add("Location", it) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private val draft = TransactionDraft(
        date = "2026-08-25",
        payee = "Grocer",
        narration = "Weekly shop",
        postings = listOf(
            PostingDraft("Expenses:Food:Groceries", Amount(BigDecimal("42.30"), "USD")),
            PostingDraft("Assets:Bank:Checking"),
        ),
    )

    private fun clientFor(address: RemoteAddress) =
        FavaClient(address, transport = PlainTransportForTests())

    @Test
    fun `a submit stores the entry once`() {
        val client = clientFor(start())

        val result = client.submit("probe", draft, key = "key-1")

        assertIs<SubmitResult.Stored>(result)
        assertEquals(1, stored.size)
        assertTrue(stored.single().contains("\"bca-id\":\"key-1\""), stored.single())
    }

    @Test
    fun `retrying with the same key after an unknown outcome stores nothing more`() {
        // The situation this exists for: the first PUT reached the server, the answer did not
        // reach us, and the app tries again.
        val client = clientFor(start())
        client.submit("probe", draft, key = "key-1")
        assertEquals(1, stored.size)

        val again = client.retry("probe", draft, "key-1")

        assertIs<SubmitResult.AlreadyPresent>(again)
        assertEquals(1, stored.size, "a retry duplicated the entry: $stored")
    }

    @Test
    fun `a retry that finds nothing does send the entry`() {
        // The other half: the first attempt never arrived, so the retry must actually submit.
        val client = clientFor(start())

        val result = client.retry("probe", draft, "key-1")

        assertIs<SubmitResult.Stored>(result)
        assertEquals(1, stored.size)
    }

    @Test
    fun `a different key is a different entry, because it is`() {
        val client = clientFor(start())
        client.submit("probe", draft, key = "key-1")

        client.retry("probe", draft, "key-2")

        assertEquals(2, stored.size, "distinct submissions must not be collapsed")
    }

    @Test
    fun `the server refusing the write is reported, not swallowed`() {
        val client = clientFor(start())
        addEntriesStatus = 500

        val result = client.submit("probe", draft, key = "key-1")

        val failure = assertIs<SubmitResult.Failed>(result).failure
        assertIs<RemoteFailure.Rejected>(failure)
        assertEquals(0, stored.size)
    }

    @Test
    fun `the entry sent matches what the user approved`() {
        val client = clientFor(start())

        client.submit("probe", draft, key = "key-1")

        val body = stored.single()
        assertTrue(body.contains("\"date\":\"2026-08-25\""), body)
        assertTrue(body.contains("\"payee\":\"Grocer\""), body)
        assertTrue(body.contains("\"narration\":\"Weekly shop\""), body)
        assertTrue(body.contains("\"account\":\"Expenses:Food:Groceries\""), body)
        assertTrue(body.contains("\"amount\":\"42.30 USD\""), body)
        // The elided posting stays elided: Beancount works out the counter-amount, exactly
        // as it does locally. Sending a computed figure would be inventing one.
        assertTrue(body.contains("""{"account":"Assets:Bank:Checking","amount":""}"""), body)
    }

    @Test
    fun `a payee with a quote in it cannot break the request`() {
        val client = clientFor(start())
        val awkward = draft.copy(payee = "Bob \"The Grocer\" O'Neill\\")

        client.submit("probe", awkward, key = "key-1")

        val body = stored.single()
        assertTrue(body.contains("""\"The Grocer\""""), body)
        // It must still be parseable JSON, which is the whole point of escaping it.
        assertTrue(body.startsWith("{\"entries\":[{"), body)
    }

    @Test
    fun `a retry makes the server re-read its file before believing the answer`() {
        // Fava answers queries from the ledger it holds in memory and only re-reads the file
        // when a request makes it check. Without this the query says "not there" about an
        // entry that is already on disk, and the retry writes a second copy — which is
        // exactly what happened on a device (docs/milestones/m10.md).
        val client = clientFor(start())
        client.submit("probe", draft, key = "key-1")
        requests.clear()

        client.retry("probe", draft, "key-1")

        val changedAt = requests.indexOfFirst { it.contains("/api/changed") }
        val queryAt = requests.indexOfFirst { it.contains("/api/query") }
        assertTrue(changedAt >= 0, "the server was never asked to re-read: $requests")
        assertTrue(queryAt > changedAt, "the query ran before the re-read: $requests")
    }

    @Test
    fun `a retry sends nothing when the server cannot say whether the entry is there`() {
        val client = clientFor(start())
        client.submit("probe", draft, key = "key-1")
        queryStatus = 500

        val result = client.retry("probe", draft, "key-1")

        // A query that fails is not evidence of absence. Sending anyway is a duplicate.
        assertIs<SubmitResult.Failed>(result)
        assertEquals(1, stored.size, "the entry was sent again on an unanswerable check: $stored")
    }

    private companion object {
        val KEY_IN_QUERY = Regex("any_meta\\('bca-id'\\) = '([^']+)'")
    }
}
