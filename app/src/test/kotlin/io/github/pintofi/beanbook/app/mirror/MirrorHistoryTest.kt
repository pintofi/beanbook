package io.github.pintofi.beanbook.app.mirror

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Mirror page shows the last ten things it did, per project (B102). */
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
}
