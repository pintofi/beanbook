package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Writing bytes through the same protocol that guards text.
 *
 * The mirror pulls files in from a folder the app does not control, and those files are not
 * all text: a project carries PDFs and other attachments beside its ledger. Before this
 * existed the mirror wrote them with a local helper — no journal, no snapshot, no re-parse,
 * no undo entry — and then reported the pass as synced, which is invariants 2, 3 and 8 at
 * once.
 *
 * Text cannot carry those bytes: a String round trip through UTF-8 replaces anything that is
 * not valid UTF-8, so a PDF pulled in through the text API would arrive corrupted and the
 * ledger would still parse, so nothing would notice.
 */
class ProjectWriterBytesTest {

    private val root: File = Files.createTempDirectory("writer-bytes-test").toFile()
    private val workspace = ProjectWorkspace(root)
    private val journal = WriteJournal(workspace)
    private val alwaysValid = LedgerValidator { Result.success(emptyList()) }
    private val projectId = "p"

    private fun writer(validator: LedgerValidator = alwaysValid) =
        ProjectWriter(workspace, journal, validator)

    private fun newProject(): File {
        workspace.createProject(projectId, title = "Test")
        return workspace.projectDir(projectId).resolve("documents/receipt.pdf")
    }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    /** A real PDF header, a null byte, and a byte that is not valid UTF-8 on its own. */
    private val binary = byteArrayOf(
        0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37,   // %PDF-1.7
        0x00,                                             // a null byte
        0xC3.toByte(), 0x28, 0xFF.toByte(),               // not valid UTF-8
    )

    @Test
    fun `bytes survive the write exactly`() = runBlocking {
        val file = newProject()

        val result = writer().replaceBytes(projectId, file, expectedSha = "", newContent = binary)

        assertTrue(result is WriteResult.Committed, "unexpected: $result")
        assertContentEquals(binary, file.readBytes(), "the bytes on disk are not the bytes written")
    }

    @Test
    fun `a byte write is journalled, so it can be undone`() = runBlocking {
        val file = newProject()
        writer().replaceBytes(projectId, file, expectedSha = "", newContent = binary)

        val records = journal.committed(projectId)
        assertTrue(
            records.any { it.operation == "mirror-pull-in" || it.operation == "replace-bytes" },
            "a byte write left no journal record: ${records.map { it.operation }}",
        )
    }

    @Test
    fun `a byte write refuses when the file changed underneath it`() = runBlocking {
        val file = newProject()
        writer().replaceBytes(projectId, file, expectedSha = "", newContent = binary)

        // Second write claims the file was still absent.
        val stale = writer().replaceBytes(projectId, file, expectedSha = "", newContent = binary)

        assertTrue(stale is WriteResult.Refused, "a stale byte write was not refused: $stale")
        assertContentEquals(binary, file.readBytes(), "the refused write changed the file")
    }

    @Test
    fun `a byte write that breaks the ledger is rolled back`() = runBlocking {
        val file = newProject()
        writer().replaceBytes(projectId, file, expectedSha = "", newContent = binary)
        val sha = WriteJournal.sha256(file)

        val brokenLedger = LedgerValidator { Result.failure(IllegalStateException("parse failed")) }
        val result = writer(brokenLedger)
            .replaceBytes(projectId, file, expectedSha = sha, newContent = byteArrayOf(1, 2, 3))

        assertTrue(result is WriteResult.RolledBack, "unexpected: $result")
        assertContentEquals(binary, file.readBytes(), "the rollback did not restore the bytes")
    }

    @Test
    fun `a byte write may not escape the project`() = runBlocking {
        newProject()
        val outside = File(root, "escaped.pdf")

        val result = writer().replaceBytes(projectId, outside, expectedSha = "", newContent = binary)

        assertTrue(result is WriteResult.Refused, "a write outside the project was allowed: $result")
        assertEquals(false, outside.exists(), "the file was written outside the project anyway")
    }
}
