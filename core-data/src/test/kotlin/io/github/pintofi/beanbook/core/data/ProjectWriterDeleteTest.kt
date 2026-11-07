package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Deleting a file goes through the write protocol: snapshotted, re-parsed, undoable, and refused where it must be. */
class ProjectWriterDeleteTest {

    private val root: File = Files.createTempDirectory("writer-delete").toFile()
    private val workspace = ProjectWorkspace(root)
    private val journal = WriteJournal(workspace)
    private val projectId = "p"
    private var errors = emptyList<String>()
    private val validator = LedgerValidator { Result.success(errors) }
    private val writer = ProjectWriter(workspace, journal, validator)

    private fun project(): File {
        workspace.createProject(projectId, title = "Test")
        return File(workspace.projectDir(projectId), "files/old.txt").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 0, 0xFF.toByte()))
        }
    }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `a delete removes the file and is journalled`() = runBlocking<Unit> {
        val file = project()

        val result = writer.delete(projectId, file, WriteJournal.sha256(file), "test-delete")

        assertIs<WriteResult.Committed>(result)
        assertFalse(file.exists())
        assertTrue(journal.committed(projectId).any { it.operation == "test-delete" })
    }

    @Test fun `undo puts the file back byte for byte`() = runBlocking<Unit> {
        val file = project()
        val before = file.readBytes()
        writer.delete(projectId, file, WriteJournal.sha256(file))

        assertIs<WriteResult.Committed>(writer.undoLast(projectId))

        assertContentEquals(before, file.readBytes())
    }

    @Test fun `a file changed since it was read is not deleted`() = runBlocking<Unit> {
        val file = project()
        val stale = WriteJournal.sha256(file)
        file.writeText("changed")

        assertIs<WriteResult.Refused>(writer.delete(projectId, file, stale))
        assertTrue(file.exists())
    }

    @Test fun `the main ledger file cannot be deleted`() = runBlocking<Unit> {
        project()
        val main = workspace.mainFile(projectId)

        assertIs<WriteResult.Refused>(writer.delete(projectId, main, WriteJournal.sha256(main)))
        assertTrue(main.exists())
    }

    @Test fun `a file that is not there is refused`() = runBlocking<Unit> {
        project()
        val gone = File(workspace.projectDir(projectId), "files/never.txt")

        assertIs<WriteResult.Refused>(writer.delete(projectId, gone, ""))
    }

    @Test fun `a path outside the project is refused`() = runBlocking<Unit> {
        project()
        val outside = File(root, "outside.txt").apply { writeText("x") }

        assertIs<WriteResult.Refused>(writer.delete(projectId, outside, WriteJournal.sha256(outside)))
        assertTrue(outside.exists())
    }

    @Test fun `a delete that adds an error is rolled back and the file returns`() = runBlocking<Unit> {
        val file = project()
        val before = file.readBytes()
        val calls = intArrayOf(0)
        val failing = ProjectWriter(
            workspace,
            journal,
            LedgerValidator { Result.success(if (calls[0]++ == 0) emptyList() else listOf("an include is missing")) },
        )

        val result = failing.delete(projectId, file, WriteJournal.sha256(file))

        assertIs<WriteResult.RolledBack>(result)
        assertEquals(before.toList(), file.readBytes().toList())
    }
}
