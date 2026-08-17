package dev.beancount.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.engine.local.LedgerReader
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mapping from Beancount's output into domain models, checked on a real ledger:
 * amounts must arrive as exact decimals, errors must carry their positions, and the
 * account tree must be derivable from the names alone.
 */
@RunWith(AndroidJUnit4::class)
class LedgerReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)

    private fun writeLedger(name: String, content: String): String {
        val dir = File(context.cacheDir, "reader-$name").apply {
            deleteRecursively()
            mkdirs()
        }
        val main = File(dir, "main.beancount")
        main.writeText(content)
        return main.absolutePath
    }

    @Test
    fun amounts_survive_as_exact_decimals() {
        val path = writeLedger(
            "decimals",
            """
            option "title" "Decimals"
            2024-01-01 open Assets:Bank:Checking USD
            2024-01-01 open Expenses:Food USD
            2024-01-01 open Equity:Opening-Balances USD

            2024-01-01 * "Opening"
              Assets:Bank:Checking     1000.00 USD
              Equity:Opening-Balances

            2024-01-02 * "Grocer" "Exact cents" #food
              Expenses:Food               0.10 USD
              Assets:Bank:Checking

            2024-01-03 * "Grocer" "More cents"
              Expenses:Food               0.20 USD
              Assets:Bank:Checking
            """.trimIndent(),
        )

        val snapshot = runBlocking { reader.read(path) }.getOrThrow()

        val food = snapshot.accounts.single { it.name == "Expenses:Food" }
        val total = food.positions.single()
        // 0.10 + 0.20 is exactly 0.30 — the value never passed through a Double.
        assertEquals(0, total.number.compareTo(BigDecimal("0.30")))
        assertEquals("USD", total.currency)

        assertEquals("Decimals", snapshot.title)
        assertEquals(3, snapshot.journal.size)
        assertTrue(snapshot.journal.first().tags.isEmpty() || snapshot.journal.any { it.tags == listOf("food") })
    }

    @Test
    fun errors_carry_a_file_and_line_the_user_can_act_on() {
        val path = writeLedger(
            "errors",
            """
            option "title" "Errors"
            2024-01-01 open Assets:Bank:Checking USD
            2024-01-01 open Expenses:Food USD

            2024-01-05 * "Grocer" "Does not balance"
              Expenses:Food                42.30 USD
              Assets:Bank:Checking        -40.00 USD
            """.trimIndent(),
        )

        val snapshot = runBlocking { reader.read(path) }.getOrThrow()

        // A ledger with errors still produces a usable snapshot.
        assertTrue(snapshot.hasErrors)
        val error = snapshot.errors.first()
        assertNotNull("error must report its file", error.file)
        assertNotNull("error must report its line", error.line)
        assertEquals("main.beancount:5", error.position)
        assertTrue(error.message.contains("does not balance"))
        assertTrue("entries should still be present", snapshot.entryCount > 0)
    }

    @Test
    fun account_tree_and_totals_come_out_of_the_names() {
        val workspace = ProjectWorkspace(File(context.cacheDir, "tree-test").apply { deleteRecursively() })
        val main = workspace.createProject("tree", title = "Tree")
        main.appendText(
            """

            2026-01-02 * "Employer" "Salary"
              Assets:Bank:Checking     2500.00 USD
              Income:Salary

            2026-01-03 * "Landlord" "Rent"
              Expenses:Home:Rent       1200.00 USD
              Assets:Bank:Checking
            """.trimIndent(),
        )

        val snapshot = runBlocking { reader.read(main.absolutePath) }.getOrThrow()

        val checking = snapshot.accounts.single { it.name == "Assets:Bank:Checking" }
        assertEquals("Checking", checking.leaf)
        assertEquals(2, checking.depth)
        assertEquals(0, checking.positions.single().number.compareTo(BigDecimal("1300.00")))

        // Totals for a subtree are a prefix match, no separate hierarchy needed.
        val assets = snapshot.totalFor("Assets").single()
        assertEquals(0, assets.number.compareTo(BigDecimal("1300.00")))
        val expenses = snapshot.totalFor("Expenses").single()
        assertEquals(0, expenses.number.compareTo(BigDecimal("1200.00")))
    }

    @Test
    fun journal_is_newest_first_for_display() {
        val workspace = ProjectWorkspace(File(context.cacheDir, "order-test").apply { deleteRecursively() })
        val main = workspace.createProject("order", title = "Order")
        main.appendText(
            """

            2026-01-02 * "First" "Older"
              Expenses:Food:Groceries    10.00 USD
              Assets:Cash

            2026-01-09 * "Second" "Newer"
              Expenses:Food:Groceries    20.00 USD
              Assets:Cash
            """.trimIndent(),
        )

        val snapshot = runBlocking { reader.read(main.absolutePath) }.getOrThrow()

        assertEquals(2, snapshot.journal.size)
        assertEquals("2026-01-09", snapshot.journal.first().date)
        assertEquals("Second · Newer", snapshot.journal.first().description)
    }
}
