package io.github.pintofi.beanbook.app.mirror

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Mirror page shows the last ten things it did, per project. */
class MirrorHistoryTest {

    private fun entry(at: Long, path: String) = MirrorHistoryEntry(at, MirrorHistoryEntry.Kind.SENT, path)

    @Test
    fun `only the newest ten are kept, newest first, and survive a restart`() {
        val dir = createTempDirectory("hist").toFile()
        val history = MirrorHistory(dir)
        history.use("hmw")

        history.record((1L..14L).map { entry(it, "f$it.beancount") })

        assertEquals(10, history.entries.value.size)
        assertEquals("f14.beancount", history.entries.value.first().detail)
        assertEquals("f5.beancount", history.entries.value.last().detail)

        val reopened = MirrorHistory(dir)
        reopened.use("hmw")
        assertEquals(history.entries.value, reopened.entries.value)
    }

    @Test
    fun `each project has its own history`() {
        val history = MirrorHistory(createTempDirectory("hist").toFile())
        history.use("a")
        history.record(listOf(entry(1, "a.beancount")))

        history.use("b")

        assertEquals(emptyList<MirrorHistoryEntry>(), history.entries.value)
    }

    @Test
    fun `the same failure again is not listed twice`() {
        val history = MirrorHistory(createTempDirectory("hist").toFile())
        history.use("a")
        val failed = MirrorHistoryEntry(1, MirrorHistoryEntry.Kind.FAILED, "files/accounts.beancount: refused")

        history.record(listOf(failed))
        history.record(listOf(failed.copy(atEpochMs = 2)))

        assertEquals(1, history.entries.value.size)
    }

    @Test
    fun `a file sent again is listed again, newest first`() {
        val history = MirrorHistory(createTempDirectory("hist").toFile())
        history.use("a")

        history.record(listOf(entry(1, "files/transactions.beancount")))
        history.record(listOf(entry(2, "files/transactions.beancount")))

        assertEquals(listOf(2L, 1L), history.entries.value.map { it.atEpochMs })
    }

    @Test
    fun `a conflict that stays is listed once`() {
        val history = MirrorHistory(createTempDirectory("hist").toFile())
        history.use("a")
        val kept = MirrorHistoryEntry(1, MirrorHistoryEntry.Kind.CONFLICT, "prices/SAR.beancount")

        history.record(listOf(kept))
        history.record(listOf(kept.copy(atEpochMs = 2)))

        assertEquals(1, history.entries.value.size)
    }
}
