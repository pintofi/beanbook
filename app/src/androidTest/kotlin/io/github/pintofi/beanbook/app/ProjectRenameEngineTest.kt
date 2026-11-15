package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.ProjectRename
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.data.WriteResult
import io.github.pintofi.beanbook.engine.local.LedgerReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rename is read back by everything that shows a project's name: the project list, and
 * Beancount itself reading the ledger, with no restart in between.
 */
@RunWith(AndroidJUnit4::class)
class ProjectRenameEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as BeancountApplication
    private val id = "rename-check-${System.nanoTime()}"

    @After
    fun cleanUp() {
        app.workspace.projectDir(id).deleteRecursively()
    }

    @Test
    fun a_rename_is_seen_by_the_project_list_and_by_the_engine_without_a_restart() = runBlocking {
        app.workspace.createProject(id, title = "Before")
        val main = app.workspace.mainFile(id)
        assertEquals("Before", app.registry.summarise(id).title)

        val updated = ProjectRename.apply(main.readBytes(), "After").getOrThrow()
        val result = app.writer.replace(id, main, WriteJournal.sha256(main), updated, operation = "rename-project")

        assertTrue("got $result", result is WriteResult.Committed)
        assertEquals("After", app.registry.summarise(id).title)
        val snapshot = LedgerReader(context).read(main.absolutePath).getOrThrow()
        assertEquals("After", snapshot.title)
        assertEquals(0, snapshot.errors.size)
    }
}
