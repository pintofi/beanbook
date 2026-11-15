package io.github.pintofi.beanbook.app.undo

import io.github.pintofi.beanbook.core.data.HeldWrites
import io.github.pintofi.beanbook.core.data.LedgerValidator
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.data.WriteResult
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The question asked when a change adds errors: held, answered once, never asked for what was not typed. */
class HeldWriteControllerTest {

    private val root: File = Files.createTempDirectory("held-controller").toFile()
    private val workspace = ProjectWorkspace(root)
    private val validator = LedgerValidator { path ->
        val bad = File(path).parentFile.walkTopDown().filter { it.isFile && it.extension == "beancount" }
            .flatMap { f -> f.readLines().filter { "BAD" in it }.map { "${f.name}: boom" } }.toList()
        Result.success(bad)
    }
    private val writer = ProjectWriter(workspace, WriteJournal(workspace), validator)
    private val store = HeldWrites(workspace)
    private val controller = HeldWriteController(writer, store, CoroutineScope(Dispatchers.Default))
    private val projectId = "p"

    @After fun cleanUp() = root.deleteRecursively().let { }

    private fun file(): File {
        if (!workspace.exists(projectId)) workspace.createProject(projectId, title = "T")
        return workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    private fun held(): io.github.pintofi.beanbook.core.data.HeldWrite = runBlocking {
        val result = writer.append(projectId, file(), "2026-01-02 note Assets:Cash \"BAD\"")
        checkNotNull((result as WriteResult.RolledBack).held)
    }

    private fun settled() = runBlocking {
        withTimeout(10_000) { controller.offer.first { it == null || it.problem != null } }
    }

    @Test
    fun `asking holds the change and shows the question, and saving anyway makes it and tells the screen`() {
        val change = held()
        var told: WriteResult.Committed? = null

        controller.ask(change) { told = it }
        assertEquals("transactions.beancount", controller.offer.value!!.what.substringAfter("to "))
        assertNotNull(store.get(projectId))
        controller.keep()
        settled()

        assertNull(controller.offer.value)
        assertEquals(1, told!!.warnings.size)
        assertTrue(file().readText().contains("BAD"))
        assertNull(store.get(projectId))
    }

    @Test
    fun `not saving leaves the ledger as it was and forgets the change`() {
        val change = held()
        val before = file().readText()
        controller.ask(change) { error("must not be made") }

        controller.discard()

        assertNull(controller.offer.value)
        assertNull(store.get(projectId))
        assertEquals(before, file().readText())
    }

    @Test
    fun `a file that changed since is not overwritten, and the person is told`() {
        val change = held()
        controller.ask(change) { error("must not be made") }
        file().appendText("\n; typed elsewhere\n")
        val changed = file().readText()

        controller.keep()
        val offer = settled()

        assertNotNull(offer!!.problem)
        assertEquals(changed, file().readText())
        controller.close()
        assertNull(controller.offer.value)
    }

    @Test
    fun `a second tap does not write it twice`() {
        val change = held()
        controller.ask(change) { }

        controller.keep()
        controller.keep()
        settled()

        assertEquals(1, Regex("BAD").findAll(file().readText()).count())
    }

    @Test
    fun `a question that was open when the app stopped comes back, and saving anyway still works`() {
        val change = held()
        store.put(change)
        var restored: String? = null
        controller.afterRestored = { id, _ -> restored = id }

        controller.restore(projectId)
        assertNotNull(controller.offer.value)
        controller.keep()
        settled()

        assertEquals(projectId, restored)
        assertTrue(file().readText().contains("BAD"))
    }

    @Test
    fun `nothing comes back for a project with nothing held`() {
        file()

        controller.restore(projectId)

        assertNull(controller.offer.value)
    }

    @Test
    fun `only changes the person wrote directly may ask`() {
        listOf("quick-add", "add-directive", "edit-entry").forEach {
            assertTrue(it, HeldWriteController.overridable(it))
        }
        listOf(
            "mirror-pull-in", "shared:editor-save", "shared:entry-edit", "include-documents", "include-accounts",
            "editor-save", "add-entries", "entry-delete", "undo", "with-errors:quick-add", "import",
        ).forEach { assertFalse(it, HeldWriteController.overridable(it)) }
    }
}
