package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.engine.EngineStatus
import dev.beancount.engine.local.LocalEngineClient
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The evidence M2 owes: the engine really serves on loopback, the token gate really
 * refuses unauthenticated requests, and the engine really stops.
 *
 * Ordered, because these share one engine start — starting Fava costs about a second and
 * the ordering is part of what is being tested (serve, then stop).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineLifecycleTest {

    companion object {
        private lateinit var engine: LocalEngineClient
        private lateinit var root: File
        private lateinit var serving: EngineStatus.Serving

        @BeforeClass
        @JvmStatic
        fun startEngine() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            root = File(context.cacheDir, "engine-test-${System.currentTimeMillis()}")
            val workspace = ProjectWorkspace(root)
            val main = workspace.createProject("test-ledger", title = "Test Ledger")

            engine = LocalEngineClient(context)
            val status = runBlocking { engine.start(main.absolutePath) }
            assertTrue("engine failed to start: $status", status is EngineStatus.Serving)
            serving = status as EngineStatus.Serving
        }

        @AfterClass
        @JvmStatic
        fun stopEngine() {
            runBlocking { engine.stop() }
            root.deleteRecursively()
        }

        private fun request(path: String, token: String?): Int {
            val connection = URL(serving.origin + path).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                if (token != null) connection.setRequestProperty("Cookie", "bca_token=$token")
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun test01_binds_loopback_on_an_ephemeral_port() {
        assertTrue(
            "must bind loopback, got ${serving.origin}",
            serving.origin.startsWith("http://127.0.0.1:"),
        )
        val port = serving.origin.substringAfterLast(':').toInt()
        // Ephemeral, so never a well-known or hard-coded port.
        assertTrue("expected an ephemeral port, got $port", port > 1024)
        assertNotNull(engine.sessionToken)
        assertTrue("token is too short to be a secret", engine.sessionToken!!.length >= 40)
    }

    @Test
    fun test02_rejects_requests_without_the_token() {
        assertEquals(403, request("/", null))
        assertEquals(403, request("/${serving.ledgerSlug}/journal/", null))
        assertEquals(403, request("/static/app.js", null))
    }

    @Test
    fun test03_rejects_requests_with_a_wrong_token() {
        assertEquals(403, request("/", "not-the-token"))
        // A prefix of the real token must not be accepted either.
        assertEquals(403, request("/", engine.sessionToken!!.dropLast(1)))
    }

    @Test
    fun test04_serves_reports_with_the_token() {
        val token = engine.sessionToken
        assertEquals(302, request("/", token))
        assertEquals(200, request("/${serving.ledgerSlug}/journal/", token))
        assertEquals(200, request("/${serving.ledgerSlug}/balance_sheet/", token))
        assertEquals(200, request("/static/app.js", token))
    }

    @Test
    fun test05_reports_a_ledger_slug_and_startup_time() {
        assertTrue(serving.ledgerSlug.isNotBlank())
        assertTrue("startup time was not measured", serving.startupMillis > 0)
    }

    @Test
    fun test06_stop_really_closes_the_port() {
        val port = serving.origin
        runBlocking { engine.stop() }
        assertEquals(EngineStatus.Stopped, engine.status.value)

        val refused = try {
            request("/", engine.sessionToken)
            false
        } catch (_: ConnectException) {
            true
        } catch (_: Exception) {
            true
        }
        assertTrue("port $port still accepted a connection after stop()", refused)

        // Restart so @AfterClass and any later run have a consistent state.
        runBlocking { engine.start(File(root, "projects/test-ledger/main.beancount").absolutePath) }
    }
}
