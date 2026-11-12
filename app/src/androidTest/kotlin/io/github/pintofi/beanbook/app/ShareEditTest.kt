package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.sharing.ShareAccess
import io.github.pintofi.beanbook.app.sharing.SharingStatus
import io.github.pintofi.beanbook.core.data.WriteResult
import io.github.pintofi.beanbook.core.engine.EngineStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Share on Wi-Fi with "Can edit": a signed-in browser on the network saves in Fava's editor, and the
 * save goes through the app's write protocol. Without it, or after Reports restart, the same save is
 * refused. Talks to the real share server over the device's own Wi-Fi address, as a browser would.
 */
@RunWith(AndroidJUnit4::class)
class ShareEditTest {

    private lateinit var app: BeancountApplication
    private lateinit var projectId: String
    private lateinit var main: File
    private var slug = ""

    @Before
    fun startOnAProject() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BeancountApplication
        app.sharingState.settings.save("tester", "correct horse".toCharArray())
        projectId = "share-edit-${System.currentTimeMillis()}"
        main = app.workspace.createProject(projectId, title = "Share edit")
        val status = runBlocking { app.engine.start(main.absolutePath) }
        assertTrue("engine failed to start: $status", status is EngineStatus.Serving)
        slug = (status as EngineStatus.Serving).ledgerSlug
    }

    @After
    fun stopAndClean() {
        app.sharingState.stop()
        runBlocking { app.engine.stop() }
        app.sharingState.settings.forget()
        app.workspace.projectDir(projectId).deleteRecursively()
    }

    private fun awaitOn(access: ShareAccess): SharingStatus.On {
        repeat(300) {
            val status = app.sharingState.status.value
            if (status is SharingStatus.On && status.access == access) return status
            if (status is SharingStatus.Failed) error("sharing failed: ${status.reason}")
            Thread.sleep(100)
        }
        error("sharing did not come on as $access: ${app.sharingState.status.value}")
    }

    private fun send(
        address: String,
        method: String,
        path: String,
        body: String? = null,
        cookie: String? = null,
        json: Boolean = false,
    ): Triple<Int, String, String?> {
        val connection = URL(address + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            if (json) {
                connection.setRequestProperty("Origin", address)
                connection.setRequestProperty("Content-Type", "application/json")
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            Triple(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), connection.getHeaderField("Set-Cookie"))
        } finally {
            connection.disconnect()
        }
    }

    private fun signIn(address: String): String {
        val (code, _, cookie) = send(address, "POST", "/__beanbook/login", "username=tester&password=correct+horse")
        assertEquals(303, code)
        return cookie!!.substringBefore(';')
    }

    private fun put(address: String, cookie: String, text: String, sha: String) = send(
        address, "PUT", "/$slug/api/source",
        JSONObject().put("file_path", main.absolutePath).put("source", text).put("sha256sum", sha).toString(),
        cookie, json = true,
    )

    private fun source(address: String, cookie: String): JSONObject {
        val path = "/$slug/api/source?file_path=" + URLEncoder.encode(main.absolutePath, "UTF-8")
        val (code, text, _) = send(address, "GET", path, cookie = cookie)
        assertEquals(text, 200, code)
        return JSONObject(text).getJSONObject("data")
    }

    @Test
    fun withCanEditASignedInBrowserSavesThroughTheWriteProtocol() {
        app.sharingState.start(ShareAccess.CAN_EDIT)
        val address = awaitOn(ShareAccess.CAN_EDIT).address
        val cookie = signIn(address)
        val before = main.readText()
        val loaded = source(address, cookie)

        val edited = loaded.getString("source") + "\n; from the other device\n"
        val (code, answer, _) = put(address, cookie, edited, loaded.getString("sha256sum"))

        assertEquals(answer, 200, code)
        assertEquals(edited, main.readText())
        val undone = runBlocking { app.writer.undoLast(projectId) }
        assertTrue("undo: $undone", undone is WriteResult.Committed)
        assertEquals(before, main.readText())
    }

    @Test
    fun aLineThatPointsFavaAtAFileIsRefusedFromAnotherDevice() {
        app.sharingState.start(ShareAccess.CAN_EDIT)
        val address = awaitOn(ShareAccess.CAN_EDIT).address
        val cookie = signIn(address)
        val before = main.readText()
        val loaded = source(address, cookie)
        val edited = loaded.getString("source") +
            "\n2020-01-01 document Assets:Cash \"/data/user/0/io.github.pintofi.beanbook/files/state/sharing\"\n"

        val (code, _, _) = put(address, cookie, edited, loaded.getString("sha256sum"))

        assertTrue("expected an error, got $code", code >= 400)
        assertEquals(before, main.readText())
    }

    @Test
    fun withoutCanEditTheSameSaveIsRefused() {
        app.sharingState.start(ShareAccess.READ_ONLY)
        val address = awaitOn(ShareAccess.READ_ONLY).address
        val cookie = signIn(address)
        val before = main.readText()
        val loaded = source(address, cookie)

        val (code, _, _) = put(address, cookie, loaded.getString("source") + "\n; no\n", loaded.getString("sha256sum"))

        assertEquals(405, code)
        assertEquals(before, main.readText())
    }

    @Test
    fun afterReportsRestartTheShareIsReadOnlyAgain() {
        app.sharingState.start(ShareAccess.CAN_EDIT)
        awaitOn(ShareAccess.CAN_EDIT)

        runBlocking {
            app.engine.stop()
            app.engine.start(main.absolutePath)
        }

        val address = awaitOn(ShareAccess.READ_ONLY).address
        assertTrue(app.sharingState.editEnded.value)
        val cookie = signIn(address)
        val loaded = source(address, cookie)
        val (code, _, _) = put(address, cookie, loaded.getString("source") + "\n; no\n", loaded.getString("sha256sum"))
        assertEquals(405, code)
    }
}
