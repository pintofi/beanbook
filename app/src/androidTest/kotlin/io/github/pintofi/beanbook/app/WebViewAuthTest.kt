package io.github.pintofi.beanbook.app

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.web.AllowlistWebViewClient
import io.github.pintofi.beanbook.app.web.applyBeancountHardening
import io.github.pintofi.beanbook.core.engine.OriginAllowlist
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A self-hosted Fava usually sits behind a reverse proxy that asks for a password, because
 * Fava has none of its own. The WebView has to answer that challenge — and answer it to
 * nobody else.
 *
 * `HttpAuthHandler` has a package-private constructor, so it cannot be faked: this drives a
 * real WebView against a real socket server that issues a real 401. That also proves the
 * part a fake could never prove — that the credentials actually satisfy the challenge.
 */
@RunWith(AndroidJUnit4::class)
class WebViewAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val servers = CopyOnWriteArrayList<TinyServer>()

    @After fun stopServers() {
        servers.forEach { it.close() }
    }

    /**
     * The smallest thing that can issue a Basic-auth challenge. Records every request line
     * and every `Authorization` header it is given, so the test can assert on what the
     * WebView actually sent rather than on what it was configured to send.
     */
    private inner class TinyServer(private val requireAuth: Boolean) {
        private val socket = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val origin: String get() = "http://127.0.0.1:$port"
        val authorizations = CopyOnWriteArrayList<String>()
        /** The status line of every response served, in order. */
        val served = CopyOnWriteArrayList<Int>()
        val connections = java.util.concurrent.atomic.AtomicInteger(0)

        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: java.io.IOException) { break }
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            client.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                var authorization: String? = null
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    if (line.startsWith("Authorization:", ignoreCase = true)) {
                        authorization = line.substringAfter(':').trim()
                    }
                    line = reader.readLine()
                }
                authorization?.let { header -> authorizations += header }

                val body = "<html><body><h1>ledger</h1></body></html>"
                served += if (requireAuth && authorization == null) 401 else 200
                val response = if (requireAuth && authorization == null) {
                    "HTTP/1.1 401 Unauthorized\r\n" +
                        "WWW-Authenticate: Basic realm=\"fava\"\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                }
                it.getOutputStream().write(response.toByteArray())
                it.getOutputStream().flush()
            }
        }

        fun close() = runCatching { socket.close() }
    }

    private fun decode(header: String): String =
        String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()))

    /**
     * Loads [url] and waits for the page to settle.
     *
     * `onPageFinished` fires for an error page too, so the return value says only that the
     * WebView stopped working — never that the load succeeded. What the server served is the
     * signal the assertions use.
     *
     * @return the URL the WebView reported finishing on, or null if nothing finished.
     */
    private fun load(
        url: String,
        allowlist: OriginAllowlist,
        auth: (() -> AllowlistWebViewClient.HttpAuth?)?,
    ): String? {
        val finished = CountDownLatch(1)
        var loaded: String? = null
        var webView: WebView? = null

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                applyBeancountHardening()
                webViewClient = AllowlistWebViewClient(
                    allowlist = allowlist,
                    onPageLoaded = { loaded = it; finished.countDown() },
                    httpAuth = auth,
                )
                loadUrl(url)
            }
        }
        finished.await(20, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { webView?.destroy() }
        return loaded
    }

    @Test fun aChallengeFromTheServerIsAnsweredWithTheSavedCredentials() {
        val server = TinyServer(requireAuth = true)

        val loaded = load(
            "${server.origin}/live/journal/",
            OriginAllowlist(setOf(server.origin)),
        ) { AllowlistWebViewClient.HttpAuth("ledger-user", "s3cret pass".toCharArray()) }

        assertEquals("${server.origin}/live/journal/", loaded)
        assertEquals(
            "the challenge was not answered exactly once",
            1,
            server.authorizations.size,
        )
        assertEquals("ledger-user:s3cret pass", decode(server.authorizations.first()))
        assertTrue(
            "the credentials did not actually satisfy the challenge: ${server.served}",
            server.served.contains(200),
        )
    }

    @Test fun withNoSavedCredentialsTheChallengeIsRefusedRatherThanPrompted() {
        val server = TinyServer(requireAuth = true)

        // A default WebViewClient shows a system password dialog here. That would be a
        // credential prompt the app cannot vouch for, so the client cancels instead.
        val loaded = load(
            "${server.origin}/live/journal/",
            OriginAllowlist(setOf(server.origin)),
            auth = null,
        )

        assertTrue(
            "nothing should have been sent: ${server.authorizations}",
            server.authorizations.isEmpty(),
        )
        assertFalse(
            "the server let something through unauthenticated: ${server.served}",
            server.served.contains(200),
        )
        assertTrue("the server was never asked at all", server.served.contains(401))
    }

    /**
     * Note what this does and does not prove. The off-origin request is refused by
     * `shouldInterceptRequest` before any challenge can be issued, so the auth check is never
     * reached — that is defence in depth doing its job, not the scoping check being
     * exercised. The scoping predicate itself is `OriginAllowlist.allowsHost`, tested
     * directly in `OriginAllowlistTest`.
     */
    @Test fun credentialsAreNeverOfferedToAServerThatIsNotTheAllowlistedOne() {
        val ours = TinyServer(requireAuth = false)
        val stranger = TinyServer(requireAuth = true)

        val loaded = load(
            "${stranger.origin}/live/journal/",
            // The allowlist names our server; the URL points at someone else's.
            OriginAllowlist(setOf(ours.origin)),
        ) { AllowlistWebViewClient.HttpAuth("ledger-user", "s3cret pass".toCharArray()) }

        assertTrue(
            "credentials reached a server that was not on the allowlist",
            stranger.authorizations.isEmpty(),
        )
        assertFalse(
            "the allowlisted server was contacted for a URL that was not its own",
            ours.connections.get() > 0,
        )
    }
}
