package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Every release-blocking invariant in SPEC.md §5 that the write protocol owns, with a
 * named test each. The validator is faked so the protocol itself is under test; the real
 * Beancount validator is exercised in the instrumentation suite.
 */
class ProjectWriterTest {

    private val root: File = Files.createTempDirectory("writer-test").toFile()
    private val workspace = ProjectWorkspace(root)
    private val journal = WriteJournal(workspace)

    /** Accepts everything, like a ledger that always parses. */
    private val alwaysValid = LedgerValidator { Result.success(emptyList()) }

    /** Rejects everything, like a write that broke the ledger. */
    private val neverValid = LedgerValidator { Result.failure(IllegalStateException("parse failed")) }

    private val projectId = "p"

    private fun writer(validator: LedgerValidator = alwaysValid) =
        ProjectWriter(workspace, journal, validator)

    private fun newProject(): File {
        workspace.createProject(projectId, title = "Test")
        return workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    // --- invariant 2: no success before a durable, re-parsed write --------------------

    @Test
    fun `a write that parses is committed and visible on disk`() = runBlocking {
        val file = newProject()
        val before = file.readText()

        val result = writer().append(projectId, file, "2026-01-02 note Assets:Cash \"hi\"")

        assertTrue(result is WriteResult.Committed, "unexpected: $result")
        assertTrue(file.readText().startsWith(before), "existing content must be preserved")
        assertTrue(file.readText().contains("note Assets:Cash"))
    }

    @Test
    fun `a write that breaks the ledger is rolled back, not reported as success`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        val beforeSha = WriteJournal.sha256(file)

        val result = writer(neverValid).append(projectId, file, "this is not beancount")

        assertTrue(result is WriteResult.RolledBack, "unexpected: $result")
        // Invariant 3: the previous valid project is recoverable — in fact restored.
        assertEquals(before, file.readText())
        assertEquals(beforeSha, WriteJournal.sha256(file))
    }

    // --- invariant 6: a retried write never duplicates -------------------------------

    @Test
    fun `a stale write is refused and changes nothing`() = runBlocking {
        val file = newProject()
        val staleSha = WriteJournal.sha256(file)

        // Something else writes first — another editor, a mirror, a script.
        file.appendText("\n2026-01-03 note Assets:Cash \"external\"\n")
        val externalContent = file.readText()

        val result = writer().replace(projectId, file, staleSha, "replacement")

        assertTrue(result is WriteResult.Refused, "unexpected: $result")
        assertEquals(externalContent, file.readText(), "a refused write must change nothing")
    }

    @Test
    fun `replaying the same append twice does not duplicate the entry`() = runBlocking {
        val file = newProject()
        val text = "2026-01-02 note Assets:Cash \"once\""
        val sha = WriteJournal.sha256(file)

        val first = writer().append(projectId, file, text)
        assertTrue(first is WriteResult.Committed)

        // A retry carrying the *original* hash — the shape a duplicate submit takes.
        val retry = writer().replace(projectId, file, sha, file.readText() + text)
        assertTrue(retry is WriteResult.Refused, "a retry against a stale hash must be refused")
        assertEquals(1, file.readText().split("\"once\"").size - 1, "entry must appear once")
    }

    // --- invariant 4: nothing else in the file is touched ----------------------------

    @Test
    fun `an append leaves every existing byte untouched`() = runBlocking {
        val file = newProject()
        file.writeText(
            """
            ; a comment that must survive
            2026-01-01 note Assets:Cash "existing"

            ; trailing comment
            """.trimIndent() + "\n"
        )
        val before = file.readText()

        writer().append(projectId, file, "2026-01-05 note Assets:Cash \"new\"")

        val after = file.readText()
        assertTrue(after.startsWith(before), "prefix must be byte-identical:\n$after")
        assertTrue(after.contains("; a comment that must survive"))
        assertTrue(after.contains("; trailing comment"))
    }

    // --- path safety ------------------------------------------------------------------

    @Test
    fun `a write outside the project is refused`() = runBlocking {
        newProject()
        val escape = File(root, "escape.beancount")

        val result = writer().append(projectId, escape, "nope")

        assertTrue(result is WriteResult.Refused, "unexpected: $result")
        assertFalse(escape.exists(), "nothing may be written outside the project")
    }

    // --- invariant 8: undo is backed by durable snapshots ----------------------------

    @Test
    fun `undo restores the previous content from a snapshot`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        val w = writer()

        assertTrue(w.append(projectId, file, "2026-01-02 note Assets:Cash \"undo me\"") is WriteResult.Committed)
        assertTrue(file.readText().contains("undo me"))

        val undo = w.undoLast(projectId)

        assertTrue(undo is WriteResult.Committed, "unexpected: $undo")
        assertEquals(before, file.readText())
    }

    @Test
    fun `undo works after a fresh start, because the snapshot is on disk`() = runBlocking {
        val file = newProject()
        val before = file.readText()

        writer().append(projectId, file, "2026-01-02 note Assets:Cash \"survives\"")

        // A brand-new writer and journal: nothing carried over in memory.
        val restarted = ProjectWriter(workspace, WriteJournal(workspace), alwaysValid)
        val undo = restarted.undoLast(projectId)

        assertTrue(undo is WriteResult.Committed, "unexpected: $undo")
        assertEquals(before, file.readText())
    }

    @Test
    fun `undo with nothing to undo is refused rather than doing something surprising`() = runBlocking {
        newProject()
        assertTrue(writer().undoLast(projectId) is WriteResult.Refused)
    }

    // --- recovery from process death --------------------------------------------------

    @Test
    fun `an interrupted write whose result parses is rolled forward`() = runBlocking {
        val file = newProject()
        // Simulate dying between rename and commit: the new content is on disk, the
        // journal record is still open.
        val record = journal.begin(projectId, "append", listOf(file))
        file.appendText("\n2026-01-02 note Assets:Cash \"half-written\"\n")
        journal.markRenamed(record)

        val outcomes = writer().recover(projectId)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().rolledForward)
        assertTrue(file.readText().contains("half-written"), "a valid write must be kept")
        assertTrue(journal.pending(projectId).isEmpty(), "no record may stay pending")
    }

    @Test
    fun `an interrupted write whose result does not parse is rolled back`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        val record = journal.begin(projectId, "append", listOf(file))
        file.appendText("\nthis will not parse\n")
        journal.markRenamed(record)

        val outcomes = writer(neverValid).recover(projectId)

        assertEquals(1, outcomes.size)
        assertFalse(outcomes.single().rolledForward)
        assertEquals(before, file.readText(), "the previous valid project must come back")
        assertTrue(journal.pending(projectId).isEmpty())
    }

    @Test
    fun `a write interrupted before the rename leaves the file untouched`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        // Died after journalling intent but before writing anything.
        journal.begin(projectId, "append", listOf(file))

        val outcomes = writer().recover(projectId)

        assertEquals(1, outcomes.size)
        assertEquals(before, file.readText())
    }

    @Test
    fun `an interrupted write that introduced errors is rolled back, even though it parses`() = runBlocking {
        val file = newProject()
        val before = file.readText()

        // The ledger parses both before and after; what changed is the error count. A new
        // process cannot know the "before" value, which is why it is journalled.
        val record = journal.begin(
            projectId, "append", listOf(file),
            baselineErrors = 0, rollbackOnNewErrors = true,
        )
        file.appendText("\n2026-01-02 balance Assets:Cash 999.00 USD\n")
        journal.markRenamed(record)

        val twoErrors = LedgerValidator { Result.success(listOf("error one", "error two")) }
        val outcomes = ProjectWriter(workspace, journal, twoErrors).recover(projectId)

        assertFalse(outcomes.single().rolledForward, "a write that added errors must not be kept")
        assertEquals(before, file.readText())
    }

    @Test
    fun `an interrupted raw edit is kept, because the user typed it`() = runBlocking {
        val file = newProject()
        val record = journal.begin(
            projectId, "raw-edit", listOf(file),
            baselineErrors = 0, rollbackOnNewErrors = false,
        )
        file.appendText("\n2026-01-02 balance Assets:Cash 999.00 USD\n")
        journal.markRenamed(record)

        val twoErrors = LedgerValidator { Result.success(listOf("error one", "error two")) }
        val outcomes = ProjectWriter(workspace, journal, twoErrors).recover(projectId)

        assertTrue(outcomes.single().rolledForward, "a raw edit must survive recovery")
        assertTrue(file.readText().contains("999.00"))
    }

    @Test
    fun `recovery is idempotent`() = runBlocking {
        val file = newProject()
        val record = journal.begin(projectId, "append", listOf(file))
        file.appendText("\n2026-01-02 note Assets:Cash \"x\"\n")
        journal.markRenamed(record)

        val w = writer()
        assertEquals(1, w.recover(projectId).size)
        assertEquals(0, w.recover(projectId).size, "a second recovery must find nothing")
    }

    // --- housekeeping -----------------------------------------------------------------

    @Test
    fun `snapshots are pruned to the undo depth`() = runBlocking {
        val file = newProject()
        val w = ProjectWriter(workspace, journal, alwaysValid, undoDepth = 3)

        repeat(6) { i -> w.append(projectId, file, "2026-01-0${i + 1} note Assets:Cash \"$i\"") }

        assertEquals(3, journal.committed(projectId).size + journal.pending(projectId).size)
    }

    @Test
    fun `runtime state stays out of the project tree`() = runBlocking {
        val file = newProject()
        writer().append(projectId, file, "2026-01-02 note Assets:Cash \"x\"")

        val projectFiles = workspace.projectDir(projectId).walkTopDown().map { it.name }.toList()
        assertFalse(projectFiles.any { it.endsWith(".tmp") }, "temp files must not survive")
        assertFalse(projectFiles.contains("state"), "journal state must not live in the project")
    }
}
