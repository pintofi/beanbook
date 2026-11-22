package io.github.pintofi.beanbook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.EntryFilter
import io.github.pintofi.beanbook.core.domain.ViewAnswer
import io.github.pintofi.beanbook.core.domain.ViewRequest
import io.github.pintofi.beanbook.core.domain.filteredBy
import io.github.pintofi.beanbook.engine.local.LedgerReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path a filter takes on the device: the engine process, Fava's own filter and time classes, the
 * answer file, and the app's mapping of it onto a snapshot of the whole ledger.
 */
@RunWith(AndroidJUnit4::class)
class LedgerViewDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = LedgerReader(context)

    private fun ledgerFile(name: String, content: String): String {
        val dir = File(context.cacheDir, "view-$name").apply {
            deleteRecursively()
            mkdirs()
        }
        return File(dir, "main.beancount").apply { writeText(content) }.absolutePath
    }

    private val ledger = """
        option "operating_currency" "USD"
        2020-01-01 open Assets:Bank
        2020-01-01 open Assets:Broker
        2020-01-01 open Income:Salary
        2020-01-01 open Expenses:Rent
        2020-01-01 open Expenses:Food

        2020-01-05 * "Employer" "salary"
          Assets:Bank          1000.00 USD
          Income:Salary

        2020-02-01 * "Landlord" "Feb rent" #home
          Expenses:Rent         250.00 USD
          Assets:Bank

        2020-02-10 * "Broker" "buy"
          Assets:Broker              2 ACME {50.00 USD}
          Assets:Bank          -100.00 USD

        2020-02-20 price ACME 60.00 USD

        2020-03-01 * "Landlord" "Mar rent" #home
          Expenses:Rent         250.00 USD
          Assets:Bank

        2020-03-05 * "Cafe" "coffee" #trip
          Expenses:Food          10.00 USD
          Assets:Bank
    """.trimIndent()

    private fun view(path: String, request: ViewRequest) = runBlocking { reader.view(path, request) }

    @Test
    fun a_period_shows_balances_at_its_end_with_earlier_history_carried_in() = runBlocking {
        val path = ledgerFile("period", ledger)
        val snapshot = reader.read(path).getOrThrow()
        val request = ViewRequest(time = "2020-03")
        val answer = view(path, request).getOrThrow()

        val ready = answer as ViewAnswer.Ready
        assertEquals(snapshot.inputHash, ready.inputHash)
        val shown = snapshot.filteredBy(EntryFilter(view = request), ready)

        // The whole snapshot is untouched; the shown one is March with the earlier months summarised.
        assertEquals(5, snapshot.journal.size)
        assertTrue(shown.journal.any { it.narration == "Mar rent" })
        assertTrue("Fava's opening-balance rows are included", shown.journal.any { it.file == null })
        val bank = shown.accounts.first { it.name == "Assets:Bank" }
        assertEquals("390.00", bank.positions.single().number.toPlainString())
        val broker = shown.accounts.first { it.name == "Assets:Broker" }
        assertEquals("120.00", broker.atValue.single().number.toPlainString())
    }

    @Test
    fun text_tags_and_amounts_are_favas_and_a_bad_input_is_refused_with_its_field() = runBlocking {
        val path = ledgerFile("inputs", ledger)
        val snapshot = reader.read(path).getOrThrow()

        val home = view(path, ViewRequest(tags = "#home")).getOrThrow() as ViewAnswer.Ready
        assertEquals(2, snapshot.filteredBy(EntryFilter(view = home.request), home).journal.size)

        val notHome = view(path, ViewRequest(tags = "-#home")).getOrThrow() as ViewAnswer.Ready
        assertEquals(3, snapshot.filteredBy(EntryFilter(view = notHome.request), notHome).journal.size)

        val big = view(path, ViewRequest(amount = ">=900")).getOrThrow() as ViewAnswer.Ready
        val bigRows = snapshot.filteredBy(EntryFilter(view = big.request), big).journal
        assertEquals(listOf("salary"), bigRows.map { it.narration })

        val not = view(path, ViewRequest(amount = "-=900")).getOrThrow()
        assertTrue((not as ViewAnswer.Ready).positions.isNotEmpty())

        val bad = view(path, ViewRequest(tags = "#trip,")).getOrThrow() as ViewAnswer.Refused
        assertEquals("tags", bad.field)
        assertTrue(bad.message.isNotBlank())
        val badTime = view(path, ViewRequest(time = "2020-02-30")).getOrThrow() as ViewAnswer.Refused
        assertEquals("time", badTime.field)
    }

    @Test
    fun an_answer_for_other_files_is_never_applied() = runBlocking {
        val path = ledgerFile("stale", ledger)
        val snapshot = reader.read(path).getOrThrow()
        val request = ViewRequest(tags = "#home")
        val answer = view(path, request).getOrThrow()
        File(path).appendText("\n2020-04-01 * \"Late\" \"after\" #home\n  Expenses:Food 1.00 USD\n  Assets:Bank\n")

        val shown = snapshot.filteredBy(EntryFilter(view = request), view(path, request).getOrThrow())

        assertNotNull(answer)
        assertTrue("the snapshot predates the edit, so nothing is shown", shown.journal.isEmpty())
    }

    /** Logs what each kind of view costs on a ledger of 25,000 transactions, and refuses only the cold budget. */
    @Test
    fun views_on_the_large_ledger_are_within_the_cold_load_budget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packaged = instrumentation.context.assets.list("corpus/large").orEmpty()
        assumeTrue("the large corpus is not packaged", packaged.isNotEmpty())
        val folder = File(context.cacheDir, "view-large").apply {
            deleteRecursively()
            mkdirs()
        }
        copyAsset("corpus/large", folder)
        val path = File(folder, "main.beancount").absolutePath
        val cases = listOf(
            "tags" to ViewRequest(tags = "-#recurring"),
            "time" to ViewRequest(time = "2018"),
            "amount" to ViewRequest(amount = ">=500"),
            "amount on one account" to ViewRequest(amount = "any(account:Expenses >=500)"),
        )
        runBlocking {
            for ((name, request) in cases) {
                repeat(2) { round ->
                    val started = System.nanoTime()
                    val answer = view(path, request).getOrThrow()
                    val millis = (System.nanoTime() - started) / 1_000_000
                    Log.i("ViewTiming", "$name round=$round total_ms=$millis")
                    assertTrue("$name refused: $answer", answer is ViewAnswer.Ready)
                    assertTrue("$name took $millis ms", millis < 20_000)
                }
            }
        }
    }

    private fun copyAsset(assetPath: String, target: File) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset("$assetPath/$it", File(target, it)) }
        }
    }
}
