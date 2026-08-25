package dev.beancount.engine.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A certificate that does not verify stops the request. Proven against a **real TLS
 * handshake**, not a mocked one.
 *
 * The test starts an HTTPS server on loopback with a self-signed certificate this JVM has
 * never trusted — the same situation as a man-in-the-middle, an expired certificate or a
 * misconfigured server — and asserts the client refuses it. It is the executable form of the
 * rule in CLAUDE.md: *certificate errors are fatal*, and there is nothing in the app that
 * can be set to continue anyway.
 */
class CertificateIsFatalTest {

    private var server: HttpsServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    /** A throwaway self-signed certificate, generated fresh for this test only. */
    private fun selfSignedContext(): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(SelfSigned.keystore(), SelfSigned.PASSWORD) }

        return SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, null)
        }
    }

    private fun startServer(): Int {
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(selfSignedContext())
        https.createContext("/") { exchange ->
            val body = """{"data":"should never be read"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        https.executor = null
        https.start()
        server = https
        return https.address.port
    }

    @Test
    fun `a self-signed certificate is refused, and the failure says why`() {
        val port = startServer()
        val transport = PlatformTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)

        val thrown = assertFailsWith<RemoteException> {
            transport.request("https://127.0.0.1:$port/")
        }

        assertIs<RemoteFailure.CertificateRejected>(thrown.failure)
        val message = thrown.failure.describe()
        assertTrue(message.contains("certificate could not be verified"), message)
        assertTrue(
            message.contains("no way to continue anyway"),
            "the message must not imply there is an override: $message",
        )
    }

    @Test
    fun `nothing in the client can be configured to accept it`() {
        // The proof is structural: nothing in this class takes or holds the objects that
        // weaken TLS. If a TrustManager, a HostnameVerifier or an SSLSocketFactory ever
        // appears in its signature or its fields, this fails — which is the point, because
        // by then someone would be one line away from "accept anyway".
        val java = PlatformTransport::class.java
        val dangerous = listOf("TrustManager", "HostnameVerifier", "SSLSocketFactory", "SSLContext")

        val signatures = buildList {
            java.declaredConstructors.forEach { addAll(it.parameterTypes.map { p -> p.name }) }
            java.declaredMethods.forEach {
                addAll(it.parameterTypes.map { p -> p.name })
                add(it.returnType.name)
            }
            java.declaredFields.forEach { add(it.type.name) }
        }

        val found = signatures.filter { type -> dangerous.any { type.contains(it) } }
        assertTrue(found.isEmpty(), "PlatformTransport gained a way to weaken TLS: $found")
    }

    @Test
    fun `a scheme that is not http or https is refused before a single byte leaves the device`() {
        val transport = PlatformTransport()

        // Not a certificate failure — it never gets that far.
        val thrown = assertFailsWith<IllegalStateException> {
            transport.request("ftp://fava.example.com/probe/api/query")
        }
        assertTrue(thrown.message!!.contains("refusing a ftp request"), thrown.message!!)
    }

    @Test
    fun `a failed handshake is never retried in the clear`() {
        // The one thing allowing cleartext must not turn into. If an https address could
        // quietly become an http one after a certificate error, then "https" would mean
        // nothing at all and the refusal above would be theatre.
        val port = startServer()
        val transport = PlatformTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)

        val thrown = assertFailsWith<RemoteException> {
            transport.request("https://127.0.0.1:$port/")
        }

        assertIs<RemoteFailure.CertificateRejected>(thrown.failure)
        // Nothing in the transport knows how to rewrite a URL, which is why this holds.
        val source = java.io.File(
            "src/main/kotlin/dev/beancount/engine/remote/PlatformTransport.kt",
        ).readText()
        assertTrue(
            !source.contains("replace(\"https\"") && !source.contains("\"http://\" +"),
            "the transport gained a way to rewrite a scheme",
        )
    }

}
