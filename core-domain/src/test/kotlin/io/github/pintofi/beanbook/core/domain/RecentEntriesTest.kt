package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "Recent entries" list on the home screen.
 *
 * Found by looking at the app with a real ledger in it: the list was headed "Recent entries",
 * captioned "showing the last 5", and showed the five *oldest*. Every layer was individually
 * defensible — the engine reports the last 200 in file order, oldest first; the reader
 * reverses them so the newest is first; the screen then called `takeLast(5).asReversed()`,
 * which on an already-reversed list walks back to the beginning of time.
 *
 * Tested here rather than in the composable so the ordering has an answer that does not
 * depend on rendering anything.
 */
class RecentEntriesTest {

    private fun entry(date: String) = JournalEntry(
        date = date,
        flag = "*",
        payee = null,
        narration = date,
        tags = emptyList(),
        links = emptyList(),
        postings = emptyList(),
        file = null,
        line = null,
    )

    /** As the reader delivers it: newest first. */
    private val snapshot = LedgerSnapshot(
        title = null,
        operatingCurrencies = emptyList(),
        entryCount = 5,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = listOf("2026-01-22", "2026-01-18", "2026-01-12", "2026-01-09", "2026-01-01")
            .map { entry(it) },
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test
    fun `takes the newest entries, not the oldest`() {
        assertEquals(
            listOf("2026-01-22", "2026-01-18", "2026-01-12"),
            snapshot.recentEntries(3).map { it.date },
        )
    }

    @Test
    fun `keeps them newest first, as the journal is read`() {
        val dates = snapshot.recentEntries(5).map { it.date }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test
    fun `asking for more than there are returns what there is`() {
        assertEquals(5, snapshot.recentEntries(50).size)
    }

    @Test
    fun `an empty journal yields nothing rather than failing`() {
        assertEquals(emptyList(), snapshot.copy(journal = emptyList()).recentEntries(5))
    }
}
