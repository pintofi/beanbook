package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A save made in Fava's file editor goes through the write protocol.
 *
 * Fava writes the file itself when the editor saves, from the engine process, which skipped the
 * snapshot, the journal and the Undo entry. These tests drive Fava's own endpoint over loopback,
 * as the editor page does, and check that the app's writer made the change. They also prove the
 * engine can wait on the app while the app's re-parse calls back into the engine, which is what
 * a write does: if Chaquopy held the interpreter lock across that wait, the save would hang.
 */
@RunWith(AndroidJUnit4::class)
class EditorSaveTest {

    private lateinit var app: BeancountApplication
    private lateinit var projectId: String
    private lateinit var main: File
    private lateinit var serving: EngineStatus.Serving

    @Before
    fun startOnAProject() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BeancountApplication
        projectId = "editor-save-${System.currentTimeMillis()}"
        main = app.workspace.createProject(projectId, title = "Editor save")
        main.appendText(
            "\n2024-01-01 open Assets:Test\n2024-01-01 open Expenses:Test\n\n" +
                "2024-01-02 * \"Lunch\"\n  Expenses:Test  5.00 USD\n  Assets:Test\n",
        )
        val status = runBlocking { app.engine.start(main.absolutePath) }
        assertTrue("engine failed to start: $status", status is EngineStatus.Serving)
        serving = status as EngineStatus.Serving
    }

    @After
    fun stopAndClean() {
        runBlocking { app.engine.stop() }
        app.workspace.projectDir(projectId).deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val connection = URL(serving.origin + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Cookie", "bca_token=${app.engine.sessionToken}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            code to stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun source(): JSONObject {
        val path = "/${serving.ledgerSlug}/api/source?file_path=" + URLEncoder.encode(main.absolutePath, "UTF-8")
        val (code, text) = call("GET", path)
        assertEquals(text, 200, code)
        return JSONObject(text).getJSONObject("data")
    }

    private fun put(text: String, sha: String): Pair<Int, String> = call(
        "PUT",
        "/${serving.ledgerSlug}/api/source",
        JSONObject().put("file_path", main.absolutePath).put("source", text).put("sha256sum", sha).toString(),
    )

    @Test
    fun theEditorSavesThroughTheWriteProtocolAndUndoPutsTheFileBack() {
        val before = main.readText()
        val loaded = source()
        val edited = loaded.getString("source") + "\n; added in the editor\n"

        val (code, answer) = put(edited, loaded.getString("sha256sum"))

        assertEquals(answer, 200, code)
        assertEquals(edited, main.readText())
        // The journal has the write, so the app's Undo can take it back byte for byte.
        val undone = runBlocking { app.writer.undoLast(projectId) }
        assertTrue("undo: $undone", undone is WriteResult.Committed)
        assertEquals(before, main.readText())
    }

    @Test
    fun aFileChangedSinceTheEditorReadItIsNotOverwritten() {
        val loaded = source()
        val sha = loaded.getString("sha256sum")
        main.appendText("\n; changed somewhere else\n")
        val changed = main.readText()

        val (code, answer) = put(loaded.getString("source") + "\n; the editor's change\n", sha)

        assertTrue("expected an error, got $code $answer", code >= 400)
        assertEquals(changed, main.readText())
    }

    @Test
    fun withoutTheAppsHandlerTheSaveIsRefusedAndNothingIsWritten() {
        val loaded = source()
        val before = main.readText()
        val handler = app.engine.favaWrites
        app.engine.favaWrites = null
        try {
            val (code, answer) = put(loaded.getString("source") + "\n; refused\n", loaded.getString("sha256sum"))
            assertTrue("expected an error, got $code $answer", code >= 400)
            assertFalse(main.readText().contains("; refused"))
            assertEquals(before, main.readText())
        } finally {
            app.engine.favaWrites = handler
        }
    }

    private fun lunchHash(): String {
        val (_, journal) = call("GET", "/${serving.ledgerSlug}/api/journal_page?page=1&order=desc")
        val hashes = Regex("#context-([0-9a-f]{32})").findAll(journal).map { it.groupValues[1] }.distinct()
        return hashes.first { hash ->
            val (code, text) = call("GET", "/${serving.ledgerSlug}/api/source_slice?entry_hash=$hash")
            code == 200 && text.contains("Lunch")
        }
    }

    private val lunch = "2024-01-02 * \"Lunch\"\n  Expenses:Test  5.00 USD\n  Assets:Test"

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun undoRestores(before: String) {
        val undone = runBlocking { app.writer.undoLast(projectId) }
        assertTrue("undo: $undone", undone is WriteResult.Committed)
        assertEquals(before, main.readText())
    }

    @Test
    fun anEntryEditedInItsOwnBoxIsSavedThroughTheWriteProtocol() {
        val before = main.readText()
        val body = JSONObject().put("entry_hash", lunchHash())
            .put("source", lunch.replace("Lunch", "Lunch (edited)")).put("sha256sum", sha256(lunch)).toString()

        val (code, answer) = call("PUT", "/${serving.ledgerSlug}/api/source_slice", body)

        assertEquals(answer, 200, code)
        assertTrue(main.readText().contains("Lunch (edited)"))
        undoRestores(before)
    }

    @Test
    fun anEntryDeletedFromTheJournalIsDeletedThroughTheWriteProtocol() {
        val before = main.readText()
        val hash = lunchHash()

        val (code, answer) = call(
            "DELETE", "/${serving.ledgerSlug}/api/source_slice?entry_hash=$hash&sha256sum=${sha256(lunch)}",
        )

        assertEquals(answer, 200, code)
        assertFalse(main.readText().contains("Lunch"))
        undoRestores(before)
    }

    @Test
    fun anEntryAddedByTheFormIsAddedThroughTheWriteProtocol() {
        val before = main.readText()
        val entry = JSONObject(
            """{"t":"Transaction","date":"2024-01-04","flag":"*","payee":"","narration":"Tea","tags":[],
            "links":[],"meta":{},"postings":[{"account":"Expenses:Test","amount":"2.00 USD"},
            {"account":"Assets:Test","amount":""}]}""",
        )

        val (code, answer) = call(
            "PUT", "/${serving.ledgerSlug}/api/add_entries",
            JSONObject().put("entries", org.json.JSONArray().put(entry)).toString(),
        )

        assertEquals(answer, 200, code)
        assertTrue(main.readText().contains("Tea"))
        undoRestores(before)
    }
}
