package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.data.RollbackCause
import io.github.pintofi.beanbook.core.data.WriteResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With the real engine: an entry that does not balance adds an error, is undone and held, and Save anyway makes
 * it with one more error in the ledger, which Undo takes back.
 */
@RunWith(AndroidJUnit4::class)
class KeepWithErrorsTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as BeancountApplication
    private val projectId = "keep-errors-${System.currentTimeMillis()}"

    @After
    fun cleanUp() {
        app.workspace.projectDir(projectId).deleteRecursively()
    }

    private val unbalanced = "2026-01-02 * \"Lunch\"\n  Expenses:Food  5.00 USD\n  Assets:Cash  -4.00 USD"

    @Test
    fun anEntryThatDoesNotBalanceIsHeldAndCanBeSavedAnywayAndUndone() = runBlocking {
        app.workspace.createProject(projectId, title = "Keep errors")
        val file = app.workspace.projectDir(projectId).resolve("files/transactions.beancount")
        val before = file.readText()

        val refused = app.writer.append(projectId, file, unbalanced)

        val undone = refused as WriteResult.RolledBack
        assertEquals(RollbackCause.NEW_ERRORS, undone.cause)
        assertEquals(before, file.readText())
        val held = checkNotNull(undone.held)
        assertTrue(held.newErrors.isNotEmpty())

        val kept = app.writer.keepWithErrors(held)

        val committed = kept as WriteResult.Committed
        assertTrue(committed.warnings.isNotEmpty())
        assertTrue(file.readText().contains("Lunch"))
        assertEquals(1, Regex("Lunch").findAll(file.readText()).count())

        val undo = app.writer.undoLast(projectId)
        assertTrue(undo is WriteResult.Committed)
        assertEquals(before, file.readText())
    }

    @Test
    fun theSameChangeCannotBeSavedAnywayTwice() = runBlocking {
        app.workspace.createProject(projectId, title = "Keep errors")
        val file: File = app.workspace.projectDir(projectId).resolve("files/transactions.beancount")
        val held = (app.writer.append(projectId, file, unbalanced) as WriteResult.RolledBack).held!!

        assertTrue(app.writer.keepWithErrors(held) is WriteResult.Committed)

        assertTrue(app.writer.keepWithErrors(held) is WriteResult.Refused)
        assertEquals(1, Regex("Lunch").findAll(file.readText()).count())
    }
}
