package dev.beancount.engine.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Data invariant 6, at the layer that actually broke it: *a retried write never creates a
 * duplicate entry.*
 *
 * A device run against a real Fava put one quick-add entry in the ledger **twice**, with the
 * same idempotency key, from a single call to [FavaClient.submit]. Nothing in this codebase
 * sent it twice. The platform HTTP stack did: with a buffered request body it may re-send the
 * whole request when the connection dies before the response arrives, and Fava does not
 * deduplicate (docs/feasibility/s12-fava-api.md). The app reported one success.
 *
 * The fix is that [PlatformTransport] streams the body, which makes it unreplayable. This test
 * is the proof: the server accepts the request and then drops the connection without
 * answering — the exact "did it land?" case — and the assertion is that the server saw the
 * request **once**.
 */
class LostResponseTest {

    private var server: HttpsServer? = null
    private var previousDefault: SSLContext? = null
    private var previousFactory: javax.net.ssl.SSLSocketFactory? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        previousDefault?.let { SSLContext.setDefault(it) }
        previousFactory?.let { javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(it) }
    }

    /**
     * Trusts exactly one throwaway certificate, for this test, and puts the default back
     * afterwards.
     *
     * This is a pin, not a weakening: the trust anchors are that one keystore and nothing
     * else, so an unrelated certificate still fails — which `CertificateIsFatalTest` keeps
     * checking. Nothing in `src/main` gains any way to do this.
     */
    private fun trustOnly(keystore: KeyStore) {
        previousDefault = SSLContext.getDefault()
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keystore) }
        val context = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        SSLContext.setDefault(context)
        // HttpsURLConnection caches its default factory the first time it is asked, so
        // setting the default SSLContext alone is not enough to affect it.
        previousFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    /** Requests seen by the server, whether or not it answered them. */
    private val received = AtomicInteger(0)

    private fun startServerThatNeverAnswers(keystore: KeyStore): Int {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keystore, SelfSigned.PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, null)
        }

        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(context)
        https.createContext("/") { exchange ->
            // Read the body, so this is a request that genuinely arrived and would have
            // been acted on, then vanish without a response.
            exchange.requestBody.readBytes()
            received.incrementAndGet()
            exchange.close()
        }
        https.executor = null
        https.start()
        server = https
        return https.address.port
    }

    @Test
    fun `a request whose response is lost is never silently re-sent`() {
        val keystore = SelfSigned.keystore()
        trustOnly(keystore)
        val port = startServerThatNeverAnswers(keystore)
        val transport = PlatformTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)

        val thrown = assertFailsWith<RemoteException> {
            transport.request(
                url = "https://127.0.0.1:$port/live/api/add_entries",
                method = "PUT",
                body = """{"entries":[{"narration":"once, and only once"}]}""",
            )
        }

        // Without this the test would pass for the wrong reason: a handshake that never
        // succeeded also delivers zero requests.
        assertIs<RemoteFailure.Unreachable>(
            thrown.failure,
            "the request never got as far as the server: ${thrown.failure.describe()}",
        )

        assertEquals(
            expected = 1,
            actual = received.get(),
            message = "the request was re-sent behind the app's back — invariant 6 is broken",
        )
    }
}
