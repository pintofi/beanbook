package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A change undone for adding errors is held exactly, and can be made again with the errors kept. */
class HeldWriteTest {

    private val root: File = Files.createTempDirectory("held-test").toFile()
    private val workspace = ProjectWorkspace(root)
    private val journal = WriteJournal(workspace)
    private val held = HeldWrites(workspace)
    private val projectId = "p"

    /** Reports an error for every line of the ledger that says BAD, so a test chooses what adds one. */
    private val validator = LedgerValidator { path ->
        val bad = File(path).parentFile.walkTopDown()
            .filter { it.isFile && it.extension == "beancount" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line -> if ("BAD" in line) "${f.name}:${i + 1}: boom" else null }
            }
            .toList()
        Result.success(bad)
    }

    private fun writer(check: LedgerValidator = validator) = ProjectWriter(workspace, journal, check)

    private fun target(): File {
        workspace.createProject(projectId, title = "Test")
        return workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    @AfterTest fun cleanUp() = root.deleteRecursively().let { }

    private suspend fun undone(
        file: File,
        text: String = "2026-01-02 note Assets:Cash \"BAD\"",
    ): WriteResult.RolledBack = assertIs<WriteResult.RolledBack>(writer().append(projectId, file, text))

    @Test
    fun `a change that adds an error is undone, and carries the change and its errors`() = runBlocking {
        val file = target()
        val before = file.readText()

        val result = undone(file)

        assertEquals(RollbackCause.NEW_ERRORS, result.cause)
        assertEquals(before, file.readText())
        val change = assertNotNull(result.held)
        assertEquals("files/transactions.beancount", change.relativePath)
        assertEquals(WriteJournal.sha256(file), change.expectedSha)
        assertEquals("append", change.operation)
        assertEquals(1, change.newErrors.size)
        assertTrue(String(change.content!!).contains("note Assets:Cash \"BAD\""))
    }

    @Test
    fun `keeping it writes the change and the error, and Undo takes it back`() = runBlocking {
        val file = target()
        val before = file.readText()
        val change = undone(file).held!!

        val result = writer().keepWithErrors(change)

        val committed = assertIs<WriteResult.Committed>(result)
        assertEquals(1, committed.warnings.size)
        assertTrue(file.readText().contains("BAD"))
        assertEquals("with-errors:append", journal.committed(projectId).first().operation)

        val undo = writer().undoLast(projectId)
        assertIs<WriteResult.Committed>(undo)
        assertEquals(before, file.readText())
    }

    @Test
    fun `keeping an appended entry twice does not write it twice`() = runBlocking {
        val file = target()
        val change = undone(file).held!!

        assertIs<WriteResult.Committed>(writer().keepWithErrors(change))
        val again = writer().keepWithErrors(change)

        assertIs<WriteResult.Refused>(again)
        assertEquals(1, Regex("BAD").findAll(file.readText()).count())
    }

    @Test
    fun `a file changed since is not overwritten`() = runBlocking {
        val file = target()
        val change = undone(file).held!!
        file.appendText("\n; typed somewhere else\n")
        val changed = file.readText()

        assertIs<WriteResult.Refused>(writer().keepWithErrors(change))

        assertEquals(changed, file.readText())
    }

    @Test
    fun `only a change that adds errors can be held`() = runBlocking {
        val file = target()
        val unreadable = LedgerValidator { Result.failure(IllegalStateException("parse failed")) }

        val result = assertIs<WriteResult.RolledBack>(writer(unreadable).append(projectId, file, "x"))

        assertEquals(RollbackCause.UNREADABLE, result.cause)
        assertNull(result.held)
        // The engine goes away between the check before the write and the check after it.
        var calls = 0
        val dies = LedgerValidator {
            if (calls++ == 0) Result.success(emptyList()) else Result.failure(LedgerUnavailable("engine down"))
        }
        val second = assertIs<WriteResult.RolledBack>(writer(dies).append(projectId, file, "y"))
        assertEquals(RollbackCause.UNCHECKED, second.cause)
        assertNull(second.held)
    }

    @Test
    fun `a keep that was killed after the file was written is rolled forward`() = runBlocking {
        val file = target()
        val change = undone(file).held!!
        // The write landed and the process died before the commit: the journal says renamed, and the policy
        // was KEEP, so recovery keeps it rather than putting it back.
        val record = journal.begin(projectId, "with-errors:append", listOf(file), 0, rollbackOnNewErrors = false)
        file.writeBytes(change.content!!)
        journal.markRenamed(record)

        val outcomes = writer().recover(projectId)

        assertTrue(file.readText().contains("BAD"), outcomes.toString())
    }

    @Test
    fun `a held change survives a restart and stays outside the project`() = runBlocking {
        val file = target()
        val change = undone(file).held!!

        held.put(change)
        val restored = assertNotNull(HeldWrites(ProjectWorkspace(root)).get(projectId))

        assertEquals(change.relativePath, restored.relativePath)
        assertEquals(change.expectedSha, restored.expectedSha)
        assertEquals(change.operation, restored.operation)
        assertEquals(change.newErrors, restored.newErrors)
        assertTrue(change.content!!.contentEquals(restored.content!!))
        assertFalse(workspace.heldDir.canonicalPath.startsWith(workspace.projectDir(projectId).canonicalPath))
        assertEquals(emptyList(), workspace.projectDir(projectId).walkTopDown().filter { "held" in it.name }.toList())

        assertIs<WriteResult.Committed>(writer().keepWithErrors(restored))
        held.clear(projectId)
        assertNull(held.get(projectId))
    }

    @Test
    fun `a held delete is kept as a delete`() = runBlocking {
        target()
        held.put(HeldWrite(projectId, "files/x.beancount", "abc", null, "delete-entry", listOf("a: b")))

        val back = assertNotNull(held.get(projectId))

        assertNull(back.content)
        assertEquals(listOf("a: b"), back.newErrors)
    }

    @Test
    fun `a held change that is missing its content is not offered`() = runBlocking {
        target()
        held.put(HeldWrite(projectId, "files/x.beancount", "abc", "text".toByteArray(), "append", emptyList()))
        File(workspace.heldDir, "$projectId.content").delete()

        assertNull(held.get(projectId))
    }
}
