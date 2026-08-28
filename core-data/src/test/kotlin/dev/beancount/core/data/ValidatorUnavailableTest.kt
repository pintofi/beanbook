package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Telling "the engine is not there" apart from "the ledger will not parse" (R5).
 *
 * `BeancountValidator` returns a failed `Result` for both: the engine process being dead or
 * unbindable, and Beancount genuinely refusing the file. The writer could not distinguish
 * them, so a transient infrastructure failure was reported to the user as
 * "the ledger could not be parsed after the write" — a diagnosis about their data that was
 * not true — and the write was rolled back regardless of policy.
 *
 * That defeated `KEEP_AND_REPORT`, whose entire purpose is that the raw editor never discards
 * what somebody typed.
 */
class ValidatorUnavailableTest {

    private val root: File = Files.createTempDirectory("validator-unavailable").toFile()
    private val workspace = ProjectWorkspace(root)
    private val journal = WriteJournal(workspace)
    private val projectId = "p"

    private val engineDown = LedgerValidator { Result.failure(LedgerUnavailable("engine unavailable")) }
    private val unparseable = LedgerValidator { Result.failure(IllegalStateException("syntax error")) }

    private fun writer(validator: LedgerValidator) = ProjectWriter(workspace, journal, validator)

    private fun newProject(): File {
        workspace.createProject(projectId, title = "Test")
        return workspace.projectDir(projectId).resolve("files/transactions.beancount")
    }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `an unreachable engine is not reported as an unparseable ledger`() = runBlocking {
        val file = newProject()

        val result = writer(engineDown).append(projectId, file, "2026-01-02 note Assets:Cash \"hi\"")

        val reason = when (result) {
            is WriteResult.RolledBack -> result.reason
            is WriteResult.Refused -> result.reason
            is WriteResult.Committed -> ""
        }
        assertTrue(
            !reason.contains("could not be parsed"),
            "a missing engine was blamed on the ledger: $reason",
        )
        assertTrue(reason.contains("engine", ignoreCase = true), "the reason does not say why: $reason")
    }

    @Test
    fun `the raw editor keeps what was typed when the engine is unreachable`() = runBlocking {
        val file = newProject()
        val typed = "2026-01-02 note Assets:Cash \"kept\""

        val result = writer(engineDown)
            .append(projectId, file, typed, policy = ErrorPolicy.KEEP_AND_REPORT)

        assertTrue(
            file.readText().contains("kept"),
            "KEEP_AND_REPORT discarded the user's text because the engine was down: $result",
        )
    }

    @Test
    fun `a genuinely unparseable ledger still rolls back and still says so`() = runBlocking {
        val file = newProject()
        val before = file.readText()

        val result = writer(unparseable).append(projectId, file, "this is not beancount")

        assertTrue(result is WriteResult.RolledBack, "unexpected: $result")
        assertTrue(
            (result as WriteResult.RolledBack).reason.contains("could not be parsed"),
            "the real parse failure lost its diagnosis: ${result.reason}",
        )
        assertEquals(before, file.readText(), "the rollback did not restore the file")
    }

    @Test
    fun `a generated write is refused before anything is written when the engine is down`() = runBlocking {
        val file = newProject()
        val before = file.readText()

        val result = writer(engineDown).append(projectId, file, "2026-01-02 note Assets:Cash \"x\"")

        assertEquals(before, file.readText(), "the project changed even though nothing could be checked")
        assertTrue(result !is WriteResult.Committed, "an unverifiable write was committed: $result")
    }
}
