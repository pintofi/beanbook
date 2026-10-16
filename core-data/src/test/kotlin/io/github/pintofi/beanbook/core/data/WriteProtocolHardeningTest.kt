package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * What the write protocol promises when things go wrong in ways the happy path never shows:
 * the caller is cancelled in the middle, the clock goes backwards, the saved copy is damaged,
 * the engine is gone when the app starts again.
 */
class WriteProtocolHardeningTest {

    private val root: File = Files.createTempDirectory("hardening").toFile()
    private val workspace = ProjectWorkspace(root)
    private val projectId = "p"

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun newProject(): File {
        workspace.createProject(projectId, title = "Test")
        return workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    @Test
    fun `a write that is cancelled while it is being checked is still finished`() = runBlocking {
        val file = newProject()
        val journal = WriteJournal(workspace)
        val reachedCheck = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        // The first call is the check before the write; the second is the re-parse after it.
        val validator = LedgerValidator {
            if (calls.incrementAndGet() == 2) {
                reachedCheck.complete(Unit)
                releaseCheck.await()
            }
            Result.success(emptyList())
        }
        val writer = ProjectWriter(workspace, journal, validator)

        val job = launch { writer.append(projectId, file, "2026-01-02 note Assets:Cash \"hi\"") }
        reachedCheck.await()
        // The screen goes away while the engine is still re-parsing.
        val cancelling = launch { job.cancelAndJoin() }
        releaseCheck.complete(Unit)
        cancelling.join()

        assertTrue(file.readText().contains("note Assets:Cash"), "the write itself landed")
        assertTrue(journal.pending(projectId).isEmpty(), "no record may be left undecided")
        assertEquals(1, journal.committed(projectId).size, "it was committed, not abandoned")
    }

    @Test
    fun `a cancelled write that turns out to break the ledger is put back`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        val journal = WriteJournal(workspace)
        val reachedCheck = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val validator = LedgerValidator {
            when (calls.incrementAndGet()) {
                1 -> Result.success(emptyList())
                else -> {
                    reachedCheck.complete(Unit)
                    releaseCheck.await()
                    Result.success(listOf("a new error"))
                }
            }
        }
        val writer = ProjectWriter(workspace, journal, validator)

        val job = launch { writer.append(projectId, file, "2026-01-02 note Assets:Cash \"x\"") }
        reachedCheck.await()
        val cancelling = launch { job.cancelAndJoin() }
        releaseCheck.complete(Unit)
        cancelling.join()

        assertEquals(before, file.readText(), "the previous valid ledger is back")
        assertTrue(journal.pending(projectId).isEmpty())
    }

    @Test
    fun `an interrupted raw edit is kept when the engine is not there to check it`() = runBlocking {
        val file = newProject()
        val journal = WriteJournal(workspace)
        val record = journal.begin(
            projectId, "raw-edit", listOf(file),
            baselineErrors = 0, rollbackOnNewErrors = false,
        )
        file.appendText("\n2026-01-02 note Assets:Cash \"typed by the user\"\n")
        journal.markRenamed(record)

        val engineDown = LedgerValidator { Result.failure(LedgerUnavailable("engine unavailable")) }
        val outcomes = ProjectWriter(workspace, journal, engineDown).recover(projectId)

        assertTrue(outcomes.single().rolledForward, "typed text is not discarded because the engine is down")
        assertTrue(file.readText().contains("typed by the user"))
    }

    @Test
    fun `an interrupted generated write is still given up when the engine is not there`() = runBlocking {
        val file = newProject()
        val before = file.readText()
        val journal = WriteJournal(workspace)
        val record = journal.begin(projectId, "append", listOf(file))
        file.appendText("\n2026-01-02 note Assets:Cash \"generated\"\n")
        journal.markRenamed(record)

        val engineDown = LedgerValidator { Result.failure(LedgerUnavailable("engine unavailable")) }
        val outcomes = ProjectWriter(workspace, journal, engineDown).recover(projectId)

        assertFalse(outcomes.single().rolledForward)
        assertEquals(before, file.readText())
    }

    @Test
    fun `a clock that goes backwards does not reorder the undo stack`() = runBlocking {
        val file = newProject()
        var now = 2_000_000_000_000L
        val journal = WriteJournal(workspace) { now }
        val valid = LedgerValidator { Result.success(emptyList()) }
        val writer = ProjectWriter(workspace, journal, valid)

        val first = writer.append(projectId, file, "2026-01-02 note Assets:Cash \"first\"")
        now = 1_000L // the device clock is reset to a time long before the first write
        val second = writer.append(projectId, file, "2026-01-03 note Assets:Cash \"second\"")

        val firstId = (first as WriteResult.Committed).txnId
        val secondId = (second as WriteResult.Committed).txnId
        assertTrue(secondId > firstId, "ids must still sort in the order the writes were made")
        assertEquals(secondId, journal.committed(projectId).first().txnId, "Undo offers the latest write")

        // And undoing it takes away the second entry, not the first.
        assertTrue(writer.undoLast(projectId) is WriteResult.Committed)
        assertTrue(file.readText().contains("first"))
        assertFalse(file.readText().contains("second"))
    }

    @Test
    fun `a damaged saved copy is not restored over a ledger that is intact`() {
        val file = newProject()
        val journal = WriteJournal(workspace)
        val record = journal.begin(projectId, "append", listOf(file))
        file.appendText("\n2026-01-02 note Assets:Cash \"changed\"\n")
        journal.markRenamed(record)
        val changed = file.readText()

        // The snapshot is cut short, as it could be after a power loss that never flushed it.
        val saved = journal.snapshotDir(projectId, record.txnId)
            .resolve("files/transactions.beancount")
        saved.writeBytes(saved.readBytes().copyOf(3))

        assertFailsWith<IOException> { journal.restoreSnapshot(record) }
        assertEquals(changed, file.readText(), "the live file was left alone")
    }

    @Test
    fun `a missing saved copy is reported, not silently skipped`() {
        val file = newProject()
        val journal = WriteJournal(workspace)
        val record = journal.begin(projectId, "append", listOf(file))
        journal.snapshotDir(projectId, record.txnId).deleteRecursively()

        assertFailsWith<IOException> { journal.restoreSnapshot(record) }
    }

    @Test
    fun `a file that did not exist before the write is removed by a restore`() {
        val file = workspace.createProject(projectId, title = "Test")
            .parentFile.resolve("files/new.beancount")
        val journal = WriteJournal(workspace)
        val record = journal.begin(projectId, "append", listOf(file))
        file.parentFile.mkdirs()
        file.writeText("; created by the write\n")

        journal.restoreSnapshot(record)

        assertFalse(file.exists())
    }
}
