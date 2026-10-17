package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.engine.LedgerState
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reloading an open ledger picks up a change made outside the app.
 *
 * This is the whole point of pull-to-refresh here, and it is worth an end-to-end test rather
 * than a test of the gesture: the app has no file watcher, so a mirror pull, a restore, or an
 * edit made on a desktop inside a synced folder leaves the screen stale with nothing to say
 * so. What must hold is that asking again actually re-reads *from disk* — a reload that
 * returned a cached snapshot would pass any test of the gesture and still show the user
 * yesterday's figures.
 *
 * A real [LedgerReader] and a real engine, because a fake reader would only prove that a
 * fake was called twice.
 */
@RunWith(AndroidJUnit4::class)
class LedgerReloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(context.cacheDir, "reload-${System.nanoTime()}")

    @After fun clean() {
        dir.deleteRecursively()
    }

    private val opening = """
        option "operating_currency" "USD"
        2024-01-01 open Assets:Cash USD
        2024-01-01 open Expenses:Food USD

        2024-01-02 * "Grocer" "One"
          Expenses:Food      1.00 USD
          Assets:Cash
    """.trimIndent()

    @Test fun reloadingPicksUpAnEditMadeOutsideTheApp() {
        dir.mkdirs()
        val main = File(dir, "main.beancount").apply { writeText(opening + "\n") }

        val state = LedgerState(LedgerReader(context), scope)
        state.load(main.absolutePath)

        val before = runBlocking {
            withTimeout(TIMEOUT) { state.snapshot.first { it != null } }
        }!!
        assertEquals("the fixture did not load", 1, before.journal.size)

        // The edit the app cannot see coming: another process appending to the file, which
        // is what a mirror pull or a desktop edit in a synced folder looks like from here.
        main.appendText(
            "\n2024-01-03 * \"Grocer\" \"Two\"\n" +
                "  Expenses:Food      2.00 USD\n" +
                "  Assets:Cash\n",
        )

        state.reload()

        val after = runBlocking {
            withTimeout(TIMEOUT) { state.snapshot.first { it != null && it.journal.size > 1 } }
        }!!
        assertEquals("the reload did not re-read the file", 2, after.journal.size)
    }

    /** Nothing open, nothing to re-read — and no guess at a path. */
    @Test fun reloadingBeforeAnythingIsOpenDoesNothing() {
        val state = LedgerState(LedgerReader(context), scope)

        state.reload()

        assertEquals("a reload invented a ledger", null, state.snapshot.value)
        assertFalse("a reload with nothing open left a spinner running", state.loading.value)
    }

    private companion object {
        // The engine may be cold on the first case, and a cold start is seconds not
        // milliseconds.
        const val TIMEOUT = 90_000L
    }
}
