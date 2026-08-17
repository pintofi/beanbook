package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.data.ProjectWriter
import dev.beancount.core.data.WriteJournal
import dev.beancount.core.data.WriteResult
import dev.beancount.engine.local.BeancountEditor
import dev.beancount.engine.local.BeancountValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The write protocol against **real Beancount**, on the device.
 *
 * ProjectWriterTest already covers the protocol with a fake validator; this suite exists
 * because the thing that decides whether a write is kept or rolled back is Beancount
 * itself, and that judgement has to be exercised for real.
 */
@RunWith(AndroidJUnit4::class)
class AtomicWriteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: ProjectWorkspace
    private lateinit var journal: WriteJournal
    private lateinit var writer: ProjectWriter
    private lateinit var editor: BeancountEditor
    private lateinit var transactions: File

    private val projectId = "atomic"

    @Before
    fun setUp() {
        val root = File(context.cacheDir, "atomic-${System.nanoTime()}")
        workspace = ProjectWorkspace(root)
        journal = WriteJournal(workspace)
        writer = ProjectWriter(workspace, journal, BeancountValidator(context))
        editor = BeancountEditor(context)
        workspace.createProject(projectId, title = "Atomic")
        transactions = workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    private fun main(): String = workspace.mainFile(projectId).absolutePath

    @Test
    fun a_valid_entry_is_committed_and_reparses() = runBlocking {
        val result = writer.append(
            projectId,
            transactions,
            """
            2027-01-05 * "Grocer" "Weekly shop"
              Expenses:Food:Groceries    42.30 USD
              Assets:Cash
            """.trimIndent(),
        )

        assertTrue("unexpected: $result", result is WriteResult.Committed)
        assertTrue((result as WriteResult.Committed).warnings.isEmpty())
        assertTrue(transactions.readText().contains("Weekly shop"))
        assertEquals(1, journal.committed(projectId).size)
    }

    @Test
    fun a_generated_entry_that_does_not_balance_is_rolled_back() = runBlocking {
        val before = transactions.readText()

        // A generated write that does not balance is our bug, not the user's intent, so
        // the default policy rolls it back.
        val result = writer.append(
            projectId,
            transactions,
            """
            2027-01-06 * "Broken" "Does not balance"
              Expenses:Food:Groceries    42.30 USD
              Assets:Cash               -40.00 USD
            """.trimIndent(),
        )

        assertTrue("unexpected: $result", result is WriteResult.RolledBack)
        assertTrue((result as WriteResult.RolledBack).errors.any { it.contains("does not balance") })
        assertEquals("the file must be restored", before, transactions.readText())
    }

    @Test
    fun the_raw_editor_keeps_what_the_user_typed_and_reports_the_errors() = runBlocking {
        // KEEP_AND_REPORT is the raw-editor policy: refusing to save would lose the
        // user's work, so the text stays and the errors are surfaced.
        val result = writer.append(
            projectId,
            transactions,
            """
            2027-01-06 * "Broken" "Does not balance"
              Expenses:Food:Groceries    42.30 USD
              Assets:Cash               -40.00 USD
            """.trimIndent(),
            policy = dev.beancount.core.data.ErrorPolicy.KEEP_AND_REPORT,
        )

        assertTrue("unexpected: $result", result is WriteResult.Committed)
        val warnings = (result as WriteResult.Committed).warnings
        assertTrue("expected the error to be surfaced, got $warnings", warnings.isNotEmpty())
        assertTrue(warnings.any { it.contains("does not balance") })
        assertTrue(transactions.readText().contains("Does not balance"))
    }

    @Test
    fun syntactically_broken_input_is_rolled_back_and_the_file_is_restored() = runBlocking {
        val before = transactions.readText()
        val beforeSha = WriteJournal.sha256(transactions)

        // Beancount does not raise on garbage: it reports LexerErrors and returns zero
        // entries. The rollback therefore comes from the error delta, not from an
        // exception (docs/milestones/m4.md).
        val result = writer.append(projectId, transactions, "2026-13-45 this is not beancount {{{")

        assertTrue("unexpected: $result", result is WriteResult.RolledBack)
        assertEquals("the previous valid file must be restored", before, transactions.readText())
        assertEquals(beforeSha, WriteJournal.sha256(transactions))
        assertTrue("no journal record may stay pending", journal.pending(projectId).isEmpty())
    }

    @Test
    fun editing_one_entry_leaves_every_other_byte_untouched() = runBlocking {
        writer.append(
            projectId,
            transactions,
            """
            ; a comment that must survive the edit
            2027-01-05 * "Grocer" "Weekly shop"
              Expenses:Food:Groceries    42.30 USD
              Assets:Cash

            2027-01-07 * "Bakery" "Bread"
              Expenses:Food:Groceries     3.50 USD
              Assets:Cash
            """.trimIndent(),
        )
        val before = transactions.readText()

        // Locate the first transaction the way the editor will: by file and line.
        val line = before.lines().indexOfFirst { it.contains("Weekly shop") } + 1
        val slice = editor.sliceAt(main(), transactions.absolutePath, line)
        assertNotNull("Beancount should report a slice at line $line", slice)

        val updated = editor.contentWithReplacement(
            transactions.absolutePath,
            slice!!.startLine,
            slice.endLine,
            slice.text.replace("42.30", "43.30"),
        )
        val result = writer.replace(
            projectId,
            transactions,
            WriteJournal.sha256(transactions),
            updated,
        )

        assertTrue("unexpected: $result", result is WriteResult.Committed)
        val after = transactions.readText()

        // Exactly one line differs, and everything else is byte-identical.
        val differing = before.lines().zip(after.lines()).count { (a, b) -> a != b }
        assertEquals("only the edited line may change", 1, differing)
        assertTrue(after.contains("; a comment that must survive the edit"))
        assertTrue(after.contains("43.30"))
        assertTrue(after.contains("Bread"))
        assertFalse(after.contains("42.30"))
    }

    @Test
    fun undo_restores_the_previous_content_and_reparses() = runBlocking {
        val before = transactions.readText()
        writer.append(
            projectId,
            transactions,
            """
            2027-01-05 * "Grocer" "Undo me"
              Expenses:Food:Groceries    10.00 USD
              Assets:Cash
            """.trimIndent(),
        )
        assertTrue(transactions.readText().contains("Undo me"))

        val undo = writer.undoLast(projectId)

        assertTrue("unexpected: $undo", undo is WriteResult.Committed)
        assertEquals(before, transactions.readText())
    }

    @Test
    fun a_stale_write_is_refused_against_the_real_ledger() = runBlocking {
        val staleSha = WriteJournal.sha256(transactions)
        transactions.appendText("\n; changed by something else\n")
        val external = transactions.readText()

        val result = writer.replace(projectId, transactions, staleSha, "; replacement\n")

        assertTrue("unexpected: $result", result is WriteResult.Refused)
        assertEquals(external, transactions.readText())
    }
}
